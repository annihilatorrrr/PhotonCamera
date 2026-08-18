//
// Created by eszdman on 16.08.2026.
//
// Single JNI wrapper around ncnn (Vulkan backend) for the two ML models
// used by PhotonCamera:
//
//   * FlowNet-v2 dense optical flow (flownet_flat.ncnn.param/.bin)
//   * KernelNet anisotropic parameter model (kernelnet_aniso_v2_2_params.ncnn.param/.bin)
//
// Both networks share one ncnn runtime linked statically into this library
// (see ncnn/<ABI>/lib/libncnn.a). The FlowNet model requires three custom
// layers (FlownetUpsample / FlownetCorrLookup / FlownetCorrFused) that are
// compiled into this translation unit and registered at runtime via
// Net::register_custom_layer() — no ncnn source modifications needed.
//
// Java side:
//   com.particlesdevs.photoncamera.processing.ml.FlowNetNcnnProcessor
//   com.particlesdevs.photoncamera.processing.ml.KernelNetNcnnProcessor
//
// The library is built as libncnnMl.so and loaded via System.loadLibrary("ncnnMl").
//

#include <jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <android/asset_manager.h>

#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <sys/time.h>
#include <vector>

#include "net.h"
#include "mat.h"

// FlowNet custom layer registration (picks Vulkan or CPU creators based on NCNN_VULKAN)
#include "flownet_register.h"

#ifdef _OPENMP
#include <omp.h>
#endif

#define LOG_TAG "NcnnML"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Pin the OpenMP runtime to a fixed number of threads. No CPU-count / core
// topology probing here — the caller decides the thread count.
static void pinOpenMPThreads(int num_threads) {
#ifdef _OPENMP
    omp_set_dynamic(0);
    omp_set_num_threads(num_threads);
    LOGI("openmp: linked statically, pinned to %d threads", omp_get_max_threads());
#else
    (void)num_threads;
#endif
}

static int64_t nowMs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

// Derive .bin asset path from .param path: "models/foo.ncnn.param" → "models/foo.ncnn.bin"
static std::string paramToBinPath(const std::string& paramPath) {
    std::string binPath = paramPath;
    size_t pos = binPath.rfind(".param");
    if (pos != std::string::npos)
        binPath.replace(pos, 6, ".bin");
    return binPath;
}

// ---------------------------------------------------------------------------
// Shared init (no-op for ncnn; kept for Java compatibility).
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_NcnnMl_nativeEnsureInit(
    JNIEnv*, jclass) {
    return JNI_TRUE;
}

// ===========================================================================
// FlowNet-v2 (fixed 768×432 input)
// ===========================================================================

struct FlowNetCtx {
    ncnn::Net net;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_FlowNetNcnnProcessor_nativeCreate(
    JNIEnv* env, jclass, jobject assetManager, jstring paramPath) {
    int64_t t0 = nowMs();

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        LOGE("AAssetManager_fromJava failed");
        return 0;
    }
    const char* path = env->GetStringUTFChars(paramPath, nullptr);
    if (path == nullptr) {
        LOGE("paramPath null");
        return 0;
    }
    std::string paramStr = path;
    env->ReleaseStringUTFChars(paramPath, path);

    auto* ctx = new (std::nothrow) FlowNetCtx();
    if (ctx == nullptr) {
        LOGE("OOM allocating FlowNetCtx");
        return 0;
    }

    // Vulkan + fp16 for GPU speed. Subgroup ops disabled (crash on Adreno/Mali).
    // fp16 arithmetic engages tensor cores for convs; the custom corr shader
    // keeps its 128-element dot in fp32 regardless (declared support_fp16_storage
    // but the shader uses float accumulators).
    ctx->net.opt.use_vulkan_compute = true;
    ctx->net.opt.use_fp16_packed = true;
    ctx->net.opt.use_fp16_storage = true;
    ctx->net.opt.use_fp16_arithmetic = true;
    ctx->net.opt.use_bf16_storage = false;
    ctx->net.opt.use_subgroup_ops = false;
    ctx->net.opt.num_threads = 4;
    ctx->net.opt.lightmode = false;
    pinOpenMPThreads(ctx->net.opt.num_threads);

    // Allow forcing CPU via env (debug only).
    if (getenv("FLOWNET_CPU") && getenv("FLOWNET_CPU")[0] == '1') {
        ctx->net.opt.use_vulkan_compute = false;
        LOGI("flownet: Vulkan disabled by FLOWNET_CPU=1, using CPU");
    }

    flownet_register_custom_layers(ctx->net);

    std::string binPath = paramToBinPath(paramStr);

    if (ctx->net.load_param(mgr, paramStr.c_str()) != 0) {
        LOGE("flownet load_param(%s) failed", paramStr.c_str());
        delete ctx;
        return 0;
    }
    if (ctx->net.load_model(mgr, binPath.c_str()) != 0) {
        LOGE("flownet load_model(%s) failed", binPath.c_str());
        delete ctx;
        return 0;
    }

    LOGI("flownet init took %lldms (vulkan=%d)", (long long)(nowMs() - t0),
         ctx->net.opt.use_vulkan_compute);
    return (jlong)ctx;
}

// baseRgba/alterRgba: interleaved floats [w*h*4], B,G,R in [0,255] (A unused).
// flowOut: [h*w*2] channel-last floats (flow.x, flow.y) in input-pixel units.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_FlowNetNcnnProcessor_nativeRun(
    JNIEnv* env, jclass, jlong handle, jobject baseRgba, jobject alterRgba,
    jint width, jint height, jobject flowOut) {
    auto* ctx = reinterpret_cast<FlowNetCtx*>(handle);
    if (ctx == nullptr) return JNI_FALSE;
    pinOpenMPThreads(ctx->net.opt.num_threads);

    const float* basePtr = static_cast<const float*>(env->GetDirectBufferAddress(baseRgba));
    const float* alterPtr = static_cast<const float*>(env->GetDirectBufferAddress(alterRgba));
    float* outPtr = static_cast<float*>(env->GetDirectBufferAddress(flowOut));
    if (basePtr == nullptr || alterPtr == nullptr || outPtr == nullptr) {
        LOGE("GetDirectBufferAddress failed");
        return JNI_FALSE;
    }

    const int plane = width * height;

    // Deinterleave RGBA→BGR and create ncnn Mats (CHW float32, values 0..255).
    // The model divides by 255 internally (BinaryOp div in the param graph).
    ncnn::Mat in0(width, height, 3);
    ncnn::Mat in1(width, height, 3);
    {
        float* c0 = (float*)in0.channel(0); // B
        float* c1 = (float*)in0.channel(1); // G
        float* c2 = (float*)in0.channel(2); // R
        float* d0 = (float*)in1.channel(0);
        float* d1 = (float*)in1.channel(1);
        float* d2 = (float*)in1.channel(2);
        for (int i = 0; i < plane; i++) {
            c0[i] = basePtr[4 * i + 0];
            c1[i] = basePtr[4 * i + 1];
            c2[i] = basePtr[4 * i + 2];
            d0[i] = alterPtr[4 * i + 0];
            d1[i] = alterPtr[4 * i + 1];
            d2[i] = alterPtr[4 * i + 2];
        }
    }

    LOGI("flownet input in0 dims=%d w=%d h=%d c=%d elemsize=%zu",
         in0.dims, in0.w, in0.h, in0.c, (size_t)in0.elemsize);

    int64_t tStart = nowMs();

    ncnn::Extractor ex = ctx->net.create_extractor();
    int ret_in0 = ex.input("in0", in0);
    int ret_in1 = ex.input("in1", in1);
    LOGI("flownet input ret: in0=%d in1=%d", ret_in0, ret_in1);

    ncnn::Mat out;
    int ret = ex.extract("out0", out);
    if (ret != 0) {
        LOGE("flownet extract failed ret=%d", ret);
        return JNI_FALSE;
    }

    LOGI("flownet forward %dx%d took %lld ms", width, height,
         (long long)(nowMs() - tStart));

    // Output [2,H,W] channel-major → channel-last [x, y].
    if (out.c < 2 || out.w != width || out.h != height) {
        LOGE("unexpected flownet output dims=%d w=%d h=%d c=%d",
             out.dims, out.w, out.h, out.c);
        return JNI_FALSE;
    }
    const float* flowX = (const float*)out.channel(0);
    const float* flowY = (const float*)out.channel(1);
    for (int i = 0; i < plane; i++) {
        outPtr[2 * i + 0] = flowX[i];
        outPtr[2 * i + 1] = flowY[i];
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_FlowNetNcnnProcessor_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<FlowNetCtx*>(handle);
    if (ctx != nullptr) delete ctx;
}

// ===========================================================================
// KernelNet (dynamic input size; output at half res)
// ===========================================================================

struct KernelNetCtx {
    ncnn::Net net;
    std::vector<float> sigmaPlane;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_KernelNetNcnnProcessor_nativeCreate(
    JNIEnv* env, jclass, jobject assetManager, jstring paramPath) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        LOGE("AAssetManager_fromJava failed");
        return 0;
    }
    const char* path = env->GetStringUTFChars(paramPath, nullptr);
    if (path == nullptr) return 0;
    std::string paramStr = path;
    env->ReleaseStringUTFChars(paramPath, path);

    auto* ctx = new (std::nothrow) KernelNetCtx();
    if (ctx == nullptr) return 0;

    // KernelNet is pure convolutions — no custom layers needed.
    ctx->net.opt.use_vulkan_compute = true;
    ctx->net.opt.use_fp16_packed = true;
    ctx->net.opt.use_fp16_storage = true;
    ctx->net.opt.use_fp16_arithmetic = true;
    ctx->net.opt.use_bf16_storage = false;
    ctx->net.opt.use_subgroup_ops = false;
    ctx->net.opt.num_threads = 4;
    ctx->net.opt.lightmode = false;
    pinOpenMPThreads(ctx->net.opt.num_threads);

    std::string binPath = paramToBinPath(paramStr);

    if (ctx->net.load_param(mgr, paramStr.c_str()) != 0) {
        LOGE("kernelnet load_param(%s) failed", paramStr.c_str());
        delete ctx;
        return 0;
    }
    if (ctx->net.load_model(mgr, binPath.c_str()) != 0) {
        LOGE("kernelnet load_model(%s) failed", binPath.c_str());
        delete ctx;
        return 0;
    }

    LOGI("kernelnet model loaded");
    return (jlong)ctx;
}

// gray: [w*h] luma floats in [0,1]. sigma is a scalar noise estimate, tiled to
// a full-res plane (the graph takes two 1-channel inputs). Output at half res:
// channel-major [s1 plane][s2 plane][rho plane], each (outH*outW).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_KernelNetNcnnProcessor_nativeRun(
    JNIEnv* env, jclass, jlong handle, jobject grayBuffer, jint width, jint height,
    jfloat sigma, jobject outBuffer) {
    auto* ctx = reinterpret_cast<KernelNetCtx*>(handle);
    if (ctx == nullptr) return JNI_FALSE;
    pinOpenMPThreads(ctx->net.opt.num_threads);

    const float* grayPtr = static_cast<const float*>(env->GetDirectBufferAddress(grayBuffer));
    float* outPtr = static_cast<float*>(env->GetDirectBufferAddress(outBuffer));
    if (grayPtr == nullptr || outPtr == nullptr) {
        LOGE("GetDirectBufferAddress failed");
        return JNI_FALSE;
    }

    const int plane = width * height;

    // Create ncnn Mats. Gray is 1-channel [0,1]; sigma is tiled to 1-channel.
    ncnn::Mat gray(width, height, 1);
    memcpy((float*)gray.data, grayPtr, (size_t)plane * sizeof(float));

    ctx->sigmaPlane.resize(static_cast<size_t>(plane));
    float* sp = ctx->sigmaPlane.data();
    for (int i = 0; i < plane; i++) sp[i] = sigma;
    ncnn::Mat sigmaMat(width, height, 1);
    memcpy((float*)sigmaMat.data, sp, (size_t)plane * sizeof(float));

    int64_t tStart = nowMs();

    ncnn::Extractor ex = ctx->net.create_extractor();
    ex.input("in0", gray);
    ex.input("in1", sigmaMat);

    ncnn::Mat out;
    if (ex.extract("out0", out) != 0) {
        LOGE("kernelnet extract failed");
        return JNI_FALSE;
    }

    LOGI("kernelnet forward %dx%d took %lld ms", width, height,
         (long long)(nowMs() - tStart));

    // Output [3, outH, outW] channel-major → flat [s1][s2][rho].
    if (out.c != 3) {
        LOGE("unexpected kernelnet output dims=%d w=%d h=%d c=%d",
             out.dims, out.w, out.h, out.c);
        return JNI_FALSE;
    }
    const int outPlane = out.w * out.h;
    for (int c = 0; c < 3; c++) {
        const float* ch = (const float*)out.channel(c);
        memcpy(outPtr + c * outPlane, ch, (size_t)outPlane * sizeof(float));
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_KernelNetNcnnProcessor_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<KernelNetCtx*>(handle);
    if (ctx != nullptr) delete ctx;
}
