#!/usr/bin/env python3
# r51_vacina_export.py — R51 §2.5: vacina do FLUXO DE EXPORT (bloco LITERAL do probe)
# Shims de IO controlaveis por modo; cada falha deve produzir o cap_rc esperado.
import re
PROBE = "C:/npu-probe/app/src/main/cpp/probe.cpp"
t = open(PROBE, encoding="utf-8", errors="replace").read()
i = t.index("                    int cap_rc48 = 0;")
j = t.index('rep("R51 CAPTURE_REQUIRED_OK (a captura requerida foi validada e exportada!)");', i)
k = t.index("\n                    }", j) + len("\n                    }")
bloco = t[i:k]
assert "mkdir(dir48, 0770) != 0" in bloco and "status.txt write/close falhou" in bloco, "bloco R51 nao extraido!"
# desindentar 20 espacos
bloco2 = "\n".join(l[20:] if l.startswith(" " * 20) else l for l in bloco.splitlines())

harness = '''#include <stdio.h>
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
BLOCK
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
        printf("%s modo=%d cap_rc=%d (esperado %d) fail=%d => %s\\n",
               ok ? "PASS" : "FAIL", casos[i].modo, rc, casos[i].esperado, fail, casos[i].nome);
        if (!ok) falhas++;
    }
    printf("VAC_EXPORT falhas=%d %s\\n", falhas, falhas ? "RED" : "GREEN");
    return falhas ? 1 : 0;
}
'''
harness = harness.replace("BLOCK", bloco2)
open("C:/llama-npu/rodada2/r51_export_v51.c", "w", encoding="utf-8", newline="\n").write(harness)
print("gerado r51_export_v51.c:", len(harness), "bytes; bloco:", len(bloco2), "chars")
