#!/bin/bash
# r16-debug: KQ_ON + mmtest:q4k em UMA rodada, capturando tudo do hexagon
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
J="C:/npu-probe/app/src/main/jniLibs/arm64-v8a"
KQ="C:/llama-npu/rodada2/kqon-libs"
OUT="C:/llama-npu/rodada2/logs/r16-debug.txt"

echo "=== R16 DEBUG HANG ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
cp $KQ/*.so "$J/"
cd C:/npu-probe || exit 1
./gradlew assembleDebug --console=plain > /dev/null 2>&1
"$A" -s "$S" install -r -d app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -n 1 | tee -a "$OUT"
# buffer grande + start
"$A" -s "$S" logcat -G 16M >/dev/null 2>&1
"$A" -s "$S" logcat -c >/dev/null 2>&1
"$A" -s "$S" shell "printf 'mmtest:q4k\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
"$A" -s "$S" shell "am force-stop br.gov.sp.pcsp.npuprobe" >/dev/null 2>&1
sleep 2
"$A" -s "$S" shell "am start -n br.gov.sp.pcsp.npuprobe/.MainActivity" >/dev/null 2>&1
echo "--- rodando 100s (hang esperado no compute) ---" | tee -a "$OUT"
sleep 100
echo "--- logcat (NpuProbe + ggml + hexagon + dspcall!) ---" | tee -a "$OUT"
"$A" -s "$S" logcat -d 2>/dev/null | grep -aiE "NpuProbe|ggml|dspcall|adsprpc|fastrpc|HTP0|hexagon" | grep -av "ActivityTaskManager\|AppSense\|Gesture\|VRR\|Oplus" | tail -n 40 | cut -c1-170 | tee -a "$OUT"
echo "--- processo vivo? ---" | tee -a "$OUT"
"$A" -s "$S" shell "pgrep -f npuprobe || echo MORTO" | tr -d '\r' | tee -a "$OUT"
echo "R16_DEBUG_DONE" | tee -a "$OUT"
