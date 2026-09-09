package br.gov.sp.pcsp.launcher

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns

/**
 * Acesso a URIs de documento e a armazenamento amplo, compartilhado pelas
 * telas que recebem arquivos por seletor (FFmpeg, Granite, RemoteStt, Whisper).
 *
 * Sao wrappers finos sobre APIs do Android: nao ha regra de negocio aqui, e o
 * teste de regressao e o gate completo (compilar + lint + assemble).
 */
internal object MediaUriSupport {

    /** Nome de exibicao do documento; cai para o ultimo segmento da URI. */
    fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    /** Android 11+ exige acesso amplo (MANAGE_EXTERNAL_STORAGE); antes disso, sempre ok. */
    fun hasSigStorageAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    /** Pede permissao persistente de leitura; falha e ignorada de proposito. */
    fun tryTakeReadPermission(contentResolver: ContentResolver, uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
    }
}
