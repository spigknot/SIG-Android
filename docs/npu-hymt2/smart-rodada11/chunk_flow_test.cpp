// R11: fixture do FLUXO de chunks com MOCK decoder (sem modelo).
// Regra: erro em chunk intermediario => ABORTA: nenhum batch/sampling posterior,
// estado final 'rejeitado' (nunca sucesso sem output).
#include "chunk_plan.h"
#include <cstdio>

struct MockDecode { int fail_at = -1; int chamadas = 0; int rc(int i) { chamadas++; return (i == fail_at) ? 1 : 0; } };

struct Resultado { bool ok = false; int chunks_ok = 0; bool abortou = false; const char * motivo = ""; };

// espelho do loop do probe (run_bridge/request_once): for(chunk_plan) { rc=mock; if (rc) break; }
static Resultado roda_fluxo(int nt, int nb, int fail_at) {
    Resultado r;
    MockDecode mock; mock.fail_at = fail_at;
    int i = 0;
    for (auto & cp : chunk_plan(nt, nb)) {
        int rc = mock.rc(i);
        if (rc != 0) { r.abortou = true; r.motivo = "chunk_falhou"; return r; }  // sem sampling posterior
        r.chunks_ok++;
        i++;
    }
    // contrato COMPLETO (espelho do probe): SEM output => rejeitado explicito
    if (r.chunks_ok == 0) { r.motivo = "resposta_vazia"; return r; }   // nunca sucesso sem output
    r.ok = true;
    return r;
}

static int falhas = 0;
static void check(bool c, const char * m) { printf(c ? "ok: %s\n" : "FAIL: %s\n", m); if (!c) falhas++; }

int main() {
    // caso A: 300 tokens (3 chunks), erro no chunk 2 (idx 1) => aborta, 1 chunk ok
    auto a = roda_fluxo(300, 128, 1);
    check(a.abortou && a.chunks_ok == 1 && !a.ok, "erro no chunk 2: ABORTA (1 chunk ok, sem sucesso)");

    // caso B: erro no chunk 1 (idx 0) => aborta sem nenhum chunk ok
    auto b = roda_fluxo(300, 128, 0);
    check(b.abortou && b.chunks_ok == 0 && !b.ok, "erro no chunk 1: ABORTA sem chunks");

    // caso C: sem erro => todos os chunks
    auto cc = roda_fluxo(300, 128, -1);
    check(cc.ok && cc.chunks_ok == 3, "sem erro: 3 chunks ok (sucesso so' com output)");

    // caso D: nt=0 => nenhum chunk, nenhum sampling => rejeitado explicitamente (sem output)
    auto d = roda_fluxo(0, 128, -1);
    check(!d.ok && d.chunks_ok == 0 && !d.abortou, "nt=0: vazio => request rejeitado explicito (sem sucesso sem output)");

    printf(falhas ? "RESULTADO: %d FALHAS\n" : "RESULTADO: TODOS PASS\n", falhas);
    return falhas ? 1 : 0;
}
