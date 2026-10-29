#pragma once
// R10/fase C (C1+C2) — instrumento DIAG da CADEIA PRODUTORA do cache K/V.
//
// Objetivo (ordem C1/C2 da rodada 10):
//   localizar o PRIMEIRO delta da rota Vulkan capturando o PRODUTOR e os BYTES
//   realmente gravados/lidos no cache, com identidade (nao so o arquivo TF).
//
// SOMENTE build de diagnostico (-PsigWhisperDiag=true => SIG_WHISPER_DUMP_HOOK).
// Em NORMAL nenhuma linha deste modulo e compilada nem chamada (custo zero).
//
// Interface criada (envs, ausentes = inativo):
//   SIG_WHISPER_KV_TRACE_DIR=<dir>   liga o trace do produtor (default: SIG_WHISPER_DUMP_DIR)
//   SIG_WHISPER_KV_TRACE_TAG=<pref>  prefixo do arquivo kv-<TAG>.jsonl (default "run")
//   SIG_WHISPER_KV_TRACE_GRAPHS=<l>  lista de grafos ("cross,decoder,enc", "all")
//   SIG_WHISPER_KV_TRACE_MAX=<n>     max registros por (grafo,chamada,fase) (default 96)
//   SIG_WHISPER_KV_TRACE_SPAN=<b>    teto de bytes hasheados por tensor (default 16 MiB)
//   SIG_WHISPER_KV_CANARY=<v>        enche o cache com padrao conhecido ANTES do 1o grafo:
//                                      "0x0000"|"0xAAAA"|"lcg"|"<hex>"  (default 0xAAAA quando set)
//   SIG_WHISPER_KV_CANARY_MAX=<b>    teto do preenchimento (default 16 MiB por tensor)
//
// IMPORTANTE (anti-"correcao"): o canario SOBRESCREVE o cache com o padrao apenas no build
// DIAG e apenas com o env setado; e instrumento de MEDICAO (distingue regiao
// escrita/usada/padding/guarda), NUNCA um fix. Nao existe no NORMAL.
//
// Registro JSONL por nó relevante (uma linha por fase antes/depois):
//   {graph,call,phase,name,op,type,ne,nb,view_offs,buffer,kv,kv_off,kv_nbytes,span,
//    fnv,src_name,src_op,src_ne,src_fnv,note}
//   kv=/kv_off=/kv_nbytes= => intervalo de bytes DESTE tensor dentro do buffer K/V registrado
//   (o "intervalo de bytes / posicao de cache usada"); fnv = hash logico do payload LIDO do
//   backend (before = conteudo ANTES da escrita; after = conteudo DEPOIS).
struct ggml_tensor;      // forward (escopo GLOBAL — o header nao inclui ggml.h)
struct ggml_cgraph;
struct ggml_backend_buffer;

namespace sig_kv {

bool enabled();       // SIG_WHISPER_KV_TRACE_DIR/DIR presente
bool canary_enabled();// SIG_WHISPER_KV_CANARY presente

// Registra um buffer K/V (base + tensores k/v) para o mapeamento de offset->posicao.
void register_kv(const char * which, ::ggml_tensor * k, ::ggml_tensor * v);

// Preenche os buffers registrados com o padrao do canario e registra hashes/primeiros bytes.
void canary_go();

// Uma chamada por grafo, antes e depois do compute (phase = "before"|"after").
void trace_graph(::ggml_cgraph * gf, const char * graph, const char * phase, int call);

int next_call(const char * graph);

} // namespace sig_kv
