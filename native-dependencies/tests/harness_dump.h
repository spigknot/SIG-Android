// DUMP DE LOGITS — rodada 12, §4.5 do parecer (29/09 21:44).
//
// Funcao EXTRAIDA do harness para ser testavel com dados sinteticos (sem
// modelo, sem aparelho). Contrato novo: DEVOLVE bool. Logar erro nao basta —
// quem chama DEVE propagar ate MEDICAO INVALIDA e retorno nao zero.
//
// Casos cobertos pelo teste (sintetico, rotulado):
//   - prefixo em diretorio inexistente => false (fopen falha)
//   - n_vocab <= 0 ou logits nulos     => false (entrada invalida)
//   - caminho truncado (snprintf)      => false
//   - escrita incompleta (fwrite)      => false E arquivo vaquereado com
//     sufixo .INCOMPLETO (marcado, nao apagado — §4.5: sem apagar controles)
//   - sucesso                          => true, com tamanho conferido
#ifndef HARNESS_DUMP_H
#define HARNESS_DUMP_H

#include <cstdio>
#include <cstring>

// Prefixo "dir_inexistente_..." nao existe => fopen falha => false.
// Nao usa `linha()` (log do harness): quem chama ja tem o canal de log.
inline bool gravar_dump_log(const char* prefixo, const char* passo,
                            const float* logits, int n_vocab,
                            char* err, size_t err_sz) {
    if (err_sz) err[0] = '\0';
    if (prefixo == nullptr || prefixo[0] == '\0') return true;  // sem dump pedido: nada a fazer
    if (logits == nullptr || n_vocab <= 0) {
        snprintf(err, err_sz, "entrada invalida (logits=%p n_vocab=%d)",
                 (const void*)logits, n_vocab);
        return false;
    }
    char caminho[1024];
    const int n = snprintf(caminho, sizeof(caminho), "%s_%s.f32", prefixo, passo);
    if (n < 0 || n >= (int)sizeof(caminho)) {          // §5 rodada 11: truncamento
        snprintf(err, err_sz, "caminho truncado (%d bytes)", n);
        return false;
    }
    FILE* f = fopen(caminho, "wb");
    if (!f) {
        snprintf(err, err_sz, "nao abriu %s", caminho);
        return false;
    }
    const size_t esperado = (size_t)n_vocab;
    const size_t escrito  = fwrite(logits, sizeof(float), esperado, f);
    const int    rcf      = fclose(f);
    if (escrito != esperado || rcf != 0) {
        // §4.5: incompleto novo e' MARCADO (quarentena), nao apagado.
        char q[1100];
        snprintf(q, sizeof(q), "%s.INCOMPLETO", caminho);
        rename(caminho, q);
        snprintf(err, err_sz, "escrita incompleta em %s (escrito %zu/%zu, fclose=%d); marcado %s",
                 caminho, escrito, esperado, rcf, q);
        return false;
    }
    return true;
}

#endif // HARNESS_DUMP_H
