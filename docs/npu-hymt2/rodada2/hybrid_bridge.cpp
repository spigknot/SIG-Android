// hybrid_bridge.cpp — RODADA 2, Bloco F2 — ponte de estado HTP->CPU (e controle CPU->CPU).
//
// Fluxo:
//  1. CONTROLE CPU->CPU: ctxA(CPU) prefill P -> export seq state -> ctxB(CPU) import
//     -> 1 bridge forward do ultimo token (pos N-1) -> sample Xk -> gerar 4 no ctxB.
//     Referencia: ctxC(CPU) prefill P + gerar 4 (tudo CPU, mesmo processo).
//     Streams ctxB vs ctxC DEVEM ser iguais (greedy) -> valida serializacao.
//  2. TESTE HTP->CPU: ctxH(HTP0) prefill P -> export -> ctxD(CPU) import
//     -> bridge forward 1 token -> gerar 4. Comparar com a referencia ctxC.
//  3. Metricas: bytes do state, tempos export/import/bridge, streams.
//
// Uso: hybrid_bridge -m modelo.gguf [--ngen 4] [--dev HTP0]
// Saida: RESULT_JSON: {...}

#include "llama.h"
#include <clocale>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>

// captura TODAS as mensagens de log do llama.cpp/ggml (log novo e silencioso por padrao)
static void log_cb(ggml_log_level level, const char * text, void * /*user*/) {
    (void)level;
    fputs(text, stderr);
}

static double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

static void batch_set_tokens(llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos pos_0) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = pos_0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, n - 1, true);
}

int main(int argc, char ** argv) {
    std::setlocale(LC_NUMERIC, "C");
    std::string model_path, dev_name = "HTP0";
    int n_gen = 4;
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "-m") && i + 1 < argc) model_path = argv[++i];
        else if (!strcmp(argv[i], "--ngen") && i + 1 < argc) n_gen = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--dev") && i + 1 < argc) dev_name = argv[++i];
    }
    if (model_path.empty()) { fprintf(stderr, "uso: hybrid_bridge -m modelo.gguf\n"); return 1; }

    llama_backend_init();
    llama_log_set(log_cb, nullptr);
    ggml_backend_dev_t htp = ggml_backend_dev_by_name(dev_name.c_str());
    if (!htp) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"dev\"}\n"); return 2; }

    llama_model_params mp = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!model) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"model\"}\n"); return 2; }
    const llama_vocab * vocab = llama_model_get_vocab(model);

    const std::string P = "Translate to Portuguese: The officer arrived at the scene and interviewed the witnesses.";
    int np = -llama_tokenize(vocab, P.c_str(), P.size(), nullptr, 0, true, true);
    std::vector<llama_token> ptoks(np);
    llama_tokenize(vocab, P.c_str(), P.size(), ptoks.data(), ptoks.size(), true, true);

    auto make_ctx = [&](bool use_htp) {
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = 1024; cp.n_batch = 512;
        llama_model_params mp2 = llama_model_default_params();
        if (use_htp) {
            mp2.n_gpu_layers = 99;
            static ggml_backend_dev_t devs[2];
            devs[0] = htp; devs[1] = nullptr;
            mp2.devices = devs;
        } else {
            mp2.n_gpu_layers = 0; // CPU puro
        }
        // nota: model ja carregado; para forcar device por contexto usamos model_params do load.
        // este harness carrega DOIS modelos (CPU e HTP) para permitir contextos com backends distintos.
        llama_model * m2 = llama_model_load_from_file(model_path.c_str(), mp2);
        if (!m2) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"model2\"}\n"); exit(2); }
        llama_context * c = llama_init_from_model(m2, cp);
        if (!c) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"ctx\"}\n"); exit(2); }
        return std::pair<llama_model *, llama_context *>(m2, c);
    };

    // prefill num ctx + devolve recursos
    auto prefill = [&](llama_context * c, llama_batch_ext * b) {
        batch_set_tokens(b, ptoks.data(), np, 0);
        if (llama_process(c, LLAMA_PROCESS_TYPE_DECODE, b) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"prefill\"}\n"); exit(2); }
    };
    // prefill PARCIAL: tokens[0..np-2] (ultimo token fica de fora -> sera a ponte no destino)
    auto prefill_bridge = [&](llama_context * c, llama_batch_ext * b) {
        batch_set_tokens(b, ptoks.data(), np - 1, 0);
        if (llama_process(c, LLAMA_PROCESS_TYPE_DECODE, b) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"prefill_bridge\"}\n"); exit(2); }
    };
    // 1 bridge forward do ultimo token (pos np-1) p/ obter logits + gera n tokens greedy
    auto bridge_and_gen = [&](llama_context * c, llama_batch_ext * b, llama_sampler * s, std::vector<llama_token> & out, double & t_bridge) {
        fprintf(stderr, "[bridge] pos_max antes=%d | token pos=%d\n",
                (int)llama_memory_seq_pos_max(llama_get_memory(c), 0), np - 1);
        double t0 = now_ms();
        llama_token last = ptoks.back();
        batch_set_tokens(b, &last, 1, np - 1);
        int prc = llama_process(c, LLAMA_PROCESS_TYPE_DECODE, b);
        if (prc != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"bridge\",\"process_rc\":%d,\"pos\":%d}\n", prc, np-1); exit(2); }
        t_bridge = now_ms() - t0;
        int n_pos = np;
        llama_token t = llama_sampler_sample(s, c, -1);
        for (int g = 0; g < n_gen; ++g) {
            out.push_back(t);
            batch_set_tokens(b, &t, 1, n_pos);
            if (llama_process(c, LLAMA_PROCESS_TYPE_DECODE, b) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"gen\"}\n"); exit(2); }
            n_pos++;
            t = llama_sampler_sample(s, c, -1);
        }
    };
    // referencia tudo-CPU: prefill + n geracoes (sem bridge)
    auto prefill_and_gen = [&](llama_context * c, llama_batch_ext * b, llama_sampler * s, std::vector<llama_token> & out) {
        prefill(c, b);
        int n_pos = np;
        llama_token t = llama_sampler_sample(s, c, -1);
        for (int g = 0; g < n_gen; ++g) {
            out.push_back(t);
            batch_set_tokens(b, &t, 1, n_pos);
            if (llama_process(c, LLAMA_PROCESS_TYPE_DECODE, b) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"refgen\"}\n"); exit(2); }
            n_pos++;
            t = llama_sampler_sample(s, c, -1);
        }
    };

    // ===== CONTROLE CPU->CPU =====
    auto [mA, cA] = make_ctx(false);
    auto [mB, cB] = make_ctx(false);
    auto [mC, cC] = make_ctx(false);
    auto * bA = llama_batch_ext_init(cA);
    auto * bB = llama_batch_ext_init(cB);
    auto * bC = llama_batch_ext_init(cC);
    auto * sB = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sB, llama_sampler_init_greedy());
    auto * sC = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sC, llama_sampler_init_greedy());

    prefill_bridge(cA, bA);  // deixa o ultimo token p/ a ponte
    size_t ssz = llama_state_seq_get_size(cA, 0);
    std::vector<uint8_t> st(ssz);
    double t0 = now_ms();
    size_t got = llama_state_seq_get_data(cA, st.data(), st.size(), 0);
    double t_exp = now_ms() - t0;
    t0 = now_ms();
    size_t set = llama_state_seq_set_data(cB, st.data(), got, 0);
    double t_imp = now_ms() - t0;
    fprintf(stderr, "[ctrl] state bytes=%zu export=%.2fms import=%.2fms set_rc=%zu pos_max(B)=%d\n",
            got, t_exp, t_imp, set, (int)llama_memory_seq_pos_max(llama_get_memory(cB), 0));

    std::vector<llama_token> out_ref, out_ctrl;
    double tb1, tb2;
    prefill_and_gen(cC, bC, sC, out_ref);          // referencia tudo-CPU (prefill + 4 gens)
    llama_memory_clear(llama_get_memory(cA), true); // limpa A (nao usado mais)
    bridge_and_gen(cB, bB, sB, out_ctrl, tb2);     // bridge controle (B ja tem o KV importado)

    auto jstream = [](const std::vector<llama_token> & s) {
        std::string r = "["; for (size_t i = 0; i < s.size(); ++i) { r += std::to_string((int)s[i]); if (i + 1 < s.size()) r += ","; } return r + "]";
    };
    printf("RESULT_JSON:{\"bloco\":\"ctrl_cpu_cpu\",\"state_bytes\":%zu,\"t_export_ms\":%.2f,\"t_import_ms\":%.2f,\"bridge_ms\":%.2f,\"out_ref\":%s,\"out_ctrl\":%s,\"iguais\":%s}\n",
           got, t_exp, t_imp, tb2, jstream(out_ref).c_str(), jstream(out_ctrl).c_str(), (out_ctrl == out_ref) ? "true" : "false");
    fprintf(stderr, "[ctrl] streams iguais: %s\n", (out_ctrl == out_ref) ? "SIM" : "NAO");

    // ===== TESTE HTP->CPU =====
    auto [mH, cH] = make_ctx(true);
    auto [mD, cD] = make_ctx(false);
    auto * bH = llama_batch_ext_init(cH);
    auto * bD = llama_batch_ext_init(cD);
    auto * sD = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sD, llama_sampler_init_greedy());

    prefill_bridge(cH, bH);  // deixa o ultimo token p/ a ponte
    ssz = llama_state_seq_get_size(cH, 0);
    std::vector<uint8_t> stH(ssz);
    t0 = now_ms();
    size_t gotH = llama_state_seq_get_data(cH, stH.data(), stH.size(), 0);
    double t_exp_h = now_ms() - t0;
    t0 = now_ms();
    size_t setH = llama_state_seq_set_data(cD, stH.data(), gotH, 0);
    double t_imp_h = now_ms() - t0;
    fprintf(stderr, "[htp] state bytes=%zu export=%.2fms import=%.2fms set_rc=%zu pos_max(D)=%d\n",
            gotH, t_exp_h, t_imp_h, setH, (int)llama_memory_seq_pos_max(llama_get_memory(cD), 0));

    std::vector<llama_token> out_htp;
    double tb3;
    bridge_and_gen(cD, bD, sD, out_htp, tb3);

    printf("RESULT_JSON:{\"bloco\":\"htp_cpu_bridge\",\"state_bytes\":%zu,\"t_export_ms\":%.2f,\"t_import_ms\":%.2f,\"bridge_ms\":%.2f,\"out_htp\":%s,\"iguais_vs_ref\":%s}\n",
           gotH, t_exp_h, t_imp_h, tb3, jstream(out_htp).c_str(), (out_htp == out_ref) ? "true" : "false");
    fprintf(stderr, "[htp] stream == referencia CPU: %s\n", (out_htp == out_ref) ? "SIM" : "NAO");

    printf("RESULT_JSON:{\"bloco\":\"fim\",\"ok\":true}\n");

    llama_batch_ext_free(bA); llama_batch_ext_free(bB); llama_batch_ext_free(bC);
    llama_batch_ext_free(bH); llama_batch_ext_free(bD);
    llama_sampler_free(sB); llama_sampler_free(sC); llama_sampler_free(sD);
    llama_free(cA); llama_free(cB); llama_free(cC); llama_free(cH); llama_free(cD);
    llama_model_free(mA); llama_model_free(mB); llama_model_free(mC); llama_model_free(mH); llama_model_free(mD);
    return 0;
}
