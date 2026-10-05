// LOGICA PURA DO LOOP DE DECODIFICACAO — rodada 10, §4.4 do parecer.
//
// Funcoes sem estado, sem contexto, sem llama_context: testadas com arrays
// SINTETICOS (nenhuma inferencia). O harness llama_harness.cpp chama estas
// mesmas funcoes — nao e' uma copia, e' o unico codigo de decisao.
//
// O que mudou aqui e' porque a versao anterior falhava em 3 pontos citados
// pelo parecer §4.1:
//   1. melhor=0 + sentinela -1e30f: se TODOS os logits fossem NaN/Inf, o
//      greedy emitia id 0 sem nenhum candidato elegivel. Alem disso -1e30f
//      exclui logits FINITOS (mas muito negativos) — sentinela arbitraria.
//      Agora: ausencia de candidato e' deteccao por FLAG, sem numero magico.
//   2. a varredura de finitude rodava DEPOIS da escolha (sample() ja tinha
//      lido logits invalidos). Agora a ordem e': varrer -> escolher.
//   3. NaN contado DUAS vezes (no argmax e de novo na varredura). Agora ha
//      UMA contagem, na funcao varrer().
#ifndef HARNESS_LOOP_H
#define HARNESS_LOOP_H

#include <cmath>

struct Varredura {
    int nan = 0;
    int inf = 0;
    bool valido() const { return nan == 0 && inf == 0; }
};

// Varredura UNICA de finitude. Chamada ANTES de qualquer escolha.
inline Varredura varrer(const float* logits, int n) {
    Varredura v;
    if (!logits || n <= 0) { v.nan = -1; return v; }  // -1 = ponteiro invalido
    for (int i = 0; i < n; i++) {
        if (std::isnan(logits[i]))      v.nan++;
        else if (std::isinf(logits[i])) v.inf++;
    }
    return v;
}

// Greedy sem sentinela numerica: iteracao comuns, ignorando nao-finitos.
// tem_candidato = false => NENHUM logit elegivel; o chamador DEVE tratar
// como erro (nao emitir id 0 nem id nenhum). Nao ha numero arbitrario que
// exclua logits finitos: o primeiro finito vira referencia, o maior ganha.
inline int argmax_finito(const float* logits, int n, bool* tem_candidato) {
    *tem_candidato = false;
    if (!logits || n <= 0) return -1;
    int melhor = -1;
    float melhor_val = 0.0f;
    for (int i = 0; i < n; i++) {
        float x = logits[i];
        if (std::isnan(x) || std::isinf(x)) continue;
        if (!*tem_candidato || x > melhor_val) { melhor_val = x; melhor = i; *tem_candidato = true; }
    }
    return *tem_candidato ? melhor : -1;
}

// Fronteira da fala "peca maior que buffer": llama_token_to_piece devolve
// -tamanho_quando_nao_coube. Nao e' erro de conteudo, e' tamanho; quem chama
// realoca. Se devolver <=0 APOS realoque, ai sim e' peca invalida (0 bytes).
inline bool peca_usavel(int retorno_token_to_piece) { return retorno_token_to_piece > 0; }

// Motivo de parada, separado: EOG prova encerramento; teto prova limite.
// As duas coisas NUNCA sao fundidas numa string so — o veredito as trata
// diferente (EOG antes do teto = normal em greedy/app; teto sem EOG = normal
// tambem; teacher sem completar = FALHA).
inline const char* motivo_parada(bool teacher, bool completou, bool eog, bool teto) {
    if (teacher)  return completou ? "protocolo teacher forcing completo"
                                   : "teacher forcing INCOMPLETO (falha)";
    if (eog)      return "EOG (fim de geracao gerado pelo modelo)";
    if (teto)     return "teto de geracao atingido";
    return "decode falhou (logits indisponiveis)";
}

// Contagem de escolhas: passos consumidos = escolhas; tokens de texto = passos
// menos EOG (que nao entra no contexto); decodes = escolhas validas.
inline bool escolhas_consistentes(int escolhas, int decodes, bool houve_eog, bool decode_falhou) {
    if (decode_falhou) return decodes <= escolhas;     // o falho nao decodifica
    if (houve_eog)     return decodes == escolhas - 1; // EOG quebra antes do decode
    return decodes == escolhas;
}

#endif // HARNESS_LOOP_H
