#!/bin/bash
# e2e-anr-test.sh — Teste E2E do fix do ANR (GREEN) no device.
# Cenário do ANR original: o usuário TOCA na tela durante a inferência
# (Input dispatching timed out -> Force finishing no v1.508).
# Com o fix (g_ui_mutex + cache atomico), a main thread não deve bloquear:
# esperado = app continua vivo, sem ANR, tradução completa.
#
# Pré-requisitos: libsig_llama.so NOVO em $NEWSO; device via USB.
set -u
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="${SERIAL:-3B15BD00FVE00000}"
NEWSO="${NEWSO:-D:/Projetos/SIG/native-dependencies/build/llama/arm64-v8a/libsig_llama.so}"
LIBDIR="/data/data/br.gov.sp.pcsp.launcher/no_backup/native_dependencies/11-arm64-v8a/lib"
BACKUP="/data/local/tmp/libsig_llama_v11_orig.so"
LOG="C:/llama-npu/rodada2/logs/e2e-anr.log"

echo "=== E2E ANR test $(date) ===" | tee "$LOG"

# 0) sha local da lib nova
sha_new=$(sha256sum "$NEWSO" | cut -d' ' -f1)
echo "sha nova: $sha_new" | tee -a "$LOG"

# 1) parar o app + backup da lib original
"$A" -s "$S" shell "am force-stop br.gov.sp.pcsp.launcher" 2>&1 | tr -d '\r' | tee -a "$LOG"
"$A" -s "$S" shell "cp '$LIBDIR/libsig_llama.so' '$BACKUP' && sha256sum '$BACKUP' && ls -Z '$LIBDIR/libsig_llama.so'" 2>&1 | tr -d '\r' | tee -a "$LOG"

# 2) push da lib nova + chmod + contexto SELinux do original
"$A" -s "$S" push "$NEWSO" "$LIBDIR/libsig_llama.so.new" 2>&1 | tr -d '\r' | tail -n 1 | tee -a "$LOG"
"$A" -s "$S" shell "sha256sum '$LIBDIR/libsig_llama.so.new'" 2>&1 | tr -d '\r' | tee -a "$LOG"
"$A" -s "$S" shell "CTX=\$(ls -Z '$LIBDIR/libsig_llama.so' | awk '{print \$1}'); mv '$LIBDIR/libsig_llama.so.new' '$LIBDIR/libsig_llama.so'; chmod 755 '$LIBDIR/libsig_llama.so'; chcon \$CTX '$LIBDIR/libsig_llama.so' 2>/dev/null; ls -laZ '$LIBDIR/libsig_llama.so'" 2>&1 | tr -d '\r' | tee -a "$LOG"

# 3) abrir a TextoActivity com o texto (extra_texto) e tocar em "Traduzir"
TXT="On Tuesday morning, officers responded to a call regarding a traffic accident near the intersection of Main Street and Oak Avenue."
echo "--- abrir TextoActivity com extra_texto ---" | tee -a "$LOG"
"$A" -s "$S" shell "am start -n br.gov.sp.pcsp.launcher/.TextoActivity --es extra_texto '\''$TXT'\''" 2>&1 | tr -d '\r' | tee -a "$LOG"
sleep 4

# achar o botao Traduzir (uiautomator dump)
"$A" -s "$S" shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" 2>/dev/null | tr -d '\r' | grep -oE 'text="Traduzir"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -n 2 | tee -a "$LOG"
BOUNDS=$("$A" -s "$S" shell "cat /sdcard/ui.xml" 2>/dev/null | tr -d '\r' | grep -oE 'text="Traduzir"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -n 1)
echo "bounds do botao: $BOUNDS" | tee -a "$LOG"

# calcular centro e tocar
CX=$(echo "$BOUNDS" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \3/' | awk '{print int(($1+$2)/2)}')
CY=$(echo "$BOUNDS" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2 \4/' | awk '{print int(($1+$2)/2)}')
echo "tap Traduzir em ($CX,$CY)" | tee -a "$LOG"
"$A" -s "$S" shell "input tap $CX $CY" >/dev/null 2>&1

# 4) taps periodicos durante ~25s (reproduz o Input dispatching do ANR)
echo "--- taps durante a inferencia (cenario do ANR) ---" | tee -a "$LOG"
for i in 1 2 3 4 5 6 7 8 9 10; do
  "$A" -s "$S" shell "input tap 540 1700" >/dev/null 2>&1
  sleep 2
done

# 5) verificar sobrevivencia: pid, ANR no logcat, geracao
echo "--- verificacao ---" | tee -a "$LOG"
"$A" -s "$S" shell "pidof br.gov.sp.pcsp.launcher; dumpsys activity processes | grep -A2 br.gov.sp.pcsp.launcher | head -n 4" 2>&1 | tr -d '\r' | tee -a "$LOG"
"$A" -s "$S" logcat -d -t 400 2>/dev/null | grep -iE "ANR in br.gov.sp|Input dispatching|Force finishing|geracao concluida" | tail -n 8 | tee -a "$LOG"

echo "=== E2E_DONE ===" | tee -a "$LOG"
