#!/usr/bin/env python3
# r29_t4.py: atualiza o T4 (criterio: rejeicao = buffer INTACTO!) + monta os cenarios!
import subprocess

# (1) atualizar o fixture: T4 com o criterio de "nada escrito"!
fx = open("/root/r29_fixture.c").read()
old = """        // offset != 0: o pack real DEVE rejeitar (GGML_ASSERT(offset==0) do pack!)
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) { set_tensor_real(nullptr, &t, q, 32, canon); }
        int ok_off = (g_aborted == 1);   // rejeitado!
        printf("  [%s] T4a offset!=0 REJEITADO pelo pack real (abort=%d esperado 1!)\\n", ok_off ? "OK " : "RED", g_aborted);
        if (!ok_off) failures++;
        // size parcial: tambem rejeitado (assert do pack!)
        g_aborted = 0;
        if (setjmp(g_jmp) == 0) { set_tensor_real(nullptr, &t, q, 0, canon / 2); }
        int ok_sz = (g_aborted == 1);
        printf("  [%s] T4b size parcial REJEITADO (abort=%d esperado 1!)\\n", ok_sz ? "OK " : "RED", g_aborted);
        if (!ok_sz) failures++;"""
new = """        // offset != 0: REJEITADO = buffer INTACTO (nada escrito!) e sem aborto!
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
assert old in fx, "T4 antigo nao achado!"
fx = fx.replace(old, new, 1)
open("/root/r29_fixture.c", "w").write(fx)
print("T4 atualizado!")

# (2) os cenarios: green (com o inc/guarda NOVOS!) + m1/m2/m3/m4/m5!
import shutil, os
base = "/root/r29_t"
shutil.rmtree(base, ignore_errors=True)
for d in ["green", "m1", "m2", "m3", "m4", "m5"]:
    os.makedirs(base + "/" + d)
    for f in ["r29_fixture.c", "r29_cores_gen.h", "r29_settensor_adap.inc"]:
        shutil.copy("/root/" + f, base + "/" + d + "/")
# regerar o inc ADAPTADO (o gen novo tem os guards!)
inc = open("/root/r29_settensor_gen.inc").read()
inc = inc.replace("static void ggml_backend_hexagon_buffer_set_tensor(", "static void set_tensor_real(")
inc = inc.replace(
    '    auto sbuf = (ggml_hexagon_shared_buffer *) buffer->context;\n'
    '    auto sess = sbuf->sess;\n',
    '    // ADAPTADO (declarado!): sbuf/sess = contexto de sessao nao exercitado!\n'
    '    (void) buffer;\n')
import re as _re
inc = _re.sub(r'    HEX_VERBOSE\("ggml-hex: %s set-tensor.*?\n', '    // HEX_VERBOSE removido (log externo! declarado!)\n', inc)
for d in ["green", "m1", "m2", "m3", "m4", "m5"]:
    open(base + "/" + d + "/r29_settensor_adap.inc", "w").write(inc)
print("inc adaptado (com guards!) regenerado nos 6!")
