// r46_vacinas.c — R46 §6: AS VACINAS HOST (o código CONSUMIDO — os fixes!)
// Testa: (1) bi=0 ACEITO (era o defeito!); (2) bi POR BATCH (reset -> re-add!);
// (3) seleção não-Q6 -> -1 (OFF!); (4) bounds (fim > cap -> NÃO copia!);
// (5) OFF total (flag '0' -> nunca arma!); (6) o memset do impl NÃO apaga mais
// (a atribuição é DEPOIS!); (7) o descritor (bufs[bi].base — não mmap!).
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

#define HTP_OP_MAX_BUFS 16
#define HTP_MAX_MMAPS   16

struct htp_buf_desc { uint64_t base; uint32_t fd; uint64_t size; };

// ============ os FRAGMENTOS REAIS do patch v2 (extraídos!) ============
// (o add_op — a lógica de seleção/owner/bi!)
static int g_r46_armed = -1;
static int g_sbuf_created = 0;
static int g_sbuf_fd = 77;
static bool g_r46_act_dump_armed(void) {
    if (g_r46_armed < 0) {
        const char * e = getenv("GGML_HEXAGON_ACT_DUMP");
        g_r46_armed = (e && e[0] == '1' && e[1] == 0) ? 1 : 0;
    }
    return g_r46_armed == 1;
}
// o batch (o fragmento!)
static unsigned n_bufs = 0;
static uint64_t b_map_fds[16];
static int b_map_bi[16]; static int b_map_n = 0;
static int r46_dump_bi = -1;
static int add_buffer_sbuf(int fd, uint64_t base, uint64_t size) {
    for (int i = 0; i < b_map_n; i++) if (b_map_fds[i] == fd) return b_map_bi[i];
    int bi = n_bufs++;
    b_map_fds[b_map_n] = fd; b_map_bi[b_map_n] = bi; b_map_n++;
    (void)base; (void)size;
    return bi;
}
static void batch_reset(void) { n_bufs = 0; b_map_n = 0; r46_dump_bi = -1; }
// a selecao (do add_op!)
static int add_op_selecao(const char * s0_type, long s0_ne0, long s0_ne1, const char * s1_type, long s1_ne1, uint64_t base, uint64_t size) {
    int out_bi = -1;
    if (g_r46_act_dump_armed() && strcmp(s0_type, "Q6_K") == 0 && s0_ne0 == 6144 && s0_ne1 == 2048 &&
        strcmp(s1_type, "F32") == 0 && s1_ne1 == 1) {
        if (!g_sbuf_created) { g_sbuf_created = 1; }
        if (r46_dump_bi < 0) r46_dump_bi = add_buffer_sbuf(g_sbuf_fd, base, size);
        if (r46_dump_bi >= 0) out_bi = r46_dump_bi;
    }
    return out_bi;
}
// o op_matmul do skel (o fragmento!)
static uint8_t * octx_act_dump_ptr = NULL;
static size_t octx_act_dump_cap = 0;
static const struct htp_buf_desc * r46_bufs = NULL; static uint32_t r46_nbufs = 0;
static void op_matmul_map(int act_dump_bi, int n_hmx) {
    octx_act_dump_ptr = NULL; octx_act_dump_cap = 0;
    if (act_dump_bi >= 0 && act_dump_bi < (int)r46_nbufs && r46_bufs && !n_hmx) {
        octx_act_dump_ptr = (uint8_t *)(uintptr_t)r46_bufs[act_dump_bi].base;
        octx_act_dump_cap = (size_t)r46_bufs[act_dump_bi].size;
    }
}
// o worker (o fragmento!)
static size_t worker_copy(uint8_t * dst_data, size_t dst_row_size, int ir_first, int ir_last) {
    if (!octx_act_dump_ptr) return 0;
    const size_t off = (size_t)dst_row_size * ir_first;
    const size_t len = (size_t)dst_row_size * (ir_last - ir_first);
    if (off <= octx_act_dump_cap && len <= octx_act_dump_cap - off) {
        memcpy(octx_act_dump_ptr + off, dst_data, len);
        return len;
    }
    return 0;   // RECUSA (sem overflow!)
}
// =====================================================================

int main(void) {
    int fails = 0;
    uint8_t dump_storage[262144]; memset(dump_storage, 0xEE, sizeof(dump_storage));
    uint8_t row_data[221184]; memset(row_data, 0xA5, sizeof(row_data));
    struct htp_buf_desc bufs[4]; memset(bufs, 0, sizeof(bufs));
    bufs[0].base = (uint64_t)(uintptr_t)dump_storage; bufs[0].size = 262144; bufs[0].fd = 77;
    r46_bufs = bufs; r46_nbufs = 1;

    // ---- C1: bi=0 ACEITO (o defeito R45: 'if (bi > 0)' rejeitava!) ----
    putenv("GGML_HEXAGON_ACT_DUMP=1");
    g_r46_armed = -1; g_sbuf_created = 0; batch_reset();
    int bi0 = add_op_selecao("Q6_K", 6144, 2048, "F32", 1, bufs[0].base, bufs[0].size);
    printf("C1 bi_do_batch=%d (exigido 0! — ACEITO!)\n", bi0);
    if (bi0 != 0) { fails++; printf("C1 FAIL\n"); }

    // ---- C2: POR BATCH: reset -> re-add (novo bi 0 — sem stale!) ----
    batch_reset();
    int bi1 = add_op_selecao("Q6_K", 6144, 2048, "F32", 1, bufs[0].base, bufs[0].size);
    printf("C2 apos reset: bi=%d n_bufs=%u (re-add OK!)\n", bi1, n_bufs);
    if (bi1 != 0 || n_bufs != 1) { fails++; printf("C2 FAIL\n"); }

    // ---- C3: selecao NAO-Q6 -> -1 (outro shape!) ----
    batch_reset();
    int bi_bad = add_op_selecao("Q4_K", 6144, 2048, "F32", 1, 0, 0);
    int bi_bad2 = add_op_selecao("Q6_K", 2048, 512, "F32", 1, 0, 0);
    int bi_bad3 = add_op_selecao("Q6_K", 6144, 2048, "F32", 2, 0, 0);   // B=2!
    printf("C3 nao-Q6=%d K2048=%d B2=%d (exigido -1/-1/-1!)\n", bi_bad, bi_bad2, bi_bad3);
    if (bi_bad != -1 || bi_bad2 != -1 || bi_bad3 != -1) { fails++; printf("C3 FAIL\n"); }

    // ---- C4: o fluxo COMPLETO: map + copy com bounds! ----
    batch_reset();
    int bi = add_op_selecao("Q6_K", 6144, 2048, "F32", 1, bufs[0].base, bufs[0].size);
    op_matmul_map(bi, 0);
    size_t copied = worker_copy(row_data, 221184, 0, 1);
    int ok_c4 = (copied == 221184 && octx_act_dump_cap == 262144 && memcmp(dump_storage, row_data, 221184) == 0);
    printf("C4 copy=%zu cap=%zu match=%s\n", copied, octx_act_dump_cap, ok_c4 ? "SIM" : "NAO");
    if (!ok_c4) { fails++; printf("C4 FAIL\n"); }

    // ---- C5: BOUNDS: len > cap -> NAO copia! off+len > cap -> NAO copia! ----
    memset(dump_storage, 0xEE, sizeof(dump_storage));
    static uint8_t big_row[400000]; memset(big_row, 0x5A, sizeof(big_row));
    size_t bad = worker_copy(big_row, 400000, 0, 1);    // len 400000 > cap 262144!
    int unt1 = 1;   // após a 1ª recusa: o dump INTEIRO ainda 0xEE (nada escrito!)
    for (size_t i = 0; i < sizeof(dump_storage); i++) if (dump_storage[i] != 0xEE) { unt1 = 0; break; }
    size_t bad2 = worker_copy(big_row, 200000, 1, 2);   // off=200000 + len=200000 > cap! (origem OK!)
    int unt2 = 1;   // após a 2ª recusa: idem!
    for (size_t i = 0; i < sizeof(dump_storage); i++) if (dump_storage[i] != 0xEE) { unt2 = 0; break; }
    size_t exact = worker_copy(big_row, 262144, 0, 1);  // == cap: COPIA (origem 400000 >= 262144 — SEM UB!)
    int unt3 = 1;   // o VÁLIDO escreveu TUDO (0x5A da big_row!)
    for (size_t i = 0; i < sizeof(dump_storage); i++) if (dump_storage[i] != 0x5A) { unt3 = 0; break; }
    const int untouched = unt1 && unt2 && unt3;
    printf("C5 len>cap=%zu(%s) off+len>cap=%zu(%s) exact=%zu(write=%s) (exigido 0/0/262144/SIM!)\n",
           bad, unt1 ? "nada-escrito" : "SUJO", bad2, unt2 ? "nada-escrito" : "SUJO", exact, unt3 ? "ok" : "PARCIAL");
    if (bad != 0 || bad2 != 0 || exact != 262144 || !untouched) { fails++; printf("C5 FAIL\n"); }

    // ---- C6: HMX desvia -> ptr NULL! ----
    op_matmul_map(bi, 1);   // n_hmx=1!
    printf("C6 hmx_ptr=%s (exigido NULL!)\n", octx_act_dump_ptr ? "SETADO" : "NULL");
    if (octx_act_dump_ptr) { fails++; printf("C6 FAIL\n"); }

    // ---- C7: flag '0' -> nunca arma (mesmo com Q6!) ----
    putenv("GGML_HEXAGON_ACT_DUMP=0");
    g_r46_armed = -1; batch_reset();
    int bi_off = add_op_selecao("Q6_K", 6144, 2048, "F32", 1, 0, 0);
    printf("C7 flag0: bi=%d (exigido -1! OFF total!)\n", bi_off);
    if (bi_off != -1) { fails++; printf("C7 FAIL\n"); }

    // ---- C8: flag lixo ('01') -> OFF! ----
    putenv("GGML_HEXAGON_ACT_DUMP=01");
    g_r46_armed = -1; batch_reset();
    int bi_g = add_op_selecao("Q6_K", 6144, 2048, "F32", 1, 0, 0);
    printf("C8 flag01: bi=%d (exigido -1!)\n", bi_g);
    if (bi_g != -1) { fails++; printf("C8 FAIL\n"); }

    printf("\n%s (%d falhas!)\n", fails == 0 ? "VACINAS_R46: TODAS PASS (8/8!)" : "VACINAS_R46: HOUVE FALHA!", fails);
    return fails == 0 ? 0 : 1;
}
