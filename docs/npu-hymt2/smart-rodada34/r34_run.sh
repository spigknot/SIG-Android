#!/bin/bash
# r34_run.sh — R34: os cenarios da seam (MESMO header!) + POISON-CHECK!
set -u
cd /root
rm -rf r34_t && mkdir -p r34_t/{green,m7,m8,poison}
cp flow_seam.h r34_t/green/ ; cp flow_seam.h r34_t/m7/ ; cp flow_seam.h r34_t/m8/
cp r34_flow_test.c r34_t/green/ ; cp r34_flow_test.c r34_t/m7/ ; cp r34_flow_test.c r34_t/m8/
CF="-std=c++17 -O2 -I."
echo "######## green ########"
(cd r34_t/green && g++ $CF r34_flow_test.c -o fx 2>&1 | grep -aE "error" | head -n 3; ./fx 2>/dev/null)
echo "######## m7 (MUTANTE_SEM_IF_BW no HEADER!) ########"
(cd r34_t/m7 && g++ $CF -DMUTANTE_SEM_IF_BW r34_flow_test.c -o fx 2>&1 | grep -aE "error" | head -n 3; ./fx 2>/dev/null | grep -aE "T1|T2|T3|TOTAL")
echo "######## m8 (MUTANTE_BYPASS_OPSTAGE no HEADER!) ########"
(cd r34_t/m8 && g++ $CF -DMUTANTE_BYPASS_OPSTAGE r34_flow_test.c -o fx 2>&1 | grep -aE "error" | head -n 3; ./fx 2>/dev/null | grep -aE "T1|T2|T3|TOTAL")
echo "######## POISON-CHECK (#error no header => build DEVE FALHAR!) ########"
cp flow_seam.h r34_t/poison/flow_seam.h
printf '#error POISON-CHECK: o header compartilhado NAO esta\\\n' | cat - > /tmp/poison_hdr.txt
# injetar o #error no TOPO do header (copia!)
python3 - <<'PYEOF'
h = open("/root/r34_t/poison/flow_seam.h").read()
open("/root/r34_t/poison/flow_seam.h", "w").write("#error POISON_CHECK_HEADER_NAO_COMPILA\n" + h)
PYEOF
cp r34_flow_test.c r34_t/poison/
(cd r34_t/poison && g++ $CF r34_flow_test.c -o fx 2>&1 | grep -acE "POISON_CHECK_HEADER_NAO_COMPILA" && echo "POISON: build FALHOU conforme exigido (o header ESTA sob o build!)" || echo "POISON: FALHA — o build passou (header nao consumido!)")
echo R34_RUN_DONE
