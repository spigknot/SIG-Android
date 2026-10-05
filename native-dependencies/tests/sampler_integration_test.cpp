// CONTROLE DE INTEGRACAO — §3.3 do parecer de 29/09 (rodada 10).
// Prova que llama_sampler_sample() ACEITA o token automaticamente (o caminho
// que o app usa), sem nenhuma chamada explicita de accept().
//
// Metodo (APIs publicas + modelo autorizado):
//   1. carrega o modelo do produto, contexto minimo
//   2. chain {penalties(1.05), greedy}  — greedy da repetibilidade
//   3. decodifica prompt curto, chama llama_sampler_sample() UMA vez
//      (dentro: apply -> select -> ACCEPT interna)
//   4. aplica a MESMA chain a um array sintetico contendo o token que acabou
//      de sair, com logit +2.0:
//        - se sample() aceitou: o token esta no historico => vira 2.0/1.05
//        - se NAO aceitou: fica 2.0 intacto
//   5. token nunca amostrado (controle negativo): fica intacto em qualquer
//      caso
//
// Compilado NDK clang aarch64; EXECUTADO NO APARELHO com o modelo
// Hy-MT2-1.8B-q4_k_m.gguf (sha c4bf1015...), n_ctx=512 — minimo valido.
#include "llama.h"

#include <cstdio>
#include <cstring>
#include <cmath>
#include <string>
#include <vector>

static bool quase(float a, float b, float rel = 1e-5f) {
    float d = fabsf(a - b);
    return d <= rel * fmaxf(1.0f, fmaxf(fabsf(a), fabsf(b)));
}

int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "uso: %s <modelo.gguf>\n", argv[0]); return 2; }
    const char* path = argv[1];
    printf("=== CONTROLE DE INTEGRACAO: sample() aceita automaticamente ===\n");

    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // CPU puro: sem GPU, o teste e' do caminho host
    auto* model = llama_load_model_from_file(path, mp);
    if (!model) { fprintf(stderr, "FALHA: nao carregou o modelo\n"); return 3; }
    const auto* vocab = llama_model_get_vocab(model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx    = 512;
    cp.n_batch  = 512;
    cp.n_threads = 4;
    auto* ctx = llama_init_from_model(model, cp);
    if (!ctx) { fprintf(stderr, "FALHA: nao criou o contexto\n"); return 3; }

    const int n_vocab = llama_vocab_n_tokens(vocab);

    // ---- 2. chain identica em estrutura a do app, com greedy para repetir ----
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    // ---- 3. prompt curto, add_special=false (como o app) ----
    const char* prompt = "The server";
    std::vector<llama_token> toks(64);
    int32_t n = llama_tokenize(vocab, prompt, (int32_t)strlen(prompt),
                               toks.data(), (int32_t)toks.size(), /*add_special=*/false, /*parse_special=*/true);
    if (n < 0) { fprintf(stderr, "FALHA: tokenize\n"); return 3; }
    toks.resize(n);

    llama_batch batch = llama_batch_get_one(toks.data(), (int32_t)toks.size());
    if (llama_decode(ctx, batch) != 0) { fprintf(stderr, "FALHA: decode\n"); return 3; }

    // chamada UNICA do app: aplica, seleciona, ACEITA internamente.
    // NENHUMA chamada explicita a llama_sampler_accept() neste programa.
    llama_token amostrado = llama_sampler_sample(smpl, ctx, -1);
    printf("amostrado = %d (accept explicito chamado: NENHUM)\n", (int)amostrado);

    // ---- 4. prova: array sintetico {token, +2.0} na MESMA chain ----
    auto aplicar = [&](llama_token id, float logit) -> float {
        llama_token_data d{ id, logit, 0.0f };
        llama_token_data_array arr{ &d, 1, -1, false };
        llama_sampler_apply(smpl, &arr);
        return d.logit;
    };

    const float apos_sample = aplicar(amostrado, 2.0f);
    const int  nunca = (amostrado + 1) % n_vocab;   // controle negativo
    const float nunca_afetado = aplicar(nunca, 2.0f);

    printf("token amostrado apos sample()  : %.6f  (2.0/1.05=%.6f)\n",
           apos_sample, 2.0f / 1.05f);
    printf("token nunca amostrado (controle): %.6f  (esperado 2.0)\n", nunca_afetado);

    // ---- 5. sem historico: chain nova, mesma aplicacao ----
    llama_sampler* limpa = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(limpa, llama_sampler_init_penalties(n_vocab, 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(limpa, llama_sampler_init_greedy());
    auto aplicar_limpa = [&](llama_token id, float logit) -> float {
        llama_token_data d{ id, logit, 0.0f };
        llama_token_data_array arr{ &d, 1, -1, false };
        llama_sampler_apply(limpa, &arr);
        return d.logit;
    };
    const float cadeia_nova = aplicar_limpa(amostrado, 2.0f);
    printf("mesmo token em chain NOVA      : %.6f  (esperado 2.0)\n", cadeia_nova);

    // ---- veredito ----
    bool houve_aceite = quase(apos_sample, 2.0f / 1.05f);
    bool controle_neg = quase(nunca_afetado, 2.0f);
    bool cadeia_igual = quase(cadeia_nova, 2.0f);
    printf("\nVEREDITO:\n");
    printf("  sample() ACEITOU o token automaticamente : %s\n", houve_aceite ? "PROVADO" : "NAO PROVADO");
    printf("  token ausente do historico intacto       : %s\n", controle_neg ? "OK" : "FALHOU");
    printf("  chain nova sem efeito (controle 2)       : %s\n", cadeia_igual ? "OK" : "FALHOU");

    llama_sampler_free(smpl);
    llama_sampler_free(limpa);
    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();

    bool ok = houve_aceite && controle_neg && cadeia_igual;
    printf("\nRESULTADO: %s\n", ok ? "PASS" : "FAIL");
    return ok ? 0 : 1;
}
