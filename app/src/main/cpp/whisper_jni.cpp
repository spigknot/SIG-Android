#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>
#include <unistd.h>
#include <pthread.h>

#include "whisper.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "webrtc/common_audio/vad/include/webrtc_vad.h"

#define LOG_TAG "SIGWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex g_mutex;
static whisper_context * g_ctx = nullptr;
static std::atomic_bool g_cancel_requested(false);
static std::string g_last_error;
static std::string g_last_load_log;

extern "C" const char * sig_opencl_loader_last_error();

static const char * backend_label(int backend);
static ggml_backend_dev_t find_gpu_device(int backend, int * gpu_index, std::string * name);
static bool can_initialize_gpu_backend(int backend_kind, int & gpu_index, std::string & error);
static void configure_vulkan_memory_limit(std::string & log);

static int pipe_stderr[2];
static pthread_t thread_stderr;

static void * stderr_thread_func(void *) {
    ssize_t rdsz;
    char buf[512];
    while ((rdsz = read(pipe_stderr[0], buf, sizeof(buf) - 1)) > 0) {
        buf[rdsz] = '\0';
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", buf);
    }
    return nullptr;
}

static void start_stderr_redirection() {
    setvbuf(stderr, nullptr, _IOLBF, 0);
    pipe(pipe_stderr);
    dup2(pipe_stderr[1], STDERR_FILENO);
    pthread_create(&thread_stderr, nullptr, stderr_thread_func, nullptr);
}

static void log_to_android(const char * text) {
    if (text == nullptr || text[0] == '\0') return;
    std::string s(text);
    if (!s.empty() && s.back() == '\n') {
        s.pop_back();
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", s.c_str());
}

static void on_ggml_abort(const char * error_message) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GGML ABORT: %s", error_message);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void * reserved) {
    start_stderr_redirection();
    ggml_set_abort_callback(on_ggml_abort);
    return JNI_VERSION_1_6;
}

struct CallbackState {
    JNIEnv * env;
    jobject callback;
    jmethodID on_segment;
    jmethodID on_progress;
    jmethodID on_native_log;
    int vad_segments;
    double vad_reduction_seconds;
    double vad_reduction_percent;
    bool vad_summary_sent;
};

static void send_native_log(CallbackState * state, const char * text) {
    if (state == nullptr || state->env == nullptr || state->callback == nullptr || state->on_native_log == nullptr || text == nullptr || text[0] == '\0') return;
    jstring line = state->env->NewStringUTF(text);
    state->env->CallVoidMethod(state->callback, state->on_native_log, line);
    state->env->DeleteLocalRef(line);
    if (state->env->ExceptionCheck()) {
        state->env->ExceptionClear();
    }
}

static bool starts_with(const char * text, const char * prefix) {
    return text != nullptr && prefix != nullptr && strncmp(text, prefix, strlen(prefix)) == 0;
}

static void on_whisper_log(ggml_log_level, const char * text, void * user_data) {
    auto * state = static_cast<CallbackState *>(user_data);
    if (text == nullptr || text[0] == '\0') return;

    log_to_android(text);

    if (starts_with(text, "whisper_vad")) {
        int segments = 0;
        if (sscanf(text, "whisper_vad: detected %d speech segments", &segments) == 1) {
            state->vad_segments = segments;
        }

        int original_samples = 0;
        int reduced_samples = 0;
        double percent = 0.0;
        if (sscanf(text, "whisper_vad: Reduced audio from %d to %d samples (%lf%% reduction)", &original_samples, &reduced_samples, &percent) == 3) {
            state->vad_reduction_seconds = (original_samples - reduced_samples) / static_cast<double>(WHISPER_SAMPLE_RATE);
            state->vad_reduction_percent = percent;
            if (!state->vad_summary_sent) {
                std::ostringstream summary;
                summary << std::fixed << std::setprecision(2)
                        << "VAD: " << state->vad_segments << " segmentos detectados; "
                        << "reduziu " << state->vad_reduction_seconds << "s "
                        << "(" << state->vad_reduction_percent << "%)";
                const std::string summary_text = summary.str();
                send_native_log(state, summary_text.c_str());
                state->vad_summary_sent = true;
            }
        }
        return;
    }

    send_native_log(state, text);
}

static void send_native_log(CallbackState * state, const std::string & text) {
    send_native_log(state, text.c_str());
}

static void on_probe_log(ggml_log_level, const char * text, void * user_data) {
    auto * output = static_cast<std::string *>(user_data);
    if (output != nullptr && text != nullptr && text[0] != '\0') {
        *output += text;
    }
    log_to_android(text);
}

static uint32_t read_u32(FILE * file) {
    uint8_t b[4] = {};
    fread(b, 1, 4, file);
    return ((uint32_t) b[0]) | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16) | ((uint32_t) b[3] << 24);
}

static uint16_t read_u16(FILE * file) {
    uint8_t b[2] = {};
    fread(b, 1, 2, file);
    return ((uint16_t) b[0]) | ((uint16_t) b[1] << 8);
}

static bool read_wav_mono_16k(const char * path, std::vector<float> & samples, std::string & error) {
    FILE * file = fopen(path, "rb");
    if (!file) {
        error = "não consegui abrir o WAV temporário";
        return false;
    }

    char riff[4] = {};
    char wave[4] = {};
    fread(riff, 1, 4, file);
    read_u32(file);
    fread(wave, 1, 4, file);
    if (strncmp(riff, "RIFF", 4) != 0 || strncmp(wave, "WAVE", 4) != 0) {
        fclose(file);
        error = "WAV inválido";
        return false;
    }

    uint16_t channels = 0;
    uint32_t sample_rate = 0;
    uint16_t bits_per_sample = 0;
    std::vector<int16_t> pcm;

    while (!feof(file)) {
        char chunk_id[4] = {};
        if (fread(chunk_id, 1, 4, file) != 4) break;
        uint32_t chunk_size = read_u32(file);

        if (strncmp(chunk_id, "fmt ", 4) == 0) {
            uint16_t audio_format = read_u16(file);
            channels = read_u16(file);
            sample_rate = read_u32(file);
            read_u32(file);
            read_u16(file);
            bits_per_sample = read_u16(file);
            if (chunk_size > 16) fseek(file, chunk_size - 16, SEEK_CUR);
            if (audio_format != 1) {
                fclose(file);
                error = "WAV temporário não está em PCM";
                return false;
            }
        } else if (strncmp(chunk_id, "data", 4) == 0) {
            pcm.resize(chunk_size / sizeof(int16_t));
            fread(pcm.data(), sizeof(int16_t), pcm.size(), file);
        } else {
            fseek(file, chunk_size, SEEK_CUR);
        }

        if (chunk_size % 2 == 1) fseek(file, 1, SEEK_CUR);
    }

    fclose(file);

    if (channels != 1 || sample_rate != WHISPER_SAMPLE_RATE || bits_per_sample != 16 || pcm.empty()) {
        error = "WAV temporário precisa ser mono, 16 kHz e 16 bits";
        return false;
    }

    samples.resize(pcm.size());
    for (size_t i = 0; i < pcm.size(); ++i) {
        samples[i] = pcm[i] / 32768.0f;
    }
    return true;
}

static bool write_wav_mono_16k(const char * path, const std::vector<float> & samples, std::string & error) {
    FILE * file = fopen(path, "wb");
    if (!file) {
        error = "não consegui criar o WAV filtrado";
        return false;
    }

    const uint32_t data_size = static_cast<uint32_t>(samples.size() * sizeof(int16_t));
    const uint32_t riff_size = 36 + data_size;
    const uint16_t format = 1;
    const uint16_t channels = 1;
    const uint32_t sample_rate = WHISPER_SAMPLE_RATE;
    const uint32_t byte_rate = sample_rate * channels * sizeof(int16_t);
    const uint16_t block_align = channels * sizeof(int16_t);
    const uint16_t bits_per_sample = 16;
    fwrite("RIFF", 1, 4, file);
    fwrite(&riff_size, sizeof(riff_size), 1, file);
    fwrite("WAVEfmt ", 1, 8, file);
    const uint32_t fmt_size = 16;
    fwrite(&fmt_size, sizeof(fmt_size), 1, file);
    fwrite(&format, sizeof(format), 1, file);
    fwrite(&channels, sizeof(channels), 1, file);
    fwrite(&sample_rate, sizeof(sample_rate), 1, file);
    fwrite(&byte_rate, sizeof(byte_rate), 1, file);
    fwrite(&block_align, sizeof(block_align), 1, file);
    fwrite(&bits_per_sample, sizeof(bits_per_sample), 1, file);
    fwrite("data", 1, 4, file);
    fwrite(&data_size, sizeof(data_size), 1, file);
    for (float value : samples) {
        const float clamped = std::max(-1.0f, std::min(1.0f, value));
        const int16_t pcm = static_cast<int16_t>(std::lrintf(clamped * 32767.0f));
        fwrite(&pcm, sizeof(pcm), 1, file);
    }
    fclose(file);
    return true;
}

static void append_speech_frame(std::vector<float> & output, const std::vector<float> & input, size_t start, size_t end, bool & had_segment) {
    constexpr size_t kSeparatorSamples = WHISPER_SAMPLE_RATE / 10;
    if (had_segment) output.insert(output.end(), kSeparatorSamples, 0.0f);
    output.insert(output.end(), input.begin() + static_cast<long>(start), input.begin() + static_cast<long>(end));
    had_segment = true;
}

static bool filter_with_webrtc_vad(const std::vector<float> & input, int aggressiveness, std::vector<float> & output, int & segments, std::string & error) {
    constexpr int kFrameSamples = 480; // 30 ms at 16 kHz.
    std::vector<int16_t> pcm(input.size());
    for (size_t i = 0; i < input.size(); ++i) {
        pcm[i] = static_cast<int16_t>(std::lrintf(std::max(-1.0f, std::min(1.0f, input[i])) * 32767.0f));
    }
    VadInst * vad = WebRtcVad_Create();
    if (vad == nullptr || WebRtcVad_Init(vad) != 0 || WebRtcVad_set_mode(vad, aggressiveness) != 0) {
        if (vad != nullptr) WebRtcVad_Free(vad);
        error = "não consegui inicializar o WebRTC VAD";
        return false;
    }

    const size_t frame_count = (pcm.size() + kFrameSamples - 1) / kFrameSamples;
    std::vector<bool> speech(frame_count, false);
    std::vector<int16_t> frame(kFrameSamples, 0);
    for (size_t i = 0; i < frame_count; ++i) {
        const size_t start = i * kFrameSamples;
        const size_t count = std::min<size_t>(kFrameSamples, pcm.size() - start);
        std::fill(frame.begin(), frame.end(), 0);
        std::copy_n(pcm.begin() + static_cast<long>(start), count, frame.begin());
        speech[i] = WebRtcVad_Process(vad, WHISPER_SAMPLE_RATE, frame.data(), kFrameSamples) == 1;
    }
    WebRtcVad_Free(vad);

    constexpr int kPaddingFrames = 7; // 210 ms around detected speech.
    for (size_t i = 0; i < frame_count; ++i) {
        if (!speech[i]) continue;
        const size_t from = i > kPaddingFrames ? i - kPaddingFrames : 0;
        const size_t to = std::min(frame_count, i + kPaddingFrames + 1);
        for (size_t j = from; j < to; ++j) speech[j] = true;
    }
    bool in_segment = false;
    bool had_segment = false;
    size_t segment_start = 0;
    for (size_t i = 0; i <= frame_count; ++i) {
        const bool active = i < frame_count && speech[i];
        if (active && !in_segment) {
            in_segment = true;
            segment_start = i * kFrameSamples;
        } else if (!active && in_segment) {
            const size_t end = std::min(input.size(), i * kFrameSamples);
            if (end > segment_start) {
                append_speech_frame(output, input, segment_start, end, had_segment);
                ++segments;
            }
            in_segment = false;
        }
    }
    if (output.empty()) {
        error = "WebRTC VAD não encontrou fala";
        return false;
    }
    return true;
}

static bool filter_with_silero_vad(const std::vector<float> & input, const char * model_path, bool request_gpu, int aggressiveness, std::vector<float> & output, int & segments, std::string & backend, std::string & error) {
    whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = 4;
    params.use_gpu = false;
    params.gpu_device = 0;
    backend = "CPU";
    if (request_gpu) {
        // The VAD graph uses a separate GGML context. Some Android GPU drivers can
        // terminate the process while compiling that small graph, before JNI has a
        // chance to return an initialization error. Keep the explicit option safe
        // until this backend has a per-device stability probe.
        backend = "CPU (fallback preventivo; GPU VAD instável neste dispositivo)";
    }
    whisper_vad_context * context = whisper_vad_init_from_file_with_params(model_path, params);
    if (context == nullptr && params.use_gpu) {
        params.use_gpu = false;
        params.gpu_device = 0;
        backend = "CPU (fallback; GPU não inicializou)";
        context = whisper_vad_init_from_file_with_params(model_path, params);
    }
    if (context == nullptr) {
        error = "não consegui carregar o modelo Silero VAD";
        return false;
    }

    whisper_vad_params vad_params = whisper_vad_default_params();
    switch (aggressiveness) {
        case 0:
            vad_params.threshold = 0.40f;
            vad_params.min_speech_duration_ms = 120;
            vad_params.min_silence_duration_ms = 500;
            vad_params.speech_pad_ms = 200;
            break;
        case 1:
            vad_params.threshold = 0.50f;
            vad_params.min_speech_duration_ms = 200;
            vad_params.min_silence_duration_ms = 350;
            vad_params.speech_pad_ms = 120;
            break;
        case 2:
            vad_params.threshold = 0.60f;
            vad_params.min_speech_duration_ms = 300;
            vad_params.min_silence_duration_ms = 250;
            vad_params.speech_pad_ms = 80;
            break;
        case 3:
            vad_params.threshold = 0.70f;
            vad_params.min_speech_duration_ms = 400;
            vad_params.min_silence_duration_ms = 150;
            vad_params.speech_pad_ms = 40;
            break;
        default:
            error = "nível de agressividade VAD inválido";
            whisper_vad_free(context);
            return false;
    }
    vad_params.samples_overlap = 0.0f;
    whisper_vad_segments * detected = whisper_vad_segments_from_samples(context, vad_params, input.data(), static_cast<int>(input.size()));
    if (detected == nullptr) {
        whisper_vad_free(context);
        error = "Silero VAD não conseguiu analisar o áudio";
        return false;
    }
    bool had_segment = false;
    segments = whisper_vad_segments_n_segments(detected);
    for (int i = 0; i < segments; ++i) {
        // whisper.cpp stores VAD timestamps in centiseconds, not seconds.
        const size_t start = std::min(input.size(), static_cast<size_t>(std::max(0.0f, whisper_vad_segments_get_segment_t0(detected, i)) * WHISPER_SAMPLE_RATE / 100.0f));
        const size_t end = std::min(input.size(), static_cast<size_t>(std::max(0.0f, whisper_vad_segments_get_segment_t1(detected, i)) * WHISPER_SAMPLE_RATE / 100.0f));
        if (end > start) append_speech_frame(output, input, start, end, had_segment);
    }
    whisper_vad_free_segments(detected);
    whisper_vad_free(context);
    if (output.empty()) {
        error = "Silero VAD não encontrou fala";
        return false;
    }
    return true;
}

static void on_new_segment(struct whisper_context * ctx, struct whisper_state *, int n_new, void * user_data) {
    auto * state = static_cast<CallbackState *>(user_data);
    if (state == nullptr || state->env == nullptr || state->callback == nullptr || state->on_segment == nullptr) return;

    const int segments = whisper_full_n_segments(ctx);
    const int first = std::max(0, segments - n_new);
    for (int i = first; i < segments; ++i) {
        const char * text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr || text[0] == '\0') continue;
        const int64_t t0_ms = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1_ms = whisper_full_get_segment_t1(ctx, i) * 10;
        jstring segment = state->env->NewStringUTF(text);
        state->env->CallVoidMethod(state->callback, state->on_segment, segment, (jlong) t0_ms, (jlong) t1_ms);
        state->env->DeleteLocalRef(segment);
        if (state->env->ExceptionCheck()) {
            state->env->ExceptionClear();
            return;
        }
    }
}

static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void * user_data) {
    auto * state = static_cast<CallbackState *>(user_data);
    if (state == nullptr || state->env == nullptr || state->callback == nullptr || state->on_progress == nullptr) return;
    state->env->CallVoidMethod(state->callback, state->on_progress, progress);
    if (state->env->ExceptionCheck()) {
        state->env->ExceptionClear();
    }
}

static bool on_abort(void *) {
    return g_cancel_requested.load();
}

static bool is_gpu_device(ggml_backend_dev_t dev) {
    const auto type = ggml_backend_dev_type(dev);
    return type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

static std::string lower_copy(const char * value) {
    std::string text = value != nullptr ? value : "";
    std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return text;
}

static const char * backend_label(int backend) {
    switch (backend) {
        case 1: return "GPU+Vulkan";
        case 2: return "GPU+OpenCL";
        default: return "CPU";
    }
}

static std::string build_configuration_summary() {
    std::ostringstream output;
    output << "build diagnostics:";
    output << "\nwhisper_version=" << whisper_version();
    output << "\nggml_version=" << ggml_version();
    output << "\nggml_commit=" << ggml_commit();
#ifdef SIG_BUILD_GGML_OPENCL
    output << "\nSIG_BUILD_GGML_OPENCL=" << SIG_BUILD_GGML_OPENCL;
#else
    output << "\nSIG_BUILD_GGML_OPENCL=(not defined)";
#endif
#ifdef SIG_BUILD_GGML_VULKAN
    output << "\nSIG_BUILD_GGML_VULKAN=" << SIG_BUILD_GGML_VULKAN;
#else
    output << "\nSIG_BUILD_GGML_VULKAN=(not defined)";
#endif
#ifdef SIG_BUILD_GGML_OPENCL_EMBED_KERNELS
    output << "\nSIG_BUILD_GGML_OPENCL_EMBED_KERNELS=" << SIG_BUILD_GGML_OPENCL_EMBED_KERNELS;
#else
    output << "\nSIG_BUILD_GGML_OPENCL_EMBED_KERNELS=(not defined)";
#endif
#ifdef SIG_BUILD_GGML_OPENCL_SOA_Q
    output << "\nSIG_BUILD_GGML_OPENCL_SOA_Q=" << SIG_BUILD_GGML_OPENCL_SOA_Q;
#else
    output << "\nSIG_BUILD_GGML_OPENCL_SOA_Q=(not defined)";
#endif
#ifdef SIG_BUILD_GGML_OPENCL_USE_ADRENO_KERNELS
    output << "\nSIG_BUILD_GGML_OPENCL_USE_ADRENO_KERNELS=" << SIG_BUILD_GGML_OPENCL_USE_ADRENO_KERNELS;
#else
    output << "\nSIG_BUILD_GGML_OPENCL_USE_ADRENO_KERNELS=(not defined)";
#endif
#ifdef _OPENMP
    output << "\nJNI_OPENMP=" << _OPENMP;
#else
    output << "\nJNI_OPENMP=0";
#endif
    return output.str();
}

static bool device_matches_backend(ggml_backend_dev_t dev, int backend) {
    if (backend == 0) return false;
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
    std::string haystack = lower_copy(ggml_backend_dev_name(dev));
    haystack += " ";
    haystack += lower_copy(reg != nullptr ? ggml_backend_reg_name(reg) : nullptr);
    if (backend == 1) return haystack.find("vulkan") != std::string::npos;
    if (backend == 2) return haystack.find("opencl") != std::string::npos;
    return false;
}

static ggml_backend_dev_t find_gpu_device(int backend, int * gpu_index, std::string * name) {
    int gpu_counter = 0;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev != nullptr && is_gpu_device(dev)) {
            if (device_matches_backend(dev, backend)) {
                if (gpu_index != nullptr) *gpu_index = gpu_counter;
                if (name != nullptr) {
                    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
                    const char * reg_name = reg != nullptr ? ggml_backend_reg_name(reg) : nullptr;
                    const char * dev_name = ggml_backend_dev_name(dev);
                    *name = std::string(reg_name != nullptr ? reg_name : backend_label(backend)) +
                            " / " + (dev_name != nullptr ? dev_name : "GPU");
                }
                return dev;
            }
            ++gpu_counter;
        }
    }
    return nullptr;
}

static std::string backend_diagnostics() {
    std::ostringstream output;
    output << "devices=" << ggml_backend_dev_count();
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const char * name = dev != nullptr ? ggml_backend_dev_name(dev) : nullptr;
        ggml_backend_reg_t reg = dev != nullptr ? ggml_backend_dev_backend_reg(dev) : nullptr;
        const char * reg_name = reg != nullptr ? ggml_backend_reg_name(reg) : nullptr;
        output << "\n[" << i << "] "
               << (name != nullptr ? name : "unknown")
               << " backend=" << (reg_name != nullptr ? reg_name : "unknown")
               << " type=" << (dev != nullptr ? ggml_backend_dev_type(dev) : -1);
    }
    const char * opencl_loader = sig_opencl_loader_last_error();
    if (opencl_loader != nullptr && opencl_loader[0] != '\0') {
        output << "\nOpenCL loader: " << opencl_loader;
    }
    return output.str();
}

static ggml_backend_dev_t first_gpu_device(int * gpu_index, std::string * name) {
    int gpu_counter = 0;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev != nullptr && is_gpu_device(dev)) {
            if (gpu_index != nullptr) *gpu_index = gpu_counter;
            if (name != nullptr) {
                const char * dev_name = ggml_backend_dev_name(dev);
                *name = dev_name != nullptr ? dev_name : "GPU";
            }
            return dev;
        }
        if (dev != nullptr && is_gpu_device(dev)) ++gpu_counter;
    }
    return nullptr;
}

static bool can_initialize_gpu_backend(int backend_kind, int & gpu_index, std::string & error) {
    std::string probe_log;
    whisper_log_set(on_probe_log, &probe_log);
    std::string device_name;
    ggml_backend_dev_t dev = backend_kind == 0
        ? nullptr
        : find_gpu_device(backend_kind, &gpu_index, &device_name);
    whisper_log_set(nullptr, nullptr);
    if (dev == nullptr) {
        error = std::string(backend_label(backend_kind)) + " não disponível neste dispositivo";
        if (!probe_log.empty()) {
            error += "\n\nlog nativo:\n" + probe_log;
        }
        error += "\n\ndiagnóstico:\n" + backend_diagnostics();
        return false;
    }
    ggml_backend_t backend = ggml_backend_dev_init(dev, nullptr);
    if (backend == nullptr) {
        error = "não consegui inicializar " + std::string(backend_label(backend_kind)) + ": " + device_name;
        error += "\n\ndiagnóstico:\n" + backend_diagnostics();
        return false;
    }
    ggml_backend_free(backend);
    return true;
}

static void configure_vulkan_memory_limit(std::string & log) {
    constexpr unsigned long long block_bytes = 256ULL * 1024ULL * 1024ULL;

    setenv("GGML_VK_SUBALLOCATION_BLOCK_SIZE", "268435456", 1);
    setenv("GGML_VK_ALLOW_SYSMEM_FALLBACK", "1", 1);
    setenv("GGML_VK_PREFER_HOST_MEMORY", "1", 1); // Enable to fallback and map unified memory properly on Adreno
    setenv("GGML_VK_DISABLE_ASYNC", "1", 1); // Fixes vk::Queue::submit Timeout on mobile drivers

    // Disable cooperative matrix extensions to prevent compilation hangs/crashes on Adreno drivers
    setenv("GGML_VK_DISABLE_COOPMAT", "1", 1);
    setenv("GGML_VK_DISABLE_COOPMAT2", "1", 1);

    // Disable FP16, BFloat16 and Integer Dot Product to prevent shader compilation failures/device lost errors
    setenv("GGML_VK_DISABLE_F16", "1", 1);
    setenv("GGML_VK_DISABLE_BFLOAT16", "1", 1);
    setenv("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "1", 1);

    // Re-enable graph optimization and fusion to maximize Vulkan execution speed
    unsetenv("GGML_VK_DISABLE_GRAPH_OPTIMIZE");
    unsetenv("GGML_VK_DISABLE_FUSION");

    // Also unset memory overrides if they were set in previous runs/contexts
    unsetenv("GGML_VK_FORCE_MAX_ALLOCATION_SIZE");
    unsetenv("GGML_VK_FORCE_MAX_BUFFER_SIZE");
    unsetenv("GGML_VULKAN_MEMORY_LIMIT");

    std::ostringstream msg;
    msg << "Vulkan memory configurations:"
        << "\nGGML_VK_SUBALLOCATION_BLOCK_SIZE=" << block_bytes
        << "\nGGML_VK_ALLOW_SYSMEM_FALLBACK=1"
        << "\nGGML_VK_PREFER_HOST_MEMORY=1 (enabled for UMA stability)"
        << "\nGGML_VK_DISABLE_ASYNC=1 (disabled to prevent timeouts)"
        << "\nGGML_VK_DISABLE_COOPMAT=1 (disabled for stability)"
        << "\nGGML_VK_DISABLE_COOPMAT2=1 (disabled for stability)"
        << "\nGGML_VK_DISABLE_F16=1 (disabled FP16 for stability)"
        << "\nGGML_VK_DISABLE_BFLOAT16=1 (disabled BF16 for stability)"
        << "\nGGML_VK_DISABLE_INTEGER_DOT_PRODUCT=1 (disabled Int Dot for stability)"
        << "\nGGML_VK_DISABLE_GRAPH_OPTIMIZE=0 (enabled)"
        << "\nGGML_VK_DISABLE_FUSION=0 (enabled)\n";
    log += msg.str();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_loadModel(JNIEnv * env, jobject, jstring modelPath, jint backendKind, jboolean flashAttention) {
    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error.clear();
    g_last_load_log.clear();

    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    g_cancel_requested.store(false);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = backendKind != 0;
    cparams.flash_attn = flashAttention == JNI_TRUE;
    cparams.gpu_device = 0;
    if (backendKind == 1) {
        configure_vulkan_memory_limit(g_last_load_log);
    }
    if (cparams.use_gpu) {
        std::string gpu_error;
        int gpu_index = 0;
        if (!can_initialize_gpu_backend(backendKind, gpu_index, gpu_error)) {
            g_last_error = gpu_error;
            env->ReleaseStringUTFChars(modelPath, path);
            return JNI_FALSE;
        }
        cparams.gpu_device = gpu_index;
    }
    whisper_log_set(on_probe_log, &g_last_load_log);
    g_ctx = whisper_init_from_file_with_params(path, cparams);
    whisper_log_set(nullptr, nullptr);
    env->ReleaseStringUTFChars(modelPath, path);

    if (g_ctx == nullptr) {
        g_last_error = cparams.use_gpu
            ? "não consegui carregar o modelo com " + std::string(backend_label(backendKind))
            : "não consegui carregar o modelo com CPU";
    }

    return g_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_transcribe(
        JNIEnv * env,
        jobject,
        jstring wavPath,
        jstring language,
        jint beamSize,
        jint bestOf,
        jboolean wordTimestamps,
        jboolean vadFilter,
        jstring vadModelPath,
        jobject callback) {
    g_cancel_requested.store(false);
    const char * path = env->GetStringUTFChars(wavPath, nullptr);
    std::vector<float> samples;
    std::string error;
    if (!read_wav_mono_16k(path, samples, error)) {
        env->ReleaseStringUTFChars(wavPath, path);
        return env->NewStringUTF(("Erro: " + error).c_str());
    }
    env->ReleaseStringUTFChars(wavPath, path);
    const char * requested_language = env->GetStringUTFChars(language, nullptr);
    const char * vad_model_path = env->GetStringUTFChars(vadModelPath, nullptr);
    const bool auto_language = requested_language == nullptr || strcmp(requested_language, "auto") == 0;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr) {
        if (requested_language != nullptr) env->ReleaseStringUTFChars(language, requested_language);
        if (vad_model_path != nullptr) env->ReleaseStringUTFChars(vadModelPath, vad_model_path);
        return env->NewStringUTF("Erro: modelo não carregado");
    }

    jclass callback_class = env->GetObjectClass(callback);
    CallbackState callback_state {
        env,
        callback,
        env->GetMethodID(callback_class, "onSegment", "(Ljava/lang/String;JJ)V"),
        env->GetMethodID(callback_class, "onProgress", "(I)V"),
        env->GetMethodID(callback_class, "onNativeLog", "(Ljava/lang/String;)V"),
        0,
        0.0,
        0.0,
        false
    };
    env->DeleteLocalRef(callback_class);

    {
        std::ostringstream wav_log;
        wav_log << std::fixed << std::setprecision(3)
                << "native wav: samples=" << samples.size()
                << " duration=" << (samples.size() / static_cast<double>(WHISPER_SAMPLE_RATE)) << "s";
        send_native_log(&callback_state, wav_log.str());
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    whisper_reset_timings(g_ctx);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    params.language = auto_language ? "auto" : requested_language;
    params.detect_language = false;
    params.n_threads = 4;
    params.beam_search.beam_size = std::max(1, (int) beamSize);
    params.greedy.best_of = std::max(1, (int) bestOf);
    params.no_timestamps = false;
    params.token_timestamps = wordTimestamps == JNI_TRUE;
    params.vad = vadFilter == JNI_TRUE;
    params.vad_model_path = (vad_model_path != nullptr && vad_model_path[0] != '\0') ? vad_model_path : nullptr;
    if (params.vad) {
        params.vad_params.speech_pad_ms = 200;
    }
    params.new_segment_callback = on_new_segment;
    params.new_segment_callback_user_data = &callback_state;
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &callback_state;
    params.abort_callback = on_abort;
    params.abort_callback_user_data = nullptr;

    {
        std::ostringstream param_log;
        param_log << "native params: sampling=beam_search"
                  << " beam_size=" << params.beam_search.beam_size
                  << " best_of=" << params.greedy.best_of
                  << " word_timestamps=" << (params.token_timestamps ? "on" : "off")
                  << " vad=" << (params.vad ? "on" : "off")
                  << " vad_speech_pad_ms=" << params.vad_params.speech_pad_ms
                  << " vad_model=" << (params.vad_model_path != nullptr ? params.vad_model_path : "")
                  << " threads=4 processors=1";
        send_native_log(&callback_state, param_log.str());
    }
    whisper_log_set(on_whisper_log, &callback_state);
    int whisper_result = -1;
    try {
        whisper_result = whisper_full_parallel(g_ctx, params, samples.data(), (int) samples.size(), 1);
        whisper_print_timings(g_ctx);
    } catch (const std::exception & ex) {
        send_native_log(&callback_state, std::string("native exception: ") + ex.what());
        g_last_error = ex.what();
        whisper_result = -1;
    } catch (...) {
        send_native_log(&callback_state, "native exception: erro nativo desconhecido");
        g_last_error = "erro nativo desconhecido";
        whisper_result = -1;
    }
    whisper_log_set(nullptr, nullptr);

    if (whisper_result != 0) {
        if (requested_language != nullptr) env->ReleaseStringUTFChars(language, requested_language);
        if (vad_model_path != nullptr) env->ReleaseStringUTFChars(vadModelPath, vad_model_path);
        if (g_cancel_requested.load()) {
            g_cancel_requested.store(false);
            return env->NewStringUTF("Cancelado: transcrição cancelada");
        }
        return env->NewStringUTF("Erro: falha na transcrição");
    }
    if (requested_language != nullptr) env->ReleaseStringUTFChars(language, requested_language);
    if (vad_model_path != nullptr) env->ReleaseStringUTFChars(vadModelPath, vad_model_path);

    const int segments = whisper_full_n_segments(g_ctx);
    send_native_log(&callback_state, "native result: segments=" + std::to_string(segments));
    std::string result;
    for (int i = 0; i < segments; ++i) {
        const char * text = whisper_full_get_segment_text(g_ctx, i);
        if (text != nullptr) {
            if (!result.empty()) result += "\n";
            result += text;
        }
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_filterVad(
        JNIEnv * env,
        jobject,
        jstring inputWavPath,
        jstring outputWavPath,
        jstring sileroModelPath,
        jint mode,
        jint aggressiveness) {
    const char * input_path = env->GetStringUTFChars(inputWavPath, nullptr);
    const char * output_path = env->GetStringUTFChars(outputWavPath, nullptr);
    const char * model_path = env->GetStringUTFChars(sileroModelPath, nullptr);
    std::vector<float> input;
    std::vector<float> filtered;
    std::string error;
    if (!read_wav_mono_16k(input_path, input, error)) {
        env->ReleaseStringUTFChars(inputWavPath, input_path);
        env->ReleaseStringUTFChars(outputWavPath, output_path);
        env->ReleaseStringUTFChars(sileroModelPath, model_path);
        return env->NewStringUTF(("ERRO|" + error).c_str());
    }

    int segments = 0;
    std::string backend;
    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (aggressiveness < 0 || aggressiveness > 3) {
            error = "nível de agressividade VAD inválido";
        } else if (mode == 3) {
            backend = "WebRTC";
            ok = filter_with_webrtc_vad(input, aggressiveness, filtered, segments, error);
        } else {
            if (model_path == nullptr || model_path[0] == '\0') {
                error = "modelo Silero VAD ausente";
            } else {
                ok = filter_with_silero_vad(input, model_path, mode == 2, aggressiveness, filtered, segments, backend, error);
            }
        }
    }
    if (ok) ok = write_wav_mono_16k(output_path, filtered, error);
    env->ReleaseStringUTFChars(inputWavPath, input_path);
    env->ReleaseStringUTFChars(outputWavPath, output_path);
    env->ReleaseStringUTFChars(sileroModelPath, model_path);
    if (!ok) return env->NewStringUTF(("ERRO|" + error).c_str());

    std::ostringstream result;
    result << "OK|" << input.size() << "|" << filtered.size() << "|" << segments << "|" << backend;
    return env->NewStringUTF(result.str().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_cancelTranscription(JNIEnv *, jobject) {
    g_cancel_requested.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_systemInfo(JNIEnv * env, jobject) {
    const char * info = whisper_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_buildInfo(JNIEnv * env, jobject) {
    const std::string output = build_configuration_summary();
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_backendInfo(JNIEnv * env, jobject) {
    const std::string output = backend_diagnostics();
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_lastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_lastLoadLog(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_load_log.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_releaseModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        try {
            whisper_free(g_ctx);
        } catch (const std::exception & ex) {
            g_last_error = std::string("erro ao liberar modelo: ") + ex.what();
            __android_log_print(ANDROID_LOG_ERROR, "SIG_WHISPER", "%s", g_last_error.c_str());
        } catch (...) {
            g_last_error = "erro desconhecido ao liberar modelo";
            __android_log_print(ANDROID_LOG_ERROR, "SIG_WHISPER", "%s", g_last_error.c_str());
        }
        g_ctx = nullptr;
    }
}
