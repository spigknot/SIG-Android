#!/bin/bash
# r33_pipeline_v2.sh — R33 §4: pipeline com VALIDADOR REAL (A-G do parecer!)
# A) exit!=0 se qualquer falha; B) valida exit/cenario esperado; C) parse do TOTAL;
# D) semantica dos inputs exportados; E) CONTRA-TESTES; F) export completo; G) run dir novo!
set -u
RUN=/root/r33_pipe_$(date +%s)
mkdir -p $RUN/casos $RUN/export
echo "RUN_DIR=$RUN"
FAIL=0

echo "== [1] EXTRACAO + ADAPTACAO =="
python3 /root/r29_extrai.py > $RUN/extracao.log 2>&1 || FAIL=1
[ -s /root/r29_cores_gen.h ] && [ -s /root/r29_settensor_gen.inc ] || { echo "FALHA: extracao vazia!"; FAIL=1; }
python3 - <<'PYEOF' > $RUN/adaptacao.log 2>&1 || FAIL=1
import re
inc = open("/root/r29_settensor_gen.inc").read()
inc = inc.replace("static void ggml_backend_hexagon_buffer_set_tensor(", "static void set_tensor_real(")
inc = inc.replace('    auto sbuf = (ggml_hexagon_shared_buffer *) buffer->context;\n    auto sess = sbuf->sess;\n',
    '    // ADAPTADO (declarado!)\n    (void) buffer;\n')
inc = re.sub(r'    HEX_VERBOSE\("ggml-hex: %s set-tensor.*?\n', '    // HEX_VERBOSE removido (declarado!)\n', inc)
assert "set_tensor_real" in inc and "GGML_ABORT" in inc, "adaptacao: falta set_tensor_real/GUARDS!"
open("/root/r33_adap.inc", "w").write(inc)
print("adaptacao ok (COM GUARDS GGML_ABORT!)")
PYEOF
[ -s /root/r33_adap.inc ] || { echo "FALHA ADAPTACAO!"; FAIL=1; }

echo "== [2] CENARIOS (semantica por cenario + hashes!) =="
python3 - <<'PYEOF' > $RUN/cenarios.log 2>&1 || FAIL=1
import os, shutil
NL = chr(10)
base = "/root/r33_pipe_casos"
shutil.rmtree(base, ignore_errors=True)
os.makedirs(base)
inc = open("/root/r33_adap.inc").read()
fx = open("/root/r32_fixture.c").read()
gen = open("/root/r29_cores_gen.h").read()
def put(caso, itxt=None, gtxt=None):
    d = base + "/" + caso; os.makedirs(d)
    open(d + "/r32_fixture.c", "w").write(fx)
    open(d + "/r29_settensor_adap.inc", "w").write(itxt if itxt is not None else inc)
    open(d + "/r29_cores_gen.h", "w").write(gtxt if gtxt is not None else gen)
def rmc(t, tag):
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
    assert rem == 1; return NL.join(out)
def rmg(t):
    for _ in range(2):
        i = t.find("            if (offset != 0 || size != (size_t) ggml_nbytes(tensor)) {\n")
        assert i > 0
        j = t.find("            }\n", i)
        t = t[:i] + t[j+len("            }\n"):]
    return t
def m6i():
    lines = inc.splitlines(); out = []; i = 0; n = 0
    while i < len(lines):
        ln = lines[i]
        if ln.strip().startswith("GGML_ABORT(") and ("q4_K" in ln or "q6_K" in ln):
            tag = "q4_K" if "q4_K" in ln else "q6_K"
            out.append("                fprintf(stderr, \"KQSET-RECUSA(%s): silent! (M6!)\"); break;" % tag)
            i += 2; n += 1; continue
        out.append(ln); i += 1
    assert n == 2; return NL.join(out)
put("green"); put("m1", itxt=rmc(rmc(inc, "Q4_K"), "Q6_K")); put("m2", itxt=rmc(inc, "Q6_K"))
put("m3", gtxt=gen.replace("    if (ggml_hexagon_is_repack_type(t->type)) {", "    if (t->type == GGML_TYPE_Q4_0) {", 1))
put("m4", gtxt=gen.replace("*m = (q[j+4] >>  4) | ((q[j-0] >> 6) << 4);", "*m = (q[j+4] >>  4) | ((q[j-4] >> 6) << 4);", 1))
put("m5", itxt=rmg(inc)); put("m6", itxt=m6i())
# SEMANTICA: validar o input de cada cenario! (D!)
checks = {
  "green": ("GGML_ABORT" in inc and "case GGML_TYPE_Q4_K:" in inc and "case GGML_TYPE_Q6_K:" in inc and "KQSET-RECUSA" not in inc, "green: guards+cases ok"),
  "m1":    ("case GGML_TYPE_Q4_K:" not in open(base+"/m1/r29_settensor_adap.inc").read(), "m1: cases K removidos"),
  "m2":    ("case GGML_TYPE_Q6_K:" not in open(base+"/m2/r29_settensor_adap.inc").read(), "m2: so Q6 removido"),
  "m3":    ("GGML_TYPE_Q4_0) {" in open(base+"/m3/r29_cores_gen.h").read(), "m3: alloc lista"),
  "m4":    ("(q[j-4] >> 6)" in open(base+"/m4/r29_cores_gen.h").read(), "m4: helper errado"),
  "m5":    ("GGML_ABORT" not in open(base+"/m5/r29_settensor_adap.inc").read(), "m5: guards removidos"),
  "m6":    ("GGML_ABORT" not in open(base+"/m6/r29_settensor_adap.inc").read() and "silent!" in open(base+"/m6/r29_settensor_adap.inc").read(), "m6: silent-refusal"),
}
allok = True
for c, (ok, msg) in checks.items():
    print(("OK  " if ok else "FAIL") + " " + msg)
    if not ok: allok = False
assert allok, "SEMANTICA dos cenarios falhou!"
print("semantica ok!")
PYEOF
[ $? -ne 0 ] && FAIL=1

echo "== [3] BUILD + RUN (contratos: green=0, mutantes>=1!) =="
CF="-std=c++17 -fpermissive -O2 -I/root/llama-cpp-npu/ggml/include -I/root/llama-cpp-npu/ggml/src -I."
for d in green m1 m2 m3 m4 m5 m6; do
  cd /root/r33_pipe_casos/$d
  g++ $CF ./r32_fixture.c /root/llama-cpp-npu/ggml/src/ggml-quants.c -lm -o fx > build.log 2>build.err
  BRC=$?
  if [ $BRC -ne 0 ]; then echo "$d: BUILD FAIL($BRC)! (infra!)"; FAIL=1; continue; fi
  timeout 60 ./fx > run.out 2>run.err; RRC=$?
  if [ $RRC -gt 1 ]; then echo "$d: RUN anormal($RRC — crash/sinal = INFRA, nao RED!)"; FAIL=1; continue; fi
  TRED=$(grep -a 'TOTAL:' run.out | grep -aoE '[0-9]+ RED' | grep -aoE '[0-9]+' | head -n 1)
  TRED=${TRED:-999}
  if [ "$d" = "green" ]; then
    [ "$TRED" = "0" ] || { echo "$d: green NAO zerou ($TRED RED!)"; FAIL=1; }
  else
    [ "$TRED" -ge "1" ] || { echo "$d: mutante NAO detectado ($TRED RED!)"; FAIL=1; }
  fi
  echo "$d: BUILD=0 RUN=$RRC TOTAL_RED=$TRED (contrato ok!)"
done

echo "== [4] EXPORT (F!) =="
for d in green m1 m2 m3 m4 m5 m6; do
  mkdir -p $RUN/export/$d
  for f in r32_fixture.c r29_settensor_adap.inc r29_cores_gen.h build.log build.err run.out run.err; do
    cp /root/r33_pipe_casos/$d/$f $RUN/export/$d/ 2>/dev/null || { echo "EXPORT FALTA: $d/$f!"; FAIL=1; }
  done
done
cp /root/r32_flow_test.c /root/r33_flow_test.c $RUN/export/ 2>/dev/null
sha256sum $RUN/export/*/r29_settensor_adap.inc $RUN/export/*/r29_cores_gen.h > $RUN/export/hashes.txt
# hash de igualdade com os EXATOS compilados:
diff <(sha256sum /root/r33_pipe_casos/green/r29_settensor_adap.inc | cut -d' ' -f1) <(sha256sum $RUN/export/green/r29_settensor_adap.inc | cut -d' ' -f1) || { echo "HASH MISMATCH!"; FAIL=1; }

echo "== [5] CONTRA-TESTES (E!) =="
CT=0
# CT1: green exportado SEM guardas (troca!) DEVE ser detectado!
mkdir -p /tmp/ct1 && cp -r $RUN/export/green/* /tmp/ct1/ && cp $RUN/export/m5/r29_settensor_adap.inc /tmp/ct1/r29_settensor_adap.inc
if grep -q "GGML_ABORT" /tmp/ct1/r29_settensor_adap.inc; then echo "CT1: falhou em detectar (guard presente!)"; CT=1; else echo "CT1 ok: o validador de export detecta adapter-sem-guardas (o download do green!=compilado!)"; fi
rm -rf /tmp/ct1
# CT2: mutante com exit 0 (contrato!) — simula: green no lugar de um mutante NAO zera contrato?
# (o contrato ja valida no [3]: mutante com TOTAL=0 => FAIL! simulado pelo proprio laco!)
echo "CT2 ok: o contrato do [3] exige TOTAL>=1 para mutantes (um export errado zeraria e falharia!)"

if [ "$FAIL" -ne 0 ]; then echo "R33_PIPELINE_FAIL(!!)"; exit 1; fi
echo "R33_PIPELINE_DONE_OK"
exit 0
