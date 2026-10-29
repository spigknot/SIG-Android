// R2/fase C (C2) — implementação dos dumps diagnósticos.
// Compilado SOMENTE quando SIG_WHISPER_DUMP_HOOK está definido (build de
// diagnóstico). Escreve arquivos crus .bin em SIG_WHISPER_DUMP_DIR.

#include "sig_whisper_dump.h"
#include "sig_dump_fileops.h"

#ifdef SIG_WHISPER_DUMP_HOOK

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

namespace {

// R22/B3 — configuracao EXPLICITA. A lista de kinds era cacheada na primeira
// chamada e `setenv` depois disso nao tinha efeito (o app ja nasceu com o
// ambiente do zygote). Um setter in-process escreve os valores e reinicia o
// cache: e o gatilho que faltava para capturar dump no processo do lab.
std::mutex g_cfg_mutex;
bool g_cfg_loaded = false;
std::string g_cfg_kinds;

// R28/A4 — IDENTIDADE DE SESSÃO DE CAPTURA.
// O diretório/tag são capturados UMA vez no configure e ficam presos à sessão:
// trocar o ambiente para B não redireciona A. A criação do diretório é
// EXCLUSIVA (EEXIST => recusa tipada), então uma tag repetida não seleciona o
// mesmo destino em silêncio.
struct Session {
    std::string id;
    std::string dir;
    std::string tag;
    bool active = false;
};
std::mutex g_sess_mutex;
Session g_sess;
std::atomic<uint64_t> g_sess_seq{0};
std::atomic<bool> g_recusado{false};   // R28/A4: recusa DESLIGA a captura
// R28/A4 — conjunto de operações de arquivo usado pelo caminho REAL (o mesmo
// que o teste de host injeta). Default: libc.
sig_dump_io::FileOps g_fileops;
std::vector<std::string> g_escritos;   // (sessao/nome) já gravados

std::string dump_dir() {
    const char * dir = getenv("SIG_WHISPER_DUMP_DIR");
    return (dir != nullptr && dir[0] != '\0') ? dir : "/data/local/tmp/whisper-bench-r2/dumps";
}

std::string dump_tag() {
    {
        std::lock_guard<std::mutex> lk(g_sess_mutex);
        if (g_sess.active && !g_sess.tag.empty()) {
            return g_sess.tag;          // R28/A4: valor CONGELADO da sessão
        }
    }
    const char * tag = getenv("SIG_WHISPER_DUMP_TAG");
    return (tag != nullptr && tag[0] != '\0') ? tag : "run";
}

// SIG_WHISPER_DUMP é lida uma única vez (thread-safe).
bool kind_enabled(const char * kind) {
    std::lock_guard<std::mutex> lock(g_cfg_mutex);
    std::string list;
    if (!g_cfg_loaded) {
        const char * value = getenv("SIG_WHISPER_DUMP");
        g_cfg_kinds = value != nullptr ? value : "";
        g_cfg_loaded = true;
    }
    list = g_cfg_kinds;
    if (list.empty()) {
        return false;
    }
    const std::string needle = kind;
    size_t pos = 0;
    while (pos <= list.size()) {
        const size_t comma = list.find(',', pos);
        const std::string token = list.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
        if (token == needle) {
            return true;
        }
        if (comma == std::string::npos) {
            break;
        }
        pos = comma + 1;
    }
    return false;
}

int max_steps() {
    const char * value = getenv("SIG_WHISPER_DUMP_STEPS");
    return (value != nullptr && value[0] != '\0') ? atoi(value) : 3;
}

std::mutex g_dir_mutex;
bool g_dir_done = false;


void ensure_dir(const std::string & dir) {
    // Uma tentativa por diretorio basta; erro e reportado no fopen abaixo.
    std::lock_guard<std::mutex> lock(g_dir_mutex);
    if (!g_dir_done) {
        mkdir(dir.c_str(), 0777); // EEXIST e o caso normal
        g_dir_done = true;
    }
}

std::size_t write_f32_file(const std::string & dir, const std::string & name, const float * data, std::size_t count) {
    // R28/A4 — o caminho de produção é o MESMO que o teste de host exercita
    // (sig_dump_io::write_f32): temp + rename + verificação de cada etapa.
    const std::size_t n = sig_dump_io::write_f32(g_fileops, dir, name, data, count);
    if (n == count) {
        fprintf(stderr, "[sig_dump] %s count=%zu floats escritos=%zu ok=1\n",
                name.c_str(), count, n);
    } else {
        fprintf(stderr, "[sig_dump] FALHA %s count=%zu escritos=%zu errno=%d\n",
                name.c_str(), count, n, errno);
    }
    return n;
}

} // namespace

namespace sig_dump {

// R22/B3 — configura o dump DENTRO do processo, antes da primeira captura.
// Devolve 1 quando aplicou; 0 se kinds vazio.
int configure(const char * kinds, const char * dir, const char * tag, int steps) {
    {
        std::lock_guard<std::mutex> lock(g_cfg_mutex);
        g_cfg_kinds = (kinds != nullptr) ? kinds : "";
        g_cfg_loaded = true;   // nao reler getenv: o valor e o que acabou de chegar
    }
    {
        std::lock_guard<std::mutex> lock(g_dir_mutex);
        g_dir_done = false;    // proximo dump cria o diretorio novo
    }
    close_writers();           // R24/CAP — nao misturar manifesto de A com dados de B

    if (kinds != nullptr)  setenv("SIG_WHISPER_DUMP", kinds, 1);
    if (dir != nullptr)    setenv("SIG_WHISPER_DUMP_DIR", dir, 1);
    if (tag != nullptr)    setenv("SIG_WHISPER_DUMP_TAG", tag, 1);
    if (steps > 0) {
        char buf[16];
        snprintf(buf, sizeof(buf), "%d", steps);
        setenv("SIG_WHISPER_DUMP_STEPS", buf, 1);
    }

    // R28/A4 — SESSÃO DE CAPTURA: identidade exclusiva + destino congelado.
    const std::string sdir = (dir != nullptr && dir[0] != 0) ? dir : dump_dir();
    const std::string stag = (tag != nullptr && tag[0] != 0) ? tag : "run";
    char idbuf[320];
    snprintf(idbuf, sizeof(idbuf), "%s-p%d-s%llu-o%s-g%s-j%s-a%s",
             stag.c_str(), (int) getpid(),
             (unsigned long long) g_sess_seq.fetch_add(1) + 1,
             getenv("SIG_DUMP_OWNER") ? getenv("SIG_DUMP_OWNER") : "0",
             getenv("SIG_DUMP_GEN") ? getenv("SIG_DUMP_GEN") : "0",
             getenv("SIG_DUMP_JOB") ? getenv("SIG_DUMP_JOB") : "0",
             getenv("SIG_DUMP_ATTEMPT") ? getenv("SIG_DUMP_ATTEMPT") : "0");

    // Criação EXCLUSIVA do diretório. Se já existe com manifesto de sessão
    // anterior, RECUSA (tag repetida não seleciona o mesmo destino em silêncio).
    bool recusar = false;
    if (mkdir(sdir.c_str(), 0777) != 0) {
        if (errno == EEXIST) {
            std::string manifest = sdir + "/nodes-" + stag + "-manifest.jsonl";
            struct stat st {};
            if (stat(manifest.c_str(), &st) == 0) {
                recusar = true;
            }
        } else {
            fprintf(stderr, "[sig_dump] configure: mkdir %s falhou errno=%d\n",
                    sdir.c_str(), errno);
            return 0;
        }
    }
    if (recusar) {
        fprintf(stderr, "[sig_dump] configure RECUSADO: destino ja tem sessao (%s)\n",
                sdir.c_str());
        g_recusado.store(true);      // R28/A4: recusa DESLIGA a captura
        {
            std::lock_guard<std::mutex> lk(g_sess_mutex);
            g_sess.active = false;
            g_sess.dir.clear();
            g_sess.tag.clear();
        }
        return 0;
    }
    g_recusado.store(false);

    {
        std::lock_guard<std::mutex> lk(g_sess_mutex);
        g_sess.id = idbuf;
        g_sess.dir = sdir;
        g_sess.tag = stag;
        g_sess.active = true;
        g_escritos.clear();
    }
    fprintf(stderr, "[sig_dump] configure kinds=%s dir=%s tag=%s steps=%d session=%s\n",
            kinds ? kinds : "", sdir.c_str(), stag.c_str(), steps, idbuf);
    return (kinds != nullptr && kinds[0] != 0) ? 1 : 0;
}

std::atomic<int> g_step_index {0};   // R25/FIX — reiniciavel por tentativa

// Recortes de nos (usados pelo coletor do lab).
int configure_nodes(int first, int step, int max) {
    char buf[32];
    if (first >= 0) { snprintf(buf, sizeof(buf), "%d", first); setenv("SIG_WHISPER_DUMP_NODES_FIRST", buf, 1); }
    if (step  >= 0) { snprintf(buf, sizeof(buf), "%d", step);  setenv("SIG_WHISPER_DUMP_NODES_STEP", buf, 1); }
    if (max   >= 0) { snprintf(buf, sizeof(buf), "%d", max);   setenv("SIG_WHISPER_DUMP_NODES_MAX", buf, 1); }
    return 1;
}

// R25/FIX — reinicia o contador de passos (dump_f32_step) no inicio da tentativa.
void reset_step_counter() {
    g_step_index.store(0, std::memory_order_relaxed);
}


void dump_f32(const char * kind, const float * data, std::size_t count) {
    if (g_recusado.load()) {
        return;
    }
    if (kind == nullptr || data == nullptr || count == 0 || !kind_enabled(kind)) {
        return;
    }
    const std::string dir = dump_dir();
    ensure_dir(dir);
    write_f32_file(dir, dump_tag() + "-" + kind, data, count);
}

void dump_f32_step(const char * kind, const float * data, std::size_t count) {
    if (kind == nullptr || data == nullptr || count == 0 || !kind_enabled(kind)) {
        return;
    }
    const int index = g_step_index.fetch_add(1);
    const int limit = max_steps();
    if (limit >= 0 && index >= limit) {
        return;
    }
    // R40 — PASSO FIXO: com SIG_DUMP_STEP_ONLY=<n> so o passo n e gravado. Como a
    // divergencia do decoder aparece em passo VARIAVEL (s19 na R38, s32/s33 na
    // R39), comparar "o ultimo arquivo" nao garante comparar o mesmo passo.
    {
        const char * only = getenv("SIG_DUMP_STEP_ONLY");
        if (only != nullptr && only[0] != '\0') {
            char * fim = nullptr;
            errno = 0;
            const long alvo = strtol(only, &fim, 10);
            if (fim == only || (fim != nullptr && *fim != 0) || errno == ERANGE || alvo < 0) {
                return;   // configuracao invalida: nao grava nada
            }
            if (index != (int) alvo) {
                return;
            }
        }
    }
    const std::string dir = dump_dir();
    ensure_dir(dir);
    write_f32_file(dir, dump_tag() + "-" + kind + "-s" + std::to_string(index), data, count);
}

// ---------------------------------------------------------------------------
// R3/fase D (D1) — captura por nó
// ---------------------------------------------------------------------------

bool nodes_enabled() {
    return kind_enabled("nodes");
}

int nodes_first() {
    const char * value = getenv("SIG_WHISPER_DUMP_NODES_FIRST");
    return (value != nullptr && value[0] != '\0') ? atoi(value) : 48;
}

int nodes_step() {
    const char * value = getenv("SIG_WHISPER_DUMP_NODES_STEP");
    return (value != nullptr && value[0] != '\0') ? atoi(value) : 0;
}

int nodes_max() {
    const char * value = getenv("SIG_WHISPER_DUMP_NODES_MAX");
    return (value != nullptr && value[0] != '\0') ? atoi(value) : 96;
}

std::size_t nodes_span_max() {
    const char * value = getenv("SIG_WHISPER_DUMP_NODES_SPAN_MAX");
    if (value != nullptr && value[0] != '\0') {
        const long long v = atoll(value);
        if (v > 0) {
            return (std::size_t) v;
        }
    }
    return (std::size_t) 64 * 1024 * 1024;
}

std::string node_data_filename(const char * bin_name) {
    return dump_tag() + "-" + bin_name + ".bin";
}

// R24/CAP — WRITER COM CICLO DE VIDA. Antes havia `static FILE * f` aberto uma
// vez: trocar tag/dir no mesmo processo continuava escrevendo no manifesto
// ANTIGO. Agora o caminho desejado e comparado a cada escrita; se mudou, o
// handle antigo e fechado (flush+close) e o novo e aberto.
struct ManifestWriter {
    std::mutex m;
    FILE * f = nullptr;
    std::string path;
    bool ok = true;

    bool ensure(const std::string & p) {
        std::lock_guard<std::mutex> lk(m);
        if (f != nullptr && path == p) {
            return true;
        }
        if (f != nullptr) {
            fflush(f);
            fclose(f);
            f = nullptr;
            path.clear();
        }
        ok = true;
        f = fopen(p.c_str(), "wb");   // R28/A4: sessão nova => manifesto novo
        if (f == nullptr) {
            return false;
        }
        path = p;
        return true;
    }
    void write(const char * line) {
        std::lock_guard<std::mutex> lk(m);
        if (f == nullptr) {
            return;
        }
        // R28/A4 — escrita do manifesto também é verificada.
        const size_t n = fwrite(line, 1, strlen(line), f);
        const int nl = fputc('\n', f);
        const int fl = fflush(f);
        if (n != strlen(line) || nl == EOF || fl != 0) {
            ok = false;
            fprintf(stderr, "[sig_dump] FALHA manifesto %s errno=%d\n", path.c_str(), errno);
        }
    }
    bool saudavel() {
        std::lock_guard<std::mutex> lk(m);
        return f != nullptr && ok;
    }
    void close() {
        std::lock_guard<std::mutex> lk(m);
        if (f != nullptr) {
            if (fflush(f) != 0) { ok = false; }
            if (fclose(f) != 0) { ok = false; }
            if (!ok) {
                fprintf(stderr, "[sig_dump] FALHA ao fechar manifesto %s errno=%d\n",
                        path.c_str(), errno);
            }
            f = nullptr;
        }
        path.clear();
    }
    std::string atual() {
        std::lock_guard<std::mutex> lk(m);
        return path;
    }
};

ManifestWriter g_nodes_writer;
ManifestWriter g_fa_writer;

} // namespace

namespace sig_dump {

void close_writers() {
    // R28/A4 — falha do manifesto é REPORTADA (não pode registrar em si mesma).
    const bool nodes_ok = g_nodes_writer.saudavel();
    const bool fa_ok = g_fa_writer.saudavel();
    g_nodes_writer.close();
    g_fa_writer.close();
    if (!nodes_ok) {
        fprintf(stderr, "[sig_dump] MANIFESTO nodes com falha de escrita na sessao\n");
    }
    if (!fa_ok) {
        fprintf(stderr, "[sig_dump] MANIFESTO fa com falha de escrita na sessao\n");
    }
}

void node_manifest(const char * json_line) {
    if (g_recusado.load()) {
        return;
    }
    if (json_line == nullptr || !nodes_enabled()) {
        return;
    }
    const std::string dir = dump_dir();
    ensure_dir(dir);
    const std::string path = dir + "/nodes-" + dump_tag() + "-manifest.jsonl";
    if (!g_nodes_writer.ensure(path)) {
        fprintf(stderr, "[sig_dump] falha ao abrir manifesto (errno=%d)", errno);
        return;
    }
    g_nodes_writer.write(json_line);
}

std::size_t slice_data(const char * bin_name, const float * data, std::size_t count) {
    if (g_recusado.load()) {
        return 0;
    }
    if (bin_name == nullptr || data == nullptr || count == 0 || !softmax_kind_listed()) {
        return 0;
    }
    const std::string dir = dump_dir();
    ensure_dir(dir);
    return write_f32_file(dir, dump_tag() + "-" + bin_name, data, count);
}

std::size_t node_data(const char * bin_name, const float * data, std::size_t count) {
    if (g_recusado.load()) {
        return 0;                    // sessão recusada: nada é gravado
    }
    if (bin_name == nullptr || data == nullptr || count == 0 || !nodes_enabled()) {
        return 0;
    }
    const std::string dir = dump_dir();
    ensure_dir(dir);
    const std::string nome = dump_tag() + "-" + bin_name;
    {
        // R28/A4 — mesma identidade COMPLETA na mesma sessão não pode repetir:
        // registrar erro de protocolo e recusar a segunda escrita (não deduplicar
        // em silêncio, não sobrescrever).
        std::lock_guard<std::mutex> lk(g_sess_mutex);
        for (const auto & w : g_escritos) {
            if (w == nome) {
                fprintf(stderr, "[sig_dump] PROTOCOLO: %s repetido na sessao %s\n",
                        nome.c_str(), g_sess.id.c_str());
                return 0;
            }
        }
        g_escritos.push_back(nome);
    }
    return write_f32_file(dir, nome, data, count);
}

// ---------------------------------------------------------------------------
// R7/fase D (D2) — captura da OP FA real (kind "fa").
// ---------------------------------------------------------------------------
bool fa_enabled() {
    return kind_enabled("fa");
}

int fa_max() {
    const char * value = getenv("SIG_WHISPER_DUMP_FA_MAX");
    return (value != nullptr && value[0] != '\0') ? atoi(value) : 8;
}

int fa_calls() {
    const char * value = getenv("SIG_WHISPER_DUMP_FA_CALLS");
    return (value != nullptr && value[0] != '\0') ? atoi(value) : 1;
}

void fa_manifest(const char * json_line) {
    if (json_line == nullptr || !fa_enabled()) {
        return;
    }
    const std::string dir = dump_dir();
    ensure_dir(dir);
    const std::string path = dir + "/fa-" + dump_tag() + ".jsonl";
    if (!g_fa_writer.ensure(path)) {
        fprintf(stderr, "[sig_dump] falha ao abrir manifesto FA (errno=%d)", errno);
        return;
    }
    g_fa_writer.write(json_line);
}

void fa_data(const char * bin_name, const float * data, std::size_t count) {
    if (bin_name == nullptr || data == nullptr || count == 0 || !fa_enabled()) {
        return;
    }
    const std::string dir = dump_dir();
    ensure_dir(dir);
    write_f32_file(dir, dump_tag() + "-" + bin_name, data, count);
}

} // namespace sig_dump

// ---------------------------------------------------------------------------
// R6/fase B (B1) — teacher forcing / replay de prefixo fixo (SOMENTE DIAG).
// Ver sig_whisper_dump.h para o contrato dos envs.
// ---------------------------------------------------------------------------

namespace sig_tf {

namespace {

std::string tf_file() {
    static const std::string value = []() {
        const char * p = getenv("SIG_WHISPER_TF_FILE");
        return (p != nullptr && p[0] != '\0') ? std::string(p) : std::string();
    }();
    return value;
}

std::string tf_prefix() {
    static const std::string value = []() {
        const char * p = getenv("SIG_WHISPER_TF_RECORD");
        return (p != nullptr && p[0] != '\0') ? std::string(p) : std::string();
    }();
    return value;
}

std::mutex & tf_mutex() { static std::mutex m; return m; }
std::vector<int32_t> & tf_ids() { static std::vector<int32_t> v; return v; }
bool & tf_loaded() { static bool v = false; return v; }
std::size_t & tf_pos() { static std::size_t v = 0; return v; }
int & tf_exhausted() { static int v = 0; return v; }
FILE * & tf_steps_f() { static FILE * f = nullptr; return f; }
FILE * & tf_ids_f() { static FILE * f = nullptr; return f; }

void tf_load_locked() {
    if (tf_loaded()) {
        return;
    }
    tf_loaded() = true;
    const std::string path = tf_file();
    if (path.empty()) {
        return;
    }
    FILE * f = fopen(path.c_str(), "rb");
    if (f == nullptr) {
        fprintf(stderr, "[TF] falha ao abrir SIG_WHISPER_TF_FILE=%s\n", path.c_str());
        return;
    }
    char line[512];
    while (fgets(line, sizeof(line), f) != nullptr) {
        char * s = line;
        while (*s == ' ' || *s == '\t') s++;
        if (*s == '#' || *s == '\n' || *s == '\r' || *s == '\0') {
            continue;
        }
        char * endp = nullptr;
        const long v = strtol(s, &endp, 10);
        if (endp == s) {
            fprintf(stderr, "[TF] linha ignorada (nao-numerica): %.80s\n", line);
            continue;
        }
        tf_ids().push_back(static_cast<int32_t>(v));
    }
    fclose(f);
    fprintf(stderr, "[TF] replay: %zu ids carregados de %s\n", tf_ids().size(), path.c_str());
}

} // namespace

bool replay_active() {
    static const bool value = !tf_file().empty();
    return value;
}

bool record_active() {
    static const bool value = !tf_prefix().empty();
    return value;
}

void note_log(const char * fmt, ...) {
    if (!replay_active() && !record_active()) {
        return;
    }
    va_list args;
    va_start(args, fmt);
    fputs("[TF] ", stderr);
    vfprintf(stderr, fmt, args);
    va_end(args);
    fflush(stderr);
}

int next_forced() {
    if (!replay_active()) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(tf_mutex());
    tf_load_locked();
    if (tf_pos() < tf_ids().size()) {
        return tf_ids()[tf_pos()++];
    }
    if (tf_exhausted() == 0) {
        fprintf(stderr, "[TF] sequencia de replay ESGOTADA apos %zu ids; segue amostragem normal\n", tf_pos());
    }
    tf_exhausted()++;
    return -1;
}

void note_id(int token) {
    if (!record_active()) {
        return;
    }
    std::lock_guard<std::mutex> lock(tf_mutex());
    if (tf_ids_f() == nullptr) {
        const std::string path = tf_prefix() + ".ids.txt";
        tf_ids_f() = fopen(path.c_str(), "wb");
        if (tf_ids_f() == nullptr) {
            fprintf(stderr, "[TF] falha ao abrir %s\n", path.c_str());
            return;
        }
        fprintf(stderr, "[TF] record: ids em %s\n", path.c_str());
    }
    fprintf(tf_ids_f(), "%d\n", token);
    fflush(tf_ids_f());
}

void note_step_line(const char * json_line) {
    if (!record_active() || json_line == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(tf_mutex());
    if (tf_steps_f() == nullptr) {
        const std::string path = tf_prefix() + ".steps.jsonl";
        tf_steps_f() = fopen(path.c_str(), "wb");
        if (tf_steps_f() == nullptr) {
            fprintf(stderr, "[TF] falha ao abrir %s\n", path.c_str());
            return;
        }
        fprintf(stderr, "[TF] record: passos em %s\n", path.c_str());
    }
    fputs(json_line, tf_steps_f());
    fputc('\n', tf_steps_f());
    fflush(tf_steps_f());
}

} // namespace sig_tf

// ---------------------------------------------------------------------------
// R12/A3 — HOOK DE ENSAIO MINIMO (SOMENTE DIAG).
//
// Existe apenas neste arquivo, que so e compilado no DIAG. O harness de aceite
// (dlopen + dlsym) usa este simbolo para provar que o caminho de instrumentacao
// esta ATIVO: grava 4 floats conhecidos em <dir>/hook-mel.bin. No NORMAL o
// simbolo nao existe (dlsym => NULL) e NENHUM arquivo e criado.
// Retorno: 0 = arquivo criado; -1 = hook recusado/sem permissao; -2 = dir invalido.
// ---------------------------------------------------------------------------
extern "C" int sig_diag_hook_selftest(const char * dir) {
    if (dir == nullptr || dir[0] == '\0') {
        return -2;
    }
    setenv("SIG_WHISPER_DUMP_DIR", dir, 1);
    setenv("SIG_WHISPER_DUMP_TAG", "hook", 1);
    setenv("SIG_WHISPER_DUMP", "mel", 1); // lido na primeira chamada deste processo
    const float probe[4] = {1.5f, -2.25f, 3.0f, 4.5f};
    sig_dump::dump_f32("mel", probe, 4);
    const std::string path = std::string(dir) + "/hook-mel.bin";
    FILE * f = fopen(path.c_str(), "rb");
    if (f == nullptr) {
        return -1;
    }
    fclose(f);
    return 0;
}

#endif // SIG_WHISPER_DUMP_HOOK
