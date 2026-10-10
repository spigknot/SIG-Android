#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
// ---- LITERAL do cohort R50: hex-fastdiv.h ----
struct fastdiv_values {
    uint32_t mp;
    uint32_t l;
};
// ---- LITERAL do cohort R50: matmul-ops.h ----
enum htp_mm_kernel_type {
    HTP_MM_KERNEL_UNSUPPORTED = 0,

    // HMX paths
    HTP_MM_KERNEL_HMX_2D,
    HTP_MM_KERNEL_HMX_F16_BATCHED,

    // HVX floating-point paths
    HTP_MM_KERNEL_HVX_F16_F16_VTCM,
    HTP_MM_KERNEL_HVX_F16_F16_DDR,
    HTP_MM_KERNEL_HVX_F16_F32_DDR,

    HTP_MM_KERNEL_HVX_F32_F32_VTCM,
    HTP_MM_KERNEL_HVX_F32_F32_DDR,
    HTP_MM_KERNEL_HVX_F32_F16_DDR,

    // HVX quantized paths
    HTP_MM_KERNEL_HVX_QUANT_ROW,      // standard row-wise parallel quantization
    HTP_MM_KERNEL_HVX_QUANT_BLOCK,    // parallel block-wise quantization
    HTP_MM_KERNEL_HVX_QUANT_ROW_FLAT, // row-wise fallback flat quantization
};
struct htp_mm_kernel_params {
    int32_t  kernel_type;        // enum htp_mm_kernel_type
    int32_t  pipeline;           // 1 = pipelined execution, 0 = standard
    int32_t  m_chunk;            // Row chunk size (M chunk)
    int32_t  n_chunk;            // Col chunk size (N chunk)
    int32_t  n_threads;          // Number of threads to spawn
    int32_t  n_act_threads;      // Number of threads for activation preparation
    int32_t  n_hmx;              // 1 = use HMX, 0 = use HVX
    int32_t  n_prefetch;         // Prefetch lookahead buffers/rows in VTCM
    int32_t  tile_size;          // Weight tile size
    int32_t  aligned_tile_size;  // Aligned weight tile size (padded to 128)
    int32_t  src1_row_size;      // Row size for quantized activation
    int32_t  vtcm_size;          // Total required scratchpad size in VTCM
    int32_t  vtcm_src0_size;     // src0 scratchpad size in VTCM
    int32_t  vtcm_src1_size;     // src1 scratchpad size in VTCM
    int32_t  vtcm_src2_size;     // src2 scratchpad size in VTCM (fused only)
    int32_t  vtcm_src3_size;     // src3 scratchpad size in VTCM (fused only)
    int32_t  vtcm_dst_size;      // dst scratchpad size in VTCM
    int32_t  act_dump_bi;        // R45 (test-only): buffer index do dump da ACT (-1 = OFF!)

    // Precomputed division values
    struct fastdiv_values div_ne12_ne1;
    struct fastdiv_values div_ne1;
    struct fastdiv_values div_r2;
    struct fastdiv_values div_r3;
    struct fastdiv_values div_ne11;
    struct fastdiv_values div_n_act_threads;
    struct fastdiv_values div_ne00_padded;
};
// ---- LITERAL do cohort R50: ggml-hexagon.cpp (host, v3) ----
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
int main(void) {
    printf("ABI sizeof(htp_mm_kernel_params)=%zu (static_assert<=128!)\n", sizeof(struct htp_mm_kernel_params));
    printf("ABI off kernel_type=%zu pipeline=%zu n_threads=%zu n_hmx=%zu\n",
        offsetof(struct htp_mm_kernel_params, kernel_type), offsetof(struct htp_mm_kernel_params, pipeline),
        offsetof(struct htp_mm_kernel_params, n_threads), offsetof(struct htp_mm_kernel_params, n_hmx));
    printf("ABI off src1_row_size=%zu vtcm_size=%zu act_dump_bi=%zu div_ne12_ne1=%zu\n",
        offsetof(struct htp_mm_kernel_params, src1_row_size), offsetof(struct htp_mm_kernel_params, vtcm_size),
        offsetof(struct htp_mm_kernel_params, act_dump_bi), offsetof(struct htp_mm_kernel_params, div_ne12_ne1));
    printf("ENUM kt: UNSUPPORTED=%d HMX_2D=%d HMX_F16_BATCHED=%d ROW=%d BLOCK=%d ROW_FLAT=%d\n",
        HTP_MM_KERNEL_UNSUPPORTED, HTP_MM_KERNEL_HMX_2D, HTP_MM_KERNEL_HMX_F16_BATCHED,
        HTP_MM_KERNEL_HVX_QUANT_ROW, HTP_MM_KERNEL_HVX_QUANT_BLOCK, HTP_MM_KERNEL_HVX_QUANT_ROW_FLAT);
    printf("ABI sizeof(act_dump_status v3)=%zu off r49_n_hmx=%zu off r50_meta_valid=%zu off r50_total_nb=%zu\n",
        sizeof(struct ggml_hexagon_act_dump_status),
        offsetof(struct ggml_hexagon_act_dump_status, r49_n_hmx),
        offsetof(struct ggml_hexagon_act_dump_status, r50_meta_valid),
        offsetof(struct ggml_hexagon_act_dump_status, r50_total_nb));
    return 0;
}
