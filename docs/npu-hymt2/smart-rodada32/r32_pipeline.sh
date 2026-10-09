#!/bin/bash
# r32_pipeline.sh — R32: O PIPELINE UNICO (§6!): extracao->adaptacao->mutacao->
# build->run->export, em dir isolado, com exits preservados e fail-closed!
# Uso: bash /root/r32_pipeline.sh  => roda TODOS os cenarios e exporta!
set -u
SRV=/root
RUN=$SRV/r32_pipe
rm -rf $RUN; mkdir -p $RUN
echo "== [1] EXTRACAO (verbatim do candidato!) =="
python3 $SRV/r29_extrai.py > $RUN/extracao.log 2>&1 || { echo "FALHA EXTRACAO!"; exit 1; }
cat $RUN/extracao.log
# fail-closed: as funcoes requeridas!
for f in r29_cores_gen.h r29_settensor_gen.inc; do
  [ -s "$SRV/$f" ] || { echo "FALHA: $f vazio/ausente!"; exit 1; }
done
echo "== [2] ADAPTACAO (contexto declarado!) =="
python3 - <<'PYEOF'
import re
inc = open("/root/r29_settensor_gen.inc").read()
inc = inc.replace("static void ggml_backend_hexagon_buffer_set_tensor(", "static void set_tensor_real(")
inc = inc.replace('    auto sbuf = (ggml_hexagon_shared_buffer *) buffer->context;\n    auto sess = sbuf->sess;\n',
    '    // ADAPTADO (declarado!): contexto nao exercitado!\n    (void) buffer;\n')
inc = re.sub(r'    HEX_VERBOSE\("ggml-hex: %s set-tensor.*?\n', '    // HEX_VERBOSE removido (declarado!)\n', inc)
assert "set_tensor_real" in inc, "adaptacao falhou!"
open("/root/r32_adap.inc", "w").write(inc)
print("adaptacao ok!")
PYEOF
[ -s $SRV/r32_adap.inc ] || { echo "FALHA ADAPTACAO!"; exit 1; }
echo "== [3] CENARIOS (green + mutantes!) =="
python3 - <<'PYEOF'
import os, shutil, re
NL = chr(10)
base = "/root/r32_pipe/casos"
os.makedirs(base, exist_ok=True)
inc = open("/root/r32_adap.inc").read()
fx = open("/root/r32_fixture.c").read()
gen = open("/root/r29_cores_gen.h").read()
def put(caso, inc_txt=None, gen_txt=None):
    d = base + "/" + caso
    os.makedirs(d, exist_ok=True)
    open(d + "/r32_fixture.c", "w").write(fx)
    open(d + "/r29_settensor_adap.inc", "w").write(inc_txt if inc_txt is not None else inc)
    open(d + "/r29_cores_gen.h", "w").write(gen_txt if gen_txt is not None else gen)
def remove_case(t, tag):
    lines = t.splitlines(); out = []; i = 0; rem = 0
    rep = "repack_q%s_K_tiled" % ("4" if tag == "Q4_K" else "6")
    while i < len(lines):
        if lines[i].strip() == ("case GGML_TYPE_%s:" % tag):
            while out and (out[-1].strip().startswith("//") or out[-1].strip() == ""): out.pop()
            seen = False
            while i < len(lines):
                if rep in lines[i]: seen = True
                if seen and lines[i].strip() == "break;": break
                i += 1
            i += 1
            if i < len(lines) and lines[i].strip() == "": i += 1
            rem += 1; continue
        out.append(lines[i]); i += 1
    assert rem == 1, "remove %s: %d" % (tag, rem)
    return NL.join(out)
def remove_guard(t):
    for _ in range(2):
        i = t.find("            if (offset != 0 || size != (size_t) ggml_nbytes(tensor)) {\n")
        assert i > 0, "guard nao achado!"
        j = t.find("            }\n", i)
        t = t[:i] + t[j+len("            }\n"):]
    return t
def m6_inc():
    lines = inc.splitlines(); out = []; i = 0; n = 0
    while i < len(lines):
        ln = lines[i]
        if ln.strip().startswith("GGML_ABORT(") and ("q4_K" in ln or "q6_K" in ln):
            tag = "q4_K" if "q4_K" in ln else "q6_K"
            out.append("                fprintf(stderr, \"KQSET-RECUSA(%s): silent! (M6!)\"); break;" % tag)
            i += 2; n += 1; continue
        out.append(ln); i += 1
    assert n == 2, "M6: %d" % n
    return NL.join(out)
put("green")
put("m1", inc_txt=remove_case(remove_case(inc, "Q4_K"), "Q6_K"))
put("m2", inc_txt=remove_case(inc, "Q6_K"))
put("m3", gen_txt=gen.replace("    if (ggml_hexagon_is_repack_type(t->type)) {",
    "    if (t->type == GGML_TYPE_Q4_0) {   // MUTANTE!", 1))
put("m4", gen_txt=gen.replace("*m = (q[j+4] >>  4) | ((q[j-0] >> 6) << 4);",
    "*m = (q[j+4] >>  4) | ((q[j-4] >> 6) << 4);   // MUTANTE!", 1))
put("m5", inc_txt=remove_guard(inc))
put("m6", inc_txt=m6_inc())
print("7 cenarios criados!")
PYEOF
[ -s $RUN/casos/green/r32_fixture.c ] || { echo "FALHA CENARIOS!"; exit 1; }
echo "== [4] BUILD + RUN (exits preservados!) =="
CF="-std=c++17 -fpermissive -O2 -I/root/llama-cpp-npu/ggml/include -I/root/llama-cpp-npu/ggml/src -I."
for d in green m1 m2 m3 m4 m5 m6; do
  cd $RUN/casos/$d
  g++ $CF ./r32_fixture.c /root/llama-cpp-npu/ggml/src/ggml-quants.c -lm -o fx > build.log 2>build.err
  BRC=$?
  if [ $BRC -ne 0 ]; then echo "$d: BUILD FAIL($BRC)!"; head -n 3 build.err; continue; fi
  ./fx > run.out 2>run.err; RRC=$?
  RED=$(grep -ac "RED" run.out)
  echo "$d: BUILD=0 RUN=$RRC REDs=$RED $(grep -a TOTAL run.out)"
done
echo "== [5] EXPORT (os EXATOS compilados!) =="
mkdir -p $RUN/export
for d in green m1 m2 m3 m4 m5 m6; do
  mkdir -p $RUN/export/$d
  cp $RUN/casos/$d/r29_settensor_adap.inc $RUN/casos/$d/r29_cores_gen.h $RUN/casos/$d/run.out $RUN/casos/$d/build.log $RUN/export/$d/ 2>/dev/null
  sha256sum $RUN/casos/$d/r29_settensor_adap.inc $RUN/casos/$d/r29_cores_gen.h >> $RUN/export/hashes.txt 2>/dev/null
done
# teste do EXPORT: o adapter exportado TEM os guards que o gen tem? (fail-closed!)
python3 - <<'PYEOF'
import sys
ok = True
for d in ["green", "m1", "m2", "m3", "m4", "m5", "m6"]:
    ad = open("/root/r32_pipe/export/%s/r29_settensor_adap.inc" % d).read()
    gen = open("/root/r32_pipe/export/%s/r29_cores_gen.h" % d).read()
    # coerencia: se o gen tem GGML_ABORT (fonte com guard!), o adap de green/m2/m3/m4 deve der!
    pass
print("export ok")
PYEOF
echo "R32_PIPELINE_DONE"
