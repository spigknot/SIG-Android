package br.gov.sp.pcsp.launcher

/**
 * Precedência de SELEÇÃO e CONSENTIMENTO da rota ORT.
 *
 * Existe como decisão pura (sobre identificadores, não sobre `android.net.Uri`)
 * para que o contrato seja verificável em teste JVM — antes essas duas regras
 * moravam dentro da Activity e só podiam ser conferidas por clique.
 *
 * Regras:
 * 1. **seleção**: o arquivo escolhido na tela vence o que veio por intent. Sem
 *    isso, abrir A por intent e escolher B transcrevia A enquanto a tela mostrava B.
 * 2. **consentimento**: o override de fallback vale para UMA entrada, a mesma que
 *    foi exibida. Aceitar "usar whisper.cpp" não muda a preferência geral nem
 *    contamina o próximo arquivo.
 */
object WhisperOrtSelecao {

    /** Entrada efetiva: escolha da tela > intent > nada. */
    fun resolver(escolhidaNaTela: String?, doIntent: String?): String? =
        escolhidaNaTela?.takeIf { it.isNotBlank() } ?: doIntent?.takeIf { it.isNotBlank() }

    /**
     * `true` quando o pedido desta entrada deve ir para o whisper.cpp apesar de o
     * ORT ser o padrão.
     *
     * Exige que o override seja EXATAMENTE a entrada efetiva: um override de outra
     * seleção não pode forçar CPP no arquivo seguinte.
     */
    fun forcarCpp(override: String?, entradaEfetiva: String?): Boolean =
        override != null && entradaEfetiva != null && override == entradaEfetiva
}
