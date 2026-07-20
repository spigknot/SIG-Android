package br.gov.sp.pcsp.launcher

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.Normalizer
import java.util.Locale

object NameDatabaseStore {

    @Volatile
    private var cachedNames: Set<String>? = null

    @Volatile
    private var cachedLastModified = -1L

    @Synchronized
    fun ensureDefault(context: Context) {
        val file = namesFile()
        if (file.exists()) return
        file.parentFile?.mkdirs()
        context.assets.open(ASSET_NAME).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Synchronized
    fun load(context: Context): Set<String> {
        ensureDefault(context)
        val file = namesFile()
        val lastModified = file.lastModified()
        cachedNames?.takeIf { cachedLastModified == lastModified }?.let { return it }
        return file.readLines(Charsets.UTF_8)
            .asSequence()
            .flatMap { matchingKeys(it).asSequence() }
            .filter(String::isNotBlank)
            .toSet()
            .also {
                cachedNames = it
                cachedLastModified = lastModified
            }
    }

    fun namesFile(): File {
        return File(
            File(File(Environment.getExternalStorageDirectory(), "SIG"), "Nomes"),
            "nomes.txt"
        )
    }

    @Synchronized
    fun addName(context: Context, value: String): Boolean {
        val name = value.trim().uppercase(Locale.ROOT)
        if (name.isBlank() || name.any { it == '\n' || it == '\r' || it == '\t' }) return false
        ensureDefault(context)
        val file = namesFile()
        val current = file.readLines(Charsets.UTF_8)
        if (current.any { normalize(it) == normalize(name) }) return false
        file.appendText(if (file.length() > 0L) "\n$name" else name, Charsets.UTF_8)
        cachedNames = null
        cachedLastModified = -1L
        return true
    }

    @Synchronized
    fun removeName(context: Context, value: String): Boolean {
        val target = normalize(value)
        if (target.isBlank()) return false
        ensureDefault(context)
        val file = namesFile()
        var removed = false
        val remaining = file.readLines(Charsets.UTF_8).filter { line ->
            val matches = normalize(line) == target
            if (matches) removed = true
            !matches
        }
        if (removed) {
            file.writeText(remaining.joinToString("\n").trimEnd() + "\n", Charsets.UTF_8)
            cachedNames = null
            cachedLastModified = -1L
        }
        return removed
    }

    fun normalize(value: String): String {
        return Normalizer.normalize(value.trim().uppercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace(Regex("""[^\p{L}'’-]"""), "")
    }

    fun matchingKeys(value: String): Set<String> {
        val normalized = normalize(value)
        if (normalized.isBlank()) return emptySet()
        return linkedSetOf(normalized, phoneticKey(normalized))
    }

    private fun phoneticKey(normalized: String): String {
        return normalized
            .replace("PH", "F")
            .replace("TH", "T")
            .replace("Y", "I")
            .replace("W", "V")
            .replace(Regex("""^H"""), "")
            .replace("QU", "C")
            .replace("K", "C")
            .replace("Q", "C")
            .replace(Regex("""C(?=[EI])"""), "S")
            .replace(Regex("""G(?=[EI])"""), "J")
            .replace("Z", "S")
            .replace(Regex("""([A-Z])\1+"""), "$1")
    }

    private val COMBINING_MARKS = Regex("""\p{M}+""")
    private const val ASSET_NAME = "default_nomes.txt"
}
