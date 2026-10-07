#!/bin/bash
# sig-adb.sh — wrapper FAIL-CLOSED para comandos adb na campanha Smart/NPU.
#
# Regras (Fase Zero das ordens):
#  - Serial FIXO: 3B15BD00FVE00000 (CPH2747). Qualquer outro alvo = ABORTA
#    (inclusive PJA110/1164a04, que e' proibido nesta campanha).
#  - Valida a IDENTIDADE do alvo (modelo) ANTES de operar.
#  - Bloqueia operacoes destrutivas em packages protegidos (SIG launcher).
#  - Fail-closed: sem o serial esperado conectado = aborta.
#  - Subcomando push-hash: deploy com readback SHA-256 obrigatorio.
#
# Uso:
#   sig-adb.sh shell "getprop ro.product.model"
#   sig-adb.sh push C:/local /data/local/tmp/dest
#   sig-adb.sh push-hash C:/local /data/local/tmp/dest <sha256-esperado>
#   sig-adb.sh install -r app.apk
#
# ADB pode ser sobrescrito para testes:  ADB=/path/fake-adb.sh sig-adb.sh ...
set -u

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
SERIAL_ESPERADO="${SIG_SERIAL:-3B15BD00FVE00000}"
MODELO_ESPERADO="${SIG_MODELO:-CPH2747}"
PACKAGES_PROTEGIDOS="${SIG_PROTEGIDOS:-br.gov.sp.pcsp.launcher}"

die() { echo "BLOQUEADO: $*" >&2; exit 2; }

[ $# -ge 1 ] || die "uso: sig-adb.sh <comando adb... | push-hash local remote sha256>"

# ---- 0) o serial esperado esta conectado? (fail-closed)
"$ADB" devices 2>/dev/null | awk -v s="$SERIAL_ESPERADO" \
  '$1==s && $2=="device"{ok=1} END{exit (ok?0:1)}' \
  || die "serial $SERIAL_ESPERADO nao esta conectado/autorizado"

# ---- 1) identidade real do alvo
MODELO=$("$ADB" -s "$SERIAL_ESPERADO" shell getprop ro.product.model 2>/dev/null | tr -d '\r' | head -n 1)
[ "$MODELO" = "$MODELO_ESPERADO" ] \
  || die "identidade divergente: modelo='$MODELO' esperado='$MODELO_ESPERADO' (alvo errado?)"

# ---- 2) guarda de operacoes destrutivas em packages protegidos
CMD=" $* "
for PROT in $PACKAGES_PROTEGIDOS; do
  case "$CMD" in
    *" uninstall $PROT "*|*" uninstall $PROT"$' '*) die "uninstall de package protegido: $PROT" ;;
    *" pm clear $PROT"*) die "pm clear de package protegido: $PROT" ;;
    *" clear $PROT"*) die "clear de package protegido: $PROT" ;;
  esac
done

# ---- 3) subcomando push-hash (deploy com readback SHA-256)
if [ "${1:-}" = "push-hash" ]; then
  [ $# -eq 4 ] || die "push-hash exige: <local> <remote> <sha256-esperado>"
  L="$2"; R="$3"; SHA="$4"
  [ -f "$L" ] || die "arquivo local inexistente: $L"
  echo "$SHA" | grep -qiE '^[0-9a-f]{64}$' || die "sha256 esperado invalido"
  "$ADB" -s "$SERIAL_ESPERADO" push "$L" "$R" >/dev/null 2>&1 || die "push falhou"
  GOT=$("$ADB" -s "$SERIAL_ESPERADO" shell "sha256sum '$R' 2>/dev/null" 2>/dev/null | awk '{print $1}' | tr -d '\r')
  [ "$GOT" = "$SHA" ] || die "sha divergente no deploy: obtido=$GOT esperado=$SHA"
  echo "DEPLOY_OK $R sha256=$GOT"
  exit 0
fi

# ---- 4) executa sempre com -s explicito
"$ADB" -s "$SERIAL_ESPERADO" "$@"
