#!/bin/bash
# test-sig-adb.sh — testes OFFLINE (sem device) do wrapper fail-closed.
# Exigidos pelas ordens: serial ausente, errado, outro aparelho, destino
# ambiguo, uninstall/clear de package protegido e hash de deploy divergente
# devem BLOQUEAR antes da acao. Caso feliz deve PASSAAR.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
W="$DIR/sig-adb.sh"; F="$DIR/fake-adb.sh"
PASS=0; FAIL=0
t() { # nome, esperado(code), comando...
  local nome="$1" esp="$2"; shift 2
  "$@" >/tmp/out-test 2>&1; local rc=$?
  if [ "$rc" = "$esp" ]; then PASS=$((PASS+1)); echo "PASS $nome (rc=$rc)"
  else FAIL=$((FAIL+1)); echo "FAIL $nome: rc=$rc esperado=$esp"; sed 's/^/    /' /tmp/out-test | head -n 3; fi
}

echo "== suite do wrapper (offline) =="
# 1) serial ausente (devices vazio)
t "serial-ausente" 2 env ADB="$F" FAKE_DEVICES="" bash "$W" shell "echo oi"
# 2) outro aparelho (somente o PJA110)
t "outro-aparelho-PJA110" 2 env ADB="$F" FAKE_DEVICES=$'1164a04\tdevice' bash "$W" shell "echo oi"
# 3) serial ok, modelo errado
t "modelo-divergente" 2 env ADB="$F" FAKE_DEVICES=$'3B15BD00FVE00000\tdevice' FAKE_MODEL="OutroPhone" bash "$W" shell "echo oi"
# 4) uninstall de package protegido
t "uninstall-protegido" 2 env ADB="$F" bash "$W" uninstall br.gov.sp.pcsp.launcher
# 5) pm clear de package protegido
t "pm-clear-protegido" 2 env ADB="$F" bash "$W" shell "pm clear br.gov.sp.pcsp.launcher"
# 6) push-hash com sha divergente
echo "conteudo-fake" > /tmp/fake-deploy.bin
t "hash-divergente" 2 env ADB="$F" bash "$W" push-hash /tmp/fake-deploy.bin /data/local/tmp/x.bin "$(printf '%064d' 0 | tr '0' 'a')"
# 7) push-hash sem args
t "push-hash-sem-args" 2 env ADB="$F" bash "$W" push-hash
# 8) caso feliz: comando normal passa (rc do adb fake = 0)
t "caso-feliz" 0 env ADB="$F" bash "$W" shell "getprop ro.product.model"
# 9) push-hash feliz (sha casa)
S=$(printf 'a%.0s' {1..64})
t "push-hash-ok" 0 env ADB="$F" FAKE_SHA="$S" bash "$W" push-hash /tmp/fake-deploy.bin /data/local/tmp/x.bin "$S"

echo "== resultado: PASS=$PASS FAIL=$FAIL =="
[ "$FAIL" -eq 0 ]
