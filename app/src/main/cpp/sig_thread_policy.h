// sig_thread_policy.h — R10/fase G (G2) + R11/D2: POLÍTICA DE THREADS do caminho real.
//
// Fonte ÚNICA da decisão (incluída pelo whisper_jni.cpp de produção e pelo
// teste de host tools/whisper/sig_thread_policy_test.cpp). Sem env, sem
// mutação global de processo: a decisão é LOCAL AO JOB (computada por
// transcrição, a partir de fatos do contexto carregado).
//
// FATOS MEDIDOS que sustentam a tabela (mesma máquina, turbo F16, harness core):
//   - encoder = 91,5–98 % do tempo do job (c01/c02);
//   - CPU: 4→6 threads = −12,2 %, 4→8 = −23,3 % (mediana de 3 pares intercalados;
//     ganho TODO no encoder; decoder piora ~+1 s);
//   - Vulkan: 4 × 8 = +1,1 % (o knob é inócuo: o encoder roda na GPU e não usa
//     o pool do CPU) ⇒ não se pede 8 em GPU;
//   - trocar threads NÃO muda 1 byte da transcrição (sha do texto idêntico);
//   - R11/D2 (medido nesta rodada, MESMA lib, pares intercalados): o ganho 4→8
//     NÃO é função do tamanho do ARQUIVO e sim de arquitetura + TIPO dos
//     pesos do modelo carregado — q5_0 (574 MB, mesma arquitetura large-v3/
//     turbo: n_audio_state=1280, n_audio_layer=32) NÃO escala como o F16 de
//     1,62 GB (encoder +2 a +5 %, decoder ~2,4× pior). R13/E1: este comentário
//     foi corrigido — a versão anterior dizia que q5 "escalava como o F16",
//     o que contradiz o critério por ftype abaixo (regra 4b). Sem mudança de
//     comportamento: só a frase estava errada.
//     Ver CHECKPOINT-D2-WHISPER-RODADA11.md.
//
// EFETIVO NA VERSÃO ATUAL (whisper.cpp deste fork): `whisper_full_params.n_threads`
// alimenta ENCODER + DECODER + mel + auto-detect de idioma + decoders paralelos do
// beam (whisper.cpp: 7996/8006/8255/8393/8503). NÃO existe `n_threads_batch` nesta
// árvore ⇒ o parâmetro NÃO é "threads do encoder": é um único botão para o job
// inteiro (por isso o decoder piora um pouco com 8).
#ifndef SIG_THREAD_POLICY_H
#define SIG_THREAD_POLICY_H

#include <algorithm>
#include <atomic>

namespace sig {

// Pedido do produto (comportamento anterior ao R10/G): 4.
constexpr int kThreadsRequested = 4;
// Teto do contrato desta rodada: 8 (não há medição acima disso; o hardware do
// alvo tem 8 núcleos).
constexpr int kThreadsMax = 8;
// R11/D2: o limite de BYTES (1 GiB) deixou de ser o critério de classe. Fica
// apenas (a) como diagnóstico e (b) como classe de último recurso quando a
// arquitetura do modelo carregado NÃO é legível (preserva o comportamento R10
// nesse caso). q5_0 (574 MB) provou que bytes não separam as classes medidas.
constexpr long long kLargeModelBytes = 1LL << 30;
// R11/D2: fatos de ARQUITETURA do modelo EFETIVAMENTE carregado (não do nome do
// arquivo, nem do tamanho): d_model do encoder e nº de camadas do encoder.
// Classe MEDIDA = large-v3/turbo (1280 / 32).
constexpr int kLargeArchAudioState = 1280;
constexpr int kLargeArchAudioLayer = 32;
// R11/D2: e o TIPO dos pesos (whisper_model_ftype), porque a medição desta
// rodada mostrou que o ganho 4→8 NÃO é função da arquitetura sozinha:
//   - F16 (GGML_TYPE_F16 = 1), large: encoder −23 % (4→8), medido (R10 e D2);
//   - q5_0 (GGML_TYPE_Q5_0 = 6), MESMA arquitetura large: encoder +2 a +5 % e
//     decoder ~2,4× PIOR (D2, pares intercalados no núcleo) ⇒ sem ganho líquido.
//   - O limiar de BYTES do R10 acertava o q5_0 por acidente (574 MB < 1 GiB),
//     mas ERRAVA um quantizado grande (> 1 GiB, ex. q8_0 ≈ 1,7 GB): esse subia
//     para 8 sem medição. O critério passa a ser arquitetura + tipo.
constexpr int kMeasuredFtype = 1;   // GGML_TYPE_F16

// Fatos do contexto carregado (lidos por whisper_model_* logo após a carga).
// unknown = true quando os acessores não estão disponíveis/legíveis.
struct ModelFacts {
    long long bytes;        // stat do arquivo carregado (diagnóstico)
    int n_audio_state;      // d_model do encoder (0 = desconhecido)
    int n_audio_layer;      // camadas do encoder (0 = desconhecido)
    int ftype;              // tipo ggml dos pesos (diagnóstico; ex. q5_0)
    bool unknown;           // arquitetura não legível nesta carga

    ModelFacts()
        : bytes(0), n_audio_state(0), n_audio_layer(0), ftype(-1), unknown(true) {}

    ModelFacts(long long bytes_, int n_audio_state_, int n_audio_layer_, int ftype_)
        : bytes(bytes_), n_audio_state(n_audio_state_), n_audio_layer(n_audio_layer_),
          ftype(ftype_), unknown(false) {}

    // Classe de arquitetura MEDIDA (large-v3/turbo). Não é "modelo grande por
    // bytes": um base F16 (148 MB) e um large-q5_0 (574 MB) têm fatos diferentes.
    bool large_arch() const {
        return !unknown && n_audio_state >= kLargeArchAudioState &&
               n_audio_layer >= kLargeArchAudioLayer;
    }

    // R11/D2: a classe COM medição de ganho de threads = large + pesos F16.
    // Quantizado na MESMA arquitetura NÃO entra (medido no q5_0).
    bool measured_class() const { return large_arch() && ftype == kMeasuredFtype; }

    bool quantized() const { return !unknown && ftype >= 0 && ftype != kMeasuredFtype; }
};

// Seam de bancada (R11/D2): sobrescrita ONE-SHOT do resultado da política,
// consumida por job. Existe para que o MESMO caminho (Activity/JNI real) possa
// rodar os DOIS braços de um par controlado (ex.: forçar 4 vs política 8) sem
// trocar lib, modelo, parâmetros ou processo. Valor 0 = seam INATIVO (a
// política decide — comportamento de produção). Clampa em [0, kThreadsMax] e
// `consume()` lê e zera ⇒ um pedido vale para NO MÁXIMO UM job (não vaza para
// o seguinte, mesmo que o caller esqueça de limpar).
class ThreadsOverride {
public:
    void set(int v) {
        if (v < 0) v = 0;
        if (v > kThreadsMax) v = kThreadsMax;
        value_.store(v, std::memory_order_relaxed);
    }
    int peek() const { return value_.load(std::memory_order_relaxed); }
    int consume() { return value_.exchange(0, std::memory_order_relaxed); }

private:
    std::atomic<int> value_{0};
};

struct ThreadDecision {
    int requested;         // o que o produto pedia antes (4) — vai no diagnóstico
    int effective;         // o que é APLICADO em params.n_threads
    int capacity;          // min(affinity ∩ hardware, kThreadsMax); 0 = desconhecido
    int override_value;    // seam de bancada consumido (0 = nenhum)
    bool capacity_restricted = false;  // afinidade < hardware (capacidade restrita)
    const char * source;   // "policy" (tabela) ou "lab_override" (seam)
    const char * policy;   // rótulo curto e estável da regra aplicada
    const char * reason;   // por que (fato medido ou ausência dele)
};

// backend_kind: 0 = CPU, 1 = Vulkan, 2 = OpenCL (mesma convenção do JNI).
// facts: fatos do modelo CARREGADO (arquitetura + bytes).
// hw_concurrency: núcleos do DISPOSITIVO (std::thread::hardware_concurrency()).
// override_value: seam de bancada (0 = desligado) — já consumido pelo JNI.
// avail_cpus: CPUs PERMITIDAS ao processo agora (sched_getaffinity); 0 = não
//   observável ⇒ usa só hw_concurrency. D1 mediu o app OCIOSO com cpuset 0-2
//   enquanto o job em foreground tem 0-7: pedir 8 com afinidade restrita é o
//   cenário de spin/barreira (mesma assinatura do "tempo anormal" do R10), por
//   isso a capacidade efetiva é a INTERSEÇÃO (affinity ∩ hardware), com teto 8.
//
// Regras (ordem de avaliação):
//   1. capacidade desconhecida ......................... 4 (fallback do contrato)
//   2. seam de bancada ativo ........................... min(override, capacidade)
//   3. backend GPU (Vulkan/OpenCL) ..................... min(4, capacidade) — 8 não ganha
//   4. arquitetura LEGÍVEL fora da classe medida ....... min(4, capacidade) — sem medição
//   5. classe medida + capacidade ≥ 6 .................. capacidade (6..8)
//   6. capacidade < 6 núcleos .......................... min(4, capacidade) — o ganho começa em 6
//   7. arquitetura ilegível ............................ classe por bytes (R10) + capacidade
inline ThreadDecision choose_threads(int backend_kind, const ModelFacts & facts,
                                     int hw_concurrency, int override_value = 0,
                                     int avail_cpus = 0) {
    ThreadDecision d;
    d.requested = kThreadsRequested;
    d.effective = kThreadsRequested;
    d.capacity  = 0;
    d.override_value = 0;
    d.source    = "policy";
    d.policy    = "fallback_4";
    d.reason    = "capacidade de nucleos desconhecida";

    if (hw_concurrency < 1) {
        return d;                       // (1) fallback declarado: 4
    }
    // Capacidade OBSERVÁVEL: interseção da afinidade real do processo com o
    // hardware, teto kThreadsMax. Afinidade não observável (0) ⇒ hardware.
    const int cap_seen = (avail_cpus > 0) ? std::min(avail_cpus, hw_concurrency)
                                          : hw_concurrency;
    d.capacity = std::min(cap_seen, kThreadsMax);
    d.capacity_restricted = (avail_cpus > 0 && avail_cpus < hw_concurrency);

    if (override_value > 0) {
        // (2) SEAM DE BANCADA: o caminho real roda com o valor pedido pelo lab
        // (clampado à capacidade real do device). Não é a política — é
        // instrumento, e por isso vem com rótulo próprio. O clamp no teto é
        // repetido aqui (defesa em profundidade): vale mesmo se o caller passar
        // um valor acima de kThreadsMax sem passar por ThreadsOverride::set.
        const int ovr = std::min(override_value, kThreadsMax);
        d.override_value = ovr;
        d.effective = std::min(ovr, d.capacity);
        d.source    = "lab_override";
        d.policy    = "lab_override";
        d.reason    = "seam de bancada (par controlado no mesmo caminho/lib/params)";
        return d;
    }
    if (backend_kind != 0) {
        // (3) GPU: encoder/decoder no dispositivo GPU; o pool do CPU só atende
        // grafos auxiliares (mel/prompt/decoder pequeno). Medido: +1,1 % com 8.
        d.effective = std::min(kThreadsRequested, d.capacity);
        d.policy    = "gpu_pool_4";
        d.reason    = "encoder/decoder no GPU; pool do CPU em grafos auxiliares (8 threads = +1,1 % medido)";
        return d;
    }
    if (!facts.unknown && facts.large_arch() && !facts.measured_class()) {
        // (4b) arquitetura MEDIDA, mas pesos NÃO F16 (quantizado: q5_0/q8_0/...):
        // D2 mediu no q5_0 que o encoder ganha só +2 a +5 % com 8 threads e o
        // decoder fica ~2,4× pior ⇒ sem ganho líquido. Não se sobe.
        d.effective = std::min(kThreadsRequested, d.capacity);
        d.policy    = "cpu_classe_quantizada_4";
        d.reason    = "pesos quantizados (ftype != F16) na classe large: medido em q5_0 "
                      "(encoder +2-5 % a 8 threads, decoder ~2,4x pior)";
        return d;
    }
    if (!facts.unknown && !facts.large_arch()) {
        // (4) arquitetura LEGÍVEL, porém fora da classe medida: não existe
        // medição de ganho de threads nessa classe ⇒ não se sobe.
        d.effective = std::min(kThreadsRequested, d.capacity);
        d.policy    = "cpu_classe_nao_medida_4";
        d.reason    = "arquitetura do modelo carregado fora da classe medida (large-v3/turbo)";
        return d;
    }
    const bool large = facts.unknown ? (facts.bytes >= kLargeModelBytes) : facts.measured_class();
    if (!large) {
        // (7) arquitetura ilegível E bytes pequenos: nada sustenta 8.
        d.effective = std::min(kThreadsRequested, d.capacity);
        d.policy    = "cpu_arquitetura_desconhecida_4";
        d.reason    = "arquitetura ilegivel e modelo < 1 GiB: sem medicao de ganho";
        return d;
    }
    if (d.capacity < 6) {
        // (6) classe medida, mas o contexto não sustenta o ganho medido: 4
        // (nunca mais que a capacidade real do dispositivo).
        d.effective = std::min(kThreadsRequested, d.capacity);
        d.policy    = "cpu_capacidade_insuficiente_4";
        d.reason    = "capacidade < 6 nucleos: ganho medido (4->6 -> 4->8) nao se aplica";
        return d;
    }
    // (5) única combinação em que se sobe de 4: CPU + CLASSE MEDIDA (large F16)
    // + ≥ 6 núcleos.
    d.effective = d.capacity;           // 6..8, nunca acima de kThreadsMax
    d.policy    = facts.unknown ? "cpu_grande_por_bytes_capacidade"
                                : "cpu_classe_grande_f16_capacidade";
    d.reason    = "encoder CPU domina (91,5-98 %) e escala com nucleos (4->6 -12,2 %, 4->8 -23,3 %); "
                  "ganho medido na CLASSE large F16 (q5_0 da mesma arquitetura NAO escala)";
    return d;
}

// Compat: chamada herdada do R10 (só bytes). Equivale a arquitetura ilegível.
inline ThreadDecision choose_threads(int backend_kind, long long model_bytes,
                                     int hw_concurrency, int override_value = 0,
                                     int avail_cpus = 0) {
    ModelFacts facts;
    facts.bytes = model_bytes;
    return choose_threads(backend_kind, facts, hw_concurrency, override_value, avail_cpus);
}

}  // namespace sig

#endif  // SIG_THREAD_POLICY_H
