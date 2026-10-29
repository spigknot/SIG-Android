// R55/MM-PLAN — testes HOST do helper REAL consumido pelo backend.
//
// O include abaixo aponta para o MESMO arquivo que ggml-vulkan.cpp inclui
// (app/src/main/cpp/whisper.cpp/ggml/src/ggml-vulkan/sig-whisper-lab-mm-plan.h).
// Nao existe copia da regra neste teste: qualquer divergencia de semantica
// aparece aqui.
//
// Build/execucao: tools/whisper/build_lab_mm_plan_host_test.sh
#include "sig-whisper-lab-mm-plan.h"

#include <cstdio>
#include <cstring>
#include <string>

namespace lab = sig_lab_mm;

static int g_fail = 0;
static int g_pass = 0;

#define CHECK(cond)                                                                 \
    do {                                                                            \
        if (cond) {                                                                 \
            ++g_pass;                                                               \
        } else {                                                                    \
            ++g_fail;                                                               \
            std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);              \
        }                                                                           \
    } while (0)

#define CHECK_EQ(a, b)                                                              \
    do {                                                                            \
        const long long va = (long long) (a);                                       \
        const long long vb = (long long) (b);                                       \
        if (va == vb) {                                                             \
            ++g_pass;                                                               \
        } else {                                                                    \
            ++g_fail;                                                               \
            std::printf("FAIL %s:%d  %s == %s  (%lld != %lld)\n",                    \
                        __FILE__, __LINE__, #a, #b, va, vb);                        \
        }                                                                           \
    } while (0)

#define CHECK_STR(a, b)                                                             \
    do {                                                                            \
        const char * va = (a);                                                      \
        const char * vb = (b);                                                      \
        if (va != nullptr && vb != nullptr && std::strcmp(va, vb) == 0) {            \
            ++g_pass;                                                               \
        } else {                                                                    \
            ++g_fail;                                                               \
            std::printf("FAIL %s:%d  %s == %s  (%s != %s)\n", __FILE__, __LINE__,    \
                        #a, #b, va ? va : "(null)", vb ? vb : "(null)");             \
        }                                                                           \
    } while (0)

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------
static lab::OpView alvo(uint64_t M = 1280, uint64_t N = 1500, uint64_t K = 1280) {
    lab::OpView op;
    op.vazio        = false;
    op.mul_mat      = true;
    op.compute      = true;
    op.fontes       = true;
    op.quantize_y   = false;
    op.src0_type    = lab::kTypeF16;
    op.src1_type    = lab::kTypeF32;
    op.src0_contig  = true;
    op.src1_contig  = true;
    op.ne00 = K; op.ne01 = M; op.ne02 = 1; op.ne03 = 1;
    op.ne10 = K; op.ne11 = N; op.ne12 = 1; op.ne13 = 1;
    op.d_ne0 = M; op.d_ne1 = N; op.d_ne2 = 1; op.d_ne3 = 1;
    op.prec = lab::kPrecDefault;
    return op;
}

static lab::VariantCaps caps_bom(uint32_t align_plain = 4, uint32_t align_aligned = 8) {
    lab::VariantCaps c;
    c.mask = true;
    c.ptr = true;
    c.ptr_aligned = true;
    c.initialized = true;
    c.initialized_aligned = true;
    c.failed = false;
    c.failed_aligned = false;
    c.compiled = false;          // lazy: NAO pode recusar
    c.compiled_aligned = false;
    c.align = align_plain;
    c.align_aligned = align_aligned;
    return c;
}

static lab::FamilyCaps caps_ok(lab::Family f = lab::Family::F32ACC) {
    lab::FamilyCaps caps;
    caps.family = f;
    for (int i = 0; i < 3; ++i) {
        caps.v[i] = caps_bom();
    }
    return caps;
}

static lab::Request pedido(const char * tile, const char * trace = nullptr) {
    return lab::make_request(tile, trace);
}

// ---------------------------------------------------------------------------
// 1. Pedido: leitura unica, copia propria, enum
// ---------------------------------------------------------------------------
static void teste_pedido() {
    {
        const lab::Request r = lab::make_request(nullptr, nullptr);
        CHECK(r.tile == lab::Tile::AUSENTE);
        CHECK(!r.trace);
        CHECK(!r.tile_present);
        CHECK(!r.tem_override());
        CHECK(!r.tem_pedido());
        CHECK_EQ(r.indice_forcado(), -1);
        CHECK_STR(r.nome(), "ausente");
    }
    {
        const lab::Request r = pedido("auto");
        CHECK(r.tile == lab::Tile::AUTO);
        CHECK(r.tile_present);
        CHECK(!r.tem_override());
        CHECK(r.tem_pedido());
        CHECK_STR(r.nome(), "auto");
    }
    {
        const lab::Request s = pedido("s");
        const lab::Request m = pedido("m");
        const lab::Request l = pedido("l");
        CHECK(s.tile == lab::Tile::S && s.indice_forcado() == 0 && s.tem_override());
        CHECK(m.tile == lab::Tile::M && m.indice_forcado() == 1 && m.tem_override());
        CHECK(l.tile == lab::Tile::L && l.indice_forcado() == 2 && l.tem_override());
        CHECK_STR(s.nome(), "s");
        CHECK_STR(m.nome(), "m");
        CHECK_STR(l.nome(), "l");
    }
    {
        // desconhecido/vazio/maiuscula/espaco sao INVALIDO (pedido presente)
        const lab::Request a = pedido("S");
        const lab::Request b = pedido("");
        const lab::Request c = pedido("auto ");
        const lab::Request d = pedido("2");
        CHECK(a.tile == lab::Tile::INVALIDO);
        CHECK(b.tile == lab::Tile::INVALIDO);
        CHECK(c.tile == lab::Tile::INVALIDO);
        CHECK(d.tile == lab::Tile::INVALIDO);
        CHECK(b.tile_present);
        CHECK_STR(a.nome(), "invalido");
    }
    {
        // trace: presenca (qualquer valor), independente do tile
        const lab::Request r = lab::make_request("s", "1");
        CHECK(r.trace);
        CHECK(r.trace_present);
        CHECK_STR(r.trace_text, "1");
        const lab::Request r2 = lab::make_request("s", "");
        CHECK(r2.trace);   // "presente" e' o criterio historico
    }
    {
        // copia propria: mudar o buffer de origem NAO muda o pedido
        char buf[8];
        std::strcpy(buf, "s");
        lab::Request r = lab::make_request(buf, nullptr);
        buf[0] = 'l';
        CHECK(r.tile == lab::Tile::S);
        CHECK_STR(r.tile_text, "s");
        // texto longo e' truncado sem estourar
        char grande[64];
        std::memset(grande, 'x', sizeof grande - 1);
        grande[sizeof grande - 1] = '\0';
        const lab::Request t = lab::make_request(grande, grande);
        CHECK(t.tile == lab::Tile::INVALIDO);
        CHECK(std::strlen(t.tile_text) == sizeof t.tile_text - 1);
        CHECK(std::strlen(t.trace_text) == sizeof t.trace_text - 1);
    }
}

// ---------------------------------------------------------------------------
// 2. Escopo: perfil real (tipo/contiguidade/batch/precisao/K/M/N)
// ---------------------------------------------------------------------------
static void teste_escopo() {
    CHECK(lab::em_escopo(alvo(), lab::Family::F32ACC));
    CHECK(lab::em_escopo(alvo(1280, 1500, 5120), lab::Family::F32ACC));
    CHECK(lab::em_escopo(alvo(5120, 1500, 1280), lab::Family::F32ACC));

    { lab::OpView o = alvo(); o.vazio = true;               CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "vazio"); }
    { lab::OpView o = alvo(); o.mul_mat = false;            CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "op"); }
    { lab::OpView o = alvo(); o.compute = false;            CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "nao_compute"); }
    { lab::OpView o = alvo(); o.fontes = false;             CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "sem_fontes"); }
    { lab::OpView o = alvo(); o.src0_type = lab::kTypeF32;  CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "tipo"); }
    { lab::OpView o = alvo(); o.src1_type = lab::kTypeF16;  CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "tipo"); }
    { lab::OpView o = alvo(); o.src0_contig = false;        CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "contiguidade"); }
    { lab::OpView o = alvo(); o.src1_contig = false;        CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "contiguidade"); }
    { lab::OpView o = alvo(); o.quantize_y = true;          CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "quantizado_y"); }
    { lab::OpView o = alvo(); o.ne02 = 2;                   CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "batch"); }
    { lab::OpView o = alvo(); o.ne13 = 2;                   CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "batch"); }
    { lab::OpView o = alvo(); o.d_ne3 = 2;                  CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "batch"); }
    { lab::OpView o = alvo(); o.prec = 1;                   CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "precisao"); }
    { lab::OpView o = alvo(); o.ne10 = 2560;                CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "shape"); }   // K nao casa com src0
    { lab::OpView o = alvo(1280, 1500, 2560);               CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "shape"); }   // coerente mas fora do perfil
    { lab::OpView o = alvo(1000, 1500, 1280);               CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "shape"); }
    { lab::OpView o = alvo(1280, 1000, 1280);               CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "shape"); }
    { lab::OpView o = alvo(); o.d_ne0 = 1000;               CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "shape"); }
    { lab::OpView o = alvo(); o.d_ne1 = 1000;               CHECK_STR(lab::fora_de_escopo_motivo(o, lab::Family::F32ACC), "shape"); }
}

// ---------------------------------------------------------------------------
// 3. Familia: mascara + ponteiro + initialized + failed; compiled lazy
// ---------------------------------------------------------------------------
static void teste_familia() {
    {
        const lab::FamilyCaps c = caps_ok();
        CHECK(c.disponivel(0, true));
        CHECK(c.disponivel(0, false));
        CHECK(c.disponivel(2, true));
        CHECK(c.indisponivel_motivo(0, true) == nullptr);
        CHECK_STR(lab::familia_nome(c.family), "f32acc");
    }
    {
        lab::FamilyCaps c = caps_ok();
        c.v[2].mask = false;
        CHECK(!c.disponivel(2, true));
        CHECK_STR(c.indisponivel_motivo(2, true), "mascara");
        CHECK(c.disponivel(1, true));   // a outra variante segue valida
    }
    {
        lab::FamilyCaps c = caps_ok();
        c.v[0].ptr = false;
        CHECK_STR(c.indisponivel_motivo(0, true), "ponteiro");
        CHECK_STR(c.indisponivel_motivo(0, false), "ponteiro");
    }
    {
        lab::FamilyCaps c = caps_ok();
        c.v[1].initialized = false;
        CHECK_STR(c.indisponivel_motivo(1, true), "nao_inicializada");
    }
    {
        lab::FamilyCaps c = caps_ok();
        c.v[1].failed = true;
        CHECK_STR(c.indisponivel_motivo(1, true), "falhou");
    }
    {
        // aligned ausente: recusa o caminho alinhado, aceita o nao-alinhado
        lab::FamilyCaps c = caps_ok();
        c.v[0].ptr_aligned = false;
        CHECK(!c.disponivel(0, true));
        CHECK_STR(c.indisponivel_motivo(0, true), "ponteiro_aligned");
        CHECK(c.disponivel(0, false));
    }
    {
        lab::FamilyCaps c = caps_ok();
        c.v[0].initialized_aligned = false;
        CHECK_STR(c.indisponivel_motivo(0, true), "aligned_nao_inicializada");
        c.v[0].initialized_aligned = true;
        c.v[0].failed_aligned = true;
        CHECK_STR(c.indisponivel_motivo(0, true), "aligned_falhou");
    }
    {
        lab::FamilyCaps c = caps_ok();
        c.v[0].align_aligned = 0;
        CHECK_STR(c.indisponivel_motivo(0, true), "align_zero");
        c.v[0].align_aligned = 8;
        c.v[0].align = 0;
        CHECK_STR(c.indisponivel_motivo(0, false), "align_zero");
    }
    {
        // compiled=false NAO recusa (compilacao preguicosa e' valida)
        lab::FamilyCaps c = caps_ok();
        CHECK(!c.v[0].compiled);
        CHECK(c.disponivel(0, true));
    }
    {
        lab::FamilyCaps vazia;   // familia ausente
        CHECK(vazia.family == lab::Family::NENHUMA);
        CHECK(!vazia.disponivel(0, true));
        CHECK_STR(vazia.indisponivel_motivo(0, true), "mascara");
    }
}

// ---------------------------------------------------------------------------
// 4. Plano por no': aplicado/recusado/fora de escopo + align coerente
// ---------------------------------------------------------------------------
static void teste_plano() {
    const lab::FamilyCaps caps = caps_ok();

    {
        const lab::OpPlan p = lab::plan_op(pedido("s"), alvo(), 2, caps);
        CHECK(p.status == lab::Status::ALVO_OK);
        CHECK(p.aplicavel);
        CHECK(p.forcado);
        CHECK_EQ(p.efetiva_idx, 0);
        CHECK_EQ(p.auto_idx, 2);
        CHECK(p.family == lab::Family::F32ACC);
        CHECK_STR(p.motivo, "aplicado");
        CHECK_STR(p.efetiva_nome(), "s");
    }
    { CHECK_EQ(lab::plan_op(pedido("m"), alvo(), 0, caps).efetiva_idx, 1); }
    { CHECK_EQ(lab::plan_op(pedido("l"), alvo(), 0, caps).efetiva_idx, 2); }
    {
        const lab::OpPlan p = lab::plan_op(pedido("auto"), alvo(), 1, caps);
        CHECK(p.status == lab::Status::ALVO_AUTO);
        CHECK(p.aplicavel);
        CHECK(!p.forcado);
        CHECK_EQ(p.efetiva_idx, 1);
        CHECK_STR(p.motivo, "auto");
    }
    {
        const lab::OpPlan p = lab::plan_op(pedido(nullptr), alvo(), 1, caps);
        CHECK(p.status == lab::Status::FORA_DE_ESCOPO);
        CHECK(!p.aplicavel);
        CHECK_EQ(p.efetiva_idx, 1);
        CHECK_STR(p.motivo, "pedido_ausente");
    }
    {
        lab::OpView o = alvo();
        o.prec = 2;
        const lab::OpPlan p = lab::plan_op(pedido("s"), o, 2, caps);
        CHECK(p.status == lab::Status::FORA_DE_ESCOPO);
        CHECK(!p.aplicavel);
        CHECK_STR(p.motivo, "precisao");
        CHECK_EQ(p.efetiva_idx, 2);   // segue AUTO
    }
    {
        const lab::OpPlan p = lab::plan_op(pedido("x"), alvo(), 1, caps);
        CHECK(p.status == lab::Status::PEDIDO_INVALIDO);
        CHECK(!p.aplicavel);
        CHECK_STR(p.motivo, "pedido_invalido");
    }
    {
        lab::FamilyCaps c = caps_ok();
        c.v[0].mask = false;
        const lab::OpPlan p = lab::plan_op(pedido("s"), alvo(), 1, c);
        CHECK(p.status == lab::Status::ALVO_RECUSADO);
        CHECK(!p.aplicavel);
        CHECK(!p.forcado || p.status == lab::Status::ALVO_RECUSADO);
        CHECK_STR(p.motivo, "mascara");
    }
    {
        lab::FamilyCaps c = caps_ok();
        c.v[2].failed = true;
        const lab::OpPlan p = lab::plan_op(pedido("l"), alvo(), 0, c);
        CHECK(p.status == lab::Status::ALVO_RECUSADO);
        CHECK_STR(p.motivo, "falhou");
    }
    {
        // alinhamento vem da variante EFETIVA, nao da variante do auto
        lab::FamilyCaps c = caps_ok();
        c.v[0].align_aligned = 8;    // s
        c.v[1].align_aligned = 64;   // m (auto)
        c.v[2].align_aligned = 128;  // l
        const lab::OpPlan p = lab::plan_op(pedido("s"), alvo(), 1, c);
        CHECK_EQ(p.efetiva_idx, 0);
        CHECK_EQ(p.align, 8);
        CHECK(p.aligned);            // 1280 % 8 == 0
        CHECK_EQ(p.kpad, 1280);

        const lab::OpPlan pa = lab::plan_op(pedido("auto"), alvo(), 1, c);
        CHECK_EQ(pa.align, 64);
        CHECK(pa.aligned);           // 1280 % 64 == 0
    }
    {
        // K nao multiplo do align -> nao alinhado, kpad arredondado
        lab::FamilyCaps c = caps_ok();
        c.v[0].align_aligned = 64;
        c.v[0].align = 64;
        lab::OpView o = alvo(1280, 1500, 1500);   // K fora do perfil, mas exercita a aritmetica
        o.ne10 = 1500; o.ne00 = 1500;
        const lab::OpPlan p = lab::plan_op(pedido("s"), o, 0, c);
        // fora de escopo por shape; align segue o do auto (mesma variante)
        CHECK(p.status == lab::Status::FORA_DE_ESCOPO);
        CHECK(!p.aligned);
        CHECK_EQ(p.align, 64);
        CHECK_EQ(p.kpad, 1536);
    }
    {
        // K multiplo + M/N pequenos: aligned exige m>8 e n>8
        lab::FamilyCaps c = caps_ok();
        c.v[0].align_aligned = 4;
        lab::OpView o = alvo(1280, 1500, 1280);
        o.ne01 = 8; o.d_ne0 = 8;
        const lab::OpPlan p = lab::plan_op(pedido("s"), o, 0, c);
        CHECK(!p.aligned);
        CHECK_EQ(p.kpad, 1280);
    }
    {
        // so a variante nao-alinhada existe: aplica e reporta align real.
        // R56/H02: plan_op e decide_graph usam a MESMA regra (variante_motivo);
        // a alternativa plain e' legal e nao pode ser recusada no preflight.
        lab::FamilyCaps c = caps_ok();
        c.v[0].ptr_aligned = false;
        c.v[0].align = 4;
        const lab::OpPlan p = lab::plan_op(pedido("s"), alvo(), 0, c);
        CHECK(p.status == lab::Status::ALVO_OK);
        CHECK(!p.aligned);
        CHECK_EQ(p.align, 4);
        CHECK(lab::em_escopo(alvo(), lab::Family::F32ACC));
        CHECK(lab::variante_motivo(c, 0) == nullptr);
    }
    {
        // R56/H01: DEFAULT com familia F16acc NAO e' aprovado como celula S/M.
        // Conserva AUTO (nao aplicacao), sem FAILED.
        const lab::FamilyCaps c16 = caps_ok(lab::Family::F16ACC);
        const lab::OpPlan p16 = lab::plan_op(pedido("s"), alvo(), 0, c16);
        CHECK(p16.status == lab::Status::FORA_DE_ESCOPO);
        CHECK_STR(p16.motivo, "familia_f16acc");
        CHECK(!p16.aplicavel);
        CHECK(p16.family == lab::Family::F16ACC);
        CHECK_STR(lab::familia_nome(p16.family), "f16acc");
    }
    {
        // R56/H01: `--prec f32` chega como GGML_PREC_F32 (10) e o probe validado
        // usa exatamente esse valor: precisa ser ALVO_OK com familia F32acc.
        lab::OpView o = alvo();
        o.prec = lab::kPrecF32;
        const lab::OpPlan p = lab::plan_op(pedido("s"), o, 0, caps);
        CHECK(p.status == lab::Status::ALVO_OK);
        CHECK(p.aplicavel);
        CHECK_EQ(p.prec_efetiva, lab::kPrecF32);
        CHECK_STR(p.motivo, "aplicado");
        // DEFAULT continua aceito (a familia efetiva e' F32acc, nao o rotulo).
        const lab::OpPlan pd = lab::plan_op(pedido("s"), alvo(), 0, caps);
        CHECK(pd.status == lab::Status::ALVO_OK);
        CHECK_EQ(pd.prec_efetiva, lab::kPrecDefault);
        // F16 explicito (1) segue fora de escopo, mesmo com familia F32acc.
        lab::OpView o16 = alvo();
        o16.prec = 1;
        const lab::OpPlan p16 = lab::plan_op(pedido("s"), o16, 0, caps);
        CHECK(p16.status == lab::Status::FORA_DE_ESCOPO);
        CHECK_STR(p16.motivo, "precisao");
    }
    {
        // precisao real do no' e' reportada (0 = GGML_PREC_DEFAULT)
        const lab::OpPlan p = lab::plan_op(pedido("s"), alvo(), 0, caps);
        CHECK_EQ(p.prec_efetiva, lab::kPrecDefault);
        lab::OpView o = alvo();
        o.prec = 2;
        const lab::OpPlan p2 = lab::plan_op(pedido("s"), o, 0, caps);
        CHECK_EQ(p2.prec_efetiva, 2);
        CHECK_STR(p2.motivo, "precisao");
    }
    {
        // pedido "s" fora do perfil NAO recusa o grafo (so nao aplica)
        lab::OpView o = alvo();
        o.src0_type = lab::kTypeF32;
        const lab::OpPlan p = lab::plan_op(pedido("s"), o, 0, caps);
        CHECK(p.status == lab::Status::FORA_DE_ESCOPO);
        CHECK_STR(p.motivo, "tipo");
    }
}

// ---------------------------------------------------------------------------
// 5. Decisao de grafo (preflight)
// ---------------------------------------------------------------------------
struct ProviderCtx {
    int chamadas = 0;
    lab::FamilyCaps caps;
    const char * motivo_forcado = nullptr;   // quando != nullptr, devolve tudo indisponivel
};

static lab::FamilyCaps provider(void * user, const lab::OpView &) {
    ProviderCtx * c = (ProviderCtx *) user;
    c->chamadas++;
    if (c->motivo_forcado != nullptr) {
        lab::FamilyCaps vazia;
        return vazia;
    }
    return c->caps;
}

static void teste_grafo() {
    ProviderCtx pc;
    pc.caps = caps_ok();

    lab::CapsProvider prov;
    prov.fn = &provider;
    prov.user = &pc;

    const lab::OpView misto[4] = {
        alvo(),                                        // alvo
        alvo(1000, 1500, 1000),                        // mul_mat fora do perfil (shape)
        alvo(),                                        // alvo
        alvo(),                                        // alvo
    };
    {
        // grafo alvo
        const lab::OpView ops[1] = { alvo() };
        const lab::GraphDecision d = lab::decide_graph(pedido("s"), ops, 1, prov);
        CHECK(d.status == lab::Status::ALVO_OK);
        CHECK_EQ(d.alvos, 1);
        CHECK_EQ(d.fora, 0);
        CHECK_EQ(d.recusados, 0);
        CHECK_EQ(pc.chamadas, 1);
    }
    {
        // grafo SEM alvo (so reshape/conv e mul_mat de outro perfil)
        lab::OpView conv = alvo();
        conv.mul_mat = false;
        conv.src1_type = lab::kTypeF16;
        const lab::OpView ops[3] = { conv, alvo(1000, 1500, 1000), alvo() };
        lab::OpView fora1 = ops[1];
        lab::OpView ops2[2] = { conv, fora1 };
        const lab::GraphDecision d = lab::decide_graph(pedido("s"), ops2, 2, prov);
        CHECK(d.status == lab::Status::FORA_DE_ESCOPO);
        CHECK_STR(d.motivo, "sem_alvo");
        CHECK_EQ(d.alvos, 0);
        CHECK_EQ(d.fora, 1);
        // NAO e' FAILED: segue auto com candidato nao aplicado
        CHECK(d.status != lab::Status::ALVO_RECUSADO);
        CHECK_EQ(pc.chamadas, 1);   // sem alvo, nem consulta a familia
        (void) ops;
    }
    {
        // grafo misto: aplica so' ao alvo
        const lab::GraphDecision d = lab::decide_graph(pedido("m"), misto, 4, prov);
        CHECK(d.status == lab::Status::ALVO_OK);
        CHECK_EQ(d.alvos, 3);
        CHECK_EQ(d.fora, 1);
        CHECK_EQ(pc.chamadas, 1 + 3);
    }
    {
        // pedido invalido: FAILED mesmo sem alvo, com trace desligado
        const lab::OpView ops[2] = { alvo(1000, 1500, 1000), alvo(1000, 1500, 1000) };
        const lab::GraphDecision d = lab::decide_graph(pedido("z"), ops, 2, prov);
        CHECK(d.status == lab::Status::PEDIDO_INVALIDO);
        CHECK_STR(d.motivo, "pedido_invalido");
        CHECK_EQ(d.alvos, 0);
    }
    {
        // alvo com variante indisponivel: recusa o grafo (sem M substituto)
        ProviderCtx ruim;
        ruim.caps = caps_ok();
        ruim.caps.v[1].mask = false;
        lab::CapsProvider p2;
        p2.fn = &provider;
        p2.user = &ruim;
        const lab::OpView ops[1] = { alvo() };
        const lab::GraphDecision d = lab::decide_graph(pedido("m"), ops, 1, p2);
        CHECK(d.status == lab::Status::ALVO_RECUSADO);
        CHECK_STR(d.motivo, "indisponivel");
        CHECK_STR(d.detalhe, "mascara");
        CHECK_EQ(d.recusados, 1);
    }
    {
        // pedido auto: alvo ok, nenhuma consulta de disponibilidade
        const lab::OpView ops[1] = { alvo() };
        ProviderCtx pc2;
        pc2.caps = caps_ok();
        lab::CapsProvider p3;
        p3.fn = &provider;
        p3.user = &pc2;
        const lab::GraphDecision d = lab::decide_graph(pedido("auto"), ops, 1, p3);
        CHECK(d.status == lab::Status::ALVO_AUTO);
        CHECK_EQ(d.alvos, 1);
        // R56: a familia e' resolvida tambem no pedido "auto" (o escopo depende
        // dela); nao ha' override, entao nada e' aplicado.
        CHECK_EQ(pc2.chamadas, 1);
    }
    {
        // ausente: sem pedido, nenhuma consulta
        ProviderCtx pc3;
        pc3.caps = caps_ok();
        lab::CapsProvider p4;
        p4.fn = &provider;
        p4.user = &pc3;
        const lab::OpView ops[1] = { alvo() };
        const lab::GraphDecision d = lab::decide_graph(pedido(nullptr), ops, 1, p4);
        CHECK(d.status == lab::Status::FORA_DE_ESCOPO);
        CHECK_STR(d.motivo, "pedido_ausente");
        CHECK_EQ(pc3.chamadas, 0);
    }
    {
        // provider nulo nao explode e vira RECUSA (fail-closed): familia
        // desconhecida nao libera AUTO quando ha' override.
        lab::CapsProvider nenhum;
        const lab::OpView ops[1] = { alvo() };
        const lab::GraphDecision d = lab::decide_graph(pedido("s"), ops, 1, nenhum);
        CHECK(d.status == lab::Status::ALVO_RECUSADO);
        CHECK_STR(d.detalhe, "familia_ausente");
        CHECK_EQ(d.recusados, 1);
    }
}

// ---------------------------------------------------------------------------
// 5b. Acordo entre as fases: o MESMO pedido, o MESMO no' e as MESMAS capacidades
//     tem de produzir a mesma variante/align/kpad no preflight (decide_graph) e
//     no despacho (plan_op). Se as duas fases divergirem, o trace mente.
// ---------------------------------------------------------------------------
struct AcordoCtx {
    lab::FamilyCaps caps;
};

static lab::FamilyCaps acordo_provider(void * user, const lab::OpView &) {
    return ((AcordoCtx *) user)->caps;
}

static void teste_acordo_fases() {
    lab::FamilyCaps caps = caps_ok();
    caps.v[0].align_aligned = 8;     // s
    caps.v[0].align = 4;
    caps.v[1].align_aligned = 16;    // m
    caps.v[1].align = 8;
    caps.v[2].align_aligned = 64;    // l
    caps.v[2].align = 32;

    AcordoCtx ac;
    ac.caps = caps;
    lab::CapsProvider prov;
    prov.fn = &acordo_provider;
    prov.user = &ac;

    const lab::OpView geoms[3] = {
        alvo(1280, 1500, 1280),
        alvo(1280, 1500, 5120),
        alvo(5120, 1500, 1280),
    };
    const char * pedidos[4] = { "auto", "s", "m", "l" };

    for (int g = 0; g < 3; ++g) {
        for (int p = 0; p < 4; ++p) {
            const lab::Request req = pedido(pedidos[p]);
            const lab::GraphDecision d = lab::decide_graph(req, &geoms[g], 1, prov);
            CHECK(d.status == (req.tile == lab::Tile::AUTO ? lab::Status::ALVO_AUTO : lab::Status::ALVO_OK));
            CHECK_EQ(d.alvos, 1);
            CHECK_EQ(d.recusados, 0);

            // a mesma decisao por no', com o auto_idx que o seletor real devolveria
            const int auto_idx = 2;   // mmp->l por wg_denoms no perfil do device
            const lab::OpPlan plano = lab::plan_op(req, geoms[g], auto_idx, caps);
            CHECK(plano.aplicavel);
            CHECK_EQ(plano.auto_idx, auto_idx);
            if (req.tem_override()) {
                CHECK_EQ(plano.efetiva_idx, req.indice_forcado());
                CHECK(plano.forcado);
            } else {
                CHECK_EQ(plano.efetiva_idx, auto_idx);
                CHECK(!plano.forcado);
            }
            // align/kpad coerentes com a variante EFETIVA e com o K real
            const lab::VariantCaps & c = caps.v[plano.efetiva_idx];
            CHECK_EQ(plano.align, plano.aligned ? c.align_aligned : c.align);
            CHECK_EQ(plano.kpad, geoms[g].ne10);      // K multiplo do align em todos os casos
            CHECK(plano.aligned);
        }
    }

    // negativo: variante pedida indisponivel -> preflight recusa E despacho recusa
    lab::FamilyCaps ruim = caps;
    ruim.v[2].failed = true;
    AcordoCtx ac2;
    ac2.caps = ruim;
    lab::CapsProvider prov2;
    prov2.fn = &acordo_provider;
    prov2.user = &ac2;
    const lab::GraphDecision d2 = lab::decide_graph(pedido("l"), &geoms[0], 1, prov2);
    CHECK(d2.status == lab::Status::ALVO_RECUSADO);
    const lab::OpPlan p2 = lab::plan_op(pedido("l"), geoms[0], 2, ruim);
    CHECK(p2.status == lab::Status::ALVO_RECUSADO);
    CHECK(!p2.aplicavel);
    CHECK_STR(p2.motivo, "falhou");
    // e a recuperacao no MESMO contexto, com a familia sadia, volta a aplicar
    const lab::OpPlan p3 = lab::plan_op(pedido("l"), geoms[0], 2, caps);
    CHECK(p3.status == lab::Status::ALVO_OK);
    CHECK(p3.aplicavel);
}

// ---------------------------------------------------------------------------
// 6. Estado por tentativa: reset, recuperacao, instancia reutilizada
// ---------------------------------------------------------------------------
static void teste_estado() {
    lab::State st;
    CHECK_EQ(st.run, 0);
    CHECK_EQ(st.attempt, 0);

    // grafo 1: pedido invalido
    st.begin_graph();
    st.begin_request(pedido("zz"));
    st.recusa = true;
    st.recusados = 3;
    st.motivo = "pedido_invalido";
    st.complete_sincronizado = true;
    CHECK_EQ(st.run, 1);
    CHECK_EQ(st.attempt, 1);
    CHECK(st.recusa);

    // grafo 2: MESMA instancia, pedido valido -> erro NAO e' pegajoso e a marca
    // de conclusao sincronizada tambem e' por tentativa
    st.begin_graph();
    CHECK(!st.complete_sincronizado);
    st.begin_request(pedido("s", "1"));
    CHECK_EQ(st.run, 2);
    CHECK_EQ(st.attempt, 2);
    CHECK(!st.recusa);
    CHECK_EQ(st.recusados, 0);
    CHECK_STR(st.motivo, "-");
    CHECK(st.request.trace);
    CHECK(st.request.tile == lab::Tile::S);

    // grafo 3: sem pedido -> attempt NAO incrementa
    st.begin_graph();
    st.begin_request(lab::make_request(nullptr, nullptr));
    CHECK_EQ(st.run, 3);
    CHECK_EQ(st.attempt, 2);
    CHECK(!st.request.tem_pedido());
    CHECK(st.request.tile == lab::Tile::AUSENTE);

    // grafo 4: recuperacao com grafo fora de escopo (segue auto, sem FAILED)
    st.begin_graph();
    st.begin_request(pedido("l"));
    lab::OpView conv = alvo();
    conv.mul_mat = false;
    const lab::OpView ops[1] = { conv };
    lab::CapsProvider prov;
    const lab::GraphDecision d = lab::decide_graph(st.request, ops, 1, prov);
    CHECK(d.status == lab::Status::FORA_DE_ESCOPO);
    CHECK_EQ(st.attempt, 3);
    CHECK(!st.recusa);
}

static void teste_dedup() {
    lab::Dedup d;
    CHECK(d.primeira("select|no=0"));
    CHECK(!d.primeira("select|no=0"));
    CHECK(d.primeira("select|no=1"));
    CHECK(d.primeira("dispatch|no=0"));   // estagio diferente nao e' suprimido
    CHECK_EQ((long long) d.tamanho(), 3);
    d.clear();
    CHECK_EQ((long long) d.tamanho(), 0);
    CHECK(d.primeira("select|no=0"));

    // conjunto LIMITADO: nunca cresce sem teto
    lab::Dedup lim;
    for (int i = 0; i < 1000; ++i) {
        lim.primeira("k" + std::to_string(i));
        CHECK(lim.tamanho() <= lim.limite);
    }
    CHECK(lim.tamanho() <= lim.limite);
}

int main() {
    teste_pedido();
    teste_escopo();
    teste_familia();
    teste_plano();
    teste_grafo();
    teste_acordo_fases();
    teste_estado();
    teste_dedup();

    std::printf("RESULT pass=%d fail=%d\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
