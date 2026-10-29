package br.gov.sp.pcsp.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * R6/D3 — PROVIDER DE FIXTURES do laboratório (existe SÓ no flavor
 * `whisperlab`; nenhum APK normal contém esta classe nem a authority).
 *
 * Serve arquivos de `filesDir/art-lab/fixtures/` por `content://` para injetar
 * a rota de ARQUIVO da WhisperActivity REAL (o MESMO caminho do seletor SAF:
 * loadPickedMedia → sondagem → conversão → load → JNI → relatório).
 *
 * Controles: `exported=false` (só o próprio app/lab acessa), sem
 * grantUriPermissions, nome do caminho validado (sem '..' e sem '/') e
 * openFile read-only. O arquivo servido é uma fixture controlada do lab —
 * jamais áudio pessoal.
 */
class WhisperLabFixtureProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "br.gov.sp.pcsp.whisperlab.fixtures"
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.isEmpty() || name.contains("..") || name.contains('/') || name.contains('\\')) return null
        val ctx = context ?: return null
        return File(File(ctx.filesDir, "art-lab/fixtures"), name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri) ?: throw FileNotFoundException("fixture inválida: $uri")
        if (!f.isFile) throw FileNotFoundException("fixture ausente: ${f.absolutePath}")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "wav" -> "audio/wav"
        "txt" -> "text/plain"
        "pcm" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("provider de fixtures é somente leitura")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("provider de fixtures é somente leitura")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("provider de fixtures é somente leitura")
}
