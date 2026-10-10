#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <stdbool.h>
#include <sys/stat.h>
#include <unistd.h>
// ---- SHIMS de IO (controlaveis; NAO dereferenciam o payload!) ----
static int g_modo = 0;
static FILE * g_fake = (FILE *) 0x1000;
static int sh_mkdir(const char * p, mode_t m) {
    (void) p; (void) m;
    if (strstr(p, "r40_1_test") != NULL) return (g_modo == 2) ? -1 : 0;   /* dir do attempt */
    return (g_modo == 1) ? -1 : 0;                                        /* raiz */
}
static FILE * sh_fopen(const char * p, const char * m) {
    (void) m;
    if (strstr(p, "status.txt")) return (g_modo == 8) ? NULL : g_fake;
    return (g_modo == 3) ? NULL : g_fake;
}
static size_t sh_fwrite(const void * d, size_t s, size_t n, FILE * f) {
    (void) d; (void) f;
    return (g_modo == 4) ? (s * n) - 1 : s * n;
}
static int sh_fflush(FILE * f) { (void) f; return (g_modo == 5) ? EOF : 0; }
static int g_close_n = 0;
static int sh_fclose(FILE * f) {
    (void) f; g_close_n++;
    if (g_modo == 6 && g_close_n == 1) return EOF;    /* fclose do PAYLOAD */
    if (g_modo == 10 && g_close_n == 2) return EOF;   /* fclose do STATUS  */
    if (g_modo == 11) return EOF;                     /* ambos */
    return 0;
}
static int sh_rename(const char * a, const char * b) { (void) a; (void) b; return (g_modo == 7) ? -1 : 0; }
static int sh_fprintf(FILE * f, const char * fmt, ...) { (void) f; (void) fmt; return 1; }
static int sh_ferror(FILE * f) { (void) f; return (g_modo == 9) ? 1 : 0; }
#define mkdir(p, m)   sh_mkdir(p, m)
#define fopen(p, m)   sh_fopen(p, m)
#define fwrite        sh_fwrite
#define fflush(f)     sh_fflush(f)
#define fclose(f)     sh_fclose(f)
#define rename(a, b)  sh_rename(a, b)
#define fprintf       sh_fprintf
#define ferror(f)     sh_ferror(f)
#define getpid()      4242
static long g_attempt_seq = 0;
static int fail = 0;
static void rep(const char * fmt, ...) { (void) fmt; }
typedef struct {
    uint32_t magic, version; int32_t status, type, K, N, B;
    uint32_t epoch, rows_done, payload_off, reserved;
    uint64_t ptr, capacity, row_size;
    int32_t r49_n_hmx, r49_kernel_type, r49_op_entered, r49_reserved;
    int32_t r50_meta_valid, r50_firstcause;
    uint32_t r50_src1_nrows, r50_n_threads, r50_need_quant, r50_n_quant_tasks;
    uint32_t r50_quant_func_id, r50_dsp_decoded, r50_total_nb, r50_reserved;
} r48_st;
static int rodar(int modo, r48_st st48) {
    g_modo = modo; fail = 0; g_close_n = 0;
    const bool val48 = true;
    const char * attempt39 = "r40_1_test";
    bool r48_capture_required = true;
    int g48_ret = 0;
    (void) val48; (void) attempt39; (void) r48_capture_required; (void) g48_ret;
int cap_rc48 = 0;
if (val48) {
    char dir48[300], tmp48[340], fin48[340];
    snprintf(dir48, sizeof(dir48), "/data/data/br.gov.sp.pcsp.npuprobe/files/r50/%s", attempt39);
    // R51 §2.5: NAMESPACE EXCLUSIVO — a RAIZ pode pre-existir (EEXIST ok);
    // o dir do ATTEMPT e' unico (seq+pid) => EEXIST/colisao = ERRO (nunca clobber!)
    if (mkdir("/data/data/br.gov.sp.pcsp.npuprobe/files/r50", 0770) != 0 && errno != EEXIST) { rep("R51 EXPORT_FAIL (mkdir r50: %s!)", strerror(errno)); cap_rc48 = -3; }
    else if (mkdir(dir48, 0770) != 0) { rep("R51 EXPORT_FAIL (dir EXCLUSIVO do attempt ja existe/falhou: %s!)", strerror(errno)); cap_rc48 = -3; }
    else {
        snprintf(tmp48, sizeof(tmp48), "%s/act_payload.bin.tmp_%d_%ld", dir48, (int) getpid(), (long) ++g_attempt_seq);
        snprintf(fin48, sizeof(fin48), "%s/act_payload.bin", dir48);
        FILE * f48 = fopen(tmp48, "wb");
        if (!f48) { rep("R50 EXPORT_FAIL (fopen!)"); cap_rc48 = -4; }
        else {
            const size_t wr48 = fwrite((const void *) ((uintptr_t) st48.ptr + st48.payload_off), 1, (size_t) st48.row_size, f48);
            const int ff48 = fflush(f48);
            const int fc48 = fclose(f48);
            if (wr48 == (size_t) st48.row_size && ff48 == 0 && fc48 == 0) {
                if (rename(tmp48, fin48) == 0) rep("R50 EXPORT ok: %zu bytes -> %s", wr48, fin48);
                else { rep("R50 EXPORT_FAIL (rename: %s!)", strerror(errno)); cap_rc48 = -5; }
            } else { rep("R50 EXPORT_FAIL (wr=%zu/%zu ff=%d fc=%d!)", wr48, (size_t) st48.row_size, ff48, fc48); cap_rc48 = -6; }
        }
        // R51 §2.5: o MANIFEST e' parte OBRIGATORIA do bundle — falha PROPAGA!
        { char man48[340];
          snprintf(man48, sizeof(man48), "%s/status.txt", dir48);
          FILE * fm = fopen(man48, "w");
          int st_ok = 0;
          if (fm) {
            fprintf(fm, "status=%d version=%u epoch=%u rows_done=%u row_size=%llu cap=%llu payload_off=%u\n",
                    st48.status, st48.version, st48.epoch, st48.rows_done,
                    (unsigned long long) st48.row_size, (unsigned long long) st48.capacity, st48.payload_off);
            fprintf(fm, "meta_valid=%d firstcause=%d entered=%d n_hmx=%d kernel_type=%d decoded=%d\n",
                    st48.r50_meta_valid, st48.r50_firstcause, st48.r49_op_entered, st48.r49_n_hmx,
                    st48.r49_kernel_type, st48.r50_dsp_decoded);
            fprintf(fm, "qfn=%u nqt=%u src1_nrows=%u nthr=%u need_quant=%u total_nb=%u K=%d N=%d B=%d type=%d\n",
                    st48.r50_quant_func_id, st48.r50_n_quant_tasks, st48.r50_src1_nrows,
                    st48.r50_n_threads, st48.r50_need_quant, st48.r50_total_nb,
                    st48.K, st48.N, st48.B, st48.type);
            st_ok = (ferror(fm) == 0) ? 1 : 0;
            if (fclose(fm) != 0) st_ok = 0;
          }
          if (!st_ok) { rep("R51 EXPORT_FAIL (status.txt write/close falhou — bundle incompleto!)"); if (cap_rc48 == 0) cap_rc48 = -7; }
        }
    }
} else {
    cap_rc48 = (g48_ret != 0) ? -1 : -2;
}
if (r48_capture_required && cap_rc48 != 0) {
    fail++;   // CONSUMIDO: a captura requerida falhou => fail do caso!
    rep("R51 CAPTURE_REQUIRED_FAIL (cap_rc=%d — consumido no fail!)", cap_rc48);
} else if (r48_capture_required) {
    rep("R51 CAPTURE_REQUIRED_OK (a captura requerida foi validada e exportada!)");
}
    return cap_rc48;
}
int main() {
    r48_st s; memset(&s, 0, sizeof(s));
    s.status = 2; s.rows_done = 1; s.row_size = 221184; s.capacity = 262144; s.payload_off = 64; s.ptr = 1;
    int falhas = 0;
    struct { int modo; int esperado; const char * nome; } casos[] = {
        {0, 0, "OK (export completo)"},
        {1, -3, "raiz mkdir falha (EACCES)"},
        {2, -3, "dir do attempt EXISTE (EEXIST=erro)"},
        {3, -4, "fopen tmp falha"},
        {4, -6, "fwrite curto"},
        {5, -6, "fflush falha"},
        {6, -6, "fclose falha"},
        {7, -5, "rename falha"},
        {8, -7, "status.txt fopen falha"},
        {9, -7, "status.txt ferror"},
        {10, -7, "status.txt fclose falha"},
        {11, -6, "ambos fclose falham => PRIMEIRO erro (-6) preservado"},
    };
    for (unsigned i = 0; i < sizeof(casos) / sizeof(casos[0]); i++) {
        int rc = rodar(casos[i].modo, s);
        int ok = (rc == casos[i].esperado) && (fail == ((casos[i].modo == 0) ? 0 : 1));
        printf("%s modo=%d cap_rc=%d (esperado %d) fail=%d => %s\n",
               ok ? "PASS" : "FAIL", casos[i].modo, rc, casos[i].esperado, fail, casos[i].nome);
        if (!ok) falhas++;
    }
    printf("VAC_EXPORT falhas=%d %s\n", falhas, falhas ? "RED" : "GREEN");
    return falhas ? 1 : 0;
}
