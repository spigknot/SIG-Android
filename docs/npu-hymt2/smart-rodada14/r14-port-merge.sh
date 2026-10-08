#!/bin/bash
# r14-port-merge: constroi o candidato SIG+KQ via git merge-file (3-way)
set -u
cd /root/llama-cpp-npu || exit 1
# 1) estado alvo: parent + patch (upstream com KQ)
git stash -q 2>/dev/null; git checkout -q 1ec818809 2>/dev/null || exit 1
echo "=== estado alvo: 1ec818809 (upstream+KQ) ==="
git log --format='FULLSHA: %H' -1
KQ=/root/kq-target
rm -rf $KQ && mkdir -p $KQ
for f in ggml-hexagon.cpp htp/hmx-mm-kernels-tiled.h htp/htp-ops.h htp/hvx-mm-kernels-flat.h htp/hvx-mm-kernels-tiled.h htp/matmul-ops.c htp/matmul-ops.h; do
  mkdir -p $KQ/$(dirname $f); cp ggml/src/ggml-hexagon/$f $KQ/$f
done
# 2) parent puro (a base do merge)
git checkout -q 1ec818809~1 2>/dev/null || exit 1
P=/root/kq-parent
rm -rf $P && mkdir -p $P
for f in ggml-hexagon.cpp htp/hmx-mm-kernels-tiled.h htp/htp-ops.h htp/hvx-mm-kernels-flat.h htp/hvx-mm-kernels-tiled.h htp/matmul-ops.c htp/matmul-ops.h; do
  mkdir -p $P/$(dirname $f); cp ggml/src/ggml-hexagon/$f $P/$f
done
git checkout -q 5e03bdd 2>/dev/null
echo "parent FULLSHA: $(git rev-parse 1ec818809~1 2>/dev/null)"
# 3) merge-file: SIG (atual) + base (parent) + KQ (target)
S=/root/sig-smart/llama/ggml/src/ggml-hexagon
O=/root/kq-merged
rm -rf $O && mkdir -p $O
TOT=0
for f in ggml-hexagon.cpp htp/hmx-mm-kernels-tiled.h htp/htp-ops.h htp/hvx-mm-kernels-flat.h htp/hvx-mm-kernels-tiled.h htp/matmul-ops.c htp/matmul-ops.h; do
  mkdir -p $O/$(dirname $f)
  cp $S/$f $O/$f
  git merge-file -L sig -L parent -L kq --marker-size=7 $O/$f $P/$f $KQ/$f > /tmp/mf.log 2>&1
  RC=$?
  C=$(grep -c '<<<<<<<' $O/$f 2>/dev/null || echo 0)
  echo "merge $f: RC=$RC conflitos=$C"
  TOT=$((TOT+C))
done
echo "TOTAL_CONFLITOS=$TOT"
echo "R14_MERGE_DONE"
