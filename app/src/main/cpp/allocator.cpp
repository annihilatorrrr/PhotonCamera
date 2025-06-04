//
// Created by eszdman on 03.06.2025.
//

#include <jni.h>
#include <malloc.h>
#include "android/log.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "Allocator", __VA_ARGS__)

extern "C"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_allocate(JNIEnv *env, jclass clazz,
                                                            jint capacity) {
    // Allocate a direct ByteBuffer of the specified size
    jobject buffer = env->NewDirectByteBuffer(malloc(capacity), capacity);
    if (buffer == nullptr) {
        // Handle allocation failure
        LOGD("Failed to allocate buffer of size %ld", capacity);
        return nullptr;
    }
    return buffer;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_free(JNIEnv *env, jclass clazz,
                                                            jobject buffer) {
    if (buffer == nullptr) {
        LOGD("Buffer is null, nothing to free");
        return;
    }

    // Get the address of the allocated memory
    void* ptr = env->GetDirectBufferAddress(buffer);
    if (ptr == nullptr) {
        LOGD("Failed to get direct buffer address");
        return;
    }

    // Free the allocated memory
    free(ptr);
    LOGD("Buffer freed successfully");
}
