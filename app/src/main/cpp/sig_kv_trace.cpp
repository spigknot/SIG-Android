// R10/fase C (C1+C2) — instrumento DIAG da cadeia PRODUTORA do cache K/V (Vulkan/CPU).
// Compilado SOMENTE no build de diagnostico (SIG_WHISPER_DUMP_HOOK). Ver sig_kv_trace.h.

#include "sig_kv_trace.h"

#ifdef SIG_WHISPER_DUMP_HOOK

#include "ggml.h"
#include "ggml-backend.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {

std::mutex g_mutex;

std::string kv_dir() {
    const char * d = getenv("SIG_WHISPER_KV_TRACE_DIR");
    if (d == nullptr || d[0] == '\0') {
        d = getenv("SIG_WHISPER_DUMP_DIR");
    }
    return (d != nullptr && d[0] != '\0') ? d : "/data/local/tmp/whisper-bench-r2/dumps";
}

std::string kv_tag() {
    const char * t = getenv("SIG_WHISPER_KV_TRACE_TAG");
    if (t == nullptr || t[0] == '\0') {
        t = getenv("SIG_WHISPER_DUMP_TAG");
    }
    return (t != nullptr && t[0] != '\0') ? t : "run";
}

int env_int(const char * name, int def) {
    const char * v = getenv(name);
    return (v != nullptr && v[0] != '\0') ? atoi(v) : def;
}

size_t env_size(const char * name, size_t def) {
    const char * v = getenv(name);
    return (v != nullptr && v[0] != '\0') ? (size_t) strtoull(v, nullptr, 10) : def;
}

bool graph_wanted(const char * graph) {
    const char * v = getenv("SIG_WHISPER_KV_TRACE_GRAPHS");
    const std::string list = (v != nullptr && v[0] != '\0') ? v : "cross,decoder";
    if (list == "all") {
        return true;
    }
    size_t pos = 0;
    while (pos <= list.size()) {
        const size_t comma = list.find(',', pos);
        const std::string tok = list.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
        if (tok == graph) {
            return true;
        }
        if (comma == std::string::npos) {
            break;
        }
        pos = comma + 1;
    }
    return false;
}

uint64_t fnv64(const void * data, size_t len) {
    const uint8_t * p = (const uint8_t *) data;
    uint64_t h = 1469598103934665603ull;
    for (size_t i = 0; i < len; ++i) {
        h ^= p[i];
        h *= 1099511628211ull;
    }
    return h;
}

std::string esc(const char * s) {
    std::string o;
    if (s == nullptr) {
        return o;
    }
    for (const char * p = s; *p != '\0'; ++p) {
        if (*p == '"' || *p == '\\') {
            o += '\\';
        }
        o += *p;
    }
    return o;
}

struct kv_entry {
    std::string which;
    struct ggml_tensor * k = nullptr;
    struct ggml_tensor * v = nullptr;
    ggml_backend_buffer_t buffer = nullptr;
    const uint8_t * base = nullptr;
    size_t bytes = 0;
};
std::vector<kv_entry> g_kv;

// Hash logico do payload de um tensor (lido do backend), com teto de bytes.
uint64_t tensor_hash(struct ggml_tensor * t, size_t span_cap) {
    if (t == nullptr || t->buffer == nullptr) {
        return 0;
    }
    const size_t n = std::min(ggml_nbytes(t), span_cap);
    if (n == 0) {
        return 0;
    }
    std::vector<uint8_t> buf(n);
    ggml_backend_tensor_get(t, buf.data(), 0, n);
    return fnv64(buf.data(), n);
}

// Se t pertence a um buffer K/V registrado, devolve qual e o offset/nbytes dentro dele.
bool in_kv(struct ggml_tensor * t, const char ** which, size_t * off, size_t * nbytes) {
    if (t == nullptr || t->buffer == nullptr || t->data == nullptr) {
        return false;
    }
    for (const auto & e : g_kv) {
        if (e.buffer != t->buffer || e.base == nullptr) {
            continue;
        }
        const uint8_t * base = e.base;
        const uint8_t * p    = (const uint8_t *) t->data;
        if (p < base || p >= base + e.bytes) {
            continue;
        }
        *which  = e.which.c_str();
        *off    = (size_t) (p - base);
        *nbytes = ggml_nbytes(t);
        return true;
    }
    return false;
}

void write_line(const std::string & line) {
    const std::string path = kv_dir() + "/kv-" + kv_tag() + ".jsonl";
    FILE * f = fopen(path.c_str(), "ab");
    if (f == nullptr) {
        return;
    }
    fwrite(line.data(), 1, line.size(), f);
    fputc('\n', f);
    fclose(f);
}

} // namespace

namespace sig_kv {

bool enabled() {
    const char * d = getenv("SIG_WHISPER_KV_TRACE_DIR");
    if (d != nullptr && d[0] != '\0') {
        return true;
    }
    // fallback: se o modo nodes estiver ligado, o trace tambem vale (mesmo DIR)
    const char * m = getenv("SIG_WHISPER_DUMP");
    return m != nullptr && strstr(m, "kv") != nullptr;
}

bool canary_enabled() {
    const char * v = getenv("SIG_WHISPER_KV_CANARY");
    return v != nullptr && v[0] != '\0';
}

int next_call(const char * graph) {
    static std::mutex m;
    static std::vector<std::pair<std::string, int>> c;
    std::lock_guard<std::mutex> lk(m);
    for (auto & kv : c) {
        if (kv.first == graph) {
            return kv.second++;
        }
    }
    c.push_back({graph, 1});
    return 0;
}

void register_kv(const char * which, ::ggml_tensor * k, ::ggml_tensor * v) {
    std::lock_guard<std::mutex> lk(g_mutex);
    kv_entry e;
    e.which  = which;
    e.k      = k;
    e.v      = v;
    e.buffer = (k != nullptr && k->buffer != nullptr) ? k->buffer : (v != nullptr ? v->buffer : nullptr);
    // faixa UNIAO k..v dentro do MESMO buffer (o cache aloca k e v no mesmo buffer).
    const uint8_t * lo = nullptr;
    const uint8_t * hi = nullptr;
    ::ggml_tensor * ts[2] = { k, v };
    for (int i = 0; i < 2; ++i) {
        if (ts[i] == nullptr || ts[i]->buffer == nullptr || ts[i]->data == nullptr ||
            ts[i]->buffer != e.buffer) {
            continue;
        }
        const uint8_t * p = (const uint8_t *) ts[i]->data;
        const uint8_t * q = p + ggml_nbytes(ts[i]);
        if (lo == nullptr || p < lo) { lo = p; }
        if (hi == nullptr || q > hi) { hi = q; }
    }
    e.base  = lo;
    e.bytes = (lo != nullptr) ? (size_t) (hi - lo) : 0;
    g_kv.push_back(e);
    fprintf(stderr, "[sig_kv] register %s k=%p base=%p bytes=%zu buffer=%s\n",
            which, (void *) k, (void *) e.base, e.bytes,
            e.buffer != nullptr ? ggml_backend_buffer_name(e.buffer) : "(null)");
}

static uint16_t canary_pattern() {
    const char * v = getenv("SIG_WHISPER_KV_CANARY");
    if (v == nullptr || v[0] == '\0' || strcmp(v, "1") == 0) {
        return 0xAAAA;
    }
    if (strcmp(v, "lcg") == 0) {
        return 0; // sentinela: usar LCG
    }
    if (strncmp(v, "0x", 2) == 0) {
        return (uint16_t) strtoul(v + 2, nullptr, 16);
    }
    return (uint16_t) strtoul(v, nullptr, 0);
}

void canary_go() {
    if (!canary_enabled()) {
        return;
    }
    std::lock_guard<std::mutex> lk(g_mutex);
    const size_t cap  = env_size("SIG_WHISPER_KV_CANARY_MAX", 16u << 20);
    const uint16_t pat = canary_pattern();
    const bool lcg = (pat == 0);
    for (auto & e : g_kv) {
        ::ggml_tensor * ts[2] = { e.k, e.v };
        for (int i = 0; i < 2; ++i) {
            ::ggml_tensor * t = ts[i];
            if (t == nullptr || t->buffer == nullptr) {
                continue;
            }
            const size_t n = std::min(ggml_nbytes(t), cap);
            std::vector<uint8_t> buf(n);
            for (size_t j = 0; j + 1 < n; j += 2) {
                uint16_t x = lcg ? (uint16_t) ((j / 2) * 2654435761u >> 8) : pat;
                buf[j] = x & 0xFF;
                buf[j + 1] = (x >> 8) & 0xFF;
            }
            ggml_backend_tensor_set(t, buf.data(), 0, n);
            std::vector<uint8_t> back(n);
            ggml_backend_tensor_get(t, back.data(), 0, n);
            const uint32_t h0 = (uint32_t) (back[0] | (back[1] << 8));
            const uint32_t h1 = (uint32_t) (back[2] | (back[3] << 8));
            const uint32_t t0 = (uint32_t) (back[n - 2] | (back[n - 1] << 8));
            fprintf(stderr, "[sig_kv] canary %s %s n=%zu pat=%04x fnv=%016llx first16=%04x,%04x last16=%04x\n",
                    e.which.c_str(), i == 0 ? "k" : "v", n, pat,
                    (unsigned long long) fnv64(back.data(), n), h0, h1, t0);
            char line[512];
            snprintf(line, sizeof(line),
                "{\"kind\":\"canary\",\"kv\":\"%s\",\"tensor\":\"%s\",\"bytes\":%zu,\"pat\":%u,"
                "\"fnv\":%llu,\"first\":%u,\"last\":%u,\"cap\":%zu}",
                e.which.c_str(), i == 0 ? "k" : "v", n, pat,
                (unsigned long long) fnv64(back.data(), n), h0, t0, cap);
            write_line(line);
        }
    }
}

void trace_graph(struct ggml_cgraph * gf, const char * graph, const char * phase, int call) {
    if (gf == nullptr || !graph_wanted(graph)) {
        return;
    }
    std::lock_guard<std::mutex> lk(g_mutex);
    const int   max_rec  = env_int("SIG_WHISPER_KV_TRACE_MAX", 64);
    const size_t span_cap = env_size("SIG_WHISPER_KV_TRACE_SPAN", 4u << 20);
    int rec = 0;
    const int nn = ggml_graph_n_nodes(gf);
    for (int i = 0; i < nn; ++i) {
        ::ggml_tensor * t = ggml_graph_node(gf, i);
        if (t == nullptr) {
            continue;
        }
        const char * which = nullptr;
        size_t off = 0, nbytes = 0;
        const bool touches_kv = in_kv(t, &which, &off, &nbytes);
        const bool is_cpy = (t->op == GGML_OP_CPY);
        // interessa: (a) escritas no cache (CPY cujo DST e' o cache), (b) leituras (views/leaves)
        if (!touches_kv && !is_cpy) {
            continue;
        }
        if (rec >= max_rec) {
            break;
        }
        ::ggml_tensor * s = t->src[0];
        const char * swhich = nullptr;
        size_t soff = 0, snb = 0;
        const bool src_in_kv = in_kv(s, &swhich, &soff, &snb);
        (void) snb;
        char line[1024];
        snprintf(line, sizeof(line),
            "{\"kind\":\"kv\",\"graph\":\"%s\",\"call\":%d,\"phase\":\"%s\",\"name\":\"%s\",\"op\":\"%s\","
            "\"type\":\"%s\",\"ne\":[%lld,%lld,%lld],\"nb\":[%lld,%lld,%lld],\"view_offs\":%lld,"
            "\"buffer\":\"%s\",\"kv\":\"%s\",\"kv_off\":%zu,\"kv_nbytes\":%zu,\"span\":%zu,\"fnv\":%llu,"
            "\"src_name\":\"%s\",\"src_op\":\"%s\",\"src_kv\":\"%s\",\"src_off\":%zu,\"src_fnvb\":%zu,\"src_fnv\":%llu}",
            esc(graph).c_str(), call, phase, esc(t->name).c_str(), ggml_op_name(t->op), ggml_type_name(t->type),
            (long long) t->ne[0], (long long) t->ne[1], (long long) t->ne[2],
            (long long) t->nb[0], (long long) t->nb[1], (long long) t->nb[2],
            (long long) t->view_offs,
            t->buffer != nullptr ? esc(ggml_backend_buffer_name(t->buffer)).c_str() : "",
            touches_kv ? which : "", touches_kv ? off : 0, touches_kv ? nbytes : 0,
            ggml_nbytes(t), (unsigned long long) tensor_hash(t, span_cap),
            s != nullptr ? esc(s->name).c_str() : "", s != nullptr ? ggml_op_name(s->op) : "",
            src_in_kv ? swhich : "", src_in_kv ? soff : 0,
            s != nullptr ? ggml_nbytes(s) : 0,
            (unsigned long long) (s != nullptr ? tensor_hash(s, span_cap) : 0));
        write_line(line);
        ++rec;
    }
    if (rec > 0) {
        fprintf(stderr, "[sig_kv] trace graph=%s call=%d phase=%s registros=%d\n", graph, call, phase, rec);
    }
}

} // namespace sig_kv

#endif // SIG_WHISPER_DUMP_HOOK
