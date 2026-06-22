package br.gov.sp.pcsp.launcher

import android.content.Context
import java.io.File

object AppCacheManager {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun cleanOlderThanOneDay(context: Context): Long {
        val cutoff = System.currentTimeMillis() - DAY_MS
        return cacheRoots(context).sumOf { root -> deleteOlderThan(root, cutoff) }
    }

    fun clearAll(context: Context): Long {
        return cacheRoots(context).sumOf { root -> deleteChildren(root) }
    }

    fun cacheSize(context: Context): Long {
        return cacheRoots(context).sumOf { root -> root.sizeRecursively() }
    }

    private fun cacheRoots(context: Context): List<File> {
        return listOfNotNull(context.cacheDir, context.externalCacheDir).distinctBy { it.absolutePath }
    }

    private fun deleteOlderThan(file: File, cutoff: Long): Long {
        if (!file.exists()) return 0L
        if (file.isFile) {
            val size = file.length()
            return if (file.lastModified() < cutoff && file.delete()) size else 0L
        }

        var deleted = 0L
        file.listFiles().orEmpty().forEach { child ->
            deleted += deleteOlderThan(child, cutoff)
        }
        if (file.listFiles().isNullOrEmpty() && file.lastModified() < cutoff) {
            file.delete()
        }
        return deleted
    }

    private fun deleteChildren(root: File): Long {
        if (!root.exists()) return 0L
        var deleted = 0L
        root.listFiles().orEmpty().forEach { child ->
            deleted += child.sizeRecursively()
            child.deleteRecursively()
        }
        return deleted
    }

    private fun File.sizeRecursively(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return listFiles().orEmpty().sumOf { it.sizeRecursively() }
    }
}
