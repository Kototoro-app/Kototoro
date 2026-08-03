#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <new>
#include <utility>
#include <vector>

namespace {

constexpr double kLanczosRadius = 3.0;

struct Contribution {
    int index;
    double weight;
};

double sinc(double value) {
    if (std::abs(value) < 1e-9) return 1.0;
    const double scaled = M_PI * value;
    return std::sin(scaled) / scaled;
}

double lanczos(double value) {
    value = std::abs(value);
    if (value >= kLanczosRadius) return 0.0;
    return sinc(value) * sinc(value / kLanczosRadius);
}

std::vector<std::vector<Contribution>> buildContributions(int sourceSize, int targetSize) {
    const double scale = static_cast<double>(targetSize) / sourceSize;
    const double kernelScale = std::min(scale, 1.0);
    const double support = kLanczosRadius / kernelScale;
    std::vector<std::vector<Contribution>> table(targetSize);
    for (int target = 0; target < targetSize; ++target) {
        const double center = (target + 0.5) / scale - 0.5;
        const int first = static_cast<int>(std::ceil(center - support));
        const int last = static_cast<int>(std::floor(center + support));
        double total = 0.0;
        auto& contributions = table[target];
        contributions.reserve(last - first + 1);
        for (int source = first; source <= last; ++source) {
            const double weight = lanczos((center - source) * kernelScale);
            if (weight == 0.0) continue;
            contributions.push_back({std::clamp(source, 0, sourceSize - 1), weight});
            total += weight;
        }
        if (std::abs(total) > 1e-9) {
            for (auto& contribution : contributions) contribution.weight /= total;
        }
    }
    return table;
}

uint8_t clampChannel(double value) {
    return static_cast<uint8_t>(std::clamp(std::lround(value), 0L, 255L));
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_skepsun_kototoro_reader_ui_compose_ReaderLanczosScaler_resizeNative(
        JNIEnv* env,
        jobject,
        jobject inputBitmap,
        jobject outputBitmap) {
    AndroidBitmapInfo inputInfo{};
    AndroidBitmapInfo outputInfo{};
    if (AndroidBitmap_getInfo(env, inputBitmap, &inputInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, outputBitmap, &outputInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        inputInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        outputInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        inputInfo.width == 0 || inputInfo.height == 0 ||
        outputInfo.width == 0 || outputInfo.height == 0) {
        return JNI_FALSE;
    }

    void* inputPixels = nullptr;
    void* outputPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, inputBitmap, &inputPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, outputBitmap, &outputPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, inputBitmap);
        return JNI_FALSE;
    }

    bool success = true;
    try {
        const auto horizontal = buildContributions(inputInfo.width, outputInfo.width);
        const auto vertical = buildContributions(inputInfo.height, outputInfo.height);
        std::vector<uint8_t> intermediate(
                static_cast<size_t>(outputInfo.width) * inputInfo.height * 4);
        const auto* input = static_cast<const uint8_t*>(inputPixels);

#pragma omp parallel for schedule(static)
        for (int y = 0; y < static_cast<int>(inputInfo.height); ++y) {
            const auto* sourceRow = input + static_cast<size_t>(y) * inputInfo.stride;
            auto* targetRow = intermediate.data() + static_cast<size_t>(y) * outputInfo.width * 4;
            for (int x = 0; x < static_cast<int>(outputInfo.width); ++x) {
                double channels[4] = {0.0, 0.0, 0.0, 0.0};
                for (const auto& contribution : horizontal[x]) {
                    const auto* pixel = sourceRow + contribution.index * 4;
                    for (int channel = 0; channel < 4; ++channel) {
                        channels[channel] += pixel[channel] * contribution.weight;
                    }
                }
                for (int channel = 0; channel < 4; ++channel) {
                    targetRow[x * 4 + channel] = clampChannel(channels[channel]);
                }
            }
        }

        auto* output = static_cast<uint8_t*>(outputPixels);
#pragma omp parallel for schedule(static)
        for (int y = 0; y < static_cast<int>(outputInfo.height); ++y) {
            auto* targetRow = output + static_cast<size_t>(y) * outputInfo.stride;
            for (int x = 0; x < static_cast<int>(outputInfo.width); ++x) {
                double channels[4] = {0.0, 0.0, 0.0, 0.0};
                for (const auto& contribution : vertical[y]) {
                    const auto* pixel = intermediate.data() +
                            (static_cast<size_t>(contribution.index) * outputInfo.width + x) * 4;
                    for (int channel = 0; channel < 4; ++channel) {
                        channels[channel] += pixel[channel] * contribution.weight;
                    }
                }
                for (int channel = 0; channel < 4; ++channel) {
                    targetRow[x * 4 + channel] = clampChannel(channels[channel]);
                }
            }
        }
    } catch (const std::bad_alloc&) {
        success = false;
    }

    AndroidBitmap_unlockPixels(env, outputBitmap);
    AndroidBitmap_unlockPixels(env, inputBitmap);
    return success ? JNI_TRUE : JNI_FALSE;
}
