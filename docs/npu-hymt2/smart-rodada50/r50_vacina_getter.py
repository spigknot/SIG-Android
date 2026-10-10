#!/usr/bin/env python3
# r50_vacina_getter.py — R50 §2.2: vacina PERMANENTE do getter (MESMO getter do probe!)
# Extrai o getter LITERAL do fonte real (/root/sigcand2/.../ggml-hexagon.cpp) e o
# testa contra: writer modelado 1:1 (h[2]/h[4..7] do op_matmul + h[8..14] do trace R50);
# OFF; cap-curta ANTES do load; magic/epoch stale; captured sem entered; duplicatas.
# Fonte: /root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp (pos-R50!)
import re, sys, os, subprocess, hashlib

SRC = "/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
SRC2 = "/root/sigcand2/ggml/src/ggml-hexagon/htp/matmul-ops.c"
OUT = "/root/r50_vac"
os.makedirs(OUT, exist_ok=True)
src = open(SRC, encoding="utf-8", errors="replace").read()
src2 = open(SRC2, encoding="utf-8", errors="replace").read()

def grab(pat, tag):
    m = re.search(pat, src, re.S)
    if not m:
        print(f"VAC_EXTRAI_FAIL: {tag}"); sys.exit(1)
    return m.group(0)

def grab2(pat, tag):
    m = re.search(pat, src2, re.S)
    if not m:
        print(f"VAC_EXTRAI_FAIL: {tag}"); sys.exit(1)
    return m.group(0)

struct_status = grab(r"struct ggml_hexagon_act_dump_status \{.*?\n\};", "struct")
magic = grab(r"#define R46_MAGIC [^\n]*", "magic")
meta = grab(r"static struct \{.*?\} g_r46_meta = \{0\};", "meta")
armed_var = grab(r"static int g_r46_armed = -1;", "armed_var")
armed = grab(r"static bool g_r46_act_dump_armed\(void\) \{.*?\n\}", "armed")
getter = grab(r"static int ggml_hexagon_get_act_dump\(void \* out_v, size_t out_size\) \{.*?\n\}", "getter")
sbuf_decl = grab(r"static ggml_hexagon_shared_buffer \* g_r46_act_dump_sbuf = nullptr;", "sbuf")

# bloco h[2]/h[4..7] do op_matmul (literal, no SKEL!) + bloco h[8..14] do trace (literal)
hblock = grab2(r"            uint32_t \* h = \(uint32_t \*\) octx->act_dump_ptr;\n            h\[2\] = 1;.*?h\[7\] = 0xA5A50001u;.*?\n", "hblock")
traceblock = grab2(r"        uint32_t \* h = \(uint32_t \*\) octx->act_dump_ptr;\n        h\[8\]  = src1_nrows;.*?\n", "traceblock")
print("hblock_anchor==skel:", "h[2] = 1" in hblock and "0xA5A50001u" in hblock)
print("traceblock_anchor==skel:", "h[8]  = src1_nrows" in traceblock)

harness = r'''
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

''' + magic + "\n\n" + sbuf_decl + "\n\n" + struct_status + "\n\n" + meta + "\n\n" + armed_var + "\n\n" + armed + "\n\n" + getter + r'''

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
'''

p = f"{OUT}/vac_getter.cpp"
open(p, "w", encoding="utf-8", newline="\n").write(harness)
print("gerado:", p, len(harness), "bytes")
print("getter extraido (sha256):", hashlib.sha256(getter.encode()).hexdigest()[:16])
sub = subprocess.run(["g++", "-std=c++17", "-O2", "-Wall", "-Wextra", p, "-o", f"{OUT}/vac_getter"],
                     capture_output=True, text=True)
print("BUILD_RC=", sub.returncode)
if sub.returncode != 0:
    print(sub.stderr[-3000:]); sys.exit(1)
for mode in ("off", "on"):
    rr = subprocess.run([f"{OUT}/vac_getter", mode], capture_output=True, text=True)
    print(f"----- RUN {mode} EXIT={rr.returncode}")
    print(rr.stdout.strip())
