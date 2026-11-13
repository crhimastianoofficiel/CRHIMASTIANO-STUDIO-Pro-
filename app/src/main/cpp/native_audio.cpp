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
constexpr int kDrumCount = 4;
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

    void setBeatPattern(int instrument, uint32_t steps) {
        if (instrument >= 0 && instrument < kDrumCount) {
            beatPatterns_[instrument].store(steps & 0xFFFFu, std::memory_order_release);
        }
    }

    void setBeatTempo(int bpm) {
        beatTempo_.store(std::clamp(bpm, 40, 220), std::memory_order_relaxed);
    }

    bool startBeat() {
        beatPlaying_.store(true, std::memory_order_release);
        return true;
    }

    void stopBeat() {
        beatPlaying_.store(false, std::memory_order_release);
    }

    void resetBeat() {
        beatPlaying_.store(false, std::memory_order_release);
        beatStepPublished_.store(-1, std::memory_order_release);
    }

    int currentBeatStep() const {
        return beatStepPublished_.load(std::memory_order_acquire);
    }

    void stopAll() {
        for (auto& voice : voices_) {
            voice.held.store(false, std::memory_order_release);
            voice.active.store(false, std::memory_order_release);
        }
        sustain_.store(false, std::memory_order_release);
        stopBeat();
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
        const bool beatPlaying = beatPlaying_.load(std::memory_order_acquire);
        const int bpm = beatTempo_.load(std::memory_order_relaxed);
        const double framesPerStep = sampleRate * 60.0 / static_cast<double>(bpm) / 4.0;
        const float kickDecay = static_cast<float>(std::exp(-1.0 / (sampleRate * 0.24)));
        const float snareDecay = static_cast<float>(std::exp(-1.0 / (sampleRate * 0.16)));
        const float hatDecay = static_cast<float>(std::exp(-1.0 / (sampleRate * 0.075)));
        const float percDecay = static_cast<float>(std::exp(-1.0 / (sampleRate * 0.20)));

        if (beatPlaying && !beatWasPlaying_) {
            beatWasPlaying_ = true;
            beatStep_ = 0;
            beatFramesRemaining_ = 0.0;
        } else if (!beatPlaying && beatWasPlaying_) {
            beatWasPlaying_ = false;
            beatStep_ = 0;
            beatFramesRemaining_ = 0.0;
            beatStepPublished_.store(-1, std::memory_order_release);
            for (auto& drum : drums_) drum.envelope = 0.0f;
        }

        for (int32_t frame = 0; frame < numFrames; ++frame) {
            if (beatPlaying) {
                if (beatFramesRemaining_ <= 0.0) {
                    beatStepPublished_.store(beatStep_, std::memory_order_release);
                    for (int instrument = 0; instrument < kDrumCount; ++instrument) {
                        const uint32_t mask = beatPatterns_[instrument].load(std::memory_order_acquire);
                        if ((mask & (1u << beatStep_)) != 0u) triggerDrum(instrument);
                    }
                    beatStep_ = (beatStep_ + 1) & 15;
                    beatFramesRemaining_ += framesPerStep;
                }
                beatFramesRemaining_ -= 1.0;
            }

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
            mixedSample += renderDrums(sampleRate, kickDecay, snareDecay, hatDecay, percDecay);
            mixedSample = std::clamp(mixedSample * masterVolume, -0.95f, 0.95f);
            for (int32_t channel = 0; channel < channelCount; ++channel) {
                output[frame * channelCount + channel] = mixedSample;
            }
        }
        return oboe::DataCallbackResult::Continue;
    }

private:
    struct DrumVoice {
        float envelope = 0.0f;
        double phase = 0.0;
        uint32_t noiseState = 0x9E3779B9u;
        float previousNoise = 0.0f;
    };

    static bool isValidVoice(int voiceIndex) {
        return voiceIndex >= 0 && voiceIndex < kMaxVoices;
    }

    float noise(DrumVoice& drum) {
        drum.noiseState ^= drum.noiseState << 13;
        drum.noiseState ^= drum.noiseState >> 17;
        drum.noiseState ^= drum.noiseState << 5;
        return static_cast<float>(drum.noiseState & 0xFFFFu) / 32767.5f - 1.0f;
    }

    void triggerDrum(int instrument) {
        auto& drum = drums_[instrument];
        drum.envelope = 1.0f;
        drum.phase = 0.0;
    }

    float renderDrums(double sampleRate, float kickDecay, float snareDecay, float hatDecay, float percDecay) {
        float sample = 0.0f;
        auto& kick = drums_[0];
        if (kick.envelope > 0.0001f) {
            const double frequency = 42.0 + 145.0 * kick.envelope;
            sample += static_cast<float>(std::sin(kick.phase)) * kick.envelope * 0.78f;
            kick.phase += kTwoPi * frequency / sampleRate;
            if (kick.phase >= kTwoPi) kick.phase -= kTwoPi;
            kick.envelope *= kickDecay;
        }
        auto& snare = drums_[1];
        if (snare.envelope > 0.0001f) {
            const float currentNoise = noise(snare);
            sample += (currentNoise * 0.72f + static_cast<float>(std::sin(snare.phase)) * 0.18f) * snare.envelope * 0.52f;
            snare.phase += kTwoPi * 185.0 / sampleRate;
            if (snare.phase >= kTwoPi) snare.phase -= kTwoPi;
            snare.envelope *= snareDecay;
        }
        auto& hat = drums_[2];
        if (hat.envelope > 0.0001f) {
            const float currentNoise = noise(hat);
            const float highPassed = currentNoise - hat.previousNoise * 0.92f;
            hat.previousNoise = currentNoise;
            sample += highPassed * hat.envelope * 0.34f;
            hat.envelope *= hatDecay;
        }
        auto& percussion = drums_[3];
        if (percussion.envelope > 0.0001f) {
            sample += static_cast<float>(std::sin(percussion.phase)) * percussion.envelope * 0.34f;
            percussion.phase += kTwoPi * 620.0 / sampleRate;
            if (percussion.phase >= kTwoPi) percussion.phase -= kTwoPi;
            percussion.envelope *= percDecay;
        }
        return sample;
    }

    std::array<Voice, kMaxVoices> voices_{};
    std::atomic<float> volume_{0.8f};
    std::atomic<bool> sustain_{false};
    std::array<std::atomic<uint32_t>, kDrumCount> beatPatterns_{};
    std::atomic<int> beatTempo_{120};
    std::atomic<bool> beatPlaying_{false};
    std::atomic<int> beatStepPublished_{-1};
    // These are only accessed on Oboe's callback thread.
    std::array<DrumVoice, kDrumCount> drums_{};
    bool beatWasPlaying_ = false;
    int beatStep_ = 0;
    double beatFramesRemaining_ = 0.0;
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

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeStop(
    JNIEnv*, jobject
) {
    gEngine.stopAll();
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_BeatMakerActivity_nativeSetBeatPattern(
    JNIEnv*, jobject, jint instrument, jint mask
) {
    gEngine.setBeatPattern(instrument, static_cast<uint32_t>(mask));
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_BeatMakerActivity_nativeSetBeatTempo(
    JNIEnv*, jobject, jint bpm
) {
    gEngine.setBeatTempo(bpm);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_crhimastiano_studiopro_BeatMakerActivity_nativeBeatPlay(
    JNIEnv*, jobject
) {
    if (!ensureStreamStarted()) return JNI_FALSE;
    return gEngine.startBeat() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_BeatMakerActivity_nativeBeatStop(
    JNIEnv*, jobject
) {
    gEngine.stopBeat();
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_BeatMakerActivity_nativeBeatReset(
    JNIEnv*, jobject
) {
    gEngine.resetBeat();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_crhimastiano_studiopro_BeatMakerActivity_nativeBeatCurrentStep(
    JNIEnv*, jobject
) {
    return gEngine.currentBeatStep();
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
JNI_OnUnload(JavaVM*, void*) {
    gEngine.stopAll();
    std::lock_guard<std::mutex> lock(gStreamMutex);
    if (gStream) {
        gStream->requestStop();
        gStream->close();
        gStream.reset();
    }
}
