package br.gov.sp.pcsp.launcher

/** Regras de diarização por provedor (Deepgram, AssemblyAI, ElevenLabs, Grok).
 *
 * Cada provedor tem parâmetros e suporte próprios, e o modo (REST vs
 * WebSocket) também importa: o Scribe v2 Realtime não suporta diarização.
 * Este objeto concentra a construção dos parâmetros e a regra de
 * habilitação da checkbox, mantendo os fluxos isolados por provedor.
 */
object SttDiarization {

    /** A checkbox fica habilitada para este provedor/modo? */
    fun supportsDiarize(provider: String, isLive: Boolean): Boolean = when (provider) {
        "deepgram" -> true
        "assemblyai" -> true
        // Scribe v2: checkbox liberada; o "?" vermelho avisa que o Realtime
        // (WS) não aplica diarização — só o REST envia diarize=true.
        "elevenlabs" -> true
        // Grok (xAI): a documentação confirma o suporte à diarização
        // acústica (campo numérico speaker nas palavras).
        "grok" -> true
        else -> false
    }

    /** Deepgram: diarize_model=latest (o diarize=true é deprecated). */
    fun deepgramQuery(checked: Boolean): String? =
        if (checked) "diarize_model=latest" else null

    /** AssemblyAI WebSocket: speaker_labels=true nos parâmetros da conexão. */
    fun assemblyaiWsQuery(checked: Boolean): String? =
        if (checked) "speaker_labels=true" else null

    /** AssemblyAI REST: (speaker_labels, punctuate). speaker_labels exige punctuate=true. */
    fun assemblyaiRest(checked: Boolean): Pair<Boolean, Boolean> =
        if (checked) true to true else false to false

    /** ElevenLabs REST (Scribe v2): diarize=true no form multipart. */
    fun elevenlabsRestDiarize(checked: Boolean): Boolean = checked

    /** ElevenLabs WS (Realtime): não suporta — nunca enviar parâmetros. */
    fun elevenlabsWsQuery(checked: Boolean): String? = null

    /** Grok: diarize=true na query (REST multipart e WebSocket). */
    fun grokQuery(checked: Boolean): String? =
        if (checked) "diarize=true" else null

    /** Grok REST: diarize=true no form (o campo file deve ser o último). */
    fun grokRestDiarize(checked: Boolean): Boolean = checked
}
