package br.gov.sp.pcsp.launcher

import java.io.File

/**
 * Storage policy shared by the local Whisper and remote STT tools.
 *
 * The public SIG directory is valid only when the app has the special
 * all-files access. Without it, scoped storage can report EPERM only when a
 * file is opened, even though the parent directories were created. The
 * app-specific directory remains writable in that case and can later be
 * exported through the Storage Access Framework.
 */
internal object SttOutputStorage {
    fun chooseRoot(
        publicRoot: File,
        appSpecificRoot: File,
        publicStorageAvailable: Boolean,
    ): File = if (publicStorageAvailable) publicRoot else appSpecificRoot

    fun ensureDirectory(directory: File): Boolean {
        return runCatching {
            directory.isDirectory || (directory.mkdirs() && directory.isDirectory)
        }.getOrDefault(false)
    }
}
