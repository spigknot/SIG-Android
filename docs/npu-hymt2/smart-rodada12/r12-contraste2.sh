#!/bin/bash
# r12-contraste2: v11 (upstream) — supports explicito + sched dump; depois restore
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r12.log"
OUT="C:/llama-npu/rodada2/logs/r12-contraste2.txt"
PKG="br.gov.sp.pcsp.npuprobe"
J="C:/npu-probe/app/src/main/cpp/../jniLibs/arm64-v8a"
J2="C:/npu-probe/app/src/main/jniLibs/arm64-v8a"
BK="C:/llama-npu/rodada2/r9-backup-v12"
B="/root/llama-cpp-npu/build-smart"

echo "=== R12 CONTRASTE2 v11 ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
scp -q root@servidor:$B/bin/libllama.so root@servidor:$B/bin/libggml.so root@servidor:$B/bin/libggml-base.so root@servidor:$B/bin/libggml-cpu.so root@servidor:$B/bin/libggml-hexagon.so root@servidor:$B/bin/libggml-opencl.so root@servidor:$B/bin/libggml-vulkan.so "$J2/"
scp -q root@servidor:$B/ggml/src/ggml-hexagon/libggml-htp-v81.so "$J2/"
cd C:/npu-probe && ./gradlew assembleDebug --console=plain > C:/llama-npu/rodada2/logs/probe-ctrl3-build.log 2>&1
echo "BUILD_RC=$? (se !=0: v11-link falhou; ver log)" | tee -a "$OUT"
if [ -f app/build/outputs/apk/debug/app-debug.apk ]; then
  "$A" -s "$S" install -r -d app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -n 1 | tee -a "$OUT"
fi

# run 1: supports no v11
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt; echo smart:supports > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
"$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
sleep 2
"$A" -s "$S" shell "am start -n $PKG/.MainActivity >/dev/null 2>&1"
sleep 30
echo "--- supports no v11 ---" | tee -a "$OUT"
grep -a "SUPPORTS HTP0 m=Q4_K\\|SUPPORTS HTP0 m=Q6_K\\|SUPPORTS HTP0 m=Q4_0" "$L" | tail -n 6 | sed 's/.*NpuProbe([0-9 ]*): /  /' | tee -a "$OUT"

# run 2: sched-dump no v11 (htp puro)
"$A" -s "$S" shell "printf 'GGML_SCHED_DEBUG=1\n' > /data/local/tmp/p4-tune.txt; printf 'puro:htp\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
"$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
sleep 2
L0=$(wc -l < "$L" 2>/dev/null || echo 0)
"$A" -s "$S" shell "am start -n $PKG/.MainActivity >/dev/null 2>&1"
sleep 50
echo "--- splits no v11 (controle; esperado: menos/zero splits CPU nas matmuls) ---" | tee -a "$OUT"
tail -n +$((L0+1)) "$L" 2>/dev/null | grep -a "## SPLIT" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 20 | tee -a "$OUT"
tail -n +$((L0+1)) "$L" 2>/dev/null | grep -a "puro:htp puro: ok" | sed 's/.*prefill=/    prefill=/' | head -n 2 | tee -a "$OUT"

echo "--- RESTORE v12 ---" | tee -a "$OUT"
for f in libllama.so libggml.so libggml-base.so libggml-cpu.so libggml-hexagon.so libggml-htp-v81.so libggml-opencl.so libggml-vulkan.so; do
  cp "$BK/$f" "$J2/$f"
done
cd C:/npu-probe && ./gradlew assembleDebug --console=plain > C:/llama-npu/rodada2/logs/probe-ctrl3-restore.log 2>&1
echo "RESTORE_RC=$?" | tee -a "$OUT"
"$A" -s "$S" install -r -d app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -n 1 | tee -a "$OUT"
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt /data/local/tmp/p4-session.txt /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
echo "R12_CONTRASTE2_DONE" | tee -a "$OUT"
