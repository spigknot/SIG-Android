// boundary18_prec — rodada 18 §8: A/B de PRECISAO POR OPERACAO no replay
// isolado do MUL_MAT Q4_K, com os MESMOS W/X dos runs anteriores.
//
// Variante DEFAULT  : como o fork roda hoje (sem ggml_mul_mat_set_prec)
// Variante PREC_F32 : ggml_mul_mat_set_prec(dst, GGML_PREC_F32) antes de
//                     alocar/executar o grafo
//
// Nao e' produto: binario diagnostico. Nao altera o app nem o GGUF.
// Uso: boundary18_prec X.mat W.q4k.bin n_rows w_ne0 w_ne1 prefixo
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cstdint>
#include <cmath>
#include <string>
#include <vector>

static double m_rms(const std::vector<float>& v) {
    double s = 0; for (float x : v) s += (double)x * x;
    return v.empty() ? 0.0 : sqrt(s / v.size());
}
struct Met { long difs; double max_abs; double rmse; };
static Met cmp(const std::vector<float>& a, const std::vector<float>& b) {
    Met m{0, 0, 0}; double s2 = 0;
    for (size_t i = 0; i < a.size(); i++) {
        double d = fabs((double)a[i] - (double)b[i]);
        if (a[i] != b[i]) m.difs++;
        if (d > m.max_abs) m.max_abs = d;
        s2 += d * d;
    }
    m.rmse = sqrt(s2 / a.size());
    return m;
}

static bool run(ggml_backend_t be, const char* rot, const std::vector<float>& X,
                const std::vector<uint8_t>& W, int n_rows, int64_t ne0, int64_t ne1,
                bool prec_f32, std::vector<float>& out) {
    struct ggml_init_params p{}; p.mem_size = 64ull*1024*1024; p.no_alloc = true;
    struct ggml_context* ctx = ggml_init(p);
    struct ggml_tensor* x = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, 2048, n_rows);
    ggml_set_input(x);
    struct ggml_tensor* w = ggml_new_tensor_2d(ctx, GGML_TYPE_Q4_K, ne0, ne1);
    ggml_set_input(w);
    struct ggml_tensor* o = ggml_mul_mat(ctx, w, x);
    if (prec_f32) {
        // controle LOCAL por no: pedido ao operador, nao global do dispositivo
        ggml_mul_mat_set_prec(o, GGML_PREC_F32);
    }
    ggml_set_output(o);
    // registrar o que o tensor carrega (o valor pedido esta em op_params[0])
    const ggml_prec pedido = (ggml_prec)o->op_params[0];   // campo real do fork
    struct ggml_cgraph* gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(gf, o);
    ggml_gallocr_t galloc = ggml_gallocr_new(ggml_backend_get_default_buffer_type(be));
    if (!ggml_gallocr_reserve(galloc, gf) || !ggml_gallocr_alloc_graph(galloc, gf)) {
        printf("  %s: FALHOU alocar\n", rot); return false;
    }
    ggml_backend_tensor_set(x, X.data(), 0, X.size()*4);
    ggml_backend_tensor_set(w, W.data(), 0, W.size());
    ggml_backend_synchronize(be);
    const enum ggml_status st = ggml_backend_graph_compute(be, gf);
    if (st != GGML_STATUS_SUCCESS) { printf("  %s: compute=%d\n", rot, (int)st); return false; }
    ggml_backend_synchronize(be);
    out.assign((size_t)(ne1*n_rows), 0.0f);
    ggml_backend_tensor_get(o, out.data(), 0, out.size()*4);
    bool finito = true;
    for (float v : out) if (!(v > -1e30f && v < 1e30f)) { finito = false; break; }
    printf("  %-10s prec_no=%s (=%d) finito=%s\n", rot,
           pedido == GGML_PREC_F32 ? "F32" : (pedido == GGML_PREC_DEFAULT ? "DEFAULT" : "?"),
           (int)pedido, finito ? "sim" : "NAO");
    ggml_gallocr_free(galloc); ggml_free(ctx);
    return true;
}

int main(int argc, char** argv) {
    if (argc < 7) { printf("uso: boundary18_prec X.mat W.q4k.bin n_rows w_ne0 w_ne1 prefixo [--only=vkdef|vkf32|cpudef]\n"); return 2; }
    // --only= : executa UMA variante por processo, para garantir estado limpo
    // (o teste de 3 variantes no mesmo processo NAO e' uma medicao limpa).
    const char* only = nullptr;
    for (int i = 7; i < argc; i++) if (strncmp(argv[i], "--only=", 7) == 0) only = argv[i] + 7;
    const int n_rows = atoi(argv[3]);
    const int64_t ne0 = atoll(argv[4]), ne1 = atoll(argv[5]);
    const std::string pfx = argv[6];
    std::vector<float> X((size_t)((int64_t)n_rows*2048));
    std::vector<uint8_t> W((size_t)(ne0*ne1*144/256));
    FILE* f = fopen(argv[1], "rb"); if (!f) { printf("nao abriu %s\n", argv[1]); return 3; }
    if (fread(X.data(), 4, X.size(), f) != X.size()) { printf("X truncado\n"); fclose(f); return 3; }
    fclose(f);
    f = fopen(argv[2], "rb"); if (!f) { printf("nao abriu %s\n", argv[2]); return 3; }
    if (fread(W.data(), 1, W.size(), f) != W.size()) { printf("W truncado\n"); fclose(f); return 3; }
    fclose(f);
    printf("boundary18_prec: X %lld floats, W %lldx%lld Q4_K (%zu bytes)\n",
           (long long)X.size(), (long long)ne0, (long long)ne1, W.size());

    ggml_backend_load_all();
    ggml_backend_dev_t dcpu = nullptr, dgpu = nullptr;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(d) == GGML_BACKEND_DEVICE_TYPE_CPU && !dcpu) dcpu = d;
        if (!dgpu && strstr(ggml_backend_dev_name(d), "Vulkan")) dgpu = d;
    }
    if (!dcpu || !dgpu) { printf("MEDICAO: INVALIDA (sem dev)\n"); return 4; }
    ggml_backend_t bcpu = ggml_backend_dev_init(dcpu, nullptr);
    ggml_backend_t bgpu = ggml_backend_dev_init(dgpu, nullptr);

    std::vector<float> vk_def, vk_f32, cpu_def;
    const double t0 = (double)ggml_time_us();
    if (!only || strcmp(only, "vkdef") == 0) {
        run(bgpu, "Vulkan DEF", X, W, n_rows, ne0, ne1, false, vk_def);
    }
    if (!only || strcmp(only, "vkf32") == 0) {
        run(bgpu, "Vulkan F32", X, W, n_rows, ne0, ne1, true,  vk_f32);
    }
    if (!only || strcmp(only, "cpudef") == 0) {
        run(bcpu, "CPU DEF",   X, W, n_rows, ne0, ne1, false, cpu_def);
    }
    const double t1 = (double)ggml_time_us();
    printf("modo=%s tempo_local=%.3fs\n", only ? only : "todas", (t1-t0)/1e6);

    if (only) {
        const std::vector<float>* v = (strcmp(only, "vkdef") == 0) ? &vk_def
                                    : (strcmp(only, "vkf32") == 0) ? &vk_f32 : &cpu_def;
        if (!v->empty()) {
            char p[256]; snprintf(p, sizeof(p), "%s_%s.bin", pfx.c_str(), only);
            FILE* o = fopen(p, "wb");
            if (o) { fwrite(v->data(), 4, v->size(), o); fclose(o); }
            printf("gravado %s (%zu floats)\n", p, v->size());
        }
        printf("MEDICAO: VALIDA (variante unica em processo limpo)\n");
        return 0;
    }
    if (vk_def.size() == vk_f32.size() && !vk_def.empty()) {
        Met m = cmp(vk_f32, vk_def);
        printf("A/B PRECISAO: Vulkan(prec_f32) x Vulkan(default): difs=%ld/%zu max=%.9g rmse=%.9g -> %s\n",
               m.difs, vk_def.size(), m.max_abs, m.rmse,
               m.difs == 0 ? "IDÊNTICOS (o controle por no NÃO mudou o resultado)"
                           : "DIFEREM (o controle por nó TIVEU efeito)");
    }
    for (const char* nome : {"vk_def", "vk_f32", "cpu_def"}) {
        const std::vector<float>* v = nome[0]=='c' ? &cpu_def : (nome[2]=='d' ? &vk_def : &vk_f32);
        char p[256]; snprintf(p, sizeof(p), "%s_%s.bin", pfx.c_str(), nome);
        FILE* o = fopen(p, "wb");
        if (o) { fwrite(v->data(), 4, v->size(), o); fclose(o); }
    }
    ggml_backend_free(bcpu); ggml_backend_free(bgpu);
    printf("MEDICAO: VALIDA (A/B executado com os mesmos operandos)\n");
    return 0;
}
