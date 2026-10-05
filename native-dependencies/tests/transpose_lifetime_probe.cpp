// transpose_lifetime_probe — prova de lifetime/ordem do caminho
// upload->transpose do backend OpenCL (F3b), executado NO DEVICE.
// (Build local pronto; execucao no alvo pendente de permissao.)
//
// CONTRATO VERIFICADO NA FONTE (03/10): get_tensor RESTAURA o layout canonico
// (kernels restore_block_q8_0/_trans, restore_block_q4_k_trans4_ns etc.) =>
// set_tensor seguido de get_tensor deve devolver os bytes ORIGINAIS (igualdade
// EXATA byte a byte), qualquer que seja o layout interno (transposto/SoA).
// (Determinismo por si SO' nao prova o layout correto — a igualdade prova.)
//
// Casos:
//  1) Q8_0/Q4_K com shapes que DISPARAM o transpose Adreno: gate REAL
//     use_adreno_kernels exige ne0>=512 E ne1>=512 (adreno_cl_compiler>=E031
//     38.11; senao 128/128), alem de ne0%32==0, ne1%4==0, ne2==ne3==1 e
//     elems<128M. TAMANHOS ALTERNADOS entre casos; padrao
//     determinista NAO simetrico; apos set: get IMEDIATO -> igualdade exata;
//     e o contador diag do backend (ggml_opencl_diag_tr_calls) deve CRESCER
//     (prova de que o caminho de transpose engatou neste run).
//  2) Caso de CONTROLE sem transpose (F32; contador NAO cresce) -> igualdade.
//  3) Determinismo: cada caso repete 2x (contextos novos) -> hash igual.
//  4) Ciclo free/re-init do backend (contexto+fila recriados) -> igualdade e
//     hash estaveis.
// Contabiliza tentativas/validos/invalidos e imprime codigos de retorno.
// rc: 0 ok; 1 falha de conteudo/lifetime; 3 sem device OpenCL.
#include "ggml.h"
#include "ggml-backend.h"

#include <cstdio>
#include <cstring>
#include <cstdint>
#include <cstdlib>
#include <vector>

// Contadores DIAG do backend (presentes no build de diagnostico/prof).
extern "C" long long ggml_opencl_diag_tr_calls(void);
extern "C" void      ggml_opencl_diag_reset(void);

static uint64_t fnv1a(const void * p, size_t n) {
    const unsigned char * b = (const unsigned char *)p;
    uint64_t h = 1469598103934665603ull;
    for (size_t i = 0; i < n; ++i) { h ^= b[i]; h *= 1099511628211ull; }
    return h;
}

struct caso { const char * nome; enum ggml_type tipo; int64_t ne0, ne1; bool espera_transpose; };

static int g_tent = 0, g_validos = 0, g_invalidos = 0;

static bool roda_caso(ggml_backend_t be, const struct caso & c,
                      uint64_t * out_hash, long long * out_tr) {
    g_tent++;
    struct ggml_init_params p{};
    p.mem_size = 64ull * 1024 * 1024;
    p.no_alloc = true;
    struct ggml_context * ctx = ggml_init(p);
    if (!ctx) { printf("    ERRO: ggml_init rc=null\n"); g_invalidos++; return false; }

    struct ggml_tensor * t = ggml_new_tensor_2d(ctx, c.tipo, c.ne0, c.ne1);
    if (!t) { printf("    ERRO: new_tensor rc=null\n"); ggml_free(ctx); g_invalidos++; return false; }
    if (!ggml_backend_alloc_ctx_tensors(ctx, be)) {
        printf("    ERRO: alloc_ctx_tensors rc=null\n"); ggml_free(ctx); g_invalidos++; return false;
    }

    const size_t nb = ggml_nbytes(t);
    std::vector<unsigned char> in(nb), out(nb, 0);
    for (size_t i = 0; i < nb; ++i) in[i] = (unsigned char)((i * 131 + 17) & 0xFF);  // nao simetrico

    const long long tr0 = ggml_opencl_diag_tr_calls();
    ggml_backend_tensor_set(t, in.data(), 0, nb);
    ggml_backend_tensor_get(t, out.data(), 0, nb);   // readback IMEDIATO
    const long long tr1 = ggml_opencl_diag_tr_calls();
    *out_tr = tr1 - tr0;

    const bool igual = (memcmp(in.data(), out.data(), nb) == 0);
    *out_hash = fnv1a(out.data(), nb);

    bool ok = igual;
    if (c.espera_transpose && (*out_tr) <= 0) {
        printf("    ERRO: caso deveria transpor mas tr_calls nao cresceu (delta=%lld)\n", *out_tr);
        ok = false;
    }
    if (!c.espera_transpose && (*out_tr) != 0) {
        printf("    ERRO: controle nao deveria transpor mas tr_calls delta=%lld\n", *out_tr);
        ok = false;
    }
    if (!igual) printf("    ERRO: readback != entrada (contrato canonico violado)\n");

    if (ok) g_validos++; else g_invalidos++;
    ggml_free(ctx);
    return ok;
}

int main() {
    ggml_backend_load_all();
    ggml_backend_dev_t dev = ggml_backend_dev_by_name("OpenCL");
    if (!dev) {
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t d = ggml_backend_dev_get(i);
            const char * n = ggml_backend_dev_name(d);
            if (n && strstr(n, "OpenCL")) { dev = d; break; }
        }
    }
    if (!dev) { printf("FALHA: sem device OpenCL\n"); return 3; }
    printf("device: %s (%s)\n", ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));

    struct caso casos[] = {
        { "q8_0_2048x512",  GGML_TYPE_Q8_0, 2048,  512, true  },
        { "q8_0_4096x512",  GGML_TYPE_Q8_0, 4096,  512, true  },   // tamanho alternado
        { "q8_0_2048x1024", GGML_TYPE_Q8_0, 2048, 1024, true  },
        { "q4_K_2048x512",  GGML_TYPE_Q4_K, 2048,  512, true  },
        { "q4_K_4096x1024", GGML_TYPE_Q4_K, 4096, 1024, true  },   // alternado de novo
        { "ctrl_q8_0_abaixo_do_threshold_2048x64", GGML_TYPE_Q8_0, 2048, 64, false },
        { "ctrl_f32_2048x80", GGML_TYPE_F32, 2048, 80, false },    // controle
    };
    const int n = (int)(sizeof(casos) / sizeof(casos[0]));

    printf("== passada 1 (igualdade exata + contador de transpose) ==\n");
    uint64_t h1[32];
    for (int i = 0; i < n; ++i) {
        ggml_opencl_diag_reset();
        long long tr = 0;
        const bool ok = roda_caso(ggml_backend_dev_init(dev, nullptr), casos[i], &h1[i], &tr);
        printf("  %-18s %s hash=%016llx tr_delta=%lld\n", casos[i].nome,
               ok ? "PASS" : "FAIL", (unsigned long long)h1[i], tr);
        if (!ok) { printf("RESULTADO: FAIL (caso %s)\n", casos[i].nome); return 1; }
    }

    printf("== passada 2 (determinismo) ==\n");
    for (int i = 0; i < n; ++i) {
        ggml_opencl_diag_reset();
        uint64_t h2 = 0; long long tr = 0;
        const bool ok = roda_caso(ggml_backend_dev_init(dev, nullptr), casos[i], &h2, &tr);
        const bool igual = (h2 == h1[i]);
        printf("  %-18s %s hash=%016llx %s\n", casos[i].nome, (ok && igual) ? "PASS" : "FAIL",
               (unsigned long long)h2, igual ? "(igual a passada 1)" : "(DIVERGENTE!)");
        if (!ok || !igual) { printf("RESULTADO: FAIL (determinismo em %s)\n", casos[i].nome); return 1; }
    }

    printf("== ciclo free/re-init do backend ==\n");
    {
        ggml_backend_t be = ggml_backend_dev_init(dev, nullptr);
        uint64_t ha = 0; long long tr = 0;
        if (!roda_caso(be, casos[1], &ha, &tr)) { printf("RESULTADO: FAIL (pre-free)\n"); return 1; }
        ggml_backend_free(be);
        ggml_backend_t be2 = ggml_backend_dev_init(dev, nullptr);
        uint64_t hb = 0;
        if (!roda_caso(be2, casos[1], &hb, &tr)) { printf("RESULTADO: FAIL (pos-reinit)\n"); return 1; }
        printf("  pre-free=%016llx pos-reinit=%016llx %s\n",
               (unsigned long long)ha, (unsigned long long)hb, (ha == hb) ? "PASS" : "FAIL");
        ggml_backend_free(be2);
        if (ha != hb) { printf("RESULTADO: FAIL (reinit)\n"); return 1; }
    }

    printf("RESULTADO: PASS | tentativas=%d validos=%d invalidos=%d (8 casos+reinit)\n",
           g_tent, g_validos, g_invalidos);
    return 0;
}
