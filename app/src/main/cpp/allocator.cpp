//
// Created by eszdman on 03.06.2025.
//

#include <jni.h>
#include <malloc.h>
#include <string.h>
#include "android/log.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "Allocator", __VA_ARGS__)

long memoryCount = 0;

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
    memoryCount += capacity;
    return buffer;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_particlesdevs_photoncamera_util_Allocator_allocateAndCopy(JNIEnv *env, jclass clazz,
                                                            jint capacity, jobject originBuffer) {
    // Allocate a direct ByteBuffer of the specified size
    void* allocation = malloc(capacity);
    jobject buffer = env->NewDirectByteBuffer(allocation, capacity);
    if (buffer == nullptr) {
        // Handle allocation failure
        LOGD("Failed to allocate buffer of size %ld", capacity);
        if (allocation != nullptr) {
            free(allocation);
        }
        return nullptr;
    }
    void* ptr = env->GetDirectBufferAddress(originBuffer);
    if (ptr == nullptr) {
        LOGD("Failed to get direct buffer address of originBuffer, disabling copying");
        return buffer;
    } else {
        // Copy the contents of the original buffer to the new buffer
        memcpy(allocation, ptr, capacity);
        LOGD("Buffer allocated and copied successfully");
    }
    memoryCount += capacity;
    LOGD("Current memory count: %ld MB", (memoryCount/1024)/1024);
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
    long capacity = env->GetDirectBufferCapacity(buffer);
    if (ptr == nullptr) {
        LOGD("Failed to get direct buffer address");
        return;
    }

    // Free the allocated memory
    free(ptr);
    memoryCount -= capacity;
    LOGD("Buffer freed successfully");
}
