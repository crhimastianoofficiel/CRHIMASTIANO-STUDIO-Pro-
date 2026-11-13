#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>

namespace {
constexpr int kMaxVoices = 16;
constexpr double kTwoPi = 2.0 * M_PI;
constexpr float kVoiceAmplitude = 0.12f;

class ToneEngine final : public oboe::AudioStreamDataCallback {
public:
    struct Voice {
        std::atomic<float> frequency{440.0f};
        std::atomic<bool> held{false};
        std::atomic<bool> active{false};
        // Phase is owned exclusively by the Oboe callback thread.
        double phase = 0.0;
    };

    bool noteOn(int voiceIndex, float frequencyHz) {
        if (!isValidVoice(voiceIndex) || !std::isfinite(frequencyHz) || frequencyHz <= 0.0f) {
            return false;
        }
        voices_[voiceIndex].frequency.store(frequencyHz, std::memory_order_relaxed);
        voices_[voiceIndex].held.store(true, std::memory_order_release);
        voices_[voiceIndex].active.store(true, std::memory_order_release);
        return true;
    }

    void noteOff(int voiceIndex) {
        if (!isValidVoice(voiceIndex)) return;
        auto& voice = voices_[voiceIndex];
        voice.held.store(false, std::memory_order_release);
        if (!sustain_.load(std::memory_order_acquire)) {
            voice.active.store(false, std::memory_order_release);
        }
    }

    void setSustain(bool enabled) {
        sustain_.store(enabled, std::memory_order_release);
        if (!enabled) {
            for (auto& voice : voices_) {
                if (!voice.held.load(std::memory_order_acquire)) {
                    voice.active.store(false, std::memory_order_release);
                }
            }
        }
    }

    void setVolume(float volume) {
        if (std::isfinite(volume)) {
            volume_.store(std::clamp(volume, 0.0f, 1.0f), std::memory_order_relaxed);
        }
    }

    void stopAll() {
        for (auto& voice : voices_) {
            voice.held.store(false, std::memory_order_release);
            voice.active.store(false, std::memory_order_release);
        }
        sustain_.store(false, std::memory_order_release);
    }

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames
    ) override {
        auto* output = static_cast<float*>(audioData);
        const int32_t channelCount = audioStream->getChannelCount();
        const double sampleRate = static_cast<double>(audioStream->getSampleRate());
        if (channelCount <= 0 || sampleRate <= 0.0) {
            return oboe::DataCallbackResult::Stop;
        }
        const float masterVolume = volume_.load(std::memory_order_relaxed);

        for (int32_t frame = 0; frame < numFrames; ++frame) {
            float mixedSample = 0.0f;
            int activeVoiceCount = 0;
            for (auto& voice : voices_) {
                if (!voice.active.load(std::memory_order_acquire)) continue;
                const float frequency = voice.frequency.load(std::memory_order_relaxed);
                const double safeFrequency = std::min(static_cast<double>(frequency), sampleRate * 0.45);
                mixedSample += static_cast<float>(std::sin(voice.phase));
                voice.phase += kTwoPi * safeFrequency / sampleRate;
                if (voice.phase >= kTwoPi) voice.phase -= kTwoPi;
                ++activeVoiceCount;
            }

            if (activeVoiceCount > 0) {
                mixedSample *= kVoiceAmplitude / std::sqrt(static_cast<float>(activeVoiceCount));
            }
            mixedSample = std::clamp(mixedSample * masterVolume, -0.8f, 0.8f);
            for (int32_t channel = 0; channel < channelCount; ++channel) {
                output[frame * channelCount + channel] = mixedSample;
            }
        }
        return oboe::DataCallbackResult::Continue;
    }

private:
    static bool isValidVoice(int voiceIndex) {
        return voiceIndex >= 0 && voiceIndex < kMaxVoices;
    }

    std::array<Voice, kMaxVoices> voices_{};
    std::atomic<float> volume_{0.8f};
    std::atomic<bool> sustain_{false};
};

ToneEngine gEngine;
std::shared_ptr<oboe::AudioStream> gStream;
std::mutex gStreamMutex;

bool ensureStreamStarted() {
    std::lock_guard<std::mutex> lock(gStreamMutex);
    if (gStream && gStream->getState() == oboe::StreamState::Started) return true;
    if (gStream) {
        gStream->close();
        gStream.reset();
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(1)
        ->setDataCallback(&gEngine);

    oboe::Result result = builder.openStream(gStream);
    if (result != oboe::Result::OK) {
        gStream.reset();
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(gStream);
    }
    if (result != oboe::Result::OK || !gStream) {
        gStream.reset();
        return false;
    }
    if (gStream->requestStart() != oboe::Result::OK) {
        gStream->close();
        gStream.reset();
        return false;
    }
    return true;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeNoteOn(
    JNIEnv*, jobject, jint voiceIndex, jfloat frequencyHz
) {
    if (voiceIndex < 0 || voiceIndex >= kMaxVoices || !std::isfinite(frequencyHz) || frequencyHz <= 0.0f) {
        return JNI_FALSE;
    }
    if (!ensureStreamStarted()) return JNI_FALSE;
    return gEngine.noteOn(voiceIndex, frequencyHz) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeNoteOff(
    JNIEnv*, jobject, jint voiceIndex
) {
    gEngine.noteOff(voiceIndex);
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeSetVolume(
    JNIEnv*, jobject, jfloat volume
) {
    gEngine.setVolume(volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeSetSustain(
    JNIEnv*, jobject, jboolean enabled
) {
    gEngine.setSustain(enabled == JNI_TRUE);
}

// Preserve the original single-note JNI entry points for compatibility.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeStart(
    JNIEnv*, jobject, jfloat frequencyHz
) {
    if (!std::isfinite(frequencyHz) || frequencyHz <= 0.0f || !ensureStreamStarted()) return JNI_FALSE;
    return gEngine.noteOn(0, frequencyHz) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeSetFrequency(
    JNIEnv*, jobject, jfloat frequencyHz
) {
    if (std::isfinite(frequencyHz) && frequencyHz > 0.0f) gEngine.noteOn(0, frequencyHz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeStop(
    JNIEnv*, jobject
) {
    gEngine.stopAll();
}

extern "C" JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM*, void*) {
    gEngine.stopAll();
    std::lock_guard<std::mutex> lock(gStreamMutex);
    if (gStream) {
        gStream->requestStop();
        gStream->close();
        gStream.reset();
    }
}
