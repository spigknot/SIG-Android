#!/bin/bash
# r14-port-delta: extrai o DELTA KQ (parent->target) e aplica no SIG com fuzzy
set -u
cd /root/llama-cpp-npu || exit 1
# 1) o delta real do commit (818+/61-) = o pacote KQ puro
git diff 82324fc508006de234552e701f4509c72c4fdd8d 1ec81880944a63bc4aaf1abfe9a6d35c7569a757 -- \
  ggml/src/ggml-hexagon/ > /root/kq-delta.patch
wc -l /root/kq-delta.patch
# 2) aplicar no SIG com fuzzy (patch -p1 --fuzz=3 --no-backup-if-mismatch)
cp -r /root/sig-smart/llama/ggml/src/ggml-hexagon /root/kq-sig-work 2>/dev/null
cd /root/kq-sig-work || exit 1
# o patch tem paths ggml/src/ggml-hexagon/... => strip 3
patch -p3 --fuzz=3 --no-backup-if-mismatch --merge < /root/kq-delta.patch > /root/kq-apply.log 2>&1
echo "PATCH_RC=$?"
grep -cE "^patching|Hunk" /root/kq-apply.log || true
echo "=== .rej / merge files ==="
find . -name "*.rej" -o -name "*.orig" | head -n 20
echo "=== resumo do patch ==="
grep -E "FAILED|Reversed|fuzz|succeeded" /root/kq-apply.log | head -n 25
echo R14_DELTA_DONE
