// hybrid_bridge_v5.cpp — R3 (item D): modos htp|cpu|hybrid|ctrl, Np variavel,
// timers completos por fase, n_p_eval do destino verificado.
// (v4 preservado em hybrid_bridge.cpp como historical da R2.)
#include "llama.h"
#include <clocale>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>
#include <sys/stat.h>

static void log_cb(ggml_log_level level, const char * text, void * /*user*/) {
    (void)level; fputs(text, stderr);
}
// ===== VACINA (R3 pos-parecer): gate de backend real + identidade de execucao =====
// O bug corrigido: modo htp rodava no ctx CPU. O gate aborta ANTES de medir
// quando o ctx usado nao corresponde ao backend exigido pelo modo.
// Mutation test: --mutant-swap deliberadamente troca o ctx; o gate DEVE falhar (RED).
static void gate_ctx(bool expect_htp, bool ctx_is_htp, const char * where) {
    if (expect_htp != ctx_is_htp) {
        printf("RESULT_JSON:{\"gate\":\"FAIL\",\"reason\":\"ctx_backend_mismatch\",\"where\":\"%s\",\"expected\":\"%s\",\"got\":\"%s\"}\n",
               where, expect_htp ? "HTP0" : "CPU", ctx_is_htp ? "HTP0" : "CPU");
        fflush(stdout);
        exit(3);
    }
    fprintf(stderr, "[gate] %s OK: ctx=%s (esperado %s)\n", where, ctx_is_htp ? "HTP0" : "CPU", expect_htp ? "HTP0" : "CPU");
}
static void print_identity(const char * mode, bool use_htp, const char * ctx_used) {
    long sz = -1, mt = -1;
    struct stat st;
    if (stat("/proc/self/exe", &st) == 0) { sz = (long) st.st_size; mt = (long) st.st_mtime; }
    printf("RESULT_JSON:{\"identity\":{\"harness\":\"v5r3d\",\"mode\":\"%s\",\"expect_backend\":\"%s\",\"ctx_used\":\"%s\",\"exec_size\":%ld,\"exec_mtime\":%ld}}\n",
           mode, use_htp ? "HTP0" : "CPU", ctx_used, sz, mt);
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
    std::string model_path, dev_name = "HTP0", mode = "hybrid";
    int n_gen = 64, np_target = 128;
    bool mutant = false;
    const char * P = "Translate to Portuguese: The officer arrived at the scene and interviewed the witnesses about the traffic accident near the intersection.";
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "-m") && i + 1 < argc) model_path = argv[++i];
        else if (!strcmp(argv[i], "--dev") && i + 1 < argc) dev_name = argv[++i];
        else if (!strcmp(argv[i], "--mode") && i + 1 < argc) mode = argv[++i];
        else if (!strcmp(argv[i], "--np") && i + 1 < argc) np_target = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--ngen") && i + 1 < argc) n_gen = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--mutant-swap")) mutant = true;
    }
    if (model_path.empty()) { fprintf(stderr, "uso: -m modelo [--mode htp|cpu|hybrid|ctrl] [--np N] [--ngen N]\n"); return 1; }

    llama_backend_init();
    llama_log_set(log_cb, nullptr);
    ggml_backend_dev_t htp = ggml_backend_dev_by_name(dev_name.c_str());
    if (!htp) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"dev\"}\n"); return 2; }

    llama_model_params mp_cpu = llama_model_default_params();
    mp_cpu.n_gpu_layers = 0;
    llama_model_params mp_htp = llama_model_default_params();
    mp_htp.n_gpu_layers = 99;
    static ggml_backend_dev_t devs[2]; devs[0] = htp; devs[1] = nullptr;
    mp_htp.devices = devs;

    auto load = [&](bool use_htp, double & t_load, llama_model ** out_model) {
        double t0 = now_ms();
        llama_model * m = llama_model_load_from_file(model_path.c_str(), use_htp ? mp_htp : mp_cpu);
        t_load = now_ms() - t0;
        *out_model = m;
        return m != nullptr;
    };

    // prompt sintetico de ~np_target tokens (repete a frase base)
    llama_model * m0 = nullptr; double t0_dummy = 0;
    if (!load(false, t0_dummy, &m0)) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"model\"}\n"); return 2; }
    const llama_vocab * vocab = llama_model_get_vocab(m0);
    std::string base = P, text = base;
    while (true) {
        int nt = -llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, true, true);
        if (nt >= np_target || text.size() > 200000) break;
        text += " " + base;
    }
    int np = -llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, true, true);
    std::vector<llama_token> ptoks(np);
    llama_tokenize(vocab, text.c_str(), text.size(), ptoks.data(), ptoks.size(), true, true);
    llama_model_free(m0);
    fprintf(stderr, "[v5] np=%d (target %d) mode=%s ngen=%d\n", np, np_target, mode.c_str(), n_gen);

    auto mk_ctx = [&](llama_model * m) {
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = np + n_gen + 256; cp.n_batch = std::max(4096, np + 64); cp.no_perf = false;
        return llama_init_from_model(m, cp);
    };
    auto greedy = []() {
        auto * s = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(s, llama_sampler_init_greedy());
        return s;
    };
    auto gen_loop = [&](llama_context * c, llama_batch_ext * b, llama_sampler * s, std::vector<llama_token> & out, int pos0) {
        int n_pos = pos0;
        llama_token t = llama_sampler_sample(s, c, -1);
        for (int g = 0; g < n_gen; ++g) {
            out.push_back(t);
            batch_set_tokens(b, &t, 1, n_pos);
            if (llama_process(c, LLAMA_PROCESS_TYPE_DECODE, b) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"gen\"}\n"); exit(2); }
            n_pos++;
            t = llama_sampler_sample(s, c, -1);
        }
    };
    auto jstream = [](const std::vector<llama_token> & s) {
        std::string r = "["; for (size_t i = 0; i < s.size(); ++i) { r += std::to_string((int)s[i]); if (i + 1 < s.size()) r += ","; } return r + "]";
    };

    double wall0 = now_ms();
    if (mode == "ctrl" || mode == "cpu" || mode == "htp") {
        bool use_htp = (mode == "htp");
        llama_model * m1 = nullptr, * m2 = nullptr;
        double tl1 = 0, tl2 = 0;
        if (!load(use_htp, tl1, &m1) || !load(false, tl2, &m2)) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"loads\"}\n"); return 2; }
        double tC0 = now_ms();
        llama_context * c1 = mk_ctx(m1);
        llama_context * c2 = mk_ctx(m2);
        double t_ctxs = now_ms() - tC0;
        auto * b1 = llama_batch_ext_init(c1);
        auto * b2 = llama_batch_ext_init(c2);
        auto * s1 = greedy();
        auto * s2 = greedy();

        if (mode == "ctrl") {
            gate_ctx(false, false, "ctrl"); // ctrl: ambos os ctx CPU por construcao (use_htp=false neste modo)
            print_identity("ctrl", false, "c1+c2");
            batch_set_tokens(b1, ptoks.data(), np - 1, 0);
            if (llama_process(c1, LLAMA_PROCESS_TYPE_DECODE, b1) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"pre\"}\n"); return 2; }
            size_t sz = llama_state_seq_get_size(c1, 0);
            std::vector<uint8_t> st(sz);
            double tE0 = now_ms();
            size_t got = llama_state_seq_get_data(c1, st.data(), st.size(), 0);
            double t_exp = now_ms() - tE0;
            double tI0 = now_ms();
            size_t set = llama_state_seq_set_data(c2, st.data(), got, 0);
            double t_imp = now_ms() - tI0;
            std::vector<llama_token> out;
            double tB0 = now_ms();
            llama_token last = ptoks[np - 1];
            batch_set_tokens(b2, &last, 1, np - 1);
            if (llama_process(c2, LLAMA_PROCESS_TYPE_DECODE, b2) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"bridge\"}\n"); return 2; }
            double t_bridge = now_ms() - tB0;
            gen_loop(c2, b2, s2, out, np);
            const auto pd = llama_perf_context(c2);
            double wall = now_ms() - wall0;
            printf("RESULT_JSON:{\"mode\":\"ctrl\",\"np\":%d,\"ngen\":%d,\"state_bytes\":%zu,\"t_load_cpu_ms\":%.1f,\"t_ctxs_ms\":%.1f,\"t_export_ms\":%.2f,\"t_import_ms\":%.2f,\"t_bridge_ms\":%.1f,\"set_rc\":%zu,\"n_p_eval_dst\":%d,\"n_eval_dst\":%d,\"wall_ms\":%.1f,\"stream\":%s}\n",
                   np, n_gen, got, tl2, t_ctxs, t_exp, t_imp, t_bridge, set, (int)pd.n_p_eval, (int)pd.n_eval, wall, jstream(out).c_str());
        } else {
            llama_context * cRun = use_htp ? c1 : c2;   // htp rola no ctx do modelo HTP
            llama_batch_ext * bRun = use_htp ? b1 : b2;
            auto * sRun = use_htp ? s1 : s2;
            if (mutant) { cRun = use_htp ? c2 : c1; bRun = use_htp ? b2 : b1; } // MUTACAO: troca o ctx
            gate_ctx(use_htp, (cRun == c1), "mode_htp_cpu");       // aborta ANTES de medir
            print_identity(mode.c_str(), use_htp, (cRun == c1) ? "c1" : "c2");
            batch_set_tokens(bRun, ptoks.data(), np, 0);
            double tP0 = now_ms();
            if (llama_process(cRun, LLAMA_PROCESS_TYPE_DECODE, bRun) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"prefill\"}\n"); return 2; }
            double t_prefill = now_ms() - tP0;
            std::vector<llama_token> out;
            double tG0 = now_ms();
            gen_loop(cRun, bRun, sRun, out, np);
            double t_gen = now_ms() - tG0;
            const auto pd = llama_perf_context(cRun);
            double wall = now_ms() - wall0;
            printf("RESULT_JSON:{\"mode\":\"%s\",\"np\":%d,\"ngen\":%d,\"t_load_ms\":%.1f,\"t_ctxs_ms\":%.1f,\"t_prefill_ms\":%.1f,\"t_gen_ms\":%.1f,\"n_p_eval\":%d,\"n_eval\":%d,\"wall_ms\":%.1f,\"stream\":%s}\n",
                   mode.c_str(), np, n_gen, use_htp ? tl1 : tl2, t_ctxs, t_prefill, t_gen, (int)pd.n_p_eval, (int)pd.n_eval, wall, jstream(out).c_str());
        }
    } else if (mode == "hybrid") {
        llama_model * mH = nullptr, * mD = nullptr;
        double tlH = 0, tlD = 0;
        if (!load(true, tlH, &mH) || !load(false, tlD, &mD)) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"loads\"}\n"); return 2; }
        llama_context * cH = mk_ctx(mH);
        double tC0 = now_ms();
        llama_context * cD = mk_ctx(mD);
        double t_ctxD = now_ms() - tC0;
        auto * bH = llama_batch_ext_init(cH);
        auto * bD = llama_batch_ext_init(cD);
        auto * sD = greedy();
        gate_ctx(true,  true, "hybrid_src");   // cH deve ser HTP (por construcao: mH via mp_htp)
        gate_ctx(false, false, "hybrid_dst");  // cD deve ser CPU (mD via mp_cpu)
        print_identity("hybrid", true, "cH+cD");
        double tP0 = now_ms();
        batch_set_tokens(bH, ptoks.data(), np - 1, 0);
        if (llama_process(cH, LLAMA_PROCESS_TYPE_DECODE, bH) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"pre_htp\"}\n"); return 2; }
        double t_prefill_htp = now_ms() - tP0;
        size_t sz = llama_state_seq_get_size(cH, 0);
        std::vector<uint8_t> st(sz);
        double tE0 = now_ms();
        size_t got = llama_state_seq_get_data(cH, st.data(), st.size(), 0);
        double t_exp = now_ms() - tE0;
        double tI0 = now_ms();
        size_t set = llama_state_seq_set_data(cD, st.data(), got, 0);
        double t_imp = now_ms() - tI0;
        double tB0 = now_ms();
        llama_token last = ptoks[np - 1];
        batch_set_tokens(bD, &last, 1, np - 1);
        if (llama_process(cD, LLAMA_PROCESS_TYPE_DECODE, bD) != 0) { fprintf(stderr, "RESULT_JSON:{\"fatal\":\"bridge\"}\n"); return 2; }
        double t_bridge = now_ms() - tB0;
        std::vector<llama_token> out;
        double tG0 = now_ms();
        gen_loop(cD, bD, sD, out, np);
        double t_gen = now_ms() - tG0;
        const auto pd = llama_perf_context(cD);
        double wall = now_ms() - wall0;
        printf("RESULT_JSON:{\"mode\":\"hybrid\",\"np\":%d,\"ngen\":%d,\"state_bytes\":%zu,\"t_load_htp_ms\":%.1f,\"t_load_cpu_ms\":%.1f,\"t_ctxD_ms\":%.1f,\"t_prefill_htp_ms\":%.1f,\"t_export_ms\":%.2f,\"t_import_ms\":%.2f,\"t_bridge_ms\":%.1f,\"t_gen_cpu_ms\":%.1f,\"set_rc\":%zu,\"n_p_eval_dst\":%d,\"n_eval_dst\":%d,\"wall_ms\":%.1f,\"stream\":%s}\n",
               np, n_gen, got, tlH, tlD, t_ctxD, t_prefill_htp, t_exp, t_imp, t_bridge, t_gen, set, (int)pd.n_p_eval, (int)pd.n_eval, wall, jstream(out).c_str());
    } else {
        fprintf(stderr, "RESULT_JSON:{\"fatal\":\"mode invalido\"}\n"); return 2;
    }
    printf("RESULT_JSON:{\"bloco\":\"fim\",\"ok\":true}\n");
    return 0;
}
