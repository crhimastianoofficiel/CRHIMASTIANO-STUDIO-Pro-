#include <jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <cmath>
#include <memory>

class ToneEngine : public oboe::AudioStreamDataCallback {
public:
    std::atomic<float> frequency{440.0f};
    std::atomic<bool> playing{false};
    double phase = 0.0;
    double sampleRate = 48000.0;

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream*,
        void* audioData,
        int32_t numFrames
    ) override {
        auto* output = static_cast<float*>(audioData);
        const float freq = frequency.load();
        const bool active = playing.load();
        const double increment = 2.0 * M_PI * freq / sampleRate;

        for (int32_t i = 0; i < numFrames; ++i) {
            float sample = 0.0f;
            if (active) {
                // Son de test doux, volontairement simple.
                sample = static_cast<float>(std::sin(phase) * 0.12);
                phase += increment;
                if (phase >= 2.0 * M_PI) phase -= 2.0 * M_PI;
            }
            output[i] = sample; // sortie mono
        }
        return oboe::DataCallbackResult::Continue;
    }
};

static std::shared_ptr<ToneEngine> engine;
static std::shared_ptr<oboe::AudioStream> stream;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeStart(
    JNIEnv*, jobject, jfloat frequencyHz
) {
    if (!engine) engine = std::make_shared<ToneEngine>();
    engine->frequency.store(frequencyHz);
    engine->playing.store(true);

    if (stream && stream->getState() == oboe::StreamState::Started) {
        return JNI_TRUE;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(1)
        ->setSampleRate(48000)
        ->setDataCallback(engine.get());

    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream);
    }
    if (result != oboe::Result::OK || !stream) {
        engine->playing.store(false);
        return JNI_FALSE;
    }

    engine->sampleRate = stream->getSampleRate();
    const oboe::Result startResult = stream->requestStart();
    if (startResult != oboe::Result::OK) {
        stream->close();
        stream.reset();
        engine->playing.store(false);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeSetFrequency(
    JNIEnv*, jobject, jfloat frequencyHz
) {
    if (engine) engine->frequency.store(frequencyHz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_crhimastiano_studiopro_MainActivity_nativeStop(
    JNIEnv*, jobject
) {
    if (engine) engine->playing.store(false);
}

extern "C" JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM*, void*) {
    if (engine) engine->playing.store(false);
    if (stream) {
        stream->requestStop();
        stream->close();
        stream.reset();
    }
    engine.reset();
}
