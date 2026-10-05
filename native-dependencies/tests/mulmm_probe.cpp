// mulmm_probe — discriminador de kernel quantizado (CPU x Vulkan) para a
// patologia Q8_0 do Hy-MT2 no Adreno.
//
// Dois modos, mesma medicao:
//   1) synthq8 [rows...]                W Q8_0 SINTETICO determinista
//   2) gguf <modelo.gguf> <tensor> [rows...]
//                                       W = tensor REAL do GGUF (qualquer
//                                       tipo quantizado; ex.: Q8_0, Q4_K)
//
// Medicao por n_rows em {1,8,64,512} (default): mesmo grafo mul_mat(w, x)
// executado em CPU e Vulkan com os MESMOS operandos; uploads conferidos byte
// a byte; referencia f64 = dequant(W) (pela propria ggml: type_traits->to_float)
// x X, nas primeiras N_REF colunas. Veredito por config:
//   AMBOS_OK / VULKAN_ATENCAO / VULKAN_GROSSEIRO (limiar 5e-2 CPU x Vulkan)
// No modo synthq8 a dequantizacao manual e' CONFERIDA contra a da ggml
// (DUPLA_CHECAGEM) — um layout errado do gerador seria detectado na hora.
//
// Sem device Vulkan, roda modo PARCIAL (apenas CPU x f64) para validar o
// proprio teste antes de gastar tempo do aparelho.
//
// Uso:
//   mulmm_probe synthq8 [1,8,64,512]
//   mulmm_probe gguf /sdcard/.../Hy-MT2-1.8B-Q8_0.gguf blk.0.attn_q.weight
// rc: 0 = medicoes validas; 1 = upload/compute falhou; 2 = uso; 4 = sem CPU.

#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include "gguf.h"

#include <cstdio>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <vector>
#include <string>

static const int    NE0     = 2048;   // n_embd do Hy-MT2 (eixo do peso)
static const int    N_REF   = 256;    // colunas com referencia f64
static const char*  ROWS_D  = "1,8,64,512";

// fp16 exato para 2^-6: 0x2400 (s = 0, exp = 9, mant = 0)
static const uint16_t D_HALF = 0x2400u;
static const float    D_VAL  = 0x1p-6f;

// ---------- gerador sintetico Q8_0 (bloco: 2B d + 32B qs) -------------------
static void gerar_w_q8(std::vector<uint8_t>& raw, std::vector<float>& w_manual,
                       int ne1, uint32_t seed) {
    const int64_t elems = (int64_t)NE0 * ne1;
    const int64_t nblocks = elems / 32;
    raw.assign((size_t)nblocks * 34, 0);
    w_manual.assign((size_t)elems, 0.0f);
    uint32_t st = seed ? seed : 1u;
    for (int64_t b = 0; b < nblocks; b++) {
        uint8_t* blk = raw.data() + b * 34;
        blk[0] = (uint8_t)(D_HALF & 0xFF);
        blk[1] = (uint8_t)(D_HALF >> 8);
        for (int i = 0; i < 32; i++) {
            st = st * 1664525u + 1013904223u;
            const int8_t q = (int8_t)((st >> 24) % 255 - 127);   // [-127,127]
            blk[2 + i] = (uint8_t)q;
            w_manual[(size_t)b * 32 + i] = (float)q * D_VAL;
        }
    }
}

static void gerar_x(std::vector<float>& X, int n_rows, uint32_t seed) {
    X.resize((size_t)NE0 * n_rows);
    uint32_t st = seed ? seed : 7u;
    for (size_t i = 0; i < X.size(); i++) {
        st = st * 1103515245u + 12345u;
        const float u = (float)((st >> 16) & 0x7FFFu) / 32768.0f;
        X[i] = sinf((float)i * 0.013f) * 0.8f + (u - 0.5f) * 0.4f;
    }
}

static double rms_f(const std::vector<float>& v) {
    double s = 0; for (float x : v) s += (double)x * x;
    return v.empty() ? 0.0 : sqrt(s / (double)v.size());
}

// ---------- medição em um backend ------------------------------------------
static bool roda_backend(ggml_backend_t be, const char* nome, ggml_type tipo,
                         const std::vector<uint8_t>& W, const std::vector<float>& X,
                         int n_rows, int ne1, std::vector<float>& out) {
    bool ok = true;
    const size_t mem = (size_t)64 * 1024 * 1024;
    struct ggml_context* ctx = ggml_init({mem, nullptr, true});
    struct ggml_tensor* x = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, NE0, n_rows);
    struct ggml_tensor* w = ggml_new_tensor_2d(ctx, tipo, NE0, ne1);
    ggml_set_input(x);
    ggml_set_input(w);
    struct ggml_tensor* o = ggml_mul_mat(ctx, w, x);
    ggml_set_output(o);
    struct ggml_cgraph* gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, o);

    const size_t w_bytes = ggml_nbytes(w);
    if (w_bytes != W.size()) {
        printf("  %s: nbytes(W)=%zu != fornecido=%zu -> INVALIDO\n", nome, w_bytes, W.size());
        ggml_free(ctx);
        return false;
    }
    ggml_gallocr_t galloc = ggml_gallocr_new(ggml_backend_get_default_buffer_type(be));
    if (!ggml_gallocr_reserve(galloc, gf) || !ggml_gallocr_alloc_graph(galloc, gf)) {
        printf("  %s: FALHOU alocar\n", nome);
        ggml_gallocr_free(galloc); ggml_free(ctx); return false;
    }
    ggml_backend_tensor_set(w, W.data(), 0, w_bytes);
    ggml_backend_tensor_set(x, X.data(), 0, X.size() * 4);
    ggml_backend_synchronize(be);
    {
        std::vector<uint8_t> wchk(w_bytes, 0);
        ggml_backend_tensor_get(w, wchk.data(), 0, w_bytes);
        printf("  %s: upload W %s\n", nome, (wchk == W) ? "BIT-EXATO" : "ALTERADO");
        if (wchk != W) ok = false;
    }
    {
        std::vector<float> xchk(X.size(), -999.0f);
        ggml_backend_tensor_get(x, xchk.data(), 0, X.size() * 4);
        long d = 0;
        for (size_t i = 0; i < X.size(); i++) {
            if (memcmp(&xchk[i], &X[i], 4) != 0) d++;
        }
        printf("  %s: upload X %s (%ld/%zu dif)\n", nome,
               d == 0 ? "BIT-EXATO" : "ALTERADO", d, X.size());
        if (d) ok = false;
    }
    enum ggml_status st = ggml_backend_graph_compute(be, gf);
    if (st != GGML_STATUS_SUCCESS) {
        printf("  %s: compute devolveu %d -> INVALIDO\n", nome, (int)st);
        ggml_gallocr_free(galloc); ggml_free(ctx); return false;
    }
    ggml_backend_synchronize(be);
    const int64_t o_elems = (int64_t)ne1 * n_rows;
    out.assign((size_t)o_elems, 0.0f);
    ggml_backend_tensor_get(o, out.data(), 0, (size_t)o_elems * 4);
    {
        long nf = 0;
        for (int64_t i = 0; i < o_elems; i++) {
            const float v = out[(size_t)i];
            if (v != v || v > 3.0e38f || v < -3.0e38f) nf++;
        }
        if (nf) { printf("  %s: %ld nao-finitos\n", nome, nf); ok = false; }
    }
    ggml_gallocr_free(galloc);
    ggml_free(ctx);
    return ok;
}

struct Met { double max_abs = 0, rmse = 0; };

static Met compara_cols(const std::vector<float>& a, const std::vector<float>& b,
                        int n_rows, int ne1) {
    Met m; double s2 = 0; long n = 0;
    for (int r = 0; r < n_rows; r++) {
        for (int c = 0; c < ne1 && c < N_REF; c++) {
            const size_t i = (size_t)c + (size_t)r * (size_t)ne1;
            const double d = fabs((double)a[i] - (double)b[i]);
            if (d > m.max_abs) m.max_abs = d;
            s2 += d * d; n++;
        }
    }
    m.rmse = n ? sqrt(s2 / (double)n) : 0.0;
    return m;
}

// correlacao de Pearson + razao mediana entre as saidas (diagnostico de
// classe do defeito: r ~ 1 = erro de escala; r ~ 0 = dados trocados/deslocados)
static void diag_relacao(const std::vector<float>& a, const std::vector<float>& b,
                         int n_rows, int ne1, double* corr, double* razao_med) {
    std::vector<double> xs, ys;
    xs.reserve((size_t)n_rows * N_REF); ys.reserve((size_t)n_rows * N_REF);
    for (int r = 0; r < n_rows; r++) {
        for (int c = 0; c < ne1 && c < N_REF; c++) {
            const size_t i = (size_t)c + (size_t)r * (size_t)ne1;
            xs.push_back((double)a[i]); ys.push_back((double)b[i]);
        }
    }
    const double n = (double)xs.size();
    double mx = 0, my = 0;
    for (size_t i = 0; i < xs.size(); i++) { mx += xs[i]; my += ys[i]; }
    mx /= n; my /= n;
    double sxy = 0, sxx = 0, syy = 0;
    std::vector<double> rr;
    for (size_t i = 0; i < xs.size(); i++) {
        const double dx = xs[i] - mx, dy = ys[i] - my;
        sxy += dx * dy; sxx += dx * dx; syy += dy * dy;
        if (fabs(xs[i]) > 1e-6) rr.push_back(ys[i] / xs[i]);
    }
    *corr = (sxx > 0 && syy > 0) ? sxy / sqrt(sxx * syy) : 0.0;
    *razao_med = 0.0;
    if (!rr.empty()) {
        std::sort(rr.begin(), rr.end());
        *razao_med = rr[rr.size() / 2];
    }
}

// referencia f64: dequant(W) * X nas colunas [0,N_REF), layout [ne1 x n_rows]
static void ref_f64(const std::vector<float>& W_deq, const std::vector<float>& X,
                    int n_rows, int ne1, std::vector<double>& ref) {
    const int ncols = ne1 < N_REF ? ne1 : N_REF;
    ref.assign((size_t)ne1 * (size_t)n_rows, 0.0);
    for (int r = 0; r < n_rows; r++) {
        const float* xr = X.data() + (size_t)r * NE0;
        for (int c = 0; c < ncols; c++) {
            const float* wr = W_deq.data() + (size_t)c * NE0;
            double acc = 0.0;
            for (int k = 0; k < NE0; k++) acc += (double)xr[k] * (double)wr[k];
            ref[(size_t)c + (size_t)r * (size_t)ne1] = acc;
        }
    }
}

static std::vector<int> parse_rows(const char* s) {
    std::vector<int> rows;
    while (*s) {
        char* end = nullptr;
        long v = strtol(s, &end, 10);
        if (end == s || v <= 0) { rows.clear(); return rows; }
        rows.push_back((int)v);
        if (*end == '\0') break;
        s = (*end == ',') ? end + 1 : end;
    }
    return rows;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        printf("uso: mulmm_probe synthq8 [rows]\n");
        printf("     mulmm_probe gguf <modelo.gguf> <tensor> [rows]\n");
        return 2;
    }
    const bool modo_gguf = (strcmp(argv[1], "gguf") == 0);
    std::vector<int> rows;
    std::vector<uint8_t> W;
    std::vector<float> W_deq;
    ggml_type tipo = GGML_TYPE_Q8_0;
    int ne1 = 0;
    std::string rotulo;

    if (modo_gguf) {
        if (argc < 4) { printf("uso: mulmm_probe gguf <modelo.gguf> <tensor> [rows]\n"); return 2; }
        const char* path = argv[2];
        const char* nome_tensor = argv[3];
        rows = parse_rows(argc > 4 ? argv[4] : ROWS_D);
        if (rows.empty()) { printf("rows invalido\n"); return 2; }

        struct gguf_init_params gp{};
        gp.no_alloc = true;
        gp.ctx = nullptr;
        struct gguf_context* g = gguf_init_from_file(path, gp);
        if (!g) { printf("ERRO: gguf_init_from_file falhou (%s)\n", path); return 3; }
        const int64_t tid = gguf_find_tensor(g, nome_tensor);
        if (tid < 0) { printf("ERRO: tensor '%s' nao existe\n", nome_tensor); gguf_free(g); return 3; }
        tipo = gguf_get_tensor_type(g, tid);
        const int64_t* tne = gguf_get_tensor_ne(g, tid);
        const int64_t ne0 = tne[0];          // eixo rapido do peso
        ne1 = (int)tne[1];
        const size_t t_off = gguf_get_data_offset(g) + gguf_get_tensor_offset(g, tid);
        const size_t t_size = gguf_get_tensor_size(g, tid);
        printf("GGUF: %s | tensor=%s tipo=%s ne=[%lld,%d] offset=%zu size=%zu\n",
               path, nome_tensor, ggml_type_name(tipo), (long long)ne0, ne1,
               t_off, t_size);
        if (ne0 != NE0) { printf("ERRO: ne0=%lld != %d (probe fixo no n_embd)\n", (long long)ne0, NE0); gguf_free(g); return 3; }
        const size_t esperado = (size_t)ggml_type_size(tipo) * (size_t)(ne0 * ne1) / (size_t)ggml_blck_size(tipo);
        if (t_size != esperado) {
            printf("AVISO: size do tensor (%zu) != esperado por bloco (%zu)\n", t_size, esperado);
        }
        FILE* f = fopen(path, "rb");
        if (!f) { printf("ERRO: nao abriu %s\n", path); gguf_free(g); return 3; }
        if (fseek(f, (long)t_off, SEEK_SET) != 0) { printf("ERRO: fseek\n"); fclose(f); gguf_free(g); return 3; }
        W.assign(t_size, 0);
        const size_t rd = fread(W.data(), 1, t_size, f);
        fclose(f);
        if (rd != t_size) { printf("ERRO: leitura curta (%zu/%zu)\n", rd, t_size); gguf_free(g); return 3; }
        gguf_free(g);
        rotulo = std::string("GGUF ") + ggml_type_name(tipo) + " " + nome_tensor;
    } else if (strcmp(argv[1], "synthq8") == 0) {
        rows = parse_rows(argc > 2 ? argv[2] : ROWS_D);
        if (rows.empty()) { printf("rows invalido\n"); return 2; }
        ne1 = NE0;
        gerar_w_q8(W, W_deq, ne1, 0xC0FFEEu);
        rotulo = "SYNTH Q8_0";
    } else {
        printf("modo desconhecido: %s\n", argv[1]);
        return 2;
    }

    // referencia dequantizada pela PROPRIA ggml (type traits)
    const ggml_type_traits* tt = ggml_get_type_traits(tipo);
    if (!tt || !tt->to_float) { printf("ERRO: tipo sem to_float\n"); return 3; }
    const size_t elems = (size_t)NE0 * (size_t)ne1;
    std::vector<float> w_ggml(elems, 0.0f);
    tt->to_float(W.data(), w_ggml.data(), (int64_t)elems);

    if (!W_deq.empty()) {
        // dupla checagem: gerador manual vs dequant da ggml
        long difs = 0; double maxd = 0;
        for (size_t i = 0; i < elems; i++) {
            const double d = fabs((double)W_deq[i] - (double)w_ggml[i]);
            if (W_deq[i] != w_ggml[i]) difs++;
            if (d > maxd) maxd = d;
        }
        printf("DUPLA_CHECAGEM dequant manual x ggml: %ld/%zu difs (max=%.6g)\n", difs, elems, maxd);
        if (difs) { printf("ERRO: gerador sintetico nao casa com a ggml\n"); return 3; }
        W_deq = w_ggml;   // segue com a referencia da ggml
    } else {
        W_deq = w_ggml;
    }

    printf("PROBE tipo=%s ne=[%d,%d] bytes=%zu rows=", ggml_type_name(tipo), NE0, ne1, W.size());
    for (size_t i = 0; i < rows.size(); i++) printf("%s%d", i ? "," : "", rows[i]);
    printf(" N_REF=%d\n", N_REF);

    ggml_backend_load_all();
    ggml_backend_dev_t dev_cpu = nullptr, dev_gpu = nullptr;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (!dev_cpu && ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU) dev_cpu = d;
        if (!dev_gpu && strstr(ggml_backend_dev_name(d), "Vulkan")) dev_gpu = d;
    }
    if (!dev_cpu) { printf("MEDICAO: INVALIDA (sem dev CPU)\n"); return 4; }
    const bool tem_vulkan = (dev_gpu != nullptr);
    if (!tem_vulkan) printf("AVISO: sem Vulkan neste host — validacao apenas CPU x f64\n");

    ggml_backend_t be_cpu = ggml_backend_dev_init(dev_cpu, nullptr);
    ggml_backend_t be_gpu = tem_vulkan ? ggml_backend_dev_init(dev_gpu, nullptr) : nullptr;
    printf("alvo CPU=%s%s%s\n", ggml_backend_dev_name(dev_cpu),
           tem_vulkan ? " GPU=" : "", tem_vulkan ? ggml_backend_dev_name(dev_gpu) : "");

    bool ok = true;
    int suspeitos = 0, confiaveis = 0, atencao = 0;
    for (int n_rows : rows) {
        printf("\n== %s n_rows=%d ==\n", rotulo.c_str(), n_rows);
        std::vector<float> X;
        gerar_x(X, n_rows, 12345u + (uint32_t)n_rows);

        std::vector<float> out_cpu, out_gpu;
        if (!roda_backend(be_cpu, "CPU   ", tipo, W, X, n_rows, ne1, out_cpu)) { ok = false; continue; }
        if (tem_vulkan) {
            if (!roda_backend(be_gpu, "Vulkan", tipo, W, X, n_rows, ne1, out_gpu)) { ok = false; continue; }
        }

        std::vector<double> ref;
        ref_f64(W_deq, X, n_rows, ne1, ref);
        std::vector<float> reff(ref.size());
        for (size_t i = 0; i < ref.size(); i++) reff[i] = (float)ref[i];
        const Met m_cf = compara_cols(out_cpu, reff, n_rows, ne1);
        const double r = rms_f(out_cpu);
        const double rel_cf = r > 0 ? m_cf.rmse / r : 0.0;
        const bool cpu_ok = rel_cf <= 1e-2;

        if (!tem_vulkan) {
            printf("n_rows=%d: CPUxf64 max=%.6g rmse_rel=%.3g -> %s\n",
                   n_rows, m_cf.max_abs, rel_cf, cpu_ok ? "CPU_OK" : "CPU_ATENCAO");
            if (cpu_ok) confiaveis++; else atencao++;
            continue;
        }

        const Met m_cv = compara_cols(out_cpu, out_gpu, n_rows, ne1);
        const Met m_gf = compara_cols(out_gpu, reff, n_rows, ne1);
        const double rel_gf = r > 0 ? m_gf.rmse / r : 0.0;
        const double rel_cv = r > 0 ? m_cv.rmse / r : 0.0;

        double corr = 0, razao = 0;
        diag_relacao(out_cpu, out_gpu, n_rows, ne1, &corr, &razao);
        // amostras para inspecao visual (5 pontos do comeco da saida)
        printf("n_rows=%d: amostras [CPU | VK | f64]:", n_rows);
        for (int c = 0; c < 5 && c < ne1; c++) {
            const size_t i = (size_t)c;   // r = 0
            printf(" (%g | %g | %g)", (double)out_cpu[i], (double)out_gpu[i], (double)reff[i]);
        }
        printf("\n");

        printf("n_rows=%d: CPUxf64 max=%.6g rmse_rel=%.3g | VKxf64 max=%.6g rmse_rel=%.3g | VKxCPU max=%.6g rmse_rel=%.3g\n",
               n_rows, m_cf.max_abs, rel_cf, m_gf.max_abs, rel_gf, m_cv.max_abs, rel_cv);
        printf("n_rows=%d: DIAG_VKxCPU corr=%.4f razao_mediana(VK/CPU)=%.4f\n", n_rows, corr, razao);

        // Acumulador fp16 no Vulkan pode dar desvio legitimo ~1e-3..1e-2;
        // desvio GROSSEIRO (>=5e-2) denuncia defeito de kernel.
        const char* veredito;
        if (rel_cv > 5e-2 || rel_gf > 1e-1)      { veredito = "VULKAN_GROSSEIRO"; suspeitos++; }
        else if (rel_gf > 1e-2 || rel_cv > 5e-3) { veredito = "VULKAN_ATENCAO"; atencao++; }
        else if (!cpu_ok)                        { veredito = "CPU_ATENCAO"; atencao++; }
        else                                     { veredito = "AMBOS_OK"; confiaveis++; }
        printf("n_rows=%d: VEREDITO=%s\n", n_rows, veredito);
    }

    if (be_gpu) ggml_backend_free(be_gpu);
    ggml_backend_free(be_cpu);

    printf("\nRESUMO: configs=%zu confiaveis=%d atencao=%d suspeitos=%d%s\n",
           rows.size(), confiaveis, atencao, suspeitos, tem_vulkan ? "" : " (sem Vulkan)");
    if (!ok) { printf("MEDICAO: INVALIDA (upload/compute falhou)\n"); return 1; }
    printf("MEDICAO: %s\n", tem_vulkan ? "VALIDA" : "PARCIAL (apenas CPU)");
    return 0;
}
