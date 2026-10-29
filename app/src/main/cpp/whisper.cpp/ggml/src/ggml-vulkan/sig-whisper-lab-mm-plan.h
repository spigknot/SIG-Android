// R55/MM-PLAN — PEDIDO, ALVO, FAMILIA E PLANO do laboratorio de MUL_MAT.
//
// Este header e' a UNICA fonte de verdade do experimento de tile (S/M/L) e do
// trace funcional. Ele e' consumido, no MESMO arquivo de producao
// (ggml-vulkan.cpp), por quatro pontos do mesmo op:
//
//   1. ggml_backend_vk_graph_compute  -> preflight (antes de qualquer comando);
//   2. ggml_vk_mul_mat_q_f16 (align)  -> kpad/aligned da pipeline EFETIVA;
//   3. ggml_vk_guess_matmul_pipeline  -> variante devolvida ao despacho;
//   4. ggml_vk_matmul / graph_compute -> trace de dispatch e de conclusao.
//
// O que este header NAO faz (de proposito):
//   * nao decide qual variante o AUTO escolheria. Essa regra e' a do seletor
//     real (`ggml_vk_guess_matmul_variant_index`, extraida do seletor que ja
//     existia) e entra aqui como `auto_idx`;
//   * nao decide a familia (f16acc/f32acc). Essa regra e'
//     `ggml_vk_get_mul_mat_mat_pipeline`; o chamador resolve a familia com ela
//     e resume o resultado em `FamilyCaps`.
//
// Consequencia: o teste HOST inclui ESTE arquivo e chama `plan_op`/`decide_graph`,
// ou seja, exercita a mesma funcao que o backend executa — nao uma copia.
//
// Sem dependencia de Vulkan/ggml: entra no build do device e no build HOST.
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <set>
#include <string>

namespace sig_lab_mm {

// Valores reais de ggml.h (GGML_TYPE_F32 = 0, GGML_TYPE_F16 = 1) e de ggml_prec
// (GGML_PREC_DEFAULT = 0). Fixados aqui para o header nao depender do ggml.
constexpr int kTypeF32     = 0;
constexpr int kTypeF16     = 1;
constexpr int kPrecDefault = 0;
// ggml_prec real: GGML_PREC_DEFAULT = 0, GGML_PREC_F32 = 10. O probe validado
// usa `--prec f32`, portanto 10 e' entrada LEGAL deste experimento; qualquer
// outro valor (F16 explicito inclusive) continua fora de escopo.
constexpr int kPrecF32     = 10;

// ---------------------------------------------------------------------------
// 3. FAMILIA — declarada aqui porque o ESCOPO (§2) depende da familia efetiva.
// ---------------------------------------------------------------------------
enum class Family : int {
    NENHUMA = 0,
    F16ACC,
    F32ACC,
    F32,
    F32_F16,
    BF16,
    QUANT,
};

inline const char * familia_nome(Family f) {
    switch (f) {
        case Family::NENHUMA: return "nenhuma";
        case Family::F16ACC:  return "f16acc";
        case Family::F32ACC:  return "f32acc";
        case Family::F32:     return "f32";
        case Family::F32_F16: return "f32_f16";
        case Family::BF16:    return "bf16";
        case Family::QUANT:   return "quant";
    }
    return "nenhuma";
}

// ---------------------------------------------------------------------------
// 1. PEDIDO — lido UMA vez por entrada de grafo e copiado para ca.
// ---------------------------------------------------------------------------
enum class Tile : int {
    AUSENTE  = 0,   // env ausente
    AUTO     = 1,   // "auto": sem override, trace permitido
    S        = 2,
    M        = 3,
    L        = 4,
    INVALIDO = 5,   // env presente com valor desconhecido (inclusive vazio)
};

struct Request {
    Tile tile         = Tile::AUSENTE;
    bool trace        = false;
    bool tile_present = false;
    bool trace_present= false;
    // Copia propria do texto: nada aqui aponta para o buffer de getenv.
    char tile_text[16]  {};
    char trace_text[16] {};

    bool tem_override() const {
        return tile == Tile::S || tile == Tile::M || tile == Tile::L;
    }
    bool tem_pedido() const { return tile_present || trace_present; }
    // 0 = s, 1 = m, 2 = l; -1 quando o pedido nao forca variante.
    int indice_forcado() const {
        switch (tile) {
            case Tile::S: return 0;
            case Tile::M: return 1;
            case Tile::L: return 2;
            default:      return -1;
        }
    }
    const char * nome() const {
        switch (tile) {
            case Tile::AUSENTE:  return "ausente";
            case Tile::AUTO:     return "auto";
            case Tile::S:        return "s";
            case Tile::M:        return "m";
            case Tile::L:        return "l";
            case Tile::INVALIDO: return "invalido";
        }
        return "invalido";
    }
};

inline void copiar_texto(char * dst, size_t cap, const char * src) {
    if (cap == 0) {
        return;
    }
    dst[0] = '\0';
    if (src == nullptr) {
        return;
    }
    size_t n = 0;
    while (src[n] != '\0' && n + 1 < cap) {
        dst[n] = src[n];
        ++n;
    }
    dst[n] = '\0';
}

// Unica leitura/interpretacao do pedido. `tile_env`/`trace_env` sao os valores
// crus de getenv (ou nullptr) e NAO sao retidos.
inline Request make_request(const char * tile_env, const char * trace_env) {
    Request r;
    r.tile_present  = (tile_env  != nullptr);
    r.trace_present = (trace_env != nullptr);
    r.trace         = r.trace_present;
    copiar_texto(r.tile_text,  sizeof r.tile_text,  tile_env);
    copiar_texto(r.trace_text, sizeof r.trace_text, trace_env);
    if (!r.tile_present) {
        r.tile = Tile::AUSENTE;
    } else if (strcmp(r.tile_text, "auto") == 0) {
        r.tile = Tile::AUTO;
    } else if (strcmp(r.tile_text, "s") == 0) {
        r.tile = Tile::S;
    } else if (strcmp(r.tile_text, "m") == 0) {
        r.tile = Tile::M;
    } else if (strcmp(r.tile_text, "l") == 0) {
        r.tile = Tile::L;
    } else {
        r.tile = Tile::INVALIDO;
    }
    return r;
}

// ---------------------------------------------------------------------------
// 2. ALVO — leitura minima de um no' e o filtro do perfil de laboratorio.
// ---------------------------------------------------------------------------
struct OpView {
    bool vazio    = false;
    bool mul_mat  = false;
    bool compute  = false;
    bool fontes   = false;   // src0 e src1 presentes
    bool quantize_y = false; // caminho Q8_1 (integer dot): fora do perfil

    int  src0_type = -1;     // tipo EFETIVO usado pelo seletor
    int  src1_type = -1;

    bool src0_contig = false;
    bool src1_contig = false;

    uint64_t ne00 = 0, ne01 = 0, ne02 = 0, ne03 = 0;   // src0 = [K, M, ne2, ne3]
    uint64_t ne10 = 0, ne11 = 0, ne12 = 0, ne13 = 0;   // src1 = [K, N, ne2, ne3]
    uint64_t d_ne0 = 0, d_ne1 = 0, d_ne2 = 0, d_ne3 = 0; // dst = [M, N, 1, 1]

    int prec = kPrecDefault;
};

// Geometrias do alvo: (M, N, K). Deliberadamente exatas.
constexpr int kGeomCount = 3;
constexpr uint64_t kGeomM[kGeomCount] = { 1280, 1280, 5120 };
constexpr uint64_t kGeomN[kGeomCount] = { 1500, 1500, 1500 };
constexpr uint64_t kGeomK[kGeomCount] = { 1280, 5120, 1280 };

inline bool geometria_no_alvo(uint64_t m, uint64_t n, uint64_t k) {
    for (int i = 0; i < kGeomCount; ++i) {
        if (m == kGeomM[i] && n == kGeomN[i] && k == kGeomK[i]) {
            return true;
        }
    }
    return false;
}

// nullptr = no' dentro do perfil. Caso contrario, motivo ESTATICO e' estavel
// para consumo por ferramenta (nunca ponteiro para buffer temporario).
//
// R56/H01-H02: o escopo depende da FAMILIA EFETIVA resolvida pelo getter real
// (ggml_vk_get_mul_mat_mat_pipeline), nao de `op.prec == DEFAULT`. Motivos:
//   * `--prec f32` chega aqui como GGML_PREC_F32 (10) e e' entrada legal;
//   * DEFAULT nao significa "perfil V0": com familia F16ACC o override estaria
//     fora do contrato F32acc e NAO pode ser aprovado como celula S/M.
inline bool precisao_autorizada(int prec) {
    return prec == kPrecDefault || prec == kPrecF32;
}

// Checagens independentes de familia (baratas, sem tocar no backend).
inline const char * fora_de_escopo_motivo_base(const OpView & op) {
    if (op.vazio)          return "vazio";
    if (!op.mul_mat)       return "op";
    if (!op.compute)       return "nao_compute";
    if (!op.fontes)        return "sem_fontes";
    if (op.src0_type != kTypeF16 || op.src1_type != kTypeF32) return "tipo";
    if (!op.src0_contig || !op.src1_contig) return "contiguidade";
    if (op.quantize_y)     return "quantizado_y";
    // batch 1 em ambos e no destino
    if (op.ne02 != 1 || op.ne03 != 1 || op.ne12 != 1 || op.ne13 != 1 ||
        op.d_ne2 != 1 || op.d_ne3 != 1) {
        return "batch";
    }
    if (!precisao_autorizada(op.prec)) return "precisao";
    // K/M/N coerentes entre si e nas geometrias do alvo
    if (op.ne10 != op.ne00)  return "shape";
    if (op.d_ne0 != op.ne01 || op.d_ne1 != op.ne11) return "shape";
    if (!geometria_no_alvo(op.ne01, op.ne11, op.ne10)) return "shape";
    return nullptr;
}

inline const char * fora_de_escopo_motivo(const OpView & op, Family fam) {
    if (const char * base = fora_de_escopo_motivo_base(op)) {
        return base;
    }
    // Familia DESCONHECIDA (getter falhou/provider ausente) nao e' "fora do
    // perfil": e' recusa (UNKNOWN retem a decisao, nao libera AUTO).
    if (fam == Family::NENHUMA) {
        return "familia_ausente";
    }
    // Contrato V0 = acumulacao F32. F16acc conserva AUTO (nao aplicacao),
    // sem FAILED e sem aprovacao da celula.
    if (fam != Family::F32ACC) {
        return fam == Family::F16ACC ? "familia_f16acc" : "familia";
    }
    return nullptr;
}

inline bool em_escopo(const OpView & op, Family fam) {
    return fora_de_escopo_motivo(op, fam) == nullptr;
}

// ---------------------------------------------------------------------------
// 3b. FAMILIA — disponibilidade REAL da familia resolvida (nao a mascara sozinha)
// ---------------------------------------------------------------------------
struct VariantCaps {
    bool mask = false;             // device->mul_mat_{s,m,l}[tipo]
    bool ptr = false;              // mmp->{s,m,l} != nullptr
    bool ptr_aligned = false;      // mmp->a_{s,m,l} != nullptr
    bool initialized = false;      // ja criada por ggml_vk_create_pipeline
    bool initialized_aligned = false;
    bool failed = false;           // reprovada: nao re-tentar
    bool failed_aligned = false;
    bool compiled = false;         // false PODE ser lazy valido (needed->load_shaders)
    bool compiled_aligned = false;
    uint32_t align = 0;            // align da pipeline nao-alinhada
    uint32_t align_aligned = 0;    // align da pipeline alinhada
};

inline const char * variante_nome(int idx) {
    switch (idx) {
        case 0: return "s";
        case 1: return "m";
        case 2: return "l";
        default: return "?";
    }
}

struct FamilyCaps {
    Family family = Family::NENHUMA;
    VariantCaps v[3];   // 0 = s, 1 = m, 2 = l

    // Disponivel para despacho. `compiled == false` NAO recusa (lazy e' legal);
    // `initialized == false`, `failed`, ponteiro nulo, mascara falsa e align 0
    // recusam — sao exatamente os casos em que ->align/->wg_denoms seriam
    // invalidos para quem despacha.
    const char * indisponivel_motivo(int idx, bool aligned) const {
        if (idx < 0 || idx > 2)  return "variante";
        const VariantCaps & c = v[idx];
        if (!c.mask)             return "mascara";
        if (!c.ptr)              return "ponteiro";
        if (!c.initialized)      return "nao_inicializada";
        if (c.failed)            return "falhou";
        if (aligned) {
            if (!c.ptr_aligned)         return "ponteiro_aligned";
            if (!c.initialized_aligned) return "aligned_nao_inicializada";
            if (c.failed_aligned)       return "aligned_falhou";
            if (c.align_aligned == 0)   return "align_zero";
        } else if (c.align == 0) {
            return "align_zero";
        }
        return nullptr;
    }
    bool disponivel(int idx, bool aligned) const {
        return indisponivel_motivo(idx, aligned) == nullptr;
    }
};

// Regra UNICA de viabilidade de uma variante FORCADA. O operador aceita a
// alternativa SEM alinhamento quando a pipeline plain existe: `resolver_align`
// cai para ela. Antes, `plan_op` (despacho) aceitava e `decide_graph`
// (preflight) recusava o MESMO no' — a celula S valida morria no preflight.
// nullptr = aplicavel por algum caminho; senao, motivo ESTAVEL do alinhado.
inline const char * variante_motivo(const FamilyCaps & caps, int idx) {
    if (idx < 0 || idx > 2) {
        return "variante";
    }
    const char * alinhado = caps.indisponivel_motivo(idx, true);
    if (alinhado == nullptr) {
        return nullptr;
    }
    if (caps.indisponivel_motivo(idx, false) == nullptr) {
        return nullptr;   // plain valida: alternativa legal, mesmo no'
    }
    return alinhado;
}

// ---------------------------------------------------------------------------
// 4. PLANO — decisao por no', consumida por align/selecao/despacho.
// ---------------------------------------------------------------------------
enum class Status : int {
    FORA_DE_ESCOPO = 0,   // segue AUTO, candidato nao aplicado (sem FAILED)
    ALVO_AUTO      = 1,   // no alvo, pedido "auto": trace, sem override
    ALVO_OK        = 2,   // no alvo, variante forcada disponivel -> aplica
    ALVO_RECUSADO  = 3,   // no alvo, variante indisponivel -> FAILED
    PEDIDO_INVALIDO= 4,   // pedido desconhecido -> FAILED
};

inline const char * status_nome(Status s) {
    switch (s) {
        case Status::FORA_DE_ESCOPO:  return "FORA_DE_ESCOPO";
        case Status::ALVO_AUTO:       return "ALVO_AUTO";
        case Status::ALVO_OK:         return "ALVO_OK";
        case Status::ALVO_RECUSADO:   return "ALVO_RECUSADO";
        case Status::PEDIDO_INVALIDO: return "PEDIDO_INVALIDO";
    }
    return "?";
}

struct OpPlan {
    Status status = Status::FORA_DE_ESCOPO;
    const char * motivo = "-";
    bool aplicavel = false;    // override/auto vale para ESTE no'
    bool forcado = false;      // variante veio do pedido (nao do auto)
    int  auto_idx = 0;
    int  efetiva_idx = 0;
    Family family = Family::NENHUMA;
    int  prec_efetiva = kPrecDefault;   // ggml_prec REAL do no' (family resolution)
    bool aligned = false;
    uint32_t align = 0;
    uint32_t kpad = 0;

    const char * efetiva_nome() const { return variante_nome(efetiva_idx); }
};

// Alinhamento/ kpad coerentes com a variante EFETIVA (auto ou forcada). Antes
// da R55 o kpad era calculado com o align da variante do AUTO e a pipeline
// forcada podia ter outro align — o trace e o despacho discordavam.
inline void resolver_align(OpPlan & p, const OpView & op, const FamilyCaps & caps) {
    const int idx = (p.efetiva_idx >= 0 && p.efetiva_idx <= 2) ? p.efetiva_idx : p.auto_idx;
    p.efetiva_idx = idx;
    const VariantCaps & c = caps.v[idx];
    const uint32_t align_a = c.align_aligned != 0 ? c.align_aligned : c.align;
    const uint64_t k = op.ne10;
    const bool pode_alinhar = caps.disponivel(idx, true);
    const bool cabe = (align_a != 0) && (k % align_a == 0) && op.ne01 > 8 && op.ne11 > 8;
    p.aligned = !op.quantize_y && pode_alinhar && cabe;
    if (op.quantize_y) {
        p.align = c.align != 0 ? c.align : align_a;
        p.kpad  = 0;
        return;
    }
    if (p.aligned) {
        p.align = align_a;
        p.kpad  = (uint32_t) k;                     // k ja e' multiplo de align
    } else {
        p.align = c.align != 0 ? c.align : align_a; // pipeline realmente usada
        p.kpad  = (p.align != 0) ? (uint32_t) (((k + p.align - 1) / p.align) * p.align) : 0;
    }
}

inline OpPlan plan_op(const Request & req, const OpView & op, int auto_idx, const FamilyCaps & caps) {
    OpPlan p;
    p.auto_idx    = (auto_idx >= 0 && auto_idx <= 2) ? auto_idx : 0;
    p.efetiva_idx = p.auto_idx;
    p.family      = caps.family;
    p.prec_efetiva = op.prec;

    const char * fora = fora_de_escopo_motivo(op, caps.family);
    if (req.tile == Tile::INVALIDO) {
        p.status    = Status::PEDIDO_INVALIDO;
        p.motivo    = "pedido_invalido";
        p.aplicavel = false;
    } else if (fora != nullptr) {
        // Familia DESCONHECIDA + pedido que forca variante = recusa: UNKNOWN
        // retem a reserva e nao libera AUTO. Sem override (auto/ausente), o no'
        // apenas nao e' alvo deste experimento.
        const bool familia_unknown = (strcmp(fora, "familia_ausente") == 0);
        if (familia_unknown && req.tem_override()) {
            p.status    = Status::ALVO_RECUSADO;
            p.motivo    = fora;
            p.aplicavel = false;
        } else {
            p.status    = Status::FORA_DE_ESCOPO;
            p.motivo    = fora;
            p.aplicavel = false;
        }
    } else if (req.tile == Tile::AUSENTE) {
        p.status    = Status::FORA_DE_ESCOPO;
        p.motivo    = "pedido_ausente";
        p.aplicavel = false;
    } else if (req.tile == Tile::AUTO) {
        p.status    = Status::ALVO_AUTO;
        p.motivo    = "auto";
        p.aplicavel = true;
        p.forcado   = false;
    } else {
        const int desejado = req.indice_forcado();
        p.aplicavel = true;
        p.forcado   = true;
        p.efetiva_idx = (desejado >= 0) ? desejado : p.auto_idx;
        if (desejado < 0) {
            // inalcancavel com Tile::S/M/L, mantido defensivo
            p.status    = Status::PEDIDO_INVALIDO;
            p.motivo    = "pedido_invalido";
            p.aplicavel = false;
        } else if (variante_motivo(caps, desejado) != nullptr) {
            // Alvo indisponivel nos DOIS caminhos: NAO substitui por auto/M
            // e NAO aprova.
            p.status    = Status::ALVO_RECUSADO;
            p.motivo    = variante_motivo(caps, desejado);
            p.aplicavel = false;
        } else {
            p.status = Status::ALVO_OK;
            p.motivo = "aplicado";
        }
    }
    resolver_align(p, op, caps);
    return p;
}

// ---------------------------------------------------------------------------
// 5. DECISAO DE GRAFO — preflight. `caps_for` resolve a familia REAL do no'
//    (no backend: ggml_vk_get_mul_mat_mat_pipeline).
// ---------------------------------------------------------------------------
struct CapsProvider {
    FamilyCaps (*fn)(void * user, const OpView & op) = nullptr;
    void * user = nullptr;

    FamilyCaps obter(const OpView & op) const {
        if (fn == nullptr) {
            return FamilyCaps();
        }
        return fn(user, op);
    }
};

struct GraphDecision {
    Status status = Status::FORA_DE_ESCOPO;
    const char * motivo = "-";
    int nos = 0;         // total de nos do grafo
    int alvos = 0;       // nos DENTRO do perfil
    int fora = 0;        // mul_mat computaveis FORA do perfil
    int fora_familia = 0;// subconjunto de `fora` barrado pela familia (nao-F32acc)
    int recusados = 0;   // alvos com a variante pedida indisponivel
    const char * detalhe = "-";   // motivo da indisponibilidade (1o alvo)
};

// Semantica (R55):
//  * pedido invalido            -> PEDIDO_INVALIDO   (FAILED antes de comando)
//  * sem nenhum no' alvo        -> FORA_DE_ESCOPO    (segue auto, NAO FAILED)
//  * alvo com variante ausente  -> ALVO_RECUSADO     (FAILED, sem M substituto)
//  * alvo ok, pedido "auto"     -> ALVO_AUTO
//  * alvo ok, pedido s/m/l      -> ALVO_OK
inline GraphDecision decide_graph(const Request & req, const OpView * ops, size_t n, const CapsProvider & caps_for) {
    GraphDecision d;
    d.nos = (int) n;
    for (size_t i = 0; i < n; ++i) {
        const OpView & op = ops[i];
        if (fora_de_escopo_motivo_base(op) != nullptr) {
            if (op.mul_mat && op.compute && !op.vazio && op.fontes) {
                d.fora++;
            }
            continue;
        }
        // Sem pedido de tile nao ha' decisao de familia a tomar: so' contagem.
        if (req.tile == Tile::AUSENTE) {
            d.alvos++;
            continue;
        }
        // So' resolve a familia dos candidatos (o getter real cria pipeline).
        const FamilyCaps caps = caps_for.obter(op);
        if (caps.family == Family::NENHUMA) {
            // Familia desconhecida: com override e' RECUSA (nao vira AUTO);
            // sem override apenas nao conta como alvo.
            if (req.tem_override()) {
                d.alvos++;   // e' candidato: a recusa vem da familia desconhecida
                if (d.recusados == 0) {
                    d.detalhe = "familia_ausente";
                }
                d.recusados++;
            } else {
                d.fora_familia++;
                d.fora++;
            }
            continue;
        }
        if (!em_escopo(op, caps.family)) {
            // Familia nao autorizada (F16acc/outras): segue AUTO, nao e' alvo.
            d.fora_familia++;
            d.fora++;
            continue;
        }
        d.alvos++;
        if (!req.tem_override()) {
            continue;
        }
        const int desejado = req.indice_forcado();
        const char * m = variante_motivo(caps, desejado);
        if (m != nullptr) {
            if (d.recusados == 0) {
                d.detalhe = m;
            }
            d.recusados++;
        }
    }
    if (req.tile == Tile::INVALIDO) {
        d.status = Status::PEDIDO_INVALIDO;
        d.motivo = "pedido_invalido";
    } else if (req.tile == Tile::AUSENTE) {
        d.status = Status::FORA_DE_ESCOPO;
        d.motivo = "pedido_ausente";
    } else if (d.alvos == 0) {
        d.status = Status::FORA_DE_ESCOPO;
        d.motivo = "sem_alvo";
    } else if (d.recusados > 0) {
        d.status = Status::ALVO_RECUSADO;
        d.motivo = "indisponivel";
    } else if (req.tile == Tile::AUTO) {
        d.status = Status::ALVO_AUTO;
        d.motivo = "auto";
    } else {
        d.status = Status::ALVO_OK;
        d.motivo = "aplicado";
    }
    return d;
}

// ---------------------------------------------------------------------------
// 6. ESTADO POR TENTATIVA — nada global, nada pegajoso.
// ---------------------------------------------------------------------------
struct Dedup {
    std::set<std::string> visto;
    size_t limite = 1024;  // teto por tentativa: nunca um set global ilimitado

    void clear() { visto.clear(); }

    bool primeira(const std::string & chave) {
        if (visto.size() >= limite) {
            visto.clear();   // rolagem: mantem o conjunto LIMITADO
        }
        return visto.insert(chave).second;
    }
    size_t tamanho() const { return visto.size(); }
};

struct State {
    Request request;
    Dedup dedup;
    uint64_t run = 0;        // ordem do grafo NESTE contexto
    uint64_t attempt = 0;    // n-esima tentativa de lab NESTE contexto
    int alvos = 0;
    int despachados = 0;
    int recusados = 0;
    bool preflight_aplicado = false;
    bool recusa = false;
    bool complete_sincronizado = false;
    const char * motivo = "-";

    // Chamado na ENTRADA de cada grafo: identidade nova, contadores zerados.
    void begin_graph() {
        dedup.clear();
        ++run;
        alvos = 0;
        despachados = 0;
        recusados = 0;
        preflight_aplicado = false;
        recusa = false;
        complete_sincronizado = false;
        motivo = "-";
        request = Request();
    }
    void begin_request(const Request & r) {
        request = r;
        if (r.tem_pedido()) {
            ++attempt;
        }
    }
};

}  // namespace sig_lab_mm
