#pragma once

#include <cstddef>
#include <string>
#include <vector>
#include <cstdint>
#include <cstdlib>
#include <cerrno> // R3/fase D (D1): assinatura de node_data_filename

// R2/fase C (C2) — dumps diagnósticos do pipeline do whisper (mel, saída do
// encoder e logits por passo de decoder) para localizar a PRIMEIRA divergência
// entre backends com FA-off.
//
// Só existe no build de diagnóstico (-DSIG_WHISPER_DIAG=1 via Gradle/CMake,
// que define SIG_WHISPER_DUMP_HOOK); no build normal os pontos de chamada nem
// são compilados. Ativação em tempo de execução:
//   SIG_WHISPER_DUMP=mel,enc,logits      lista de dumps (ausente = nenhum)
//   SIG_WHISPER_DUMP_DIR=<dir>           default /data/local/tmp/whisper-bench-r2/dumps
//   SIG_WHISPER_DUMP_TAG=<prefixo>       default "run" (prefixo dos arquivos)
//   SIG_WHISPER_DUMP_STEPS=<n>           default 3 (quantos passos de logits)
//
// Formato de cada arquivo: 1 linha de cabeçalho textual
// ("# sig_dump <nome> count=<n> type=f32le") seguida de <n> floats LE crus.
namespace sig_dump {

// R37/B1 — recorte de softmax (laboratorio, default OFF).
//
// Defeitos provados pela auditoria R36, todos corrigidos aqui:
//   * o registro do callback so aceitava nodes/fa  -> agora aceita o kind exato;
//   * cache estatico ignorava `configure`          -> leitura por chamada;
//   * "notsoftmax" ativava por substring           -> token EXATO;
//   * rows/max negativos iam para size_t           -> validados com recusa.
// Eixos (confirmados no operador): ne[0]=chaves (eixo reduzido), ne[1]=queries,
// ne[2]=heads. O recorte leva head H, queries [q0,q0+rows) e TODAS as chaves.
inline bool softmax_kind_listed() {
    // Token EXATO na lista de kinds, lida a cada chamada: "notsoftmax" nao ativa,
    // e reconfigurar no mesmo processo tem efeito imediato (sem cache estatico).
    const char * v = getenv("SIG_WHISPER_DUMP");
    if (v == nullptr) {
        return false;
    }
    const std::string lista(v);
    size_t i = 0;
    while (i < lista.size()) {
        size_t f = lista.find(',', i);
        if (f == std::string::npos) {
            f = lista.size();
        }
        std::string tok = lista.substr(i, f - i);
        const size_t a = tok.find_first_not_of(" 	");
        const size_t b = tok.find_last_not_of(" 	");
        if (a != std::string::npos && tok.substr(a, b - a + 1) == "softmax") {
            return true;
        }
        i = f + 1;
    }
    return false;
}

inline bool softmax_slice_enabled() {
    return softmax_kind_listed();
}

inline int softmax_slice_max() {
    const char * v = getenv("SIG_DUMP_SOFTMAX_MAX");
    if (v == nullptr || v[0] == 0) {
        return 2;
    }
    char * fim = nullptr;
    errno = 0;
    const long n = strtol(v, &fim, 10);
    if (fim == v || (fim != nullptr && *fim != 0) || errno == ERANGE || n < 0 || n > 64) {
        return 0;   // "4junk"/"abc"/overflow: recusa explicita
    }
    return (int) n;
}

inline int softmax_slice_rows() {
    const char * v = getenv("SIG_DUMP_SOFTMAX_ROWS");
    if (v == nullptr || v[0] == 0) {
        return 4;
    }
    char * fim = nullptr;
    errno = 0;
    const long n = strtol(v, &fim, 10);
    if (fim == v || (fim != nullptr && *fim != 0) || errno == ERANGE || n <= 0 || n > 256) {
        return 0;
    }
    return (int) n;
}

inline int softmax_slice_head() {
    const char * v = getenv("SIG_DUMP_SOFTMAX_HEAD");
    if (v == nullptr || v[0] == 0) {
        return 0;
    }
    const long n = strtol(v, nullptr, 10);
    return (n < 0 || n > 1024) ? -1 : (int) n;
}

// R37/B2 — extracao PURA do recorte (testavel no host, usada pelo backend).
// Ordem canonica de saida: [query][key] para UM head. Exige nb[0]==sizeof(float)
// (contiguo em chaves) e devolve false com motivo quando nao pode ler.
enum class SliceErr { OK, DTYPE, STRIDE, COORD, ROWS, KEYS, SPAN };

inline const char * slice_err_name(SliceErr e) {
    switch (e) {
        case SliceErr::OK: return "ok";
        case SliceErr::DTYPE: return "dtype-nao-suportado";
        case SliceErr::STRIDE: return "stride-nao-contiguo";
        case SliceErr::COORD: return "coordenada-fora-do-tensor";
        case SliceErr::ROWS: return "linhas-fora-do-tensor";
        case SliceErr::KEYS: return "eixo-de-chaves-vazio";
        case SliceErr::SPAN: return "fora-do-span";
    }
    return "?";
}

// R38/D2 — PLANO de leitura por LINHA, com origem UNICA de offset.
//
// O defeito da R37: o chamador lia de `off` e passava a base ja deslocada a um
// helper que somava `off` de novo. Aqui o plano calcula, para CADA linha, o
// offset absoluto no tensor e o tamanho de linha, e verifica o limite por linha
// (padding da ultima linha nao precisa ser lido).
inline SliceErr softmax_slice_plan(const int64_t ne[4], const size_t nb[4], size_t elem_size,
                                   int head, int q0, int rows, size_t span_bytes,
                                   std::vector<size_t> & offsets, size_t & row_bytes) {
    offsets.clear();
    row_bytes = 0;
    if (elem_size != sizeof(float)) return SliceErr::DTYPE;
    if (nb[0] != sizeof(float)) return SliceErr::STRIDE;
    if (ne[0] <= 0) return SliceErr::KEYS;
    if (head < 0 || (int64_t) head >= ne[2] || q0 < 0 || (int64_t) q0 >= ne[1]) return SliceErr::COORD;
    if (rows <= 0 || (int64_t) (q0 + rows) > ne[1]) return SliceErr::ROWS;
    const size_t rb = (size_t) ne[0] * sizeof(float);
    offsets.reserve((size_t) rows);
    for (int r = 0; r < rows; ++r) {
        const size_t off = (size_t) (q0 + r) * nb[1] + (size_t) head * nb[2];
        if (off > span_bytes || rb > span_bytes - off) {
            return SliceErr::SPAN;
        }
        offsets.push_back(off);
    }
    row_bytes = rb;
    return SliceErr::OK;
}

inline SliceErr softmax_slice_extract(const int64_t ne[4], const size_t nb[4], size_t elem_size,
                                      int head, int q0, int rows, size_t span_bytes,
                                      const uint8_t * base, std::vector<float> & out) {
    out.clear();
    if (elem_size != sizeof(float)) return SliceErr::DTYPE;
    if (nb[0] != sizeof(float)) return SliceErr::STRIDE;
    if (ne[0] <= 0) return SliceErr::KEYS;
    if (head < 0 || (int64_t) head >= ne[2] || q0 < 0 || (int64_t) q0 >= ne[1]) return SliceErr::COORD;
    if (rows <= 0 || (int64_t) (q0 + rows) > ne[1]) return SliceErr::ROWS;
    const size_t off = (size_t) q0 * nb[1] + (size_t) head * nb[2];
    const size_t bytes = (size_t) rows * nb[1];
    if (off + bytes > span_bytes) return SliceErr::SPAN;
    const float * f = reinterpret_cast<const float *>(base + off);
    const size_t passo = nb[1] / sizeof(float);
    out.reserve((size_t) rows * (size_t) ne[0]);
    for (int q = 0; q < rows; ++q) {
        for (int64_t k = 0; k < ne[0]; ++k) {
            out.push_back(f[(size_t) q * passo + (size_t) k]);
        }
    }
    return SliceErr::OK;
}

inline int softmax_slice_query0() {
    const char * v = getenv("SIG_DUMP_SOFTMAX_QUERY0");
    if (v == nullptr || v[0] == 0) {
        return 0;
    }
    const long n = strtol(v, nullptr, 10);
    return (n < 0 || n > 100000) ? -1 : (int) n;
}

// R22/B3 — configuracao in-process (antes da primeira captura do processo).
void close_writers();   // R24/CAP — fecha manifestos antes de trocar dir/tag
int configure(const char * kinds, const char * dir, const char * tag, int steps);
int configure_nodes(int first, int step, int max);
// R25/FIX — o contador de passos era `static` no arquivo e esgotava o teto de A
// em B no mesmo processo. Reiniciado no inicio de cada tentativa.
void reset_step_counter();

void dump_f32(const char * kind, const float * data, std::size_t count);
void dump_f32_step(const char * kind, const float * data, std::size_t count);

// ---------------------------------------------------------------------------
// R3/fase D (D1): captura POR NÓ do grafo (modo "nodes" em SIG_WHISPER_DUMP).
// Somente no build DIAG. Manifesto JSONL com metadados de TODOS os nós
// executados + arquivos de dados (f32le) por nó capturado.
// Recorte: SIG_WHISPER_DUMP_NODES_FIRST (default 48), ..._STEP (default 0=off),
// ..._MAX (default 96 dumps), ..._SPAN_MAX (bytes, default 64 MiB).
// ---------------------------------------------------------------------------
bool nodes_enabled();
int  nodes_first();
int  nodes_step();
int  nodes_max();
std::size_t nodes_span_max();

// Uma linha do manifesto (JSONL; sem \n; o escritor adiciona).
void node_manifest(const char * json_line);

// Nome de arquivo completo (com TAG) para um dump de nó.
std::string node_data_filename(const char * bin_name);

// Escreve os dados de um nó (formato f32le + header, igual aos demais dumps).
// R25/FIX — devolve os floats REALMENTE escritos (0 = falha), para o chamador
// publicar o resultado da escrita em vez de anunciar o arquivo antes dela.
std::size_t node_data(const char * bin_name, const float * data, std::size_t count);
// R37/B1 — escrita do RECORTE: mesma sessao/escrita segura, sem exigir "nodes".
std::size_t slice_data(const char * bin_name, const float * data, std::size_t count);

// ---------------------------------------------------------------------------
// R7/fase D (D2) — captura da OP de Flash Attention REAL (kind "fa" da lista
// SIG_WHISPER_DUMP). Materializa Q/K/V/mask/dst da chamada REAL do operador
// (encoder self e cross-attention do decoder) para prova host-vs-device.
// Recortes: SIG_WHISPER_DUMP_FA_MAX (default 8 ops), SIG_WHISPER_DUMP_FA_CALLS
// (default 1 por grafo). Manifesto: <DIR>/fa-<TAG>.jsonl.
// ---------------------------------------------------------------------------
bool fa_enabled();
int  fa_max();
int  fa_calls();
void fa_manifest(const char * json_line);
void fa_data(const char * bin_name, const float * data, std::size_t count);

} // namespace sig_dump

// ---------------------------------------------------------------------------
// R6/fase B (B1) — TEACHER FORCING / replay de prefixo fixo (SOMENTE DIAG).
//
// O ponto de amostragem do decoder passa a poder CONSUMIR uma sequência de
// token ids pré-gravada, em ordem, sem re-escolher por argmax/sampling; todo o
// resto do decode (encode, prompt, KV, timestamps, filtros, temperatura) é o
// caminho normal intocado. Ativação em runtime (ausente = modo normal):
//   SIG_WHISPER_TF_FILE=<path>    replay: ids forçados, um por linha
//                                 (# e linhas vazias ignorados).
//   SIG_WHISPER_TF_RECORD=<pref>  grava <pref>.ids.txt (ids escolhidos, o
//                                 arquivo de replay) e <pref>.steps.jsonl
//                                 (eventos de prompt e passo com hashes).
// Sem nenhum dos dois envs, nada é chamado/alocado (mesmo custo do normal).
namespace sig_tf {

bool replay_active();
bool record_active();
int  next_forced();                         // -1 = esgotado ou replay off
void note_id(int token);                    // append em <pref>.ids.txt
void note_step_line(const char * json_line); // append em <pref>.steps.jsonl
void note_log(const char * fmt, ...);       // stderr "[TF] ..." se ativo

} // namespace sig_tf
