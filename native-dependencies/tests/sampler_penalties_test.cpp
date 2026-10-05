// TESTE REAL DO SAMPLER DE PENALIDADES — rodada 10, §3.2 do parecer.
//
// Vinculado a libllama.a (a MESMA implementacao que o app e o harness usam),
// chamando llama_sampler_init_penalties / _apply / _accept / _reset direto,
// com array sintetico de llama_token_data. Nenhuma copia de formula: se o
// fork mudar a transformacao, este teste falha.
//
// Cobertos: §3.2 a (repeat=1.0 preserva), b (1.05 com historico vazio nao
// altera), c (aceite: positivo divide / negativo multiplica), d (token
// ausente intacto), e (reset limpa), f (janela finita: aceitar 1x vs 2x por
// passo muda a expulsao — e NAO muda a escala, porque freq=present=0).
//
// Compilado NDK/clang para aarch64, EXECUTADO NO APARELHO (nao no host).
#include "llama.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

static int g_pass = 0, g_fail = 0;
static void check(bool cond, const char* desc) {
    if (cond) { g_pass++; printf("  ok   %s\n", desc); }
    else      { g_fail++; printf("  FALHA %s\n", desc); }
}
// igualdade com tolerancia relativa (float: 1e-5 rel basta aqui)
static bool quase(float a, float b, float rel = 1e-5f) {
    float d = fabsf(a - b);
    return d <= rel * fmaxf(1.0f, fmaxf(fabsf(a), fabsf(b)));
}

// Monta o array sintetico e aplica o sampler REAL.
struct Cand { llama_token id; float logit; };
static std::vector<float> aplicar(llama_sampler* s, const std::vector<Cand>& c) {
    std::vector<llama_token_data> v(c.size());
    for (size_t i = 0; i < c.size(); i++)
        v[i] = llama_token_data{c[i].id, c[i].logit, 0.0f};
    llama_token_data_array arr{v.data(), v.size(), -1, false};
    llama_sampler_apply(s, &arr);
    std::vector<float> out(c.size());
    for (size_t i = 0; i < c.size(); i++) out[i] = v[i].logit;
    return out;
}

int main() {
    printf("=== TESTE REAL DE PENALIDADES (libllama.a) ===\n");

    const int NV = 16;            // vocabulario sintetico
    const int JANELA = 4;          // penalty_last_n pequeno para a janela (f)

    // §3.2(a): repeat=1.0/freq=0/present=0 => is_disabled => sampler VAZIO,
    // apply nao mexe em nada. (E o UNICO repeat que desativa.)
    {
        llama_sampler* s = llama_sampler_init_penalties(NV, 64, 1.0f, 0.0f, 0.0f);
        llama_sampler_accept(s, 1);  // aceite nao deve importar: sampler desativado
        auto r = aplicar(s, {{1, 2.0f}, {2, -3.0f}});
        check(quase(r[0], 2.0f) && quase(r[1], -3.0f),
              "(a) repeat=1.0/freq=0/present=0 preserva os logits");
        llama_sampler_free(s);
    }

    // §3.2(b): repeat=1.05 com historico VAZIO nao altera candidatos.
    {
        llama_sampler* s = llama_sampler_init_penalties(NV, 64, 1.05f, 0.0f, 0.0f);
        auto r = aplicar(s, {{1, 2.0f}, {2, -3.0f}, {3, 0.0f}});
        check(quase(r[0], 2.0f) && quase(r[1], -3.0f) && quase(r[2], 0.0f),
              "(b) repeat=1.05 com historico vazio: NENHUM candidato alterado");
        llama_sampler_free(s);
    }

    // §3.2(c)+(d): apos accept, token no historico transforma (positivo /=,
    // negativo *=) e token AUSENTE fica intacto. E count>1 com freq=present=0
    // NAO aplica a divisao 2x (o aviso do parecer): e' uma vez, por presenca.
    {
        llama_sampler* s = llama_sampler_init_penalties(NV, 64, 1.05f, 0.0f, 0.0f);
        llama_sampler_accept(s, 1);
        auto r = aplicar(s, {{1, 2.1f}, {1, -2.1f}, {2, 7.5f}, {1, 0.0f}});
        check(quase(r[0], 2.1f / 1.05f), "(c) token no historico, logit +2.1  => /1.05");
        check(quase(r[1], -2.1f * 1.05f), "(c) token no historico, logit -2.1 => *1.05");
        check(quase(r[2], 7.5f),           "(d) token AUSENTE do historico permanece intacto");
        check(quase(r[3], 0.0f),           "(c) logit zero (<=0) multiplicado: 0*1.05=0");

        // §3.2(f) parte 1: aceitar o MESMO token 2x nao muda a escala
        // (count=2, mas freq=0 e present=0 => sem subtracao; a divisao e' so
        // por presenca — NAO e' penalidade ao quadrado).
        llama_sampler_accept(s, 1);
        auto r2 = aplicar(s, {{1, 2.1f}});
        check(quase(r2[0], 2.1f / 1.05f),
              "(f) aceite 2x do mesmo token: escala IGUAL (nao e' /1.05^2)");
        llama_sampler_free(s);
    }

    // §3.2(e): reset limpa o historico; aplicacoes futuras nao penalizam.
    {
        llama_sampler* s = llama_sampler_init_penalties(NV, 64, 1.05f, 0.0f, 0.0f);
        llama_sampler_accept(s, 5);
        llama_sampler_reset(s);
        auto r = aplicar(s, {{1, 2.0f}, {5, 2.0f}});
        check(quase(r[0], 2.0f) && quase(r[1], 2.0f),
              "(e) reset remove o efeito do historico");
        llama_sampler_free(s);
    }

    // §3.2(f) parte 2: JANELA FINITA. O anel tem JANELA=4 slots. Aceitar 2x
    // por passo ocupa o dobro de slots -> o token antigo e' EXPULSO antes.
    // Aqui NAO ha quadruplicacao de escala (freq=present=0): so a expulsao muda.
    {
        // 1x por passo: A,B,C,D,E -> A sai so no 5o aceite (anel de 4).
        llama_sampler* s1 = llama_sampler_init_penalties(NV, JANELA, 1.05f, 0.0f, 0.0f);
        for (llama_token t : {1, 2, 3, 4, 5}) llama_sampler_accept(s1, t); // A saiu em E
        auto r1 = aplicar(s1, {{1, 2.1f}, {5, 2.1f}});
        check(quase(r1[0], 2.1f) && !quase(r1[1], 2.1f),
              "(f1) 1x/passo com anel=4: A (5o aceite) JA expulso, E presente");
        llama_sampler_free(s1);

        // 2x por passo (A,A,B,B,C,C): A esgota os 2 slots no 1o passo e e'
        // expulso no 3o passo — ANTES do 5o aceite da cadencia simples.
        llama_sampler* s2 = llama_sampler_init_penalties(NV, JANELA, 1.05f, 0.0f, 0.0f);
        for (llama_token t : {1, 1, 2, 2, 3, 3}) llama_sampler_accept(s2, t);
        auto r2 = aplicar(s2, {{1, 2.1f}, {3, 2.1f}});
        check(quase(r2[0], 2.1f) && !quase(r2[1], 2.1f),
              "(f2) 2x/passo com anel=4: A expulso mais CEDO (slots duplicados)");
        // contagem: no cadenciamento duplo a retencao de um token cai de
        // JANELA passos para JANELA/2 passos (2 slots por passo).
        llama_sampler_free(s2);
    }

    printf("\n=====================================\n  %d passaram, %d falharam\n"
           "=====================================\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
