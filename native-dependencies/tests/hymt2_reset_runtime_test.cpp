// ============================================================================
// TESTE NATIVO DE RUNTIME DO RESET DE PEDIDO — roda NO APARELHO, com o
// modelo REAL e o contexto REAL (llama.cpp da revisao vendored).
//
// Prova, pelo caminho real (nao por stub):
//   (a) `llama_memory_seq_pos_max/min` refletem o pedido anterior e voltam a
//       "vazio" (-1) depois do helper REAL `hymt2_begin_fresh_request`
//       (o mesmo header compilado em `llama_jni.cpp` no produto);
//   (b) o pedido seguinte comeca na posicao 0 e produz a MESMA saida gulosa
//       que um contexto fresco — sem herdar KV nem posicao;
//   (c) controle negativo: SEM reset, a saida do pedido seguinte difere da
//       de contexto fresco (mostra que o teste tem poder de deteccao).
//
// Backend CPU por desenho (n_gpu_layers=0): a contagem de posicao/KV nao
// depende de backend, e CPU e deterministico entre repeticoes.
//
// Uso no aparelho:
//   adb shell /data/local/tmp/hymt2_reset_rt /sdcard/Android/data/br.gov.sp.pcsp.launcher/files/hymt2_models/Hy-MT2-1.8B-q4_k_m.gguf
// rc=0 => todos os cheques passaram.
// ============================================================================
#include "hymt2_request_reset.h"   // MESMO helper usado pelo produto

#include "llama.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int g_pass = 0, g_fail = 0;
#define CHECA(nome, cond) do { \
    if (cond) { g_pass++; printf("  ok    %s\n", nome); } \
    else { g_fail++; printf("  FALHA %s\n", nome); } \
} while (0)

// Textos ASCII, sem template: o objetivo e' determinismo, nao qualidade.
static const char * TEXTO_A =
    "The maintenance team inspected the northern pipeline yesterday afternoon "
    "and replaced two valves near the river crossing.";
static const char * TEXTO_B =
    "Maria confirmed that the sensor was not calibrated last Tuesday, and the "
    "warehouse still holds twelve unopened boxes.";

static std::vector<llama_token> tokeniza(const llama_vocab * voc, const char * txt) {
    const int n_max = -llama_tokenize(voc, txt, (int) strlen(txt), nullptr, 0, false, true);
    std::vector<llama_token> toks(n_max > 0 ? n_max : 0);
    if (toks.empty()) return toks;
    const int rc = llama_tokenize(voc, txt, (int) strlen(txt), toks.data(),
                                  (int) toks.size(), false, true);
    toks.resize(rc > 0 ? rc : 0);
    return toks;
}

// prefill + n_novos passos gulosos; devolve os ids emitidos.
static std::vector<llama_token> roda(llama_context * ctx,
                                     const std::vector<llama_token> & prompt,
                                     const int n_novos, bool * ok) {
    std::vector<llama_token> emitidos;
    if (ok) *ok = false;
    llama_batch b = llama_batch_get_one(
        const_cast<llama_token *>(prompt.data()), (int) prompt.size());
    if (llama_decode(ctx, b) != 0) return emitidos;

    const llama_vocab * voc = llama_model_get_vocab(llama_get_model(ctx));
    const int n_vocab = llama_vocab_n_tokens(voc);
    for (int i = 0; i < n_novos; i++) {
        const float * logits = llama_get_logits_ith(ctx, -1);
        if (logits == nullptr) return emitidos;
        int best = 0;
        for (int v = 1; v < n_vocab; v++) {
            if (logits[v] > logits[best]) best = v;
        }
        emitidos.push_back((llama_token) best);
        llama_token t = (llama_token) best;
        llama_batch b2 = llama_batch_get_one(&t, 1);
        if (llama_decode(ctx, b2) != 0) return emitidos;
    }
    if (ok) *ok = true;
    return emitidos;
}

static int conta_diferencas(const std::vector<llama_token> & x,
                            const std::vector<llama_token> & y) {
    int d = 0;
    const size_t n = x.size() < y.size() ? x.size() : y.size();
    for (size_t i = 0; i < n; i++) {
        if (x[i] != y[i]) d++;
    }
    return d + (int) (x.size() > n ? x.size() - n : y.size() - n);
}

int main(int argc, char ** argv) {
    const char * modelo = (argc > 1)
        ? argv[1]
        : "/sdcard/Android/data/br.gov.sp.pcsp.launcher/files/hymt2_models/Hy-MT2-1.8B-q4_k_m.gguf";
    const int N_NOVOS = 8;

    printf("RUNTIME_RESET| modelo=%s\n", modelo);
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // CPU por desenho
    llama_model * model = llama_model_load_from_file(modelo, mparams);
    if (model == nullptr) {
        printf("RUNTIME_RESET| ERRO: llama_model_load_from_file falhou\n");
        return 2;
    }
    const llama_vocab * voc = llama_model_get_vocab(model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = 1024;
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;
    cparams.n_threads       = 4;
    cparams.n_threads_batch = 4;

    const std::vector<llama_token> toksA = tokeniza(voc, TEXTO_A);
    const std::vector<llama_token> toksB = tokeniza(voc, TEXTO_B);
    printf("RUNTIME_RESET| tokens: A=%d B=%d\n", (int) toksA.size(), (int) toksB.size());
    if (toksA.empty() || toksB.empty()) {
        printf("RUNTIME_RESET| ERRO: tokenizacao vazia\n");
        llama_model_free(model);
        return 2;
    }

    // ---------------- fase F: contexto fresco, pedido B ----------------
    std::vector<llama_token> idsB_fresh;
    {
        llama_context * ctxF = llama_init_from_model(model, cparams);
        if (ctxF == nullptr) { printf("RUNTIME_RESET| ERRO: ctx fresco falhou\n"); return 2; }
        bool ok = false;
        idsB_fresh = roda(ctxF, toksB, N_NOVOS, &ok);
        CHECA("fresco: B gerou os 8 tokens pedidos", ok && (int) idsB_fresh.size() == N_NOVOS);
        llama_free(ctxF);
    }

    // ---------------- fase R: A -> reset (helper real) -> B ----------------
    {
        printf("== fase R: A -> helper real -> B (sem recriar contexto) ==\n");
        llama_context * ctxR = llama_init_from_model(model, cparams);
        if (ctxR == nullptr) { printf("RUNTIME_RESET| ERRO: ctx R falhou\n"); return 2; }

        bool okA = false;
        const std::vector<llama_token> idsA = roda(ctxR, toksA, N_NOVOS, &okA);
        CHECA("pedido A completou", okA && (int) idsA.size() == N_NOVOS);

        llama_memory_t mem = llama_get_memory(ctxR);
        const llama_pos pos_max_apos_A = llama_memory_seq_pos_max(mem, 0);
        const llama_pos esperado_A = (llama_pos) ((int) toksA.size() + N_NOVOS - 1);
        printf("RUNTIME_RESET| pos_max apos A=%d (esperado %d)\n",
               (int) pos_max_apos_A, (int) esperado_A);
        CHECA("pos_max reflete o pedido A", pos_max_apos_A == esperado_A);

        std::string err;
        const bool ok_reset = hymt2_begin_fresh_request(ctxR, &err);
        CHECA("helper real retornou true", ok_reset);

        const llama_pos pos_max_apos_reset = llama_memory_seq_pos_max(mem, 0);
        const llama_pos pos_min_apos_reset = llama_memory_seq_pos_min(mem, 0);
        printf("RUNTIME_RESET| pos_max/min apos reset = %d/%d\n",
               (int) pos_max_apos_reset, (int) pos_min_apos_reset);
        CHECA("pos_max vazio (-1) apos reset", pos_max_apos_reset == -1);
        CHECA("pos_min vazio (-1) apos reset", pos_min_apos_reset == -1);

        bool okB = false;
        const std::vector<llama_token> idsB_after = roda(ctxR, toksB, N_NOVOS, &okB);
        CHECA("pedido B completou apos reset", okB && (int) idsB_after.size() == N_NOVOS);
        const int dif_reset = conta_diferencas(idsB_after, idsB_fresh);
        printf("RUNTIME_RESET| B apos reset != B fresco em %d tokens\n", dif_reset);
        CHECA("B apos reset IDENTICO ao B fresco (sem heranca)", dif_reset == 0);

        llama_free(ctxR);
    }

    // ---------------- fase N: controle negativo (sem reset) ----------------
    {
        printf("== fase N: A -> B SEM reset (controle negativo) ==\n");
        llama_context * ctxN = llama_init_from_model(model, cparams);
        if (ctxN == nullptr) { printf("RUNTIME_RESET| ERRO: ctx N falhou\n"); return 2; }
        bool ok1 = false, ok2 = false;
        roda(ctxN, toksA, N_NOVOS, &ok1);
        const std::vector<llama_token> idsB_noclear = roda(ctxN, toksB, N_NOVOS, &ok2);
        const int dif_sem_reset = conta_diferencas(idsB_noclear, idsB_fresh);
        printf("RUNTIME_RESET| DIF_SEM_RESET=%d tokens (informativo)\n", dif_sem_reset);
        if (dif_sem_reset > 0) {
            printf("RUNTIME_RESET| controle negativo OK: sem reset o pedido B herda estado\n");
        } else {
            printf("RUNTIME_RESET| AVISO: sem reset a saida coincidiu; teste perdeu poder\n");
        }
        llama_free(ctxN);
    }

    llama_model_free(model);
    llama_backend_free();

    printf("\n===== %d passaram, %d falharam =====\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
