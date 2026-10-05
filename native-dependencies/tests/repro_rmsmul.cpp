// REPRODUCER RMS_NORM+MUL (fusion-round, Etapas 5-6).
// Grafo minimo de 2 nos, sem modelo: X[f32 128,16,T] -> RMS_NORM(eps) -> MUL(W[128]).
// Uso: repro_rmsmul <pattern> <vk|cpu> <out.f32>
//   pattern: real29 | ones_w | ones_x | two | ramp
// R0 = cpu; R1 = vk + GGML_VK_DISABLE_FUSION=1; R2 = vk default (fusao).
// Imprime REPRO| checksum + 8 primeiros valores; despeja o .f32 p/ comparar.
#include "ggml.h"
#include "ggml-cpu.h"
#include "ggml-backend.h"
#include "ggml-vulkan.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <string>
#include <vector>

static uint32_t lcg(uint32_t & s) { s = s * 1664525u + 1013904223u; return s; }

// Le o tensor, imprime o resumo e grava o dump. `phase` diz COMO o dump foi
// obtido, para que ninguem confunda "sentinela intacta" com "resultado do run".
static void dump_result(ggml_backend_t be, ggml_tensor * out, const char * outpath,
                        size_t nx, const std::string & pat, const std::string & mode,
                        int T, const char * phase) {
    std::vector<float> od(nx);
    ggml_backend_tensor_get(out, od.data(), 0, nx * sizeof(float));
    double sum = 0, sum2 = 0, mn = od[0], mx = od[0];
    size_t n_nan = 0, n_inf = 0, n_7 = 0;
    for (size_t i = 0; i < nx; i++) {
        sum += od[i]; sum2 += (double)od[i] * od[i];
        if (od[i] < mn) mn = od[i];
        if (od[i] > mx) mx = od[i];
        if (std::isnan(od[i])) n_nan++;
        else if (std::isinf(od[i])) n_inf++;
        else if (od[i] == 7.0f) n_7++;
    }
    fprintf(stderr, "REPRO| pat=%s mode=%s T=%d sum=%.6f sum2=%.6f min=%.6f max=%.6f\n",
        pat.c_str(), mode.c_str(), T, sum, sum2, mn, mx);
    fprintf(stderr, "REPRO| fase=%s nan=%zu inf=%zu n_sete=%zu\n", phase, n_nan, n_inf, n_7);
    fprintf(stderr, "REPRO| oito=");
    for (int i = 0; i < 8; i++) fprintf(stderr, " %.6f", od[i]);
    fprintf(stderr, "\n");
    FILE * f = fopen(outpath, "wb");
    if (!f) { fprintf(stderr, "REPRO| sem escrita %s\n", outpath); return; }
    fwrite(od.data(), sizeof(float), nx, f);
    fclose(f);
    fprintf(stderr, "REPRO| dump=%s bytes=%zu\n", outpath, nx * sizeof(float));
}

int main(int argc, char ** argv) {
    if (argc < 4) { fprintf(stderr, "uso: %s <pattern> <vk|cpu> <out.f32>\n", argv[0]); return 2; }
    std::string pat = argv[1], mode = argv[2];
    const char * outpath = argv[3];
    // 4o arg opcional: caminho do dump pre_dispatch_sentinel.
    const char * prepath = (argc >= 5) ? argv[4] : "";

    int T = (pat == "real29") ? 29 : 4;
    const int N0 = 128, N1 = 16;
    const bool fullw = (pat == "fullw");
    const size_t nx = (size_t)N0 * N1 * T, nw = fullw ? nx : 128;
    std::vector<float> xd(nx), wd(nw);
    uint32_t s = 12345;
    for (size_t i = 0; i < nx; i++) {
        float u = (lcg(s) >> 8) * (1.0f / 8388608.0f); // [0,1)
        xd[i] = (pat == "ones_x") ? 1.0f : (u * 4.0f - 2.0f); // ~[-2,2]
    }
    for (size_t i = 0; i < nw; i++) {
        float u = (lcg(s) >> 8) * (1.0f / 8388608.0f);
        if (pat == "ones_w" || fullw) wd[i] = 1.0f;
        else if (pat == "two") wd[i] = 2.0f;
        else if (pat == "ramp") wd[i] = 0.5f + (float)i * 0.01f;
        else wd[i] = 0.5f + u; // ~[0.5,1.5]
    }

    // Backend-alloc: mem_size p/ objetos, no_alloc=true p/ dados.
    struct ggml_init_params ip = { 16 * 1024 * 1024, NULL, true };
    struct ggml_context * gctx = ggml_init(ip);
#ifndef SIGREPRO_CHECK_BUILD
    fprintf(stderr, "REPRO| ckpt init ok\n");
#endif
    ggml_backend_t be = nullptr;
    if (mode == "vk") {
        be = ggml_backend_vk_init(0);
        if (!be) { fprintf(stderr, "REPRO| sem backend vk\n"); return 3; }
    } else {
        be = ggml_backend_cpu_init();
    }
    fprintf(stderr, "REPRO| ckpt backend ok\n");
    ggml_backend_buffer_t buf = nullptr;
    ggml_tensor * x = ggml_new_tensor_3d(gctx, GGML_TYPE_F32, N0, N1, T);
    ggml_tensor * w = fullw ? ggml_new_tensor_3d(gctx, GGML_TYPE_F32, N0, N1, T)
                            : ggml_new_tensor_1d(gctx, GGML_TYPE_F32, 128);
    fprintf(stderr, "REPRO| ckpt tensores ok\n");
    // Grafo ANTES do alloc: intermediarios (rms/out) tambem ganham buffer.
    struct ggml_cgraph * gr = ggml_new_graph(gctx);
    ggml_tensor * rms = ggml_rms_norm(gctx, x, 1e-5f);
    ggml_tensor * out = ggml_mul(gctx, rms, w);
    ggml_build_forward_expand(gr, out);
    fprintf(stderr, "REPRO| ckpt grafo ok\n");
    buf = ggml_backend_alloc_ctx_tensors(gctx, be); // cobre x, w, rms, out
    fprintf(stderr, "REPRO| ckpt alloc ok buf=%p\n", (void*)buf);
    ggml_backend_tensor_set(x, xd.data(), 0, nx * sizeof(float));
    ggml_backend_tensor_set(w, wd.data(), 0, nw * sizeof(float));
    // Sentinela no destino: se o kernel nao escrever nada, lemos 7.0 de volta.
    // Se escrever zeros, lemos 0.0. Distingue "nao executou" de "zerou".
    { std::vector<float> seven(nx, 7.0f);
      ggml_backend_tensor_set(out, seven.data(), 0, nx * sizeof(float)); }
    // Fase pre_dispatch_sentinel: grava o estado do destino ANTES do compute.
    // E o que permite distinguir "o kernel nao escreveu" de "o arquivo e velho".
    if (getenv("SIGREPRO_DUMP_PRE") != nullptr) {
        dump_result(be, out, prepath, nx, pat, mode, T, "pre_dispatch_sentinel");
    }
    fprintf(stderr, "REPRO| ckpt set ok\n");
    if (ggml_backend_graph_compute(be, gr) != GGML_STATUS_SUCCESS) {
        fprintf(stderr, "REPRO| compute falhou\n"); return 4;
    }
    // Rodrigues: o CHECK aborta DENTRO do graph_compute (e nao retorna), se o
    // build e o instrumentado. Nesse caso o .f32 antigo ficava no disco e era
    // lido como se fosse deste run (foi exatamente a fonte da contradicao).
    // Este bloco so roda se o compute RETORNOU. Ver SIGREPRO_DUMP_PRE.
    dump_result(be, out, outpath, nx, pat, mode, T, "post_dispatch_gpu_result");
    return 0;
}
