#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include "realcugan.h"
#include "cpu.h"
#include "gpu.h"

#define TAG "RealCuganJni"
#ifndef NDEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void) 0)
#endif
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static RealCUGAN* realcugan = nullptr;

extern "C" JNIEXPORT jlong JNICALL
Java_org_skepsun_kototoro_reader_translate_data_RealCuganNcnnEngine_initNative(JNIEnv *env, jobject thiz, jstring paramPath, jstring binPath, jint gpuId, jboolean ttaMode) {
    const char *param_str = env->GetStringUTFChars(paramPath, nullptr);
    const char *bin_str = env->GetStringUTFChars(binPath, nullptr);

    int gpuid = gpuId;
    if (gpuid >= 0) {
        if (ncnn::get_gpu_count() == 0) gpuid = -1;
    }

    if (realcugan != nullptr) {
        delete realcugan;
        realcugan = nullptr;
    }

    realcugan = new RealCUGAN(gpuid, ttaMode);
    
    // Default model args for RealCUGAN anime models
    // prepadding MUST match the official realcugan-ncnn-vulkan values:
    //   scale=2 → prepadding=18
    //   scale=3 → prepadding=14
    //   scale=4 → prepadding=19
    // Using too-small prepadding (e.g. 3) causes severe tile-seam artifacts
    // (white/yellow bars at tile boundaries) because the model lacks enough
    // context pixels at the edge of each processing tile.
    realcugan->noise = -1;
    realcugan->scale = 2;
    realcugan->syncgap = 3;
    realcugan->prepadding = 18;

    // Auto-detect tilesize based on GPU heap budget.
    // RealCUGAN 2x is less demanding than ESRGAN 4x, but still needs to respect
    // mobile GPU VRAM limits to avoid VK_ERROR_DEVICE_LOST.
    if (gpuid >= 0) {
        uint32_t heap_budget = ncnn::get_gpu_device(gpuid)->get_heap_budget();
        LOGD("GPU %d heap budget: %u MB", gpuid, heap_budget);
        if (heap_budget > 1900)
            realcugan->tilesize = 400;
        else if (heap_budget > 800)
            realcugan->tilesize = 300;
        else if (heap_budget > 400)
            realcugan->tilesize = 200;
        else
            realcugan->tilesize = 100;
        LOGD("Auto-selected tilesize: %d", realcugan->tilesize);
    } else {
        realcugan->tilesize = 400; // CPU mode, no VRAM concern
    }

    int ret = realcugan->load(param_str, bin_str);

    env->ReleaseStringUTFChars(paramPath, param_str);
    env->ReleaseStringUTFChars(binPath, bin_str);

    if (ret != 0) {
        LOGE("Failed to load RealCUGAN models.");
        delete realcugan;
        realcugan = nullptr;
        return 0;
    }
    LOGD("RealCUGAN initialized successfully on GPU %d", gpuid);
    return reinterpret_cast<jlong>(realcugan);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_skepsun_kototoro_reader_translate_data_RealCuganNcnnEngine_processNative(JNIEnv *env, jobject thiz, jlong nativeHandle, jobject inBitmap, jobject outBitmap) {
    auto* cugan = reinterpret_cast<RealCUGAN*>(nativeHandle);
    if (!cugan) return JNI_FALSE;

    AndroidBitmapInfo inInfo, outInfo;
    AndroidBitmap_getInfo(env, inBitmap, &inInfo);
    AndroidBitmap_getInfo(env, outBitmap, &outInfo);

    // Validate that both bitmaps are RGBA_8888, otherwise the 4-byte-per-pixel
    // assumption breaks (e.g. RGB_565 = 2 bytes/pixel → garbled output).
    if (inInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap format mismatch: in=%d out=%d, need RGBA_8888(%d)",
             inInfo.format, outInfo.format, ANDROID_BITMAP_FORMAT_RGBA_8888);
        return JNI_FALSE;
    }

    void* inPixels;
    void* outPixels;
    AndroidBitmap_lockPixels(env, inBitmap, &inPixels);
    AndroidBitmap_lockPixels(env, outBitmap, &outPixels);

    // NCNN's RealCUGAN wrapper expects tightly packed flat buffers without padding.
    // Android bitmaps can have stride padding, so we must pack them densely if stride != width * 4.
    bool inNeedsCopy = (inInfo.stride != inInfo.width * 4);
    bool outNeedsCopy = (outInfo.stride != outInfo.width * 4);

    uint8_t* denseIn = (uint8_t*)inPixels;
    if (inNeedsCopy) {
        denseIn = new uint8_t[inInfo.width * inInfo.height * 4];
        for (uint32_t y = 0; y < inInfo.height; y++) {
            memcpy(denseIn + y * inInfo.width * 4, 
                   (uint8_t*)inPixels + y * inInfo.stride, 
                   inInfo.width * 4);
        }
    }

    uint8_t* denseOut = (uint8_t*)outPixels;
    if (outNeedsCopy) {
        // Value-initialize (zero) the buffer so any pixels the GPU misses
        // remain transparent instead of showing as random garbage colors.
        denseOut = new uint8_t[outInfo.width * outInfo.height * 4]();
    }

    ncnn::Mat inMat(inInfo.width, inInfo.height, denseIn, (size_t)4u, 4);
    ncnn::Mat outMat(outInfo.width, outInfo.height, denseOut, (size_t)4u, 4);
    
    int ret = cugan->process(inMat, outMat);
    if (ret != 0) {
        LOGE("RealCUGAN process() failed with code %d (likely VK_ERROR_DEVICE_LOST)", ret);
    }

    if (outNeedsCopy && ret == 0) {
        for (uint32_t y = 0; y < outInfo.height; y++) {
            memcpy((uint8_t*)outPixels + y * outInfo.stride, 
                   denseOut + y * outInfo.width * 4, 
                   outInfo.width * 4);
        }
    }

    if (inNeedsCopy) delete[] denseIn;
    if (outNeedsCopy) delete[] denseOut;

    AndroidBitmap_unlockPixels(env, inBitmap);
    AndroidBitmap_unlockPixels(env, outBitmap);

    return ret == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_skepsun_kototoro_reader_translate_data_RealCuganNcnnEngine_releaseNative(JNIEnv *env, jobject thiz, jlong nativeHandle) {
    auto* cugan = reinterpret_cast<RealCUGAN*>(nativeHandle);
    if (cugan) {
        delete cugan;
        if (cugan == realcugan) realcugan = nullptr;
        LOGD("RealCUGAN native instance released.");
    }
}
