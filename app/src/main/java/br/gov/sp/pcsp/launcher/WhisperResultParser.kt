package br.gov.sp.pcsp.launcher

import java.util.Locale

/** Resultado estruturado de uma transcrição Whisper (W10).
 *
 * O status nativo ("ok"/"no_speech"/"cancelled"/"error") é a fonte de verdade;
 * o prefixo de controle do texto ("Erro:"/"Cancelado:") é apenas fallback para
 * bibliotecas antigas que não expõem lastTranscribeStatus. Um texto que começa
 * com "Erro:" ou "Cancelado:" vindo com status "ok" é conteúdo real do áudio,
 * não um sinal de falha.
 */
sealed class WhisperTranscriptResult {
    data class Success(val text: String) : WhisperTranscriptResult()
    data class Cancelled(val message: String) : WhisperTranscriptResult()
    data class Error(val message: String) : WhisperTranscriptResult()
    object NoSpeech : WhisperTranscriptResult()
}

object WhisperResultParser {

    private const val ERRO_PREFIX = "Erro:"
    private const val CANCELADO_PREFIX = "Cancelado:"

    const val DEFAULT_CANCEL_MESSAGE = "transcrição cancelada"
    const val DEFAULT_ERROR_MESSAGE = "falha na transcrição"

    /** Status conhecidos pelo nativo. */
    const val STATUS_OK = "ok"
    const val STATUS_NO_SPEECH = "no_speech"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_ERROR = "error"

    /** Interpretar o retorno de [WhisperNative.transcribe] combinando texto e
     * [WhisperNative.lastTranscribeStatus]. Texto e status são normalizados com
     * trim; status desconhecido/ausente cai no legado por prefixo. */
    fun parse(returned: String?, status: String?): WhisperTranscriptResult {
        val text = returned?.trim().orEmpty()
        return when (status?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            STATUS_OK -> WhisperTranscriptResult.Success(text)
            STATUS_NO_SPEECH -> WhisperTranscriptResult.NoSpeech
            STATUS_CANCELLED -> WhisperTranscriptResult.Cancelled(cancelMessageOf(text))
            STATUS_ERROR -> WhisperTranscriptResult.Error(errorMessageOf(text))
            else -> parseLegacyByPrefix(text)
        }
    }

    /** Compat: bibliotecas sem lastTranscribeStatus. */
    fun parseLegacyByPrefix(text: String?): WhisperTranscriptResult {
        val trimmed = text?.trim().orEmpty()
        return when {
            trimmed.startsWith(CANCELADO_PREFIX) ->
                WhisperTranscriptResult.Cancelled(cancelMessageOf(trimmed))
            trimmed.startsWith(ERRO_PREFIX) ->
                WhisperTranscriptResult.Error(errorMessageOf(trimmed))
            else -> WhisperTranscriptResult.Success(trimmed)
        }
    }

    private fun cancelMessageOf(text: String): String =
        text.removePrefix(CANCELADO_PREFIX).trim().ifEmpty { DEFAULT_CANCEL_MESSAGE }

    private fun errorMessageOf(text: String): String =
        text.removePrefix(ERRO_PREFIX).trim().ifEmpty { DEFAULT_ERROR_MESSAGE }
}
