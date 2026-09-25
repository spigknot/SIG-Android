// Ponte JNI da tradução Hy-MT2 (llama.cpp + kernel STQ1_0).
//
// Espelha whisper_jni.cpp: só declara/aciona o nativo; seleção de modelo, UI e
// log ficam no Kotlin (TextoActivity). O prompt chega pronto do seam puro
// HyMt2Translator (incluindo os tokens de template do modelo); aqui ficam só a
// inferência (carregar modelo, amostrar, decodificar) e o backend.
//
// Backends (kind): 0 = CPU, 1 = GPU OpenCL (Adreno), 2 = GPU Vulkan,
// 3 = NPU (ainda não implementado — erro claro). O kind do Kotlin é o
// HyMt2Backend.nativeKind do TextoActivity. Escolher GPU exige o device
// correspondente no aparelho: sem ele a carga falha com diagnóstico (sem
// fallback silencioso para CPU — o usuário decide).
//
// Nenhum segredo passa por aqui.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cctype>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "SIGLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" const char * sig_opencl_loader_last_error();

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static std::string g_last_error;
static std::string g_backend_desc = "CPU";

// Logs do llama.cpp/ggml -> logcat (tag SIGLlama): sem isso o diagnóstico de
// campo (split de grafo, ops que caem para CPU, devices) fica invisível.
static void sig_log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    int priority = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) priority = ANDROID_LOG_DEBUG;
    __android_log_print(priority, LOG_TAG, "%s", text);
}

static void set_error(const std::string & message) {
    g_last_error = message;
    LOGE("%s", message.c_str());
}

static const char * backend_label(int kind) {
    switch (kind) {
        case 1: return "GPU OpenCL";
        case 2: return "GPU Vulkan";
        case 3: return "NPU";
        default: return "CPU";
    }
}

static bool is_gpu_device(ggml_backend_dev_t dev) {
    const auto type = ggml_backend_dev_type(dev);
    return type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

static std::string lower_copy(const char * value) {
    std::string text = value != nullptr ? value : "";
    for (char & c : text) c = (char) std::tolower((unsigned char) c);
    return text;
}

static bool device_matches_backend(ggml_backend_dev_t dev, int kind) {
    if (kind != 1 && kind != 2) return false;
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
    std::string haystack = lower_copy(ggml_backend_dev_name(dev));
    haystack += " ";
    haystack += lower_copy(reg != nullptr ? ggml_backend_reg_name(reg) : nullptr);
    if (kind == 2) return haystack.find("vulkan") != std::string::npos;
    return haystack.find("opencl") != std::string::npos;
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
               << " type=" << (dev != nullptr ? (int) ggml_backend_dev_type(dev) : -1);
    }
    const char * opencl_loader = sig_opencl_loader_last_error();
    if (opencl_loader != nullptr && opencl_loader[0] != '\0') {
        output << "\nOpenCL loader: " << opencl_loader;
    }
    return output.str();
}

static ggml_backend_dev_t find_gpu_device(int kind, std::string * name) {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev != nullptr && is_gpu_device(dev) && device_matches_backend(dev, kind)) {
            if (name != nullptr) {
                ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
                const char * reg_name = reg != nullptr ? ggml_backend_reg_name(reg) : nullptr;
                const char * dev_name = ggml_backend_dev_name(dev);
                *name = std::string(reg_name != nullptr ? reg_name : backend_label(kind)) +
                        " / " + (dev_name != nullptr ? dev_name : "GPU");
            }
            return dev;
        }
    }
    return nullptr;
}

// Mesmas variáveis de estabilidade do whisper_jni para Adreno: sem elas o
// driver trava em vk::Queue::submit e em compilação de shader cooperativo.
static void configure_vulkan_memory_limit() {
    setenv("GGML_VK_SUBALLOCATION_BLOCK_SIZE", "268435456", 1);
    setenv("GGML_VK_ALLOW_SYSMEM_FALLBACK", "1", 1);
    setenv("GGML_VK_PREFER_HOST_MEMORY", "1", 1);
    setenv("GGML_VK_DISABLE_ASYNC", "1", 1);
    setenv("GGML_VK_DISABLE_COOPMAT", "1", 1);
    setenv("GGML_VK_DISABLE_COOPMAT2", "1", 1);
    setenv("GGML_VK_DISABLE_F16", "1", 1);
    setenv("GGML_VK_DISABLE_BFLOAT16", "1", 1);
    setenv("GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "1", 1);
    unsetenv("GGML_VK_DISABLE_GRAPH_OPTIMIZE");
    unsetenv("GGML_VK_DISABLE_FUSION");
    unsetenv("GGML_VK_FORCE_MAX_ALLOCATION_SIZE");
    unsetenv("GGML_VK_FORCE_MAX_BUFFER_SIZE");
    unsetenv("GGML_VULKAN_MEMORY_LIMIT");
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void * reserved) {
    llama_log_set(sig_log_callback, nullptr);
    llama_backend_init();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_loadModel(
        JNIEnv * env, jobject /* this */,
        jstring modelPath, jint backendKind, jint nThreads, jint nCtx) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (backendKind == 3) {
        set_error("Backend NPU ainda nao implementado (use CPU, OpenCL ou Vulkan).");
        return JNI_FALSE;
    }
    if (backendKind < 0 || backendKind > 2) {
        set_error("Backend desconhecido.");
        return JNI_FALSE;
    }
    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr || path[0] == '\0') {
        set_error("Caminho do modelo vazio.");
        return JNI_FALSE;
    }

    // CPU precisa desligar o offload explicitamente: o default do llama.cpp é
    // n_gpu_layers=99 e agora os backends GPU existem no binário.
    llama_model_params lparams = llama_model_default_params();
    lparams.n_gpu_layers = 0;
    std::string used_desc = "CPU";

    if (backendKind != 0) {
        if (backendKind == 2) configure_vulkan_memory_limit();
        std::string device_name;
        ggml_backend_dev_t dev = find_gpu_device(backendKind, &device_name);
        if (dev == nullptr) {
            set_error(std::string(backend_label(backendKind)) +
                      " nao disponivel neste dispositivo.\n\ndiagnostico:\n" + backend_diagnostics());
            env->ReleaseStringUTFChars(modelPath, path);
            return JNI_FALSE;
        }
        // Sonda de inicialização: detecta driver ausente/quebrado ANTES de
        // carregar os pesos (igual ao can_initialize_gpu_backend do whisper).
        ggml_backend_t probe = ggml_backend_dev_init(dev, nullptr);
        if (probe == nullptr) {
            set_error("nao consegui inicializar " + std::string(backend_label(backendKind)) +
                      ": " + device_name + "\n\ndiagnostico:\n" + backend_diagnostics());
            env->ReleaseStringUTFChars(modelPath, path);
            return JNI_FALSE;
        }
        ggml_backend_free(probe);
        static ggml_backend_dev_t devices[2] = { nullptr, nullptr };
        devices[0] = dev;
        devices[1] = nullptr;
        lparams.devices = devices;
        lparams.n_gpu_layers = -1; // todas as camadas no device pedido
        used_desc = std::string(backend_label(backendKind)) + " (" + device_name + ")";
    }

    // Recarregar troca o modelo; libera o anterior só depois de pronto o novo.
    llama_model * model = llama_model_load_from_file(path, lparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (model == nullptr) {
        set_error("Falha ao carregar o modelo GGUF (arquivo corrompido ou formato nao suportado).");
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 0 ? (uint32_t) nCtx : 8192;
    cparams.n_batch = 2048;
    // Flash attention desligado SÓ no caminho GPU (25/09): no Vulkan/Adreno 840 a
    // rota mista com pesos STQ devolveu saída alucinada — o FA é o suspeito nº 1
    // nesses drivers. Na CPU mantém o default (AUTO), que é o comportamento já
    // validado em campo.
    if (backendKind != 0) {
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    }
    if (nThreads > 0) {
        cparams.n_threads = nThreads;
        cparams.n_threads_batch = nThreads;
    }
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        set_error("Falha ao criar o contexto de inferencia.");
        return JNI_FALSE;
    }

    if (g_ctx != nullptr) llama_free(g_ctx);
    if (g_model != nullptr) llama_model_free(g_model);
    g_model = model;
    g_ctx = ctx;
    g_backend_desc = used_desc;
    g_last_error.clear();
    LOGI("modelo carregado: backend=%s n_ctx=%u n_threads=%d",
         used_desc.c_str(), cparams.n_ctx, llama_n_threads(ctx));
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_releaseModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_lastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_backendDescription(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_backend_desc.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_systemInfo(JNIEnv * env, jobject) {
    std::string info = std::string("llama.cpp ") + llama_print_system_info();
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_generate(
        JNIEnv * env, jobject /* this */,
        jstring prompt, jint maxTokens,
        jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_ctx == nullptr) {
        set_error("Modelo nao carregado.");
        return nullptr;
    }

    const char * prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) {
        set_error("Prompt invalido.");
        return nullptr;
    }
    std::string prompt_text(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);

    // parse_special=true: os tokens <｜hy_...｜> do template viram tokens unicos.
    int32_t n_prompt_max = (int32_t) prompt_text.size() + 8;
    std::vector<llama_token> prompt_tokens(n_prompt_max);
    int32_t n_prompt = llama_tokenize(
            vocab, prompt_text.c_str(), (int32_t) prompt_text.size(),
            prompt_tokens.data(), n_prompt_max,
            /*add_special=*/false, /*parse_special=*/true);
    if (n_prompt < 0) {
        set_error("Falha ao tokenizar o prompt.");
        return nullptr;
    }
    prompt_tokens.resize(n_prompt);

    const uint32_t n_ctx = llama_n_ctx(g_ctx);
    int32_t max_out = maxTokens > 0 ? maxTokens : 4096;
    if ((uint32_t) n_prompt + max_out > n_ctx) {
        max_out = (int32_t) n_ctx - n_prompt - 8;
    }
    if (max_out <= 0) {
        set_error("Texto longo demais para o contexto do modelo.");
        return nullptr;
    }

    // Cadeia de amostragem do model card (1.8B/7B): temp 0.7, top_p 0.6,
    // top_k 20, repetition_penalty 1.05.
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab), /*penalty_last_n=*/64,
            repeatPenalty > 0.0f ? repeatPenalty : 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 20));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0.0f ? topP : 0.6f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature > 0.0f ? temperature : 0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    const llama_token eos = llama_vocab_eos(vocab);
    std::string result;
    result.reserve(4096);

    // Decodifica o prompt em fatias de n_batch e depois um token por vez.
    auto decode_tokens = [&](const llama_token * data, int32_t count) -> bool {
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(data), count);
        return llama_decode(g_ctx, batch) == 0;
    };

    int32_t n_batch = (int32_t) llama_n_batch(g_ctx);
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        int32_t chunk = n_prompt - i < n_batch ? n_prompt - i : n_batch;
        if (!decode_tokens(prompt_tokens.data() + i, chunk)) {
            llama_sampler_free(smpl);
            set_error("Falha ao decodificar o prompt.");
            return nullptr;
        }
    }

    std::vector<char> piece(512);
    llama_token token = 0;
    int32_t n_out = 0;
    for (int32_t i = 0; i < max_out; ++i) {
        token = llama_sampler_sample(smpl, g_ctx, -1);
        if (token == eos) break;

        int32_t n = llama_token_to_piece(vocab, token, piece.data(), (int32_t) piece.size(), 0, true);
        if (n > 0) {
            result.append(piece.data(), (size_t) n);
        } else if (n < 0) {
            // Peca maior que o buffer: realoca e tenta de novo.
            piece.resize((size_t) -n + 8);
            n = llama_token_to_piece(vocab, token, piece.data(), (int32_t) piece.size(), 0, true);
            if (n > 0) result.append(piece.data(), (size_t) n);
        }
        ++n_out;

        if (!decode_tokens(&token, 1)) {
            llama_sampler_free(smpl);
            set_error("Falha ao decodificar o texto gerado.");
            return nullptr;
        }
    }

    llama_sampler_free(smpl);
    g_last_error.clear();
    LOGI("geracao concluida: %d tokens de saida (backend=%s)", n_out, g_backend_desc.c_str());
    return env->NewStringUTF(result.c_str());
}
