// q8mm_probe — discriminador da patologia Q8_0 no backend Vulkan.
//
// Pergunta: o kernel de mul_mat com pesos Q8_0 (usado pelo modelo Q8_0 do
// app) calcula certo no Vulkan do alvo, ou produz resultado corrompido?
//
// Metodo (sem GGUF; operandos sinteticos deterministicos):
//   - W Q8_0 [ne0=2048, ne1] com escala fp16 EXATA (d = 2^-6) e qs em
//     [-127,127] gerados por LCG -> dequantizacao de referencia e' exata.
//   - X F32 [2048, n_rows] deterministico.
//   - MESMOS operandos em CPU e em Vulkan; uploads conferidos byte a byte.
//   - Referencia f64 = dequant(W) * X (produto em dupla precisao) para as
//     primeiras N_REF_COLS colunas (custo controlado).
//   - n_rows cobre os caminhos de kernel: 1 (decode / mul_mat_vec),
//     8, 64 (blocos pequenos), 512 (prefill grande).
//
// Veredito por config:
//   CPU x f64 <= 1e-3 rel.  -> CPU confiavel (esperado)
//   Vulkan x f64 grande E CPU x f64 pequeno -> KERNEL VULKAN SUSPEITO
//   ambos pequenos -> KERNEL OK neste caminho
//
// Uso: q8mm_probe [ne1] [n_rows_lista]     (default ne1=2048, "1,8,64,512")
// rc=0 todas as medições validas (uploads bit-exatos); rc=1 se invalida.

#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"

#include <cstdio>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <vector>

static const int    NE0     = 2048;   // n_embd (fixo no modelo)
static const int    N_REF   = 256;    // colunas com referencia f64

// fp16 EXATO para 2^-6: s=0 exp=9 mant=0  -> 0x2400
static const uint16_t D_HALF = 0x2400u;
static const float    D_VAL  = 0x1p-6f;   // 2^-6 = 1/64

// decodificador fp16 -> f32 (sem depender de headers internos)
static float half_to_f32(uint16_t h) {
    const uint32_t s = (uint32_t)(h >> 15) & 1u;
    const uint32_t e = (uint32_t)(h >> 10) & 0x1Fu;
    const uint32_t m = (uint32_t)(h) & 0x3FFu;
    uint32_t bits;
    if (e == 0) {
        if (m == 0) bits = s << 31;
        else {
            // subnormal: normaliza
            int shift = 0;
            uint32_t mm = m;
            while ((mm & 0x400u) == 0) { mm <<= 1; shift++; }
            mm &= 0x3FFu;
            const uint32_t exp32 = (uint32_t)(127 - 15 - shift);
            bits = (s << 31) | (exp32 << 23) | (mm << 13);
        }
    } else if (e == 31) {
        bits = (s << 31) | (0xFFu << 23) | (m << 13);
    } else {
        bits = (s << 31) | ((e - 15 + 127) << 23) | (m << 13);
    }
    float f;
    memcpy(&f, &bits, 4);
    return f;
}

// Gera W Q8_0 [NE0 x ne1] (blocos de 32: 2B d + 32B qs) e devolve a versao
// dequantizada em f32 para a referencia.
static void gerar_w_q8(std::vector<uint8_t>& raw, std::vector<float>& w_deq,
                       int ne1, uint32_t seed) {
    const int64_t elems = (int64_t)NE0 * ne1;
    const int64_t nblocks = elems / 32;
    raw.assign((size_t)nblocks * 34, 0);
    w_deq.assign((size_t)elems, 0.0f);
    uint32_t st = seed ? seed : 1u;
    for (int64_t b = 0; b < nblocks; b++) {
        uint8_t* blk = raw.data() + b * 34;
        blk[0] = (uint8_t)(D_HALF & 0xFF);
        blk[1] = (uint8_t)(D_HALF >> 8);
        for (int i = 0; i < 32; i++) {
            // LCG deterministico
            st = st * 1664525u + 1013904223u;
            int8_t q = (int8_t)((st >> 24) % 255 - 127);   // [-127,127]
            blk[2 + i] = (uint8_t)q;
            w_deq[(size_t)b * 32 + i] = (float)q * D_VAL;
        }
    }
}

static void gerar_x(std::vector<float>& X, int n_rows, uint32_t seed) {
    X.resize((size_t)NE0 * n_rows);
    uint32_t st = seed ? seed : 7u;
    for (size_t i = 0; i < X.size(); i++) {
        st = st * 1103515245u + 12345u;
        const float u = (float)((st >> 16) & 0x7FFFu) / 32768.0f;  // [0,1)
        X[i] = sinf((float)i * 0.013f) * 0.8f + (u - 0.5f) * 0.4f;
    }
}

static double rms_f(const std::vector<float>& v) {
    double s = 0; for (float x : v) s += (double)x * x;
    return v.empty() ? 0.0 : sqrt(s / (double)v.size());
}

// executa mul_mat(w Q8_0, x F32) no backend dado; valida uploads W e X.
static bool roda_backend(ggml_backend_t be, const char* nome,
                         const std::vector<uint8_t>& W,
                         const std::vector<float>& X,
                         int n_rows, int ne1,
                         std::vector<float>& out) {
    bool ok = true;
    struct ggml_init_params p{}; p.mem_size = 0;
    // tamanho de contexto: folga p/ grafo + descritores
    const size_t mem = (size_t)64 * 1024 * 1024;
    struct ggml_context* ctx = ggml_init({mem, nullptr, true});
    (void)p;
    struct ggml_tensor* x = ggml_new_tensor_2d(ctx, GGML_TYPE_F32,  NE0, n_rows);
    struct ggml_tensor* w = ggml_new_tensor_2d(ctx, GGML_TYPE_Q8_0, NE0, ne1);
    ggml_set_input(x);
    ggml_set_input(w);
    struct ggml_tensor* o = ggml_mul_mat(ctx, w, x);
    ggml_set_output(o);
    struct ggml_cgraph* gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, o);

    const size_t w_bytes = ggml_nbytes(w);
    if (w_bytes != W.size()) {
        printf("  %s: nbytes(W)=%zu != gerado=%zu -> INVALIDO\n", nome, w_bytes, W.size());
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

    // conferencia de upload: W byte a byte; X byte a byte
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
        printf("  %s: upload X %s (%ld/%zu bytes dif)\n", nome,
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

struct Met { double max_abs = 0, rmse = 0; long difs = 0; };

// compara um subconjunto de colunas [0,N_REF) de duas saidas [ne1 x n_rows].
// Layout ggml: ne0 (=ne1) e' o eixo rapido -> idx = c + r*ne1.
static Met compara_cols(const std::vector<float>& a, const std::vector<float>& b,
                        int n_rows, int ne1) {
    Met m;
    double s2 = 0; long n = 0;
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

// referencia f64: dequant(W) * X nas colunas [0,N_REF), MESMO layout [ne1 x n_rows]
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

int main(int argc, char** argv) {
    int ne1 = NE0;
    const char* rows_list = "1,8,64,512";
    if (argc > 1) ne1 = atoi(argv[1]);
    if (argc > 2) rows_list = argv[2];
    if (ne1 <= 0 || (ne1 % 32) != 0) { printf("uso: q8mm_probe [ne1(%%32==0)] [n_rows,...]\n"); return 2; }

    std::vector<int> rows;
    {
        const char* s = rows_list;
        while (*s) {
            char* end = nullptr;
            long v = strtol(s, &end, 10);
            if (end == s || v <= 0) { printf("lista de rows invalida: %s\n", rows_list); return 2; }
            rows.push_back((int)v);
            s = (*end == ',') ? end + 1 : end;
            if (*end == '\0') break;
        }
    }

    printf("Q8MM_PROBE ne0=%d ne1=%d rows=", NE0, ne1);
    for (size_t i = 0; i < rows.size(); i++) printf("%s%d", i ? "," : "", rows[i]);
    printf(" N_REF=%d\n", N_REF);

    std::vector<uint8_t> W;
    std::vector<float> W_deq;
    gerar_w_q8(W, W_deq, ne1, 0xC0FFEEu);
    printf("W Q8_0: %lld blocos, %zu bytes gerados\n",
           (long long)((int64_t)NE0 * ne1 / 32), W.size());

    ggml_backend_load_all();
    ggml_backend_dev_t dev_cpu = nullptr, dev_gpu = nullptr;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (!dev_cpu && ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU) dev_cpu = d;
        if (!dev_gpu && strstr(ggml_backend_dev_name(d), "Vulkan")) dev_gpu = d;
    }
    if (!dev_cpu) { printf("MEDICAO: INVALIDA (sem dev CPU)\n"); return 4; }
    const bool tem_vulkan = (dev_gpu != nullptr);
    if (!tem_vulkan) {
        printf("AVISO: sem device Vulkan neste host — validacao apenas CPU x f64\n");
    }

    ggml_backend_t be_cpu = ggml_backend_dev_init(dev_cpu, nullptr);
    ggml_backend_t be_gpu = tem_vulkan ? ggml_backend_dev_init(dev_gpu, nullptr) : nullptr;
    printf("alvo CPU=%s%s%s\n", ggml_backend_dev_name(dev_cpu),
           tem_vulkan ? " GPU=" : "", tem_vulkan ? ggml_backend_dev_name(dev_gpu) : "");

    bool ok = true;
    int suspeitos = 0, confiaveis = 0, atencao = 0;
    for (int n_rows : rows) {
        printf("\n== n_rows=%d ==\n", n_rows);
        std::vector<float> X;
        gerar_x(X, n_rows, 12345u + (uint32_t)n_rows);

        std::vector<float> out_cpu, out_gpu;
        const bool ok_cpu = roda_backend(be_cpu, "CPU   ", W, X, n_rows, ne1, out_cpu);
        if (!ok_cpu) { ok = false; continue; }
        if (tem_vulkan) {
            const bool ok_gpu = roda_backend(be_gpu, "Vulkan", W, X, n_rows, ne1, out_gpu);
            if (!ok_gpu) { ok = false; continue; }
        }

        std::vector<double> ref;
        ref_f64(W_deq, X, n_rows, ne1, ref);
        std::vector<float> reff(ref.size());
        for (size_t i = 0; i < ref.size(); i++) reff[i] = (float)ref[i];
        const Met m_cf = compara_cols(out_cpu, reff, n_rows, ne1);
        const double r = rms_f(out_cpu);   // escala de referencia (nao-zero)
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

        printf("n_rows=%d: CPUxf64 max=%.6g rmse_rel=%.3g | VKxf64 max=%.6g rmse_rel=%.3g | VKxCPU max=%.6g rmse_rel=%.3g\n",
               n_rows, m_cf.max_abs, rel_cf, m_gf.max_abs, rel_gf, m_cv.max_abs, rel_cv);

        // Limiares: kernels quantizados no Vulkan podem usar acumulador fp16
        // (desvio legitimo da ordem de 1e-3..1e-2 relativo ao f64). O que
        // denuncia defeito de kernel e' desvio GROSSEIRO (>=5e-2) entre CPU e
        // Vulkan ou erro absoluto enorme.
        const char* veredito;
        if (rel_cv > 5e-2 || rel_gf > 1e-1) {
            veredito = "VULKAN_GROSSEIRO"; suspeitos++;
        } else if (rel_gf > 1e-2 || rel_cv > 5e-3) {
            veredito = "VULKAN_ATENCAO"; atencao++;
        } else if (!cpu_ok) {
            veredito = "CPU_ATENCAO"; atencao++;
        } else {
            veredito = "AMBOS_OK"; confiaveis++;
        }
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
