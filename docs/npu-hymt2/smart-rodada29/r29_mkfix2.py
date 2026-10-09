#!/usr/bin/env python3
# r29_mkfix2.py: fixture R29 robusto (sem asserts de texto fragil!)
import re

# (1) o inc ADAPTADO (declarado!):
inc = open("/root/r29_settensor_gen.inc").read()
inc = inc.replace("static void ggml_backend_hexagon_buffer_set_tensor(", "static void set_tensor_real(")
# adaptar as linhas de contexto (declarado!)
inc = inc.replace(
    '    auto sbuf = (ggml_hexagon_shared_buffer *) buffer->context;\n'
    '    auto sess = sbuf->sess;\n',
    '    // ADAPTADO (declarado!): sbuf/sess = contexto de sessao nao exercitado!\n'
    '    (void) buffer;\n')
# o HEX_VERBOSE (usa sess->c_name()!) -> removido (declarado!)
inc = re.sub(r'    HEX_VERBOSE\("ggml-hex: %s set-tensor.*?\n', '    // HEX_VERBOSE removido (log externo! declarado!)\n', inc)
open("/root/r29_settensor_adap.inc", "w").write(inc)
print("inc adaptado ok (set_tensor_real!)")

# (2) fixture: partir do r28 + transformacoes:
t = open("/root/r28_fixture.c").read()
# (a) include do gen novo!
t = t.replace('#include "r28_cores_gen.h"', '#include "r29_cores_gen.h"')
# (b) remover o helper transcrito (por funcao!)
def remove_func(s, sig):
    i = s.find(sig)
    if i < 0: return s, False
    depth = 0; started = False; j = i
    for j in range(i, len(s)):
        ch = s[j]
        if ch == "{": depth += 1; started = True
        elif ch == "}": depth -= 1
        if started and depth == 0:
            j += 1
            break
    # remover tambem a linha de comentario anterior!
    k = s.rfind("\n", 0, i)
    prev = s.rfind("\n", 0, k)
    if "// get_scale_min_k4 VERBATIM" in s[prev:k]:
        i = prev + 1
    return s[:i] + "// (get_scale_min_k4 agora vem do GEN extraido!)\n" + s[j:], True
t, ok1 = remove_func(t, "static inline void get_scale_min_k4(")
# (c) remover o set_tensor_real antigo (wrapper)!
t, ok2 = remove_func(t, "static void set_tensor_real(")
# remover o comentario do wrapper antigo + o include antigo!
t = re.sub(r'// === o CALLBACK REAL.*?\n', '// === o CALLBACK REAL: set_tensor COMPLETO (inc adaptado!)\n', t, count=1)
# (d) inserir o include do inc adaptado (antes do main!)
t = t.replace("int main(void) {", '#include "r29_settensor_adap.inc"\n\nint main(void) {', 1)
open("/root/r29_fixture.c", "w").write(t)
print("fixture ok: helper removido=%s, wrapper removido=%s" % (ok1, ok2))
# sanity: o set_tensor_real vem do inc!
print("set_tensor_real no fixture?", "set_tensor_real" in t or "include \"r29_settensor_adap.inc\"" in t)
print("gen novo?", "r29_cores_gen.h" in t)
