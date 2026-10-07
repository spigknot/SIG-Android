// reset_harness.cpp — RODADA 2, Bloco B.2/B.3 — reset A-B-A no MESMO processo.
// Prova: llama_memory_clear(ctx) limpa KV/metadata e o contexto pos-reset se
// comporta como fresco; mesma request apos reset == referencia (greedy, mesma seed).
//
// Uso: reset_harness -m modelo.gguf [-ngl 99] [--dev HTP0] [--ngena 5] [--ngenb 6]
// Saida: linhas JSON comeca com "RESULT_JSON:" (uma por bloco de checks) + humano.
//
// Compilado contra o pkg do build 5e03bdd (include/ + lib/). NAO altera o produto.

#include "llama.h"
#include <clocale>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>

struct top8 { std::vector<llama_token> ids; std::vector<float> vals; };

static void batch_set_tokens(llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos pos_0) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = pos_0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, n - 1, true);
}

static top8 get_top8(llama_context * ctx, const llama_vocab * vocab) {
    top8 t;
    const float * lg = llama_get_logits_ith(ctx, -1);
    const int nv = llama_vocab_n_tokens(vocab);
    std::vector<std::pair<float, llama_token>> all(nv);
    for (int i = 0; i < nv; ++i) all[i] = {lg[i], (llama_token)i};
    std::partial_sort(all.begin(), all.begin() + 8, all.end(),
                      [](auto & a, auto & b) { return a.first > b.first; });
    for (int i = 0; i < 8; ++i) { t.vals.push_back(all[i].first); t.ids.push_back(all[i].second); }
    return t;
}

static bool top8_ids_equal(const top8 & a, const top8 & b) { return a.ids == b.ids; }
static float top8_maxdiff(const top8 & a, const top8 & b) {
    if (a.vals.empty() || b.vals.empty()) return 1e9f;
    float m = 0; for (int i = 0; i < 8; ++i) m = std::max(m, std::fabs(a.vals[i] - b.vals[i]));
    return m;
}

int main(int argc, char ** argv) {
    std::setlocale(LC_NUMERIC, "C");
    std::string model_path, dev_name = "HTP0";
    int ngl = 99, n_gen = 6;
    std::string P1 = "Translate to Portuguese: Good morning, how are you?";
    std::string P2 = "Translate to Portuguese: The quick brown fox jumps over the lazy dog near the river bank.";

    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "-m") && i + 1 < argc) model_path = argv[++i];
        else if (!strcmp(argv[i], "-ngl") && i + 1 < argc) ngl = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--dev") && i + 1 < argc) dev_name = argv[++i];
        else if (!strcmp(argv[i], "--ngen") && i + 1 < argc) n_gen = atoi(argv[++i]);
    }
    if (model_path.empty()) { fprintf(stderr, "uso: reset_harness -m modelo.gguf\n"); return 1; }

    llama_backend_init();

    // device HTP0 explicito (mesmo que -dev do CLI)
    ggml_backend_dev_t htp = ggml_backend_dev_by_name(dev_name.c_str());
    if (!htp) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"dev %s nao encontrado\"}\n", dev_name.c_str()); return 2; }
    fprintf(stderr, "[reset] device: %s (%s)\n", ggml_backend_dev_name(htp), ggml_backend_dev_description(htp));

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = ngl;
    ggml_backend_dev_t devs[2] = { htp, nullptr };
    mp.devices = devs;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!model) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"load model\"}\n"); return 2; }
    const llama_vocab * vocab = llama_model_get_vocab(model);

    auto tok = [&](const std::string & s) {
        int n = -llama_tokenize(vocab, s.c_str(), s.size(), nullptr, 0, true, true);
        std::vector<llama_token> t(n);
        llama_tokenize(vocab, s.c_str(), s.size(), t.data(), t.size(), true, true);
        return t;
    };
    auto tk1 = tok(P1), tk2 = tok(P2);
    fprintf(stderr, "[reset] P1=%zu tokens | P2=%zu tokens\n", tk1.size(), tk2.size());

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 1024; cp.n_batch = 512; cp.no_perf = false;

    llama_context * A = llama_init_from_model(model, cp);
    llama_context * B = llama_init_from_model(model, cp);
    if (!A || !B) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"ctx init\"}\n"); return 2; }

    auto sA = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sA, llama_sampler_init_greedy());
    auto sB = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sB, llama_sampler_init_greedy());

    auto * bA = llama_batch_ext_init(A);
    auto * bB = llama_batch_ext_init(B);

    // run: prefill + n_gen greedy. Devolve stream; first = top8 pos-prefill.
    auto run = [&](llama_context * ctx, llama_sampler * smp, llama_batch_ext * batch,
                   const std::vector<llama_token> & prompt, top8 & first) {
        std::vector<llama_token> stream;
        batch_set_tokens(batch, prompt.data(), (int)prompt.size(), 0);
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            fprintf(stderr, "RESULT_JSON:{\"fatal\":\"process prefill\"}\n"); exit(2);
        }
        first = get_top8(ctx, vocab);
        int n_pos = (int)prompt.size();
        llama_token t = llama_sampler_sample(smp, ctx, -1); // accept interno
        for (int g = 0; g < n_gen; ++g) {
            stream.push_back(t);
            llama_token one = t;
            batch_set_tokens(batch, &one, 1, n_pos);
            if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
                fprintf(stderr, "RESULT_JSON:{\"fatal\":\"process gen\"}\n"); exit(2);
            }
            n_pos += 1;
            t = llama_sampler_sample(smp, ctx, -1);
        }
        return stream;
    };

    auto pos_max = [&](llama_context * ctx) { return llama_memory_seq_pos_max(llama_get_memory(ctx), 0); };

    // ===== 1) P1 em A (ref) e B (test) =====
    top8 a1, b1;
    auto stA1 = run(A, sA, bA, tk1, a1);
    auto stB1 = run(B, sB, bB, tk1, b1);
    auto jstream = [](const std::vector<llama_token> & s) {
        std::string r = "["; for (size_t i = 0; i < s.size(); ++i) { r += std::to_string((int)s[i]); if (i + 1 < s.size()) r += ","; } return r + "]";
    };
    printf("RESULT_JSON:{\"bloco\":\"P1_A_vs_B\",\"top8_ids_igual\":%s,\"top8_maxdiff\":%.6f,\"stream_A1\":%s,\"stream_B1\":%s}\n",
           top8_ids_equal(a1, b1) ? "true" : "false", top8_maxdiff(a1, b1),
           jstream(stA1).c_str(), jstream(stB1).c_str());
    fprintf(stderr, "[P1] top8 ids A == B: %s | maxdiff %.3e\n",
            top8_ids_equal(a1, b1) ? "SIM" : "NAO", top8_maxdiff(a1, b1));

    // ===== 2) RESET em B; pos_max antes/depois =====
    llama_pos pm_before = pos_max(B);
    llama_memory_clear(llama_get_memory(B), true);   // <- o reset sob teste
    llama_pos pm_after = pos_max(B);
    fprintf(stderr, "[RESET] B pos_max antes=%d depois=%d\n", (int)pm_before, (int)pm_after);

    // ===== 3) P2 em B (pos-reset) e em A (apos reset de A) =====
    top8 b2, a2;
    auto stB2 = run(B, sB, bB, tk2, b2);
    llama_memory_clear(llama_get_memory(A), true);
    auto stA2 = run(A, sA, bA, tk2, a2);

    bool streams_eq = (stB2 == stA2);
    printf("RESULT_JSON:{\"bloco\":\"P2_pos_reset_B_vs_A_reset\",\"pos_before\":%d,\"pos_after\":%d,\"top8_ids_igual\":%s,\"top8_maxdiff\":%.6f,\"streams_iguais\":%s}\n",
           (int)pm_before, (int)pm_after, top8_ids_equal(a2, b2) ? "true" : "false",
           top8_maxdiff(a2, b2), streams_eq ? "true" : "false");
    fprintf(stderr, "[P2] pos-reset B: pos_max %d->%d | top8==A: %s | streams iguais: %s\n",
            (int)pm_before, (int)pm_after, top8_ids_equal(a2, b2) ? "SIM" : "NAO", streams_eq ? "SIM" : "NAO");

    // ===== 4) reset de novo em B + MESMA P2 -> comparar com stB2 (idempotencia A-B-A) =====
    llama_memory_clear(llama_get_memory(B), true);
    top8 b3;
    auto stB3 = run(B, sB, bB, tk2, b3);
    printf("RESULT_JSON:{\"bloco\":\"ABA_P2_reset_P2\",\"stream_2==3\":%s,\"top8_2==3_ids\":%s,\"top8_maxdiff\":%.6f}\n",
           (stB2 == stB3) ? "true" : "false", top8_ids_equal(b2, b3) ? "true" : "false", top8_maxdiff(b2, b3));
    fprintf(stderr, "[A-B-A] P2/reset/P2 stream igual: %s\n", (stB2 == stB3) ? "SIM" : "NAO");

    printf("RESULT_JSON:{\"bloco\":\"fim\",\"ok\":true}\n");

    llama_batch_ext_free(bA); llama_batch_ext_free(bB);
    llama_sampler_free(sA); llama_sampler_free(sB);
    llama_free(A); llama_free(B);
    llama_model_free(model);
    return 0;
}
