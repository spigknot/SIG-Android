#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
#define QK_K 256
#define GGML_RESTRICT __restrict__
using ggml_half=uint16_t;
static float hf(uint16_t h){unsigned e=(h>>10)&31,m=h&1023;float f;if(e==0)f=std::ldexp((float)m,-24);else if(e==31)f=m?std::numeric_limits<float>::quiet_NaN():std::numeric_limits<float>::infinity();else {uint32_t b=((e+112)<<23)|(m<<13);std::memcpy(&f,&b,4);}return h&0x8000?-f:f;}
#define GGML_FP16_TO_FP32 hf
typedef struct {
    uint8_t ql[QK_K/2];      // quants, lower 4 bits
    uint8_t qh[QK_K/4];      // quants, upper 2 bits
    int8_t  scales[QK_K/16]; // scales, quantized with 8 bits
    ggml_half d;             // super-block scale
} block_q6_K;
static_assert(sizeof(block_q6_K)==210,"Q6 size");
void dequantize_row_q6_K(const block_q6_K * GGML_RESTRICT x, float * GGML_RESTRICT y, int64_t k) {
    assert(k % QK_K == 0);
    const int64_t nb = k / QK_K;

    for (int i = 0; i < nb; i++) {
        const float d = GGML_FP16_TO_FP32(x[i].d);

        const uint8_t * GGML_RESTRICT ql = x[i].ql;
        const uint8_t * GGML_RESTRICT qh = x[i].qh;
        const int8_t  * GGML_RESTRICT sc = x[i].scales;

        for (int n = 0; n < QK_K; n += 128) {
            for (int l = 0; l < 32; ++l) {
                int is = l/16;
                const int8_t q1 = (int8_t)((ql[l +  0] & 0xF) | (((qh[l] >> 0) & 3) << 4)) - 32;
                const int8_t q2 = (int8_t)((ql[l + 32] & 0xF) | (((qh[l] >> 2) & 3) << 4)) - 32;
                const int8_t q3 = (int8_t)((ql[l +  0]  >> 4) | (((qh[l] >> 4) & 3) << 4)) - 32;
                const int8_t q4 = (int8_t)((ql[l + 32]  >> 4) | (((qh[l] >> 6) & 3) << 4)) - 32;
                y[l +  0] = d * sc[is + 0] * q1;
                y[l + 32] = d * sc[is + 2] * q2;
                y[l + 64] = d * sc[is + 4] * q3;
                y[l + 96] = d * sc[is + 6] * q4;
            }
            y  += 128;
            ql += 64;
            qh += 32;
            sc += 8;
        }
    }
}
static float leitor_q6k_w(const uint8_t * tiled, int n_k_tiles, int r, int k) {
    const int ct = r / 32, row = r % 32;
    const int kt = k / 32, lk = k % 32;
    const uint8_t * tile = tiled + ((size_t) ct * n_k_tiles + kt) * 896;
    const uint8_t * lo_pl = tile;
    const uint8_t * hi_pl = tile + 512;
    const ggml_half * sc_pl = (const ggml_half *) (tile + 768);
    const int g = lk >> 2, pos = row * 4 + (lk & 3);
    const uint8_t lo = (uint8_t) ((lo_pl[(g >> 1) * 128 + pos] >> ((g & 1) * 4)) & 0xF);
    const uint8_t hi = (uint8_t) ((hi_pl[(g >> 2) * 128 + pos] >> ((g & 3) * 2)) & 3);
    const uint8_t q6 = (uint8_t)(lo | (hi << 4));
    const int sub = lk / 16;
    const float sc = GGML_FP16_TO_FP32(sc_pl[sub * 32 + row]);
    return sc * (float)((int) q6 - 32);
}
static std::vector<uint8_t> load(const char*p,size_t n){FILE*f=std::fopen(p,"rb");if(!f)throw std::runtime_error(p);std::vector<uint8_t>b(n);size_t got=std::fread(b.data(),1,n,f);int extra=std::fgetc(f);std::fclose(f);if(got!=n||extra!=EOF)throw std::runtime_error("bad size");return b;}
static void dump(const std::string&p,const void*d,size_t n){FILE*f=std::fopen(p.c_str(),"wb");if(!f)throw std::runtime_error(p);bool ok=std::fwrite(d,1,n,f)==n;int c=std::fclose(f);if(!ok||c)throw std::runtime_error("write fail");}
static void stats(const char*label,const std::vector<double>&ref,const float*got){double se=0,ss=0,maxa=0;int viol=0,nf=0;for(size_t i=0;i<ref.size();i++){if(!std::isfinite(ref[i])||!std::isfinite(got[i])){nf++;continue;}double d=std::fabs(ref[i]-got[i]);se+=d*d;ss+=ref[i]*ref[i];maxa=std::fmax(maxa,d);viol+=d>0.02+0.05*std::fabs(ref[i]);}std::printf("%s nd=%zu nf=%d max_abs=%.17g nmse=%.17g legacy_viol=%d\n",label,ref.size()-nf,nf,maxa,ss>0?se/ss:-1,viol);}
int main(int argc,char**argv){if(argc!=8)return 64;try{const int K=6144,N=2048;auto w=load(argv[1],10321920),t=load(argv[2],11010048),x=load(argv[3],24576),a=load(argv[4],24576),h=load(argv[5],8192),c=load(argv[6],8192);std::string out=argv[7];std::vector<float>wf(K);std::vector<double> sf(N),tf(N),sr(N),tr(N),alt(N);const float*xf=(float*)x.data(),*ar=(float*)a.data(),*ht=(float*)h.data(),*cpu=(float*)c.data();
for(int n=0;n<N;n++){dequantize_row_q6_K((block_q6_K*)(w.data()+(size_t)n*24*210),wf.data(),K);for(int k=0;k<K;k++){double wc=wf[k],wt=leitor_q6k_w(t.data(),192,n,k);sf[n]+=wc*xf[k];tf[n]+=wt*xf[k];sr[n]+=wc*ar[k];tr[n]+=wt*ar[k];alt[n]+=wt*ar[k^1];}}
stats("CPU_Rscalar_F32",sf,cpu);stats("CPU_Rtiled_F32",tf,cpu);stats("HTP_Rscalar_F32",sf,ht);stats("HTP_Rtiled_F32",tf,ht);stats("HTP_Rscalar_ACT_REAL",sr,ht);stats("HTP_Rtiled_ACT_REAL",tr,ht);stats("HTP_Rtiled_ACT_REAL_adjacent_swap_control",alt,ht);
dump(out+"/Rscalar_F32_f64.bin",sf.data(),N*8);dump(out+"/Rtiled_F32_f64.bin",tf.data(),N*8);dump(out+"/Rscalar_ACT_REAL_f64.bin",sr.data(),N*8);dump(out+"/Rtiled_ACT_REAL_f64.bin",tr.data(),N*8);dump(out+"/adjacent_swap_control_f64.bin",alt.data(),N*8);
std::vector<float>halves(65536);for(int i=0;i<65536;i++)halves[i]=hf((uint16_t)i);dump(out+"/half_converter_fixture_f32.bin",halves.data(),65536*4);return 0;}catch(const std::exception&e){std::fprintf(stderr,"ERROR %s\n",e.what());return 2;}}
