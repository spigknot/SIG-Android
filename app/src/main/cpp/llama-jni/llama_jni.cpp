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
#include <sys/system_properties.h>

#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <iostream>
#include <mutex>
#include <thread>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"
#include "hymt2_request_reset.h"

#define LOG_TAG "SIGLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" const char * sig_opencl_loader_last_error();

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static std::string g_last_error;
static std::string g_backend_desc = "CPU";
// ANR FIX (rodada ANR/UI): dados de UI protegidos por mutex DEDICADO, para a main
// thread nunca esperar no g_mutex (segurado por load/generate). Ordem de locks:
// g_mutex -> g_ui_mutex (nunca inversa). Getter de threads usa cache atomico.
// CONTRATO do g_threads_cache (getter non-block da UI):
//   0  = contexto indisponivel (sem load / load em andamento / apos release);
//   N>=1 = threads do contexto carregado.
// load: store(0) ANTES de trocar g_model/g_ctx, store(N) apos sucesso — a UI
// nunca ve valor de um contexto que nao esta mais ativo; release: store(0)
// antes do free. O getter NAO bloqueia em nenhum estado.
static std::mutex g_ui_mutex;
static std::atomic<int> g_threads_cache{0};
// DIAG rodada OpenCL (02/10): tempos por fase da carga + chave de runtime
// debug.sig.hymt2.skip_probe (default 0 = comportamento de producao, com
// sonda; 1 = pula a sonda p/ A/B controlado). Remover quando a rodada fechar.
static bool g_skip_probe = false;
static double g_probe_s = 0.0, g_model_s = 0.0, g_ctx_s = 0.0;

// Contadores do backend OpenCL (ggml-opencl.cpp): upload/alocacao na carga.
extern "C" double ggml_opencl_diag_set_s(void);
extern "C" long long ggml_opencl_diag_set_calls(void);
extern "C" long long ggml_opencl_diag_set_bytes(void);
extern "C" double ggml_opencl_diag_alloc_s(void);
extern "C" double ggml_opencl_diag_enq_s(void);
extern "C" long long ggml_opencl_diag_enq_calls(void);
extern "C" int ggml_opencl_diag_nb(void);
extern "C" int ggml_opencl_diag_tnb(void);
extern "C" int ggml_opencl_diag_dfr(void);
extern "C" double ggml_opencl_diag_tr_s(void);
extern "C" long long ggml_opencl_diag_tr_calls(void);
extern "C" double ggml_opencl_diag_sb_s(void);
extern "C" long long ggml_opencl_diag_sb_calls(void);
extern "C" void ggml_opencl_diag_reset(void);

static std::mutex g_summary_mutex;
static std::string g_load_summary;   // linhas de memória/splits do último carregamento
// Ultima geracao CONCLUIDA com sucesso (R6, politica explicita): permanece com
// o ultimo valor valido ate' a proxima geracao concluida — erro/cancelamento
// NAO apaga nem atualiza; a UI so exibe apos sucesso. Protegido por g_ui_mutex.
static std::string g_last_stats;
// PROF (F9): segmentos de host do loop de geracao (sample/decode/detok).
// Opt-in: debug.sig.hymt2.hostprof=1 (default 0 = stock; sem clock no loop).
static bool g_hostprof = false;
static long long g_hp_sample_us = 0, g_hp_decode_us = 0, g_hp_detok_us = 0;

// PROF (F10): fases internas do decode (libllama) — opt-in decprof.
extern "C" void sig_prof_set_enabled(int on);
extern "C" void sig_prof_reset(void);
extern "C" long long sig_prof_prep_us(void);
extern "C" long long sig_prof_build_us(void);
extern "C" long long sig_prof_compute_us(void);
extern "C" long long sig_prof_total_us(void);
extern "C" long long sig_prof_calls(void);
extern "C" long long sig_prof_ubatches(void);

// PROF (F12): micro-instrumento do backend OpenCL (graph_compute x enqueue).
extern "C" void sig_sched_set_enabled(int on);
extern "C" void sig_sched_reset(void);
extern "C" void sig_sched_perop_reset(void);
extern "C" long long sig_opencl_shim_calls(void);
extern "C" long long sig_opencl_dlsym_calls(void);
extern "C" void sig_opencl_counters_reset(void);
extern "C" long long sig_sched_graph_us(void);
extern "C" long long sig_sched_enq_us(void);
extern "C" long long sig_sched_nodes(void);
extern "C" long long sig_sched_enq_n(void);
extern "C" long long sig_sched_perop_us(int op);
extern "C" long long sig_sched_perop_n(int op);
extern "C" const char * ggml_op_name(enum ggml_op op);
static bool g_schedprof = false;
static bool g_clcount  = false;

/** Acrescenta uma linha qualquer ao resumo (ex.: VRAM do device). */
static void summary_append_line(const std::string & line) {
    std::lock_guard<std::mutex> lock(g_summary_mutex);
    if (!g_load_summary.empty()) g_load_summary += "\n";
    g_load_summary += line;
}

/** Guarda as linhas de memória do llama.cpp (tamanhos de buffer) para mostrar na UI. */
static void summary_capture(const char * text) {
    if (text == nullptr) return;
    std::string line(text);
    if (line.find("model buffer size") == std::string::npos &&
        line.find("compute buffer size") == std::string::npos &&
        line.find("graph splits") == std::string::npos) {
        return;
    }
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
    summary_append_line(line);
}

static std::string summary_read() {
    std::lock_guard<std::mutex> lock(g_summary_mutex);
    return g_load_summary;
}

static void summary_reset(const std::string & prefixo) {
    std::lock_guard<std::mutex> lock(g_summary_mutex);
    g_load_summary = prefixo;
}

// Logs do llama.cpp/ggml -> logcat (tag SIGLlama): sem isso o diagnóstico de
// campo (split de grafo, ops que caem para CPU, devices) fica invisível.
static void sig_log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    summary_capture(text);
    int priority = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) priority = ANDROID_LOG_DEBUG;
    __android_log_print(priority, LOG_TAG, "%s", text);
}

// ggml-vulkan escreve o diagnóstico de pipeline em std::cerr (ex.: "Compute pipeline
// creation failed for <nome do shader>"), que no Android vai para o limbo — sem isto
// não dá para saber QUAL shader o driver recusa. Redireciona cerr/clog para o logcat.
struct CerrRedirect {
    static std::mutex mutex;
    static std::string buffer;

    CerrRedirect() { std::cerr.rdbuf(&sink); std::clog.rdbuf(&sink); }
    ~CerrRedirect() { std::cerr.rdbuf(std::cerr.rdbuf()); }

    struct Buf : std::streambuf {
        int overflow(int c) override {
            if (c != EOF) CerrRedirect::mutex.lock(), CerrRedirect::buffer.push_back((char) c),
                CerrRedirect::mutex.unlock();
            return c;
        }
        std::streamsize xsputn(const char * s, std::streamsize n) override {
            std::lock_guard<std::mutex> lock(CerrRedirect::mutex);
            CerrRedirect::buffer.append(s, (size_t) n);
            return n;
        }
    } sink;
};
std::mutex CerrRedirect::mutex;
std::string CerrRedirect::buffer;

static void cerr_drain() {
    std::string linha;
    {
        std::lock_guard<std::mutex> lock(CerrRedirect::mutex);
        linha.swap(CerrRedirect::buffer);
    }
    size_t inicio = 0;
    while (inicio < linha.size()) {
        size_t fim = linha.find('\n', inicio);
        if (fim == std::string::npos) fim = linha.size();
        LOGE("%s", linha.substr(inicio, fim - inicio).c_str());
        inicio = fim + 1;
    }
}

static void set_error(const std::string & message) {
    cerr_drain();  // o diagnóstico do driver (std::cerr) vem junto do erro
    {
        std::lock_guard<std::mutex> lock(g_ui_mutex);
        g_last_error = message;
    }
    LOGE("%s", message.c_str());
}

// R6 (auditoria ANR): leitura do estado de UI SEMPRE por copia sob g_ui_mutex.
// O lock segura apenas a copia — NewStringUTF/log ficam FORA da regiao.
// Ordem de locks: g_mutex -> g_ui_mutex (nunca inversa).
static std::string ui_copy_backend_desc() {
    std::lock_guard<std::mutex> lock(g_ui_mutex);
    return g_backend_desc;
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
    static CerrRedirect cerr_to_logcat;  // ggml-vulkan diagnostica em std::cerr
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
    ggml_backend_dev_t gpu_dev = nullptr;

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
        // DIAG (rodada OpenCL): le a chave de runtime e mede a fase da sonda.
        {
            char prop_skip[16] = {0};
            __system_property_get("debug.sig.hymt2.skip_probe", prop_skip);
            g_skip_probe = (prop_skip[0] == '1');
        }
        auto t_probe0 = std::chrono::steady_clock::now();
        if (!g_skip_probe) {
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
        }
        g_probe_s = std::chrono::duration<double>(
                        std::chrono::steady_clock::now() - t_probe0).count();
        static ggml_backend_dev_t devices[2] = { nullptr, nullptr };
        devices[0] = dev;
        devices[1] = nullptr;
        gpu_dev = dev;
        lparams.devices = devices;
        lparams.n_gpu_layers = -1; // todas as camadas no device pedido
        used_desc = std::string(backend_label(backendKind)) + " (" + device_name + ")";
    }

    summary_reset("Device: " + used_desc);
    // Recarregar troca o modelo; libera o anterior só depois de pronto o novo.
    // try/catch: o driver Adreno pode LANCAR (vk::SystemError) ao criar um shader —
    // sem catch isso vira SIGABRT e mata o app (visto no Ace 2 Pro, 25/09).
    llama_model * model = nullptr;
    ggml_opencl_diag_reset();   // DIAG: zera contadores de upload/alocacao
    auto t_load0 = std::chrono::steady_clock::now();
    try {
        model = llama_model_load_from_file(path, lparams);
    } catch (const std::exception & e) {
        env->ReleaseStringUTFChars(modelPath, path);
        set_error(std::string("Backend ") + backend_label(backendKind) +
                  " falhou ao carregar (driver): " + e.what());
        return JNI_FALSE;
    } catch (...) {
        env->ReleaseStringUTFChars(modelPath, path);
        set_error(std::string("Backend ") + backend_label(backendKind) +
                  " falhou ao carregar (excecao desconhecida do driver).");
        return JNI_FALSE;
    }
    env->ReleaseStringUTFChars(modelPath, path);
    if (model == nullptr) {
        set_error("Falha ao carregar o modelo GGUF (arquivo corrompido ou formato nao suportado).");
        return JNI_FALSE;
    }
    g_model_s = std::chrono::duration<double>(
                    std::chrono::steady_clock::now() - t_load0).count();

    if (gpu_dev != nullptr) {
        size_t livre = 0, total = 0;
        ggml_backend_dev_memory(gpu_dev, &livre, &total);
        char vram[160];
        snprintf(vram, sizeof(vram), "VRAM: %.0f MiB livres de %.0f MiB",
                 livre / 1048576.0, total / 1048576.0);
        summary_append_line(vram);
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

    // PROF (F6): medicao de perf do llama (no_perf=false) SOMENTE quando
    // debug.sig.hymt2.prof=1; default = stock (no_perf=true, sem contadores).
    {
        char vprof[8] = {0};
        __system_property_get("debug.sig.hymt2.prof", vprof);
        cparams.no_perf = !(vprof[0] == '1');
    }
    if (nThreads > 0) {
        cparams.n_threads = nThreads;
        cparams.n_threads_batch = nThreads;
    }
    llama_context * ctx = nullptr;
    auto t_ctx0 = std::chrono::steady_clock::now();
    try {
        ctx = llama_init_from_model(model, cparams);
    } catch (const std::exception & e) {
        llama_model_free(model);
        set_error(std::string("Backend ") + backend_label(backendKind) +
                  " falhou ao criar o contexto (driver): " + e.what());
        return JNI_FALSE;
    } catch (...) {
        llama_model_free(model);
        set_error(std::string("Backend ") + backend_label(backendKind) +
                  " falhou ao criar o contexto (excecao desconhecida do driver).");
        return JNI_FALSE;
    }
    if (ctx == nullptr) {
        llama_model_free(model);
        set_error("Falha ao criar o contexto de inferencia.");
        return JNI_FALSE;
    }
    g_ctx_s = std::chrono::duration<double>(
                  std::chrono::steady_clock::now() - t_ctx0).count();

    if (g_ctx != nullptr) llama_free(g_ctx);
    if (g_model != nullptr) llama_model_free(g_model);
    g_threads_cache.store(0);
    g_model = model;
    g_ctx = ctx;
    g_threads_cache.store(llama_n_threads(ctx));
    {
        std::lock_guard<std::mutex> lock(g_ui_mutex);
        g_backend_desc = used_desc;
        g_last_error.clear();
    }
    LOGI("modelo carregado: backend=%s n_ctx=%u n_threads=%d",
         used_desc.c_str(), cparams.n_ctx, llama_n_threads(ctx));
    // DIAG (rodada OpenCL): fases da carga no resumo do app (visivel no log)
    // e no logcat. total = sonda + modelo + contexto (mesma fronteira do timer
    // do app "Modelo carregado em Ls", que ainda inclui ~estes 3 passos).
    {
        char fases[420];
        snprintf(fases, sizeof(fases),
                 "Fases da carga: sonda=%.1fs modelo=%.1fs contexto=%.1fs total=%.1fs (skip_probe=%d) "
                 "| upload=%.1fs em %lld chamadas (%.0f MiB) [enqueue=%.1fs/%lld; subbuf=%.1fs/%lld; tr=%.1fs/%lld; cpu=%.1fs; nb=%d; tnb=%d; dfr=%d] | alloc=%.1fs",
                 g_probe_s, g_model_s, g_ctx_s,
                 g_probe_s + g_model_s + g_ctx_s, g_skip_probe ? 1 : 0,
                 ggml_opencl_diag_set_s(), ggml_opencl_diag_set_calls(),
                 ggml_opencl_diag_set_bytes() / 1048576.0,
                 ggml_opencl_diag_enq_s(), ggml_opencl_diag_enq_calls(),
                 ggml_opencl_diag_sb_s(), ggml_opencl_diag_sb_calls(),
                 ggml_opencl_diag_tr_s(), ggml_opencl_diag_tr_calls(),
                 ggml_opencl_diag_set_s() - ggml_opencl_diag_enq_s() - ggml_opencl_diag_sb_s() - ggml_opencl_diag_tr_s(),
                 ggml_opencl_diag_nb(), ggml_opencl_diag_tnb(), ggml_opencl_diag_dfr(),
                 ggml_opencl_diag_alloc_s());
        summary_append_line(fases);
        LOGI("%s", fases);
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_releaseModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_threads_cache.store(0);
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
    std::string copy;
    {
        std::lock_guard<std::mutex> lock(g_ui_mutex);
        copy = g_last_error;
    }
    return env->NewStringUTF(copy.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_backendDescription(JNIEnv * env, jobject) {
    std::string copy;
    {
        std::lock_guard<std::mutex> lock(g_ui_mutex);
        copy = g_backend_desc;
    }
    return env->NewStringUTF(copy.c_str());
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

    // try/catch: no Vulkan o driver pode lancar (vk::SystemError na criacao de
    // pipeline) durante a primeira decodificacao — sem catch vira SIGABRT.
    try {
    // Isolamento de pedidos: o contexto é reutilizado entre traduções (sem
    // recarga de pesos), então a memória KV/posição do pedido anterior deve
    // ser limpa aqui — sob o MESMO g_mutex que protege inferência e release.
    // Sem isso, llama_batch_get_one(pos=null) continua de seq_pos_max+1 e o
    // pedido novo observa o anterior. Modelo/pesos permanecem carregados.
    {
        std::string reset_error;
        if (!hymt2_begin_fresh_request(g_ctx, &reset_error)) {
            set_error(reset_error);
            return nullptr;
        }
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

    // F17: schedprof habilitado/resetado ANTES do prefill => o snapshot pos-
    // prefill captura a fase PREFILL de verdade (antes, o reset pos-prefill
    // zerava tudo e [prefill] saia 0 por construcao).
    {
        char vs[8] = {0};
        __system_property_get("debug.sig.hymt2.schedprof", vs);
        const int on = (vs[0] == '1') ? 1 : 0;
        g_schedprof = (on != 0);
        sig_sched_set_enabled(on);
        if (on) sig_sched_reset();
    }
    {
        char vc[8] = {0};
        __system_property_get("debug.sig.hymt2.clcount", vc);
        g_clcount = (vc[0] == '1');
        if (g_clcount) sig_opencl_counters_reset();
    }
    int32_t n_batch = (int32_t) llama_n_batch(g_ctx);
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        int32_t chunk = n_prompt - i < n_batch ? n_prompt - i : n_batch;
        if (!decode_tokens(prompt_tokens.data() + i, chunk)) {
            llama_sampler_free(smpl);
            set_error("Falha ao decodificar o prompt.");
            return nullptr;
        }
    }

    // PROF (F12): snapshot das contagens do backend apos o PREFILL — o delta
    // ate' o fim da geracao = fase DECODE (loop) separada do prefill.
    long long sig_pref_graph = 0, sig_pref_enq = 0, sig_pref_nodes = 0, sig_pref_enq_n = 0;
    if (g_schedprof) {
        sig_pref_graph = sig_sched_graph_us(); sig_pref_enq = sig_sched_enq_us();
        sig_pref_nodes = sig_sched_nodes();     sig_pref_enq_n = sig_sched_enq_n();
    }
    if (g_schedprof) sig_sched_perop_reset();   // topops mede apenas a fase decode

    std::vector<char> piece(512);
    llama_token token = 0;
    int32_t n_out = 0;
    {
        char vh[8] = {0};
        __system_property_get("debug.sig.hymt2.hostprof", vh);
        g_hostprof = (vh[0] == '1');
        g_hp_sample_us = g_hp_decode_us = g_hp_detok_us = 0;
    }
    {
        char vd[8] = {0};
        __system_property_get("debug.sig.hymt2.decprof", vd);
        const int on = (vd[0] == '1') ? 1 : 0;
        sig_prof_set_enabled(on);
        if (on) sig_prof_reset();
    }
    const auto geracao_inicio = std::chrono::steady_clock::now();
    for (int32_t i = 0; i < max_out; ++i) {
        std::chrono::steady_clock::time_point hp0, hp1;
        if (g_hostprof) hp0 = std::chrono::steady_clock::now();
        token = llama_sampler_sample(smpl, g_ctx, -1);
        if (g_hostprof) {
            hp1 = std::chrono::steady_clock::now();
            g_hp_sample_us += std::chrono::duration_cast<std::chrono::microseconds>(hp1 - hp0).count();
            hp0 = hp1;
        }
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

        if (g_hostprof) {
            hp1 = std::chrono::steady_clock::now();
            g_hp_detok_us += std::chrono::duration_cast<std::chrono::microseconds>(hp1 - hp0).count();
            hp0 = hp1;
        }
        if (!decode_tokens(&token, 1)) {
            llama_sampler_free(smpl);
            set_error("Falha ao decodificar o texto gerado.");
            return nullptr;
        }
        if (g_hostprof) {
            hp1 = std::chrono::steady_clock::now();
            g_hp_decode_us += std::chrono::duration_cast<std::chrono::microseconds>(hp1 - hp0).count();
        }
    }

    llama_sampler_free(smpl);
    {
        // R6: clear sob g_ui_mutex (lastError le sob o mesmo lock; clear sem
        // lock era data race com NewStringUTF na thread de UI).
        std::lock_guard<std::mutex> lock(g_ui_mutex);
        g_last_error.clear();
    }
    {
        const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - geracao_inicio).count();
        const double tps = ms > 0 ? (n_out * 1000.0 / (double) ms) : 0.0;
        char stats[160];
        snprintf(stats, sizeof(stats), "%d tokens em %.1f s (%.1f tokens/s)",
                 n_out, ms / 1000.0, tps);
        {
            std::lock_guard<std::mutex> lock(g_ui_mutex);
            g_last_stats = stats;
        }
    }
    // PROF (F6): perfil llama (prefill x decode) — gated por debug.sig.hymt2.prof
    // (default 0 = stock). Contadores acumulam por contexto; o protocolo de medicao
    // usa processo novo por run (leitura limpa).
    {
        char vp[8] = {0};
        __system_property_get("debug.sig.hymt2.prof", vp);
        if (vp[0] == '1') {
            const llama_perf_context_data pd = llama_perf_context(g_ctx);
            char perf[240];
            snprintf(perf, sizeof(perf),
                     "Perfil llama: load=%.0fms prefill=%.0fms (%d tok; %.2f tok/s) "
                     "decode=%.0fms (%d tok; %.2f tok/s)",
                     pd.t_load_ms, pd.t_p_eval_ms, pd.n_p_eval,
                     pd.t_p_eval_ms > 0 ? pd.n_p_eval * 1000.0 / pd.t_p_eval_ms : 0.0,
                     pd.t_eval_ms, pd.n_eval,
                     pd.t_eval_ms > 0 ? pd.n_eval * 1000.0 / pd.t_eval_ms : 0.0);
            summary_append_line(perf);
            LOGI("%s", perf);
        }
    }
    // PROF (F9): segmentos de HOST do loop (exclusivos: sample+detok+decode;
    // GPU/device NUNCA comparado direto ao host — relatorio separado).
    if (g_hostprof) {
        const long long tot_us = g_hp_sample_us + g_hp_decode_us + g_hp_detok_us;
        char hp[220];
        snprintf(hp, sizeof(hp),
                 "Host loop: sample=%.0fms detok=%.0fms decode_host=%.0fms | soma_segmentos=%.0fms (n=%d)",
                 g_hp_sample_us / 1000.0, g_hp_detok_us / 1000.0, g_hp_decode_us / 1000.0,
                 tot_us / 1000.0, n_out);
        summary_append_line(hp);
        LOGI("%s", hp);
    }
    // PROF (F10): fases internas do decode() — aninhadas: prep cobre as reservas
    // antes do loop; build/compute por ubatch dentro de total; residual explicito.
    if (sig_prof_calls() > 0) {
        const long long prep = sig_prof_prep_us(), build = sig_prof_build_us();
        const long long comp = sig_prof_compute_us(), tot = sig_prof_total_us();
        char dp[240];
        snprintf(dp, sizeof(dp),
                 "Decode fases: prep=%.0fms build=%.0fms compute=%.0fms residual=%.0fms | total=%.0fms calls=%lld ubatches=%lld",
                 prep / 1000.0, build / 1000.0, comp / 1000.0,
                 (tot - prep - build - comp) / 1000.0, tot / 1000.0,
                 sig_prof_calls(), sig_prof_ubatches());
        summary_append_line(dp);
        LOGI("%s", dp);
    }
    // PROF (F12): micro-split do backend OpenCL (grafo x enqueue por op).
    if (g_clcount) {
        char cc[160];
        snprintf(cc, sizeof(cc), "OCL/shim: chamadas=%lld dlsym=%lld (pedido inteiro)",
                 sig_opencl_shim_calls(), sig_opencl_dlsym_calls());
        summary_append_line(cc); LOGI("%s", cc);
    }
    if (g_schedprof) {
        const long long g_ = sig_sched_graph_us(), e_ = sig_sched_enq_us();
        const long long n_ = sig_sched_nodes(),   en_ = sig_sched_enq_n();
        char sp[300];
        snprintf(sp, sizeof(sp),
                 "Sched/OCL[prefill]: graph=%.0fms nodes=%lld enq=%.0fms (n=%lld)",
                 sig_pref_graph / 1000.0, sig_pref_nodes, sig_pref_enq / 1000.0, sig_pref_enq_n);
        summary_append_line(sp); LOGI("%s", sp);
        snprintf(sp, sizeof(sp),
                 "Sched/OCL[decode]: graph=%.0fms nodes=%lld enq=%.0fms (n=%lld) setup=%.0fms",
                 (g_ - sig_pref_graph) / 1000.0, n_ - sig_pref_nodes,
                 (e_ - sig_pref_enq) / 1000.0, en_ - sig_pref_enq_n,
                 ((g_ - sig_pref_graph) - (e_ - sig_pref_enq)) / 1000.0);
        summary_append_line(sp); LOGI("%s", sp);
        // top-6 ops por tempo de NO (setup) acumulado (fase decode; reset por request)
        char tp[380] = "Sched/OCL[topops]:";
        int seen[6]; int nseen = 0;
        for (int r = 0; r < 6; ++r) {
            int best = -1; long long bv = 0;
            for (int o = 0; o < 128; ++o) {
                bool skip = false;
                for (int k = 0; k < nseen; ++k) if (seen[k] == o) { skip = true; break; }
                if (skip) continue;
                if (sig_sched_perop_us(o) > bv) { bv = sig_sched_perop_us(o); best = o; }
            }
            if (best < 0 || bv <= 0) break;
            seen[nseen++] = best;
            char frag[72];
            snprintf(frag, sizeof(frag), " %s=%.0fms/%lld",
                     ggml_op_name((enum ggml_op) best), bv / 1000.0, sig_sched_perop_n(best));
            strncat(tp, frag, sizeof(tp) - strlen(tp) - 1);
        }
        summary_append_line(tp); LOGI("%s", tp);
    }
    LOGI("geracao concluida: %d tokens de saida (backend=%s)", n_out, ui_copy_backend_desc().c_str());
    return env->NewStringUTF(result.c_str());
    } catch (const std::exception & e) {
        set_error(std::string("Excecao na inferencia (") + ui_copy_backend_desc() + "): " + e.what());
        return nullptr;
    } catch (...) {
        set_error(std::string("Excecao desconhecida na inferencia (") + ui_copy_backend_desc() + ").");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_loadSummary(JNIEnv * env, jobject) {
    const std::string resumo = summary_read();
    return env->NewStringUTF(resumo.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_lastStats(JNIEnv * env, jobject) {
    std::string copy;
    {
        std::lock_guard<std::mutex> lock(g_ui_mutex);
        copy = g_last_stats;
    }
    return env->NewStringUTF(copy.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_threadCount(JNIEnv * env, jobject) {
    // ANR FIX: cache atomico (sem g_mutex) - main thread nao espera load/generate.
    const int n = g_threads_cache.load();
    return env->NewStringUTF(std::to_string(n).c_str());
}

// R6/VACINA (TESTE APENAS): segura o lock principal pelo tempo pedido, para o
// teste instrumentado (AnrUiLockContractTest) validar que os getters de UI
// respondem SEM bloquear com load/geracao "em andamento". Nao usar no app.
extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_HyMt2Native_sigTestHoldGmutex(JNIEnv *, jobject, jlong ms) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (ms > 0) std::this_thread::sleep_for(std::chrono::milliseconds(ms));
}
