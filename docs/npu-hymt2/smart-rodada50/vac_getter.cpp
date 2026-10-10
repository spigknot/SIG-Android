
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// stubs minimos (declarados!): o tipo do sbuf usa os MESMOS campos que o getter le
struct ggml_hexagon_session {};
struct ggml_hexagon_shared_buffer {
    ggml_hexagon_session * sess = nullptr;
    uint8_t * base = nullptr;
    size_t size = 0;
    int fd = -1;
};

#define R46_MAGIC 0x52343644u   // "R46D"

static ggml_hexagon_shared_buffer * g_r46_act_dump_sbuf = nullptr;

struct ggml_hexagon_act_dump_status {
    uint32_t magic;        // 0x52343644
    uint32_t version;      // 1
    int32_t  status;       // 0=NOT_ARMED; 1=ARMED_NO_CAPTURE; 2=CAPTURED; -1=INVALIDO
    int32_t  type;         // ggml_type do peso (o Q6_K!)
    int32_t  K, N, B;
    uint32_t epoch;        // a captura (1,2,...)
    uint32_t rows_done;    // do header (LIDO AGORA!)
    uint32_t payload_off;  // 64 (payload!)
    uint32_t reserved;
    uint64_t ptr;          // o buffer (para o probe ler!)
    uint64_t capacity;     // total do sbuf
    uint64_t row_size;     // da op capturada
    // R49 (version 2!): a METADATA do ramo real (do header!)
    int32_t  r49_n_hmx;       // o n_hmx DECODIFICADO pelo DSP no op_matmul!
    int32_t  r49_kernel_type; // o kernel_type!
    int32_t  r49_op_entered;  // 1 = o op_matmul LEU os kparams desta op!
    int32_t  r49_reserved;
    // R50 (version 3!): observabilidade em PENDING + o TRACE do quantizador DSP
    int32_t  r50_meta_valid;    // 1 = header com magic/epoch OK (metadata VISIVEL mesmo em pending!)
    int32_t  r50_firstcause;    // 0=none; 1=op_seen0; 2=rows0; 3=B!=1; 4=rows!=B; 5=row0; 6=cap/row; 7=sem entered
    uint32_t r50_src1_nrows;    // h[8]  (DSP!)
    uint32_t r50_n_threads;     // h[9]  (DSP!)
    uint32_t r50_need_quant;    // h[10] (DSP!)
    uint32_t r50_n_quant_tasks; // h[11] (DSP!)
    uint32_t r50_quant_func_id; // h[12] (DSP! 0=null 1=q8_0_tiled 2=q8_1_tiled 3=q8_0_tiled_block 4=q8_1_tiled_block 5=q8_0_flat 6=q8_1_flat 7=f32 8=f16 9=f16f16)
    uint32_t r50_dsp_decoded;   // 1 = h[13]==0xA5A50002 (o DSP DECODIFICOU a decisao!)
    uint32_t r50_total_nb;      // h[14] (DSP! total de tiles da ACT — o coverage!)
    uint32_t r50_reserved;
};

static struct {
    int      used;         // 1 = ja capturou (desarma as demais!)
    uint32_t epoch;
    int      K, N, B, type;
    size_t   row_size;
} g_r46_meta = {0};

static int g_r46_armed = -1;

static bool g_r46_act_dump_armed(void) {
    if (g_r46_armed < 0) {   // INIT-ONLY (declarado!): a primeira leitura decide!
        const char * e = getenv("GGML_HEXAGON_ACT_DUMP");
        g_r46_armed = (e && e[0] == '1' && e[1] == 0) ? 1 : 0;   // so' "1" (o resto OFF!)
    }
    return g_r46_armed == 1;
}

static int ggml_hexagon_get_act_dump(void * out_v, size_t out_size) {
    // R47: HANDSHAKE — recusa struct copiado menor (ABI às cegas morre aqui!)
    if (!out_v || out_size < sizeof(struct ggml_hexagon_act_dump_status)) return -2;
    struct ggml_hexagon_act_dump_status * out = (struct ggml_hexagon_act_dump_status *) out_v;
    out->magic = R46_MAGIC; out->version = 3;   // R50: + observabilidade em PENDING + trace DSP!
    out->type = 0; out->K = 0; out->N = 0; out->B = 0;
    out->epoch = g_r46_meta.epoch; out->rows_done = 0; out->payload_off = 64;
    out->reserved = 0; out->ptr = 0; out->capacity = 0; out->row_size = 0;
    out->r49_n_hmx = -1; out->r49_kernel_type = -1; out->r49_op_entered = 0; out->r49_reserved = 0;
    out->r50_meta_valid = 0; out->r50_firstcause = 0;
    out->r50_src1_nrows = 0; out->r50_n_threads = 0; out->r50_need_quant = 0;
    out->r50_n_quant_tasks = 0; out->r50_quant_func_id = 0; out->r50_dsp_decoded = 0;
    out->r50_total_nb = 0; out->r50_reserved = 0;
    if (!g_r46_act_dump_armed()) { out->status = 0; return 0; }          // NOT_ARMED (a flag!)
    if (g_r46_meta.epoch == 0 || !g_r46_act_dump_sbuf) { out->status = 1; return 0; }   // ARMED, sem captura
    out->ptr = (uint64_t) (uintptr_t) g_r46_act_dump_sbuf->base;
    out->capacity = g_r46_act_dump_sbuf->size;
    out->type = g_r46_meta.type; out->K = g_r46_meta.K; out->N = g_r46_meta.N; out->B = g_r46_meta.B;
    out->row_size = g_r46_meta.row_size;
    // R50: CAPACIDADE conferida ANTES de qualquer load do header (parecer R49/D!)
    if (g_r46_act_dump_sbuf->base == nullptr || g_r46_act_dump_sbuf->size < 64) { out->status = -1; return 0; }
    const uint32_t * hdr = (const uint32_t *) g_r46_act_dump_sbuf->base;
    if (hdr[0] != R46_MAGIC || hdr[1] != g_r46_meta.epoch) { out->status = -1; return 0; }   // INVALIDO (o header!)
    out->rows_done = hdr[3];
    // R50: A METADATA SAI AQUI (header valido!) — a observacao acontece MESMO em PENDING
    // (o parecer R49/D: "-1/-1/0 eram os DEFAULTS do GETTER, nao evidencia do SKEL!")
    out->r49_n_hmx = (int32_t) hdr[4];
    out->r49_kernel_type = (int32_t) hdr[5];
    out->r49_op_entered = (hdr[7] == 0xA5A50001u) ? 1 : 0;
    out->r50_meta_valid = 1;
    out->r50_src1_nrows = hdr[8];
    out->r50_n_threads = hdr[9];
    out->r50_need_quant = hdr[10];
    out->r50_n_quant_tasks = hdr[11];
    out->r50_quant_func_id = hdr[12];
    out->r50_dsp_decoded = (hdr[13] == 0xA5A50002u) ? 1 : 0;
    out->r50_total_nb = hdr[14];
    // R47/R50: VALIDACAO FORMAL antes de CAPTURED (nada de >=B/row0/cap64 passando!)
    const int op_seen = hdr[2] == 1;
    if (!op_seen) { out->status = 1; out->r50_firstcause = 1; return 0; }                       // op_seen0 (pending!)
    if (hdr[3] == 0) { out->status = 1; out->r50_firstcause = 2; return 0; }                    // rows0 (pending!)
    if (g_r46_meta.B != 1) { out->status = -1; out->r50_firstcause = 3; return 0; }             // ALVO e' B1!
    if ((int) hdr[3] != g_r46_meta.B) { out->status = -1; out->r50_firstcause = 4; return 0; }  // rows EXATO!
    if (g_r46_meta.row_size == 0) { out->status = -1; out->r50_firstcause = 5; return 0; }      // row 0!
    if (g_r46_meta.row_size > out->capacity || out->payload_off + g_r46_meta.row_size > out->capacity) {
        out->status = -1; out->r50_firstcause = 6; return 0;                                    // 64+row <= cap!
    }
    if (hdr[7] != 0xA5A50001u) { out->status = -1; out->r50_firstcause = 7; return 0; }         // R50: CAPTURED exige o op_matmul!
    out->status = 2;   // CAPTURED (tudo validado!)
    return 0;
}

// writer modelado 1:1 dos blocos do skel (op_matmul h[2]/h[4..7]; trace h[8..14])
static void writer(uint8_t * p, uint32_t n_hmx, uint32_t kernel_type,
                   uint32_t src1_nrows, uint32_t nthr, uint32_t needq,
                   uint32_t nqt, uint32_t qid, uint32_t total_nb) {
    uint32_t * h = (uint32_t *) p;
    h[0] = R46_MAGIC; h[1] = 1;      // epoch (host seta g_r46_meta.epoch=1)
    h[2] = 1;                        // op_seen (op_matmul)
    h[3] = 0;                        // rows_done (ainda 0!)
    h[4] = n_hmx; h[5] = kernel_type; h[6] = 0; h[7] = 0xA5A50001u;
    h[8] = src1_nrows; h[9] = nthr; h[10] = needq; h[11] = nqt;
    h[12] = qid; h[13] = 0xA5A50002u; h[14] = total_nb;
}

static int falhas = 0;
#define REQ(cond, msg) do { if (!(cond)) { printf("VAC_FAIL: %s\n", msg); falhas++; } } while (0)

int main(int argc, char ** argv) {
    const bool on = (argc > 1 && strcmp(argv[1], "on") == 0);
    if (on) setenv("GGML_HEXAGON_ACT_DUMP", "1", 1);   // a armed le na 1a chamada!
    static ggml_hexagon_shared_buffer b;
    static uint8_t mem[262144];
    b.base = mem; b.size = sizeof(mem);
    memset(mem, 0, sizeof(mem));
    g_r46_act_dump_sbuf = &b;   // o owner (o que o host seta no select!)

    struct ggml_hexagon_act_dump_status st;
    memset(&st, 0, sizeof(st));

    if (!on) {
        int r = ggml_hexagon_get_act_dump(&st, sizeof(st));
        printf("VAC_OFF ret=%d status=%d (esperado 0/0)\n", r, st.status);
        REQ(r == 0 && st.status == 0, "OFF deve ser NOT_ARMED");
        return falhas ? 1 : 0;
    }

    // ON: sem captura ainda (sbuf setado, epoch 0)
    int r = ggml_hexagon_get_act_dump(&st, sizeof(st));
    printf("VAC_ON_NOCAP ret=%d status=%d (esperado 0/1)\n", r, st.status);
    REQ(r == 0 && st.status == 1, "sem captura => status 1");

    // ARMA a captura (o que o op_matmul faz: g_r46_meta!)
    g_r46_meta.epoch = 1; g_r46_meta.K = 6144; g_r46_meta.N = 2048; g_r46_meta.B = 1;
    g_r46_meta.type = 14; g_r46_meta.row_size = 221184;

    // T1: writer escreve SO a metadata (rows=0!) => PENDING com metadata VISIVEL!
    writer(mem, 0, 10, 1, 8, 1, 8, 3, 48);
    r = ggml_hexagon_get_act_dump(&st, sizeof(st));
    printf("VAC_PENDING ret=%d status=%d meta_valid=%d firstcause=%d n_hmx=%d kernel=%d entered=%d decoded=%d qid=%u nqt=%u rows_done=%u\n",
        r, st.status, st.r50_meta_valid, st.r50_firstcause, st.r49_n_hmx, st.r49_kernel_type,
        st.r49_op_entered, st.r50_dsp_decoded, st.r50_quant_func_id, st.r50_n_quant_tasks, st.rows_done);
    REQ(r == 0 && st.status == 1, "T1 pending");
    REQ(st.r50_meta_valid == 1, "T1 metadata visivel!");
    REQ(st.r49_n_hmx == 0 && st.r49_kernel_type == 10 && st.r49_op_entered == 1, "T1 ramo real visivel!");
    REQ(st.r50_dsp_decoded == 1 && st.r50_quant_func_id == 3 && st.r50_n_quant_tasks == 8 && st.r50_src1_nrows == 1, "T1 trace DSP!");
    REQ(st.r50_firstcause == 2 && st.rows_done == 0, "T1 firstcause=rows0; NUNCA payload!");

    // T2: cap-curta ANTES do load
    b.size = 16;
    r = ggml_hexagon_get_act_dump(&st, sizeof(st));
    printf("VAC_CAPSHORT ret=%d status=%d (esperado -1)\n", r, st.status);
    REQ(r == 0 && st.status == -1, "T2 cap<64 => INVALID");
    b.size = sizeof(mem);

    // T3: magic/epoch stale
    ((uint32_t *) mem)[1] = 9;   // h[1] = epoch errado
    r = ggml_hexagon_get_act_dump(&st, sizeof(st));
    printf("VAC_STALE ret=%d status=%d (esperado -1)\n", r, st.status);
    REQ(r == 0 && st.status == -1, "T3 epoch stale => INVALID");
    ((uint32_t *) mem)[1] = 1;

    // T4: captured SEM entered magic => NAO publica
    uint32_t * h = (uint32_t *) mem; h[3] = 1; h[7] = 0;
    r = ggml_hexagon_get_act_dump(&st, sizeof(st));
    printf("VAC_NOENTER ret=%d status=%d firstcause=%d (esperado -1/7)\n", r, st.status, st.r50_firstcause);
    REQ(r == 0 && st.status == -1 && st.r50_firstcause == 7, "T4 sem entered => INVALID");

    // T5: captured COMPLETO (+entered) => status 2 (e duplicata estavel!)
    h[7] = 0xA5A50001u;
    r = ggml_hexagon_get_act_dump(&st, sizeof(st));
    printf("VAC_CAPTURED ret=%d status=%d rows=%u (esperado 0/2/1)\n", r, st.status, st.rows_done);
    REQ(r == 0 && st.status == 2 && st.rows_done == 1, "T5 captured");
    r = ggml_hexagon_get_act_dump(&st, sizeof(st));
    REQ(r == 0 && st.status == 2, "T5 duplicata estavel");

    // T6: handshake (struct menor)
    r = ggml_hexagon_get_act_dump(&st, 72);
    printf("VAC_HANDSHAKE ret=%d (esperado -2)\n", r);
    REQ(r == -2, "T6 struct curto => -2");

    printf("VAC_RESULTADO falhas=%d %s\n", falhas, falhas ? "RED" : "GREEN");
    return falhas ? 1 : 0;
}
