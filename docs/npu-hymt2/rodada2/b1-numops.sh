#!/bin/bash
# b1-numops.sh — Bloco B.1: numericos adicionais no HTP0 (ops do HyMT2)
# Roda em serie (1 device). Logs completos no device; tail no runner.
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2

run() { # nome timeout shell
  echo "== $1 =="
  python runner2.py --name "$1" --timeout "$2" --need-model --tag "B.1 numops" \
    --shell "$3" 2>&1 | tail -n 6
}

# 1) RMS_NORM + SOFT_MAX (265 casos estimados)
run b1_rmsnorm_softmax 1200 "./bin/test-backend-ops -b HTP0 -o 'RMS_NORM,SOFT_MAX' test > b1r.log 2>&1; echo RC=\$?; tail -n 3 b1r.log"

# 2) ROPE (470 casos)
run b1_rope 1500 "./bin/test-backend-ops -b HTP0 -o 'ROPE' test > b1rope.log 2>&1; echo RC=\$?; tail -n 3 b1rope.log"

# 3) SET_ROWS/GET_ROWS/ADD/MUL (filtro tipos do modelo: f32/f16/q4_0)
run b1_rows_addmul 1500 "./bin/test-backend-ops -b HTP0 -o 'SET_ROWS,GET_ROWS,ADD,MUL' -p 'type=f32|type=f16|type_a=q4_0' test > b1s.log 2>&1; echo RC=\$?; tail -n 3 b1s.log"

# 4) GET_ROWS q6_K (confirmar unsupported do embd) — expect: unsupported/skip
run b1_getrows_q6k 600 "./bin/test-backend-ops -b HTP0 -o 'GET_ROWS' -p 'type=q6_K' test > b1q6.log 2>&1; echo RC=\$?; tail -n 5 b1q6.log"

echo B1_DONE
