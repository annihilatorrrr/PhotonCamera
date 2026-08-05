//
// Created by eszdman on 05.08.2026.
//
// JNI wrapper around ncnn for the KernelNet anisotropic parameter model.
// Mirrors the interface of KernelNetProcessor (ONNX Runtime) so the two
// backends can be swapped for comparison. Runs on Vulkan when available and
// falls back to CPU.
//
// Model (kernelnet_aniso_v2_params.ncnn.param/.bin):
//   in0  (W,H,1)  luma plane, values in [0,1]
//   in1  (W,H,1)  noise sigma plane (full-res; ncnn can't broadcast a scalar
//                  into the concat the way ONNX does, so it is tiled)
//   out0 (W/2,H/2,3) channel-major s1, s2, rho
//

#include <jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <android/asset_manager.h>

#include <cstring>
#include <mutex>
#include <new>
#include <string>

#include "net.h"
#include "mat.h"
#include "gpu.h"

#define LOG_TAG "KernelNetNCNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct KernelNet {
    ncnn::Net net;
    std::string inGray;
    std::string inSigma;
    std::string outName;
};

// GPU instance is a process-global singleton in ncnn; create it once and keep
// it alive for the lifetime of the process (there is no clean teardown point).
std::once_flag g_gpuOnce;
bool g_gpuAvailable = false;

void ensureGpu() {
    std::call_once(g_gpuOnce, [] {
        int ret = ncnn::create_gpu_instance();
        g_gpuAvailable = ret == 0 && ncnn::get_gpu_count() > 0;
        LOGI("Vulkan instance ret=%d gpu_count=%d", ret, ncnn::get_gpu_count());
    });
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_KernelNetNCNNProcessor_nativeCreate(
        JNIEnv *env, jclass, jobject assetManager, jstring paramPath, jstring binPath,
        jstring inGrayName, jstring inSigmaName, jstring outName) {
    ensureGpu();

    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        LOGE("AAssetManager_fromJava failed");
        return 0;
    }

    const char *param = env->GetStringUTFChars(paramPath, nullptr);
    const char *bin = env->GetStringUTFChars(binPath, nullptr);
    const char *inGray = env->GetStringUTFChars(inGrayName, nullptr);
    const char *inSigma = env->GetStringUTFChars(inSigmaName, nullptr);
    const char *out = env->GetStringUTFChars(outName, nullptr);

    auto *k = new (std::nothrow) KernelNet();
    if (k == nullptr) {
        env->ReleaseStringUTFChars(paramPath, param);
        env->ReleaseStringUTFChars(binPath, bin);
        env->ReleaseStringUTFChars(inGrayName, inGray);
        env->ReleaseStringUTFChars(inSigmaName, inSigma);
        env->ReleaseStringUTFChars(outName, out);
        return 0;
    }

    std::string paramPathStr = param != nullptr ? param : "";
    std::string binPathStr = bin != nullptr ? bin : "";
    k->inGray = inGray != nullptr ? inGray : "";
    k->inSigma = inSigma != nullptr ? inSigma : "";
    k->outName = out != nullptr ? out : "";

    env->ReleaseStringUTFChars(paramPath, param);
    env->ReleaseStringUTFChars(binPath, bin);
    env->ReleaseStringUTFChars(inGrayName, inGray);
    env->ReleaseStringUTFChars(inSigmaName, inSigma);
    env->ReleaseStringUTFChars(outName, out);

    ncnn::Net &net = k->net;
    net.opt.num_threads = 4;
    net.opt.use_vulkan_compute = g_gpuAvailable;
    // fp16 packing/storage is much faster on Vulkan and matches the ONNX
    // NNAPI fp16 path; arithmetic stays fp32 for accuracy.
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = false;

    if (net.load_param(mgr, paramPathStr.c_str()) != 0) {
        LOGE("load_param failed: %s", paramPathStr.c_str());
        delete k;
        return 0;
    }
    if (net.load_model(mgr, binPathStr.c_str()) != 0) {
        LOGE("load_model failed: %s", binPathStr.c_str());
        delete k;
        return 0;
    }

    LOGI("net loaded (vulkan=%d)", net.opt.use_vulkan_compute);
    return (jlong) k;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_KernelNetNCNNProcessor_nativeRun(
        JNIEnv *env, jclass, jlong handle, jobject grayBuffer, jint width, jint height,
        jfloat sigma, jobject outBuffer) {
    auto *k = reinterpret_cast<KernelNet *>(handle);
    if (k == nullptr) return JNI_FALSE;

    const float *grayPtr = static_cast<const float *>(env->GetDirectBufferAddress(grayBuffer));
    float *outPtr = static_cast<float *>(env->GetDirectBufferAddress(outBuffer));
    if (grayPtr == nullptr || outPtr == nullptr) {
        LOGE("GetDirectBufferAddress failed");
        return JNI_FALSE;
    }

    const int plane = width * height;

    ncnn::Mat gray(width, height, 1);
    std::memcpy(gray.data, grayPtr, (size_t) plane * sizeof(float));

    // ncnn can't broadcast a scalar sigma into the concat, so tile it to a
    // full-resolution plane (the model was converted this way).
    ncnn::Mat sigmaPlane(width, height, 1);
    float *sp = static_cast<float *>(sigmaPlane.data);
    for (int i = 0; i < plane; i++) sp[i] = sigma;

    ncnn::Extractor ex = k->net.create_extractor();
    if (ex.input(k->inGray.c_str(), gray) != 0) {
        LOGE("input %s failed", k->inGray.c_str());
        return JNI_FALSE;
    }
    if (ex.input(k->inSigma.c_str(), sigmaPlane) != 0) {
        LOGE("input %s failed", k->inSigma.c_str());
        return JNI_FALSE;
    }

    ncnn::Mat feat;
    // type 0 (default) converts fp16/packed output back to fp32 float.
    if (ex.extract(k->outName.c_str(), feat) != 0) {
        LOGE("extract %s failed", k->outName.c_str());
        return JNI_FALSE;
    }

    const int outW = (width - 1) / 2 + 1;
    const int outH = (height - 1) / 2 + 1;
    const int outPlane = outW * outH;
    if (feat.c < 3 || (int) feat.total() < 3 * outPlane) {
        LOGE("unexpected output %dx%dx%d", feat.w, feat.h, feat.c);
        return JNI_FALSE;
    }

    // Channel-major copy into the direct buffer: [s1 plane][s2 plane][rho plane].
    const float *fp = static_cast<const float *>(feat.data);
    std::memcpy(outPtr, fp, (size_t) outPlane * sizeof(float));                       // s1
    std::memcpy(outPtr + outPlane, fp + feat.cstep, (size_t) outPlane * sizeof(float));  // s2
    std::memcpy(outPtr + 2 * outPlane, fp + 2 * feat.cstep, (size_t) outPlane * sizeof(float)); // rho

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_KernelNetNCNNProcessor_nativeDestroy(
        JNIEnv *, jclass, jlong handle) {
    auto *k = reinterpret_cast<KernelNet *>(handle);
    if (k != nullptr) delete k;
}
