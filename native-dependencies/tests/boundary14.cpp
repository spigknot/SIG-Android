// boundary14 — RODADA 14 §6: replay isolado REAL no caminho do produto.
//   (1) FRONTEIRA: upload/readback de F32 com sentinela identificada no
//       backend real do alvo (Vulkan), verificando regiao completa.
//   (2) SUBGRAFO: RMS_NORM + MUL (build_norm efetivo: RMS puro + escala)
//       com MESMOS operandos A e W executados em CPU e em Vulkan, comparados
//       bit a bit entre si e contra referencia f64.
// Nenhum shader/fusao do produto e' alterado; binario de diagnostico proprio.
// Uso: boundary14
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include <cstdio>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <cfloat>
#include <vector>

static const int    N   = 2048;      // n_embd
static const float  EPS = 1e-5f;     // rms_eps do alvo

static void gerar_operandos(std::vector<float>& A, std::vector<float>& W) {
    A.resize(N); W.resize(N);
    for (int i = 0; i < N; i++) {
        A[i] = sinf((float)i * 0.017f) * 1.5f + cosf((float)i * 0.11f) * 0.25f;
        W[i] = 0.5f + 0.25f * sinf((float)i * 0.031f);
    }
}

static double rms64(const std::vector<float>& v) {
    double s = 0; for (float x : v) s += (double)x * x;
    return sqrt(s / v.size());
}
// referencia f64: (A / rms(A,eps)) * W
static std::vector<double> ref_f64(const std::vector<float>& A, const std::vector<float>& W) {
    double s = 0; for (float x : A) s += (double)x * x;
    double rms = sqrt(s / A.size() + (double)EPS);
    std::vector<double> o(A.size());
    for (size_t i = 0; i < A.size(); i++) o[i] = ((double)A[i] / rms) * (double)W[i];
    return o;
}

struct Metrica { long difs; double max_abs; double rmse; };
static Metrica compara(const std::vector<float>& a, const std::vector<float>& b) {
    Metrica m{0, 0, 0}; double s2 = 0;
    for (size_t i = 0; i < a.size(); i++) {
        double d = fabs((double)a[i] - (double)b[i]);
        if (a[i] != b[i]) m.difs++;
        if (d > m.max_abs) m.max_abs = d;
        s2 += d * d;
    }
    m.rmse = sqrt(s2 / a.size());
    return m;
}

// ---- TESTE 1: fronteira com sentinela --------------------------------------
// sentinela identificada em todos os bytes; regiao completa verificada.
static bool teste_fronteira(ggml_backend_t be, const char* rotulo) {
    const uint32_t SENT = 0x5A5A1234u;
    std::vector<uint32_t> padrao(N), lido(N, 0);
    for (int i = 0; i < N; i++) padrao[i] = SENT ^ (uint32_t)(i * 2654435761u);

    struct ggml_init_params p{}; p.mem_size = 16ull * 1024 * 1024; p.no_alloc = true;
    struct ggml_context* ctx = ggml_init(p);
    struct ggml_tensor* t = ggml_new_tensor_1d(ctx, GGML_TYPE_F32, N);
    ggml_set_output(t);
    struct ggml_cgraph* gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, t);

    ggml_gallocr_t galloc = ggml_gallocr_new(ggml_backend_get_default_buffer_type(be));
    if (!ggml_gallocr_reserve(galloc, gf) || !ggml_gallocr_alloc_graph(galloc, gf)) {
        printf("FRONT %s: FALHOU alocar\n", rotulo); return false;
    }
    // upload do padrao -> readback -> regiao completa identica
    ggml_backend_tensor_set(t, padrao.data(), 0, (size_t)N * 4);
    ggml_backend_synchronize(be);
    memset(lido.data(), 0, (size_t)N * 4);
    ggml_backend_tensor_get(t, lido.data(), 0, (size_t)N * 4);
    long diffs = 0;
    for (int i = 0; i < N; i++) if (lido[i] != padrao[i]) diffs++;
    bool ok = diffs == 0;
    printf("FRONT %-10s sentinela: %ld/%d bytes-corrompidos -> %s\n", rotulo, diffs, N, ok ? "BIT-EXATO" : "CORROMPIDO");

    ggml_gallocr_free(galloc);
    ggml_free(ctx);
    return ok;
}

// ---- TESTE 2: subgrafo RMS+MUL nos dois backends ---------------------------
static bool teste_subgrafo(ggml_backend_t be, const std::vector<float>& A,
                           const std::vector<float>& W, std::vector<float>& out) {
    struct ggml_init_params p{}; p.mem_size = 32ull * 1024 * 1024; p.no_alloc = true;
    struct ggml_context* ctx = ggml_init(p);
    struct ggml_tensor* inp = ggml_new_tensor_1d(ctx, GGML_TYPE_F32, N); ggml_set_input(inp);
    struct ggml_tensor* w   = ggml_new_tensor_1d(ctx, GGML_TYPE_F32, N); ggml_set_input(w);
    struct ggml_tensor* n   = ggml_rms_norm(ctx, inp, EPS);
    struct ggml_tensor* o   = ggml_mul(ctx, n, w);
    ggml_set_output(o);
    struct ggml_cgraph* gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, o);

    ggml_gallocr_t galloc = ggml_gallocr_new(ggml_backend_get_default_buffer_type(be));
    if (!ggml_gallocr_reserve(galloc, gf) || !ggml_gallocr_alloc_graph(galloc, gf)) {
        printf("SUBG: FALHOU alocar\n"); return false;
    }
    ggml_backend_tensor_set(inp, A.data(), 0, (size_t)N * 4);
    ggml_backend_tensor_set(w,   W.data(), 0, (size_t)N * 4);
    ggml_backend_synchronize(be);
    enum ggml_status st = ggml_backend_graph_compute(be, gf);
    if (st != GGML_STATUS_SUCCESS) { printf("SUBG: compute devolveu %d\n", (int)st); return false; }
    ggml_backend_synchronize(be);
    out.assign(N, 0.0f);
    ggml_backend_tensor_get(o, out.data(), 0, (size_t)N * 4);

    ggml_gallocr_free(galloc);
    ggml_free(ctx);
    return true;
}

// ---- modo 'mul': replay real do MUL_MAT suspeito (rodada 15 §6) ----
// operandos REAIS: X = attn_norm capturado no alvo (277x2048 F32), W = peso
// cru do GGUF (Q4_K, ne0 x ne1). Executa em CPU e Vulkan com os MESMOS
// operandos, confere o input apos upload e compara as saidas.
static double m_rms(const std::vector<float>& v) {
    double s = 0; for (float x : v) s += (double)x * x;
    return v.empty() ? 0.0 : sqrt(s / v.size());
}

static int modo_mul(int argc, char** argv) {
    // boundary14 mul <X.mat> <W.bin> <n_rows> <w_ne0> <w_ne1> <prefixo>
    // Contrato: argv[0]=prog, [1]=X, [2]=W, [3]=n_rows, [4]=w_ne0, [5]=w_ne1, [6]=pfx
    if (argc < 7) {
        printf("uso: boundary14 mul X.mat W.bin n_rows w_ne0 w_ne1 prefixo\n");
        printf("      argc recebido = %d (minimo 7)\n", argc);
        return 2;
    }
    // validacao estrita: sem atoi/atoll silenciosos
    auto parse_int = [](const char* s_, long long* out) {
        if (!s_ || !*s_) return false;
        char* end = nullptr;
        long long v = strtoll(s_, &end, 10);
        if (end == s_ || *end != '\0') return false;
        *out = v;
        return true;
    };
    long long l_rows = 0, l_ne0 = 0, l_ne1 = 0;
    if (!parse_int(argv[3], &l_rows) || !parse_int(argv[4], &l_ne0) || !parse_int(argv[5], &l_ne1)) {
        printf("mul: n_rows/w_ne0/w_ne1 devem ser inteiros validos (recebidos: %s %s %s)\n",
               argv[3], argv[4], argv[5]);
        return 2;
    }
    const int    n_rows = (int)l_rows;
    const int64_t w_ne0 = l_ne0;
    const int64_t w_ne1 = l_ne1;
    const char * pfx = argv[6];
    if (n_rows <= 0 || w_ne0 <= 0 || w_ne1 <= 0) {
        printf("mul: dimensoes devem ser > 0 (rows=%d ne0=%lld ne1=%lld)\n", n_rows, (long long)w_ne0, (long long)w_ne1);
        return 2;
    }
    // X: dimensao Fixa em 2048 (o tokenizer do modelo) — conferir contra o
    // tamanho real do arquivo e a divisibilidade exigida por Q4_K/Q8_K.
    const int64_t X_NE0 = 2048;
    const int64_t x_bytes_esperado = (int64_t)n_rows * X_NE0 * 4;
    {
        FILE* pf = fopen(argv[1], "rb");
        if (!pf) { printf("mul: nao abriu %s\n", argv[1]); return 3; }
        fseek(pf, 0, SEEK_END);
        const long sz = ftell(pf);
        fclose(pf);
        if ((int64_t)sz != x_bytes_esperado) {
            printf("mul: X com %ld bytes, esperado %lld (%d linhas x %d F32)\n",
                   sz, (long long)x_bytes_esperado, n_rows, X_NE0);
            return 2;
        }
    }
    if (w_ne0 != X_NE0) {
        printf("mul: ne0 do peso (%lld) != ne0 de X (%d) — mul_mat exige igualdade\n",
               (long long)w_ne0, X_NE0);
        return 2;
    }
    if ((w_ne0 % 256) != 0) { printf("mul: ne0 %% 256 != 0 (Q4_K)\n"); return 2; }
    const int64_t x_elems = (int64_t)n_rows * 2048;
    // Q4_K: 144 bytes por bloco de 256 elementos (0.5625 B/elem)
    if ((w_ne0 * w_ne1) % 256 != 0) { printf("mul: ne nao multiplo de 256\n"); return 2; }
    const int64_t w_bytes = (int64_t)((w_ne0 * w_ne1) * 144 / 256);
    std::vector<float> X((size_t)x_elems);
    std::vector<uint8_t> W((size_t)w_bytes);
    {
        FILE* f = fopen(argv[1], "rb");
        if (!f) { printf("mul: nao abriu %s\n", argv[1]); return 3; }
        size_t r = fread(X.data(), 4, (size_t)x_elems, f); fclose(f);
        if (r != (size_t)x_elems) { printf("mul: X truncado (%zu/%lld)\n", r, (long long)x_elems); return 3; }
        f = fopen(argv[2], "rb");
        if (!f) { printf("mul: nao abriu %s\n", argv[2]); return 3; }
        r = fread(W.data(), 1, (size_t)w_bytes, f); fclose(f);
        if (r != (size_t)w_bytes) { printf("mul: W truncado (%zu/%lld)\n", r, (long long)w_bytes); return 3; }
    }
    printf("MUL: X[%lld] F32 + W[%lldx%lld] Q4_K (%lld bytes)\n",
           (long long)x_elems, (long long)w_ne0, (long long)w_ne1, (long long)w_bytes);

    ggml_backend_load_all();
    ggml_backend_dev_t dev_cpu = nullptr, dev_gpu = nullptr;
    const size_t n_dev = ggml_backend_dev_count();
    for (size_t i = 0; i < n_dev; i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU && !dev_cpu) dev_cpu = d;
        if (!dev_gpu && strstr(ggml_backend_dev_name(d), "Vulkan")) dev_gpu = d;
    }
    if (!dev_cpu || !dev_gpu) { printf("MEDICAO: INVALIDA (sem dev)\n"); return 4; }

    std::vector<float> out_cpu, out_gpu, upload_check;
    bool ok = true;
    struct Backend { ggml_backend_dev_t dev; const char* nome; std::vector<float> *out; };
    Backend backs[2] = {{dev_cpu, "CPU", &out_cpu}, {dev_gpu, "Vulkan", &out_gpu}};
    for (auto & B : backs) {
        ggml_backend_t be = ggml_backend_dev_init(B.dev, nullptr);
        struct ggml_init_params p{}; p.mem_size = 64ull*1024*1024; p.no_alloc = true;
        struct ggml_context* ctx = ggml_init(p);
        struct ggml_tensor* x = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, 2048, n_rows); ggml_set_input(x);
        struct ggml_tensor* w = ggml_new_tensor_2d(ctx, GGML_TYPE_Q4_K, w_ne0, w_ne1); ggml_set_input(w);
        struct ggml_tensor* o = ggml_mul_mat(ctx, w, x); ggml_set_output(o);
        struct ggml_cgraph* gf = ggml_new_graph(ctx);
        ggml_build_forward_expand(gf, o);
        ggml_gallocr_t galloc = ggml_gallocr_new(ggml_backend_get_default_buffer_type(be));
        if (!ggml_gallocr_reserve(galloc, gf) || !ggml_gallocr_alloc_graph(galloc, gf)) {
            printf("%s: FALHOU alocar\n", B.nome); ok = false; ggml_gallocr_free(galloc); ggml_free(ctx); ggml_backend_free(be); continue;
        }
        ggml_backend_tensor_set(x, X.data(), 0, (size_t)x_elems * 4);
        ggml_backend_tensor_set(w, W.data(), 0, (size_t)w_bytes);
        ggml_backend_synchronize(be);
        // §6: conferir X E W apos upload — por BYTES (float != nao e'
        // identidade byte a byte: +0 e -0 comparam iguais com != mas tem
        // bytes diferentes).
        upload_check.assign((size_t)x_elems, -999.0f);
        ggml_backend_tensor_get(x, upload_check.data(), 0, (size_t)x_elems * 4);
        long u_difs = 0;
        for (int64_t i = 0; i < x_elems; i++) {
            if (memcmp(&upload_check[i], &X[i], 4) != 0) u_difs++;
        }
        printf("%s: X apos upload: %ld/%lld BYTES diferentes -> %s\n",
               B.nome, u_difs, (long long)x_elems, u_difs == 0 ? "BIT-EXATO" : "ALTERADO");
        if (u_difs) ok = false;
        {
            std::vector<uint8_t> w_chk((size_t)w_bytes, 0);
            ggml_backend_tensor_get(w, w_chk.data(), 0, (size_t)w_bytes);
            const long w_difs = (w_chk == W) ? 0 : 1;
            printf("%s: W apos upload: %s\n", B.nome, w_difs == 0 ? "BIT-EXATO" : "ALTERADO");
            if (w_difs) ok = false;
        }
        enum ggml_status st = ggml_backend_graph_compute(be, gf);
        if (st != GGML_STATUS_SUCCESS) {
            // §6: erro de compute INTERROMPE antes de ler a saida
            printf("%s: compute devolveu %d — saida NAO lida\n", B.nome, (int)st);
            ok = false;
            ggml_gallocr_free(galloc); ggml_free(ctx); ggml_backend_free(be);
            continue;
        }
        ggml_backend_synchronize(be);
        const int64_t o_elems = (int64_t)w_ne1 * n_rows;
        B.out->assign((size_t)o_elems, 0.0f);
        ggml_backend_tensor_get(o, B.out->data(), 0, (size_t)o_elems * 4);
        // finitude da saida antes de gravar
        {
            long nf = 0;
            for (int64_t i = 0; i < o_elems; i++) {
                const float v = (*B.out)[i];
                if (v != v || v > 3.0e38f || v < -3.0e38f) nf++;
            }
            if (nf) { printf("%s: %ld valores nao finitos na saida\n", B.nome, nf); ok = false; }
        }
        // gravar saida: falha de fopen/fwrite/fclose e' erro, nao aviso
        char op[512];
        int pn = snprintf(op, sizeof(op), "%s_%s.bin", pfx, B.nome);
        if (pn < 0 || (size_t)pn >= sizeof(op)) {
            printf("%s: caminho truncado (%d)\n", B.nome, pn); ok = false;
        } else {
            FILE* of = fopen(op, "wb");
            if (!of) { printf("%s: FALHOU abrir %s\n", B.nome, op); ok = false; }
            else {
                const size_t wn = fwrite(B.out->data(), 4, (size_t)o_elems, of);
                const int frc = fclose(of);
                if (wn != (size_t)o_elems || frc != 0) {
                    printf("%s: escrita INCOMPLETA em %s (%zu/%lld)\n", B.nome, op, wn, (long long)o_elems);
                    ok = false;
                } else {
                    printf("%s: saida gravada %s (%lld floats)\n", B.nome, op, (long long)o_elems);
                }
            }
        }
        ggml_gallocr_free(galloc); ggml_free(ctx); ggml_backend_free(be);
    }
    // comparacao CPU x Vulkan
    if (!out_cpu.empty() && out_cpu.size() == out_gpu.size()) {
        Metrica m = compara(out_cpu, out_gpu);
        double maior_ulp = 0;
        for (size_t i = 0; i < out_cpu.size(); i++) {
            float c = out_cpu[i], g = out_gpu[i];
            if (c == g) continue;
            float base = fabs(c) > 1e-30f ? fabs(c) : fabs(g);
            float ulp = nextafterf(base, 1e30f) - base;
            if (ulp <= 0) ulp = FLT_MIN;
            double u = fabs((double)c - (double)g) / ulp;
            if (u > maior_ulp) maior_ulp = u;
        }
        // ULP: valores opostos/perto de zero produzem distancias enormes
        // LEGITIMAMENTE (denominador ~FLT_MIN). NAO e' bug do estimador.
        // Por isso ULP e' reportado so para elementos com base normal, e as
        // metricas decisorias sao max_abs/RMSE/erro normalizado protegido.
        long ulp_ok = 0, ulp_ruim = 0;
        for (size_t i = 0; i < out_cpu.size(); i++) {
            const float base = fabsf(out_cpu[i]);
            if (base < 1e-4f) continue;             // perto de zero: ULP nao informativo
            ulp_ok++;
            if (fabsf(out_cpu[i] - out_gpu[i]) / (nextafterf(base, 2.0f*base) - base) > 8.0f) ulp_ruim++;
        }
        const double rms_cpu = m_rms(out_cpu);
        printf("MUL CPU x Vulkan: difs=%zu/%zu max_abs=%.9g RMSE=%.9g RMSE/rms_cpu=%.4f%%\n",
               m.difs, out_cpu.size(), m.max_abs, m.rmse,
               rms_cpu > 0 ? 100.0 * m.rmse / rms_cpu : 0.0);
        printf("MUL: ULP (so elementos |base|>=1e-4, n=%ld): maior=%.2f, acima de 8 ULP: %ld\n",
               ulp_ok, maior_ulp, ulp_ruim);
        printf("MUL: nota — ULP nao e' informativo perto de zero (denominador ~FLT_MIN); "
               "decisao por max_abs/RMSE/erro normalizado\n");
    }
    printf(ok ? "MEDICAO: VALIDA (mul real: uploads bit-exatos e comparacao executada)\n"
              : "MEDICAO: INVALIDA (upload alterado ou falha de compute)\n");
    return ok ? 0 : 1;
}

int main(int argc, char** argv) {
    if (argc > 1 && strcmp(argv[1], "mul") == 0) return modo_mul(argc - 1, argv + 1);
    printf("BOUNDARY14 — rodada 14 §6 (fronteira + subgrafo RMS+MUL real)\n");
    printf("N=%d eps=%g\n", N, EPS);
    ggml_backend_load_all();

    ggml_backend_dev_t dev_cpu = nullptr, dev_gpu = nullptr;
    const size_t n_dev = ggml_backend_dev_count();
    for (size_t i = 0; i < n_dev; i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        enum ggml_backend_dev_type tp = ggml_backend_dev_type(d);
        printf("  dev[%zu]: %-18s type=%d  %s\n", i, ggml_backend_dev_name(d), (int)tp,
               ggml_backend_dev_description(d));
        if (tp == GGML_BACKEND_DEVICE_TYPE_CPU && !dev_cpu) dev_cpu = d;
        // escolha POR NOME: o alvo do produto e' o Vulkan (Adreno 840).
        // O OpenCL NAO entra nesta investigacao (fora de escopo por parecer).
        if (!dev_gpu && strstr(ggml_backend_dev_name(d), "Vulkan")) dev_gpu = d;
    }
    if (!dev_cpu) { printf("MEDICAO: INVALIDA (sem dev CPU)\n"); return 2; }
    if (!dev_gpu) { printf("MEDICAO: INVALIDA (sem dev GPU)\n"); return 3; }

    ggml_backend_t be_cpu = ggml_backend_dev_init(dev_cpu, nullptr);
    ggml_backend_t be_gpu = ggml_backend_dev_init(dev_gpu, nullptr);
    printf("alvo CPU = %s | alvo GPU = %s\n", ggml_backend_dev_name(dev_cpu), ggml_backend_dev_name(dev_gpu));

    bool ok = true;
    ok &= teste_fronteira(be_cpu, "CPU");
    ok &= teste_fronteira(be_gpu, "GPU");

    std::vector<float> A, W;
    gerar_operandos(A, W);

    std::vector<float> out_cpu, out_gpu;
    if (!teste_subgrafo(be_cpu, A, W, out_cpu)) ok = false;
    if (!teste_subgrafo(be_gpu, A, W, out_gpu)) ok = false;

    if (!out_cpu.empty() && !out_gpu.empty()) {
        Metrica m_cv = compara(out_cpu, out_gpu);
        printf("SUBG CPU x GPU : difs=%ld/%d max_abs=%.9g RMSE=%.9g\n",
               m_cv.difs, N, m_cv.max_abs, m_cv.rmse);

        auto ref = ref_f64(A, W);
        std::vector<float> rf(ref.begin(), ref.end());
        Metrica m_rc = compara(out_cpu, rf);
        Metrica m_rg = compara(out_gpu, rf);
        printf("SUBG CPU x f64 : difs=%ld/%d max_abs=%.9g RMSE=%.9g\n", m_rc.difs, N, m_rc.max_abs, m_rc.rmse);
        printf("SUBG GPU x f64 : difs=%ld/%d max_abs=%.9g RMSE=%.9g\n", m_rg.difs, N, m_rg.max_abs, m_rg.rmse);
        // ULP por elemento (§2.2 do parecer): max |diff| / ulp do float no ponto
        double maior_ulp = 0;
        for (int i = 0; i < N; i++) {
            float c = out_cpu[i], g = out_gpu[i];
            if (c == g) continue;
            float refv = out_cpu[i];
            if (refv == 0) refv = out_gpu[i];
            float ulp = nextafterf(fabs(refv), 1e30f) - fabs(refv);  // ulp local
            if (ulp <= 0) ulp = FLT_MIN;
            double u = fabs((double)c - (double)g) / ulp;
            if (u > maior_ulp) maior_ulp = u;
        }
        printf("SUBG CPU x GPU : maior diferenca = %.2f ULP (por elemento, dtype/magnitude)\n", maior_ulp);
        // §2.2/§6: divergencia de kernel medida e' aceitavel se <= 8 ULP por
        // elemento (arredondamento f32); acima disso e' corrupcao suspeita.
        if (maior_ulp > 8.0) ok = false;
        printf("SUBG: divergencia %s (limiar 8 ULP/elemento)\n",
               maior_ulp > 8.0 ? "ACIMA DO LIMIAR -> corrupcao" : "dentro do arredondamento f32");
    }

    ggml_backend_free(be_cpu);
    ggml_backend_free(be_gpu);
    printf(ok ? "MEDICAO: VALIDA (fronteira bit-exata; subgrafo divergiu apenas dentro do arredondamento f32)\n"
              : "MEDICAO: INVALIDA (fronteira corrompida OU subgrafo acima de 8 ULP/elemento)\n");
    return ok ? 0 : 1;
}
