#!/bin/bash
# R6/VACINA: verificacao dos invariantes do fix ANR no fonte do llama_jni.cpp.
# Uso: bash check-anr-fix.sh [caminho/llama_jni.cpp]
# Sai 0 se os invariantes estao presentes; 1 com diagnostico se nao.
F="${1:-app/src/main/cpp/llama-jni/llama_jni.cpp}"
[ -f "$F" ] || { echo "FAIL: arquivo $F nao encontrado"; exit 1; }
fail=0
chk() { # nome, esperado, obtido
  if [ "$2" = "$3" ]; then echo "OK   $1: $3"; else echo "FAIL $1: esperado $2, obtido $3"; fail=1; fi
}
chk "locks em g_ui_mutex (set_error+load+3 getters+stats+clear+helper)" 8 "$(grep -c 'lock(g_ui_mutex)' "$F")"
chk "locks em g_mutex (load+release+generate+funcao de teste)" 4 "$(grep -c 'lock(g_mutex)' "$F")"
chk "helper ui_copy_backend_desc presente" 1 "$(grep -c 'static std::string ui_copy_backend_desc()' "$F")"
chk "funcao de teste sigTestHoldGmutex presente" 1 "$(grep -c 'sigTestHoldGmutex' "$F")"
chk "getters com copy-out (lastError/backendDesc/lastStats; threadCount usa atomic)" 3 "$(grep -c 'copy = g_' "$F")"
chk "NewStringUTF dos getters copy-out fora do lock" 3 "$(grep -c 'NewStringUTF(copy.c_str())' "$F")"
chk "threadCount com to_string inline (sem lock)" 1 "$(grep -c 'NewStringUTF(std::to_string(n).c_str())' "$F")"
# o padrao bugado do dlerror duplo nao pode existir em nenhum lugar do repo de fonte
chk "sem padrao dlerror duplo (bug R5)" 0 "$(grep -c 'dlerror() ? dlerror()' "$F" || true)"
[ $fail -eq 0 ] && echo "== CHECK-ANR-FIX: PASS ==" || echo "== CHECK-ANR-FIX: FAIL =="
exit $fail
