#!/bin/bash
# r9-diag-deploy: cohort diag (logs efetivos) -> htp solo x4 -> coleta -> restore v12
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r9.log"
OUT="C:/llama-npu/rodada2/logs/r9-diag.txt"
PKG="br.gov.sp.pcsp.npuprobe"
J="C:/npu-probe/app/src/main/jniLibs/arm64-v8a"
BK="C:/llama-npu/rodada2/r9-backup-v12"
B="/root/sig-smart/llama/build-diag"
mkdir -p "$BK"

echo "=== R9 DIAG DEPLOY ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
echo "--- backup v12 ---" | tee -a "$OUT"
for f in libllama.so libggml.so libggml-base.so libggml-cpu.so libggml-hexagon.so libggml-htp-v81.so libggml-opencl.so libggml-vulkan.so; do
  cp "$J/$f" "$BK/$f"
done
sha256sum "$J"/lib*.so | cut -c1-16,66- | tee -a "$OUT"

echo "--- deploy diag (scp) ---" | tee -a "$OUT"
scp -q root@servidor:$B/bin/libllama.so root@servidor:$B/bin/libggml.so root@servidor:$B/bin/libggml-base.so root@servidor:$B/bin/libggml-cpu.so root@servidor:$B/bin/libggml-hexagon.so root@servidor:$B/bin/libggml-opencl.so root@servidor:$B/bin/libggml-vulkan.so "$J/"
scp -q root@servidor:$B/ggml/src/ggml-hexagon/libggml-htp-v81.so "$J/"
sha256sum "$J/libggml-hexagon.so" | cut -c1-16,66- | tee -a "$OUT"

cd C:/npu-probe && ./gradlew assembleDebug --console=plain > C:/llama-npu/rodada2/logs/probe-diag-build.log 2>&1
echo "BUILD_RC=$?" | tee -a "$OUT"
"$A" -s "$S" install -r -d app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -n 1 | tee -a "$OUT"

echo "--- run htp solo x4 (cohort diag) ---" | tee -a "$OUT"
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-tune.txt; printf 'puro:htp\\npuro:htp\\npuro:htp\\npuro:htp\\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
"$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
sleep 2
L0=$(wc -l < "$L" 2>/dev/null || echo 0)
"$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
sleep 75
tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "HEXINIT_DIAG|IFACE_START_DIAG|SESS\\[.*htp.*prefill" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 15 | tee -a "$OUT"
"$A" -s "$S" exec-out run-as $PKG cat files/r6-sess.jsonl > C:/llama-npu/rodada2/logs/r9-diag-htp.jsonl 2>/dev/null
echo "    JSON: $(wc -c < C:/llama-npu/rodada2/logs/r9-diag-htp.jsonl 2>/dev/null) bytes" | tee -a "$OUT"

echo "--- RESTORE v12 ---" | tee -a "$OUT"
for f in libllama.so libggml.so libggml-base.so libggml-cpu.so libggml-hexagon.so libggml-htp-v81.so libggml-opencl.so libggml-vulkan.so; do
  cp "$BK/$f" "$J/$f"
done
cd C:/npu-probe && ./gradlew assembleDebug --console=plain > C:/llama-npu/rodada2/logs/probe-diag-restore.log 2>&1
echo "RESTORE_BUILD_RC=$?" | tee -a "$OUT"
"$A" -s "$S" install -r -d app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -n 1 | tee -a "$OUT"
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-session.txt /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
echo "R9_DIAG_DONE" | tee -a "$OUT"
