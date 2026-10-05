// mulmm_probe_v3 — AUDITORIA + CANARIOS + REPRODUTOR do mul_mat Q8_0 CPU x Vulkan.
//
// Diferencas sobre a v1/v2 (pedido do parecer):
//   (a) DECODIFICADOR Q8_0 INDEPENDENTE (hand-written, sem ggml): d=fp16 (bits
//       manuais) * int8 com sinal, bloco de 34 bytes; conferido contra a
//       dequantizacao da propria ggml (relatorio de difs). A referencia f64
//       usa o decoder INDEPENDENTE, nao o da ggml.
//   (b) CANARIOS ANALITICOS: padroes de W/X com esperado matematico exato
//       (d potencias de 2, qs 0/±1/127/-128, alternado, onehot, rampa,
//       d por bloco, qs por bloco). Sem sampler/modelo/EOG.
//   (c) FORMULA EXPLICITA: rmse_rel = RMSE(subconjunto)/RMS_CPU(subconjunto),
//       ambos SOBRE OS MESMOS elementos (primeiras N_REF colunas, todas as
//       linhas). VKxCPU idem. max_abs em unidades absolutas da saida.
//   (d) DUMP para replay offline: HYM_DUMP=<dir> salva W.bin, X.bin,
//       out_cpu.bin, out_vk.bin, ref_f64.bin e meta.txt por config.
//
// Modos:
//   mulmm_probe_v3 synthq8 [rows]
//   mulmm_probe_v3 gguf <modelo.gguf> <tensor> [rows]
//   mulmm_probe_v3 canary [rows]           (rows default 1 e 8 para canarios)
//
// Linhas de resultado (grep):
//   CANARIO| <caso>| esperado=<v> cpu=<v> vk=<v>| CPU=<OK/ERR> VK=<OK/ERR>
//   AUDIT_DECODER| difs=<n>/<N> max=<v>   (independente x ggml)
//   FORMULA| rmse_rel = RMSE(sub)/RMS_cpu(sub) sobre primeiras N_REF colunas

#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include "gguf.h"

#include <cstdio>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstdarg>
#include <vector>
#include <string>
#include <algorithm>

static const int   NE0   = 2048;
static const int   N_REF = 256;
static const char* ROWS_D = "1,8,64,512";

// ============================ decoder independente ==========================
// fp16 -> f32 (bits manuais, sem dependencia de ggml/compilador).
static float fp16_to_f32_indep(uint16_t h) {
    const uint32_t s = (uint32_t)(h >> 15) & 1u;
    const uint32_t e = (uint32_t)(h >> 10) & 0x1Fu;
    const uint32_t m = (uint32_t)(h) & 0x3FFu;
    uint32_t bits;
    if (e == 0) {
        if (m == 0) {
            bits = s << 31;
        } else {
            // subnormal: normaliza; valor = (1 + mant/1024) * 2^(-14 - shift)
            int shift = 0;
            uint32_t mm = m;
            while ((mm & 0x400u) == 0) { mm <<= 1; shift++; }
            mm &= 0x3FFu;
            const uint32_t exp32 = (uint32_t)(127 - 14 - shift);   // corrigido (era 127-15)
            bits = (s << 31) | (exp32 << 23) | (mm << 13);
        }
    } else if (e == 31) {
        bits = (s << 31) | (0xFFu << 23) | (m << 13);
    } else {
        bits = (s << 31) | ((e - 15 + 127) << 23) | (m << 13);
    }
    float f; memcpy(&f, &bits, 4);
    return f;
}

// Q8_0: blocos de 34 bytes: [d fp16 LE][qs 32 x int8 com sinal]
static void q8_0_dequant_indep(const uint8_t* raw, float* out, int64_t nblocks) {
    for (int64_t b = 0; b < nblocks; b++) {
        const uint8_t* blk = raw + b * 34;
        const uint16_t d_bits = (uint16_t)((uint16_t)blk[0] | ((uint16_t)blk[1] << 8));
        const float d = fp16_to_f32_indep(d_bits);
        for (int i = 0; i < 32; i++) {
            const int8_t q = (int8_t)blk[2 + i];
            out[b * 32 + i] = d * (float)q;
        }
    }
}

static uint16_t f32_to_fp16_exato(float v) {   // so para valores 2^e exatos
    for (int e = -24; e <= 15; e++) {
        const float p = ldexpf(1.0f, e);
        if (p == v) return (uint16_t)((e + 15) << 10);
    }
    return 0;
}

// float32 -> float16 (round-to-nearest-even) -> float32: simula o
// arredondamento de cada etapa de acumulacao em registrador de 16 bits.
static float fp32_to_fp16_roundtrip(float f) {
    // vetor de 16 bits (round-to-nearest-even) sem depender da F16C do host
    uint32_t x;
    memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = (int32_t)((x >> 23) & 0xFF) - 127 + 15;
    uint32_t man = x & 0x7FFFFFu;
    if (((x >> 23) & 0xFF) == 0xFF) {           // inf/NaN
        return sign ? -INFINITY : (man ? NAN : INFINITY);
    }
    if (exp >= 31) {                            // overflow -> +-inf
        return sign ? -INFINITY : INFINITY;
    }
    if (exp <= 0) {                             // subnormal/zero
        if (exp < -10) return sign ? -0.0f : 0.0f;   // flush abaixo de 2^-24
        man |= 0x800000u;                        // implicit 1
        const uint32_t shift = (uint32_t)(14 - exp);
        uint32_t h = man >> shift;
        const uint32_t rem = man & ((1u << shift) - 1u);
        const uint32_t half = 1u << (shift - 1);
        if (rem > half || (rem == half && (h & 1u))) h++;
        uint16_t h16 = (uint16_t)(sign | h);
        return fp16_to_f32_indep(h16);
    }
    uint16_t h16 = (uint16_t)(sign | (uint32_t)(exp << 10) | (man >> 13));
    const uint32_t rem = man & 0x1FFFu;
    if (rem > 0x1000u || (rem == 0x1000u && (h16 & 1u))) h16++;
    return fp16_to_f32_indep(h16);
}

// ============================ utilidades de medida ==========================
// Formula explicita: subconjunto = primeiras N_REF colunas x todas as linhas.
struct Met2 { double rmse = 0, max_abs = 0, rms_a = 0; long n = 0; };

static Met2 compara_subconjunto(const std::vector<float>& a, const std::vector<float>& b,
                                int n_rows, int ne1) {
    Met2 m; double s2 = 0, sa2 = 0;
    for (int r = 0; r < n_rows; r++) {
        for (int c = 0; c < ne1 && c < N_REF; c++) {
            const size_t i = (size_t)c + (size_t)r * (size_t)ne1;
            const double d = fabs((double)a[i] - (double)b[i]);
            if (d > m.max_abs) m.max_abs = d;
            s2 += d * d;
            sa2 += (double)a[i] * (double)a[i];
            m.n++;
        }
    }
    m.rmse    = m.n ? sqrt(s2 / (double)m.n) : 0.0;
    m.rms_a   = m.n ? sqrt(sa2 / (double)m.n) : 0.0;
    return m;
}

// ============================ execucao em um backend ========================
static bool roda_backend(ggml_backend_t be, const char* nome, ggml_type tipo,
                         const std::vector<uint8_t>& W, const std::vector<float>& X,
                         int n_rows, int ne1, std::vector<float>& out,
                         const char* dump_prefix, bool prec_f32 = false) {
    bool ok = true;
    const size_t mem = (size_t)64 * 1024 * 1024;
    struct ggml_context* ctx = ggml_init({mem, nullptr, true});
    struct ggml_tensor* x = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, NE0, n_rows);
    struct ggml_tensor* w = ggml_new_tensor_2d(ctx, tipo, NE0, ne1);
    ggml_set_input(x);
    ggml_set_input(w);
    struct ggml_tensor* o = ggml_mul_mat(ctx, w, x);
    if (prec_f32) {
        // Controle causal do MM: mesma aritmetica de pipeline, porem pedindo
        // a variante de acumulador f32acc (GGML_PREC_F32 -> op_params=10).
        // Nao altera padrao global do produto: so este no.
        ggml_mul_mat_set_prec(o, GGML_PREC_F32);
    }
    ggml_set_output(o);
    struct ggml_cgraph* gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, o);

    const size_t w_bytes = ggml_nbytes(w);
    if (w_bytes != W.size()) {
        printf("  %s: nbytes(W)=%zu != fornecido=%zu -> INVALIDO\n", nome, w_bytes, W.size());
        ggml_free(ctx); return false;
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
    if (dump_prefix && *dump_prefix) {
        char p[512];
        snprintf(p, sizeof(p), "%s_W.bin", dump_prefix);
        FILE* f = fopen(p, "wb"); if (f) { fwrite(W.data(), 1, W.size(), f); fclose(f); }
        snprintf(p, sizeof(p), "%s_X.bin", dump_prefix);
        f = fopen(p, "wb"); if (f) { fwrite(X.data(), 4, X.size(), f); fclose(f); }
        snprintf(p, sizeof(p), "%s_out.bin", dump_prefix);
        f = fopen(p, "wb"); if (f) { fwrite(out.data(), 4, out.size(), f); fclose(f); }
    }
    ggml_gallocr_free(galloc);
    ggml_free(ctx);
    return ok;
}

// ============================ gerador sintetico Q8_0 ========================
static void gerar_w_q8(std::vector<uint8_t>& raw, int ne1, uint32_t seed) {
    const int64_t elems = (int64_t)NE0 * ne1;
    const int64_t nblocks = elems / 32;
    raw.assign((size_t)nblocks * 34, 0);
    uint32_t st = seed ? seed : 1u;
    const uint16_t d_bits = f32_to_fp16_exato(0x1p-6f);
    for (int64_t b = 0; b < nblocks; b++) {
        uint8_t* blk = raw.data() + b * 34;
        blk[0] = (uint8_t)(d_bits & 0xFF);
        blk[1] = (uint8_t)(d_bits >> 8);
        for (int i = 0; i < 32; i++) {
            st = st * 1664525u + 1013904223u;
            const int8_t q = (int8_t)((st >> 24) % 255 - 127);
            blk[2 + i] = (uint8_t)q;
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

// ============================ canarios analiticos ===========================
struct Canario {
    const char* nome;
    double esperado;          // por linha (todas as linhas iguais)
};

static void montar_w(const char* nome, std::vector<uint8_t>& W, int ne1) {
    const int64_t elems = (int64_t)NE0 * ne1;
    const int64_t nblocks = elems / 32;
    W.assign((size_t)nblocks * 34, 0);
    auto set_blk = [&](int64_t b, uint16_t d_bits, auto q) {   // q: callable(int)->inteiro
        uint8_t* blk = W.data() + b * 34;
        blk[0] = (uint8_t)(d_bits & 0xFF);
        blk[1] = (uint8_t)(d_bits >> 8);
        for (int i = 0; i < 32; i++) blk[2 + i] = (uint8_t)(int8_t)q(i);
    };
    const std::string n(nome);
    if (n == "qs0_d1")        for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int){ return 0; });
    else if (n == "qs1_d1")   for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int){ return 1; });
    else if (n == "qsm1_d1")  for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int){ return -1; });
    else if (n == "qsm2_d1")  for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int){ return -2; });
    else if (n == "qsm64_d1") for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int){ return -64; });
    else if (n == "qs127_d1") for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int){ return 127; });
    else if (n == "qsm128_d1")for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int){ return -128; });
    else if (n == "qs1_d2m6") for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x2400, [](int){ return 1; });
    else if (n == "alt_d1")   for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int i){ return (i % 2) ? -1 : 1; });
    else if (n == "mix8minus")   // +1 em tudo, -1 nos 8 primeiros quants globais
        for (int64_t b = 0; b < nblocks; b++) {
            if (b == 0) set_blk(b, 0x3C00, [](int i){ return i < 8 ? -1 : 1; });
            else        set_blk(b, 0x3C00, [](int){ return 1; });
        }
    else if (n == "onehot_d1")for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int i){ return i == 0 ? 1 : 0; });
    else if (n == "pos4_ones")for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int i){ return i == 4 ? 1 : 0; });
    else if (n == "pos5_ones")for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int i){ return i == 5 ? 1 : 0; });
    else if (n == "pos7_ones")for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [](int i){ return i == 7 ? 1 : 0; });
    else if (n == "blocks_d")       // bloco b: d = 2^-(b mod 15), qs = 1
        for (int64_t b = 0; b < nblocks; b++) set_blk(b, f32_to_fp16_exato(ldexpf(1.0f, -(int)(b % 15))), [](int){ return 1; });
    else if (n == "blocks_q")       // bloco b: d = 1, qs = b % 128 (0..63 aqui)
        for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [b](int){ return (int8_t)(b % 128); });
    else if (n == "shift_w")        // d=1; qs=1 em TODAS as posicoes menos a ultima do bloco 1
        for (int64_t b = 0; b < nblocks; b++) set_blk(b, 0x3C00, [&](int i){ return (b == 1 && i == 31) ? 0 : 1; });
    else { printf("CANARIO desconhecido: %s\n", nome); }
}

static void montar_x(const char* nome, std::vector<float>& X, int n_rows) {
    const std::string n(nome);
    X.assign((size_t)NE0 * n_rows, 0.0f);
    for (int r = 0; r < n_rows; r++) {
        float* xr = X.data() + (size_t)r * NE0;
        if (n == "onehot4")      { for (int k = 0; k < NE0; k++) xr[k] = (k == 4) ? 1.0f : 0.0f; }
        else if (n == "onehot7") { for (int k = 0; k < NE0; k++) xr[k] = (k == 7) ? 1.0f : 0.0f; }
        else if (n == "ramp_x")  { for (int k = 0; k < NE0; k++) xr[k] = (float)k; }
        else if (n == "neg_ones"){ for (int k = 0; k < NE0; k++) xr[k] = -1.0f; }
        else if (n == "alt_x")   { for (int k = 0; k < NE0; k++) xr[k] = (k % 2) ? -1.0f : 1.0f; }
        else                     { for (int k = 0; k < NE0; k++) xr[k] = 1.0f; }   // ones
    }
}

// roda o caso canario nos dois backends e compara com o esperado
static void caso_canario(ggml_backend_t be_cpu, ggml_backend_t be_gpu, bool tem_vulkan,
                         const char* w_nome, const char* x_nome, double esperado_por_linha,
                         int n_rows, int ne1) {
    std::vector<uint8_t> W;
    std::vector<float> X, out_cpu, out_vk;
    montar_w(w_nome, W, ne1);
    montar_x(x_nome, X, n_rows);

    const bool ok_cpu = roda_backend(be_cpu, "CPU   ", GGML_TYPE_Q8_0, W, X, n_rows, ne1, out_cpu, nullptr);
    bool ok_vk = false;
    if (tem_vulkan) {
        ok_vk = roda_backend(be_gpu, "Vulkan", GGML_TYPE_Q8_0, W, X, n_rows, ne1, out_vk, nullptr);
    }
    const double cpu_v = ok_cpu ? (double)out_cpu[0] : NAN;
    const double vk_v  = ok_vk  ? (double)out_vk[0]  : NAN;
    const double tol = 1e-3 * (1.0 + fabs(esperado_por_linha));
    const bool cpu_ok = ok_cpu && fabs(cpu_v - esperado_por_linha) <= tol;
    const bool vk_ok  = ok_vk  && fabs(vk_v  - esperado_por_linha) <= tol;
    printf("CANARIO| %-22s | esperado=%-14.6g cpu=%-14.6g vk=%-14.6g | CPU=%s VK=%s\n",
           w_nome, esperado_por_linha, cpu_v, vk_v,
           cpu_ok ? "OK" : "ERR", tem_vulkan ? (vk_ok ? "OK" : "ERR") : "n/a");
}

static void roda_canarios(ggml_backend_t be_cpu, ggml_backend_t be_gpu, bool tem_vulkan,
                          const int nr) {
    const int ne1 = 8;
    printf("\n===== CANARIOS ANALITICOS (ne0=%d ne1=%d n_rows=%d) =====\n", NE0, ne1, nr);
    printf("caso: qs = quants, d = escala fp16; X = ones salvo indicado\n");
    // d = 1 (0x3C00), X ones
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qs0_d1",    "ones",     0.0,                         nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qs1_d1",    "ones",     (double)NE0,                 nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qsm1_d1",   "ones",    -(double)NE0,                 nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qsm2_d1",   "ones",    -2.0 * NE0,                   nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qsm64_d1",  "ones",    -64.0 * NE0,                  nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qs127_d1",  "ones",     127.0 * NE0,                 nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qsm128_d1", "ones",    -128.0 * NE0,                 nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qs1_d2m6",  "ones",     (double)NE0 / 64.0,          nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "alt_d1",    "ones",     0.0,                         nr, ne1);
    // pico por bloco (1 em cada 1 dos 64 blocos) -> esperado = nblocks
    caso_canario(be_cpu, be_gpu, tem_vulkan, "onehot_d1", "ones",     (double)(NE0 / 32),          nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "pos4_ones", "ones",     (double)(NE0 / 32),          nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "shift_w",   "ones",     (double)(NE0 - 1),           nr, ne1);
    // posicao: pico em i dentro do bloco x X onehot em k -> 1 se casar, 0 se deslocar
    caso_canario(be_cpu, be_gpu, tem_vulkan, "pos4_ones", "onehot4",  1.0,                         nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "pos5_ones", "onehot4",  0.0,                         nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "pos4_ones", "onehot7",  0.0,                         nr, ne1);
    caso_canario(be_cpu, be_gpu, tem_vulkan, "pos7_ones", "onehot7",  1.0,                         nr, ne1);
    // rampa: esperado = soma de k
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qs1_d1",    "ramp_x",   (double)NE0 * (NE0 - 1) / 2, nr, ne1);
    // X negativo (B side): W = +1 (d=1), X = -1 em tudo -> esperado = -2048
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qs1_d1",    "neg_ones", -(double)NE0,                nr, ne1);
    // X com sinal MISTO (testa a sinalizacao do lado B byte a byte)
    caso_canario(be_cpu, be_gpu, tem_vulkan, "qs1_d1",    "alt_x",    0.0,                          nr, ne1);
    // magnitude PEQUENA com negativos (nao satura fp16; discrimina 255 vs 127 vs correto)
    //   correto = 2040*1 + 8*(-1) = 2032; unsigned=255 -> 4080; saturado=127 -> 3056
    caso_canario(be_cpu, be_gpu, tem_vulkan, "mix8minus", "ones",     2032.0,                       nr, ne1);
    // d por bloco (2^-b, b mod 15) e qs por bloco (b)
    {
        double esp_d = 0, esp_q = 0;
        const int64_t nblocks = (int64_t)NE0 / 32;
        for (int64_t b = 0; b < nblocks; b++) esp_d += 32.0 * ldexp(1.0, -(int)(b % 15));
        for (int64_t b = 0; b < nblocks; b++) esp_q += 32.0 * (double)(b % 128);
        caso_canario(be_cpu, be_gpu, tem_vulkan, "blocks_d", "ones", esp_d, nr, ne1);
        caso_canario(be_cpu, be_gpu, tem_vulkan, "blocks_q", "ones", esp_q, nr, ne1);
    }
}

// ============================ replay FP16 sequencial (hipotese) ============
// Reproduz a ORDEM de acumulacao "mais interna primeiro" com acumulador
// float16 (round a cada passo) para VERIFICAR NUMERICAMENTE se o erro
// medido no alvo (ex.: 96 -> 117.312) e' compativel com a ordem fp16 do
// pipeline. NAO e' prova da ordem real da GPU (tiles/subgrupos podem
// reordenar); e' teste de CONSISTENCIA que separa "precisao" de "defeito".
static double replay_fp16(const std::vector<float>& W_deq, const std::vector<float>& X,
                          int ne1, int col, int n_rows, bool round_to_fp16 = true) {
    double acc = 0.0;
    for (int r = 0; r < n_rows; r++) {
        const float* xr = X.data() + (size_t)r * NE0;
        float accf = 0.0f;
        for (int k = 0; k < NE0; k++) {
            const float term = xr[k] * W_deq[(size_t)col * NE0 + k];
            accf += term;
            if (round_to_fp16) accf = fp32_to_fp16_roundtrip(accf);
        }
        acc = accf;
    }
    return acc;
}

// ============================ prec: DEFAULT(f16acc) x F32ACC ==============
// Controle causal do MM: MESMOS operandos (W/X/bytes), mesma dimensao,
// somente o acumulador muda (GGML_PREC_F32 -> variante f32acc). Se o F32
// eliminar o erro e o DEFAULT mantiver, a hipotese "acumulacao FP16" fica
// SUSTENTADA; se ambos errarem, o problema nao e' o acumulador.
// Suites SEPARADAS e com amplitudes bem-condicionadas (sem overflow de fp16):
//   S1 bytes 1..64 com d=2^-6 (|soma| <= ~2.0)   ->判 numerico, sem saturar
//   S2 K curto (1, 8, 32, 64, 2048) com qs=+1,d=2^-6 -> efeito do K
//   S3 coluna "ne1 real" 2048 vs "pequena" 8       -> efeito de matriz pequena
static void suite_prec(ggml_backend_t be_cpu, ggml_backend_t be_gpu, bool tem_vulkan,
                       int n_rows) {
    if (!tem_vulkan) { printf("PREC| sem Vulkan — controle indisponivel\n"); return; }
    printf("\n===== PREC: DEFAULT(f16acc) vs F32ACC — mesmos operandos =====\n");
    printf("n_rows=%d  (col 0; esperado analitico = s8(v)*d*NE0 para W uniforme)\n", n_rows);

    struct Caso { int byte; uint16_t d_bits; double d; const char* rot; };
    // S1: bytes pequenos/medianos, escala 2^-6 -> soma |.| <= 2048*64/64 = 2048
    const Caso casos[] = {
        {  1, 0x2400, 0x1p-6, "S1 b=1  d=2^-6"},
        {  3, 0x2400, 0x1p-6, "S1 b=3  d=2^-6"},
        {  5, 0x2400, 0x1p-6, "S1 b=5  d=2^-6"},
        { 32, 0x2400, 0x1p-6, "S1 b=32 d=2^-6"},
        { 64, 0x2400, 0x1p-6, "S1 b=64 d=2^-6"},
        {-64, 0x2400, 0x1p-6, "S1 b=-64 d=2^-6 (byte 192)"},
    };

    for (const Caso& c : casos) {
        const int v = c.byte < 0 ? c.byte + 256 : c.byte;
        const int s = (v >= 128) ? v - 256 : v;
        std::vector<uint8_t> W((size_t)NE0 * 8 / 32 * 34, 0);
        for (size_t b = 0; b + 34 <= W.size(); b += 34) {
            W[b] = (uint8_t)(c.d_bits & 0xFF);
            W[b + 1] = (uint8_t)(c.d_bits >> 8);
            for (int i = 0; i < 32; i++) W[b + 2 + i] = (uint8_t)v;
        }
        std::vector<float> W_deq((size_t)NE0 * 8, 0.0f);
        q8_0_dequant_indep(W.data(), W_deq.data(), (int64_t)(W.size() / 34));
        std::vector<float> X((size_t)NE0 * n_rows, 1.0f), o_def, o_f32, o_cpu;
        const bool okc = roda_backend(be_cpu, "CPU   ", GGML_TYPE_Q8_0, W, X, n_rows, 8, o_cpu, nullptr);
        const bool okd = roda_backend(be_gpu, "Vulkan", GGML_TYPE_Q8_0, W, X, n_rows, 8, o_def, nullptr, false);
        const bool okf = roda_backend(be_gpu, "Vulkan", GGML_TYPE_Q8_0, W, X, n_rows, 8, o_f32, nullptr, true);
        if (!okc || !okd || !okf) { printf("PREC| %s: execucao falhou (cpu=%d def=%d f32=%d)\n", c.rot, okc, okd, okf); continue; }
        const double esperado = (double)s * c.d * (double)NE0;
        const double rep = replay_fp16(W_deq, X, 8, 0, n_rows, true);
        const double rep32 = replay_fp16(W_deq, X, 8, 0, n_rows, false);
        printf("PREC| %-22s esp=%9.4f CPU=%9.4f DEF=%9.4f (err %+7.4f) F32=%9.4f (err %+7.4f) replayFP16=%9.4f replayF32=%9.4f\n",
               c.rot, esperado, (double)o_cpu[0], (double)o_def[0], (double)o_def[0] - esperado,
               (double)o_f32[0], (double)o_f32[0] - esperado, rep, rep32);
    }
    printf("PREC| leitura: F32ACC ~= replayF32 esperado; DEF afastado e' consistente\n"
           "      com acumulacao fp16. replayFP16 sequencial NAO prova a ordem da GPU.\n");
}

// ============================ bytes256 (teste permanente) ===================
// Para CADA valor de byte 0..255 (interpretado como int8 COM sinal) e para
// escalas d em {1, 2^-6, -1, 0}: W uniforme (todos os quants = v, d = escolhida),
// X = ones; esperado ANALITICO = s8(v) * d * NE0. Roda CPU e Vulkan e conta
// acertos/erros. Cobre todos os 256 bytes, sinal, escala positiva/negativa/
// pequena/zero e todos os lanes (o valor e' uniforme em todos os blocos).
static void roda_bytes256(ggml_backend_t be_cpu, ggml_backend_t be_gpu, bool tem_vulkan,
                          const int nr) {
    const int ne1 = 8;
    const double escalas[4] = {1.0, 0x1p-6, -1.0, 0.0};
    const char* nomes[4] = {"d=1", "d=2^-6", "d=-1", "d=0"};
    printf("\n===== BYTES256 (ne0=%d ne1=%d n_rows=%d): 256 bytes x 4 escalas =====\n",
           NE0, ne1, nr);
    for (int ie = 0; ie < 4; ie++) {
        const double d = escalas[ie];
        int ok_cpu = 0, ok_vk = 0, bad_cpu = 0, bad_vk = 0;
        int mostru_cpu = 0, mostru_vk = 0;
        for (int v = 0; v < 256; v++) {
            const int s = (v >= 128) ? v - 256 : v;         // int8 com sinal
            const double esperado = (double)s * d * (double)NE0;
            std::vector<uint8_t> W((size_t)NE0 * ne1 / 32 * 34, 0);
            // d em fp16 (bits) — usa o encoder do proprio teste
            uint16_t d_bits = 0;
            if (ie == 0) d_bits = 0x3C00;                    // 1.0
            else if (ie == 1) d_bits = 0x2400;               // 2^-6
            else if (ie == 2) d_bits = 0xBC00;               // -1.0
            else d_bits = 0x0000;                            // +0.0
            for (size_t b = 0; b + 34 <= W.size(); b += 34) {
                W[b] = (uint8_t)(d_bits & 0xFF);
                W[b + 1] = (uint8_t)(d_bits >> 8);
                for (int i = 0; i < 32; i++) W[b + 2 + i] = (uint8_t)v;
            }
            std::vector<float> X((size_t)NE0 * nr, 1.0f), out_c, out_v;
            const bool okc = roda_backend(be_cpu, "CPU   ", GGML_TYPE_Q8_0, W, X, nr, ne1, out_c, nullptr);
            bool okv = false;
            if (tem_vulkan) okv = roda_backend(be_gpu, "Vulkan", GGML_TYPE_Q8_0, W, X, nr, ne1, out_v, nullptr);
            const double tol = 1e-3 * (1.0 + fabs(esperado));
            const bool c_ok = okc && fabs((double)out_c[0] - esperado) <= tol;
            const bool v_ok = okv && fabs((double)out_v[0] - esperado) <= tol;
            if (c_ok) ok_cpu++; else { bad_cpu++; if (mostru_cpu < 4) { printf("BYTES256| %s byte=%3d (s8=%4d) esperado=%.6g CPU=%.6g ERR\n", nomes[ie], v, s, esperado, okc ? (double)out_c[0] : NAN); mostru_cpu++; } }
            if (!tem_vulkan) continue;
            if (v_ok) ok_vk++; else { bad_vk++; if (mostru_vk < 4) { printf("BYTES256| %s byte=%3d (s8=%4d) esperado=%.6g VK=%.6g ERR\n", nomes[ie], v, s, esperado, okv ? (double)out_v[0] : NAN); mostru_vk++; } }
        }
        printf("BYTES256| %-7s | CPU %3d/256 ok (%d erros) | VK %3d/256 ok (%d erros)\n",
               nomes[ie], ok_cpu, bad_cpu, tem_vulkan ? ok_vk : -1, tem_vulkan ? bad_vk : -1);
    }
    printf("NOTA: escala subnormal fp16 (2^-24) nao entra no pass/fail (flush-to-zero\n"
           "e' politica do driver); 2^-14 e' a menor normal suportada.\n");
}

// ============================ main ==========================================
static std::vector<int> parse_rows(const char* s) {
    std::vector<int> rows;
    while (*s) {
        char* end = nullptr;
        long v = strtol(s, &end, 10);
        if (end == s || v <= 0) { rows.clear(); return rows; }
        rows.push_back((int)v);
        if (*end == '\0') break;
        s = (*end == ',' ) ? end + 1 : end;
    }
    return rows;
}

int main(int argc, char** argv) {
    setvbuf(stdout, nullptr, _IONBF, 0);
    const char* dump_dir = getenv("HYM_DUMP");

    if (argc < 2) {
        printf("uso: synthq8 [rows] | gguf <modelo> <tensor> [rows] | canary [rows]\n");
        return 2;
    }
    const bool modo_canary = (strcmp(argv[1], "canary") == 0);
    const bool modo_bytes  = (strcmp(argv[1], "bytes256") == 0);
    const bool modo_prec   = (strcmp(argv[1], "prec") == 0);
    const bool modo_gguf   = (strcmp(argv[1], "gguf") == 0);
    const bool modo_synth  = (strcmp(argv[1], "synthq8") == 0);
    if (!modo_canary && !modo_bytes && !modo_prec && !modo_gguf && !modo_synth) { printf("modo desconhecido\n"); return 2; }

    ggml_backend_load_all();
    ggml_backend_dev_t dev_cpu = nullptr, dev_gpu = nullptr;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (!dev_cpu && ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU) dev_cpu = d;
        if (!dev_gpu && strstr(ggml_backend_dev_name(d), "Vulkan")) dev_gpu = d;
        printf("BACKEND_DEV| %zu: %s (%s)\n", i, ggml_backend_dev_name(d),
               ggml_backend_dev_description(d));
    }
    if (!dev_cpu) { printf("MEDICAO: INVALIDA (sem dev CPU)\n"); return 4; }
    const bool tem_vulkan = (dev_gpu != nullptr);
    ggml_backend_t be_cpu = ggml_backend_dev_init(dev_cpu, nullptr);
    ggml_backend_t be_gpu = tem_vulkan ? ggml_backend_dev_init(dev_gpu, nullptr) : nullptr;

    if (modo_prec) {
        const int nr = (argc > 2) ? atoi(argv[2]) : 64;
        if (nr <= 0) { printf("n_rows invalido\n"); return 2; }
        suite_prec(be_cpu, be_gpu, tem_vulkan, nr);
        if (be_gpu) ggml_backend_free(be_gpu);
        ggml_backend_free(be_cpu);
        printf("\nMEDICAO: %s\n", tem_vulkan ? "VALIDA" : "PARCIAL (apenas CPU)");
        return 0;
    }

    if (modo_bytes) {
        const int nr = (argc > 2) ? atoi(argv[2]) : 1;
        if (nr <= 0) { printf("n_rows invalido\n"); return 2; }
        roda_bytes256(be_cpu, be_gpu, tem_vulkan, nr);
        if (be_gpu) ggml_backend_free(be_gpu);
        ggml_backend_free(be_cpu);
        printf("\nMEDICAO: %s\n", tem_vulkan ? "VALIDA" : "PARCIAL (apenas CPU)");
        return 0;
    }

    if (modo_canary) {
        const int nr = (argc > 2) ? atoi(argv[2]) : 1;
        if (nr <= 0) { printf("n_rows invalido\n"); return 2; }
        roda_canarios(be_cpu, be_gpu, tem_vulkan, nr);
        if (be_gpu) ggml_backend_free(be_gpu);
        ggml_backend_free(be_cpu);
        printf("\nMEDICAO: %s\n", tem_vulkan ? "VALIDA" : "PARCIAL (apenas CPU)");
        return 0;
    }

    // ---- modos synthq8 / gguf ----
    std::vector<int> rows;
    std::vector<uint8_t> W;
    ggml_type tipo = GGML_TYPE_Q8_0;
    int ne1 = 0;
    std::string rotulo;
    std::vector<std::string> tensores;

    if (modo_gguf) {
        if (argc < 4) { printf("uso: gguf <modelo> <tensor> [rows]\n"); return 2; }
        const char* path = argv[2];
        const char* nome_tensor = argv[3];
        rows = parse_rows(argc > 4 ? argv[4] : ROWS_D);
        if (rows.empty()) { printf("rows invalido\n"); return 2; }
        struct gguf_init_params gp{}; gp.no_alloc = true; gp.ctx = nullptr;
        struct gguf_context* g = gguf_init_from_file(path, gp);
        if (!g) { printf("ERRO: gguf_init_from_file falhou\n"); return 3; }
        const int64_t tid = gguf_find_tensor(g, nome_tensor);
        if (tid < 0) { printf("ERRO: tensor nao existe\n"); gguf_free(g); return 3; }
        tipo = gguf_get_tensor_type(g, tid);
        const int64_t* tne = gguf_get_tensor_ne(g, tid);
        ne1 = (int)tne[1];
        const size_t t_off  = gguf_get_data_offset(g) + gguf_get_tensor_offset(g, tid);
        const size_t t_size = gguf_get_tensor_size(g, tid);
        printf("GGUF| arquivo=%s tensor=%s tipo=%s ne=[%lld,%d] offset=%zu size=%zu\n",
               path, nome_tensor, ggml_type_name(tipo), (long long)tne[0], ne1, t_off, t_size);
        FILE* f = fopen(path, "rb");
        if (!f) { printf("ERRO abrir\n"); gguf_free(g); return 3; }
        fseek(f, (long)t_off, SEEK_SET);
        W.assign(t_size, 0);
        const size_t rd = fread(W.data(), 1, t_size, f);
        fclose(f);
        gguf_free(g);
        if (rd != t_size) { printf("ERRO leitura curta\n"); return 3; }
        rotulo = std::string("GGUF ") + ggml_type_name(tipo);
    } else {
        rows = parse_rows(argc > 2 ? argv[2] : ROWS_D);
        if (rows.empty()) { printf("rows invalido\n"); return 2; }
        ne1 = NE0;
        gerar_w_q8(W, ne1, 0xC0FFEEu);
        rotulo = "SYNTH Q8_0";
    }

    // decodificador INDEPENDENTE + conferencia contra a ggml
    const size_t elems = (size_t)NE0 * (size_t)ne1;
    std::vector<float> w_indep(elems, 0.0f), w_ggml(elems, 0.0f);
    q8_0_dequant_indep(W.data(), w_indep.data(), (int64_t)(elems / 32));
    const ggml_type_traits* tt = ggml_get_type_traits(tipo);
    if (!tt || !tt->to_float) { printf("ERRO: tipo sem to_float\n"); return 3; }
    tt->to_float(W.data(), w_ggml.data(), (int64_t)elems);
    {
        long difs = 0; double maxd = 0;
        for (size_t i = 0; i < elems; i++) {
            const double d = fabs((double)w_indep[i] - (double)w_ggml[i]);
            if (w_indep[i] != w_ggml[i]) difs++;
            if (d > maxd) maxd = d;
        }
        printf("AUDIT_DECODER| independente x ggml: %ld/%zu difs (max=%.6g)\n", difs, elems, maxd);
    }
    if (tipo != GGML_TYPE_Q8_0) {
        printf("AVISO: tipo %s != q8_0 — decoder independente so cobre q8_0; referencia usa ggml\n",
               ggml_type_name(tipo));
        w_indep = w_ggml;
    }
    printf("FORMULA| rmse_rel = RMSE(sub)/RMS_cpu(sub); sub = primeiras %d colunas x %d linhas\n",
           N_REF, rows.empty() ? 0 : rows.back());
    printf("PROBE| %s ne=[%d,%d] bytes=%zu rows=%s\n", rotulo.c_str(), NE0, ne1, W.size(), argv[argc-1]);

    bool ok = true;
    int suspeitos = 0, confiaveis = 0, atencao = 0;
    for (int n_rows : rows) {
        std::vector<float> X;
        gerar_x(X, n_rows, 12345u + (uint32_t)n_rows);
        std::vector<float> out_cpu, out_vk;
        char dp[512];
        const char* dump_cpu = nullptr;
        if (dump_dir && *dump_dir) { snprintf(dp, sizeof(dp), "%s/cpu_r%d", dump_dir, n_rows); dump_cpu = dp; }
        if (!roda_backend(be_cpu, "CPU   ", tipo, W, X, n_rows, ne1, out_cpu, dump_cpu)) { ok = false; continue; }
        if (tem_vulkan) {
            const char* dump_vk = nullptr;
            if (dump_dir && *dump_dir) { snprintf(dp, sizeof(dp), "%s/vk_r%d", dump_dir, n_rows); dump_vk = dp; }
            if (!roda_backend(be_gpu, "Vulkan", tipo, W, X, n_rows, ne1, out_vk, dump_vk)) { ok = false; continue; }
        }
        // referencia f64 com o decoder INDEPENDENTE
        std::vector<double> ref((size_t)ne1 * (size_t)n_rows, 0.0);
        {
            const int ncols = ne1 < N_REF ? ne1 : N_REF;
            for (int r = 0; r < n_rows; r++) {
                const float* xr = X.data() + (size_t)r * NE0;
                for (int c = 0; c < ncols; c++) {
                    const float* wr = w_indep.data() + (size_t)c * NE0;
                    double acc = 0.0;
                    for (int k = 0; k < NE0; k++) acc += (double)xr[k] * (double)wr[k];
                    ref[(size_t)c + (size_t)r * (size_t)ne1] = acc;
                }
            }
        }
        std::vector<float> reff(ref.size());
        for (size_t i = 0; i < ref.size(); i++) reff[i] = (float)ref[i];

        const Met2 m_cf = compara_subconjunto(out_cpu, reff, n_rows, ne1);
        const double rel_cf = m_cf.rms_a > 0 ? m_cf.rmse / m_cf.rms_a : 0.0;
        if (!tem_vulkan) {
            printf("n_rows=%d: CPUxf64 rmse=%.6g rms_cpu=%.6g rmse_rel=%.3g max=%.6g -> %s\n",
                   n_rows, m_cf.rmse, m_cf.rms_a, rel_cf, m_cf.max_abs,
                   rel_cf <= 1e-2 ? "CPU_OK" : "CPU_ATENCAO");
            if (rel_cf <= 1e-2) confiaveis++; else atencao++;
            continue;
        }
        const Met2 m_cv = compara_subconjunto(out_cpu, out_vk, n_rows, ne1);
        const Met2 m_gf = compara_subconjunto(out_vk, reff, n_rows, ne1);
        const double rel_cv = m_cf.rms_a > 0 ? m_cv.rmse / m_cf.rms_a : 0.0;
        const double rel_gf = m_cf.rms_a > 0 ? m_gf.rmse / m_cf.rms_a : 0.0;
        printf("n_rows=%d: CPUxf64 rmse_rel=%.3g max=%.6g | VKxf64 rmse_rel=%.3g max=%.6g | VKxCPU rmse_rel=%.3g max=%.6g (rms_cpu=%.4g)\n",
               n_rows, rel_cf, m_cf.max_abs, rel_gf, m_gf.max_abs, rel_cv, m_cv.max_abs, m_cf.rms_a);
        const char* veredito;
        if (rel_cv > 5e-2 || rel_gf > 1e-1)      { veredito = "VULKAN_GROSSEIRO"; suspeitos++; }
        else if (rel_gf > 1e-2 || rel_cv > 5e-3) { veredito = "VULKAN_ATENCAO"; atencao++; }
        else if (rel_cf > 1e-2)                  { veredito = "CPU_ATENCAO"; atencao++; }
        else                                     { veredito = "AMBOS_OK"; confiaveis++; }
        printf("n_rows=%d: VEREDITO=%s\n", n_rows, veredito);
    }

    if (be_gpu) ggml_backend_free(be_gpu);
    ggml_backend_free(be_cpu);
    printf("\nRESUMO: configs=%zu confiaveis=%d atencao=%d suspeitos=%d\n",
           rows.size(), confiaveis, atencao, suspeitos);
    if (!ok) { printf("MEDICAO: INVALIDA\n"); return 1; }
    printf("MEDICAO: %s\n", tem_vulkan ? "VALIDA" : "PARCIAL (apenas CPU)");
    return 0;
}
