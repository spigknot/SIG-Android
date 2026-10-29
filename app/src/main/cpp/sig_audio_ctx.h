#ifndef SIG_AUDIO_CTX_H
#define SIG_AUDIO_CTX_H

/*
 * R15/D2 — AUDIO_CTX DERIVADO DA JANELA (não de número mágico).
 *
 * O override da R14 fixava 1024 sem provar cobertura — e a medição ainda tinha
 * sido feita no modelo BASE. Aqui o contexto vem das AMOSTRAS EFETIVAMENTE
 * enviadas à janela, com margem documentada, e é RECUSADO quando não cobre.
 *
 * Conversão amostras -> posições (premissa EXPLÍCITA, verificada com o modelo):
 *   whisper reamostra para 16 kHz; o mel usa hop de 160 amostras (10 ms);
 *   o encoder consome 2 quadros de mel por posição (stride 2 na 1ª conv).
 *   => posições = n_samples / 320.
 *   Sanidade: 30 s = 480.000 amostras -> 1.500 posições = n_audio_ctx padrão.
 *
 * Regra:
 *   posicoes = ceil(n_samples / 320)        (ceil: NENHUMA amostra fica fora)
 *   ctx      = posicoes + ceil(posicoes/8)  (margem de 12,5%, documentada)
 *   recusa   = ctx > ctx_do_modelo          => 0 (não reduzir; não inventar)
 *
 * Recusar é deliberado: executar contexto reduzido num trecho maior do que a
 * cobertura perde o meio do áudio e produz comparação inválida.
 */

#define SIG_AUDIO_CTX_AMOSTRAS_POR_POSICAO 320

static inline int sig_audio_ctx_de_janela(long long n_samples, int ctx_do_modelo) {
    if (n_samples <= 0 || ctx_do_modelo <= 0) return 0;

    const long long posicoes =
        (n_samples + SIG_AUDIO_CTX_AMOSTRAS_POR_POSICAO - 1) / SIG_AUDIO_CTX_AMOSTRAS_POR_POSICAO;

    /* margem de 12,5% arredondada para cima, para padding/reparo de cauda */
    const long long margem = (posicoes + 7) / 8;
    const long long ctx = posicoes + margem;

    if (ctx > (long long) ctx_do_modelo) return 0;   /* não cobre: RECUSA */
    if (ctx < 1) return 0;
    return (int) ctx;
}

#endif /* SIG_AUDIO_CTX_H */
