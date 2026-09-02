package br.gov.sp.pcsp.launcher

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

/**
 * Leitura das midias recebidas por compartilhamento (ACTION_SEND /
 * ACTION_SEND_MULTIPLE) nas ferramentas do SIG.
 *
 * O filtro de mimetype do AndroidManifest ja limita o que aparece na gaveta de
 * compartilhamento, mas alguns remetentes enviam tipos genericos
 * (application/octet-stream, * / *). Cada ferramenta revalida o tipo aqui
 * antes de carregar, para que a restricao "somente video" / "somente audio"
 * valha tambem nesses casos.
 */
object SharedMediaIntents {

    private val VIDEO_EXTENSIONS =
        listOf(".mp4", ".mkv", ".mov", ".avi", ".webm", ".3gp", ".m4v", ".mts", ".m2ts")
    private val AUDIO_EXTENSIONS =
        listOf(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".amr", ".aiff", ".wma")

    data class SharedMedia(
        val uri: Uri,
        val name: String,
        val mime: String
    ) {
        val isVideo: Boolean get() = isVideoMedia(name, mime)
        val isAudio: Boolean get() = isAudioMedia(name, mime)
    }

    /**
     * O mimetype decide; a extensao so entra em cena quando ele e ausente ou
     * generico. Mantido em funcoes puras para ser testavel sem o android.jar.
     */
    fun isVideoMedia(name: String, mime: String): Boolean = when {
        mime.startsWith("video/") -> true
        mime.startsWith("audio/") -> false
        else -> endsWithAny(name, VIDEO_EXTENSIONS)
    }

    fun isAudioMedia(name: String, mime: String): Boolean = when {
        mime.startsWith("audio/") -> true
        mime.startsWith("video/") -> false
        else -> endsWithAny(name, AUDIO_EXTENSIONS)
    }

    fun isShareAction(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    /**
     * URIs de todas as origens que um remetente pode usar: ClipData (galeria,
     * SEND_MULTIPLE), EXTRA_STREAM (SEND simples) e o proprio data do intent.
     */
    @Suppress("DEPRECATION")
    fun urisFrom(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { uris += it }
            }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        intent.data?.let { uris += it }
        return uris.distinct()
    }

    /** Mantem o acesso de leitura depois que o processo remetente morrer. */
    fun takeReadPermission(resolver: ContentResolver, uri: Uri, flags: Int) {
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }

    /** Lista vazia quando o intent nao e um compartilhamento. */
    fun mediaFrom(context: Context, intent: Intent?): List<SharedMedia> {
        if (!isShareAction(intent)) return emptyList()
        val shared = intent ?: return emptyList()
        val resolver = context.contentResolver
        return urisFrom(shared).map { uri ->
            takeReadPermission(resolver, uri, shared.flags)
            val name = displayName(context, uri, "midia")
            SharedMedia(uri, name, mimeOf(context, uri, name))
        }
    }

    fun displayName(context: Context, uri: Uri, fallback: String): String {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val value = cursor.getString(index).orEmpty().trim()
                        if (value.isNotEmpty()) return value
                    }
                }
            }
        val segment = uri.lastPathSegment?.substringAfterLast('/').orEmpty().trim()
        return segment.ifEmpty { fallback }
    }

    /** Ignora mimetypes genericos para que a classificacao caia na extensao. */
    fun mimeOf(context: Context, uri: Uri, name: String): String {
        val declared = context.contentResolver.getType(uri).orEmpty().trim()
        if (declared.isNotEmpty() && declared != "*/*" && declared != "application/octet-stream") {
            return declared
        }
        return mimeFromExtension(name)
    }

    fun mimeFromExtension(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "mp4", "m4v", "mov", "3gp" -> "video/$extension"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mts", "m2ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "amr" -> "audio/amr"
            "aiff" -> "audio/aiff"
            "wma" -> "audio/x-ms-wma"
            else -> ""
        }
    }
}

private fun endsWithAny(name: String, extensions: List<String>): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return extensions.any { lower.endsWith(it) }
}
