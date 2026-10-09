#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include <stdarg.h>
#include "ggml.h"
#include "ggml-impl.h"
#include "ggml-quants.h"
#define HTP_MM_WEIGHT_TILE_SIZE_Q4_1 640
size_t ggml_row_size(enum ggml_type type, int64_t n) {
 if(type==GGML_TYPE_Q4_K) return (size_t)(n/256)*sizeof(block_q4_K);
 if(type==GGML_TYPE_Q6_K) return (size_t)(n/256)*sizeof(block_q6_K);
 return 0;
}
size_t ggml_type_size(enum ggml_type type) {
 if(type==GGML_TYPE_Q4_K) return sizeof(block_q4_K);
 if(type==GGML_TYPE_Q6_K) return sizeof(block_q6_K);
 return 0;
}
const char *ggml_type_name(enum ggml_type type) { (void)type; return "audit"; }
static inline int64_t hex_round_up(int64_t x, int64_t n) { return ((x+n-1)/n)*n; }
void ggml_abort(const char *file, int line, const char *fmt, ...) {
  va_list ap; va_start(ap,fmt); fprintf(stderr,"ABORT %s:%d ",file,line); vfprintf(stderr,fmt,ap); va_end(ap); abort();
}
static inline void get_scale_min_k4(int j, const uint8_t * q, uint8_t * d, uint8_t * m) {
    if (j < 4) {
        *d = q[j] & 63; *m = q[j + 4] & 63;
    } else {
        *d = (uint8_t) ((q[j+4] & 0xF) | ((q[j-4] >> 6) << 4));
        *m = (uint8_t) ((q[j+4] >>  4) | ((q[j-4] >> 6) << 4));
    }
}
static void repack_q4_K_tiled(ggml_tensor * t, const void * data, size_t offset, size_t size) {
    GGML_ASSERT(offset == 0);

    const block_q4_K * src_matrix = (const block_q4_K *) data;
    int64_t ne0 = t->ne[0];
    int64_t ne1 = t->ne[1];
    int64_t ne2 = t->ne[2];
    int64_t ne3 = t->ne[3];
    int64_t ne0_padded = hex_round_up(ne0, 32);
    int64_t ne1_padded = hex_round_up(ne1, 32);

    GGML_ASSERT(ne0 % QK_K == 0);

    const int n_col_tiles = ne1_padded / 32;
    const int n_k_tiles   = ne0_padded / 32;
    const size_t tile_size   = HTP_MM_WEIGHT_TILE_SIZE_Q4_1;
    const size_t matrix_size = (size_t) n_col_tiles * n_k_tiles * tile_size;

    const int64_t sb_per_row = ne0 / QK_K;

    for (int i3 = 0; i3 < ne3; i3++) {
        for (int i2 = 0; i2 < ne2; i2++) {
            const block_q4_K * src_slice = src_matrix + (i3 * ne2 + i2) * (ne1 * sb_per_row);
            uint8_t * matrix_dst = (uint8_t *) t->data + (i3 * ne2 + i2) * matrix_size;

            memset(matrix_dst, 0, matrix_size);

            for (int64_t r = 0; r < ne1; r++) {
                const int ct  = (int) (r / 32);
                const int row = (int) (r % 32);
                const block_q4_K * src_row = src_slice + r * sb_per_row;

                for (int kt = 0; kt < n_k_tiles; kt++) {
                    const int kt_local = kt % 8;
                    const block_q4_K * b = &src_row[kt / 8];
                    const float d = GGML_FP16_TO_FP32(b->d);
                    const float dmin = GGML_FP16_TO_FP32(b->dmin);

                    uint8_t * tile_dst = matrix_dst + ((size_t) ct * n_k_tiles + kt) * tile_size;

                    uint8_t sc, m;
                    get_scale_min_k4(kt_local, b->scales, &sc, &m);

                    const float D = d * (float) sc;
                    const float M = -dmin * (float) m;

                    const uint8_t * qs_sub = b->qs + (kt_local / 2) * 32;
                    const int shift = (kt_local & 1) ? 4 : 0;

                    for (int cp = 0; cp < 16; cp++) {
                        const uint8_t q0 = (qs_sub[2 * cp + 0] >> shift) & 0x0F;
                        const uint8_t q1 = (qs_sub[2 * cp + 1] >> shift) & 0x0F;
                        tile_dst[cp * 32 + row] = (uint8_t) ((q1 << 4) | q0);
                    }

                    ggml_half * scale_dst = (ggml_half *) (tile_dst + 512);
                    scale_dst[2 * row + 0] = GGML_FP32_TO_FP16(D);
                    scale_dst[2 * row + 1] = GGML_FP32_TO_FP16(M);
                }
            }
        }
    }

    GGML_UNUSED(size);
}
static float leitor_q4k_w(const uint8_t * tiled, int n_k_tiles, int64_t r, int64_t k) {
    const int ct = (int)(r / 32), row = (int)(r % 32);
    const int kt = (int)(k / 32), k_loc = (int)(k % 32);
    const uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * 640;
    const int cp = k_loc / 2;
    const uint8_t qv = tile[cp * 32 + row];
    const uint8_t q = (k_loc & 1) ? (qv >> 4) : (qv & 0x0F);
    const ggml_half * sc = (const ggml_half *) (tile + 512);
    const float D = GGML_FP16_TO_FP32(sc[2 * row + 0]);
    const float M = GGML_FP16_TO_FP32(sc[2 * row + 1]);
    return D * (float) q + M;
}

int main(void) {
 const int K=256,N=64; float src[K*N],dc[K*N]; block_q4_K q[N];
 for(int i=0;i<K*N;i++) src[i]=(float)(sin((double)i*0.017)*1.7);
 quantize_q4_K(src,q,N,K,NULL);
 for(int r=0;r<N;r++) dequantize_row_q4_K(q+r,dc+(size_t)r*K,K);
 uint8_t tiled[10240]; memset(tiled,0,sizeof(tiled));
 struct ggml_tensor t; memset(&t,0,sizeof(t)); t.ne[0]=K;t.ne[1]=N;t.ne[2]=1;t.ne[3]=1;t.data=tiled;t.type=GGML_TYPE_Q4_K;
 repack_q4_K_tiled(&t,q,0,sizeof(q));
 long bad=0,nf=0,bad_low=0,bad_high=0; double maxabs=0;
 for(int r=0;r<N;r++) for(int k=0;k<K;k++) {
   float w=leitor_q4k_w(tiled,K/32,r,k), ref=dc[r*K+k];
   if(!isfinite(w)||!isfinite(ref)){nf++;continue;}
   double d=fabs((double)w-ref);if(d>maxabs)maxabs=d;
   if(d>0.05){bad++;if(k<128)bad_low++;else bad_high++;}
 }
 printf("Q4K same_reader bad=%ld nonfinite=%ld max_abs=%.9f bad_k0_127=%ld bad_k128_255=%ld\n",bad,nf,maxabs,bad_low,bad_high);
 return bad||nf?1:0;
}
