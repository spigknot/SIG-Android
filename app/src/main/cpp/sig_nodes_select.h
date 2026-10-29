// R24/CAP — SELEÇÃO EXPLÍCITA DE INTERVALO PARA CAPTURA POR NÓ.
//
// O código antigo tinha `nodes_first` como QUANTIDADE DE PREFIXO e
// `nodes_step` como amostragem desde zero. Não existia offset: pedir
// FIRST=48/STEP=1 capturava de novo 0…23 e esgotava o teto — foi o erro que a
// revisão da R23 apontou no meu "próximo recorte".
//
// Este header define o contrato novo, testável fora do aparelho:
//   modo INTERVALO : begin <= comp < end   (end EXCLUSIVO), com step opcional
//   modo PREFIXO   : 0 <= comp < end       (compatibilidade com o legado)
// Regras explícitas:
//   * begin < 0, end < 0 ou end < begin  → seleção INVÁLIDA (nenhum nó).
//   * begin == end                       → vazio (não "tudo").
//   * teto (max) é contado em CAPTURAS, não em posições visitadas.
//   * nó não elegível por tipo/OP continua fora, e o manifesto não afirma
//     arquivo que não foi escrito.
#ifndef SIG_NODES_SELECT_H
#define SIG_NODES_SELECT_H

#include <cstdlib>

namespace sig_nodes {

enum class Modo { LEGADO, INTERVALO };

struct Sel {
    Modo modo   = Modo::LEGADO;
    int  begin  = 0;      // intervalo: offset inicial (inclusivo)
    int  end    = 0;      // intervalo: limite (EXCLUSIVO) / legado: tamanho do prefixo
    int  step   = 0;      // 0 = sem amostragem
    int  max    = 0;      // teto de capturas; <=0 = sem teto
    bool valida = true;   // false = parâmetro recusado
};

inline int env_int(const char * nome, int padrao) {
    const char * v = getenv(nome);
    if (v == nullptr || v[0] == '\0') {
        return padrao;
    }
    char * fim = nullptr;
    const long x = strtol(v, &fim, 10);
    if (fim == v || (fim != nullptr && *fim != '\0')) {
        return -1;   // presente e nao numerico: RECUSA (nao cai no default)
    }
    if (x < 0 || x > 100000000L) {
        return -1;   // sinaliza inválido ao chamador
    }
    return (int) x;
}

// Monta a seleção a partir do ambiente. `primeiro_legado` é nodes_first
// (quantidade de prefixo) usado quando NODES_END não é informado.
inline Sel from_env(int primeiro_legado, int step_legado, int max_legado) {
    Sel s;
    const int begin = env_int("SIG_WHISPER_DUMP_NODES_BEGIN", 0);
    const int end   = env_int("SIG_WHISPER_DUMP_NODES_END",   -2);   // -2 = ausente
    if (begin < 0 || end == -1) {
        s.valida = false;
        return s;
    }
    if (end == -2) {
        // R25/FIX — MODO LEGADO preservado: `end` é o TAMANHO DO PREFIXO e o
        // step amostra o grafo inteiro (comp % step), como antes da R24.
        s.modo  = Modo::LEGADO;
        s.begin = 0;
        s.end   = primeiro_legado > 0 ? primeiro_legado : 0;
        s.step  = step_legado;
    } else {
        s.modo  = Modo::INTERVALO;
        s.begin = begin;
        s.end   = end;
        s.step  = step_legado;
    }
    s.max = max_legado;
    if (s.end < s.begin) {
        s.valida = false;
    }
    return s;
}

// Decisão por nó. `comp` é o índice de computação; `ja_capturados` é o total de
// capturas feitas nesta tentativa (teto). `elegivel` cobre tipo/OP (F32/F16,
// OP != NONE) que são responsabilidade do chamador.
inline bool want(const Sel & s, int comp, int ja_capturados, bool elegivel) {
    if (!s.valida || !elegivel || comp < 0) {
        return false;
    }
    if (s.max > 0 && ja_capturados >= s.max) {
        return false;
    }
    if (s.modo == Modo::LEGADO) {
        // R25/FIX — legado: prefixo OU amostragem ao longo do grafo.
        const bool no_prefixo = comp < s.end;
        const bool no_passo   = s.step > 0 && (comp % s.step) == 0;
        return no_prefixo || no_passo;
    }
    if (comp < s.begin || comp >= s.end) {
        return false;          // end é EXCLUSIVO
    }
    if (s.step > 0 && ((comp - s.begin) % s.step) != 0) {
        return false;
    }
    return true;
}

} // namespace sig_nodes

#endif // SIG_NODES_SELECT_H
