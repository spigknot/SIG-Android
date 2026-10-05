#!/bin/sh
# safe_deploy_test.sh — dry-run/mock tests do safe_deploy.sh (SEM device).
# Cria um 'adb' falso (mock) que simula: fonte ok, push falho, push truncado,
# sha divergente, sucesso — e AFERE que o safe_deploy:
#   (a) nunca roda 'cp' no destino quando a verificacao falha (fail-closed);
#   (b) segue o caminho completo no sucesso;
#   (c) propaga rc != 0 nas falhas.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then HERE="$(cygpath -m "$HERE")"; fi
BASE="${TMPDIR:-/tmp}/safe_deploy_test"
rm -rf "$BASE"; mkdir -p "$BASE/mockbin" "$BASE/dev" "$BASE/art"
FAILS=0

# --- artefato de teste (1.5 MB) ---
python - <<PY
open(r"$BASE/art/good.so","wb").write(b"AB"*786432 + b"CD")
PY

# --- mock adb ---
cat > "$BASE/mockbin/adb" <<'MOCK'
#!/bin/sh
# mock adb: MOCK_BASE, MOCK_FAIL (none|push|truncate|shamismatch)
B="${MOCK_BASE:?}"; MODE="${MOCK_FAIL:-none}"
echo "adb $*" >> "$B/cmds.log"
args="$*"
case "$args" in
  *" push "*)
     if [ "$MODE" = "push" ]; then echo "adb: error: failed to copy (mock)"; exit 1; fi
     src=$(echo "$args" | awk '{for(i=1;i<=NF;i++) if($i=="push") print $(i+1)}')
     dst=$(echo "$args" | awk '{for(i=1;i<=NF;i++) if($i=="push") print $(i+2)}')
     name=$(basename "$dst")
     mkdir -p "$B/dev/data/local/tmp"
     if [ "$MODE" = "truncate" ]; then : > "$B/dev/data/local/tmp/$name"; else cp "$src" "$B/dev/data/local/tmp/$name"; fi
     echo "1 file pushed"; exit 0 ;;
  *"shell"*)
     cmd=$(echo "$args" | sed 's/.*shell //; s/^"//; s/"$//')
     case "$cmd" in
       *"stat -c %s /data/local/tmp/"*) f=$(echo "$cmd" | grep -o '/data/local/tmp/[^ ]*' | head -n1); stat -c %s "$B/dev$f" 2>/dev/null || echo 0 ;;
       *"sha256sum /data/local/tmp/"*) f=$(echo "$cmd" | grep -o '/data/local/tmp/[^ ]*' | head -n1); sha256sum "$B/dev$f" 2>/dev/null | cut -d ' ' -f1 ;;
       *"run-as"*" cp "*)
          s=$(echo "$cmd" | awk '{for(i=1;i<=NF;i++) if($i=="cp") print $(i+1)}')
          d=$(echo "$cmd" | awk '{for(i=1;i<=NF;i++) if($i=="cp") print $(i+2)}')
          mkdir -p "$B/dev/$(dirname "$d")"
          cp "$B/dev$s" "$B/dev/$d" ;;
       *"run-as"*"stat -c %s"*) d=$(echo "$cmd" | grep -o 'no_backup[^ ]*'); stat -c %s "$B/dev/$d" 2>/dev/null || echo 0 ;;
       *"run-as"*"sha256sum"*) d=$(echo "$cmd" | grep -o 'no_backup[^ ]*'); if [ "${MODE}" = "shamismatch" ]; then echo "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff  $d"; else sha256sum "$B/dev/$d" 2>/dev/null | cut -d ' ' -f1; fi ;;
       *"chmod"*) : ;;
       *) : ;;
     esac
     exit 0 ;;
esac
exit 0
MOCK
chmod +x "$BASE/mockbin/adb"

export MOCK_BASE="$BASE"
runsafe() {
    MODE="$1"; SRC="$2"
    : > "$BASE/cmds.log"
    MOCK_FAIL="$MODE" ADB="$BASE/mockbin/adb" PATH="$BASE/mockbin:$PATH" \
        sh "$HERE/safe_deploy.sh" "$SRC" FAKESERIAL "no_backup/fake/lib.so" >"$BASE/out.log" 2>&1
    return $?
}
cp_ran() { grep -q "run-as.*cp /data/local/tmp" "$BASE/cmds.log"; }

echo "== 1) fonte ausente: deve ABORTAR sem push =="
if runsafe none "$BASE/art/nao_existe.so"; then echo "  FAIL: aceitou fonte ausente"; FAILS=$((FAILS+1)); else
    grep -q "push" "$BASE/cmds.log" && { echo "  FAIL: fez push com fonte ausente"; FAILS=$((FAILS+1)); } || echo "  OK (abortou antes do push)"
fi

echo "== 2) push falho: deve ABORTAR sem cp no destino =="
if runsafe push "$BASE/art/good.so"; then echo "  FAIL: aceitou push falho"; FAILS=$((FAILS+1)); else
    cp_ran && { echo "  FAIL: rodou cp apos push falho"; FAILS=$((FAILS+1)); } || echo "  OK (sem cp; fail-closed)"
fi

echo "== 3) push truncado (EOF): deve detectar size divergente e NAO copiar =="
if runsafe truncate "$BASE/art/good.so"; then echo "  FAIL: aceitou tmp truncado"; FAILS=$((FAILS+1)); else
    cp_ran && { echo "  FAIL: rodou cp com tmp truncado (regressao do incidente F13)"; FAILS=$((FAILS+1)); } || echo "  OK (sem cp; vacina ativa)"
fi

echo "== 4) sha do destino divergente apos cp: deve falhar no readback =="
if runsafe shamismatch "$BASE/art/good.so"; then echo "  FAIL: aceitou sha divergente no destino"; FAILS=$((FAILS+1)); else
    echo "  OK (readback detectou divergencia)"
fi

echo "== 5) sucesso: caminho completo =="
if runsafe none "$BASE/art/good.so"; then
    cp_ran || { echo "  FAIL: nao copiou no sucesso"; FAILS=$((FAILS+1)); }
    echo "  OK (deploy completo verificado)"
else
    echo "  FAIL: abortou no caso de sucesso"; FAILS=$((FAILS+1))
fi

echo "== 6) path com BACKSLASH (subprocess/PowerShell): deve NORMALIZAR e passar =="
BADPATH=$(printf '%s' "$BASE/art/good.so" | sed 's|/|\\|g')
if runsafe none "$BADPATH"; then
    echo "  OK (path normalizado; regressao do bug de deploy corrigida)"
else
    echo "  FAIL: safe_deploy nao normalizou o path com backslash"; FAILS=$((FAILS+1))
fi

echo
if [ "$FAILS" -eq 0 ]; then echo "RESULTADO: PASS (6/6 casos; fail-closed comprovado)"; exit 0
else echo "RESULTADO: FAIL ($FAILS casos)"; exit 1; fi
