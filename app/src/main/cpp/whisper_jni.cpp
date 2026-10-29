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
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>
#include <pthread.h>
#include <sched.h>
#include <sys/stat.h>

#include "whisper.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "webrtc/common_audio/vad/include/webrtc_vad.h"
#include "whisper_wav_io.h"
#include "whisper_mutf8.h"
#include "whisper_jni_chars.h"
// R10/fase G (G2): política de threads do caminho real (fonte única, testada no
// host por tools/whisper/sig_thread_policy_test.cpp).
#include "sig_thread_policy.h"

// R12/A3 — identidade de BUILD do artefato nativo. Sempre exportada (barata);
// permite que um loader/harness distinga NORMAL de DIAG sem depender do nome do
// arquivo, do diretorio de saida ou do hash de app/.cxx.
extern "C" const char * sig_whisper_build_mode(void) {
#if defined(SIG_WHISPER_DUMP_HOOK)
    return "diag";
#else
    return "normal";
#endif
}

#define LOG_TAG "SIGWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// R4/B3: accessor real do RAII de strings JNI. `get()` devolve nullptr em
// falha (OOM/exception pendente) — o chamador RECUSA, nunca usa caminho vazio.
struct JniEnvCharsAccessor {
    JNIEnv * env;
    jstring jstr;
    const char * get() const {
        return (env != nullptr && jstr != nullptr) ? env->GetStringUTFChars(jstr, nullptr) : nullptr;
    }
    void release(const char * chars) const {
        if (chars != nullptr && env != nullptr && jstr != nullptr) {
            env->ReleaseStringUTFChars(jstr, chars);
        }
    }
};

using JniChars = JniCharsGuard<JniEnvCharsAccessor>;

// R6/fase E (E1): código de retorno de whisper_full quando o abort_callback
// interrompeu a transcrição (definido em whisper.cpp: SIG_WHISPER_FULL_ABORTED).
// NÃO é falha numérica: este lado trata como cancelamento explícito.
static constexpr int kWhisperFullAborted = -100;

static std::mutex g_mutex;
static whisper_context * g_ctx = nullptr;
static std::atomic_bool g_cancel_requested(false);
static std::string g_last_error;
static std::string g_last_load_log;
// W10: status estruturado da última transcrição ("ok", "no_speech",
// "cancelled", "error"). Diferencia texto real (que pode começar com "Erro:"
// ou "Cancelado:") de mensagem de controle do protocolo JNI.
static std::string g_last_transcribe_status = "ok";
static std::mutex g_status_mutex;

// R2/B1 — ownership por sessão (API v2). Contrato verificável:
// - Toda sessão "owned" tem dono jlong > 0 (retorno de beginSessionOwned).
// - g_ctx_owner é guardado por g_mutex; 0 = contexto legado/anônimo.
// - Pedido de cancelamento com dono (g_cancel_owner > 0) só é observado e
//   consumido pelo próprio dono; pedido anônimo (0) mantém a semântica legada.
// - Status por dono permite parear texto+status do mesmo job mesmo quando
//   outra sessão altera o status global.
// - R4/B2: dono NÃO é "qualquer número positivo": só tokens EMITIDOS por
//   beginSessionOwned (g_issued_owners) podem carregar/assumir contexto, e a
//   posse não retrocede — uma transferência para owner ANTERIOR ao dono atual
//   é recusada (lease monotônico). Assumir o próprio dono atual é idempotente.
static std::atomic<jlong> g_session_counter(0);
static std::mutex g_issued_mutex;
static std::set<jlong> g_issued_owners;                 // guardado por g_issued_mutex
static constexpr size_t MAX_ISSUED_OWNERS = 256;
static jlong g_ctx_owner = 0;                           // guardado por g_mutex; 0 = legado/anonimo; sessões começam em 1
static std::string g_ctx_model_path;                    // guardado por g_mutex (chave do contexto carregado)
static jint g_ctx_backend_kind = 0;                     // guardado por g_mutex
static long long g_ctx_model_bytes = 0;                 // R10/G (G2): tamanho do modelo carregado (fato do contexto; guardado por g_mutex)
// R11/D2: fatos de ARQUITETURA do modelo REALMENTE carregado (não do nome nem
// do tamanho do arquivo) — lidos por whisper_model_* logo após a carga e
// guardados por g_mutex, como g_ctx_model_bytes. 0/-1 = não legível.
static int g_ctx_arch_audio_state = 0;                  // d_model do encoder (large-v3/turbo = 1280)
static int g_ctx_arch_audio_layer = 0;                   // camadas do encoder (large-v3/turbo = 32)
static int g_ctx_ftype = -1;                             // tipo ggml dos pesos (diagnóstico; q5_0 = 8)
// R11/D2: SEAM DE BANCADA da política de threads (one-shot: consumido por job).
// 0 = inativo ⇒ a política decide (comportamento de produção). Só é escrito por
// `setThreadsOverride`, que o produto NUNCA chama (o objeto de seam de produção
// devolve 0 e o gateway só toca o nativo quando o valor é > 0).
static sig::ThreadsOverride g_threads_override;
// R14/D2-D3 — SEAM DE BANCADA do contexto de encoder (experimental, one-shot).
// -1 = inativo (default do modelo). Só o lab escreve; o produto nunca chama.
#include "sig_audio_ctx.h"
#include "sig_audio_ctx_request.h"
#include "sig_attempt_opts.h"   // R24/REQ — fotografia por tentativa
#include "sig_attempt_request.h" // R25/REQ — pedido completo NA chamada
#include "sig_whisper_dump.h"    // R25/REQ — captura configurada pelo pedido

// R19/D1 — pedido TRANSACIONAL por dono (uma palavra atomica por slot).
static std::atomic<uint64_t> g_ac_slots[SIG_AC_SLOTS];

// R15/D2 — amostras EFETIVAS da janela pedida pelo ensaio (-1 = nenhuma).
static std::atomic<long long> g_audio_ctx_window_samples{-1};
static jboolean g_ctx_flash_requested = JNI_FALSE;      // guardado por g_mutex
static bool g_ctx_flash_graph = false;                  // R7/D4: FA realmente no grafo (pós-fallback)
// R11/fase E (E1): o backend PEDIDO pela sessão (chave de cache do app) e o
// backend EFETIVO do contexto podem DIVERGIR quando a guarda de perfil OpenCL
// recusa a combinação medida como insegura (recusa + fallback tipados).
// `g_ctx_backend_kind` passa a valer como EFETIVO; este guarda o PEDIDO, para
// que `assumeContextOwned` continue reconhecendo a chave do app (cache quente
// coerente) sem reabrir a combinação recusada.
static jint g_ctx_backend_requested = 0;                 // guardado por g_mutex
static std::atomic<jlong> g_active_transcribe_owner(0); // dono da inferência em curso (0 = nenhuma)
static std::mutex g_cancel_mutex;
// R3/B2: armazenamento POR DONO — vários pedidos pendentes coexistem (cancel A
// não é perdido quando cancel B chega) e cada job só consome o SEU. O pedido
// legado é anônimo (bool). Mapa limitado (MAX_CANCEL_OWNERS) para não crescer
// sem teto com sessões que nunca consumirem (job antigo já concluído).
// R6/fase E (E1): o flag anônimo virou atômico e os donos com pedido pendente
// têm um ESPELHO lock-free (g_cancel_owned_slots), porque o abort_callback roda
// A CADA OPERAÇÃO do grafo CPU e não pode tocar mutex (nem JNI). Mutadores
// continuam sob g_cancel_mutex e reconstroem o espelho (ordem: mutex -> slots;
// leitores só leem).
static std::atomic_bool g_cancel_anonymous(false);
static std::map<jlong, uint64_t> g_cancel_owned;        // dono -> ordem de chegada; guardado por g_cancel_mutex
static std::atomic<uint64_t> g_cancel_owned_seq(0);
static constexpr size_t MAX_CANCEL_OWNERS = 16;
static std::atomic<jlong> g_cancel_owned_slots[MAX_CANCEL_OWNERS]; // 0 = vazio; espelho só-leitura do mapa

/** Reconstrói o espelho lock-free (chamar com g_cancel_mutex preso). */
static void cancel_slots_rebuild_locked() {
    size_t i = 0;
    for (const auto & entry : g_cancel_owned) {
        if (i >= MAX_CANCEL_OWNERS) break;
        g_cancel_owned_slots[i].store(entry.first, std::memory_order_relaxed);
        ++i;
    }
    for (; i < MAX_CANCEL_OWNERS; ++i) {
        g_cancel_owned_slots[i].store(0, std::memory_order_relaxed);
    }
}

static std::map<jlong, std::string> g_status_by_owner;  // guardado por g_status_mutex
static std::string g_last_status_latest;                // W10: último status (leitura legada)

/** Pedido pendente DESTE dono, com g_cancel_mutex PRESO (D1 — a decisão do
 * ponto de commit precisa consultar mapa/slots, não só o espelho lock-free).
 * Mesma semântica de cancel_pending_for: anônimo vale para qualquer job. */
static bool cancel_pending_locked(jlong owner) {
    if (g_cancel_anonymous.load()) return true;
    if (owner == 0) return false;
    return g_cancel_owned.find(owner) != g_cancel_owned.end();
}

static void refresh_cancel_requested_locked() {
    g_cancel_requested.store(g_cancel_anonymous.load() || !g_cancel_owned.empty());
}

// ---------------------------------------------------------------------------
// D1 — PONTO DE COMMIT LINEARIZADO DA PUBLICAÇÃO (lado JNI, dono do pedido).
// <<< SIG_PUBLISH_COMMIT_GATE_BEGIN
//
// O motor (whisper.cpp) consulta este par begin/end ANTES de emitir os
// segmentos retidos de uma tentativa VAD aceita; a decisão e a emissão correm
// dentro da mesma zona de exclusão do pedido de cancelamento. Com isso não
// existe janela entre "ler o pedido" e "emitir callbacks":
//   * pedido já pendente no begin  => begin devolve 0; nada é emitido;
//   * pedido que chega com a zona aberta => request_cancel_owned ESPERA a zona
//     fechar e só então registra; logo ele é posterior à publicação e não a
//     revoga (regra do projeto: publicado antes do pedido é legítimo).
//
// ORDEM DE LOCKS (única): g_publish_commit_mutex -> g_cancel_mutex. Nenhum
// caminho pega g_cancel_mutex e depois o mutex da zona. O mutex do compute
// (g_mutex) NUNCA é tomado aqui: a zona é curta e cobre só decisão + emissão.
static std::mutex g_publish_commit_mutex;
// Profundidade da zona na thread corrente. Só a thread do compute que abriu a
// zona tem valor > 0; é o que impede auto-deadlock quando um pedido nasce
// DENTRO da própria publicação (callback Java -> cancel na mesma thread).
static thread_local int tls_publish_commit_depth = 0;

// Definida em whisper.cpp (mesma .so). Não é API pública nem contrato Java:
// símbolo C interno entre as duas TUs nativas.
typedef int  (*sig_publish_commit_begin_fn)(unsigned long long job, int attempt, void * opaque);
typedef void (*sig_publish_commit_end_fn)(unsigned long long job, int attempt, void * opaque);
extern "C" int sig_whisper_publish_commit_set(sig_publish_commit_begin_fn begin,
                                              sig_publish_commit_end_fn end,
                                              void * opaque);

namespace sig_publish_commit_gate {

// Um job por vez (o chamador é single-flight), mas o ponteiro é atômico para
// que registro/desregistro não corram com a leitura do motor.
struct job_scope {
    jlong owner = 0;
    unsigned long long job = 0;
    int attempt = 0;
    bool locked = false;      // zona aberta por ESTA thread
    bool committed = false;   // decisão já tomada (auditoria do job)
};

static int  gate_begin(unsigned long long job, int attempt, void * opaque);
static void gate_end(unsigned long long job, int attempt, void * opaque);

/** Decide, sob a exclusão do pedido: 1 = pode publicar (zona ABERTA e presa
 * até gate_end), 0 = descarta (nada foi preso). */
static int gate_begin(unsigned long long job, int attempt, void * opaque) {
    job_scope * scope = static_cast<job_scope *>(opaque);
    if (scope == nullptr) return 0;
    // Chamada aninhada é violação de contrato: recusa em vez de re-travar o
    // mutex (auto-deadlock). Nunca acontece no caminho de produção.
    if (tls_publish_commit_depth > 0) return 0;
    // Um job publica UMA vez (tentativa 1 aceita OU tentativa 2 aceita, nunca
    // as duas): uma segunda tentativa de commit no mesmo escopo é violação de
    // contrato e é recusada — nunca duplica flush.
    if (scope->committed) return 0;
    g_publish_commit_mutex.lock();
    bool pending = false;
    {
        std::lock_guard<std::mutex> lock(g_cancel_mutex);
        pending = cancel_pending_locked(scope->owner);
        if (!pending) {
            scope->job = job;
            scope->attempt = attempt;
            scope->committed = true;
        }
    }
    if (pending) {
        g_publish_commit_mutex.unlock();
        return 0;
    }
    scope->locked = true;
    ++tls_publish_commit_depth;
    return 1;
}

/** Fecha a zona aberta por gate_begin (só depois da emissão dos callbacks). */
static void gate_end(unsigned long long /*job*/, int /*attempt*/, void * opaque) {
    job_scope * scope = static_cast<job_scope *>(opaque);
    if (scope == nullptr || !scope->locked) return;
    scope->locked = false;
    if (tls_publish_commit_depth > 0) --tls_publish_commit_depth;
    g_publish_commit_mutex.unlock();
}

/** RAII por job: registra o dono do pedido antes do compute e desregistra em
 * TODO término (ok/erro/cancel/exceção), antes da barreira final. */
struct job_registration {
    job_scope scope;
    explicit job_registration(jlong owner) {
        scope.owner = owner;
        sig_whisper_publish_commit_set(&gate_begin, &gate_end, &scope);
    }
    ~job_registration() {
        sig_whisper_publish_commit_set(nullptr, nullptr, nullptr);
    }
    job_registration(const job_registration &) = delete;
    job_registration & operator=(const job_registration &) = delete;
};

} // namespace sig_publish_commit_gate
// <<< SIG_PUBLISH_COMMIT_GATE_END

// R13/B2 — pedido de cancelamento OWNED: FONTE UNICA do corpo. O entrypoint JNI
// (`cancelTranscriptionOwned`) e a ponte DIAG do trabalho em voo
// (ggml-vulkan, via registro de callback) chamam ESTE helper; o comportamento do
// entrypoint NAO muda (mesma insercao por dono, mesmo descarte do mais antigo,
// mesmo espelho lock-free, mesmas barreiras). Nunca espera g_mutex (rapido).
// owner <= 0 vira pedido ANONIMO, como antes.
// <<< SIG_CANCEL_OWNED_HELPER_BEGIN
static void request_cancel_owned(jlong owner) {
    // D1: o pedido NÃO pode se tornar visível no meio de uma zona de commit —
    // senão um segmento emitido dentro da zona sairia "depois do pedido".
    // Espera a zona fechar (curta: um flush) e só então registra. O pedido
    // originado DENTRO da própria publicação (callback Java -> cancel na mesma
    // thread) não espera por si mesmo: por construção ele é posterior ao
    // commit, e esperar seria auto-deadlock.
    std::unique_lock<std::mutex> commit_zone;
    if (tls_publish_commit_depth == 0) {
        commit_zone = std::unique_lock<std::mutex>(g_publish_commit_mutex);
    }
    std::lock_guard<std::mutex> lock(g_cancel_mutex);
    if (owner > 0) {
        g_cancel_owned[owner] = g_cancel_owned_seq.fetch_add(1) + 1;
        if (g_cancel_owned.size() > MAX_CANCEL_OWNERS) {
            // descarta o pedido mais antigo (ordem de chegada), não o novo
            auto oldest = g_cancel_owned.begin();
            for (auto it = g_cancel_owned.begin(); it != g_cancel_owned.end(); ++it) {
                if (it->second < oldest->second) oldest = it;
            }
            g_cancel_owned.erase(oldest);
        }
        cancel_slots_rebuild_locked();
    } else {
        g_cancel_anonymous.store(true);
    }
    refresh_cancel_requested_locked();
}
// <<< SIG_CANCEL_OWNED_HELPER_END

#ifdef SIG_WHISPER_DUMP_HOOK
// ---------------------------------------------------------------------------
// D1-físico — GATILHO DIAG DETERMINÍSTICO (lado DONO do pedido) do COMMIT DENIED.
// <<< SIG_COMMIT_RACE_TRIGGER_BEGIN
//
// Liga por SIG_WHISPER_COMMIT_RACE=1, por JOB OWNED e por UMA vez. No ponto
// exato "rc=0 do decode, antes do flush" (sig_commit_race_point em whisper.cpp)
// este bloco insere UM pedido de cancelamento pelo MESMO entrypoint do dono
// (request_cancel_owned — o corpo de cancelTranscriptionOwned e da ponte DIAG).
// Não existe flag forjada no gate: o gate lê g_cancel_owned como em produção e
// nega porque HÁ pedido. Nada é revogado: se algo já tivesse sido publicado,
// request_cancel_owned apenas esperaria a zona fechar (nunca despublica).
// Env ausente => o gancho não é instalado e o caminho é byte-idêntico ao de
// sempre (nenhum pedido, nenhum log, nenhum desvio).
namespace sig_commit_race {

typedef void (*sig_commit_race_fn)(unsigned long long job, int attempt, void * opaque);

// Definido em whisper.cpp (mesma .so). FRACO de propósito: se o motor não tiver
// o gancho, o arm é recusado em vez de quebrar o link.
extern "C" int sig_whisper_commit_race_set(sig_commit_race_fn fn, void * opaque) __attribute__((weak));

static std::atomic<jlong> g_owner{0};
static std::atomic_bool g_armed{false};
static std::atomic<int> g_fired{0};

static void trace(const char * ev, unsigned long long job, int attempt, const char * detail) {
    fprintf(stderr, "SIGCOMMIT ev=%s job=%llu attempt=%d owner=%lld fired=%d env=SIG_WHISPER_COMMIT_RACE=1 %s\n",
            ev, job, attempt, (long long) g_owner.load(std::memory_order_relaxed),
            g_fired.load(std::memory_order_relaxed), detail);
    fflush(stderr);
}

static void fire(unsigned long long job, int attempt, void * /*opaque*/) {
    if (!g_armed.load(std::memory_order_acquire)) return;
    const jlong owner = g_owner.load(std::memory_order_acquire);
    if (owner <= 0) return;                 // sem dono emitido não há pedido owned
    if (g_fired.fetch_add(1, std::memory_order_acq_rel) != 0) {
        trace("race_skip", job, attempt, "reason=already_fired_once");
        return;
    }
    trace("race_point", job, attempt, "where=after_decode_rc0_before_flush");
    // Entrypoint OWNED real: mesma inserção por dono, mesmo espelho, barreiras.
    request_cancel_owned(owner);
    trace("race_injected", job, attempt, "via=request_cancel_owned");
}

/** RAII por job: arma com o dono REAL e desarma em todo término. */
struct arm_guard {
    bool installed = false;
    explicit arm_guard(jlong owner) {
        const char * env = getenv("SIG_WHISPER_COMMIT_RACE");
        if (env == nullptr || strcmp(env, "1") != 0) return;
        g_owner.store(owner, std::memory_order_release);
        g_fired.store(0, std::memory_order_release);
        g_armed.store(true, std::memory_order_release);
        installed = (sig_whisper_commit_race_set != nullptr) &&
                    (sig_whisper_commit_race_set(&fire, nullptr) == 1);
        if (!installed) g_armed.store(false, std::memory_order_release);
        trace(installed ? "arm" : "arm_refused", 0, 0,
              installed ? "detail=hook_installed" : "detail=engine_hook_absent");
    }
    ~arm_guard() {
        if (!installed) return;
        sig_whisper_commit_race_set(nullptr, nullptr);
        g_armed.store(false, std::memory_order_release);
        trace("disarm", 0, 0, "via=job_scope_dtor");
        g_owner.store(0, std::memory_order_release);
    }
    arm_guard(const arm_guard &) = delete;
    arm_guard & operator=(const arm_guard &) = delete;
};

} // namespace sig_commit_race
// <<< SIG_COMMIT_RACE_TRIGGER_END
#endif // SIG_WHISPER_DUMP_HOOK

static void set_last_transcribe_status(const char * status, jlong owner) {
    {
        std::lock_guard<std::mutex> lock(g_status_mutex);
        g_last_transcribe_status = status;
        g_last_status_latest = status;
        g_status_by_owner[owner] = status;
        if (g_status_by_owner.size() > 8) {
            // std::map itera em ordem de chave: begin() é o dono mais antigo.
            g_status_by_owner.erase(g_status_by_owner.begin());
        }
    }
    // R3/B2: job com terminal registrado não tem mais nada a cancelar — um
    // pedido pendente DESTE dono (que chegou tarde ou depois do consumo) é
    // descartado aqui, sem afetar pedidos de outros donos.
    std::lock_guard<std::mutex> lock(g_cancel_mutex);
    if (g_cancel_owned.erase(owner) > 0) {
        cancel_slots_rebuild_locked();
        refresh_cancel_requested_locked();
    }
}

/** O pedido de cancelamento pendente vale para este dono?
 * R6/fase E (E1): caminho do abort_callback — SOMENTE atômicos, sem mutex e
 * sem JNI; o callback é consultado a cada operação do grafo. */
static bool cancel_pending_for(jlong owner) {
    if (!g_cancel_requested.load(std::memory_order_acquire)) return false;
    if (g_cancel_anonymous.load(std::memory_order_acquire)) return true;
    if (owner == 0) return false;
    for (size_t i = 0; i < MAX_CANCEL_OWNERS; ++i) {
        if (g_cancel_owned_slots[i].load(std::memory_order_acquire) == owner) return true;
    }
    return false;
}

/** Consome o pedido SE ele for deste dono (ou anônimo). Pedidos de OUTROS donos
 * permanecem pendentes — cada job consome somente o seu. */
static bool cancel_consume_for(jlong owner) {
    if (!g_cancel_requested.load()) return false;
    std::lock_guard<std::mutex> lock(g_cancel_mutex);
    if (g_cancel_anonymous.load()) {
        g_cancel_anonymous.store(false);
        refresh_cancel_requested_locked();
        return true;
    }
    if (g_cancel_owned.erase(owner) > 0) {
        cancel_slots_rebuild_locked();
        refresh_cancel_requested_locked();
        return true;
    }
    return false;
}

// R4/B2 — registro de donos EMITIDOS por beginSessionOwned. Sem isto, o
// nativo aceitava "qualquer número positivo" como owner (ex.: um ID fabricado
// ou de outro processo). O mapa é limitado: os donos são monotônicos, então o
// descarte do menor não afeta sessões novas.
static void note_issued_owner(jlong owner) {
    std::lock_guard<std::mutex> lock(g_issued_mutex);
    g_issued_owners.insert(owner);
    while (g_issued_owners.size() > MAX_ISSUED_OWNERS) {
        g_issued_owners.erase(g_issued_owners.begin());
    }
}

/** Este dono foi emitido pela lib? (leitura pontual; obter sob g_mutex para
 * decisões atômicas — a ordem de locks é sempre g_mutex → g_issued_mutex). */
static bool owner_is_issued(jlong owner) {
    std::lock_guard<std::mutex> lock(g_issued_mutex);
    return g_issued_owners.find(owner) != g_issued_owners.end();
}

/** RAII: marca a inferência em curso com o dono e zera em TODOS os returns. */
struct ActiveTranscribeGuard {
    explicit ActiveTranscribeGuard(jlong owner) {
        g_active_transcribe_owner.store(owner);
    }
    ~ActiveTranscribeGuard() {
        g_active_transcribe_owner.store(0);
    }
    ActiveTranscribeGuard(const ActiveTranscribeGuard &) = delete;
    ActiveTranscribeGuard & operator=(const ActiveTranscribeGuard &) = delete;
};

// W08: callbacks podem chegar de threads internas do ggml; o JNIEnv nunca é
// guardado entre chamadas — cada callback obtém o env da sua própria thread
// (anexando temporariamente quando necessário) via JavaVM capturado no load.
static JavaVM * g_vm = nullptr;
// Serializa o resumo de VAD e o envio de logs vindos de threads concorrentes.
static std::mutex g_callback_mutex;

extern "C" const char * sig_opencl_loader_last_error();

static const char * backend_label(int backend);
// R11/fase E (E1): fatos do device OpenCL publicados pelo backend (ggml-opencl).
// São a fonte da guarda de perfil — NUNCA parse de log (-1 = desconhecido).
extern "C" int sig_opencl_device_is_adreno(void);
extern "C" int sig_opencl_device_is_adreno_a7x(void);
extern "C" const char * sig_opencl_device_name(void);
extern "C" const char * sig_opencl_device_driver(void);
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

// R11/D2: CPUs PERMITIDAS ao processo agora (afinidade real), ou 0 se não for
// observável. A capacidade da política é a INTERSEÇÃO disso com o hardware: o
// D1 mediu o app OCIOSO com cpuset 0-2 e o job em foreground com 0-7 — pedir 8
// threads com afinidade restrita é o regime de spin/barreira (assinatura do
// "tempo anormal" do R10). No foreground (0-7) o resultado não muda.
static int available_cpus_for_process() {
    cpu_set_t set;
    CPU_ZERO(&set);
    if (sched_getaffinity(0, sizeof(set), &set) != 0) {
        return 0;                       // não observável ⇒ a política usa só o hardware
    }
    const int n = CPU_COUNT(&set);
    return n > 0 ? n : 0;
}

static void on_ggml_abort(const char * error_message) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GGML ABORT: %s", error_message);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void * reserved) {
    g_vm = vm;
    start_stderr_redirection();
    ggml_set_abort_callback(on_ggml_abort);
    return JNI_VERSION_1_6;
}

// W08: obtém o JNIEnv da thread corrente; anexa (e desanexa) só quando a
// chamada vem de uma thread nativa sem env. O JNIEnv recebido na entrada do
// JNI nunca é reutilizado fora daquela chamada.
class ScopedEnv {
public:
    ScopedEnv() : env_(nullptr), attached_(false) {
        if (g_vm == nullptr) return;
        const jint status = g_vm->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6);
        if (status == JNI_OK) return;
        if (status == JNI_EDETACHED && g_vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            attached_ = true;
        } else {
            env_ = nullptr;
        }
    }
    ~ScopedEnv() {
        if (attached_ && g_vm != nullptr) g_vm->DetachCurrentThread();
    }
    ScopedEnv(const ScopedEnv &) = delete;
    ScopedEnv & operator=(const ScopedEnv &) = delete;
    JNIEnv * get() const { return env_; }
    bool valid() const { return env_ != nullptr; }

private:
    JNIEnv * env_;
    bool attached_;
};

struct CallbackState {
    jobject callback; // referência global; liberada ao fim de transcribe
    jmethodID on_segment;
    jmethodID on_progress;
    jmethodID on_native_log;
    int vad_segments;
    double vad_reduction_seconds;
    double vad_reduction_percent;
    bool vad_summary_sent;
    // Observabilidade W08: thread que abriu a transcrição e contagem de
    // callbacks vindos de outras threads (reportada no resultado final).
    pthread_t home_thread;
    std::atomic<int> callback_calls;
    std::atomic<int> foreign_thread_calls;
    // R10/fase F (F2): dono (sessão/job) do transcribe — correlaciona os IDs
    // NATIVOS de tentativa (whisper.cpp: job/attempt) ao job/sessão do chamador.
    jlong owner = 0;
};

static void send_native_log(CallbackState * state, const char * text) {
    if (state == nullptr || state->callback == nullptr || state->on_native_log == nullptr || text == nullptr || text[0] == '\0') return;
    ScopedEnv scoped;
    if (!scoped.valid()) return;
    JNIEnv * env = scoped.get();
    state->callback_calls.fetch_add(1);
    if (!pthread_equal(pthread_self(), state->home_thread)) {
        state->foreign_thread_calls.fetch_add(1);
    }
    // Fix MUTF-8 (fase C): logs nativos podem trazer bytes crus (p.ex. nomes de
    // device/driver); o NewStringUTF do ART ABORTA com bytes fora do Modified
    // UTF-8. Sanitiza aqui antes do NewStringUTF.
    std::string sanitized = text;
    int replaced = 0;
    sig_mutf8::sanitize_utf8_for_jni(sanitized, &replaced, nullptr);
    if (replaced > 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "MUTF-8 sanitize (log nativo): %d sequencia(oes) invalida(s) -> U+FFFD", replaced);
    }
    jstring line = env->NewStringUTF(sanitized.c_str());
    env->CallVoidMethod(state->callback, state->on_native_log, line);
    env->DeleteLocalRef(line);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

// Fix MUTF-8 (fase C): TODA string vinda do modelo/backend passa por aqui antes
// de virar jstring. O ART valida Modified UTF-8 em NewStringUTF e ABORTA o
// processo (SIGABRT, ex.: s5 do laboratorio com backend Vulkan — sequencia
// truncada 0xE8 0x80). Substitui apenas sequencias invalidas por U+FFFD e
// registra a contagem (no callback, quando existir; senao no logcat).
// R6/fase E (E2): o núcleo recebe std::string por valor para que bytes crus com
// NUL embutido (0x00) cheguem INTEIROS ao sanitizador — a sonda de laboratório
// exercita esse caminho com a mesma função da produção; o caminho const char*
// continua idêntico para os chamadores existentes.
static jstring new_string_utf8_sanitized_std(JNIEnv * env, std::string sanitized, const char * source, CallbackState * log_target = nullptr) {
    int replaced = 0;
    int consumed = 0;
    sig_mutf8::sanitize_utf8_for_jni(sanitized, &replaced, &consumed);
    if (replaced > 0) {
        std::ostringstream warning;
        warning << "MUTF-8 sanitize (" << (source != nullptr ? source : "?") << "): "
                << replaced << " sequencia(oes) invalida(s) substituida(s) por U+FFFD ("
                << consumed << " bytes originais)";
        const std::string message = warning.str();
        if (log_target != nullptr) {
            send_native_log(log_target, message.c_str());
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", message.c_str());
        }
    }
    return env->NewStringUTF(sanitized.c_str());
}

static jstring new_string_utf8_sanitized(JNIEnv * env, const char * text, const char * source, CallbackState * log_target = nullptr) {
    return new_string_utf8_sanitized_std(env, text != nullptr ? std::string(text) : std::string(), source, log_target);
}

static bool starts_with(const char * text, const char * prefix) {
    return text != nullptr && prefix != nullptr && strncmp(text, prefix, strlen(prefix)) == 0;
}

static void on_whisper_log(ggml_log_level, const char * text, void * user_data) {
    auto * state = static_cast<CallbackState *>(user_data);
    if (text == nullptr || text[0] == '\0') return;

    log_to_android(text);

    if (starts_with(text, "whisper_vad")) {
        std::lock_guard<std::mutex> lock(g_callback_mutex);
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

    // R10/fase F (F2): correlaciona o rastro NATIVO da tentativa (job/attempt do
    // whisper.cpp) ao dono (sessão/job) do chamador. Como o JNI é single-flight
    // (g_mutex serializa o transcribe), o job nativo N corresponde ao N-ésimo
    // transcribe deste dono; a linha SIGVADATTEMPT amarra os dois IDs.
    if (state != nullptr && std::string(text).find("VAD job=") != std::string::npos) {
        std::ostringstream corr;
        corr << "SIGVADATTEMPT owner=" << static_cast<long long>(state->owner) << " " << text;
        const std::string correlated = corr.str();
        send_native_log(state, correlated.c_str());
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

// W07: parser WAV endurecido movido para whisper_wav_io.{h,cpp} (testável fora
// da fronteira JNI). Wrappers mantêm os nomes locais usados abaixo.
static_assert(WHISPER_SAMPLE_RATE == sig_wav::kSampleRate,
              "whisper_wav_io espera a taxa nativa do Whisper (16 kHz)");

static bool read_wav_mono_16k(const char * path, std::vector<float> & samples, std::string & error) {
    return sig_wav::read_wav_mono_16k(path, samples, error);
}

static bool write_wav_mono_16k(const char * path, const std::vector<float> & samples, std::string & error) {
    return sig_wav::write_wav_mono_16k(path, samples, error);
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

// R9/fase E (E2) — CONTRATO DA TENTATIVA (transação do whisper.cpp):
// o decode do PCM COMPOSTO (com VAD) é uma tentativa PROVISÓRIA; seus segmentos
// NÃO chegam aqui enquanto o gate de qualidade da tentativa não decidir. Se ela for
// ACEITA, whisper.cpp chama on_new_segment UMA vez com n_new=total (todos os
// segmentos da tentativa aceita — coordenadas do mapa composto, attempt_id=1); se
// for REJEITADA (junk ESTRUTURAL), on_new_segment NÃO é chamado para ela e o retry
// sem VAD streama com os callbacks reais. Garantias: (a) nenhum segmento da
// tentativa descartada é concatenado no artefato final; (b) sem duplicação/mistura
// de tentativas; (c) o callback de progresso segue ativo na tentativa (indicador de
// UI, não artefato). Ver tools/whisper/vad_attempt_transaction_test.cpp.
static void on_new_segment(struct whisper_context * ctx, struct whisper_state *, int n_new, void * user_data) {
    auto * state = static_cast<CallbackState *>(user_data);
    if (state == nullptr || state->callback == nullptr || state->on_segment == nullptr) return;

    ScopedEnv scoped;
    if (!scoped.valid()) return;
    JNIEnv * env = scoped.get();
    state->callback_calls.fetch_add(1);
    if (!pthread_equal(pthread_self(), state->home_thread)) {
        state->foreign_thread_calls.fetch_add(1);
    }

    const int segments = whisper_full_n_segments(ctx);
    const int first = std::max(0, segments - n_new);
    for (int i = first; i < segments; ++i) {
        const char * text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr || text[0] == '\0') continue;
        const int64_t t0_ms = whisper_full_get_segment_t0(ctx, i) * 10;
        const int64_t t1_ms = whisper_full_get_segment_t1(ctx, i) * 10;
        jstring segment = new_string_utf8_sanitized(env, text, "segmento", state);
        env->CallVoidMethod(state->callback, state->on_segment, segment, (jlong) t0_ms, (jlong) t1_ms);
        env->DeleteLocalRef(segment);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return;
        }
    }
}

static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void * user_data) {
    auto * state = static_cast<CallbackState *>(user_data);
    if (state == nullptr || state->callback == nullptr || state->on_progress == nullptr) return;

    ScopedEnv scoped;
    if (!scoped.valid()) return;
    JNIEnv * env = scoped.get();
    state->callback_calls.fetch_add(1);
    if (!pthread_equal(pthread_self(), state->home_thread)) {
        state->foreign_thread_calls.fetch_add(1);
    }
    env->CallVoidMethod(state->callback, state->on_progress, progress);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

// ---------------------------------------------------------------------------
// R6/fase E (E1) — rastro de cancelamento no lado JNI (SOMENTE build DIAG).
// ---------------------------------------------------------------------------
// Mesma porta (env SIG_WHISPER_CANCEL_TRACE=1) e formato do lado whisper.cpp:
// SIGCANCEL ev=request|callback|terminal ... t_us=<monotônico>. Usado apenas
// pelo laboratório para medir pedido -> callback observado -> retorno do
// compute -> terminal num único relógio. Compilado fora do build NORMAL.
#ifdef SIG_WHISPER_DUMP_HOOK
namespace sig_cancel_trace_jni {

inline bool enabled() {
    static const bool value = getenv("SIG_WHISPER_CANCEL_TRACE") != nullptr;
    return value;
}

inline void mark(const char * ev, jlong owner, const char * detail = nullptr) {
    if (!enabled()) {
        return;
    }
    const long long t = (long long) ggml_time_us();
    // R18/B1 — o logcat rotaciona em segundos; o rastro precisa SOBREVIVER.
    // Grava também em arquivo (SIG_CANCEL_TRACE_PATH), append por linha.
    static FILE * sink = nullptr;
    static bool sink_aberto = false;
    if (!sink_aberto) {
        sink_aberto = true;
        const char * path = getenv("SIG_CANCEL_TRACE_PATH");
        if (path != nullptr && path[0] != '\0') {
            sink = fopen(path, "a");
        }
    }
    if (sink != nullptr) {
        fprintf(sink, "%s ev=%s owner=%lld%s%s t_us=%lld\n", "SIGCANCEL", ev, (long long) owner,
                detail != nullptr ? " " : "", detail != nullptr ? detail : "", t);
        fflush(sink);
    }
    fprintf(stderr, "SIGCANCEL ev=%s owner=%lld%s%s t_us=%lld\n", ev, (long long) owner,
            detail != nullptr ? " " : "", detail != nullptr ? detail : "", t);
    fflush(stderr);
}

// Uma marca de "callback observado" por job (o callback é consultado a cada
// operação; sem isto o rastro de um job abortado repetiria a marca).
static std::atomic_bool g_observed_armed(false);

inline void arm_job_observation() {
    g_observed_armed.store(true);
}

inline void note_callback_observed(jlong owner) {
    if (!enabled()) return;
    bool expected = true;
    if (g_observed_armed.compare_exchange_strong(expected, false)) {
        mark("callback", owner);
    }
}

} // namespace sig_cancel_trace_jni

// ---------------------------------------------------------------------------
// R13/B2 — PONTE JNI do DIAG de trabalho em voo (ggml-vulkan).
// ---------------------------------------------------------------------------
// O ggml submete o lote compute em thread propria e NAO pode tocar estaticos
// desta TU (mapa de cancelamento, mutex) nem JNIEnv (a thread de submit nao e a
// thread Java). A API C do DIAG (declarada FRACA aqui: no build NORMAL ela nao
// existe e o ponteiro e null) recebe um callback e um opaque do REGISTRANTE;
// quando a fence dedicada esta em voo, o ggml chama a ponte com o owner do job e
// o pedido entra no MESMO caminho do entrypoint owned (request_cancel_owned).
// Tudo (macro, simbolo, strings) so existe no DIAG.
// <<< SIG_VK_INFLIGHT_JNI_BRIDGE_BEGIN
extern "C" int sig_vk_inflight_diag_register_cancel_bridge(
        void (* fn)(void * opaque, const char * owner, unsigned long long job, int attempt),
        void * opaque) __attribute__((weak));

namespace sig_vk_inflight_jni_bridge {

// Opaque EXPLICITO: identifica este registrante. O callback recusa qualquer
// outro ponteiro — a ponte nao depende de estado global implicito e nunca
// interpreta o opaque como dado.
struct context { const char * origin; };
static context g_context{"jni_cancel_owned"};

static std::atomic_bool g_registered(false);

/** Owner textual da ponte -> jlong, SOMENTE inteiro decimal positivo exato.
 * "0", "-3", "7x", " 12", vazio, nullptr e > 18 digitos devolvem 0 (invalido):
 * owner invalido NUNCA vira pedido anonimo (isso cancelaria qualquer job). */
static jlong owner_from_text(const char * text) {
    if (text == nullptr) return 0;
    const size_t len = strlen(text);
    if (len == 0 || len > 18) return 0;
    for (size_t i = 0; i < len; ++i) {
        if (text[i] < '0' || text[i] > '9') return 0;
    }
    const long long parsed = strtoll(text, nullptr, 10);
    return parsed > 0 ? static_cast<jlong>(parsed) : 0;
}

static void on_cancel_request(void * opaque, const char * owner, unsigned long long job, int attempt) {
    (void) job;      // identidade NATIVA da tentativa vive no rastro do ggml
    (void) attempt;
    if (opaque != &g_context) return;   // ponte de outro registrante: nao toca o estado
    const jlong parsed = owner_from_text(owner);
    if (parsed <= 0) return;
    sig_cancel_trace_jni::mark("inflight_request", parsed, "via=vk_fence_probe");
    request_cancel_owned(parsed);
}

/** Registro idempotente. A API devolve -2 quando o runtime do DIAG esta
 * desligado (SIG_WHISPER_VK_INFLIGHT_DIAG ausente em ggml), entao a tentativa
 * NAO e memorizada como definitiva: sem latch de falha na primeira chamada. */
static void register_bridge_if_needed() {
    if (g_registered.load(std::memory_order_acquire)) return;
    if (sig_vk_inflight_diag_register_cancel_bridge == nullptr) return;   // NORMAL
    if (sig_vk_inflight_diag_register_cancel_bridge(&on_cancel_request, &g_context) == 1) {
        g_registered.store(true, std::memory_order_release);
    }
}

} // namespace sig_vk_inflight_jni_bridge
// <<< SIG_VK_INFLIGHT_JNI_BRIDGE_END

// ---------------------------------------------------------------------------
// R13/B2 §1.3 — ARM/DISARM pela TENTATIVA REAL (opcao 2).
// ---------------------------------------------------------------------------
// A tentativa 2 do B3 NASCE em whisper.cpp, no MESMO ponto que emite
// "SIGVAD ev=begin job=N attempt=2". Ali o gancho entrega job/attempt REAIS e
// este bloco arma com:
//   * owner capturado na ENTRADA de whisper_full_parallel (thread_local), e
//   * backend Vulkan REAL do contexto, capturado na MESMA entrada, e
//   * os valores RECEBIDOS do evento (job/attempt).
// Nenhum attempt e inventado nesta TU; nada e rotulado por posicao/ordem.
// Os simbolos DIAG sao FRACOS (padrao do projeto): no NORMAL eles nao existem
// e este bloco inteiro nao compila.
// <<< SIG_INFLIGHT_ARM_BEGIN
extern "C" void * sig_whisper_vk_backend(struct whisper_context * ctx) __attribute__((weak));
extern "C" int sig_whisper_inflight_diag_set_attempt_hook(
        void (* fn)(unsigned long long, int, void *), void *) __attribute__((weak));
extern "C" int sig_vk_inflight_diag_arm(
        void * backend, const char * owner, unsigned long long job, int attempt) __attribute__((weak));
extern "C" void sig_vk_inflight_diag_disarm(void) __attribute__((weak));
extern "C" int sig_vk_inflight_diag_state(char * buf, int cap) __attribute__((weak));

namespace sig_inflight_arm {

// Estado do job ATUAL nesta thread: o owner e o backend sao capturados na
// ENTRADA do compute. thread_local de proposito — um sinal de tentativa vindo
// de OUTRA thread nao encontra escopo e NAO arma (nada de arm implicito/global).
struct scope {
    bool active = false;
    bool attempted = false;
    bool armed = false;
    bool rearm = false;
    jlong owner = 0;
    void * vk_backend = nullptr;
    unsigned long long job = 0;
    int attempt = 0;
    int arm_rc = 0;
};

static thread_local scope g_scope;

// Token EXPLICITO do registrante: o gancho recusa opaque de terceiros.
struct hook_token { const char * origin; };
static hook_token g_token{"jni_attempt2_hook"};
static std::atomic_bool g_hook_registered(false);

static void trace(const char * ev, const char * detail) {
    sig_cancel_trace_jni::mark(ev, g_scope.owner, detail);
}

static bool read_state(char * buf, int cap) {
    if (sig_vk_inflight_diag_state == nullptr || buf == nullptr || cap <= 0) return false;
    buf[0] = '\0';
    return sig_vk_inflight_diag_state(buf, cap) > 0;
}

// Disarm so limpa o slot quando NAO ha fence anexada ao ggml: por isso o rearme
// checa isto ANTES de tentar de novo (senao repetiria a recusa -4).
static bool fence_slot_free() {
    char state[256];
    if (!read_state(state, (int) sizeof state)) return false;
    return strstr(state, "attached=0") != nullptr;
}

static void on_attempt_begin(unsigned long long job, int attempt, void * opaque) {
    if (opaque != &g_token) return;        // gancho de outro registrante
    scope & s = g_scope;
    if (!s.active) return;                 // sem escopo nesta thread: nao arma
    if (attempt != 2) return;              // SO a tentativa 2 REAL arma
    if (s.owner <= 0) { trace("inflight_arm_skipped", "reason=no_owner"); return; }
    if (job == 0)     { trace("inflight_arm_skipped", "reason=no_job"); return; }
    if (s.vk_backend == nullptr) { trace("inflight_arm_skipped", "reason=no_vulkan_backend"); return; }
    if (sig_vk_inflight_diag_arm == nullptr) { trace("inflight_arm_skipped", "reason=no_diag_symbol"); return; }

    char owner_text[24];
    std::snprintf(owner_text, sizeof(owner_text), "%lld", (long long) s.owner);
    s.attempted = true;
    int rc = sig_vk_inflight_diag_arm(s.vk_backend, owner_text, job, attempt);
    if (rc == -4 && fence_slot_free()) {
        // Rearme SEGURO: -4 pode ser fence antiga pendente de drenagem. So
        // rearma quando o estado PROVA que nao ha fence anexada; caso contrario
        // a recusa fica registrada e NADA e apresentado como captura.
        if (sig_vk_inflight_diag_disarm != nullptr) sig_vk_inflight_diag_disarm();
        rc = sig_vk_inflight_diag_arm(s.vk_backend, owner_text, job, attempt);
        s.rearm = true;
    }
    s.armed = (rc == 1);
    s.arm_rc = rc;
    s.job = job;
    s.attempt = attempt;
    char detail[96];
    std::snprintf(detail, sizeof(detail), "job=%llu attempt=%d rc=%d rearm=%d",
                  job, attempt, rc, s.rearm ? 1 : 0);
    trace(s.armed ? "inflight_arm" : "inflight_arm_refused", detail);
}

// Fim do job: le o estado ANTES do disarm (disarm limpa o slot quando nao ha
// fence) e registra o desfecho. NUNCA eleva nada a testemunho.
static void end_scope() {
    scope & s = g_scope;
    if (!s.active) return;
    char state[256];
    const bool have_state = read_state(state, (int) sizeof state);
    char detail[320];
    std::snprintf(detail, sizeof(detail),
                  "armed=%d attempted=%d rc=%d job=%llu attempt=%d rearm=%d%s%s",
                  (int) s.armed, (int) s.attempted, s.arm_rc, s.job, s.attempt,
                  (int) s.rearm, have_state ? " state=" : "", have_state ? state : "");
    trace(s.armed ? "inflight_arm_end" : "inflight_arm_idle", detail);
    if (s.armed && have_state && strstr(state, "dispatch_count=0") != nullptr) {
        // Lote escolhido sem dispatch real: registra e NAO finge prova.
        trace("inflight_arm_no_dispatch", "verdict=NO_DISPATCH");
    }
    if (sig_vk_inflight_diag_disarm != nullptr) sig_vk_inflight_diag_disarm();
    s = scope{};
}

static void begin_scope(jlong owner, struct whisper_context * ctx) {
    g_scope = scope{};
    g_scope.owner = owner > 0 ? owner : 0;
    g_scope.vk_backend = (sig_whisper_vk_backend != nullptr) ? sig_whisper_vk_backend(ctx) : nullptr;
    g_scope.active = true;
    if (!g_hook_registered.load(std::memory_order_acquire)
        && sig_whisper_inflight_diag_set_attempt_hook != nullptr
        && sig_whisper_inflight_diag_set_attempt_hook(&on_attempt_begin, &g_token) == 1) {
        g_hook_registered.store(true, std::memory_order_release);
    }
}

// RAII: o destructor roda em TODO termino (sucesso, erro, cancel, excecao).
class job_scope_guard {
public:
    job_scope_guard(jlong owner, struct whisper_context * ctx) { begin_scope(owner, ctx); }
    ~job_scope_guard() { end_scope(); }
    job_scope_guard(const job_scope_guard &) = delete;
    job_scope_guard & operator=(const job_scope_guard &) = delete;
};

} // namespace sig_inflight_arm
// <<< SIG_INFLIGHT_ARM_END
#endif // SIG_WHISPER_DUMP_HOOK

static bool on_abort(void *) {
    // R6/fase E (E1): só testa o estado do DONO ATIVO — sem JNI e sem mutex
    // (cancel_pending_for é 100% atômico). Consulta por operação do grafo CPU.
    const jlong owner = g_active_transcribe_owner.load();
    const bool cancelled = cancel_pending_for(owner);
#ifdef SIG_WHISPER_DUMP_HOOK
    if (cancelled) {
        sig_cancel_trace_jni::note_callback_observed(owner);
    }
#endif
    return cancelled;
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

// ---------------------------------------------------------------------------
// R7/fase D (D4) — ESTADO ESTRUTURADO de Flash Attention (aviso + diagnóstico).
// O aviso NÃO infere mais por texto de log: estas linhas key=value são a ÚNICA
// fonte de "aplicado/execução". Ausência de linha => estado NÃO comprovado (o
// aviso diz isso ao usuário; nunca anuncia "GPU OpenCL" com FA no CPU).
//   SIG_FA_STATUS: fatos da CARGA (pedido, grafo, família, pré-checagem).
//   SIG_FA_EXEC:   contadores REAIS pós-job (execução GPU x fallback CPU).
// ---------------------------------------------------------------------------
extern "C" int sig_vk_fa_status(char * buf, int cap) __attribute__((weak));
// R14/C2 — o R6 (kernel próprio) está disponível? 1 = sim, 0 = não, -1 = ausente.
extern "C" int sig_vk_fa_r6_available(void) __attribute__((weak));
// R8/fase A (A2): snapshot CRU dos contadores (para delta por job) e contador
// de EXECUÇÃO no CPU (símbolo opcional — lib sem o hook responde indisponível
// e o JNI NUNCA converte recusa de placement em "CPU executado").
extern "C" int sig_vk_fa_counters(uint64_t * out, int n) __attribute__((weak));
extern "C" uint64_t sig_fa_cpu_exec_count(void) __attribute__((weak));

static std::string fa_kv_get(const std::string & line, const char * key) {
    const std::string need = std::string(key) + "=";
    size_t pos = 0;
    while ((pos = line.find(need, pos)) != std::string::npos) {
        if (pos == 0 || line[pos - 1] == ' ') {
            const size_t start = pos + need.size();
            if (start < line.size() && line[start] == '"') {
                const size_t end = line.find('"', start + 1);
                if (end != std::string::npos) {
                    return line.substr(start + 1, end - start - 1);
                }
            }
            const size_t end = line.find(' ', start);
            return line.substr(start, end == std::string::npos ? std::string::npos : end - start);
        }
        pos += need.size();
    }
    return std::string();
}

static std::string fa_backend_name(int backend_kind) {
    switch (backend_kind) {
        case 0: return "cpu";
        case 1: return "vulkan";
        case 2: return "opencl";
        default: return "backend" + std::to_string(backend_kind);
    }
}

struct fa_status_snapshot {
    bool have = false;
    // R8/A2: contadores 64-bit SEM estreitamento (atoi cortava em int).
    uint64_t fa_gpu = 0;            // execuções GPU totais (r6 + genérico)
    uint64_t fa_gpu_r6 = 0;
    uint64_t fa_gpu_generic = 0;
    uint64_t fa_cpu = 0;            // execuções REAIS no CPU (fonte: ggml-cpu)
    bool     cpu_available = false;
    uint64_t placement_refusals = 0;
    uint64_t r6_accepts = 0, generic_accepts = 0;
    uint64_t gen_failed = 0, sm_gpu = 0, sm_cpu = 0;
    std::string exec = "NOT_RUN";
    std::string fa_reason = "sem estado do backend";
    std::string sm_route = "indisponivel";
};

static fa_status_snapshot fa_status_read_backend() {
    fa_status_snapshot s;
    if (sig_vk_fa_status == nullptr) {
        return s;
    }
    char buf[768];
    const int n = sig_vk_fa_status(buf, (int) sizeof(buf));
    if (n <= 0) {
        return s;
    }
    const std::string line(buf, std::min<size_t>((size_t) n, sizeof(buf) - 1));
    s.have = true;
    auto to_u64 = [](const std::string & v) {
        return v.empty() ? 0ULL : strtoull(v.c_str(), nullptr, 10);
    };
    s.fa_gpu_r6   = to_u64(fa_kv_get(line, "fa_r6_dispatches"));
    s.fa_gpu_generic = to_u64(fa_kv_get(line, "fa_generic_dispatches"));
    s.fa_gpu      = s.fa_gpu_r6 + s.fa_gpu_generic;
    s.placement_refusals = to_u64(fa_kv_get(line, "fa_placement_refusals"));
    s.r6_accepts  = to_u64(fa_kv_get(line, "fa_r6_accepts"));
    s.generic_accepts = to_u64(fa_kv_get(line, "fa_generic_accepts"));
    s.gen_failed  = to_u64(fa_kv_get(line, "fa_generic_failed"));
    s.sm_gpu      = to_u64(fa_kv_get(line, "sm_gpu_wg64"));
    s.sm_cpu      = to_u64(fa_kv_get(line, "sm_cpu_refused"));
    // Execução CPU: SEMPRE da fonte de execução (backend CPU) — nunca da
    // contagem de recusas.
    s.fa_cpu = to_u64(fa_kv_get(line, "fa_cpu_executed"));
    s.cpu_available = fa_kv_get(line, "fa_cpu_exec_source") == "ggml-cpu";
    s.exec        = fa_kv_get(line, "fa_exec");
    s.fa_reason   = fa_kv_get(line, "fa_reason");
    s.sm_route    = fa_kv_get(line, "sm_route");
    if (s.exec.empty()) {
        s.exec = "NOT_RUN";
    }
    return s;
}

// R8/A2 — foto CRUA dos contadores para DELTA POR JOB (before/after dentro da
// mesma seção crítica g_mutex do transcribe: single-flight por processo ⇒ o
// delta é DESTE job, jamais do anterior/sucessor).
struct fa_counters_raw {
    bool have = false;
    uint64_t r6 = 0, generic = 0, refusals = 0, gen_failed = 0, r6_accept = 0, generic_accept = 0;
    uint64_t cpu = 0;
    bool cpu_available = false;
};

static fa_counters_raw fa_counters_snapshot() {
    fa_counters_raw c;
    if (sig_vk_fa_counters != nullptr) {
        uint64_t out[6] = {0, 0, 0, 0, 0, 0};
        if (sig_vk_fa_counters(out, 6) == 6) {
            c.have = true;
            c.r6 = out[0];
            c.generic = out[1];
            c.refusals = out[2];
            c.gen_failed = out[3];
            c.r6_accept = out[4];
            c.generic_accept = out[5];
        }
    }
    if (sig_fa_cpu_exec_count != nullptr) {
        c.cpu = sig_fa_cpu_exec_count();
        c.cpu_available = true;
    }
    return c;
}

// Classificação de EXECUÇÃO do job APENAS por execução REAL observada no delta,
// COM o CONTEXTO do backend SOLICITADO (R9/A2):
//  - CPU solicitado + FA CPU computada  => CPU_EXECUTED (execução normal, NÃO fallback);
//  - GPU solicitado + op FA executada no CPU => CPU_FALLBACK;
//  - GPU e CPU computados no mesmo job => MIXED;
//  - nenhuma computação => REFUSED_NO_EXEC (recusa deste job) / NOT_RUN / UNKNOWN.
// NÃO infere execução por supports_op: só contadores de compute reais (delta).
static std::string fa_job_exec_label(const fa_counters_raw & before, const fa_counters_raw & after,
                                     int backend_kind) {
    if (!after.have && !after.cpu_available) {
        return "UNKNOWN";
    }
    const uint64_t gpu = (after.r6 - before.r6) + (after.generic - before.generic);
    const uint64_t cpu = after.cpu_available ? (after.cpu - before.cpu) : 0;
    if (gpu > 0 && cpu > 0) {
        return "MIXED";
    }
    if (gpu > 0) {
        return "GPU_EXECUTED";
    }
    if (cpu > 0) {
        // R9/A2: o rótulo depende do backend PEDIDO — "fallback" só existe
        // quando se pediu GPU; CPU pedido + FA CPU é execução normal.
        return backend_kind == 0 ? "CPU_EXECUTED" : "CPU_FALLBACK";
    }
    if (after.refusals > before.refusals) {
        // Recusado pelo Vulkan NESTE job e nenhuma execução observada (CPU sem
        // hook, por exemplo): NÃO promete CPU_FALLBACK.
        return "REFUSED_NO_EXEC";
    }
    return "NOT_RUN";
}

// R9/A2 — backend EFETIVO do job, DERIVADO da execução real (não de supports_op).
static std::string fa_job_backend_eff(const std::string & exec, int backend_kind) {
    if (exec == "GPU_EXECUTED") {
        return fa_backend_name(backend_kind);
    }
    if (exec == "CPU_EXECUTED" || exec == "CPU_FALLBACK") {
        return "cpu";
    }
    if (exec == "MIXED") {
        return std::string(fa_backend_name(backend_kind)) + "+cpu";
    }
    return "none";
}

static std::string fa_status_line(const char * prefix, int backend_kind, bool requested, bool graph_fa,
                                  const char * preflight, const char * reason_hint, bool after_job) {
    const fa_status_snapshot s = fa_status_read_backend();
    const std::string backend = fa_backend_name(backend_kind);

    std::string family = "none";
    std::string exec = after_job ? s.exec : "NOT_RUN";
    std::string reason = reason_hint != nullptr ? reason_hint : "";
    if (requested) {
        if (backend_kind == 0) {
            family = "cpu_f32acc";
            if (after_job) {
                exec = "CPU_EXECUTED";
            }
        } else if (backend_kind == 1) {
            if (graph_fa) {
                family = s.have ? (s.gen_failed != 0 ? "vulkan_fa_r6_compact" : "vulkan_generic")
                                : "vulkan_unknown";
            } else {
                family = "none";
                if (reason.empty()) {
                    reason = "carga refeita com FA off";
                }
            }
        } else if (backend_kind == 2) {
            family = "opencl_guarded";
            if (reason.empty()) {
                reason = "W11: FA recusado no OpenCL (falha silenciosa)";
            }
        }
    }
    if (reason.empty() && s.have && !s.fa_reason.empty()) {
        reason = s.fa_reason;
    }
    if (reason.empty()) {
        reason = graph_fa ? "sem recusa registrada" : "nao aplicado";
    }
    const bool experimental = (family == "vulkan_fa_r6_compact");
    if (!after_job && family != "none" && exec.empty()) {
        exec = "NOT_RUN";
    }
    if (!after_job && graph_fa && backend_kind == 1) {
        exec = "NOT_RUN";
    }

    std::ostringstream out;
    out << prefix
        << " requested=" << (requested ? 1 : 0)
        << " backend=" << backend
        << " graph_fa=" << (graph_fa ? 1 : 0)
        << " family=" << family
        << " variant=" << (experimental ? "compacta_r6" : (family == "vulkan_generic" ? "generica" : "n/a"))
        << " preflight=" << (preflight != nullptr ? preflight : "n/a")
        << " exec=" << exec
        // R8/A2 — EXECUÇÃO (totais do processo; o POR-JOB vai na SIG_FA_EXEC):
        << " exec_fa_gpu=" << s.fa_gpu
        << " exec_fa_gpu_r6=" << s.fa_gpu_r6
        << " exec_fa_gpu_generic=" << s.fa_gpu_generic
        << " exec_fa_cpu=" << s.fa_cpu
        << " cpu_exec_source=" << (s.cpu_available ? "ggml-cpu" : "unavailable")
        // R8/A2 — PLACEMENT (decisões de supports_op; consultáveis VÁRIAS vezes
        // por op e NÃO são execução): recusa de família e aceites.
        << " placement_refusals=" << s.placement_refusals
        << " r6_accepts=" << s.r6_accepts
        << " generic_accepts=" << s.generic_accepts
        << " sm_gpu_wg64=" << s.sm_gpu
        << " sm_cpu_refused=" << s.sm_cpu
        << " sm_route=\"" << s.sm_route << "\""
        << " profile=" << (experimental ? "experimental_no_validated_advantage" : "standard")
        << " reason=\"" << reason << "\""
        << " evidence=structured_v2";
    return out.str();
}

// Troca (ou acrescenta) a linha de execução no log de carga, sem acumular.
static void fa_log_set_exec_line(const std::string & line) {
    std::string & log = g_last_load_log;
    const std::string prefix = "SIG_FA_EXEC";
    size_t pos = 0;
    while ((pos = log.find(prefix, pos)) != std::string::npos) {
        size_t eol = log.find('\n', pos);
        if (eol == std::string::npos) { eol = log.size(); } else { ++eol; }
        log.erase(pos, eol - pos);
        pos = 0;
    }
    if (!log.empty() && log.back() != '\n') {
        log += "\n";
    }
    log += line;
    log += "\n";
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
    // R11/fase E (E1) — os FATOS DO DEVICE (plataforma, device, driver, família
    // Adreno, limites) saem no log do PROBE, que até aqui era descartado no
    // caminho de sucesso. A guarda de perfil precisa deles ANTES do dispatch:
    // agora que a compilação de kernels é LAZY (D3), a carga real promove o
    // contexto validado e NÃO repete estas linhas — sem este acúmulo, o log de
    // carga sairia sem device/driver e a guarda não teria critério local.
    if (!probe_log.empty()) {
        // Só os FATOS do device (uma linha cada); o rastro de compilação (125
        // pontos) fica de fora do log de carga para não poluir terminal/relatório.
        static const char * const keep[] = {
            "selected platform:", "ggml_opencl: device:", "OpenCL driver:",
            "kernels optimized for Adreno", "default device:",
            "device FP16 support:", "global mem size:", "max mem alloc size:",
        };
        std::string facts;
        size_t pos = 0;
        while (pos <= probe_log.size()) {
            const size_t eol = probe_log.find('\n', pos);
            const std::string line = probe_log.substr(pos, eol == std::string::npos ? std::string::npos : eol - pos);
            for (const char * needle : keep) {
                if (line.find(needle) != std::string::npos) {
                    facts += line;
                    facts += "\n";
                    break;
                }
            }
            if (eol == std::string::npos) {
                break;
            }
            pos = eol + 1;
        }
        if (!facts.empty()) {
            if (!g_last_load_log.empty() && g_last_load_log.back() != '\n') {
                g_last_load_log += "\n";
            }
            g_last_load_log += facts;
        }
    }
    return true;
}

// R15/C1 — modo R6 pedido pelo ENSAIO ("" = nao definido). So o lab chama;
// a producao nunca define, entao o ambiente fica ausente => AUTO.
static std::string g_fa_r6_mode_env;

extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_setFaR6ModeEnv(JNIEnv * env, jobject, jstring mode) {
    if (mode == nullptr) {
        g_fa_r6_mode_env.clear();
        return;
    }
    const char * c = env->GetStringUTFChars(mode, nullptr);
    if (c != nullptr) {
        g_fa_r6_mode_env = c;
        env->ReleaseStringUTFChars(mode, c);
    }
}

static void apply_fa_r6_mode_env() {
    if (g_fa_r6_mode_env.empty()) return;
    setenv("GGML_VK_FA_R6", g_fa_r6_mode_env.c_str(), 1);
}

// R16/D3 — K0 focal: ativa o observador de beam + gravação TF (DIAG).
// O build NORMAL ignora (código compilado fora); o DIAG lê no primeiro uso.
extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_setBeamObsEnv(JNIEnv * env, jobject, jstring prefix) {
    if (prefix == nullptr) return;
    const char * c = env->GetStringUTFChars(prefix, nullptr);
    if (c != nullptr) {
        setenv("SIG_WHISPER_TF_RECORD", c, 1);
        setenv("SIG_WHISPER_BEAMOBS", "1", 1);
        env->ReleaseStringUTFChars(prefix, c);
    }
}

static void configure_vulkan_memory_limit(std::string & log) {
    constexpr unsigned long long block_bytes = 256ULL * 1024ULL * 1024ULL;

    // Trava de ensaio (rodada 1): o harness de benchmark define o perfil
    // completo via ambiente e sinaliza com SIG_WHISPER_VK_PROFILE_LOCK=1 para
    // que o produto NÃO reescreva as variáveis (caso contrário todo A/B de
    // perfil V0–V6 colapsaria em V0). Produção nunca define esta variável.
    if (getenv("SIG_WHISPER_VK_PROFILE_LOCK") != nullptr) {
        log += "Vulkan profile lock ativo (SIG_WHISPER_VK_PROFILE_LOCK): variáveis de ambiente não modificadas.\n";
        return;
    }

    // R15/C1 — modo R6 do ENSAIO (vazio em producao => AUTO).
    if (getenv("SIG_WHISPER_VK_PROFILE_LOCK") == nullptr) {
        apply_fa_r6_mode_env();
    }
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

// ---------------------------------------------------------------------------
// R11/fase E (E1) — GUARDA DE PERFIL OpenCL: RECUSA antes do dispatch
//
// Fato medido (R9 → R11; PJA110 / Adreno 740 / OpenCL 3.0 E031.41.03.36):
//   * base + OpenCL: estável (3/3 células); OpenCL explícito continua OK em
//     tiny/base (D3 §3.5).
//   * turbo/large (encoder 1500/PAD 1536 → ~1.063 nós) + OpenCL: o backend
//     enfileira o grafo do encoder (41 ms) e o processo NUNCA mais termina —
//     driver preso em `adreno_drawctxt_wait`, sessão pendurada e compositor
//     congelado (R10/D2). A CARGA-ONLY do turbo é estável ⇒ o gatilho é o
//     COMPUTE em escala turbo.
//   * memória NÃO é a causa: maior pedido do turbo 1.072.015.360 B < teto por
//     objeto 1.073.741.824 B (`oversized=0`, R10/D1).
//
// Critério LOCAL (não desabilita OpenCL nem modelos): backend PEDIDO = OpenCL
// + GPU da família Adreno geração A7X (o MESMO carve-out que o backend usa
// para escolher kernels) + classe de arquitetura large/turbo
// (n_audio_state ≥ 1280 E n_audio_layer ≥ 32, lidos do modelo REALMENTE
// carregado). tiny/base/small/medium (≤ 1024/24) seguem no OpenCL.
// ---------------------------------------------------------------------------
static const char * sig_ocl_guard_reason(int audio_state, int audio_layer) {
    // Família e geração vêm do BACKEND (fato, não parse de log): o mesmo
    // critério que escolhe os kernels Adreno/A7X.
    if (sig_opencl_device_is_adreno() != 1) {
        return nullptr;
    }
    if (sig_opencl_device_is_adreno_a7x() != 1) {
        return nullptr;
    }
    if (audio_state >= 1280 && audio_layer >= 32) {
        return "classe large/turbo (encoder 1500/PAD 1536, ~1063 nos): fila in-order sem retorno "
               "do driver Adreno (ultima op concluida i=1059 ADD; adreno_drawctxt_wait)";
    }
    return nullptr;
}

// Extrai `ggml_opencl: device: '<...>'` do log de carga (fato medido do device).
static std::string sig_ocl_device_from_log(const std::string & load_log) {
    const std::string key = "ggml_opencl: device: '";
    const size_t at = load_log.find(key);
    if (at == std::string::npos) {
        return "desconhecido";
    }
    const size_t from = at + key.size();
    const size_t to = load_log.find('\'', from);
    if (to == std::string::npos || to <= from) {
        return "desconhecido";
    }
    return load_log.substr(from, to - from);
}

// Backend seguro de reserva: **CPU**.
//
// Por que NÃO Vulkan (medido nesta fase, R11/E): depois de um contexto OpenCL
// COMPLETO ter sido criado e liberado no MESMO processo (kernels + buffers de
// 1,6 GB), o primeiro `vk::Queue::submit` do contexto Vulkan criado em seguida
// falha com `ErrorDeviceLost` — enquanto um job VULKAN PURO (sem OpenCL) no
// mesmo device/modelo/áudio conclui ok (controle `e1-ctl-vulkan`). O fallback
// para GPU fica, portanto, RECUSADO por medição; CPU não depende do estado da
// GPU e é a recusa PREVISÍVEL.
static int sig_ocl_guard_safe_backend() {
    return 0;
}

static jboolean load_model_impl(JNIEnv * env, jstring modelPath, jint backendKind, jboolean flashAttention, jlong owner) {
    // R4/B3: RAII — libera EXATAMENTE uma vez em qualquer caminho; falha de
    // aquisição (nullptr/exception pendente) NÃO vira caminho vazio: recusa.
    JniChars path_guard{JniEnvCharsAccessor{env, modelPath}};
    if (!path_guard.acquired()) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = "modelPath indisponivel (falha de aquisicao JNI)";
        return JNI_FALSE;
    }
    const std::string requested_path = path_guard.get();
    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error.clear();
    g_last_load_log.clear();
    // R4/B2: apenas donos EMITIDOS podem carregar, e a posse não retrocede
    // (substituir o contexto de um dono POSTERIOR seria rollback de lease).
    if (owner > 0 && !owner_is_issued(owner)) {
        g_last_error = "recusa nativa — owner nao emitido por beginSessionOwned";
        return JNI_FALSE;
    }
    if (owner > 0 && g_ctx != nullptr && owner < g_ctx_owner) {
        g_last_error = "recusa nativa — owner antigo (rollback de posse) no load";
        return JNI_FALSE;
    }
    // R3/B2: chave do contexto carregado (requested_path é cópia — o ponteiro
    // JNI é liberado pelo guard no fim desta função). Só vale se a carga concluir.
    g_ctx_model_path = requested_path;
    g_ctx_backend_kind = backendKind;
    g_ctx_backend_requested = backendKind;   // R11/E (E1): pedido da sessão (== efetivo, salvo recusa da guarda)
    g_ctx_flash_requested = flashAttention;
    g_ctx_model_bytes = 0;   // R10/G (G2): só vale se ESTA carga concluir
    // R11/D2: fatos de arquitetura da carga ANTERIOR deixam de valer aqui.
    g_ctx_arch_audio_state = 0;
    g_ctx_arch_audio_layer = 0;
    g_ctx_ftype = -1;

    if (g_ctx != nullptr) {
        // R2/B1: substituir contexto existente é permitido (a sessão nova
        // assume); o dono antigo deixa de valer.
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_ctx_owner = 0;
    }
    // W09: o cancelamento NÃO é apagado aqui — um pedido da sessão atual feito
    // durante o load deve sobreviver até a barreira da transcrição. O consumo
    // final (não vazar para a próxima sessão) é feito por beginSession().

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = backendKind != 0;
    // W11/W12 (rodada 1): Flash Attention em GPU reproduzia SIGABRT no Vulkan
    // (10/10 células) e falha silenciosa no OpenCL (transcrição vazia/decoder
    // 0ms). R2/fase C (C1): no VULKAN a guarda cega foi substituída pela
    // pré-checagem do motor (sig_vk_flash_attn_preflight, em whisper_model_load):
    // a tentativa com FA-on é feita; se as pipelines FA não compilam, a carga
    // falha com erro explícito e é REFEITA com FA off + aviso — recusa
    // antecipada LIMPA (NÃO é FA GPU funcionando). No OpenCL a guarda permanece:
    // a falha é silenciosa e a pré-checagem (minicomputação) não a detecta.
    const bool fa_vulkan_attempt = flashAttention == JNI_TRUE && backendKind == 1;
    if (flashAttention == JNI_TRUE && backendKind == 2) {
        g_last_load_log += "W11: Flash Attention desativado no OpenCL (falha silenciosa: transcrição vazia/decoder 0ms); FA off nesta carga.\n";
    }
    cparams.flash_attn = fa_vulkan_attempt || (flashAttention == JNI_TRUE && backendKind == 0);
    cparams.gpu_device = 0;
    if (backendKind == 1) {
        configure_vulkan_memory_limit(g_last_load_log);
    }
    if (cparams.use_gpu) {
        std::string gpu_error;
        int gpu_index = 0;
        if (!can_initialize_gpu_backend(backendKind, gpu_index, gpu_error)) {
            g_last_error = gpu_error;
            return JNI_FALSE;   // R4/B3: guard libera a string no escopo
        }
        cparams.gpu_device = gpu_index;
    }
    whisper_log_set(on_probe_log, &g_last_load_log);
    g_ctx = whisper_init_from_file_with_params(requested_path.c_str(), cparams);
    bool fa_refused_by_preflight = false;
    // R14/C2 — CICLO INICIAL DO FA: quando a família GENÉRICA reprova (o
    // Adreno 740 não compila `flash_attn_f32_f16_aligned`), o latch
    // `flash_attn_pipelines_failed` habilita a rota R6 — mas a carga com
    // `flash_attn=true` FALHA e o JNI desligava o FA INTEIRO, nunca tentando
    // o R6. Aqui: se o R6 está disponível, a carga é refeita do MESMO jeito
    // (o preflight do whisper decide) e, se ela falhar, o FA só é desligado
    // quando NENHUMA rota está disponível.
    if (g_ctx == nullptr && fa_vulkan_attempt) {
        const int r6_ok = (sig_vk_fa_r6_available != nullptr) ? sig_vk_fa_r6_available() : -1;
        g_last_load_log += "C2: FA generica reprovou; rota R6 " +
                           std::string(r6_ok == 1 ? "DISPONIVEL (mantendo FA)" : "indisponivel") +
                           " - se a carga falhar de novo, FA sera desligado.\n";
    }
    if (g_ctx == nullptr && fa_vulkan_attempt) {
        // R2/fase C (C1): a pré-checagem de FA em Vulkan recusou ANTES de a
        // primeira inferência abortar (ggml-backend.cpp:898). Refaz a carga
        // com FA off e registra o motivo no log de load (o chamador vê o aviso).
        g_last_load_log += "C1: Flash Attention recusado na pre-checagem do Vulkan "
                           "(pipelines FA nao compilam neste driver); refazendo a carga com FA off.\n";
        fa_refused_by_preflight = true;
        cparams.flash_attn = false;
        g_ctx = whisper_init_from_file_with_params(requested_path.c_str(), cparams);
    }
    whisper_log_set(nullptr, nullptr);

    if (g_ctx == nullptr) {
        g_last_error = cparams.use_gpu
            ? "não consegui carregar o modelo com " + std::string(backend_label(backendKind))
            : "não consegui carregar o modelo com CPU";
        return JNI_FALSE;
    }
    // R10/fase G (G2): fato do contexto para a política de threads — tamanho do
    // arquivo do modelo REALMENTE carregado (diagnóstico; a CLASSE da política
    // vem da arquitetura abaixo).
    // Se o stat falhar, fica 0 = desconhecido ⇒ a política cai no fallback 4.
    {
        struct stat model_stat;
        if (stat(requested_path.c_str(), &model_stat) == 0 && S_ISREG(model_stat.st_mode)) {
            g_ctx_model_bytes = static_cast<long long>(model_stat.st_size);
        }
    }
    // R11/D2: fatos de ARQUITETURA do CONTEXTO carregado (não do filename/bytes):
    // d_model e nº de camadas do encoder + tipo ggml dos pesos. São estes que
    // classificam o modelo para a política de threads (a classe MEDIDA é
    // large-v3/turbo; um q5_0 da mesma arquitetura tem 574 MB e escala igual).
    g_ctx_arch_audio_state = whisper_model_n_audio_state(g_ctx);
    g_ctx_arch_audio_layer = whisper_model_n_audio_layer(g_ctx);
    g_ctx_ftype            = whisper_model_ftype(g_ctx);
    // R7/fase D (D4): linha ESTRUTURADA do aviso de FA (fatos da CARGA). O aviso
    // (Kotlin) consome APENAS esta linha — nunca texto livre.
    g_ctx_flash_graph = cparams.flash_attn;
    // R11/fase E (E1) — GUARDA DE PERFIL: a sessão PEDIU OpenCL. Se o perfil for
    // o medido como inseguro (Adreno A7X + classe large/turbo), RECUSA TIPADA +
    // FALLBACK EXPLÍCITO, aqui, ANTES de qualquer dispatch (o primeiro compute
    // em escala turbo é o que pendura o driver e congela o compositor).
    // Nada está em uso neste ponto: o load é single-flight (g_mutex) e nenhuma
    // inferência deste contexto pode estar em curso.
    if (backendKind == 2) {
        const char * guard_reason = sig_ocl_guard_reason(g_ctx_arch_audio_state,
                                                         g_ctx_arch_audio_layer);
        // Evidência da AVALIAÇÃO (o critério sempre roda no OpenCL, e o veredito
        // é observável — inclusive "allow", para não se confundir "não recusado"
        // com "guarda não executada").
        {
            std::ostringstream eval;
            eval << "SIGOCL_LOAD_EVAL requested=opencl"
                 << " adreno=" << sig_opencl_device_is_adreno()
                 << " gen_a7x=" << sig_opencl_device_is_adreno_a7x()
                 << " arch_audio_state=" << g_ctx_arch_audio_state
                 << " arch_audio_layer=" << g_ctx_arch_audio_layer
                 << " ftype=" << g_ctx_ftype
                 << " unsafe_class=" << ((g_ctx_arch_audio_state >= 1280 && g_ctx_arch_audio_layer >= 32) ? 1 : 0)
                 << " verdict=" << (guard_reason != nullptr ? "refuse" : "allow");
            const std::string eval_line = eval.str() + "\n";
            log_to_android(eval_line.c_str());
        }
        if (guard_reason != nullptr) {
            std::string device = sig_opencl_device_name();
            if (device.empty()) {
                device = sig_ocl_device_from_log(g_last_load_log);
            }
            const int safe_backend = sig_ocl_guard_safe_backend();
            // Derruba o contexto OpenCL (nunca libera buffer/ctx em uso: nada
            // foi despachado) e recarrega no backend seguro.
            whisper_free(g_ctx);
            g_ctx = nullptr;
            g_ctx_owner = 0;
            whisper_context_params safe_params = whisper_context_default_params();
            safe_params.use_gpu = safe_backend != 0;
            // FA no fallback: no CPU, FA é capacidade VALIDADA (gate de CPU da
            // fase D3/B) e não depende da GPU; em GPU o fallback está recusado
            // acima. A guarda W11 (OpenCL) não é reaberta.
            safe_params.flash_attn = (safe_backend == 0) && (flashAttention == JNI_TRUE);
            safe_params.gpu_device = 0;
            if (safe_params.use_gpu) {
                std::string safe_error;
                int safe_index = 0;
                if (!can_initialize_gpu_backend(safe_backend, safe_index, safe_error)) {
                    // GPU indisponível depois do probe: cai para CPU (tipado).
                    g_last_load_log += "SIG_OCL_GUARD note=fallback_gpu_indisponivel reason=\"" + safe_error + "\"\n";
                    safe_params.use_gpu = false;
                    safe_params.flash_attn = (flashAttention == JNI_TRUE);
                } else {
                    safe_params.gpu_device = safe_index;
                }
            }
            whisper_log_set(on_probe_log, &g_last_load_log);
            g_ctx = whisper_init_from_file_with_params(requested_path.c_str(), safe_params);
            whisper_log_set(nullptr, nullptr);
            if (g_ctx == nullptr && safe_params.use_gpu) {
                // Fallback final: CPU (só alcançável se a GPU de reserva tiver
                // sido habilitada e falhado). Se nem isso carregar, é erro tipado.
                g_last_load_log += "SIG_OCL_GUARD note=fallback_gpu_falhou tentando=cpu\n";
                safe_params.use_gpu = false;
                safe_params.flash_attn = (flashAttention == JNI_TRUE);
                whisper_log_set(on_probe_log, &g_last_load_log);
                g_ctx = whisper_init_from_file_with_params(requested_path.c_str(), safe_params);
                whisper_log_set(nullptr, nullptr);
            }
            if (g_ctx == nullptr) {
                g_last_error = std::string("recusa de perfil OpenCL (") + guard_reason +
                               ") e nem o backend seguro carregou o modelo";
                return JNI_FALSE;
            }
            {
                std::ostringstream guard;
                guard << "SIG_OCL_GUARD requested=1 backend_req=" << backend_label(2)
                      << " backend_eff=" << backend_label(safe_backend)
                      << " arch_audio_state=" << g_ctx_arch_audio_state
                      << " arch_audio_layer=" << g_ctx_arch_audio_layer
                      << " ftype=" << g_ctx_ftype
                      << " flash_attn=" << (safe_params.flash_attn ? 1 : 0)
                      << " device=\"" << device << "\""
                      << " driver=\"" << sig_opencl_device_driver() << "\""
                      << " reason=\"" << guard_reason << "\""
                      << " evidence=structured_v2";
                if (!g_last_load_log.empty() && g_last_load_log.back() != '\n') {
                    g_last_load_log += "\n";
                }
                g_last_load_log += guard.str() + "\n";
                // Também no log do processo (logcat): a recusa tem de ser
                // observável fora do log de carga (o chamador pode não exibi-lo).
                const std::string guard_line = guard.str() + "\n";
                log_to_android(guard_line.c_str());
            }
            // EFETIVO != PEDIDO. O PEDIDO fica guardado para a chave de cache do
            // app (assumeContextOwned) e para o relatório.
            g_ctx_backend_requested = backendKind;
            g_ctx_backend_kind = safe_backend;
            g_ctx_flash_graph = false;
            g_ctx_arch_audio_state = whisper_model_n_audio_state(g_ctx);
            g_ctx_arch_audio_layer = whisper_model_n_audio_layer(g_ctx);
            g_ctx_ftype            = whisper_model_ftype(g_ctx);
            g_ctx_owner = owner;
            return JNI_TRUE;
        }
    }
    if (flashAttention == JNI_TRUE) {
        const char * preflight = fa_vulkan_attempt ? (fa_refused_by_preflight ? "refused" : "ok")
                                : (backendKind == 0 ? "skipped" : "guarded");
        const std::string fa_line = fa_status_line("SIG_FA_STATUS", backendKind, true, cparams.flash_attn,
                                                   preflight,
                                                   fa_refused_by_preflight ? "pré-checagem do Vulkan recusou FA (pipelines não compilam)" : nullptr,
                                                   false);
        if (!g_last_load_log.empty() && g_last_load_log.back() != '\n') {
            g_last_load_log += "\n";
        }
        g_last_load_log += fa_line + "\n";
    }
    // R2/B1: a sessão que carregou passa a ser dona do contexto.
    g_ctx_owner = owner;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_setThreadsOverride(JNIEnv *, jobject, jint threads) {
    // R11/D2 — SEAM DE BANCADA (one-shot) da política de threads. O produto NUNCA
    // chama isto (o objeto de seam de produção devolve 0). Valor 0 = desliga.
    // O valor vale para NO MÁXIMO o próximo job (consumido em transcribe).
    g_threads_override.set(static_cast<int>(threads));
    return static_cast<jint>(g_threads_override.peek());
}

extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_threadsOverridePeek(JNIEnv *, jobject) {
    // Diagnóstico do seam (bancada): 0 = inativo.
    return static_cast<jint>(g_threads_override.peek());
}

extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_setAudioCtxOverride(JNIEnv *, jobject, jint audio_ctx) {
    // R14/D2-D3 — contexto de encoder EXPERIMENTAL. 0 = desliga (default do
    // modelo). Vale para NO MÁXIMO o próximo job (consumido em transcribe).
    // R18/E1 — sem dono explícito, o pedido NÃO é aplicável (owner = -1).
    // R19/D1 — setter SEM dono nao publica nada e NAO limpa pedido de outro.
    // Antes ele apagava o slot global (o "CLEAR sem dono" da revisao).
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "lab_audio_ctx_legado_ignorado valor=%d", (int) audio_ctx);
    return 0;
}

// R18/E1 — pedido VINCULADO ao dono: só o job `owner` pode consumi-lo.
extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_setAudioCtxOverrideFor(
        JNIEnv *, jobject, jlong owner, jint audio_ctx) {
    const int slot = sig_ac_publish(g_ac_slots, SIG_AC_SLOTS,
                                    (int64_t) owner, (int) audio_ctx);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "lab_audio_ctx_publish owner=%lld valor=%d slot=%d",
        (long long) owner, (int) audio_ctx, slot);
    return (jint) (slot >= 0 ? audio_ctx : -1);
}

// R20/C1 — descarte do pedido do dono (cancelamento/erro antes do consumo).
extern "C" JNIEXPORT jboolean JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_cancelAudioCtxRequest(JNIEnv *, jobject, jlong owner) {
    const bool ok = sig_ac_cancel(g_ac_slots, SIG_AC_SLOTS, (int64_t) owner);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "lab_audio_ctx_cancel owner=%lld ok=%d", (long long) owner, ok ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_audioCtxOverridePeek(JNIEnv *, jobject) {
    // R19/D1 — devolve o valor pendente do PRIMEIRO slot ocupado (diagnóstico).
    for (int i = 0; i < SIG_AC_SLOTS; ++i) {
        const uint64_t w = g_ac_slots[i].load(std::memory_order_acquire);
        if (w != 0) {
            return sig_ac_value_of(w);
        }
    }
    return -1;
}

// R15/D2 — o ensaio informa as AMOSTRAS da janela; o contexto e' derivado.
// R24/REQ — publica a fotografia da tentativa (owner/attempt) ANTES do compute.
extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_publishAttemptOpts(
        JNIEnv *, jobject, jlong owner, jlong attempt, jlong window,
        jint submitMb, jint k, jint serial) {
    sig_attempt::Opts o;
    o.owner = owner; o.attempt = attempt; o.window = window;
    o.submit_mb = submitMb; o.k = k; o.serial = serial;
    const sig_attempt::Publish r = sig_attempt::global().publish(o);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "attempt_opts_publish owner=%lld attempt=%lld r=%d", (long long) owner,
        (long long) attempt, (int) r);
    return (jint) r;
}

extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_clearAttemptOpts(
        JNIEnv *, jobject, jlong owner, jlong attempt) {
    return sig_attempt::global().drop(owner, attempt) ? 1 : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_setAudioCtxWindow(JNIEnv *, jobject, jlong n_samples) {
    g_audio_ctx_window_samples.store(static_cast<long long>(n_samples));
    return static_cast<jlong>(g_audio_ctx_window_samples.load());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_loadModel(JNIEnv * env, jobject, jstring modelPath, jint backendKind, jboolean flashAttention) {
    return load_model_impl(env, modelPath, backendKind, flashAttention, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_loadModelOwned(JNIEnv * env, jobject, jstring modelPath, jint backendKind, jboolean flashAttention, jlong owner) {
    return load_model_impl(env, modelPath, backendKind, flashAttention, owner > 0 ? owner : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_assumeContextOwned(
        JNIEnv * env, jobject, jstring modelPath, jint backendKind, jboolean flashAttention, jlong owner) {
    // R3/B2: transferência ATÔMICA de posse do contexto já carregado, no ponto
    // protegido (g_mutex), SEM recarga — só aceita a MESMA chave real (caminho
    // do modelo + backend + FA solicitado) e um contexto existente. É o que
    // permite o fast-path de cache do Activity para jobs sucessivos ficar
    // rápido sem "cache falso": a validade é conferida aqui, na fonte de verdade.
    // R4/B3: a string do caminho é liberada por RAII em TODOS os retornos
    // (antes, esta função chamava GetStringUTFChars e NUNCA liberava). Falha de
    // aquisição (nullptr/exception pendente) NÃO vira caminho vazio: recusa.
    JniChars path_guard{JniEnvCharsAccessor{env, modelPath}};
    if (!path_guard.acquired()) {
        return JNI_FALSE;
    }
    const std::string requested_path = path_guard.get();
    std::lock_guard<std::mutex> lock(g_mutex);
    // R4/B2: dono precisa ser EMITIDO (beginSessionOwned) e a transferência não
    // pode retroceder — owner >= dono atual; assumir o dono atual é idempotente.
    // Qualquer número positivo fabricado, ou um owner ANTERIOR (pedido tardio
    // de sessão substituída), é recusado aqui sem tocar no contexto.
    const bool owner_valid = owner > 0 && owner_is_issued(owner);
    const bool no_rollback = owner >= g_ctx_owner;
    const bool ok = g_ctx != nullptr && owner_valid && no_rollback
              && g_ctx_model_path == requested_path
              && g_ctx_backend_requested == backendKind
              && g_ctx_flash_requested == flashAttention;
    if (ok) {
        g_ctx_owner = owner;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

static jstring transcribe_impl(
        JNIEnv * env,
        jstring wavPath,
        jstring language,
        jint beamSize,
        jint bestOf,
        jboolean wordTimestamps,
        jboolean vadFilter,
        jstring vadModelPath,
        jobject callback,
        jlong owner,
        const char * req_text = nullptr) {
    // R20/C1 — DESCARTE EM RETORNO PRECOCE: o pedido de audio_ctx do dono é
    // cancelado se a função sair antes do consumo (cancelamento, WAV inválido,
    // contexto ausente, erro de carga). Antes, ele ficava pendente e podia ser
    // herdado numa execução seguinte do mesmo processo.
    struct AudioCtxRequestGuard {
        jlong owner;
        bool consumed = false;
        ~AudioCtxRequestGuard() {
            if (!consumed) {
                sig_ac_cancel(g_ac_slots, SIG_AC_SLOTS, (int64_t) owner);
            }
        }
    } ac_guard{owner, false};

    // R25/REQ — PEDIDO DA TENTATIVA transportado NA chamada. Parse e validação
    // acontecem ANTES de qualquer trabalho: um pedido inválido/não suportado é
    // recusado de forma tipada, sem aplicar metade e sem consumir cancelamento.
    sig_req::Request req;
    bool tem_req = false;
    if (req_text != nullptr) {
        const sig_req::Result pr = sig_req::parse(req_text, &req);
        if (!pr.ok()) {
            const char * tipo = (pr.status == sig_req::Status::UNSUPPORTED) ? "UNSUPPORTED" : "INVALID";
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                "attempt_req_recusado tipo=%s chave=%s motivo=%s owner=%lld",
                tipo, pr.key_of_error.c_str(), pr.error.c_str(), (long long) owner);
            set_last_transcribe_status(pr.status == sig_req::Status::UNSUPPORTED
                                       ? "unsupported" : "error-request", owner);
            return new_string_utf8_sanitized(env,
                ("Erro: pedido da tentativa recusado (" + pr.error + ")").c_str(), "erro-request");
        }
        // R26/REQ — a IDENTIDADE do pedido tem de casar com a chamada: um pedido
        // de outro owner não pode ser aplicado neste contexto.
        if (req.owner != owner) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                "attempt_req_recusado tipo=INVALID motivo=owner_divergente pedido=%lld chamada=%lld",
                (long long) req.owner, (long long) owner);
            set_last_transcribe_status("error-request", owner);
            return new_string_utf8_sanitized(env,
                "Erro: pedido da tentativa de outro owner (recusado)", "erro-request");
        }
        tem_req = true;
    }
#ifndef SIG_WHISPER_DUMP_HOOK
    // NORMAL não tem política DIAG (K/serial/captura). Recusa tipada em vez de
    // aplicar meia política: o produto nunca manda pedido, o lab roda DIAG.
    if (tem_req && (req.k_set || req.serial_set || req.submit_set ||
                    req.cap == sig_req::CapMode::ON)) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "attempt_req_recusado tipo=UNSUPPORTED motivo=build NORMAL sem politica DIAG owner=%lld",
            (long long) owner);
        set_last_transcribe_status("unsupported", owner);
        return new_string_utf8_sanitized(env,
            "Erro: pedido da tentativa exige politica DIAG (build NORMAL)", "erro-request");
    }
#endif
    // W09 — barreira 1: cancelamento pedido antes de qualquer trabalho
    // (inclusive antes da leitura do WAV) é honrado, não apagado. R2/B1: só o
    // dono (ou um pedido anônimo) consome; pedido de outro dono não é apagado.
    // R3/B2: a marca de "inferência em curso" NÃO é instalada aqui — um job que
    // ainda espera pelo g_mutex não é executante e não pode se declarar ativo
    // (o abort de outro job não pode achar que este dono está rodando).
    if (cancel_consume_for(owner)) {
        set_last_transcribe_status("cancelled", owner);
        return new_string_utf8_sanitized(env, "Cancelado: transcrição cancelada", "cancelado");
    }
    // R4/B3: guardas RAII para TODAS as strings desta função (a antiga tinha
    // release manual espalhado por cada retorno; uma exceção C++ entre a
    // aquisição e o release vazava). Falha de aquisição do WAV não vira
    // caminho vazio: erro explícito.
    JniChars wav_guard{JniEnvCharsAccessor{env, wavPath}};
    std::vector<float> samples;
    std::string error;
    if (!wav_guard.acquired() || !read_wav_mono_16k(wav_guard.get(), samples, error)) {
        if (!wav_guard.acquired()) error = "caminho do WAV indisponivel (falha de aquisicao JNI)";
        // não vaza para a próxima transcrição (só consome se for deste dono)
        cancel_consume_for(owner);
        set_last_transcribe_status("error", owner);
        return new_string_utf8_sanitized(env, ("Erro: " + error).c_str(), "erro");
    }
    // W09 — barreira 2: entre a leitura do WAV e a aquisição do lock.
    if (cancel_consume_for(owner)) {
        set_last_transcribe_status("cancelled", owner);
        return new_string_utf8_sanitized(env, "Cancelado: transcrição cancelada", "cancelado");
    }
    JniChars language_guard{JniEnvCharsAccessor{env, language}};
    JniChars vad_guard{JniEnvCharsAccessor{env, vadModelPath}};
    const bool auto_language = !language_guard.acquired() || strcmp(language_guard.get(), "auto") == 0;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr) {
        return new_string_utf8_sanitized(env, "Erro: modelo não carregado", "erro");
    }
    // R3/B2: APÓS qualquer espera pelo g_mutex a fronteira reconfere o dono do
    // contexto. Uma sessão que esperou o mutex (load/release de outra sessão em
    // curso) e descobre que o contexto agora pertence a OUTRO dono é RECUSADA
    // aqui — nada de inferir no contexto alheio. O guard de execução só é
    // instalado depois desta checagem: ele representa a execução que pode ser
    // abortada, nunca um waiter.
    if (owner > 0 && g_ctx_owner != owner) {
        std::ostringstream mismatch;
        mismatch << "Erro: recusa nativa — contexto do dono " << g_ctx_owner
                 << " não pertence à sessão " << owner;
        set_last_transcribe_status("error", owner);
        return new_string_utf8_sanitized(env, mismatch.str().c_str(), "erro-owner");
    }
    ActiveTranscribeGuard active_owner(owner);
    // D1 — o dono do pedido de cancelamento arbitra o ponto de commit da
    // publicação: o par begin/end fica registrado no motor durante ESTE job e
    // é consultado antes de emitir segmentos retidos (tentativa VAD aceita).
    // O RAII desregistra em TODO término (ok/erro/cancel/exceção) — declarado
    // depois do guard do dono, portanto destruído ANTES dele. Nenhum mutex do
    // compute é tomado: o motor chama begin/end na própria thread do compute.
    sig_publish_commit_gate::job_registration publish_commit_gate(owner);
#ifdef SIG_WHISPER_DUMP_HOOK
    // D1-físico: gatilho DIAG do COMMIT DENIED (SIG_WHISPER_COMMIT_RACE=1).
    // Declarado DEPOIS do registro do gate, logo destruído ANTES dele: o gancho
    // nunca sobrevive ao escopo do job. Sem a env, é um no-op de duas instruções.
    sig_commit_race::arm_guard commit_race_armed(owner);
#endif
#ifdef SIG_WHISPER_DUMP_HOOK
    // R6/fase E (E1): rearma a marca de "callback observado" deste job (DIAG).
    sig_cancel_trace_jni::arm_job_observation();
#endif
    // Barreira 1b: não vale a pena montar callbacks/inferência se o pedido
    // chegou enquanto esperávamos o lock.
    if (cancel_consume_for(owner)) {
        set_last_transcribe_status("cancelled", owner);
        return new_string_utf8_sanitized(env, "Cancelado: transcrição cancelada", "cancelado");
    }

    jclass callback_class = env->GetObjectClass(callback);
    jobject callback_global = env->NewGlobalRef(callback);
    struct GlobalRefGuard {
        JNIEnv * env;
        jobject ref;
        ~GlobalRefGuard() {
            if (ref != nullptr && env != nullptr) env->DeleteGlobalRef(ref);
        }
    } callback_guard { env, callback_global };
    CallbackState callback_state {
        callback_global,
        env->GetMethodID(callback_class, "onSegment", "(Ljava/lang/String;JJ)V"),
        env->GetMethodID(callback_class, "onProgress", "(I)V"),
        env->GetMethodID(callback_class, "onNativeLog", "(Ljava/lang/String;)V"),
        0,
        0.0,
        0.0,
        false,
        pthread_self(),
        0,
        0
    };
    callback_state.owner = owner;   // R10/fase F (F2): correlação attempt <-> job/sessão
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
    params.language = auto_language ? "auto" : language_guard.get();
    params.detect_language = false;
    // R14/D2-D3 — CONTEXTO DE ENCODER EXPERIMENTAL (bancada): consumido
    // one-shot. 0/ausente = default do modelo (1500 posições / 30 s).
    // NUNCA habilitado no produto: o seam de produção devolve 0 e este bloco
    // só é alcançado quando o lab chamou o setter.
    {
        // R16/E2 — a JANELA vem do PCM REALMENTE processado, nao do setter.
        // O que o ensaio fornece e apenas o HABILITADOR (politica experimental);
        // a duracao declarada pelo Intent nao pode ser menor que a entrada.
        // R25/REQ — DECISÃO ÚNICA: quando o pedido veio na chamada, modo/valor
        // são lidos UMA vez da struct (nada de getter consumptivo chamado duas
        // vezes). A tabela lateral (R24) só atende o caminho legado, e o
        // atestado diz qual fonte valeu.
        const char * fonte = tem_req ? "pedido" : "default";
        sig_attempt::Opts att;
        bool tem_att = false;
        if (!tem_req && owner > 0) {
            tem_att = sig_attempt::global().take(owner, 0, &att);
            if (tem_att) {
                fonte = "tabela_legada";
            }
        }

        // --- política de submissão: aplicada UMA vez, com DEFAULT explícito ---
        if (tem_req) {
#ifdef SIG_WHISPER_DUMP_HOOK
            if (req.submit_set) {
                char b[16]; snprintf(b, sizeof(b), "%d", req.submit_mb);
                setenv("SIG_VK_SUBMIT_MB", b, 1);
            } else {
                unsetenv("SIG_VK_SUBMIT_MB");          // DEFAULT limpa o domínio
            }
            if (req.k_set) {
                char b[16]; snprintf(b, sizeof(b), "%d", req.k);
                setenv("SIG_VK_INFLIGHT_K", b, 1);
            } else {
                unsetenv("SIG_VK_INFLIGHT_K");
            }
            setenv("SIG_VK_SERIAL_BATCH", req.serial_set && req.serial == 1 ? "1" : "0", 1);
#endif
        } else if (tem_att) {
            if (att.submit_mb > 0) { char b[16]; snprintf(b, sizeof(b), "%d", att.submit_mb); setenv("SIG_VK_SUBMIT_MB", b, 1); }
            if (att.k > 0)         { char b[16]; snprintf(b, sizeof(b), "%d", att.k); setenv("SIG_VK_INFLIGHT_K", b, 1); }
            setenv("SIG_VK_SERIAL_BATCH", att.serial ? "1" : "0", 1);
        }

        // --- contexto de encoder: literal OU janela, decidido uma vez ---
        const int modelo_ac = whisper_model_n_audio_ctx(g_ctx);
        const sig_req::CtxMode ctx_modo = tem_req ? req.ctx
            : (tem_att && att.window > 0 ? sig_req::CtxMode::DEFAULT : sig_req::CtxMode::DEFAULT);
        const bool pede_janela = tem_req ? (req.win == sig_req::WinMode::WINDOW)
            : (tem_att && att.window > 0);
        const long long win = pede_janela ? (long long) samples.size() : -1;
        int ac_aplicado = 0;
        const char * ac_motivo = "default";

        if (pede_janela && win > 0) {
            const int ac_janela = sig_audio_ctx_de_janela(win, modelo_ac);
            if (ac_janela > 0) {
                params.audio_ctx = ac_janela;
                ac_aplicado = ac_janela;
                ac_motivo = "janela_pcm_real";
                __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                    "lab_audio_ctx_janela=%d amostras=%lld posicoes=%lld modelo=%d",
                    ac_janela, (long long) win,
                    (long long) ((win + 319) / 320), modelo_ac);
            } else {
                // RECUSA explicita: a janela excede a cobertura do modelo.
                ac_motivo = "janela_recusada_acima_do_modelo";
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                    "lab_audio_ctx_recusado amostras=%lld modelo=%d "
                    "(janela excede a cobertura; mantendo o default)",
                    (long long) win, modelo_ac);
            }
        }

        if (tem_req && req.ctx == sig_req::CtxMode::LITERAL) {
            if (req.ctx_value > 0 && req.ctx_value <= modelo_ac) {
                params.audio_ctx = req.ctx_value;
                ac_aplicado = req.ctx_value;
                ac_motivo = "literal_do_pedido";
            } else {
                ac_motivo = "literal_recusado_acima_do_modelo";
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                    "lab_audio_ctx_literal_recusado pedido=%d modelo=%d (mantendo o default)",
                    req.ctx_value, modelo_ac);
            }
        } else if (!tem_req) {
            // R18/E1 — pedido literal da tabela antiga: só o DONO consome.
            int valor_pedido = 0;
            if (sig_ac_take(g_ac_slots, SIG_AC_SLOTS, (int64_t) owner, &valor_pedido)) {
                ac_guard.consumed = true;   // consumido: o guard não descarta
                if (valor_pedido > 0) {
                    params.audio_ctx = valor_pedido;
                    ac_aplicado = valor_pedido;
                    ac_motivo = "literal_da_tabela_legada";
                }
            } else if (sig_ac_pending(g_ac_slots, SIG_AC_SLOTS) > 0) {
                __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                    "lab_audio_ctx_recusado job=%lld (pendentes de outro dono)",
                    (long long) owner);
            }
        }

        // --- captura: configurada a partir do MESMO pedido, antes do compute ---
#ifdef SIG_WHISPER_DUMP_HOOK
        if (tem_req) {
            if (req.cap == sig_req::CapMode::ON) {
                sig_dump::configure(req.cap_kinds.c_str(), req.cap_dir.c_str(),
                                    req.cap_tag.c_str(), req.cap_steps);
                sig_dump::configure_nodes(req.cap_first, req.cap_step, req.cap_max);
                sig_dump::reset_step_counter();   // R25/FIX — teto por tentativa
            } else {
                // cap=off é decisão explícita: fecha writers e desliga kinds.
                sig_dump::configure("", req.cap_dir.c_str(), req.cap_tag.c_str(), req.cap_steps);
            }
        }
#endif

        // --- atestado: pedido x aplicado, com identidade e fonte ---
        if (tem_req) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                "attempt_req token=%s owner=%lld gen=%lld job=%lld attempt=%lld "
                "ctx=%s literal=%d win=%s win_samples=%lld submit=%s%d serial=%s%d k=%s%d "
                "cap=%s kinds=%s tag=%s first=%d step=%d max=%d steps=%d "
                "producer=%s config=%s fonte=%s aplicado_audio_ctx=%d motivo=%s",
                req.token.c_str(), (long long) req.owner, (long long) req.gen,
                (long long) req.job, (long long) req.attempt,
                req.ctx == sig_req::CtxMode::LITERAL ? "literal" : "default",
                req.ctx_value,
                req.win == sig_req::WinMode::WINDOW ? "window" : "default",
                (long long) req.win_samples,
                req.submit_set ? "" : "default:", req.submit_set ? req.submit_mb : 0,
                req.serial_set ? "" : "default:", req.serial_set ? req.serial : 0,
                req.k_set ? "" : "default:", req.k_set ? req.k : 0,
                req.cap == sig_req::CapMode::ON ? "on" : "off",
                req.cap_kinds.c_str(), req.cap_tag.c_str(),
                req.cap_first, req.cap_step, req.cap_max, req.cap_steps,
                req.producer.c_str(), req.config.c_str(), fonte,
                ac_aplicado, ac_motivo);
        } else if (tem_att) {
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                "attempt_opts_legado owner=%lld attempt=%lld window=%lld submit_mb=%d k=%d serial=%d "
                "fonte=%s aplicado_audio_ctx=%d motivo=%s (sem attempt na chamada)",
                (long long) att.owner, (long long) att.attempt, (long long) att.window,
                att.submit_mb, att.k, att.serial, fonte, ac_aplicado, ac_motivo);
        }
        (void) ctx_modo;
    }
    // R10/fase G (G2) + R11/D2: POLÍTICA DE THREADS — decisão explícita, local
    // ao job, computada dos FATOS do contexto: backend efetivo + ARQUITETURA do
    // modelo carregado (d_model/camadas do encoder, não filename/bytes) + TIPO
    // dos pesos (F16 = classe medida) + capacidade OBSERVÁVEL (afinidade real do
    // processo ∩ hardware). Fallback 4 quando o contexto não sustenta o ganho.
    // Sem env/estado de processo.
    // R11/D2: o SEAM de bancada é consumido AQUI (um valor setado e não consumido
    // nunca sobrevive a um job). Observação MEDIDA no teste de 2 jobs no MESMO
    // processo: enquanto o extra do lab está setado, o CAMINHO DO LAB re-arma o
    // valor antes de cada job (WhisperLabSeams/WhisperDrive) — ou seja, o
    // "one-shot" vale para o nativo (o valor aplicado é consumido), não para o
    // campo do lab (que segue vencendo a tabela enquanto setado).
    sig::ModelFacts sig_facts;
    sig_facts.bytes         = g_ctx_model_bytes;
    sig_facts.n_audio_state = g_ctx_arch_audio_state;
    sig_facts.n_audio_layer = g_ctx_arch_audio_layer;
    sig_facts.ftype         = g_ctx_ftype;
    sig_facts.unknown       = (g_ctx_arch_audio_state <= 0 || g_ctx_arch_audio_layer <= 0);
    // R11/D2: capacidade observável = afinidade REAL do processo ∩ hardware.
    const int sig_avail_cpus = available_cpus_for_process();
    const sig::ThreadDecision sig_threads = sig::choose_threads(
            static_cast<int>(g_ctx_backend_kind),
            sig_facts,
            static_cast<int>(std::thread::hardware_concurrency()),
            g_threads_override.consume(),
            sig_avail_cpus);
    params.n_threads = sig_threads.effective;
    params.beam_search.beam_size = std::max(1, (int) beamSize);
    params.greedy.best_of = std::max(1, (int) bestOf);
    params.no_timestamps = false;
    params.token_timestamps = wordTimestamps == JNI_TRUE;
    params.vad = vadFilter == JNI_TRUE;
    params.vad_model_path = (vad_guard.acquired() && vad_guard.get()[0] != '\0') ? vad_guard.get() : nullptr;
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
                  << " threads=" << params.n_threads
                  << " threads_requested=" << sig_threads.requested
                  << " threads_capacity=" << sig_threads.capacity
                  << " threads_policy=" << sig_threads.policy
                  << " threads_source=" << sig_threads.source
                  << " threads_override=" << sig_threads.override_value
                  << " threads_avail_cpus=" << sig_avail_cpus
                  << " threads_capacity_restricted=" << (sig_threads.capacity_restricted ? "true" : "false")
                  << " threads_arch_audio_state=" << g_ctx_arch_audio_state
                  << " threads_arch_audio_layer=" << g_ctx_arch_audio_layer
                  << " threads_ftype=" << g_ctx_ftype
                  << " threads_scope=encoder+decoder+mel+autodetect"
                  << " threads_backend=" << backend_label(static_cast<int>(g_ctx_backend_kind))
                  << " threads_model_bytes=" << g_ctx_model_bytes
                  << " processors=1";
        send_native_log(&callback_state, param_log.str());
        std::ostringstream threads_log;
        threads_log << "SIGThreads requested=" << sig_threads.requested
                    << " effective=" << sig_threads.effective
                    << " capacity=" << sig_threads.capacity
                    << " policy=" << sig_threads.policy
                    << " source=" << sig_threads.source
                    << " override=" << sig_threads.override_value
                    << " avail_cpus=" << sig_avail_cpus
                    << " capacity_restricted=" << (sig_threads.capacity_restricted ? "true" : "false")
                    << " arch_audio_state=" << g_ctx_arch_audio_state
                    << " arch_audio_layer=" << g_ctx_arch_audio_layer
                    << " ftype=" << g_ctx_ftype
                    << " model_bytes=" << g_ctx_model_bytes
                    << " reason=\"" << sig_threads.reason << "\"";
        send_native_log(&callback_state, threads_log.str());
        // R10/G: além do callback (UI/Activity), o diagnóstico da decisão vai
        // TAMBÉM para o logcat — é o único caminho observável em bancada fora do
        // ART (harness --mode jni usa JNIEnv falso e não emite os logs nativos).
        log_to_android(threads_log.str().c_str());
    }
    whisper_log_set(on_whisper_log, &callback_state);
    // R8/fase A (A2): foto dos contadores ANTES da inferência — o delta
    // (after - before) é a EXECUÇÃO DESTE job. Contrato de concorrência: o
    // processo é single-flight (g_mutex serializa o transcribe aqui dentro);
    // nenhum contador de outro job/sessão entra no delta e nada é resetado.
#ifdef SIG_WHISPER_DUMP_HOOK
    // R13/B2: a ponte da fence em voo precisa estar REGISTRADA antes de qualquer
    // lote compute deste job. Registro idempotente e barato; no NORMAL o bloco
    // nem existe (simbolo fraco e emissor ausentes).
    sig_vk_inflight_jni_bridge::register_bridge_if_needed();
    // R13/B2 §1.3: escopo do job ABERTO na ENTRADA do compute — e daqui que sai
    // o owner (e o backend Vulkan REAL) usado pelo arm. O arm em si acontece
    // depois, no evento REAL de tentativa 2 emitido por whisper.cpp; o
    // destructor fecha o escopo em TODO termino (ok, erro, cancel, excecao).
    sig_inflight_arm::job_scope_guard sig_inflight_scope(owner, g_ctx);
#endif
    const fa_counters_raw fa_before = fa_counters_snapshot();
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

    // W09 — barreira final: um cancelamento pedido durante a inferência (mesmo
    // que o motor tenha terminado antes de observar) não deixa a sessão ser
    // completada como sucesso; o pedido é consumido aqui e não vaza para a
    // próxima transcrição. R2/B1: cancelamento com dono diferente NÃO é
    // consumido nem apagado aqui.
    const bool cancelled = cancel_consume_for(owner);
    // R6/fase E (E1): retorno explícito de abort do motor (definido em
    // whisper.cpp: SIG_WHISPER_FULL_ABORTED) é cancelamento — nunca falha
    // numérica — mesmo quando o pedido já foi consumido por uma barreira.
    const bool engine_aborted = (whisper_result == kWhisperFullAborted);

    // R8/fase A (A2): SIG_FA_EXEC é atualizada em TODOS os terminais do job
    // (ok/no_speech/cancel/erro) — com o DELTA DESTE job. Cancel/recusa sem op
    // NÃO herdam a execução do job anterior (a linha antiga é substituída).
    const fa_counters_raw fa_after = fa_counters_snapshot();
    if (g_ctx_flash_requested == JNI_TRUE) {
        const uint64_t job_r6 = fa_after.r6 - fa_before.r6;
        const uint64_t job_generic = fa_after.generic - fa_before.generic;
        const uint64_t job_refusals = fa_after.refusals - fa_before.refusals;
        const uint64_t job_cpu = fa_after.cpu_available && fa_before.cpu_available
                                     ? (fa_after.cpu - fa_before.cpu) : 0;
        const std::string family = (g_ctx_backend_kind == 0) ? "cpu_f32acc"
                                 : (g_ctx_backend_kind == 1)
                                       ? (g_ctx_flash_graph ? (fa_after.gen_failed != 0 ? "vulkan_fa_r6_compact"
                                                                     : "vulkan_generic")
                                                            : "none")
                                       : (g_ctx_backend_kind == 2 ? "opencl_guarded" : "backend");
        const std::string variant = family == "vulkan_fa_r6_compact" ? "compacta_r6"
                                  : family == "vulkan_generic" ? "generica" : "n/a";
        // R16/D2 — família EFETIVA (da execução): reconcilia o metadado de
        // construção do grafo (`family`) com os contadores do job. Grafo
        // genérico que executou 100% dispatches R6 é efetivamente R6 — o
        // `family` de construção é preservado (mensagem do produto não muda),
        // e a verdade de execução vai em `exec_family`.
        const std::string exec_family =
            (job_r6 > 0 && job_generic == 0) ? "vulkan_fa_r6_exec"
          : (job_generic > 0 && job_r6 == 0) ? "vulkan_generic_exec"
          : (job_r6 > 0 && job_generic > 0)  ? "vulkan_misto_exec"
          : family;
        // R9/A2 — classificação COM contexto do backend SOLICITADO + efetivo real.
        const std::string job_exec = fa_job_exec_label(fa_before, fa_after, g_ctx_backend_kind);
        std::ostringstream exec_log;
        exec_log << "SIG_FA_EXEC"
                 << " requested=1 backend=" << fa_backend_name(g_ctx_backend_kind)
                 << " backend_req=" << fa_backend_name(g_ctx_backend_kind)
                 << " backend_eff=" << fa_job_backend_eff(job_exec, g_ctx_backend_kind)
                 << " graph_fa=" << (g_ctx_flash_graph ? 1 : 0)
                 << " family=" << family
                 << " variant=" << variant
                 << " exec_family=" << exec_family
                 << " exec=" << job_exec
                 << " job_gpu_r6=" << job_r6
                 << " job_gpu_generic=" << job_generic
                 << " job_cpu=" << job_cpu
                 << " cpu_exec_source=" << (fa_after.cpu_available ? "ggml-cpu" : "unavailable")
                 << " job_refusals=" << job_refusals
                 << " owner=" << (long long) owner
                 << " totals_gpu_r6=" << fa_after.r6
                 << " totals_gpu_generic=" << fa_after.generic
                 << " totals_cpu=" << fa_after.cpu
                 << " totals_refusals=" << fa_after.refusals
                 << " sm_route=\"" << fa_status_read_backend().sm_route << "\""
                 << " profile=" << (family == "vulkan_fa_r6_compact"
                                        ? "experimental_no_validated_advantage" : "standard")
                 << " reason=\"" << (fa_status_read_backend().fa_reason) << "\""
                 << " evidence=structured_v2";
        fa_log_set_exec_line(exec_log.str());
    } else {
        // R8/A2: FA não pedido => NENHUMA linha de execução de job anterior pode
        // ficar visível (um job sem FA não herda a execução do anterior).
        // R9/A2: backend_req/eff explícitos (eff=none — nada foi computado).
        fa_log_set_exec_line(std::string("SIG_FA_EXEC requested=0 backend=") +
                             fa_backend_name(g_ctx_backend_kind) +
                             " backend_req=" + fa_backend_name(g_ctx_backend_kind) +
                             " backend_eff=none graph_fa=0 family=none exec=NOT_REQUESTED evidence=structured_v2");
    }

    if (whisper_result != 0) {
        if (cancelled || engine_aborted) {
            set_last_transcribe_status("cancelled", owner);
#ifdef SIG_WHISPER_DUMP_HOOK
            sig_cancel_trace_jni::mark("terminal", owner, engine_aborted ? "result=cancelled engine_aborted=1" : "result=cancelled");
#endif
            return new_string_utf8_sanitized(env, "Cancelado: transcrição cancelada", "cancelado");
        }
        set_last_transcribe_status("error", owner);
#ifdef SIG_WHISPER_DUMP_HOOK
        sig_cancel_trace_jni::mark("terminal", owner, "result=error");
#endif
        return new_string_utf8_sanitized(env, "Erro: falha na transcrição", "erro");
    }
    if (cancelled) {
        set_last_transcribe_status("cancelled", owner);
#ifdef SIG_WHISPER_DUMP_HOOK
        sig_cancel_trace_jni::mark("terminal", owner, "result=cancelled");
#endif
        return new_string_utf8_sanitized(env, "Cancelado: transcrição cancelada", "cancelado");
    }

    const int segments = whisper_full_n_segments(g_ctx);
    std::ostringstream result_log;
    result_log << "native result: segments=" << segments
               << " callbacks=" << callback_state.callback_calls.load()
               << " foreign_thread_callbacks=" << callback_state.foreign_thread_calls.load();
    send_native_log(&callback_state, result_log.str());
    std::string result;
    for (int i = 0; i < segments; ++i) {
        const char * text = whisper_full_get_segment_text(g_ctx, i);
        if (text != nullptr) {
            if (!result.empty()) result += "\n";
            result += text;
        }
    }

    // W10: resultado estruturado — texto vazio com inferência bem-sucedida é
    // "no_speech" (definido), não erro genérico; texto real continua "ok" mesmo
    // quando começa com "Erro:"/"Cancelado:" (o consumidor deve usar o status).
    // R8/A2: a SIG_FA_EXEC do job já foi atualizada ACIMA (todos os terminais,
    // delta real) — nada de reescrever aqui com totais.
    if (segments == 0 || result.find_first_not_of(" \t\r\n") == std::string::npos) {
        set_last_transcribe_status("no_speech", owner);
#ifdef SIG_WHISPER_DUMP_HOOK
        sig_cancel_trace_jni::mark("terminal", owner, "result=no_speech");
#endif
    } else {
        set_last_transcribe_status("ok", owner);
#ifdef SIG_WHISPER_DUMP_HOOK
        sig_cancel_trace_jni::mark("terminal", owner, "result=ok");
#endif
    }
    return new_string_utf8_sanitized(env, result.c_str(), "resultado", &callback_state);
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
    return transcribe_impl(env, wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath, callback, 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_transcribeOwned(
        JNIEnv * env,
        jobject,
        jstring wavPath,
        jstring language,
        jint beamSize,
        jint bestOf,
        jboolean wordTimestamps,
        jboolean vadFilter,
        jstring vadModelPath,
        jlong owner,
        jobject callback) {
    return transcribe_impl(env, wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter, vadModelPath, callback, owner > 0 ? owner : 0);
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_transcribeOwnedReq(
        JNIEnv * env,
        jobject,
        jstring wavPath,
        jstring language,
        jint beamSize,
        jint bestOf,
        jboolean wordTimestamps,
        jboolean vadFilter,
        jstring vadModelPath,
        jlong owner,
        jobject callback,
        jstring request) {
    // R25/REQ — ADITIVA: a fotografia da tentativa viaja NESTA chamada. Sem
    // request ilegível não há fallback silencioso para o caminho legado.
    JniChars req_guard{JniEnvCharsAccessor{env, request}};
    if (!req_guard.acquired()) {
        set_last_transcribe_status("error-request", owner > 0 ? owner : 0);
        return new_string_utf8_sanitized(env,
            "Erro: pedido da tentativa ilegivel (JNI)", "erro-request");
    }
    return transcribe_impl(env, wavPath, language, beamSize, bestOf, wordTimestamps, vadFilter,
                           vadModelPath, callback, owner > 0 ? owner : 0, req_guard.get());
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
        return new_string_utf8_sanitized(env, ("ERRO|" + error).c_str(), "erro-vad");
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
    if (!ok) return new_string_utf8_sanitized(env, ("ERRO|" + error).c_str(), "erro-vad");

    std::ostringstream result;
    result << "OK|" << input.size() << "|" << filtered.size() << "|" << segments << "|" << backend;
    return new_string_utf8_sanitized(env, result.str().c_str(), "vad-result");
}

extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_ownershipApiVersion(JNIEnv *, jobject) {
    // R2/B1: capacidade de ownership por sessão. Lib antiga não exporta este
    // símbolo; o controller Kotlin cai no modo legado (UnsatisfiedLinkError).
    // R4/B2 (v4): donos emitidos (g_issued_owners) + lease monotônico (sem
    // retrocesso de posse) + RAII de strings JNI.
    return 4;
}

extern "C" JNIEXPORT jlong JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_contextOwner(JNIEnv *, jobject) {
    // R3/B2: dono do contexto carregado (0 = nenhum/legado). Permite à camada
    // Kotlin conferir EXISTÊNCIA + dono no fast-path de cache do modelo.
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_ctx == nullptr ? 0 : g_ctx_owner;
}

extern "C" JNIEXPORT jlong JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_activeTranscribeOwner(JNIEnv *, jobject) {
    // R3/B2: dono da inferência EXECUTANDO agora (0 = nenhuma). Diagnóstico:
    // um job esperando o g_mutex NÃO aparece aqui.
    return g_active_transcribe_owner.load();
}

extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_cancelTranscription(JNIEnv *, jobject) {
    // W09: cancelamento nunca espera o mutex de inferência — apenas marca o
    // pedido atômico observado por abort_callback e pelas barreiras.
    // R2/B1: o pedido legado é anônimo (dono 0) — vale para qualquer inferência.
    std::lock_guard<std::mutex> lock(g_cancel_mutex);
    g_cancel_anonymous.store(true);
    refresh_cancel_requested_locked();
#ifdef SIG_WHISPER_DUMP_HOOK
    sig_cancel_trace_jni::mark("request", 0, "via=anonymous");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_cancelTranscriptionOwned(JNIEnv *, jobject, jlong owner) {
    // R3/B2: pedido vinculado ao dono em armazenamento POR DONO — um cancel(A)
    // pendente não é sobrescrito por cancel(B) e cada job consome apenas o seu.
    // Rápido: nunca espera g_mutex.
    // R13/B2: corpo extraido para request_cancel_owned (mesmo caminho da ponte
    // DIAG do trabalho em voo). Comportamento e ordem das operacoes inalterados.
    request_cancel_owned(owner);
#ifdef SIG_WHISPER_DUMP_HOOK
    sig_cancel_trace_jni::mark("request", owner, "via=owned");
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_beginSession(JNIEnv *, jobject) {
    // W09: fronteira de sessão — um pedido de cancelamento de uma sessão
    // anterior não pode vazar para esta, e o consumo em transcribe não apaga
    // pedidos da sessão atual. Retorna o identificador de sessão (diagnóstico).
    // Legado mantém o comportamento atual (limpa qualquer pedido pendente).
    static std::atomic<uint64_t> session_counter(0);
    std::lock_guard<std::mutex> lock(g_cancel_mutex);
    g_cancel_anonymous.store(false);
    g_cancel_owned.clear();
    cancel_slots_rebuild_locked();
    refresh_cancel_requested_locked();
    return static_cast<jlong>(session_counter.fetch_add(1) + 1);
}

extern "C" JNIEXPORT jlong JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_beginSessionOwned(JNIEnv *, jobject) {
    // R2/B1: fronteira de sessão com dono. NÃO apaga cancelamentos com dono
    // (owner > 0) nem o pedido de uma inferência em curso; limpa SOMENTE o
    // cancelamento anônimo ocioso (o resíduo legado entre sessões).
    // R4/B2: registra o dono como EMITIDO — único caminho de emissão de tokens.
    const jlong session = ++g_session_counter;
    note_issued_owner(session);
    std::lock_guard<std::mutex> lock(g_cancel_mutex);
    if (g_cancel_anonymous.load() && g_active_transcribe_owner.load() == 0) {
        g_cancel_anonymous.store(false);
        refresh_cancel_requested_locked();
    }
    return session;
}

// R14/B4 — define a variável de ambiente que LIGA o DIAG de trabalho em voo.
// Só o build de DIAG a expõe (o símbolo não existe no NORMAL). O lab a chama
// ANTES de carregar a lib, porque `getenv` é lido uma única vez (static init).
extern "C" void sig_whisper_diag_setenv(const char * name, const char * value) {
    setenv(name, value, 1);
}

extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_setDiagEnv(JNIEnv * env, jobject, jstring name, jstring value) {
    const char * n = name == nullptr ? nullptr : env->GetStringUTFChars(name, nullptr);
    const char * v = value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
    if (n != nullptr && v != nullptr) {
        setenv(n, v, 1);
    }
    if (n != nullptr) env->ReleaseStringUTFChars(name, n);
    if (v != nullptr) env->ReleaseStringUTFChars(value, v);
    return 1;
}

// R14/B4 — MEDIÇÃO do trabalho em voo, legível por qualquer consumidor da lib
// (o harness de bancada): submits, nós, bytes de mul_mat e o maior lote de
// nós por submit do ÚLTIMO grafo. Não é autorreferente e não altera nada.
extern "C" int sig_vk_b4_inflight_state(char * buf, int cap) __attribute__((weak));

extern "C" int sig_whisper_b4_inflight_state(char * buf, int cap) {
    if (buf == nullptr || cap <= 0) return 0;
    buf[0] = '\0';
    if (sig_vk_b4_inflight_state == nullptr) return -1;
    return sig_vk_b4_inflight_state(buf, cap);
}

extern "C" int sig_cl_submit_state(char * buf, int cap) __attribute__((weak));

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_clSubmitState(JNIEnv * env, jobject) {
    // R17/D2 — submissões do backend OPENCL (o b4 mede só o Vulkan).
    char buf[256];
    const int n = (sig_cl_submit_state != nullptr) ? sig_cl_submit_state(buf, (int) sizeof(buf)) : 0;
    if (n <= 0) {
        return env->NewStringUTF("cl_state=unavailable");
    }
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_b4InflightState(JNIEnv * env, jobject) {
    // R14/B4 — MEDIÇÃO do trabalho em voo (submits/nós/bytes do último grafo).
    char buf[768];
    const int n = sig_whisper_b4_inflight_state(buf, (int) sizeof(buf));
    if (n <= 0) {
        return env->NewStringUTF("b4_state=unavailable");
    }
    return env->NewStringUTF(buf);
}

// R19/A4 — BACKEND EFETIVO do contexto carregado (pos-fallback), nao o pedido.
// `g_ctx_backend_kind` ja guarda o efetivo; separar isso do pedido permite
// abortar ANTES do compute quando o pedido nao foi honrado.
extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_effectiveBackendKind(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jint>(g_ctx_backend_kind);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_effectiveFlashGraph(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_ctx_flash_graph ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_faRouteStatus(JNIEnv * env, jobject) {
    // R14/C2 — ROTA FA OBSERVÁVEL no artefato: contadores do backend Vulkan
    // (r6/generic/refusals/cpu) como string compacta para o lab registrar.
    char buf[768];
    const int n = (sig_vk_fa_status != nullptr) ? sig_vk_fa_status(buf, (int) sizeof(buf)) : 0;
    if (n <= 0) {
        return env->NewStringUTF("fa_status=unavailable");
    }
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_lastTranscribeStatus(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_status_mutex);
    return new_string_utf8_sanitized(env, g_last_status_latest.c_str(), "status");
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_lastTranscribeStatusOwned(JNIEnv * env, jobject, jlong owner) {
    // R2/B1: status do dono — pareia texto+status do mesmo job; "" quando o
    // dono não tem status registrado (o consumidor cai no fallback por prefixo).
    std::lock_guard<std::mutex> lock(g_status_mutex);
    const auto it = g_status_by_owner.find(owner);
    return new_string_utf8_sanitized(env, it != g_status_by_owner.end() ? it->second.c_str() : "", "status-owner");
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_systemInfo(JNIEnv * env, jobject) {
    const char * info = whisper_print_system_info();
    return new_string_utf8_sanitized(env, info != nullptr ? info : "", "system-info");
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_buildInfo(JNIEnv * env, jobject) {
    const std::string output = build_configuration_summary();
    return new_string_utf8_sanitized(env, output.c_str(), "build-info");
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_backendInfo(JNIEnv * env, jobject) {
    const std::string output = backend_diagnostics();
    return new_string_utf8_sanitized(env, output.c_str(), "backend-info");
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_lastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return new_string_utf8_sanitized(env, g_last_error.c_str(), "last-error");
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_lastLoadLog(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return new_string_utf8_sanitized(env, g_last_load_log.c_str(), "last-load-log");
}

// R8/fase A (A2) — marcador `faJobBegin`: INÍCIO DE JOB no log nativo. Delimita
// o estado FA do job ATUAL: leitores (FlashAttentionNotice/fa_job_state) só
// consideram linhas SIG_FA_STATUS/SIG_FA_EXEC APÓS o último marcador — um job
// sem carga própria (contexto reutilizado) e sem transcribe NÃO herda a
// execução do job anterior. NUNCA escreve contadores: é só o delimitador.
// R22/B3 — DUMP e POLITICA configurados DENTRO do processo. Sem estes
// bindings o lab nao tem como ligar o gatilho (getenv do adb shell nao alcanca
// um app ja iniciado pelo zygote) e o dump nunca era escrito.
#include "sig_whisper_dump.h"

extern "C" {
void sig_vk_set_inflight_k(int k);
void sig_vk_set_serial(int on);
}

extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_sigDumpConfigure(
        JNIEnv * env, jobject, jstring kinds, jstring dir, jstring tag, jint steps) {
#ifdef SIG_WHISPER_DUMP_HOOK
    const char * k = kinds != nullptr ? env->GetStringUTFChars(kinds, nullptr) : nullptr;
    const char * d = dir != nullptr ? env->GetStringUTFChars(dir, nullptr) : nullptr;
    const char * t = tag != nullptr ? env->GetStringUTFChars(tag, nullptr) : nullptr;
    const int r = sig_dump::configure(k, d, t, steps);
    if (k) env->ReleaseStringUTFChars(kinds, k);
    if (d) env->ReleaseStringUTFChars(dir, d);
    if (t) env->ReleaseStringUTFChars(tag, t);
    return r;
#else
    (void) env; (void) kinds; (void) dir; (void) tag; (void) steps;
    return 0;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_sigDumpConfigureNodes(
        JNIEnv *, jobject, jint first, jint step, jint maxNodes) {
#ifdef SIG_WHISPER_DUMP_HOOK
    return sig_dump::configure_nodes(first, step, maxNodes);
#else
    (void) first; (void) step; (void) maxNodes;
    return 0;
#endif
}

// Em NORMAL os simbolos de politica DIAG nao existem: a chamada fica guardada
// (recusa em vez de quebrar o link do flavor de producao).
extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_setInflightK(JNIEnv *, jobject, jint k) {
#ifdef SIG_WHISPER_DUMP_HOOK
    sig_vk_set_inflight_k(k);
#else
    (void) k;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_setSerialBatch(JNIEnv *, jobject, jint on) {
#ifdef SIG_WHISPER_DUMP_HOOK
    sig_vk_set_serial(on);
#else
    (void) on;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_faJobBegin(JNIEnv * env, jobject, jstring route) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string line = "faJobBegin";
    if (route != nullptr) {
        const char * r = env->GetStringUTFChars(route, nullptr);
        if (r != nullptr) {
            std::string route_str(r);
            env->ReleaseStringUTFChars(route, r);
            for (size_t i = 0; i < route_str.size(); ++i) {
                if (route_str[i] == '\n' || route_str[i] == '\r') route_str[i] = ' ';
            }
            if (!route_str.empty()) {
                line += " route=";
                line += route_str;
            }
        }
    }
    if (!g_last_load_log.empty() && g_last_load_log.back() != '\n') {
        g_last_load_log += "\n";
    }
    g_last_load_log += line;
    g_last_load_log += "\n";
    return JNI_TRUE;
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
        g_ctx_owner = 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_br_gov_sp_pcsp_launcher_WhisperNative_releaseModelOwned(JNIEnv *, jobject, jlong owner) {
    // R2/B1: só libera o contexto se o dono ainda for este (reconferido sob
    // g_mutex, imediatamente antes do free). Dono diferente => JNI_FALSE sem
    // tocar no contexto (um release atrasado de outra sessão vira no-op).
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr || owner == 0 || g_ctx_owner != owner) {
        return JNI_FALSE;
    }
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
    g_ctx_owner = 0;
    return JNI_TRUE;
}

// ---------------------------------------------------------------------------
// R6/fase E (E2) — SONDA DE LABORATÓRIO (SOMENTE build DIAG).
// ---------------------------------------------------------------------------
// A travessia bytes crus -> sanitizador -> jstring e a volta GetStringUTFChars
// (MUTF-8 que o ART emite) usam as MESMAS funções do caminho de produção
// (sig_mutf8::sanitize_utf8_for_jni e NewStringUTF). Estes símbolos NÃO existem
// na lib NORMAL (check de hooks=0 e de exports no SUMMARY-E); o app de produto
// não os referencia — só o cenário s15 do flavor whisperlab (APK de lab).
#ifdef SIG_WHISPER_DUMP_HOOK
extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_whisperlab_LabUtf8Probe_sanitize(JNIEnv * env, jobject, jbyteArray raw) {
    if (raw == nullptr) {
        return env->NewStringUTF("");
    }
    const jsize len = env->GetArrayLength(raw);
    std::string bytes(static_cast<size_t>(len), '\0');
    if (len > 0) {
        env->GetByteArrayRegion(raw, 0, len, reinterpret_cast<jbyte *>(&bytes[0]));
    }
    // Núcleo de produção: NUL cru (0x00) NÃO trunca aqui (std::string íntegra);
    // o sanitizador o substitui por U+FFFD, como no caminho do resultado.
    return new_string_utf8_sanitized_std(env, std::move(bytes), "lab-utf8", nullptr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_whisperlab_LabUtf8Probe_mutf8Back(JNIEnv * env, jobject, jstring text) {
    // Volta REAL: o ART emite MUTF-8 em GetStringUTFChars (CESU-8 para fora do
    // BMP; C0 80 para U+0000). Devolve comprimento + hex para o cenário
    // comparar ida/volta sem converter nada no meio.
    if (text == nullptr) {
        return env->NewStringUTF("{\"mutf8_len\":0,\"hex\":\"\"}");
    }
    const char * chars = env->GetStringUTFChars(text, nullptr);
    if (chars == nullptr) {
        return env->NewStringUTF("{\"mutf8_len\":-1,\"hex\":\"\"}");
    }
    const size_t n = strlen(chars); // MUTF-8 não tem byte 0x00 dentro (NUL é C0 80)
    static const char * digits = "0123456789abcdef";
    std::string hex;
    hex.reserve(n * 2 + 48);
    for (size_t i = 0; i < n; ++i) {
        const unsigned char c = static_cast<unsigned char>(chars[i]);
        hex.push_back(digits[c >> 4]);
        hex.push_back(digits[c & 0x0f]);
    }
    env->ReleaseStringUTFChars(text, chars);
    std::string out = "{\"mutf8_len\":" + std::to_string(n) + ",\"hex\":\"" + hex + "\"}";
    return env->NewStringUTF(out.c_str()); // ASCII puro — sem risco de MUTF-8
}
#endif // SIG_WHISPER_DUMP_HOOK
