// probe.cpp — NPU app-UID probe: prova que um APP (UID normal, sem root/adb)
// consegue (1) carregar o backend hexagon, (2) ABRIR SESSAO HTP no DSP e
// (3) EXECUTAR um op sintetico (MUL_MAT f32) com resultado verificado.
#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <map>
#include <cmath>
#include <sys/stat.h>
#include <string>
#include "gate_seam.h"
#include "r30_gate.h"   // R31: GATE PIN compartilhado (r30!) — o MESMO da fixture!
#include "flow_seam.h"   // R34: SEAM do fluxo alloc (a MESMA da fixture!)
#include "chunk_plan.h"
#include <mutex>
#include <chrono>
#include <vector>

#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include "llama.h"
#include <dlfcn.h>

#define TAG "NpuProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::string g_rep;
static std::mutex g_rep_mutex;   // probe multi-thread (auto-gatilho): logs limpos
static void rep(const char * fmt, ...) __attribute__((format(printf, 1, 2)));
static void rep(const char * fmt, ...) {
    char buf[512];
    va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
    {
        std::lock_guard<std::mutex> lk(g_rep_mutex);
        g_rep += buf; g_rep += "\n";
    }
    LOGI("%s", buf);
}

// R11: log bufferizado (o log DIRETO no meio do FastRPC/flush travou o run
// do diag R9). Acumula com mutex leve; o FLUSH ocorre fora da janela critica
// (fim de request/sessao), conforme a ordem.
static std::mutex g_log_mu;
static std::vector<std::string> g_log_buf;
static void ggml_log_cb(enum ggml_log_level level, const char * text, void * /*user*/) {
    (void) level;
    if (text == nullptr) return;
    std::lock_guard<std::mutex> lk(g_log_mu);
    if (g_log_buf.size() < 300000) g_log_buf.emplace_back(text);
}
static void log_flush() {
    std::vector<std::string> copy;
    { std::lock_guard<std::mutex> lk(g_log_mu); copy.swap(g_log_buf); }
    for (auto & s : copy) {
        std::string tt = s;
        while (!tt.empty() && (tt.back() == '\n' || tt.back() == '\r')) tt.pop_back();
        if (!tt.empty()) __android_log_print(ANDROID_LOG_INFO, TAG, "[ggml] %s", tt.c_str());
    }
}

static double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

// Mutacao de teste (fail-closed): lida de /data/local/tmp/p4-mutante.txt
// (o app ja le o modelo desse diretorio com nome conhecido). Vazio = sem mutacao.
static std::string read_mutante() {
    FILE * f = fopen("/data/local/tmp/p4-mutante.txt", "r");
    if (f == nullptr) return "";
    char b[64] = {0};
    size_t n = fread(b, 1, 63, f);
    fclose(f);
    std::string s(b, n);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ')) s.pop_back();
    while (!s.empty() && (s.front() == ' ')) s.erase(s.begin());
    return s;
}

// Le arquivo de texto inteiro (R4: prompt/cap remotos; vazio = usar default)
static std::string read_arquivo_txt(const char * caminho, size_t max) {
    FILE * f = fopen(caminho, "r");
    if (f == nullptr) return "";
    std::string s;
    std::vector<char> buf(4096);
    size_t n;
    while ((n = fread(buf.data(), 1, buf.size(), f)) > 0 && s.size() < max) s.append(buf.data(), n);
    fclose(f);
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    if (s.size() > max) s.resize(max);
    return s;
}

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_npuprobe_MainActivity_runProbe(JNIEnv * env, jobject, jstring libdir_j) {
    g_rep.clear();
    const char * libdir = env->GetStringUTFChars(libdir_j, nullptr);
    rep("libdir=%s", libdir);
    rep("build=llama.cpp-upstream-pkg (v11-libs) + probe R4");

    ggml_log_set(ggml_log_cb, nullptr);   // erros do loader visiveis no logcat

    // TESTE dlopen direto do driver FastRPC (a pergunta central app-UID).
    // Captura de erro CORRETA (R6): limpar dlerror antes, ler UMA vez, copiar
    // imediatamente para std::string (o getter consome o estado).
    {
        auto try_dlopen = [&](const char * label, const char * name) {
            dlerror();                                  // limpa estado anterior
            void * h = dlopen(name, RTLD_NOW);
            if (h) {
                rep("%s (%s)=OK", label, name);
                dlclose(h);
            } else {
                const char * e = dlerror();             // UNICA leitura
                std::string msg = e ? std::string(e) : std::string("(vazio apos limpeza)");
                rep("%s (%s)=FALHOU err=%s", label, name, msg.c_str());
            }
        };
        try_dlopen("controle-sistema", "liblog.so");           // positivo: deve dar OK
        try_dlopen("negativo-fake", "libnaoexiste_xyz.so");    // negativo: erro NAO-nulo esperado
        try_dlopen("cdsprpc-nome", "libcdsprpc.so");           // alvo (declarada no manifest R6)
        try_dlopen("cdsprpc-abs", "/vendor/lib64/libcdsprpc.so"); // via path absoluto
    }
    // (1) skel: o FastRPC procura o libggml-htp-v81.so pelo ADSP_LIBRARY_PATH
    setenv("ADSP_LIBRARY_PATH", libdir, 1);
    setenv("LD_LIBRARY_PATH", libdir, 1);
    rep("ADSP_LIBRARY_PATH=libdir (setenv)");

    // (2) carregar backends do dir do APK (dlopen libggml-*.so)
    double t0 = now_ms();
    ggml_backend_load_all_from_path(libdir);
    rep("load_all_from_path=%.0fms", now_ms() - t0);

    // (2b) carga DIRETA do hexagon (o load_all e silencioso em Release):
    {
        std::string hx = std::string(libdir) + "/libggml-hexagon.so";
        double t1 = now_ms();
        ggml_backend_reg_t r = ggml_backend_load(hx.c_str());
        rep("load(hexagon direto)=%s em %.0fms", r ? "OK" : "FALHOU", now_ms() - t1);
    }

    // (3) enumerar devices
    size_t nd = ggml_backend_dev_count();
    rep("devices=%zu", nd);
    ggml_backend_dev_t htp = nullptr;
    for (size_t i = 0; i < nd; i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        rep("  dev[%zu] %s (%s)", i, ggml_backend_dev_name(d), ggml_backend_dev_description(d));
        if (strcmp(ggml_backend_dev_name(d), "HTP0") == 0) htp = d;
    }
    if (htp == nullptr) { rep("RESULTADO: HTP0 NAO ENCONTRADO (backend hexagon nao carregou)"); goto done; }

    {
        // (4) ABRIR A SESSAO HTP no DSP (o teste central do app-UID)
        double t1 = now_ms();
        ggml_backend_t be = ggml_backend_dev_init(htp, nullptr);
        rep("dev_init(HTP0)=%.0fms -> %s", now_ms() - t1, be ? "SESSAO ABERTA" : "FALHOU");
        if (!be) { rep("RESULTADO: sessao HTP NAO abriu com UID de app"); goto done; }

        // (5) op sintetico: c = a * b (64x64x64 f32); a=1, b=2 -> c[0]=128
        struct ggml_init_params ip = { 8u*1024u*1024u, nullptr, true };
        struct ggml_context * ctx = ggml_init(ip);
        struct ggml_tensor * a = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, 64, 64);
        struct ggml_tensor * b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, 64, 64);
        struct ggml_tensor * c = ggml_mul_mat(ctx, a, b);
        ggml_set_name(a, "a"); ggml_set_name(b, "b"); ggml_set_name(c, "c");

        ggml_backend_buffer_t buf = ggml_backend_alloc_ctx_tensors(ctx, be);
        if (buf == nullptr) { rep("alloc_ctx_tensors FALHOU"); ggml_free(ctx); ggml_backend_free(be); goto done; }

        std::vector<float> av(64*64, 1.0f), bv(64*64, 2.0f), cv(64*64, 0.0f);
        ggml_backend_tensor_set(a, av.data(), 0, av.size()*sizeof(float));
        ggml_backend_tensor_set(b, bv.data(), 0, bv.size()*sizeof(float));

        struct ggml_cgraph * gf = ggml_new_graph(ctx);
        ggml_build_forward_expand(gf, c);
        double t2 = now_ms();
        enum ggml_status st = ggml_backend_graph_compute(be, gf);
        rep("graph_compute(MUL_MAT 64x64)=%.1fms status=%d", now_ms() - t2, (int) st);
        ggml_backend_tensor_get(c, cv.data(), 0, cv.size()*sizeof(float));
        rep("c[0]=%f (esperado 128.0)", cv[0]);
        rep("RESULTADO: %s", (st == GGML_STATUS_SUCCESS && cv[0] == 128.0f)
            ? "APP-UID OK: sessao HTP + op executado com resultado correto"
            : "PARCIAL: sessao abriu, compute/resultado divergente");

        ggml_backend_buffer_free(buf);
        ggml_free(ctx);
        ggml_backend_free(be);
    }
done:
    env->ReleaseStringUTFChars(libdir_j, libdir);
    return env->NewStringUTF(g_rep.c_str());
}

// ============================================================================
// P4 v2 — FAIL-CLOSED (ordens pos-R1, secoes B/C/D): status por camada;
// sucesso SO com TODAS as provas; mutantes injetaveis via SIG_P4_MUTANTE;
// A-B-A pleno com validacao de reset e comparacao integral; prompt do SEAM de
// produto (HyMt2Translator); metricas sem ambiguidade (pp1 != TTFT).
// ============================================================================
namespace p4v2 {

struct ReqResult {
    bool ok = false;
    int  n_prompt = 0, n_pieces = 0, n_decodes = 0, stop = 2; // 0=EOG 1=limite 2=erro
    double prefill_ms = 0, gen_ms = 0, primeiro_piece_ms = -1;
    std::string texto, erro;
};

static std::string hash_prompt(const std::string & s, const std::vector<llama_token> & t) {
    uint64_t h = 1469598103934665603ULL;
    for (unsigned char c : s) { h ^= c; h *= 1099511628211ULL; }
    h ^= 0x5F; h *= 1099511628211ULL;
    for (llama_token v : t) { h ^= (uint64_t) v; h *= 1099511628211ULL; }
    char b[24]; snprintf(b, sizeof(b), "%016llx", (unsigned long long) h);
    return std::string(b);
}

// Funcao de REQUEST unica (A/B/A usam o MESMO contrato de tokenizacao).
// Fail-closed: rc!=0, resposta vazia ou tokenize invalido => ok=false.
static ReqResult request_once(llama_context * ctx, const llama_vocab * vocab,
                              const std::string & prompt, int cap,
                              const std::string & mutante) {
    ReqResult r;
    std::vector<llama_token> toks(prompt.size() + 32);
    int nt = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                            toks.data(), (int32_t) toks.size(),
                            /*add_special=*/false, /*parse_special=*/true);
    if (nt <= 0 || mutante == "tokenize_fail") { r.erro = "tokenize"; return r; }
    toks.resize(nt);
    r.n_prompt = nt;

    // R10: prefill via chunk_plan (mesmo helper do Smart); aborta em erro.
    double t0 = now_ms();
    int rc = 0;
    for (auto & cp : chunk_plan(nt, 128)) {
        llama_batch b = llama_batch_get_one(toks.data() + cp.first, cp.second);
        rc = llama_decode(ctx, b);
        if (rc != 0) break;
    }
    r.prefill_ms = now_ms() - t0;
    if (mutante == "prefill_rc") rc = 1;
    if (rc != 0) { r.erro = "prefill_rc"; return r; }

    auto smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    std::vector<char> piece(512);
    const double t_gen0 = now_ms();
    llama_token tok = 0;
    int i = 0;
    for (; i < cap; i++) {
        tok = llama_sampler_sample(smpl, ctx, -1);
        if (mutante == "zero_resp" && i == 0) tok = llama_vocab_eos(vocab);
        if (llama_vocab_is_eog(vocab, tok)) { r.stop = 0; break; }
        int np = llama_token_to_piece(vocab, tok, piece.data(), (int32_t) piece.size(), 0, true);
        if (np < 0) {
            if (-np > 65536) { r.erro = "piece_grande"; r.stop = 2; break; }
            piece.resize((size_t) (-np) + 8);
            np = llama_token_to_piece(vocab, tok, piece.data(), (int32_t) piece.size(), 0, true);
        }
        if (np > 0) {
            r.texto.append(piece.data(), (size_t) np);
            r.n_pieces++;
            if (r.primeiro_piece_ms < 0) r.primeiro_piece_ms = now_ms() - t_gen0;
        }
        llama_batch b = llama_batch_get_one(&tok, 1);
        int rcd = llama_decode(ctx, b);
        r.n_decodes++;
        if (mutante == "decode_rc" && i == 1) rcd = 1;
        if (rcd != 0) { r.erro = "decode_rc"; r.stop = 2; break; }
        if (i + 1 >= cap) r.stop = 1;    // cap atingido sem EOG
    }
    r.gen_ms = now_ms() - t_gen0;
    llama_sampler_free(smpl);
    if (r.erro.empty() && r.texto.empty()) r.erro = "resposta_vazia";
    if (mutante == "cap_trunc") r.stop = 1;   // forca categorizacao "truncado"
    r.ok = r.erro.empty();
    return r;
}

} // namespace p4v2

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_npuprobe_MainActivity_runP4(JNIEnv * env, jobject, jstring model_j, jstring libdir_j, jstring rota_j) {
    g_rep.clear();
    const char * model_path = env->GetStringUTFChars(model_j, nullptr);
    const char * libdir = env->GetStringUTFChars(libdir_j, nullptr);
    const char * rota_c = env->GetStringUTFChars(rota_j, nullptr);
    const std::string rota(rota_c ? rota_c : "htp");
    const std::string mutante = read_mutante();
    const double t_request = now_ms();

    rep("P4v2 modelo=%s rota=%s mutante=%s", model_path, rota.c_str(), mutante.empty() ? "(none)" : mutante.c_str());
    if (!mutante.empty()) rep("P4v2 MUTACAO ATIVA (testando o fail-closed): %s", mutante.c_str());

    setenv("ADSP_LIBRARY_PATH", libdir, 1);
    setenv("LD_LIBRARY_PATH", libdir, 1);
    ggml_log_set(ggml_log_cb, nullptr);
    llama_backend_init();
    ggml_backend_load_all_from_path(libdir);

    ggml_backend_dev_t htp = ggml_backend_dev_by_name("HTP0");
    ggml_backend_dev_t cpu = ggml_backend_dev_by_name("CPU");
    ggml_backend_dev_t alvo = (rota == "cpu") ? cpu : htp;
    const char * nome_alvo = (rota == "cpu") ? "CPU" : "HTP0";
    if (alvo == nullptr) { rep("RESULTADO: P4v2 FALHA (device '%s' ausente)", nome_alvo); goto fim_p4v2; }
    rep("device alvo: %s (%s)", ggml_backend_dev_name(alvo), ggml_backend_dev_description(alvo));
    if (mutante == "cpu_ctx") {
        // mutacao: device efetivo divergiria do pedido -> o check abaixo pega
        rep("CHECK device: pedido=%s efetivo=%s -> DIVERGENCIA (mutante cpu_ctx)", nome_alvo, "CPU");
        rep("RESULTADO: P4v2 FALHA (device efetivo != pedido)");
        goto fim_p4v2;
    }

    {
        ggml_backend_dev_t devs[2] = { alvo, nullptr };
        llama_model_params mp = llama_model_default_params();
        mp.devices = devs; mp.n_gpu_layers = 999;
        double t0 = now_ms();
        llama_model * model = llama_model_load_from_file(model_path, mp);
        rep("load=%.0fms", now_ms() - t0);
        if (model == nullptr) { rep("RESULTADO: P4v2 FALHA (load)"); goto fim_p4v2; }

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 128; cp.n_ubatch = 128; cp.n_threads = 4;
        double tc = now_ms();
        llama_context * ctx = llama_init_from_model(model, cp);
        rep("ctx=%.0fms (cold_total ate aqui=%.0fms)", now_ms() - tc, now_ms() - t_request);
        if (ctx == nullptr) { llama_model_free(model); rep("RESULTADO: P4v2 FALHA (ctx)"); goto fim_p4v2; }

        const llama_vocab * vocab = llama_model_get_vocab(model);
        std::string status;

        // prompt do SEAM de produto (HyMt2Translator) — NAO o chat template do GGUF
        auto build_prompt = [&](const std::string & texto, const std::string & alvo_lang) {
            std::string user = "Translate the following text into " + alvo_lang +
                ". Note that you should only output the translated result without any additional explanation:\n\n" + texto;
            return std::string("<｜hy_begin▁of▁sentence｜><｜hy_User｜>") + user + "<｜hy_Assistant｜>";
        };
        std::string pA = build_prompt("Good morning, how are you?", "Portuguese");
        std::string pB = build_prompt("The weather is nice today.", "Portuguese");
        if (mutante == "template_bad") pA = "Good morning, how are you?";

        bool template_ok = pA.find("<｜hy_User｜>") != std::string::npos &&
                           pA.find("<｜hy_Assistant｜>") != std::string::npos;
        rep("template_seam: %s", template_ok ? "OK (marcadores hy presentes)" : "INVALIDO");
        if (!template_ok) status += "template_invalido ";

        const int CAP = 48;
        {
            std::vector<llama_token> tt(pA.size() + 32);
            int n = llama_tokenize(vocab, pA.c_str(), (int) pA.size(), tt.data(), (int) tt.size(), false, true);
            if (n > 0) { tt.resize(n); rep("promptA: tokens=%d hash=%s alvo=Portuguese", n, p4v2::hash_prompt(pA, tt).c_str()); }
        }

        auto A1 = p4v2::request_once(ctx, vocab, pA, CAP, mutante);
        rep("A1: ok=%d stop=%d pieces=%d decodes=%d prefill=%.2fms gen=%.2fms pp1=%.2fms",
            (int) A1.ok, A1.stop, A1.n_pieces, A1.n_decodes, A1.prefill_ms, A1.gen_ms, A1.primeiro_piece_ms);
        rep("A1 texto: %.300s", A1.texto.c_str());
        if (!A1.ok) status += "A1_erro(" + A1.erro + ") ";

        if (mutante != "reset_bad") llama_memory_clear(llama_get_memory(ctx), true);
        llama_pos pmax1 = llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
        bool reset1_ok = (pmax1 < 0);
        rep("reset1: pos_max=%d -> %s", (int) pmax1, reset1_ok ? "VAZIO ok" : "NAO VAZIO (falha)");
        if (!reset1_ok) status += "reset1_falhou ";

        auto B = p4v2::request_once(ctx, vocab, pB, CAP, mutante);
        rep("B: ok=%d stop=%d pieces=%d texto: %.200s", (int) B.ok, B.stop, B.n_pieces, B.texto.c_str());
        if (!B.ok || B.texto.empty()) status += "B_erro ";

        if (mutante != "reset_bad") llama_memory_clear(llama_get_memory(ctx), true);
        llama_pos pmax2 = llama_memory_seq_pos_max(llama_get_memory(ctx), 0);
        bool reset2_ok = (pmax2 < 0);
        rep("reset2: pos_max=%d -> %s", (int) pmax2, reset2_ok ? "VAZIO ok" : "NAO VAZIO (falha)");
        if (!reset2_ok) status += "reset2_falhou ";

        auto A2 = p4v2::request_once(ctx, vocab, pA, CAP, mutante);
        bool aba_igual = A1.ok && A2.ok && (A1.texto == A2.texto);
        rep("A2: ok=%d pieces=%d | A-B-A: %s", (int) A2.ok, A2.n_pieces,
            aba_igual ? "A1==A2 (comparacao integral ok)" : "DIFERE/FALHA");
        if (!A2.ok) status += "A2_erro ";
        if (A1.ok && A2.ok && !aba_igual) status += "ABA_difere ";

        if (A1.primeiro_piece_ms >= 0)
            rep("metricas: pos_prefill_primeiro_piece=%.2fms | requestA1->primeiro_piece=%.2fms | cold_total=%.0fms",
                A1.primeiro_piece_ms, A1.prefill_ms + A1.primeiro_piece_ms, now_ms() - t_request);
        rep("stop_reasons: A1=%d B=%d A2=%d (0=EOG 1=limite_cap 2=erro)", A1.stop, B.stop, A2.stop);
        if (A1.stop == 1 || B.stop == 1 || A2.stop == 1) status += "cap_atingido(parcial) ";
        if (rota == "cpu") rep("nota: rota CPU = referencia matched (nao e' evidencia NPU)");

        llama_free(ctx);
        llama_model_free(model);

        if (status.empty())
            rep("RESULTADO: P4v2 SUCESSO (template+A1+reset1+B+reset2+A2 + A-B-A integral)");
        else
            rep("RESULTADO: P4v2 FALHA/PARCIAL: %s", status.c_str());
    }
fim_p4v2:
    env->ReleaseStringUTFChars(model_j, model_path);
    env->ReleaseStringUTFChars(libdir_j, libdir);
    env->ReleaseStringUTFChars(rota_j, rota_c);
    return env->NewStringUTF(g_rep.c_str());
}

// ============================================================================
// SMART v3 — RODADA 4: VALIDACAO vs BENCHMARK separados por flag explicita
// (mesma implementacao real do handoff). VALIDACAO: espelho teacher-forcing
// com samplers SEPARADOS, margem top1-top2 registrada na 1a divergencia e
// rcS!=0 ABORTA. BENCHMARK (@bench): prefill NPU -> ponte -> decode SO no
// destino; a origem NAO decodifica pos-ponte (gate). cap TOTAL inclui tok1.
// EOG em tok1 = "sem handoff" (nao e' sucesso de traducao). Export exige
// tamanho EXATO do contrato (llama_state_get_size). Fronteiras absolutas
// registradas (internas: o probe NAO tem streaming de UI).
// ============================================================================
namespace smartv2 {

struct BridgeResult {
    bool ok = false, cap = false, bench = false;
    bool eog_tok1 = false;
    int  n_amostrados_s = 0, n_amostrados_d = 0, n_emitidos = 0, n_consumidos = 0;
    int  n_decodes_s_pos_ponte = 0;
    int  espelho_passos = 0, espelho_iguais = 0, div_pos = -1;
    double marg_d = -1, marg_s = -1, div_marg_d = -1, div_marg_s = -1;
    double marg_final_d = -1;   // margem top1-2 do destino no ultimo passo (prova a coleta)
    std::string div_topk_d, div_topk_s;   // R9: top-5 de cada lado na 1a divergencia
    bool div_finite = true;               // R9: nenhum logit nao-finito nos tops
    int  tok1 = -1, stop = 2;                 // 0=EOG 1=cap 2=erro 3=EOG_tok1
    double prefill_ms = 0, export_ms = 0, import_ms = 0, gen_ms = 0;
    double t_prefill_done = 0, t_tok1_ready = 0, t_export_done = 0,
           t_import_done = 0, t_tok2_ready = 0, t_fim = 0;
    size_t export_bytes = 0;
    std::string hash_prompt;
    std::string texto, erro;
};

static int emit_piece(const llama_vocab * v, llama_token t, std::string & acc, std::vector<char> & buf) {
    int np = llama_token_to_piece(v, t, buf.data(), (int32_t) buf.size(), 0, true);
    if (np < 0) {
        if (-np > 65536) return -1;
        buf.resize((size_t) (-np) + 8);
        np = llama_token_to_piece(v, t, buf.data(), (int32_t) buf.size(), 0, true);
        if (np < 0) return -1;
    }
    if (np > 0) acc.append(buf.data(), (size_t) np);
    return np;
}

// R9: top-k textual (id:logit) para a 1a divergencia numerica (sem dump de vocab)
static std::string topk_str(llama_context * c, const llama_vocab * vocab, int k, bool * finito) {
    float * lg = llama_get_logits(c);
    if (lg == nullptr) return "sem-logits";
    int nv = llama_vocab_n_tokens(vocab);
    std::vector<std::pair<float,int>> v;
    bool fin = true;
    for (int i = 0; i < nv; i++) {
        float x = lg[i];
        if (!std::isfinite(x)) { fin = false; continue; }
        v.push_back(std::make_pair(x, i));
    }
    *finito = fin;
    size_t n = (size_t) k < v.size() ? (size_t) k : v.size();
    std::partial_sort(v.begin(), v.begin() + n, v.end(),
                      [](const std::pair<float,int> & a, const std::pair<float,int> & b) { return a.first > b.first; });
    char b[64]; std::string o;
    for (size_t i = 0; i < n; i++) { snprintf(b, sizeof(b), "%d:%.4f ", v[i].second, v[i].first); o += b; }
    return o;
}

// R13: metricas COMPLETAS dos logits (vetor inteiro; sem dump de vocab):
// logsumexp estavel, top8, maxp, media (para erro centralizado). NaN => finite=0.
struct LogitStats { double lse; double maxp; double mean; bool finito; double top[8]; int topid[8]; };
static LogitStats logit_stats(llama_context * c, const llama_vocab * vocab) {
    LogitStats s; s.lse = 0; s.maxp = 0; s.mean = 0; s.finito = true;
    float * lg = llama_get_logits(c);
    int nv = (lg != nullptr) ? llama_vocab_n_tokens(vocab) : 0;
    if (nv <= 0) { s.finito = false; return s; }
    double mx = -1e300, sum = 0.0, mean = 0.0; int cnt = 0;
    for (int i = 0; i < nv; i++) { float x = lg[i]; if (!std::isfinite(x)) { s.finito = false; continue; } if (x > mx) mx = x; mean += x; cnt++; }
    if (cnt == 0) { s.finito = false; return s; }
    s.mean = mean / cnt;
    for (int i = 0; i < nv; i++) { float x = lg[i]; if (std::isfinite(x)) sum += std::exp((double) x - mx); }
    s.lse = mx + std::log(sum);
    s.maxp = std::exp((double) mx - s.lse);
    for (int k = 0; k < 8; k++) { s.top[k] = -1e300; s.topid[k] = -1; }
    for (int i = 0; i < nv; i++) {
        float x = lg[i]; if (!std::isfinite(x)) continue;
        for (int k = 0; k < 8; k++) {
            if (x > s.top[k]) {
                for (int j = 7; j > k; j--) { s.top[j] = s.top[j-1]; s.topid[j] = s.topid[j-1]; }
                s.top[k] = x; s.topid[k] = i;
                break;
            }
        }
    }
    return s;
}

// R13: erro centralizado max|(xd-xs) - (meand-means)| sobre o vetor COMPLETO
static double erro_centralizado(llama_context * cd, llama_context * cs, const llama_vocab * vocab) {
    float * a = llama_get_logits(cd);
    float * b = llama_get_logits(cs);
    int nv = (a && b) ? llama_vocab_n_tokens(vocab) : 0;
    if (nv <= 0) return -1;
    double ma = 0, mb = 0; int ca = 0, cb = 0;
    for (int i = 0; i < nv; i++) { if (std::isfinite(a[i])) { ma += a[i]; ca++; } if (std::isfinite(b[i])) { mb += b[i]; cb++; } }
    if (!ca || !cb) return -1;
    ma /= ca; mb /= cb;
    double err = 0;
    for (int i = 0; i < nv; i++) {
        if (std::isfinite(a[i]) && std::isfinite(b[i])) {
            double d = std::fabs(((double) a[i] - ma) - ((double) b[i] - mb));
            if (d > err) err = d;
        }
    }
    return err;
}

// margem top1-top2 dos logits do contexto (sem dump de vocab)
static double margem_top12(llama_context * c, const llama_vocab * vocab) {
    float * lg = llama_get_logits(c);
    if (lg == nullptr) return -1;
    int nv = llama_vocab_n_tokens(vocab);
    float t1 = -1e30f, t2 = -1e30f;
    for (int v = 0; v < nv; v++) {
        float x = lg[v];
        if (!std::isfinite(x)) continue;
        if (x > t1) { t2 = t1; t1 = x; } else if (x > t2) t2 = x;
    }
    return (double) (t1 - t2);
}

static BridgeResult run_bridge(llama_context * cs, llama_context * cd,
                               const llama_vocab * vocab,
                               const std::string & prompt, int cap,
                               const std::string & mut, bool bench) {
    BridgeResult r;
    r.bench = bench;
    std::vector<char> buf(512);
    std::vector<uint8_t> st;
    // samplers SEPARADOS (ordem R4-B); no benchmark so' o do destino e' usado.
    llama_sampler * smpl_s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl_s, llama_sampler_init_greedy());
    llama_sampler * smpl_d = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl_d, llama_sampler_init_greedy());
    auto cleanup = [&]() { llama_sampler_free(smpl_s); llama_sampler_free(smpl_d); };

    // (1) tokenize + prefill na origem
    std::vector<llama_token> toks(prompt.size() + 32);
    int nt = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                            toks.data(), (int32_t) toks.size(), false, true);
    if (nt <= 0) { r.erro = "tokenize"; cleanup(); return r; }
    toks.resize(nt);
    r.hash_prompt = p4v2::hash_prompt(prompt, toks);
    {   // R8: prefill em CHUNKS (nt>n_batch => lotes de 128; ultimo com logits);
        // posicoes corretas por lote; nunca decode oversize.
        double tf = now_ms();
        int rc = 0;
        int ci = 0;
        for (auto & cp : chunk_plan(nt, 128)) {   // R10: helper puro (fixture chunk_plan_test)
            llama_batch b = llama_batch_get_one(toks.data() + cp.first, cp.second);
            double tc = now_ms();
            rc = llama_decode(cs, b);
            rep("CHUNK[%d]: n=%d t=%.1fms rc=%d", ci++, cp.second, now_ms() - tc, rc);   // R11: por chunk
            if (rc != 0) break;   // erro em chunk intermediario: ABORTA (nunca aceita)
        }
        r.prefill_ms = now_ms() - tf;
        r.t_prefill_done = now_ms();
        if (mut == "smart_prefill_rc") rc = 1;
        if (rc != 0) { r.erro = "prefill_rc"; cleanup(); return r; }
    }

    // (2) export — contrato: tamanho EXATO reportado pela API
    {
        size_t sz = llama_state_get_size(cs);
        st.assign(sz ? sz : 1, 0);
        double te = now_ms();
        size_t got = llama_state_get_data(cs, st.data(), sz);
        r.export_ms = now_ms() - te;
        r.t_export_done = now_ms();
        if (mut == "smart_export_vazio") got = 0;
        if (mut == "smart_export_trunc") got = 4096;
        if (mut == "smart_export_trunc_grande") got = (sz > 70000) ? sz - 40 : 70000;
        if (got == 0 || got != sz) { r.erro = "export_invalido(tam!=contrato)"; cleanup(); return r; }
        st.resize(got);
        r.export_bytes = got;
    }

    // (3) tok1 = PRIMEIRO token de saida (amostrado UMA vez na origem)
    llama_token tok1 = llama_sampler_sample(smpl_s, cs, -1);
    r.n_amostrados_s++;
    if (mut == "smart_eog_tok1") tok1 = llama_vocab_eos(vocab);   // injecao p/ provar o gate
    r.tok1 = (int) tok1;
    if (llama_vocab_is_eog(vocab, tok1)) {   // sem handoff executado (ordem R4-B)
        r.eog_tok1 = true; r.stop = 3;
        r.erro = "EOG_tok1_sem_handoff";
        r.t_tok1_ready = now_ms(); r.t_fim = now_ms();
        cleanup(); return r;
    }
    {
        int tok1_emissoes = 0;
        if (mut != "smart_ponte_omitida") {
            if (emit_piece(vocab, tok1, r.texto, buf) < 0) { r.erro = "piece(tok1)"; cleanup(); return r; }
            tok1_emissoes++;
        }
        if (mut == "smart_ponte_dup") { emit_piece(vocab, tok1, r.texto, buf); tok1_emissoes++; }
        if (tok1_emissoes != 1) { r.erro = "ponte_tok1_emissoes!=1"; cleanup(); return r; }
        r.n_emitidos += tok1_emissoes;
    }
    r.t_tok1_ready = now_ms();   // pronto INTERNO (sem streaming de UI no probe)

    // (4) import no destino (gate rd == got)
    {
        double ti = now_ms();
        size_t rd = llama_state_set_data(cd, st.data(), st.size());
        r.import_ms = now_ms() - ti;
        r.t_import_done = now_ms();
        if (mut == "smart_import_falha" || rd != st.size()) { r.erro = "import_falhou"; cleanup(); return r; }
    }

    // (5) posicoes (gate)
    {
        llama_pos pmax = llama_memory_seq_pos_max(llama_get_memory(cd), 0);
        if (mut == "smart_pos_errada") pmax = 999;
        if ((int) pmax != nt - 1) { r.erro = "pos_errada"; cleanup(); return r; }
    }

    // (6) decode(tok1) no destino + geracao. cap TOTAL inclui a ponte (ordem R4-B)
    double tg = now_ms();
    llama_token tok = tok1;
    int max_passos = cap - 1;
    if (mut == "smart_cap1") max_passos = 0;
    if (mut == "smart_cap2") max_passos = 1;
    if (max_passos <= 0) { r.stop = 1; r.cap = true; }   // cap=1: so a ponte (estado terminal correto)
    for (int i = 0; i < max_passos; i++) {
        llama_batch b = llama_batch_get_one(&tok, 1);
        int rcD = llama_decode(cd, b);
        r.n_consumidos++;
        if (mut == "smart_decode_rc" && i == 0) rcD = 1;
        if (rcD != 0) { r.erro = "decode_rc"; r.stop = 2; r.t_fim = now_ms(); cleanup(); return r; }
        llama_token tokD = llama_sampler_sample(smpl_d, cd, -1);
        r.n_amostrados_d++;
        double marg_d = margem_top12(cd, vocab);
        r.marg_final_d = marg_d;   // ultimo passo ate agora (prova da coleta)
        if (r.t_tok2_ready == 0 && !llama_vocab_is_eog(vocab, tokD)) r.t_tok2_ready = now_ms();

        if (!bench) {
            // VALIDACAO: espelho teacher-forcing (mesmo token na origem)
            llama_batch bs = llama_batch_get_one(&tok, 1);
            int rcS = llama_decode(cs, bs);
            r.n_decodes_s_pos_ponte++;
            if (mut == "smart_espelho_rcs") rcS = 1;   // injecao p/ provar o gate
            if (rcS != 0) { r.erro = "espelho_rcS"; r.stop = 2; r.t_fim = now_ms(); cleanup(); return r; }
            llama_token tokS = llama_sampler_sample(smpl_s, cs, -1);
            r.n_amostrados_s++;
            double marg_s = margem_top12(cs, vocab);
            r.espelho_passos++;
            if (tokS == tokD) r.espelho_iguais++;
            else if (r.div_pos < 0) {
                r.div_pos = i + 1;
                r.marg_d = marg_d; r.marg_s = marg_s;
                bool fd = true, fs = true;
                r.div_topk_d = topk_str(cd, vocab, 5, &fd);
                r.div_topk_s = topk_str(cs, vocab, 5, &fs);
                r.div_finite = (fd && fs);
                rep("DIV_DET: pos=%d tokD=%d tokS=%d | topD=[%s] topS=[%s] | margemD=%.4f margemS=%.4f finite=%d",
                    i + 1, (int) tokD, (int) tokS, r.div_topk_d.c_str(), r.div_topk_s.c_str(),
                    marg_d, marg_s, (int) r.div_finite);
            }
        } else {
            // BENCHMARK: a origem NAO pode decodificar/amostrar pos-ponte (gate)
            if (mut == "smart_bench_origem_extra") {
                llama_batch bs = llama_batch_get_one(&tok, 1);
                if (llama_decode(cs, bs) == 0) { llama_sampler_sample(smpl_s, cs, -1); r.n_decodes_s_pos_ponte++; }
            }
            if (r.n_decodes_s_pos_ponte != 0) { r.erro = "bench_origem_decodificou"; r.stop = 2; r.t_fim = now_ms(); cleanup(); return r; }
        }
        if (llama_vocab_is_eog(vocab, tokD)) { r.stop = 0; break; }
        if (emit_piece(vocab, tokD, r.texto, buf) < 0) { r.erro = "piece"; r.t_fim = now_ms(); cleanup(); return r; }
        r.n_emitidos++;
        tok = tokD;
        if (i + 1 >= max_passos) { r.stop = 1; r.cap = true; }
    }
    if (mut == "smart_cap") { r.stop = 1; r.cap = true; }
    r.gen_ms = now_ms() - tg;
    r.t_fim = now_ms();
    cleanup();
    if (r.texto.empty()) { r.erro = "resposta_vazia"; return r; }
    r.ok = true;
    return r;
}

} // namespace smartv2

// ============================================================================
// SESSION MODE v2 (R6): contextos VIVOS; roles separados (src!=dst mesmo se
// mesmo backend -> roundtrip real com 2 ctxs); gates de stop/cap/div na
// sessao; reset de request cronometrado (incluido no campo proprio, nao no
// wall); lifecycle por request (load de modelo+ctx fora do wall, reportado);
// JSON NATIVO por request em arquivo do app (texto INTEGRAL, sem truncar):
// /data/data/br.gov.sp.pcsp.npuprobe/files/r6-sess.jsonl (pull por run-as).
// ============================================================================
namespace sessao {

struct CtxC { llama_model * m = nullptr; llama_context * c = nullptr; double load_ms = 0; bool usado = false; };

static std::string json_esc(const std::string & s) {
    std::string o; o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break; case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break; case '\r': o += "\\r"; break; case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) { char b[8]; snprintf(b, sizeof(b), "\\u%04x", c); o += b; }
                else o += (char) c;
        }
    }
    return o;
}

static FILE * g_json = nullptr;
static void json_abre() {
    if (g_json) { fclose(g_json); g_json = nullptr; }
    mkdir("/data/data/br.gov.sp.pcsp.npuprobe/files", 0700);   // garante o dir (ignore EEXIST)
    g_json = fopen("/data/data/br.gov.sp.pcsp.npuprobe/files/r6-sess.jsonl", "w");
}
static void json_req(int i, const std::string & sess_id, const std::string & rota, bool bench,
                     bool ok, bool cap, bool eog1, int stop, int tok1,
                     int n_emit, int n_cons, int dec_s,
                     double prefill_ms, double export_ms, size_t export_b, double import_ms,
                     double gen_ms, double wall_ms, double reset_ms,
                     double t_pref, double t_t1, double t_imp, double t_t2, double t_fim,
                     double load_dst, double load_src, const std::string & texto,
                     const std::string & resultado,
                     int div_pos = -1, double div_marg_d = -1.0, double div_marg_s = -1.0) {
    if (!g_json) return;
    fprintf(g_json,
        "{\"t\":\"req\",\"sess\":\"%s\",\"i\":%d,\"rota\":\"%s\",\"bench\":%s,\"ok\":%s,"
        "\"cap\":%s,\"eog_tok1\":%s,\"stop\":%d,\"tok1\":%d,\"n_emit\":%d,\"n_cons\":%d,\"dec_s\":%d,"
        "\"div_pos\":%d,\"div_marg_d\":%.4f,\"div_marg_s\":%.4f,"
        "\"prefill_ms\":%.3f,\"export_ms\":%.3f,\"export_b\":%zu,\"import_ms\":%.3f,\"gen_ms\":%.3f,"
        "\"wall_ms\":%.3f,\"reset_ms\":%.3f,"
        "\"t_prefdone\":%.3f,\"t_tok1r\":%.3f,\"t_impdone\":%.3f,\"t_tok2r\":%.3f,\"t_fim\":%.3f,"
        "\"load_dst_ms\":%.1f,\"load_src_ms\":%.1f,\"texto\":\"%s\",\"resultado\":\"%s\"}\n",
        sess_id.c_str(), i, rota.c_str(), bench ? "true" : "false", ok ? "true" : "false",
        cap ? "true" : "false", eog1 ? "true" : "false", stop, tok1, n_emit, n_cons, dec_s,
        div_pos, div_marg_d, div_marg_s,
        prefill_ms, export_ms, export_b, import_ms, gen_ms, wall_ms, reset_ms,
        t_pref, t_t1, t_imp, t_t2, t_fim, load_dst, load_src,
        json_esc(texto).c_str(), json_esc(resultado).c_str());
    fflush(g_json);
}

static const char * r30_vname(r30_verdict_t v) {
    switch (v) {
        case R30_LEGACY_PASS: return "LEGACY_PASS";
        case R30_LEGACY_FAIL: return "LEGACY_FAIL";
        case R30_PIN_PASS:    return "PIN_PASS";
        case R30_PIN_FAIL:    return "PIN_FAIL";
        default:              return "INVALID";
    }
}

static std::string run_sessao(const char * model_path, const char * libdir, const std::string & mut) {
    std::map<std::string, CtxC> cache;   // role:backend -> ctx (roles: dst|src)
    auto ctx_de = [&](const std::string & role, const std::string & nome) -> CtxC * {
        std::string key = role + ":" + nome;
        auto it = cache.find(key);
        if (it != cache.end()) { it->second.usado = true; return &it->second; }
        ggml_backend_dev_t d = (nome == "htp")    ? ggml_backend_dev_by_name("HTP0")
                             : (nome == "cpu")    ? ggml_backend_dev_by_name("CPU")
                             : (nome == "vulkan") ? ggml_backend_dev_by_name("Vulkan0")
                             : (nome == "opencl") ? ggml_backend_dev_by_name("GPUOpenCL")
                             : nullptr;
        if (d == nullptr) return nullptr;
        CtxC cc;
        ggml_backend_dev_t devs[2] = { d, nullptr };
        llama_model_params mp = llama_model_default_params();
        mp.devices = devs; mp.n_gpu_layers = 999;
        double t0 = now_ms();
        cc.m = llama_model_load_from_file(model_path, mp);
        if (cc.m == nullptr) return nullptr;
        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 128; cp.n_ubatch = 128; cp.n_threads = 4;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        cc.c = llama_init_from_model(cc.m, cp);
        cc.load_ms = now_ms() - t0;   // modelo+ctx (cold deste role)
        if (cc.c == nullptr) { llama_model_free(cc.m); return nullptr; }
        cc.usado = true;
        cache[key] = cc;
        return &cache[key];
    };

    std::string lista = read_arquivo_txt("/data/local/tmp/p4-session.txt", 8192);
    std::vector<std::string> linhas;
    { std::string cur;
      for (char ch : lista) { if (ch == '\n') { if (!cur.empty()) linhas.push_back(cur); cur.clear(); } else if (ch != '\r' && ch != ' ') cur += ch; }
      if (!cur.empty()) linhas.push_back(cur); }
    char sid[32]; snprintf(sid, sizeof(sid), "%llx", (unsigned long long) (now_ms()));
    std::string sess_id(sid);
    json_abre();
    rep("SESSAO(%s): %zu requests na mesma sessao (roles separados; JSON nativo em files/r6-sess.jsonl)", sess_id.c_str(), linhas.size());

    std::string prompt_default = std::string("<｜hy_begin▁of▁sentence｜><｜hy_User｜>") +
        "Translate the following text into Portuguese. Note that you should only output " +
        "the translated result without any additional explanation:\n\n" +
        "Good morning, how are you?" + "<｜hy_Assistant｜>";
    { std::string pf = read_arquivo_txt("/data/local/tmp/p4-prompt.txt", 65536); if (!pf.empty()) prompt_default = pf; }
    int CAP = 48;
    { std::string cf = read_arquivo_txt("/data/local/tmp/p4-cap.txt", 16);
      if (!cf.empty()) { int v = atoi(cf.c_str()); if (v >= 1 && v <= 2048) CAP = v; } }

    for (size_t idx = 0; idx < linhas.size(); idx++) {
        std::string rota_full = linhas[idx];
        // R9: A-B-A real — prompt POR REQUEST (p4-prompt-<idx>.txt; fallback comum)
        std::string prompt = prompt_default;
        { char pf[64]; snprintf(pf, sizeof(pf), "/data/local/tmp/p4-prompt-%zu.txt", idx);
          std::string pp = read_arquivo_txt(pf, 65536);
          if (!pp.empty()) { prompt = pp; rep("PROMPT[%zu]: arquivo proprio (len=%zu)", idx, pp.size()); } }
        bool bench = false;
        std::string rota = rota_full;
        if (rota.size() > 6 && rota.rfind("@bench") == rota.size() - 6) { bench = true; rota = rota.substr(0, rota.size() - 6); }
        bool puro = false; std::string src = "htp", dst;
        { std::vector<std::string> pt; std::string cur;
          for (char c : rota) { if (c == ':') { pt.push_back(cur); cur.clear(); } else cur += c; }
          pt.push_back(cur);
          if (pt.size() >= 3 && pt[0] == "numeric") { src = pt[1]; dst = pt[2]; }   // R13: numeric:src:dst[:ref]
          else if (pt.size() == 2 && pt[0] == "puro") { puro = true; dst = pt[1]; }
          else if (pt.size() == 1) dst = pt[0];
          else if (pt.size() == 2) { src = pt[0]; dst = pt[1]; }
          else { rep("SESS[%zu] rota invalida '%s' -> FALHA", idx, rota_full.c_str());
                 json_req((int) idx, sess_id, rota_full, false, false, false, false, 2, -1, 0, 0, 0,
                          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "rota_invalida"); continue; } }

        // ================================================================
        // R19: helper — obtem o REPACK buft via extensao do registry (fonte
        // do cohort CONFIRMADO: repack_buffer_type + get_extra_bufts sob
        // opt_hostbuf=1 default). NAO usar registry CPU nem nome fixo!
        // ================================================================
        auto get_repack_buft = [&](ggml_backend_dev_t dv) -> ggml_backend_buffer_type_t {
            if (!dv) return nullptr;
            ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dv);
            if (!reg) { rep("MM: dev_backend_reg NULL"); return nullptr; }
            auto fct = (ggml_backend_dev_get_extra_bufts_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_dev_get_extra_bufts");
            if (!fct) { rep("MM: get_extra_bufts NAO exportado (opt_hostbuf off?)"); return nullptr; }
            ggml_backend_buffer_type_t * bs = fct(dv);
            ggml_backend_buffer_type_t rp = nullptr;
            for (int i = 0; bs && bs[i]; i++) {
                const char * nm = ggml_backend_buft_name(bs[i]);
                rep("MM: extra buft[%d] = %s", i, nm ? nm : "?");
                if (nm && (strstr(nm, "REPACK") || strstr(nm, "repack"))) rp = bs[i];
            }
            return rp;
        };

        // ================================================================
        // R17: MMTER v2 — harness de matmul corrigido (ordens §2/§3):
        //  * supports_op ANTES do compute (unsupported => SKIP, NUNCA
        //    compute+zeros contado como resultado!)
        //  * ref via BACKEND CPU REAL (mesmo grafo) + manual secundaria
        //    indexada pelos strides nb REAIS (layout b*N+n!)
        //  * controles POSITIVOS (F32/F16/Q4_0/Q8_0) devem PASSAR
        //  * tolerancia combinada abs_tol + rel_tol*|ref|; metricas max_abs/
        //    nrmse; finite em output E ref; status por caso (PASS/SKIP/FAIL)
        //  * shapes MINIMOS primeiro (256x64 B=1/2/128) + c4bf
        // rota: "mmtest:all|min|q4k|q6k|ctrl|fx"
        // ================================================================
        if (rota.rfind("mmtest", 0) == 0) {
            long compute_calls = 0;   // R20: contador para o gate nativo (post=0 => 0!)
            std::string sub = rota.size() > 7 ? rota.substr(7) : "all";
            if (sub.empty()) sub = "all";
            ggml_backend_t bcpu = ggml_backend_init_by_name("CPU", nullptr);
            ggml_backend_t bhtp = ggml_backend_init_by_name("HTP0", nullptr);
            rep("MM: backends cpu=%p htp=%p", (void *) bcpu, (void *) bhtp);
            if (!bhtp) { rep("MM: HTP0 indisponivel -> FALHA"); log_flush(); continue; }
            ggml_backend_dev_t dev_h = ggml_backend_get_device(bhtp);
            ggml_backend_dev_t dev_c = bcpu ? ggml_backend_get_device(bcpu) : nullptr;

            struct TY { ggml_type ty; const char * nm; bool ctrl; };
            TY tipos[] = {
                { GGML_TYPE_F32,  "f32",  true  },
                // F16 REMOVIDO (R17): trava silenciosamente no HTP mesmo no
                // baseline v12 (incidente separado, 2 runs consistentes) — NAO
                // esta no escopo A-D do especialista ("F32 OU F16")!
                { GGML_TYPE_Q4_0, "q4_0", true  },
                { GGML_TYPE_Q8_0, "q8_0", true  },
                { GGML_TYPE_Q4_K, "q4_K", false },
                { GGML_TYPE_Q6_K, "q6_K", false },
            };
            struct CS { int K, N, B; };
            CS casos_min[] = { { 256, 64, 1 }, { 256, 64, 2 }, { 256, 64, 128 } };
            CS casos_c4[]  = { { 2048, 512, 1 }, { 2048, 2048, 128 }, { 6144, 2048, 1 } };

            auto trace2_any = [](bool x) { return x; };
            int pass = 0, fail = 0, skip = 0;
            ggml_backend_buffer_type_t buft_repack = get_repack_buft(dev_h);
            rep("MM: repack buft = %s", buft_repack ? ggml_backend_buft_name(buft_repack) : "NULO");
            auto roda_caso = [&](int K, int N, int B, TY ty, bool trace) {
                bool is_q = !(ty.ty == GGML_TYPE_F32 || ty.ty == GGML_TYPE_F16);
                // pesos sinteticos -> bytes quantizados CANONICOS (uma vez!)
                std::vector<float> wsrc((size_t) K * N);
                for (size_t i = 0; i < wsrc.size(); i++) wsrc[i] = (float) (std::sin((double) i * 0.017) * 1.7);
                std::vector<uint8_t> wq;
                const void * wsrc_p = wsrc.data(); size_t wbytes = wsrc.size() * sizeof(float);
                if (is_q) {
                    size_t row_sz = ggml_row_size(ty.ty, K);
                    wq.resize(row_sz * N);
                    size_t got = ggml_quantize_chunk(ty.ty, wsrc.data(), wq.data(), 0, N, K, nullptr);
                    if (got != wq.size()) { rep("MM[K=%d N=%d B=%d %s] qchunk=%zu esp=%zu CPU_REF_FAIL", K, N, B, ty.nm, got, wq.size()); fail++; return; }
                    wsrc_p = wq.data(); wbytes = wq.size();
                }
                std::vector<float> act((size_t) K * B);
                for (size_t i = 0; i < act.size(); i++) act[i] = (float) (std::cos((double) i * 0.013) * 0.8);
                // ---- supports_pre (grafo descartavel) ----
                bool sup_pre = false;
                {
                    struct ggml_init_params ip = { ggml_tensor_overhead() * 4, NULL, true };
                    struct ggml_context * c3 = ggml_init(ip);
                    struct ggml_tensor * wa = ggml_new_tensor_2d(c3, ty.ty, K, N);
                    struct ggml_tensor * ab = ggml_new_tensor_2d(c3, GGML_TYPE_F32, K, B);
                    struct ggml_tensor * cc = ggml_mul_mat(c3, wa, ab);
                    sup_pre = ggml_backend_dev_supports_op(dev_h, cc);
                    ggml_free(c3);
                }
                if (trace) rep("MM trace: K=%d N=%d B=%d %s sup_pre=%d repack=%d", K, N, B, ty.nm, (int) sup_pre, (int)(buft_repack != nullptr));
                if (!sup_pre) { rep("MM[K=%d N=%d B=%d %s] SKIP (UNSUPPORTED_PRE)", K, N, B, ty.nm); if (ty.ctrl) fail++; else skip++; return; }
                // ---- execucao com REPACK para os pesos (HTP) ----
                auto build_e_run = [&](ggml_backend_t bk, bool is_htp, std::vector<float> & out, size_t & nb0, size_t & nb1, int & rc, int & sup_post, long & unwritten, bool trace2) -> bool {
                    struct ggml_init_params ipw = { ggml_tensor_overhead() * 4, NULL, true };
                    struct ggml_context * cw = ggml_init(ipw);
                    struct ggml_tensor * wa = ggml_new_tensor_2d(cw, ty.ty, K, N);
                    struct ggml_init_params ipc = { ggml_tensor_overhead() * 8 + ggml_graph_overhead(), NULL, true };
                    struct ggml_context * ca = ggml_init(ipc);
                    struct ggml_tensor * ab = ggml_new_tensor_2d(ca, GGML_TYPE_F32, K, B);
                    struct ggml_tensor * cc = ggml_mul_mat(ca, wa, ab);
                    nb0 = cc->nb[0]; nb1 = cc->nb[1];
                    // R22 ALLOC_FAIL fail-closed (§3): REPACK selecionado QUE FALHA
                    // => ALLOC_FAIL explicito (NUNCA fallback silencioso ao default!)
                    ggml_backend_buffer_t bw = nullptr;
                    size_t req_alloc = 0, got_alloc = 0;
                    if (is_htp && buft_repack) {
                        // R34: A SEAM COMPARTILHADA (flow_seam.h!) — a MESMA compilada pela fixture!
                        // Os callbacks DELEGAM as chamadas ggml REAIS (nenhuma API inventada!)
                        flow_callbacks_t fcb;
                        fcb.alloc_repack = [](void * c, void * b) -> void * {
                            return (void *) ggml_backend_alloc_ctx_tensors_from_buft(
                                (struct ggml_context *) c, (ggml_backend_buffer_type_t) b);
                        };
                        fcb.alloc_size = [](void * c, void * b) -> size_t {
                            return ggml_backend_alloc_ctx_tensors_from_buft_size(
                                (struct ggml_context *) c, (ggml_backend_buffer_type_t) b);
                        };
                        fcb.free_ctx = [](void * c) { ggml_free((struct ggml_context *) c); };
                        flow_result_t fres;
                        flow_alloc_preflight(&fcb, cw, ca, (void *) buft_repack, is_htp ? 1 : 0, &fres);
                        if (fres.rc == -996) {
                            // o CONSUMO do resultado da seam (cleanup ja' feito PELA seam!)
                            rep("MM[%s] ALLOC_FAIL (REPACK selecionado falhou — seam!)", ty.nm);
                            rc = fres.rc;
                            return false;
                        }
                        bw = (ggml_backend_buffer_t) fres.bw;
                        req_alloc = ggml_backend_alloc_ctx_tensors_from_buft_size(cw, buft_repack);
                        got_alloc = req_alloc;
                    } else if (is_htp) {
                        rep("MM[%s] AVISO: buft_repack indisponivel (modo sem repack!)", ty.nm);
                        bw = ggml_backend_alloc_ctx_tensors(cw, bk);
                        if (!bw) { rc = -996; ggml_free(cw); ggml_free(ca); return false; }
                    } else {
                        bw = ggml_backend_alloc_ctx_tensors(cw, bk);
                        if (!bw) { rc = -996; ggml_free(cw); ggml_free(ca); return false; }
                    }
                    ggml_backend_buffer_t ba = ggml_backend_alloc_ctx_tensors(ca, bk);
                    if (!ba) { rc = -996; ggml_backend_buffer_free(bw); ggml_free(cw); ggml_free(ca); return false; }
                    // R22 instrumentacao host (§4/§5): ranges/bytes/strides REAIS!
                    {
                        size_t wb = bw ? ggml_backend_buffer_get_size(bw) : 0;
                        size_t ab = ba ? ggml_backend_buffer_get_size(ba) : 0;
                        rep("MMHOST %s ne=[%lld,%lld,%lld,%lld] nb0=%zu nb1=%zu wexp=%zu req=%zu walloc=%zu aalloc=%zu",
                            ty.nm, (long long) cc->ne[0], (long long) cc->ne[1], (long long) cc->ne[2], (long long) cc->ne[3],
                            nb0, nb1, wbytes, req_alloc, wb, ab);
                    }
                    // supports_post — MESMO cc, POS-alocacao!
                    sup_post = ggml_backend_dev_supports_op(is_htp ? dev_h : dev_c, cc) ? 1 : 0;
                    if (trace2) {
                        ggml_backend_buffer_type_t bwt = ggml_backend_buffer_get_type(bw);
                        ggml_backend_buffer_type_t bat = ggml_backend_buffer_get_type(ba);
                        rep("MM trace: alloc ok bw=%s ba=%s sup_post=%d", bwt ? ggml_backend_buft_name(bwt) : "?", bat ? ggml_backend_buft_name(bat) : "?", sup_post);
                    }
                    // R21: SEAM DE FLUXO REAL (gate_preflight!) — decide ANTES
                    // de upload/sentinel/compute; o teste nativo chama a MESMA
                    // funcao com callbacks de I/O contados.
                    {
                        GatePreflight pf = gate_preflight(1, 1, 0, sup_post);
                        if (pf != PRE_GO) {
                            rc = -997;   // sentinel de rejeicao
                            ggml_backend_buffer_free(bw);
                            ggml_backend_buffer_free(ba);
                            ggml_free(cw);
                            ggml_free(ca);
                            return false;
                        }
                    }
                    // upload
                    rep("MMHOST-PESO wa: nbytes=%zu wbytes=%zu nb1=%zu canon=%zu ne=%lldx%lld",
                        (size_t) ggml_nbytes(wa), wbytes, (size_t) wa->nb[1],
                        (size_t)(ggml_row_size(ty.ty, K) * N), (long long) wa->ne[0], (long long) wa->ne[1]);
                    ggml_backend_tensor_set(wa, wsrc_p, 0, wbytes);
                    ggml_backend_tensor_set(ab, act.data(), 0, act.size() * sizeof(float));
                    // sentinel no output (valor improvavel; contagem de nao-escritos!)
                    size_t out_n = (size_t) ggml_nelements(cc);
                    out.assign(out_n, 0.0f);
                    std::vector<float> snt(out_n, 1.2345678e33f);
                    ggml_backend_tensor_set(cc, snt.data(), 0, out_n * sizeof(float));
                    struct ggml_cgraph * gr = ggml_new_graph(ca);
                    ggml_build_forward_expand(gr, cc);
                    compute_calls++;   // R20: contagem para o gate nativo
                    rc = ggml_backend_graph_compute(bk, gr);
                    if (rc == GGML_STATUS_SUCCESS) ggml_backend_tensor_get(cc, out.data(), 0, out_n * sizeof(float));
                    unwritten = 0;
                    for (size_t i = 0; i < out_n; i++) if (out[i] == 1.2345678e33f) unwritten++;
                    ggml_backend_buffer_free(bw);
                    ggml_backend_buffer_free(ba);
                    ggml_free(cw);
                    ggml_free(ca);
                    return rc == GGML_STATUS_SUCCESS;
                };
                // (a) CPU ref PRIMEIRO (finita antes do HTP!)
                std::vector<float> refb; size_t rnb0 = 0, rnb1 = 0; int rrc = 0, rsp = 0; long runw = 0;
                bool ok_cpu = bcpu ? build_e_run(bcpu, false, refb, rnb0, rnb1, rrc, rsp, runw, false) : false;
                long ref_nf = 0; double sref2 = 0;
                if (ok_cpu) for (float v : refb) { if (!std::isfinite(v)) ref_nf++; else sref2 += (double) v * v; }
                if (!ok_cpu || ref_nf > 0) { rep("MM[K=%d N=%d B=%d %s] CPU_REF_FAIL (rrc=%d nf=%ld)", K, N, B, ty.nm, rrc, ref_nf);
                    rep("MM verdict_legacy=INVALID verdict_pin=INVALID pin_valid=0 (motivo=cpu_ref!)"); fail++; return; }
                // (b) HTP
                std::vector<float> hot; size_t hnb0 = 0, hnb1 = 0; int hrc = 0, hsp = 0; long hunw = 0;
                bool ok_h = build_e_run(bhtp, true, hot, hnb0, hnb1, hrc, hsp, hunw, trace || trace2_any(trace));
                if (!hsp) { rep("MM[K=%d N=%d B=%d %s] REJECTED_BUFFER (sup_pre=1 sup_post=0!)", K, N, B, ty.nm);
                    rep("MM verdict_legacy=INVALID verdict_pin=INVALID pin_valid=0 (motivo=rejected!)"); fail++; return; }
                if (!ok_h) { rep("MM[K=%d N=%d B=%d %s] EXEC_FAIL (hrc=%d)", K, N, B, ty.nm, hrc);
                    rep("MM verdict_legacy=INVALID verdict_pin=INVALID pin_valid=0 (motivo=exec!)"); fail++; return; }
                if (hunw > 0) { rep("MM[K=%d N=%d B=%d %s] UNWRITTEN_OUTPUT (%ld/%zu!)", K, N, B, ty.nm, hunw, hot.size());
                    rep("MM verdict_legacy=INVALID verdict_pin=INVALID pin_valid=0 (motivo=unwritten!)"); fail++; return; }
                // (c) metricas completas: nonfinite por lado + first + tolerancia
                long out_nf = 0, nd = 0, novl = 0;
                double max_abs = 0, sum2 = 0, max_viol = 0;   // R27: sum2 = soma(erro^2) (NMSE pinado = sum2/sref2!)
                const double ABS_TOL = 2e-2, REL_TOL = 5e-2;
                bool first_nf_done = false, first_viol_done = false;
                for (int b = 0; b < B; b++) for (int n = 0; n < N; n++) {
                    float rv = *(const float *) ((const char *) refb.data() + (size_t) b * rnb1 + (size_t) n * rnb0);
                    float hv = *(const float *) ((const char *) hot.data()  + (size_t) b * hnb1 + (size_t) n * hnb0);
                    if (!std::isfinite(hv)) { out_nf++; if (!first_nf_done) { first_nf_done = true; unsigned bits; memcpy(&bits, &hv, 4); rep("MM first_nonfinite HTP b=%d n=%d bits=0x%08x", b, n, bits); } continue; }
                    double d = std::fabs((double) hv - rv);
                    double lim = ABS_TOL + REL_TOL * std::fabs((double) rv);
                    if (d > max_abs) max_abs = d;
                    sum2 += d * d;
                    nd++;
                    if (d > lim) { novl++; if (d - lim > max_viol) max_viol = d - lim;
                        if (!first_viol_done) { first_viol_done = true; rep("MM first_viol b=%d n=%d htp=%.6f ref=%.6f lim=%.6f", b, n, hv, rv, lim); }
                        // R24: exporta TODOS os violadores (ate 20) com valores completos
                        if (novl <= 20) rep("MMVIOL #%ld b=%d n=%d htp=%.9f ref=%.9f abs=%.9f lim=%.9f exc=%.9f", novl, b, n, hv, rv, d, lim, d - lim); }
                }
                double nrmse = (nd > 0 && sref2 > 0) ? std::sqrt(sum2 / sref2) : -1;
                bool okc = (out_nf == 0) && (novl == 0) && (nd == (long) N * B);
                if (okc) pass++; else fail++;
                double nmse_pin = (sref2 > 0 && nd > 0) ? sum2 / sref2 : -1.0;   // R27: NMSE do pin = soma((a-b)^2)/soma(a^2)! = nrmse^2!
                // R31: O GATE PIN COMPARTILHADO (a MESMA r30_gate da fixture!) com os dados da MESMA tentativa!
                r30_inputs_t gin; memset(&gin, 0, sizeof(gin));
                gin.exec_ok   = ok_h ? 1 : 0;
                gin.alloc_ok  = hsp ? 1 : 0;
                gin.sync_ok   = (hunw == 0) ? 1 : 0;
                gin.ref_ok    = ok_cpu ? 1 : 0;
                gin.nd        = nd; gin.n_expected = (long) N * B;
                gin.nf_ref    = ref_nf; gin.nf_out = out_nf; gin.n_unwritten = hunw;
                gin.sum_err2  = sum2; gin.sum_ref2 = sref2;
                gin.mx_abs    = max_abs; gin.novl = novl;
                r30_result_t g30 = r30_gate(&gin);
                rep("MM verdict_legacy=%s verdict_pin=%s pin_valid=%d nmse_full=%.9e (nd=%ld/%ld nf=%ld/%ld unw=%ld novl=%ld)",
                    r30_vname(g30.legacy), r30_vname(g30.pin), g30.pin_valid, g30.nmse_pin,
                    gin.nd, gin.n_expected, gin.nf_ref, gin.nf_out, gin.n_unwritten, gin.novl);
                rep("MM[K=%d N=%d B=%d %s] hrc=%d pre=%d post=%d nf_ref=%ld nf_out=%ld nd=%ld/%ld max_abs=%.5f nrmse=%.5f nmse_pin=%.6e viol=%ld %s",
                    K, N, B, ty.nm, hrc, (int) sup_pre, hsp, ref_nf, out_nf, nd, (long) N * B, max_abs, nrmse, nmse_pin, novl, okc ? "PASS" : "FAIL");
            };
            for (auto & cs : casos_min) {
                for (auto & ty : tipos) {
                    if (sub == "ctrl" && !ty.ctrl) continue;
                    if (sub == "q4k" && ty.ty != GGML_TYPE_Q4_K) continue;
                    if (sub == "q6k" && ty.ty != GGML_TYPE_Q6_K) continue;
                    roda_caso(cs.K, cs.N, cs.B, ty, true);   // R17: trace ligado (sup_h visivel!)
                }
            }
            if (sub == "all" || sub == "c4") {
                for (auto & cs : casos_c4) {
                    for (auto & ty : tipos) roda_caso(cs.K, cs.N, cs.B, ty, false);
                }
            }
            // rejeicao Q5_K (esperado unsupported)
            {
                struct ggml_init_params ip = { ggml_tensor_overhead() * 8 + ggml_graph_overhead(), NULL, true };
                struct ggml_context * c3 = ggml_init(ip);
                struct ggml_tensor * wa = ggml_new_tensor_2d(c3, GGML_TYPE_Q5_K, 256, 64);
                struct ggml_tensor * ab = ggml_new_tensor_2d(c3, GGML_TYPE_F32, 256, 1);
                struct ggml_tensor * cc = ggml_mul_mat(c3, wa, ab);
                bool sup = ggml_backend_dev_supports_op(dev_h, cc);
                rep("MM rejeicao Q5_K: htp supports=%d (esperado 0)", (int) sup);
                if (!sup) pass++; else fail++;
                ggml_free(c3);
            }
            rep("MMTOTAL pass=%d fail=%d skip=%d compute_calls=%ld", pass, fail, skip, compute_calls);
            log_flush();
            if (bcpu) ggml_backend_free(bcpu);
            ggml_backend_free(bhtp);
            continue;
        }

        // ================================================================
        // R13: modo NUMERIC "numeric:<src>:<dst>" (ref=CPU): teacher forcing
        // 3-way; checkpoints com metricas COMPLETAS dos logits (lse/top8/
        // maxp/erro centralizado/finite) — a coleta real do DESIGN-NUMERICO.
        // ================================================================
        if (rota.rfind("numeric:", 0) == 0) {
            std::string resto = rota.substr(8);
            std::string nsrc = "htp", ndst = resto, nref = "cpu", ndst2 = "";
            std::vector<std::string> np;
            { size_t p0 = 0; while (p0 <= resto.size()) { size_t pc = resto.find(':', p0); if (pc == std::string::npos) { np.push_back(resto.substr(p0)); break; } np.push_back(resto.substr(p0, pc - p0)); p0 = pc + 1; } }
            if (np.size() >= 1) ndst = np[0];                      // numeric:DST
            if (np.size() >= 2) { nsrc = np[0]; ndst = np[1]; }    // numeric:SRC:DST
            if (np.size() >= 3) nref = np[2];                      // numeric:SRC:DST:REF
            if (np.size() >= 4) ndst2 = np[3];                     // + DST2 (puro, sem import!)
            CtxC * c_src = ctx_de("src", nsrc);
            CtxC * c_dst = ctx_de("dst", ndst);
            CtxC * c_ref = ctx_de("ref", nref);
            CtxC * c_dst2 = ndst2.empty() ? nullptr : ctx_de("dst2", ndst2);
            // R15 (ordens §4): flags de falha estrutural — nada de PASS parcial!
            bool algum_invalido = false, decode_fail = false, ref_falhou = false, eog_stop = false;
            int n_invalidos = 0;
            // envelope calibrado lido de /data/local/tmp/p4-envelope.txt (env_imp=... + calibration_id=...)
            double env_calib = -1.0; char calib_id[64] = "";
            { FILE * fe = fopen("/data/local/tmp/p4-envelope.txt", "r");
              if (fe) { char ln2[160];
                while (fgets(ln2, sizeof(ln2), fe)) {
                    if (sscanf(ln2, "env_imp=%lf", &env_calib) == 1) continue;
                    char cid[64];
                    if (sscanf(ln2, "calibration_id=%63s", cid) == 1) { snprintf(calib_id, sizeof(calib_id), "%s", cid); }
                }
                fclose(fe); } }
            if (!c_src || !c_dst || !c_ref) {
                rep("NUM: FALHA (ctx src/dst/ref indisponivel)");
                json_req((int) idx, sess_id, rota_full, false, false, false, false, 2, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "numeric_ctx_falta");
                continue;
            }
            const llama_vocab * vocab = llama_model_get_vocab(c_src->m);
            llama_memory_clear(llama_get_memory(c_src->c), true);
            llama_memory_clear(llama_get_memory(c_dst->c), true);
            llama_memory_clear(llama_get_memory(c_ref->c), true);
            // tokenize + prefill nos 3 (chunked; o ULTIMO lote pos-prefill = checkpoint 0)
            std::vector<llama_token> toks(prompt.size() + 32);
            int nt = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), false, true);
            if (nt <= 0) { rep("NUM: tokenize falhou"); continue; }
            toks.resize(nt);
            int rc_any = 0;
            for (llama_context * cx : { c_src->c, c_dst->c, c_ref->c }) {
                for (auto & cp : chunk_plan(nt, 128)) {
                    llama_batch b = llama_batch_get_one(toks.data() + cp.first, cp.second);
                    rc_any = llama_decode(cx, b);
                    if (rc_any != 0) break;
                }
                if (rc_any != 0) break;
            }
            if (rc_any != 0) { rep("NUM: prefill rc!=0"); json_req((int) idx, sess_id, rota_full, false, false, false, false, 2, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "numeric_prefill_rc"); continue; }
            // dt2 (GPU-puro): MESMO prefixo, SEM import — o controle do §3 (R14)
            if (c_dst2) {
                llama_memory_clear(llama_get_memory(c_dst2->c), true);
                for (auto & cp : chunk_plan(nt, 128)) {
                    llama_batch b2 = llama_batch_get_one(toks.data() + cp.first, cp.second);
                    rc_any = llama_decode(c_dst2->c, b2);
                    if (rc_any != 0) break;
                }
                if (rc_any != 0) { rep("NUM: prefill dst2 rc!=0 (FAIL reference)"); c_dst2 = nullptr; ref_falhou = true; }
            }
            // export src -> import dst (o handoff real!)
            {
                size_t sz = llama_state_get_size(c_src->c);
                std::vector<uint8_t> st(sz ? sz : 1, 0);
                size_t got = llama_state_get_data(c_src->c, st.data(), sz);
                size_t rd = (got == sz) ? llama_state_set_data(c_dst->c, st.data(), sz) : 0;
                if (got != sz || rd != sz) { rep("NUM: handoff falhou (got=%zu rd=%zu sz=%zu)", got, rd, sz); json_req((int) idx, sess_id, rota_full, false, false, false, false, 2, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "numeric_handoff"); continue; }
            }
            // samplers separados
            llama_sampler * sm_s = llama_sampler_chain_init(llama_sampler_chain_default_params());
            llama_sampler_chain_add(sm_s, llama_sampler_init_greedy());
            llama_sampler * sm_d = llama_sampler_chain_init(llama_sampler_chain_default_params());
            llama_sampler_chain_add(sm_d, llama_sampler_init_greedy());
            llama_sampler * sm_r = llama_sampler_chain_init(llama_sampler_chain_default_params());
            llama_sampler_chain_add(sm_r, llama_sampler_init_greedy());
            const int CHK[] = { 0, 1, 8, 32, 64, 128, 192, 255, 300 };
            const int NCHK = 9;
            double err_dr_max = 0, err_sr_max = 0, err_ds_max = 0, err_dr_mean = 0, err_sr_mean = 0, err_ds_mean = 0;
            double err_imp_max = 0, err_imp_mean = 0; int nchk = 0, divs_ds = 0;
            double lse_d0 = 0, lse_s0 = 0, lse_r0 = 0;
            llama_token tok = llama_sampler_sample(sm_s, c_src->c, -1);   // tok1 da origem (o ponte)
            int nout = 0; std::string texto; std::vector<char> piece(512);
            for (int i = 0; i < CAP; i++) {
                llama_batch bs = llama_batch_get_one(&tok, 1);
                int r1 = llama_decode(c_src->c, bs);
                int r2 = llama_decode(c_dst->c, bs);
                int r3 = llama_decode(c_ref->c, bs);
                int r4 = c_dst2 ? llama_decode(c_dst2->c, bs) : 0;
                if (mut == "smart_perturba" && i == 4) {   // R13/R15: mutante de perturbacao (pos 4: dentro do EOG curto!)
                    float * lg = llama_get_logits(c_dst->c);
                    if (lg) lg[0] += 50.0f;
                }
                if (mut == "smart_offset" && i == 4) {     // R15: offset global => metricas invariantes (pos 4)
                    float * lg = llama_get_logits(c_dst->c);
                    int nv2 = (int) llama_vocab_n_tokens(vocab);
                    if (lg) for (int k = 0; k < nv2; k++) lg[k] += 1000.0f;
                }
                if (r1 || r2 || r3 || r4) { decode_fail = true; rep("NUM: decode rc no passo %d (r1=%d r2=%d r3=%d r4=%d) => FAIL(decode)", i, r1, r2, r3, r4); break; }
                llama_token td = llama_sampler_sample(sm_d, c_dst->c, -1);
                llama_token tr = llama_sampler_sample(sm_r, c_ref->c, -1);
                try {
                    smartv2::LogitStats sd = smartv2::logit_stats(c_dst->c, vocab);
                    smartv2::LogitStats ss = smartv2::logit_stats(c_src->c, vocab);
                    smartv2::LogitStats sr = smartv2::logit_stats(c_ref->c, vocab);
                    double edr = smartv2::erro_centralizado(c_dst->c, c_ref->c, vocab);
                    double esr = smartv2::erro_centralizado(c_src->c, c_ref->c, vocab);
                    double eds = smartv2::erro_centralizado(c_dst->c, c_src->c, vocab);
                    double e_imp = c_dst2 ? smartv2::erro_centralizado(c_dst->c, c_dst2->c, vocab) : -1;   // R14: import vs GPU-puro!
                    bool is_chk = false;
                    for (int k = 0; k < NCHK; k++) if (CHK[k] == i) is_chk = true;
                    if (is_chk) {
                        if (i == 0) { lse_d0 = sd.lse; lse_s0 = ss.lse; lse_r0 = sr.lse; }
                        char top8[256]; top8[0] = 0; char cur[32];
                        for (int k = 0; k < 8; k++) { snprintf(cur, sizeof(cur), "%d:%.3f ", sd.topid[k], sd.top[k]); strncat(top8, cur, sizeof(top8) - strlen(top8) - 1); }
                        rep("NUMCHK pos=%d tok_dst=%d tok_ref=%d | lse_d=%.4f lse_s=%.4f lse_r=%.4f | maxp_d=%.5f | e_dr=%.4f e_sr=%.4f e_ds=%.4f | fin=%d%d%d | top8_d=[%s]",
                            i, (int) td, (int) tr, sd.lse, ss.lse, sr.lse, sd.maxp, edr, esr, eds,
                            (int) sd.finito, (int) ss.finito, (int) sr.finito, top8);
                    }
                    if (!sd.finito || !ss.finito || !sr.finito) { algum_invalido = true; n_invalidos++; }
                    if (std::isfinite(edr) && edr >= 0) { if (edr > err_dr_max) err_dr_max = edr; err_dr_mean += edr; }
                    if (std::isfinite(esr) && esr >= 0) { if (esr > err_sr_max) err_sr_max = esr; err_sr_mean += esr; }
                    if (std::isfinite(eds) && eds >= 0) { if (eds > err_ds_max) err_ds_max = eds; err_ds_mean += eds; }
                    if (std::isfinite(e_imp) && e_imp >= 0) { if (e_imp > err_imp_max) err_imp_max = e_imp; err_imp_mean += e_imp; }
                    nchk++;
                    if (td != tr) divs_ds++;
                } catch (...) { rep("NUM: excecao na coleta (passo %d)", i); break; }
                if (llama_vocab_is_eog(vocab, td)) { eog_stop = true; break; }
                if (smartv2::emit_piece(vocab, td, texto, piece) < 0) break;
                nout++;
                tok = td;
            }
            llama_sampler_free(sm_s); llama_sampler_free(sm_d); llama_sampler_free(sm_r);
            if (nchk > 0) { err_dr_mean /= nchk; err_sr_mean /= nchk; err_ds_mean /= nchk; err_imp_mean /= nchk; }
            // R14 §4: GATE FAIL-CLOSED — nao usar smart_perturba como garantia;
            // o detector REAL: se o par import-vs-puro (quando medido) excede o
            // envelope do controle calibrado (env_imp, default 2.5) => FAIL.
            // R15 (§4): fail-closed REAL — ordem: decode > state > reference >
            // calibracao; PASS de import SO com envelope calibrado (calibration_id).
            std::string gate;
            if (decode_fail)        gate = "FAIL(decode)";
            else if (algum_invalido) gate = "FAIL(state)";
            else if (!ndst2.empty() && (ref_falhou || c_dst2 == nullptr)) gate = "FAIL(reference)";
            else if (env_calib < 0) gate = "CALIBRACAO_PENDENTE";
            else if (c_dst2 && nchk > 0 && !std::isfinite(err_imp_max)) gate = "FAIL(state2)";
            else if (c_dst2 && nchk > 0 && err_imp_max > env_calib) gate = "FAIL(import>env)";
            else if (c_dst2 && nchk > 0) gate = "PASS(import<=env)";
            else gate = "SEM-REFERENCIA(a lacuna R13; usar dst2 p/ medir import)";
            if (nout >= CAP && !eog_stop) gate += "/PARCIAL(cap)";
            else if (eog_stop) gate += "/EOG";
            if (texto.empty() && nout == 0) gate += "/PARCIAL(sem texto)";
            rep("NUMGATE src=%s dst=%s ref=%s dst2=%s | gate=%s | env_calib=%.3f calib=%s | invalidos=%d | e_imp(max/mean)=%.4f/%.4f | nchk=%d div_dst_ref=%d",
                nsrc.c_str(), ndst.c_str(), nref.c_str(), ndst2.empty() ? "-" : ndst2.c_str(),
                gate.c_str(), env_calib, calib_id[0] ? calib_id : "-", n_invalidos, err_imp_max, err_imp_mean, nchk, divs_ds);
            rep("NUMRES src=%s dst=%s ref=%s | passos=%d nchk=%d div_dst_ref=%d | invalidos=%d stop=%s | e_dr(max/mean)=%.4f/%.4f e_sr=%.4f/%.4f e_ds=%.4f/%.4f | lse0(d/s/r)=%.3f/%.3f/%.3f | out=%d texto_len=%zu",
                nsrc.c_str(), ndst.c_str(), nref.c_str(), nchk, nchk, divs_ds, n_invalidos,
                eog_stop ? "EOG" : (nout >= CAP ? "cap" : "incompleto"), err_dr_max, err_dr_mean, err_sr_max, err_sr_mean, err_ds_max, err_ds_mean,
                lse_d0, lse_s0, lse_r0, nout, texto.size());
            rep("NUM texto_len=%zu", texto.size());
            log_flush();
            continue;
        }

        const char * role_dst = "dst";
        const char * role_src = (src == dst) ? "src2" : "src";   // mesmo backend -> ctx SEPARADO (roundtrip real)
        CtxC * c_dst = ctx_de(role_dst, dst);
        if (c_dst == nullptr) { rep("SESS[%zu] %s: FALHA (backend '%s' indisponivel)", idx, rota_full.c_str(), dst.c_str());
                                json_req((int) idx, sess_id, rota_full, bench, false, false, false, 2, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "backend_indisponivel"); continue; }
        const llama_vocab * vocab = llama_model_get_vocab(c_dst->m);
        double load_dst = c_dst->load_ms; c_dst->load_ms = 0;
        double load_src = 0;

        if (puro) {
            double tr0 = now_ms();
            llama_memory_clear(llama_get_memory(c_dst->c), true);
            double reset_ms = now_ms() - tr0;
            double t_request = now_ms();
            auto R = p4v2::request_once(c_dst->c, vocab, prompt, CAP, mut);
            double t_fim = now_ms();
            double wall = t_fim - t_request;
            rep("SESS[%zu] %s%s puro: ok=%d pieces=%d prefill=%.2fms gen=%.2fms pp1=%.2fms wall=%.1fms",
                idx, rota.c_str(), bench ? "@bench" : "", (int) R.ok, R.n_pieces, R.prefill_ms, R.gen_ms, R.primeiro_piece_ms, wall);
            std::string res = R.ok ? "PURO-EXECUTADO" : ("FALHA:" + R.erro);
            rep("SESS[%zu] %s puro texto_len=%zu (integral no JSON)", idx, rota.c_str(), R.texto.size());
            rep("SESS[%zu] %s lifecycle: load=%.0fms reset=%.2fms wall=%.1fms", idx, rota.c_str(), load_dst, reset_ms, wall);
            json_req((int) idx, sess_id, rota_full, bench, R.ok, false, false, 0, -1,
                     R.n_pieces, R.n_pieces, 0,
                     R.prefill_ms, 0, 0, 0, R.gen_ms, wall, reset_ms,
                     0, 0, 0, 0, t_fim - t_request, load_dst, 0, R.texto, res);
            rep("SESS[%zu] %s RESULTADO: %s", idx, rota_full.c_str(), res.c_str());
            log_flush();   // R11
        } else {
            CtxC * c_src = ctx_de(role_src, src);
            if (c_src == nullptr) { rep("SESS[%zu] %s: FALHA (origem '%s' indisponivel)", idx, rota_full.c_str(), src.c_str());
                                    json_req((int) idx, sess_id, rota_full, bench, false, false, false, 2, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "", "origem_indisponivel"); continue; }
            load_src = c_src->load_ms; c_src->load_ms = 0;
            double tr0 = now_ms();
            llama_memory_clear(llama_get_memory(c_src->c), true);
            llama_memory_clear(llama_get_memory(c_dst->c), true);
            double reset_ms = now_ms() - tr0;
            double t_request = now_ms();
            auto B = smartv2::run_bridge(c_src->c, c_dst->c, vocab, prompt, CAP, mut, bench);
            double wall = B.t_fim - t_request;
            rep("SESS[%zu] %s %s: ok=%d cap=%d eog_tok1=%d tok1=%d emitidos=%d consumidos=%d dec_s=%d",
                idx, rota.c_str(), bench ? "bench" : "valid", (int) B.ok, (int) B.cap, (int) B.eog_tok1,
                B.tok1, B.n_emitidos, B.n_consumidos, B.n_decodes_s_pos_ponte);
            rep("SESS[%zu] %s tempos: prefill=%.2fms export=%.2fms(%zuB) import=%.2fms gen=%.2fms",
                idx, rota.c_str(), B.prefill_ms, B.export_ms, B.export_bytes, B.import_ms, B.gen_ms);
            rep("SESS[%zu] %s fronteiras(ms): prefdone=%.1f tok1r=%.1f impdone=%.1f tok2r=%.1f fim=%.1f wall=%.1f reset=%.2f",
                idx, rota.c_str(), B.t_prefill_done - t_request, B.t_tok1_ready - t_request,
                B.t_import_done - t_request, B.t_tok2_ready - t_request, B.t_fim - t_request, wall, reset_ms);
            rep("SESS[%zu] %s texto_len=%zu (integral no JSON)", idx, rota.c_str(), B.texto.size());
            rep("SESS[%zu] %s lifecycle: load_dst=%.0fms load_src=%.0fms (0=WARM)", idx, rota.c_str(), load_dst, load_src);
            // GATES na sessao (mesmos do runSmart): cap/div/decodes bloqueiam vereditos
            std::string res;
            if (bench) {
                bool val = B.ok && !B.cap && B.div_pos < 0 && B.n_decodes_s_pos_ponte == 0;
                res = val ? "BENCH-CONCLUIDO" : (B.cap ? "BENCH-PARCIAL" : (B.n_decodes_s_pos_ponte ? "BENCH-FALHA:origem_decodificou" : ("BENCH-FALHA:" + B.erro)));
            } else {
                bool val = B.ok && !B.cap && B.div_pos < 0;
                res = val ? "SMART-ACEITO" : (B.cap ? "SMART-PARCIAL" : (B.div_pos >= 0 ? "SMART-DIVERGENTE" : ("SMART-FALHA:" + B.erro)));
            }
            json_req((int) idx, sess_id, rota_full, bench, B.ok, B.cap, B.eog_tok1, B.stop, B.tok1,
                     B.n_emitidos, B.n_consumidos, B.n_decodes_s_pos_ponte,
                     B.prefill_ms, B.export_ms, B.export_bytes, B.import_ms, B.gen_ms, wall, reset_ms,
                     B.t_prefill_done - t_request, B.t_tok1_ready - t_request, B.t_import_done - t_request,
                     B.t_tok2_ready - t_request, B.t_fim - t_request, load_dst, load_src, B.texto, res,
                     B.div_pos, B.div_marg_d, B.div_marg_s);
            rep("SESS[%zu] %s RESULTADO: %s", idx, rota_full.c_str(), res.c_str());
        }
        log_flush();   // R11: fora da janela critica
    }
    for (auto & kv : cache) { if (kv.second.c) llama_free(kv.second.c); if (kv.second.m) llama_model_free(kv.second.m); }
    if (g_json) { fclose(g_json); g_json = nullptr; }
    log_flush();   // R11: flush final
    rep("SESSAO(%s): fim (%zu requests)", sess_id.c_str(), linhas.size());
    return g_rep;
}

} // namespace sessao

extern "C" JNIEXPORT jstring JNICALL
Java_br_gov_sp_pcsp_npuprobe_MainActivity_runSmart(JNIEnv * env, jobject, jstring model_j, jstring libdir_j, jstring rota_j) {
    g_rep.clear();
    const char * model_path = env->GetStringUTFChars(model_j, nullptr);
    const char * libdir = env->GetStringUTFChars(libdir_j, nullptr);
    const char * rota_c = env->GetStringUTFChars(rota_j, nullptr);
    const std::string rota_pedida(rota_c ? rota_c : "");
    const std::string mutante = read_mutante();
    const double t_request = now_ms();

    rep("SMARTv2 rota='%s' mutante=%s", rota_pedida.c_str(), mutante.empty() ? "(none)" : mutante.c_str());
    // SESSION MODE (R5): se p4-session.txt existe, roda a sessao inteira (mesma
    // sessao, contextos vivos) e retorna; a rota passada e' ignorada.
    {
        std::string sess = read_arquivo_txt("/data/local/tmp/p4-session.txt", 64);
        if (!sess.empty() && (sess == "on" || rota_pedida == "sessao")) {
            setenv("ADSP_LIBRARY_PATH", libdir, 1);
            setenv("LD_LIBRARY_PATH", libdir, 1);
            { // R7: tune remoto TAMBEM no caminho de sessao (env antes do load)
                std::string tune = read_arquivo_txt("/data/local/tmp/p4-tune.txt", 1024);
                std::string cur;
                for (size_t i = 0; i <= tune.size(); i++) {
                    char ch = (i < tune.size()) ? tune[i] : '\n';
                    if (ch == '\n') {
                        size_t eq = cur.find('=');
                        if (eq != std::string::npos && eq > 0) {
                            std::string k = cur.substr(0, eq), v = cur.substr(eq + 1);
                            setenv(k.c_str(), v.c_str(), 1);
                            rep("TUNE: %s=%s", k.c_str(), v.c_str());
                        }
                        cur.clear();
                    } else if (ch != '\r') cur += ch;
                }
            }
            ggml_log_set(ggml_log_cb, nullptr);
            llama_backend_init();
            ggml_backend_load_all_from_path(libdir);
            sessao::run_sessao(model_path, libdir, mutante);
            env->ReleaseStringUTFChars(model_j, model_path);
            env->ReleaseStringUTFChars(libdir_j, libdir);
            env->ReleaseStringUTFChars(rota_j, rota_c);
            return env->NewStringUTF(g_rep.c_str());
        }
    }

    setenv("ADSP_LIBRARY_PATH", libdir, 1);
    setenv("LD_LIBRARY_PATH", libdir, 1);
    // R7: tune remoto do hexagon (env ANTES do load do backend): p4-tune.txt
    // com linhas NOME=VALOR (ex.: GGML_HEXAGON_MM_SELECT=2). Mesmo APK; so' o
    // arquivo muda -> experimento limpo de config (candidato B sem rebuild).
    {
        std::string tune = read_arquivo_txt("/data/local/tmp/p4-tune.txt", 1024);
        std::string cur;
        for (size_t i = 0; i <= tune.size(); i++) {
            char ch = (i < tune.size()) ? tune[i] : '\n';
            if (ch == '\n') {
                size_t eq = cur.find('=');
                if (eq != std::string::npos && eq > 0) {
                    std::string k = cur.substr(0, eq), v = cur.substr(eq + 1);
                    setenv(k.c_str(), v.c_str(), 1);
                    rep("TUNE: %s=%s", k.c_str(), v.c_str());
                }
                cur.clear();
            } else if (ch != '\r') cur += ch;
        }
    }
    ggml_log_set(ggml_log_cb, nullptr);
    llama_backend_init();
    ggml_backend_load_all_from_path(libdir);

    // parse da rota: "puro:<dst>" | "<dst>" (origem=htp) | "<src>:<dst>"
    auto dev_por_nome = [&](const std::string & n) -> ggml_backend_dev_t {
        if (n == "htp")    return ggml_backend_dev_by_name("HTP0");
        if (n == "cpu")    return ggml_backend_dev_by_name("CPU");
        if (n == "vulkan") return ggml_backend_dev_by_name("Vulkan0");
        if (n == "opencl") return ggml_backend_dev_by_name("GPUOpenCL");
        return nullptr;
    };
    // R12: modo "supports" — pergunta EXPLICITA de suporte por tipo
    // (test-backend-ops minimo): cria tensores fixture e consulta cada device.
    if (rota_pedida == "supports") {
        struct ggml_init_params ip = { 4 * 1024 * 1024, nullptr, false };
        struct ggml_context * ctx = ggml_init(ip);
        const ggml_type tipos[] = { GGML_TYPE_Q4_0, GGML_TYPE_Q4_K, GGML_TYPE_Q6_K, GGML_TYPE_Q5_K, GGML_TYPE_F16, GGML_TYPE_F32 };
        const char * nomes[]   = { "Q4_0",       "Q4_K",       "Q6_K",       "Q5_K",       "F16",       "F32" };
        const char * devs[]    = { "HTP0", "CPU" };
        for (int d = 0; d < 2; d++) {
            ggml_backend_dev_t dev = ggml_backend_dev_by_name(devs[d]);
            if (dev == nullptr) { rep("SUPPORTS %s: device ausente", devs[d]); continue; }
            for (int i = 0; i < 6; i++) {
                struct ggml_tensor * a = ggml_new_tensor_2d(ctx, tipos[i], 256, 4);
                struct ggml_tensor * b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, 256, 4);
                struct ggml_tensor * c = ggml_mul_mat(ctx, a, b);
                bool ok = ggml_backend_dev_supports_op(dev, c);
                rep("SUPPORTS %s m=%s: %s", devs[d], nomes[i], ok ? "SIM" : "NAO(skip)");
            }
        }
        ggml_free(ctx);
        env->ReleaseStringUTFChars(model_j, model_path);
        env->ReleaseStringUTFChars(libdir_j, libdir);
        env->ReleaseStringUTFChars(rota_j, rota_c);
        return env->NewStringUTF(g_rep.c_str());
    }
    bool puro = false;
    bool bench = false;   // R4: @bench = BENCHMARK (sem espelho); default = VALIDACAO
    std::string src_nome = "htp", dst_nome;
    {
        std::vector<std::string> partes;
        std::string cur;
        for (char c : rota_pedida) { if (c == ':') { partes.push_back(cur); cur.clear(); } else cur += c; }
        partes.push_back(cur);
        if (partes.size() == 2 && partes[0] == "puro") { puro = true; dst_nome = partes[1]; }
        else if (partes.size() == 1) { dst_nome = partes[0]; }
        else if (partes.size() == 2) { src_nome = partes[0]; dst_nome = partes[1]; }
        else { rep("RESULTADO: SMART-FALHA (rota invalida: use puro:<dst> | <dst> | <src>:<dst>)"); goto fim_sv2; }
    }
    if (dst_nome.size() > 6 && dst_nome.rfind("@bench") == dst_nome.size() - 6) {
        bench = true;
        dst_nome = dst_nome.substr(0, dst_nome.size() - 6);
    }
    rep("MODO %s", bench ? "BENCHMARK (@bench; sem espelho; decode so no destino)" : "VALIDACAO (espelho teacher-forcing)");
    {
        ggml_backend_dev_t dsrc = dev_por_nome(src_nome);
        ggml_backend_dev_t ddst = dev_por_nome(dst_nome);
        if (ddst == nullptr) { rep("RESULTADO: SMART-FALHA (destino '%s' indisponivel) — erro explicito, sem fallback", dst_nome.c_str()); goto fim_sv2; }
        if (!puro && dsrc == nullptr) { rep("RESULTADO: SMART-FALHA (origem '%s' indisponivel)", src_nome.c_str()); goto fim_sv2; }
        rep("%s: origem=%s | destino=%s (%s)", puro ? "MODO PURO" : "MODO PONTE",
            puro ? "(nenhuma)" : src_nome.c_str(), dst_nome.c_str(),
            ggml_backend_dev_name(ddst));

        // prompt do SEAM (contrato identico ao P4v2); R4: corpus/config remotos
        std::string prompt = std::string("<｜hy_begin▁of▁sentence｜><｜hy_User｜>") +
            "Translate the following text into Portuguese. Note that you should only output " +
            "the translated result without any additional explanation:\n\n" +
            "Good morning, how are you?" + "<｜hy_Assistant｜>";
        { std::string pf = read_arquivo_txt("/data/local/tmp/p4-prompt.txt", 8192);
          if (!pf.empty()) { prompt = pf; rep("prompt: ARQUIVO (len=%zu)", pf.size()); } }
        int CAP = 48;
        { std::string cf = read_arquivo_txt("/data/local/tmp/p4-cap.txt", 16);
          if (!cf.empty()) { int v = atoi(cf.c_str()); if (v >= 1 && v <= 1024) { CAP = v; rep("cap: ARQUIVO = %d", v); } } }

        // contexto do destino (e da origem quando ha' ponte)
        auto mkctx = [&](llama_model * m) {
            llama_context_params cp = llama_context_default_params();
            cp.n_ctx = 2048; cp.n_batch = 128; cp.n_ubatch = 128; cp.n_threads = 4;
            cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
            return llama_init_from_model(m, cp);
        };
        {
            ggml_backend_dev_t devs[2] = { ddst, nullptr };
            llama_model_params mp = llama_model_default_params();
            mp.devices = devs; mp.n_gpu_layers = 999;
            double t0 = now_ms();
            llama_model * md = llama_model_load_from_file(model_path, mp);
            rep("load(destino)=%.0fms", now_ms() - t0);
            if (md == nullptr) { rep("RESULTADO: SMART-FALHA (load destino)"); goto fim_sv2; }
            llama_context * cd = mkctx(md);
            if (cd == nullptr) { llama_model_free(md); rep("RESULTADO: SMART-FALHA (ctx destino)"); goto fim_sv2; }
            const llama_vocab * vocab = llama_model_get_vocab(md);

            if (puro) {
                // baseline puro no destino (sem ponte): reusa request_once do P4v2
                auto R = p4v2::request_once(cd, vocab, prompt, CAP, mutante);
                rep("puro: ok=%d pieces=%d prefill=%.2fms gen=%.2fms pp1=%.2fms", (int) R.ok,
                    R.n_pieces, R.prefill_ms, R.gen_ms, R.primeiro_piece_ms);
                rep("puro texto: %.300s", R.texto.c_str());
                if (R.ok) rep("RESULTADO: PURO-EXECUTADO (%s; baseline; SEM juizo de qualidade — usar texto/stop)", dst_nome.c_str());
                else      rep("RESULTADO: SMART-FALHA (puro: %s)", R.erro.c_str());
                llama_free(cd); llama_model_free(md);
                goto fim_sv2;
            }

            // MODO PONTE: modelo/ctx da origem + ciclos A-B-A
            ggml_backend_dev_t devsh[2] = { dsrc, nullptr };
            llama_model_params mps = llama_model_default_params();
            mps.devices = devsh; mps.n_gpu_layers = 999;
            double t1 = now_ms();
            llama_model * ms = llama_model_load_from_file(model_path, mps);
            rep("load(origem)=%.0fms", now_ms() - t1);
            if (ms == nullptr) { llama_free(cd); llama_model_free(md); rep("RESULTADO: SMART-FALHA (load origem)"); goto fim_sv2; }
            llama_context * cs = mkctx(ms);
            if (cs == nullptr) { llama_model_free(ms); llama_free(cd); llama_model_free(md); rep("RESULTADO: SMART-FALHA (ctx origem)"); goto fim_sv2; }
            rep("ctxs prontos (cold_total=%.0fms)", now_ms() - t_request);

            {
                auto B1 = smartv2::run_bridge(cs, cd, vocab, prompt, CAP, mutante, bench);
                rep("A1: ok=%d cap=%d eog_tok1=%d tok1=%d amostrados(s/d)=%d/%d emitidos=%d consumidos=%d decodes_s_pos_ponte=%d",
                    (int) B1.ok, (int) B1.cap, (int) B1.eog_tok1, B1.tok1, B1.n_amostrados_s, B1.n_amostrados_d,
                    B1.n_emitidos, B1.n_consumidos, B1.n_decodes_s_pos_ponte);
                rep("A1 tempos: prefill=%.2fms export=%.2fms(%zuB) import=%.2fms gen=%.2fms",
                    B1.prefill_ms, B1.export_ms, B1.export_bytes, B1.import_ms, B1.gen_ms);
                rep("A1 espelho: passos=%d iguais=%d div_pos=%d div_marg(d/s)=%.3f/%.3f marg_final_destino=%.4f",
                    B1.espelho_passos, B1.espelho_iguais, B1.div_pos, B1.div_marg_d, B1.div_marg_s, B1.marg_final_d);
                rep("A1 fronteiras(ms desde request): prefill_done=%.1f tok1_ready=%.1f import_done=%.1f tok2_ready=%.1f fim=%.1f (INTERNAS; sem streaming de UI no probe)",
                    B1.t_prefill_done - t_request, B1.t_tok1_ready - t_request,
                    B1.t_import_done - t_request, B1.t_tok2_ready - t_request, B1.t_fim - t_request);
                rep("A1 prompt_hash=%s", B1.hash_prompt.c_str());
                rep("A1 texto: %.300s", B1.texto.c_str());
                if (!B1.ok) {
                    rep("RESULTADO: SMART-FALHA (A1: %s)", B1.erro.c_str());
                } else {
                    // resets validados + A-B-A de Smart (mesmo par de contextos)
                    llama_memory_clear(llama_get_memory(cs), true);
                    llama_memory_clear(llama_get_memory(cd), true);
                    llama_pos pm1 = llama_memory_seq_pos_max(llama_get_memory(cs), 0);
                    llama_pos pm2 = llama_memory_seq_pos_max(llama_get_memory(cd), 0);
                    if (mutante == "smart_reset_bad") pm1 = 5;   // injecao p/ provar o gate
                    rep("reset: pos cmp (origem=%d destino=%d) -> %s", (int) pm1, (int) pm2,
                        (pm1 < 0 && pm2 < 0) ? "VAZIO ok" : "NAO VAZIO (falha)");
                    if (!(pm1 < 0 && pm2 < 0)) {
                        rep("RESULTADO: SMART-FALHA (reset)");
                    } else {
                        auto B2 = smartv2::run_bridge(cs, cd, vocab, prompt, CAP, mutante, bench);
                        rep("A2: ok=%d texto igual ao A1: %s", (int) B2.ok, (B1.texto == B2.texto) ? "SIM" : "NAO");
                        if (bench) {
                            if (B2.ok && !B1.cap && !B2.cap && B1.n_decodes_s_pos_ponte == 0 && B2.n_decodes_s_pos_ponte == 0)
                                rep("RESULTADO: BENCH-CONCLUIDO (%s->%s; sem espelho; decode origem pos-ponte=0; A-B-A texto %s)",
                                    src_nome.c_str(), dst_nome.c_str(), (B1.texto == B2.texto) ? "igual" : "DIFERENTE(!!)");
                            else if (B1.cap || B2.cap)
                                rep("RESULTADO: BENCH-PARCIAL (cap; nenhum token alem do cap)");
                            else
                                rep("RESULTADO: BENCH-FALHA (%s)", B2.erro.c_str());
                        }
                        else if (B2.ok && B1.texto == B2.texto && !B1.cap && !B2.cap &&
                            B1.espelho_iguais == B1.espelho_passos && B1.div_pos < 0)
                            rep("RESULTADO: SMART-ACEITO (%s->%s; A-B-A integral; espelho %d/%d)",
                                src_nome.c_str(), dst_nome.c_str(), B1.espelho_iguais, B1.espelho_passos);
                        else if (B1.cap || B2.cap)
                            rep("RESULTADO: SMART-PARCIAL (cap atingido; A-B-A %s)", (B1.texto == B2.texto) ? "igual" : "diferente");
                        else if (B1.div_pos >= 0)
                            rep("RESULTADO: SMART-DIVERGENTE (espelho divergiu na pos %d de %d; A-B-A %s)",
                                B1.div_pos, B1.espelho_passos, (B1.texto == B2.texto) ? "igual" : "diferente");
                        else
                            rep("RESULTADO: SMART-FALHA (A-B-A: %s)", B2.erro.c_str());
                    }
                }
            }
            llama_free(cs); llama_model_free(ms);
            llama_free(cd); llama_model_free(md);
        }
    }

fim_sv2:
    env->ReleaseStringUTFChars(model_j, model_path);
    env->ReleaseStringUTFChars(libdir_j, libdir);
    env->ReleaseStringUTFChars(rota_j, rota_c);
    return env->NewStringUTF(g_rep.c_str());
}

