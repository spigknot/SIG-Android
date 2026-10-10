#include <cstdint>
#include <cstdio>
#include <cstring>
enum{GGML_TYPE_Q6_K=14};
struct r48_st {
                        uint32_t magic, version; int32_t status, type, K, N, B;
                        uint32_t epoch, rows_done, payload_off, reserved;
                        uint64_t ptr, capacity, row_size;
                        int32_t r49_n_hmx, r49_kernel_type, r49_op_entered, r49_reserved;   // R49!
                        // R50 (version 3!): observabilidade em PENDING + trace DSP
                        int32_t r50_meta_valid, r50_firstcause;
                        uint32_t r50_src1_nrows, r50_n_threads, r50_need_quant, r50_n_quant_tasks;
                        uint32_t r50_quant_func_id, r50_dsp_decoded, r50_total_nb, r50_reserved;
                    } st48;
;
static bool gate(r48_st st48, int g48_ret = 0) {
const bool val48 = (g48_ret == 0 && st48.status == 2 && st48.magic == 0x52343644u &&
                                        st48.version == 3u && st48.epoch != 0 && st48.type == (int) GGML_TYPE_Q6_K &&
                                        st48.K == 6144 && st48.N == 2048 && st48.B == 1 &&
                                        st48.rows_done == 1u && st48.row_size == 221184u &&
                                        st48.payload_off == 64 &&
                                        st48.row_size <= st48.capacity &&
                                        64 + st48.row_size <= st48.capacity &&
                                        st48.capacity <= (1u << 20) && st48.ptr != 0 &&
                                        st48.r50_meta_valid == 1 &&
                                        st48.r49_op_entered == 1 && st48.r49_n_hmx == 0 &&
                                        st48.r49_kernel_type == 10 &&
                                        st48.r50_dsp_decoded == 1 && st48.r50_need_quant == 1 &&
                                        st48.r50_quant_func_id == 3 &&
                                        st48.r50_n_threads >= 1 && st48.r50_n_threads <= 16 &&
                                        st48.r50_n_quant_tasks ==
                                            ((48u < st48.r50_n_threads) ? 48u : st48.r50_n_threads) &&
                                        st48.r50_src1_nrows == 1 && st48.r50_total_nb == 48u);
    return val48;
}
static int falhas = 0;
#define REQ(cond, msg) do { if (!(cond)) { printf("VAC_FAIL: %s\n", msg); falhas++; } } while (0)
static r48_st base() {
    r48_st s; memset(&s, 0, sizeof(s));
    s.status=2; s.magic=0x52343644u; s.version=3; s.epoch=1; s.type=14; s.K=6144; s.N=2048; s.B=1;
    s.rows_done=1; s.row_size=221184; s.payload_off=64; s.capacity=262144; s.ptr=1;
    s.r50_meta_valid=1; s.r49_op_entered=1; s.r49_n_hmx=0; s.r49_kernel_type=10;
    s.r50_dsp_decoded=1; s.r50_need_quant=1; s.r50_quant_func_id=3; s.r50_n_threads=8;
    s.r50_n_quant_tasks=8; s.r50_src1_nrows=1; s.r50_total_nb=48;
    return s;
}
int main() {
    // POSITIVO (profile observado B1/nthr8): qfn=3 nqt=8
    REQ(gate(base()) == true, "P1 baseline qfn3/nqt8 deve PASSAR");
    printf("P1 baseline qfn=3 nqt=8 nthr=8: aceito=1\n");
    // NEGATIVOS PRIMEIRO (2.2): qfn errado
    for (int q = 0; q <= 9; ++q) {
        if (q == 3) continue;
        r48_st s = base(); s.r50_quant_func_id = q;
        REQ(gate(s) == false, "N-qfn deve rejeitar");
        printf("N qfn=%d: aceito=0\n", q);
    }
    // nqt inconsistente
    { r48_st s = base(); s.r50_n_quant_tasks = 999; REQ(!gate(s), "N-nqt999"); printf("N nqt=999: aceito=0\n"); }
    { r48_st s = base(); s.r50_n_quant_tasks = 7;   REQ(!gate(s), "N-nqt7");   printf("N qfn=3 nqt=7: aceito=0\n"); }
    // nthr que muda min(): nthr=16 => nqt deve ser 16; nqt=8 fica INCONSISTENTE
    { r48_st s = base(); s.r50_n_threads = 16; s.r50_n_quant_tasks = 8;  REQ(!gate(s), "N-nthr16-nqt8"); printf("N nthr=16 nqt=8 (exige 16): aceito=0\n"); }
    { r48_st s = base(); s.r50_n_threads = 16; s.r50_n_quant_tasks = 16; REQ(gate(s), "P-nthr16-nqt16"); printf("P nthr=16 nqt=16: aceito=1\n"); }
    // nthr fora de faixa
    { r48_st s = base(); s.r50_n_threads = 0; REQ(!gate(s), "N-nthr0"); printf("N nthr=0: aceito=0\n"); }
    { r48_st s = base(); s.r50_n_threads = 17; REQ(!gate(s), "N-nthr17"); printf("N nthr=17: aceito=0\n"); }
    // total_nb errado
    { r48_st s = base(); s.r50_total_nb = 47; REQ(!gate(s), "N-tnb47"); printf("N total_nb=47: aceito=0\n"); }
    // shape/rows/entered ainda rejeitam
    { r48_st s = base(); s.rows_done = 2; REQ(!gate(s), "N-rows2"); }
    { r48_st s = base(); s.r49_op_entered = 0; REQ(!gate(s), "N-entered0"); }
    { r48_st s = base(); s.r49_n_hmx = 1; REQ(!gate(s), "N-hmx1"); }
    printf("VAC_CONSUMER falhas=%d %s\n", falhas, falhas ? "RED" : "GREEN");
    return falhas ? 1 : 0;
}
