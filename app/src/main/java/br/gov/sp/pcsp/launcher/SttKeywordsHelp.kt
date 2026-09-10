package br.gov.sp.pcsp.launcher

/**
 * Texto da tela de ajuda das keywords (o "?" da checkbox e da seção Keywords).
 *
 * Explica o que o recurso faz, que a ORDEM da tabela é a ordem de envio e —
 * o ponto principal — quanto de cada modelo é realmente aproveitado: cada
 * provedor aceita um número limitado de termos e alguns cortam o próprio termo
 * (caracteres). O texto é montado aqui, sem UI, para poder ser verificado.
 */
object SttKeywordsHelp {

    /** Nome do provedor como o usuário o vê nas telas de transcrição. */
    fun providerLabel(provider: String): String = when (provider) {
        "deepgram" -> "Deepgram Nova 3"
        "grok" -> "Grok STT (xAI)"
        "elevenlabs" -> "ElevenLabs Scribe v2"
        "assemblyai" -> "AssemblyAI Universal-3.5 Pro"
        "metamuse" -> "Muse Voice (Meta)"
        "alibaba" -> "Alibaba Fun ASR/Qwen"
        else -> provider
    }

    const val PURPOSE =
        "Keywords são termos de reforço: palavras ou expressões que você quer que o modelo " +
            "reconheça com mais facilidade (nomes próprios, apelidos, gírias, lugares, " +
            "medicamentos, placas...). Elas não substituem a transcrição normal — servem apenas " +
            "para aumentar a chance de o termo sair escrito corretamente quando ele aparecer no áudio."

    const val ORDER_TIP =
        "A ORDEM IMPORTA: os termos são enviados na ordem da tabela e cada modelo aproveita " +
            "apenas uma PARTE da lista (veja os limites abaixo). Cadastre primeiro os termos mais " +
            "importantes — os que ficarem no fim podem não ser usados."

    const val CHECKBOX_TIP =
        "A caixa \"Keywords\" liga e desliga o envio. Desmarcada, nenhuma keyword vai na " +
            "requisição e a transcrição segue como antes."

    const val INSERTION_WARNING =
        "ATENÇÃO: o reforço pode fazer o modelo escrever um termo cadastrado mesmo quando ele " +
            "NÃO foi dito no áudio. Em transcrição que vira prova, isso é um risco real. Use poucos " +
            "termos, só os realmente importantes, e confira a transcrição antes de usar."

    const val LIMITS_HEADER = "Limites de cada modelo:"

    /** O que cada provedor aproveita da lista (REST e ao vivo podem diferir). */
    fun providerLimit(provider: String, isLive: Boolean): String? = when (provider) {
        "deepgram" ->
            "Deepgram: envia os primeiros termos até somar 500 tokens no total — na prática, " +
                "cerca de 38 termos de 50 caracteres. Os termos seguintes são descartados."
        "grok" ->
            "Grok (xAI): até 100 termos. Termo com mais de 50 caracteres é descartado."
        "elevenlabs" ->
            if (isLive) {
                "ElevenLabs (ao vivo): até 50 termos, e de cada termo são usados apenas os " +
                    "primeiros 20 caracteres."
            } else {
                "ElevenLabs (arquivo): até 1000 termos; cada termo precisa ter menos de 50 " +
                    "caracteres (e no máximo 5 palavras)."
            }
        "assemblyai" ->
            if (isLive) {
                "AssemblyAI (ao vivo): até 100 termos. Termo com mais de 50 caracteres é descartado."
            } else {
                "AssemblyAI (arquivo): envia os primeiros termos até somar 2048 caracteres."
            }
        "metamuse" ->
            "Muse Voice (Meta): a lista inteira é enviada — não há limite publicado."
        "alibaba" ->
            if (isLive) {
                "Alibaba Cloud (ao vivo): a lista inteira é enviada, com peso 5 para cada termo."
            } else {
                "Alibaba Cloud (arquivo): o modelo atual NÃO aplica keywords — ele exige uma " +
                    "lista de hotwords pré-cadastrada no painel do Alibaba (por isso, neste modo, " +
                    "as keywords cadastradas aqui não surtem efeito)."
            }
        else -> null
    }

    /** Linha com o aproveitamento REAL da lista atual no provedor/modo atual. */
    fun effectiveLine(provider: String, isLive: Boolean, keywords: List<String>): String? {
        val total = SttKeywords.normalize(keywords).size
        if (total == 0) return null
        val enviados = SttKeywords.fitForProvider(provider, keywords, isLive).size
        val modo = if (isLive) "ao vivo" else "arquivos"
        return if (enviados >= total) {
            "Na configuração atual ($modo), o modelo recebe todos os $total termos cadastrados."
        } else {
            "Na configuração atual ($modo), o modelo recebe $enviados dos $total termos " +
                "cadastrados — os outros ficam de fora."
        }
    }

    /** Texto completo da ajuda. `provider` nulo = mostra todos (seção Keywords). */
    fun text(
        provider: String?,
        isLive: Boolean,
        keywords: List<String> = emptyList(),
    ): String = buildString {
        append(PURPOSE)
        append("\n\n").append(ORDER_TIP)
        if (provider != null) {
            append("\n\n").append(CHECKBOX_TIP)
            effectiveLine(provider, isLive, keywords)?.let { append("\n\n").append(it) }
        }
        append("\n\n").append(LIMITS_HEADER)
        val limits = if (provider == null) {
            listOf(
                providerLimit("deepgram", false),
                providerLimit("grok", false),
                providerLimit("elevenlabs", false),
                providerLimit("elevenlabs", true),
                providerLimit("assemblyai", true),
                providerLimit("assemblyai", false),
                providerLimit("metamuse", false),
                providerLimit("alibaba", false),
                providerLimit("alibaba", true),
            )
        } else {
            listOf(providerLimit(provider, isLive))
        }
        limits.filterNotNull().forEach { append("\n• ").append(it) }
        append("\n\n").append(INSERTION_WARNING)
    }
}
