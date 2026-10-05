#!/bin/sh
# safe_deploy.sh — deploy fail-closed de .so para o device (vacina do incidente F13).
# Falha CEDO e SEM tocar no destino quando: fonte ausente/pequena/sha errado,
# push falho, ou verificacao do tmp no device divergente. Nunca mascara rc.
# Uso: safe_deploy.sh <artefato_local> <serial> <caminho_relativo_destino>
#   ex.: safe_deploy.sh lib.so 100.108.27.64:36001 \
#        no_backup/native_dependencies/9-arm64-v8a/lib/libsig_llama.so
set -eu
ART="${1:?artefato}"; SERIAL="${2:?serial}"; DEST="${3:?destino (relativo a run-as)}"
# F19: normaliza path nativo com backslash (chamadas via subprocess/PowerShell quebravam
# o sha local => 'tmp sha divergente' falso). Idempotente; sem efeito em paths ja' limpos.
ART=$(printf '%s' "$ART" | tr '\' '/')
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
PKG="${PKG:-br.gov.sp.pcsp.launcher}"
TMPNAME="$(basename "$ART").stage"

log() { echo "[safe_deploy] $*"; }
fail() { echo "[safe_deploy] ABORT: $*"; exit 2; }

# 1) fonte local: existe, tamanho plausivel, sha completo
[ -f "$ART" ] || fail "fonte ausente: $ART"
LOCAL_SZ=$(stat -c %s "$ART")
[ "$LOCAL_SZ" -ge 1000000 ] || fail "fonte suspeita (${LOCAL_SZ}B)"
LOCAL_SH=$(sha256sum "$ART" | cut -d ' ' -f1)
log "fonte: ${LOCAL_SZ}B sha=${LOCAL_SH}"

# 2) push SEM pipe mascarante; rc preservado
"$ADB" -s "$SERIAL" push "$ART" "/data/local/tmp/$TMPNAME" || fail "push falhou"
log "push ok"

# 3) verificacao do tmp NO DEVICE antes de qualquer cp.
# Retry LIMITADO apenas da LEITURA pos-push (flakiness transitoria observada);
# NUNCA reaproveita fonte/rc ruins: push rc!=0 ou fonte errada ja abortaram antes.
verify_tmp() {
    a=1
    while [ $a -le 3 ]; do
        DEV_SZ=$("$ADB" -s "$SERIAL" shell "stat -c %s /data/local/tmp/$TMPNAME" 2>/dev/null | tr -d '\r')
        DEV_SH=$("$ADB" -s "$SERIAL" shell "sha256sum /data/local/tmp/$TMPNAME" 2>/dev/null | tr -d '\r' | cut -d ' ' -f1)
        if [ "$DEV_SZ" = "$LOCAL_SZ" ] && [ "$DEV_SH" = "$LOCAL_SH" ]; then
            log "tmp verificado (tentativa $a): ${DEV_SZ}B sha ok"
            return 0
        fi
        log "tentativa $a: tmp divergente — releitura"
        sleep 2; a=$((a+1))
    done
    return 1
}
verify_tmp || fail "tmp divergente apos 3 releituras — fail-closed, destino intocado"

# 4) cp + chmod + readback exato
"$ADB" -s "$SERIAL" shell "run-as $PKG cp /data/local/tmp/$TMPNAME $DEST && run-as $PKG chmod 600 $DEST" >/dev/null
D2_SZ=$("$ADB" -s "$SERIAL" shell "run-as $PKG stat -c %s $DEST" | tr -d '\r')
D2_SH=$("$ADB" -s "$SERIAL" shell "run-as $PKG sha256sum $DEST" | tr -d '\r' | cut -d ' ' -f1)
[ "$D2_SZ" = "$LOCAL_SZ" ] || fail "destino size divergente apos cp (${D2_SZ}B)"
[ "$D2_SH" = "$LOCAL_SH" ] || fail "destino sha divergente apos cp"
log "DEPLOY OK: destino = ${D2_SZ}B sha=${D2_SH}"
exit 0
