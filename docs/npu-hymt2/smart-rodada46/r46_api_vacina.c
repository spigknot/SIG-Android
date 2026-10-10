// r46_api_vacina.c — R46 §5: a vacina da API (o fluxo REAL extraído!)
// N1 flag off -> NOT_ARMED; N2 armed sem captura -> 1; N3 completo -> CAPTURED;
// N4 header inválido -> -1; N5 op_seen=0 -> 1; N6 rows_done=0 -> 1.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

#define R46_MAGIC 0x52343644u
struct ggml_hexagon_act_dump_status {
    uint32_t magic; uint32_t version; int32_t status; int32_t type;
    int32_t K, N, B; uint32_t epoch; uint32_t rows_done; uint32_t payload_off; uint32_t reserved;
    uint64_t ptr; uint64_t capacity; uint64_t row_size;
};
// ===== os fragmentos REAIS do patch §5 (adaptados: o sbuf vira um array!) =====
static int g_r46_armed = -1;
static bool g_r46_act_dump_armed(void) {
    if (g_r46_armed < 0) {
        const char * e = getenv("GGML_HEXAGON_ACT_DUMP");
        g_r46_armed = (e && e[0] == '1' && e[1] == 0) ? 1 : 0;
    }
    return g_r46_armed == 1;
}
static uint8_t g_sbuf[262144];
static int g_sbuf_exists = 0;
static struct { int used; uint32_t epoch; int K, N, B, type; size_t row_size; } g_r46_meta = {0};
static int ggml_hexagon_get_act_dump(void * out_v) {
    struct ggml_hexagon_act_dump_status * out = (struct ggml_hexagon_act_dump_status *) out_v;
    if (!out) return -1;
    out->magic = R46_MAGIC; out->version = 1;
    out->type = 0; out->K = 0; out->N = 0; out->B = 0;
    out->epoch = g_r46_meta.epoch; out->rows_done = 0; out->payload_off = 64;
    out->reserved = 0; out->ptr = 0; out->capacity = 0; out->row_size = 0;
    if (!g_r46_act_dump_armed()) { out->status = 0; return 0; }
    if (g_r46_meta.epoch == 0 || !g_sbuf_exists) { out->status = 1; return 0; }
    out->ptr = (uint64_t)(uintptr_t) g_sbuf;
    out->capacity = sizeof(g_sbuf);
    out->type = g_r46_meta.type; out->K = g_r46_meta.K; out->N = g_r46_meta.N; out->B = g_r46_meta.B;
    out->row_size = g_r46_meta.row_size;
    const uint32_t * hdr = (const uint32_t *) g_sbuf;
    if (hdr[0] != R46_MAGIC || hdr[1] != g_r46_meta.epoch) { out->status = -1; return 0; }
    out->rows_done = hdr[3];
    const int op_seen = hdr[2] == 1;
    out->status = (op_seen && (int) hdr[3] >= g_r46_meta.B && g_r46_meta.B > 0) ? 2 : 1;
    return 0;
}
// =============================================================================
int main(void) {
    int fails = 0;
    struct ggml_hexagon_act_dump_status st;
    // N1: flag off!
    putenv("GGML_HEXAGON_ACT_DUMP=0"); g_r46_armed = -1;
    ggml_hexagon_get_act_dump(&st);
    printf("N1 flag0: status=%d (exigido 0!)\n", st.status);
    if (st.status != 0) { fails++; printf("N1 FAIL\n"); }
    // N2: armed sem captura!
    putenv("GGML_HEXAGON_ACT_DUMP=1"); g_r46_armed = -1; g_r46_meta.used = 0;
    ggml_hexagon_get_act_dump(&st);
    printf("N2 armed-sem-captura: status=%d (exigido 1!)\n", st.status);
    if (st.status != 1) { fails++; printf("N2 FAIL\n"); }
    // N3: captura completa (header+op_seen+rows_done!)
    g_sbuf_exists = 1;
    g_r46_meta.epoch = 1; g_r46_meta.K = 6144; g_r46_meta.N = 2048; g_r46_meta.B = 1; g_r46_meta.type = 14; g_r46_meta.row_size = 221184;
    uint32_t * hdr = (uint32_t *) g_sbuf;
    hdr[0] = R46_MAGIC; hdr[1] = 1; hdr[2] = 1; hdr[3] = 1;   // op_seen + 1 row!
    ggml_hexagon_get_act_dump(&st);
    printf("N3 completo: status=%d rows=%u K=%d N=%d B=%d row=%llu cap=%llu (exigido 2/1/6144/2048/1/221184/262144!)\n",
           st.status, st.rows_done, st.K, st.N, st.B, (unsigned long long) st.row_size, (unsigned long long) st.capacity);
    if (st.status != 2 || st.rows_done != 1 || st.K != 6144 || st.N != 2048) { fails++; printf("N3 FAIL\n"); }
    // N4: header invalido!
    hdr[0] = 0xDEADBEEF;
    ggml_hexagon_get_act_dump(&st);
    printf("N4 header-invalido: status=%d (exigido -1!)\n", st.status);
    if (st.status != -1) { fails++; printf("N4 FAIL\n"); }
    // N5: op_seen=0 (a op nao chegou!)
    hdr[0] = R46_MAGIC; hdr[2] = 0; hdr[3] = 0;
    ggml_hexagon_get_act_dump(&st);
    printf("N5 op_seen0: status=%d (exigido 1!)\n", st.status);
    if (st.status != 1) { fails++; printf("N5 FAIL\n"); }
    // N6: rows_done=0 (os workers nao copiaram!)
    hdr[2] = 1; hdr[3] = 0;
    ggml_hexagon_get_act_dump(&st);
    printf("N6 rows0: status=%d (exigido 1!)\n", st.status);
    if (st.status != 1) { fails++; printf("N6 FAIL\n"); }
    printf("\n%s (%d falhas!)\n", fails == 0 ? "API_VACINA_R46: TODAS PASS (6/6!)" : "API_VACINA_R46: FALHA!", fails);
    return fails == 0 ? 0 : 1;
}
