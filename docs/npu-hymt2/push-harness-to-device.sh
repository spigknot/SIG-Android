#!/bin/bash
# push-harness-to-device.sh — envia o pacote do harness NPU ao CPH2747
# Fail-closed: verifica a fonte local, faz push, confere no device e so entao
# considera pronto. Nada do app SIG e tocado (sandbox proprio).
#
# Uso: bash push-harness-to-device.sh <serial>
#   ex.: bash push-harness-to-device.sh 100.108.27.64:41257
set -euo pipefail

SERIAL="${1:?serial explicito (ex.: 100.108.27.64:41257)}"
PKG_LOCAL="C:/llama-npu/pkg-android/llama.cpp"
DEST="/data/local/tmp/sig-hymt2-npu-r1"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"

log()  { echo "[push-npu] $*"; }
fail() { echo "[push-npu] ABORT: $*"; exit 2; }

# 1) fonte local
[ -d "$PKG_LOCAL" ] || fail "pacote ausente: $PKG_LOCAL"
LIBS=$(ls "$PKG_LOCAL/lib"/*.so | wc -l)
[ "$LIBS" -ge 10 ] || fail "pacote suspeito (libs=$LIBS)"

# 2) device + espaco
"$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 || fail "device $SERIAL nao responde"
FREE=$( "$ADB" -s "$SERIAL" shell "df -k /data/local/tmp | tail -n 1 | awk '{print \$4}'" | tr -d '\r' )
[ "${FREE:-0}" -gt 700000 ] || fail "espaco insuficiente em /data/local/tmp (${FREE}KB)"

# 3) diretorio no device (idempotente) — nao mexe em nada existente fora do sandbox
"$ADB" -s "$SERIAL" shell "mkdir -p $DEST/lib $DEST/bin" || fail "mkdir falhou"

# 4) push (bin + lib; sem modelos)
log "push bin/ ($(du -sh "$PKG_LOCAL/bin" | cut -f1))"
"$ADB" -s "$SERIAL" push "$PKG_LOCAL/bin/." "$DEST/bin/" > /dev/null || fail "push bin falhou"
log "push lib/ ($(du -sh "$PKG_LOCAL/lib" | cut -f1))"
"$ADB" -s "$SERIAL" push "$PKG_LOCAL/lib/." "$DEST/lib/" > /dev/null || fail "push lib falhou"

# 5) permissao + verificacao de 2 arquivos-chave no device
"$ADB" -s "$SERIAL" shell "chmod -R 755 $DEST/bin $DEST/lib" || fail "chmod falhou"
for f in bin/llama-cli lib/libggml-hexagon.so lib/libggml-htp-v81.so; do
  LS=$( "$ADB" -s "$SERIAL" shell "ls -la $DEST/$f 2>/dev/null" | tr -d '\r' )
  [ -n "$LS" ] || fail "arquivo ausente no device: $f"
  log "OK $f"
done

log "PUSH OK -> $DEST"
log "proximo: LD_LIBRARY_PATH=$DEST/lib $DEST/bin/llama-cli --list-devices"
