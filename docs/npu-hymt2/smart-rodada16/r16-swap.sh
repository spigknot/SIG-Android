#!/bin/bash
# r16-swap: backup v12 libs -> KQ_ON libs -> rebuild -> install -> mmtest
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
J="C:/npu-probe/app/src/main/jniLibs/arm64-v8a"
BK="C:/llama-npu/rodada2/jnilibs-v12-backup"
KQ="C:/llama-npu/rodada2/kqon-libs"
L="C:/llama-npu/rodada2/logs/npuprobe-r16.log"

echo "=== R16 SWAP KQ_ON ===" | tee C:/llama-npu/rodada2/logs/r16-swap.txt
mkdir -p "$BK"
# (1) backup do v12 (só se ainda nao houver backup com sha do v12)
if [ ! -f "$BK/libggml-htp-v81.so" ]; then
  cp $J/libllama.so $J/libggml.so $J/libggml-base.so $J/libggml-cpu.so $J/libggml-hexagon.so $J/libggml-opencl.so $J/libggml-vulkan.so $J/libggml-htp-v81.so "$BK/" 2>/dev/null
  echo "backup v12 feito: $(ls $BK | wc -l) libs" | tee -a C:/llama-npu/rodada2/logs/r16-swap.txt
fi
# (2) swap para KQ_ON
cp $KQ/libllama.so $KQ/libggml.so $KQ/libggml-base.so $KQ/libggml-cpu.so $KQ/libggml-hexagon.so $KQ/libggml-opencl.so $KQ/libggml-vulkan.so $KQ/libggml-htp-v81.so "$J/"
echo "KQ_ON copiado" | tee -a C:/llama-npu/rodada2/logs/r16-swap.txt
sha256sum $J/libggml-htp-v81.so | cut -c1-24 | tee -a C:/llama-npu/rodada2/logs/r16-swap.txt
# (3) rebuild + install
cd C:/npu-probe || exit 1
./gradlew assembleDebug --console=plain > C:/llama-npu/rodada2/logs/probe-r16-kqon-build.log 2>&1
echo "BUILD_RC=$?" | tee -a C:/llama-npu/rodada2/logs/r16-swap.txt
"$A" -s "$S" install -r -d app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -n 1 | tee -a C:/llama-npu/rodada2/logs/r16-swap.txt
# (4) run mmtest
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt; printf 'mmtest:all\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-tune.txt" >/dev/null 2>&1
"$A" -s "$S" shell "am force-stop br.gov.sp.pcsp.npuprobe" >/dev/null 2>&1
sleep 2
L0=$(wc -l < "$L" 2>/dev/null || echo 0)
"$A" -s "$S" shell "am start -n br.gov.sp.pcsp.npuprobe/.MainActivity" >/dev/null 2>&1
sleep 45
echo "=== MM (KQ_ON!) ===" | tee -a C:/llama-npu/rodada2/logs/r16-swap.txt
tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "MM" | sed 's/.*NpuProbe([0-9 ]*): /  /' | head -n 24 | tee -a C:/llama-npu/rodada2/logs/r16-swap.txt
echo R16_SWAP_DONE | tee -a C:/llama-npu/rodada2/logs/r16-swap.txt
