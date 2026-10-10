#include <cstdio>
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
int main(){
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_UNSUPPORTED", (int) HTP_MM_KERNEL_UNSUPPORTED);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HMX_2D", (int) HTP_MM_KERNEL_HMX_2D);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HMX_F16_BATCHED", (int) HTP_MM_KERNEL_HMX_F16_BATCHED);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HVX_F16_F16_VTCM", (int) HTP_MM_KERNEL_HVX_F16_F16_VTCM);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HVX_F16_F16_DDR", (int) HTP_MM_KERNEL_HVX_F16_F16_DDR);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HVX_F16_F32_DDR", (int) HTP_MM_KERNEL_HVX_F16_F32_DDR);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HVX_F32_F32_VTCM", (int) HTP_MM_KERNEL_HVX_F32_F32_VTCM);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HVX_F32_F32_DDR", (int) HTP_MM_KERNEL_HVX_F32_F32_DDR);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HVX_F32_F16_DDR", (int) HTP_MM_KERNEL_HVX_F32_F16_DDR);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HVX_QUANT_ROW", (int) HTP_MM_KERNEL_HVX_QUANT_ROW);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HVX_QUANT_BLOCK", (int) HTP_MM_KERNEL_HVX_QUANT_BLOCK);
  printf("MAPA_COHORT %s=%d\n", "HTP_MM_KERNEL_HVX_QUANT_ROW_FLAT", (int) HTP_MM_KERNEL_HVX_QUANT_ROW_FLAT);
  return 0;}
