package br.gov.sp.pcsp.launcher

/**
 * R115 §T02.1 — REALIMENTAÇÃO DIRETA entre passos do decoder HTP, sem cópia.
 *
 * A regra é a que a R114 qualificou com 0,7 ms por janela (contra 36,6 ms do
 * modo cópia):
 * 1. o primeiro passo da janela usa caches self **zerados**, próprios;
 * 2. do segundo em diante, os tensores de saída do passo anterior
 *    (`k/v_cache_self_i_out`) entram como entrada do passo seguinte
 *    (`k/v_cache_self_i_in`), sem passar por buffer intermediário;
 * 3. o resultado anterior só é **fechado depois** do `run` que o consumiu —
 *    fechar antes invalida a memória que o QNN EP ainda está lendo;
 * 4. se a API recusar a realimentação direta, o erro é registrado e o caminho
 *    cai para cópia (ping-pong), sem mascarar a falha.
 *
 * Genérico de propósito: com `T = OnnxTensor` é o produto; com `T = String` o
 * teste JVM prova a ORDEM (zerar → reusar → fechar depois), que é o que pode
 * quebrar sem o aparelho.
 */
internal class WhisperOrtFeedbackDireto<T, S>(
    /** Saídas de um resultado ([T]) por nome de ENTRADA do passo seguinte ([S]). */
    private val saidasParaEntrada: (T) -> Map<String, S>,
    /** Libera um resultado. */
    private val fechar: (T) -> Unit,
) {

    /** `direto` (Q′) ou `copia` (API recusou a realimentação). */
    var modo: String = DIRETO
        private set

    /** Primeira recusa da API, com a mensagem — nunca apagada. */
    var erro: String? = null
        private set

    private var anterior: T? = null

    /** Há resultado fechado pendente? Só para diagnóstico e teste. */
    val temAnterior: Boolean get() = anterior != null

    /**
     * Entradas de cache deste passo e a origem que só pode ser fechada depois
     * do `run` (a mesma que foi lida para montar as entradas).
     */
    fun entradas(): Pair<Map<String, S>, T?> {
        val fonte = anterior
        if (fonte == null) return emptyMap<String, S>() to null
        return saidasParaEntrada(fonte) to fonte
    }

    /** A API recusou a realimentação: registra e passa a copiar. */
    fun recusar(motivo: String) {
        if (erro == null) erro = motivo
        modo = COPIA
        // O resultado anterior CONTINUA aberto: a cópia do caminho `copia` lê
        // dele no retry imediato, e ele só fecha em [concluir] (depois do run).
    }

    /** Guarda o resultado deste passo e fecha, DEPOIS do run, o que foi lido. */
    fun concluir(novo: T, origem: T?) {
        anterior = novo
        if (origem != null) fechar(origem)
    }

    /** Fim da janela/backend: não deixa resultado aberto. */
    fun limpar() {
        val fonte = anterior
        anterior = null
        if (fonte != null) fechar(fonte)
    }

    companion object {
        const val DIRETO = "direto"
        const val COPIA = "copia"
    }
}
