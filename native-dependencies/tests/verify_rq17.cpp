// verify_rq17 — rodada 17 §3: quantiza X em Q8_K com a ROTINA C REAL
// (quantize_row_q8_K do fork) e exporta os bytes, para comparar com a
// transcricao Python. Nao e' produto: build diagnostico isolado.
// Uso: verify_rq17 <X.mat> <n_rows> <n_embd> <saida.bin>
#include "ggml.h"
#include "ggml-cpu.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <vector>
#include <string>

// a rotina exportada do fork (mesma que o trait GGML_TYPE_Q8_K usa)
extern "C" void quantize_row_q8_K(const float * x, void * y, int64_t k);

int main(int argc, char** argv) {
    if (argc < 5) { printf("uso: verify_rq17 X.mat n_rows n_embd saida.bin\n"); return 2; }
    const int n_rows = atoi(argv[2]);
    const int n_embd = atoi(argv[3]);
    const int64_t nelem = (int64_t)n_rows * n_embd;

    std::vector<float> X((size_t)nelem);
    FILE* f = fopen(argv[1], "rb");
    if (!f) { printf("verify: nao abriu %s\n", argv[1]); return 3; }
    size_t rd = fread(X.data(), 4, (size_t)nelem, f);
    fclose(f);
    if (rd != (size_t)nelem) { printf("verify: X truncado (%zu/%lld)\n", rd, (long long)nelem); return 3; }

    // um bloco = QK_K floats -> um block_q8_K (float d + int8 qs[QK_K] + int16 bsums[])
    const int64_t nblk_total = nelem / 256;
    const size_t bytes_por_blk = 4 + 256 + (256/16)*2;   // 292 bytes
    std::vector<uint8_t> out((size_t)(nblk_total * bytes_por_blk));
    uint8_t* dst = out.data();

    for (int64_t b = 0; b < nblk_total; ++b) {
        quantize_row_q8_K(X.data() + b*256, dst, 256);
        dst += bytes_por_blk;
    }

    FILE* o = fopen(argv[4], "wb");
    if (!o) { printf("verify: nao abriu %s\n", argv[4]); return 3; }
    size_t w = fwrite(out.data(), 1, out.size(), o);
    int rc = fclose(o);
    if (w != out.size() || rc != 0) { printf("verify: escrita incompleta\n"); return 3; }
    printf("verify_rq17: %ld linhas x %d -> %lld blocos Q8_K, %zu bytes gravados (%s)\n",
           (long)n_rows, n_embd, (long long)nblk_total, out.size(), argv[4]);
    return 0;
}
