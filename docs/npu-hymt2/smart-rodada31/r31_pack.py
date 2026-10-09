#!/usr/bin/env python3
# r31_pack.py: pacote final R31 — inc novo (GGML_ABORT!) + T4 refinado + mutantes!
import re, os, shutil
NL = chr(10)
base = "/root/r31_t"
shutil.rmtree(base, ignore_errors=True)

# (1) o inc adaptado (do gen NOVO com GGML_ABORT!)
inc = open("/root/r29_settensor_gen.inc").read()   # (re-extraido: L1318..1393!)
inc = inc.replace("static void ggml_backend_hexagon_buffer_set_tensor(", "static void set_tensor_real(")
inc = inc.replace(
    '    auto sbuf = (ggml_hexagon_shared_buffer *) buffer->context;\n'
    '    auto sess = sbuf->sess;\n',
    '    // ADAPTADO (declarado!): sbuf/sess = contexto nao exercitado!\n'
    '    (void) buffer;\n')
inc = re.sub(r'    HEX_VERBOSE\("ggml-hex: %s set-tensor.*?\n', '    // HEX_VERBOSE removido (log externo! declarado!)\n', inc)
print("inc com GGML_ABORT?", "GGML_ABORT" in inc)

# (2) o FIXTURE: base r29 + T4 refinado!
fx = open("/root/r29_fixture.c").read()
old = """        // offset != 0: REJEITADO = buffer INTACTO (nada escrito!) e sem aborto!
        memset(buf, 0, 10240 + 4096);
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) { set_tensor_real(nullptr, &t, q, 32, canon); }
        long mudou_off = 0;
        for (int i = 0; i < 10240; i++) if (buf[i] != 0) mudou_off++;
        int ok_off = (g_aborted == 0 && mudou_off == 0);   // rejeitado SEM efeitos!
        printf("  [%s] T4a offset!=0 REJEITADO sem efeitos (abort=%d bytes-escritos=%ld!)\\n", ok_off ? "OK " : "RED", g_aborted, mudou_off);
        if (!ok_off) failures++;
        // size parcial: idem!
        memset(buf, 0, 10240 + 4096);
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) { set_tensor_real(nullptr, &t, q, 0, canon / 2); }
        long mudou_sz = 0;
        for (int i = 0; i < 10240; i++) if (buf[i] != 0) mudou_sz++;
        int ok_sz = (g_aborted == 0 && mudou_sz == 0);
        printf("  [%s] T4b size parcial REJEITADO sem efeitos (abort=%d bytes-escritos=%ld!)\\n", ok_sz ? "OK " : "RED", g_aborted, mudou_sz);
        if (!ok_sz) failures++;"""
new = """        // R31 (refinado!): REJEICAO_OBSERVAVEL (abort ativo OU recusa) + ZERO efeitos!
        // (nao exige abort==0: a rejeicao ATIVA do backend e' valida!)
        memset(buf, 0, 10240 + 4096);
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) { set_tensor_real(nullptr, &t, q, 32, canon); }
        long mudou_off = 0;
        for (int i = 0; i < 10240; i++) if (buf[i] != 0) mudou_off++;
        int ok_off = (mudou_off == 0);   // SEGURANCA: zero efeitos (abort OU recusa!)
        printf("  [%s] T4a offset!=0 REJEITADO (rejeicao=%s!) com ZERO efeitos (bytes=%ld!)\\n",
               ok_off ? "OK " : "RED", g_aborted ? "ATIVA(abort!)" : "recusa!", mudou_off);
        if (!ok_off) failures++;
        memset(buf, 0, 10240 + 4096);
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) { set_tensor_real(nullptr, &t, q, 0, canon / 2); }
        long mudou_sz = 0;
        for (int i = 0; i < 10240; i++) if (buf[i] != 0) mudou_sz++;
        int ok_sz = (mudou_sz == 0);
        printf("  [%s] T4b size parcial REJEITADO (rejeicao=%s!) com ZERO efeitos (bytes=%ld!)\\n",
               ok_sz ? "OK " : "RED", g_aborted ? "ATIVA(abort!)" : "recusa!", mudou_sz);
        if (!ok_sz) failures++;"""
assert old in fx, "T4 antigo nao achado!"
fx = fx.replace(old, new, 1)
open("/root/r31_fixture.c", "w").write(fx)
print("fixture R31 ok!")

# (3) os cenarios!
for d in ["green", "m1", "m2", "m3", "m4", "m5"]:
    os.makedirs(base + "/" + d)
    open(base + "/" + d + "/r31_fixture.c", "w").write(fx)
    open(base + "/" + d + "/r29_settensor_adap.inc", "w").write(inc)
    shutil.copy("/root/r29_cores_gen.h", base + "/" + d + "/r29_cores_gen.h")

# (4) os mutantes!
def remove_case(t, tag):
    lines = t.splitlines(); out = []; i = 0; removed = 0
    repack = "repack_q%s_K_tiled" % ("4" if tag == "Q4_K" else "6")
    while i < len(lines):
        if lines[i].strip() == ("case GGML_TYPE_%s:" % tag):
            while out and (out[-1].strip().startswith("//") or out[-1].strip() == ""): out.pop()
            seen = False
            while i < len(lines):
                if repack in lines[i]: seen = True
                if seen and lines[i].strip() == "break;": break
                i += 1
            i += 1
            if i < len(lines) and lines[i].strip() == "": i += 1
            removed += 1; continue
        out.append(lines[i]); i += 1
    assert removed == 1, "remove %s: %d" % (tag, removed)
    return NL.join(out)
for d, tags in [("m1", ["Q4_K", "Q6_K"]), ("m2", ["Q6_K"])]:
    t = inc
    for tag in tags: t = remove_case(t, tag)
    open(base + "/" + d + "/r29_settensor_adap.inc", "w").write(t)
# m3/m4: gen mutado!
h = open("/root/r29_cores_gen.h").read()
open(base + "/m3/r29_cores_gen.h", "w").write(h.replace("    if (ggml_hexagon_is_repack_type(t->type)) {",
    "    if (t->type == GGML_TYPE_Q4_0 || t->type == GGML_TYPE_Q4_1 || t->type == GGML_TYPE_Q8_0 || t->type == GGML_TYPE_IQ4_NL || t->type == GGML_TYPE_MXFP4) {   // MUTANTE!", 1))
open(base + "/m4/r29_cores_gen.h", "w").write(h.replace("*m = (q[j+4] >>  4) | ((q[j-0] >> 6) << 4);",
    "*m = (q[j+4] >>  4) | ((q[j-4] >> 6) << 4);   // MUTANTE (indice errado!)", 1))
# m5: guards (GGML_ABORT!) removidos do switch!
def remove_guard(t):
    out = []
    for block in ["q4_K", "q6_K"]:
        old_g = '            if (offset != 0 || size != (size_t) ggml_nbytes(tensor)) {\n'
        i = t.find(old_g)
        assert i > 0, block + ": guard nao achado!"
        j = t.find("            }\n", i)
        t = t[:i] + t[j+len("            }\n"):]
    return t
open(base + "/m5/r29_settensor_adap.inc", "w").write(remove_guard(inc))
print("mutantes ok!")
