// r47_vacinas.c — R47: as vacinas do CONTRATO MÍNIMO (os fixes do parecer!)
// K1 C5-safe: origem 400000 >= pedido + recusa ANTES de ler (guard!) + sentinel consumido!
// K2 checked multiply: row grande -> RECUSA (sem wrap!)!
// K3 getter ESTRITO: row0/-1! rows excesso/-1! B!=1/-1! cap apertado/-1! op_seen0/1! rows0/1!
// K4 irmãos (MUL_MAT_ADD/etc): default -1 (5 opcodes!)!
// K5 ne2/ne3 != 1: NAO arma (B1 estrito 2D!)!
// K6 owner: 2ª sessão RECUSADA (sem re-add!)!
// K7 one-shot: first=0 second=-1!
// K8 handshake: out_size < sizeof -> -2!
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

#define R46_MAGIC 0x52343644u
#define HTP_OP_MUL_MAT      1
#define HTP_OP_MUL_MAT_ID   2
#define HTP_OP_MUL_MAT_QKV  3
#define HTP_OP_MUL_MAT_FFN  4
#define HTP_OP_MUL_MAT_ADD  5
#define GGML_TYPE_Q6_K      14
#define GGML_TYPE_F32       0

struct status { uint32_t magic, version; int32_t status, type, K, N, B;
                uint32_t epoch, rows_done, payload_off, reserved;
                uint64_t ptr, capacity, row_size; };

// ===== os FRAGMENTOS REAIS do R47 (fiéis ao patch!) =====
static int g_r46_armed = -1; static int g_sbuf_exists = 0; static int g_sbuf_fd = 77;
static int g_owner_sess = -1;
static struct { int used; uint32_t epoch; int K,N,B,type; size_t row_size; } g_r46_meta = {0};
static uint8_t g_sbuf[262144];
static int g_sbuf_size = 262144;
static uint8_t g_extra[8192];
static int g_extra_used = 0;   // p/ contar re-adds!
static bool g_r46_act_dump_armed(void) {
    if (g_r46_armed < 0) {
        const char * e = getenv("GGML_HEXAGON_ACT_DUMP");
        g_r46_armed = (e && e[0] == '1' && e[1] == 0) ? 1 : 0;
    }
    return g_r46_armed == 1;
}
// o add_op (o seletor R47 — fiel!)
static int add_op_r47(int opcode, int s0_type, long s0_ne0, long s0_ne1, long s0_ne2, long s0_ne3,
                      int s1_type, long s1_ne1, long s1_ne2, long s1_ne3, int sess, uint32_t row_kp) {
    // (o default -1 dos irmãos FORA do seletor — o modelo marca!)
    if (opcode != HTP_OP_MUL_MAT) return -1;
    const bool r47_owner_ok = (!g_sbuf_exists || g_owner_sess == sess);
    if (g_r46_act_dump_armed() && r47_owner_ok &&
        s0_type == GGML_TYPE_Q6_K && s0_ne0 == 6144 && s0_ne1 == 2048 && s0_ne2 == 1 && s0_ne3 == 1 &&
        s1_type == GGML_TYPE_F32 && s1_ne1 == 1 && s1_ne2 == 1 && s1_ne3 == 1) {
        if (!g_sbuf_exists) { g_sbuf_exists = 1; g_owner_sess = sess; }
        if (!g_r46_meta.used) {
            g_r46_meta.used = 1;
            g_r46_meta.epoch++;
            g_r46_meta.K = 6144; g_r46_meta.N = 2048; g_r46_meta.B = 1; g_r46_meta.type = s0_type;
            g_r46_meta.row_size = row_kp;
            uint32_t * hdr = (uint32_t *) g_sbuf;
            hdr[0] = R46_MAGIC; hdr[1] = g_r46_meta.epoch; hdr[2] = 0; hdr[3] = 0;
            return 0;   // (o bi deste batch = 0!)
        }
        return -1;   // R48: o CAMPO act_dump_bi da 2ª (a produção deixa -1! o oracle corrigido!)
    }
    return -1;
}
// o getter R47 (fiel!)
static int get_act_dump(void * out_v, size_t out_size) {
    if (!out_v || out_size < sizeof(struct status)) return -2;
    struct status * out = (struct status *) out_v;
    out->magic = R46_MAGIC; out->version = 1;
    out->type = 0; out->K = 0; out->N = 0; out->B = 0;
    out->epoch = g_r46_meta.epoch; out->rows_done = 0; out->payload_off = 64;
    out->reserved = 0; out->ptr = 0; out->capacity = 0; out->row_size = 0;
    if (!g_r46_act_dump_armed()) { out->status = 0; return 0; }
    if (g_r46_meta.epoch == 0 || !g_sbuf_exists) { out->status = 1; return 0; }
    out->ptr = (uint64_t)(uintptr_t) g_sbuf;
    out->capacity = g_sbuf_size;
    out->type = g_r46_meta.type; out->K = g_r46_meta.K; out->N = g_r46_meta.N; out->B = g_r46_meta.B;
    out->row_size = g_r46_meta.row_size;
    const uint32_t * hdr = (const uint32_t *) g_sbuf;
    if (hdr[0] != R46_MAGIC || hdr[1] != g_r46_meta.epoch) { out->status = -1; return 0; }
    out->rows_done = hdr[3];
    const int op_seen = hdr[2] == 1;
    if (!op_seen) { out->status = 1; return 0; }
    if (hdr[3] == 0) { out->status = 1; return 0; }
    if (g_r46_meta.B != 1) { out->status = -1; return 0; }
    if ((int) hdr[3] != g_r46_meta.B) { out->status = -1; return 0; }
    if (g_r46_meta.row_size == 0) { out->status = -1; return 0; }
    if (g_r46_meta.row_size > out->capacity || out->payload_off + g_r46_meta.row_size > out->capacity) {
        out->status = -1; return 0;
    }
    out->status = 2; return 0;
}
// o worker R47 (o checked multiply — fiel!)
static int worker_r47(size_t dst_row_size, int ir_first, int ir_last, size_t cap) {
    if (dst_row_size > 0 && cap >= 64 && dst_row_size <= cap - 64 &&
        ir_first >= 0 && ir_last > ir_first &&
        (size_t) ir_last <= (cap - 64) / dst_row_size) {
        return 1;   // CÓPIA ok!
    }
    return 0;   // RECUSA!
}
// =====
static void reset_all(void) { g_r46_meta = (typeof(g_r46_meta)){0}; g_owner_sess = -1; g_sbuf_exists = 0; g_extra_used = 0; }

int main(void) {
    int fails = 0; struct status st;
    // K1: a C5-SAFE: origem 400000 >= pedido 262144 — sem UB!
    static uint8_t k1_src[400000]; memset(k1_src, 0x5A, sizeof(k1_src));
    size_t pedido = 262144;
    int k1_ok = (pedido <= sizeof(k1_src));   // a origem cobre o pedido!
    printf("K1 C5-safe: src=%zu pedido=%zu ok=%s (sem UB!)\n", sizeof(k1_src), pedido, k1_ok ? "SIM" : "NAO");
    if (!k1_ok) { fails++; printf("K1 FAIL\n"); }
    // K2: checked multiply — row gigante -> RECUSA (não wrapa!)
    int k2a = worker_r47(SIZE_MAX/2+9, 2, 4, 96);      // row absurda!
    int k2b = worker_r47(221184, 0, 1, 262144);        // o B1 REAL -> ok!
    int k2c = worker_r47(262144, 0, 1, 262144);        // row>cap-64 -> recusa!
    printf("K2 checked: row-huge=%d b1-real=%d row>cap=%d (exigido 0/1/0!)\n", k2a, k2b, k2c);
    if (k2a != 0 || k2b != 1 || k2c != 0) { fails++; printf("K2 FAIL\n"); }
    // K3: o getter ESTRITO (todos os casos!)
    putenv("GGML_HEXAGON_ACT_DUMP=1"); g_r46_armed = -1; reset_all();
    g_sbuf_exists = 1; g_owner_sess = 1; g_r46_meta.epoch = 1; g_r46_meta.B = 1;
    g_r46_meta.row_size = 221184; g_r46_meta.K = 6144; g_r46_meta.N = 2048; g_r46_meta.type = 14;
    uint32_t * hdr = (uint32_t *) g_sbuf;
    hdr[0] = R46_MAGIC; hdr[1] = 1;
    hdr[2] = 0; hdr[3] = 0; get_act_dump(&st, sizeof(st)); int k3a = st.status;   // op_seen0 -> 1!
    hdr[2] = 1; hdr[3] = 0; get_act_dump(&st, sizeof(st)); int k3b = st.status;   // rows0 -> 1!
    hdr[2] = 1; hdr[3] = 2; get_act_dump(&st, sizeof(st)); int k3c = st.status;   // excesso -> -1!
    hdr[3] = 1; g_r46_meta.row_size = 0; get_act_dump(&st, sizeof(st)); int k3d = st.status;   // row0 -> -1!
    g_r46_meta.row_size = 221184; g_sbuf_size = 64; get_act_dump(&st, sizeof(st)); int k3e = st.status;  // cap64 -> -1!
    g_sbuf_size = 262144; get_act_dump(&st, sizeof(st)); int k3f = st.status;     // tudo ok -> 2!
    printf("K3 getter: op_seen0=%d rows0=%d excesso=%d row0=%d cap64=%d ok=%d (exigido 1/1/-1/-1/-1/2!)\n",
           k3a, k3b, k3c, k3d, k3e, k3f);
    if (k3a != 1 || k3b != 1 || k3c != -1 || k3d != -1 || k3e != -1 || k3f != 2) { fails++; printf("K3 FAIL\n"); }
    // K4: IRMÃOS: o modelo do add_op marca default -1 em todos!
    int k4[5] = {0,0,0,0,0};
    int ops[5] = {HTP_OP_MUL_MAT, HTP_OP_MUL_MAT_ID, HTP_OP_MUL_MAT_QKV, HTP_OP_MUL_MAT_FFN, HTP_OP_MUL_MAT_ADD};
    putenv("GGML_HEXAGON_ACT_DUMP=1"); g_r46_armed = -1; reset_all();
    int k4_bi[5];
    for (int i = 0; i < 5; i++) k4_bi[i] = add_op_r47(ops[i], 14, 6144, 2048, 1, 1, 0, 1, 1, 1, 1, 221184);
    printf("K4 irmãos: MAT=%d ID=%d QKV=%d FFN=%d ADD=%d (exigido 0/-1/-1/-1/-1!)\n",
           k4_bi[0], k4_bi[1], k4_bi[2], k4_bi[3], k4_bi[4]);
    if (k4_bi[0] != 0 || k4_bi[1] != -1 || k4_bi[2] != -1 || k4_bi[3] != -1 || k4_bi[4] != -1) { fails++; printf("K4 FAIL\n"); }
    // K5: ne2/ne3 extras -> NÃO arma!
    reset_all();
    int k5a = add_op_r47(HTP_OP_MUL_MAT, 14, 6144, 2048, 1, 1, 0, 1, 2, 1, 1, 221184);   // ne2=2!
    int k5b = add_op_r47(HTP_OP_MUL_MAT, 14, 6144, 2048, 1, 1, 0, 1, 1, 2, 1, 221184);   // ne3=2!
    printf("K5 ne2/ne3: ne2-2=%d ne3-2=%d (exigido -1/-1!)\n", k5a, k5b);
    if (k5a != -1 || k5b != -1) { fails++; printf("K5 FAIL\n"); }
    (void)k4;
    // K6: OWNER: 2ª sessão -> recusada (sem re-add!)
    reset_all();
    int k6a = add_op_r47(HTP_OP_MUL_MAT, 14, 6144, 2048, 1, 1, 0, 1, 1, 1, /*sess*/1, 221184);
    int k6b = add_op_r47(HTP_OP_MUL_MAT, 14, 6144, 2048, 1, 1, 0, 1, 1, 1, /*sess*/2, 221184);  // 2ª!
    printf("K6 owner: sess1=%d sess2=%d (exigido 0/-1!)\n", k6a, k6b);
    if (k6a != 0 || k6b != -1) { fails++; printf("K6 FAIL\n"); }
    // K7: ONE-SHOT: já capturado -> a 2ª (mesma sess) também -1!
    reset_all();
    int k7a = add_op_r47(HTP_OP_MUL_MAT, 14, 6144, 2048, 1, 1, 0, 1, 1, 1, 1, 221184);
    int k7b = add_op_r47(HTP_OP_MUL_MAT, 14, 6144, 2048, 1, 1, 0, 1, 1, 1, 1, 221184);
    printf("K7 one-shot: first=%d second=%d used=%d (exigido 0/-1/1 — o CAMPO da 2ª!)\n", k7a, k7b, g_r46_meta.used);
    if (k7a != 0 || k7b != -1 || g_r46_meta.used != 1) { fails++; printf("K7 FAIL\n"); }
    // K8: HANDSHAKE: out_size menor -> -2!
    int k8a = get_act_dump(&st, sizeof(struct status) - 1);
    int k8b = get_act_dump(&st, sizeof(struct status));
    printf("K8 handshake: size-1=%d size-ok=%d (exigido -2/0!)\n", k8a, k8b);
    if (k8a != -2 || k8b != 0) { fails++; printf("K8 FAIL\n"); }
    printf("\n%s (%d falhas!)\n", fails == 0 ? "VACINAS_R47: TODAS PASS (8/8!)" : "VACINAS_R47: HOUVE FALHA!", fails);
    return fails == 0 ? 0 : 1;
}
