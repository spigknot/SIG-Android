package br.gov.sp.pcsp.launcher

import org.junit.rules.TemporaryFolder
import java.io.File

/** Monta um pacote válido MÍNIMO em diretório temporário, para testes de consumidor. */
object WhisperOrtPackageInstallerTestSupport {

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(b).joinToString("") { "%02x".format(it) }

    fun packageWith(temp: TemporaryFolder): WhisperOrtPackageInstaller.Validated {
        val base = arrayOf(
            "encoder_model_int8.onnx" to ByteArray(32) { 1 },
            "decoder_model_int8.onnx" to ByteArray(24) { 2 },
            "decoder_with_past_model_int8.onnx" to ByteArray(16) { 3 },
            "vocab.json" to """{"a":65}""".toByteArray(),
            "generation_config.json" to """{"max_length":448}""".toByteArray(),
        )
        val cat = base.map { (n, b) ->
            WhisperOrtModelCatalog.Entry(n, b.size.toLong(), sha(b), when {
                n.contains("with_past") -> WhisperOrtModelPackage.Role.DECODER_WITH_PAST
                n.contains("encoder") -> WhisperOrtModelPackage.Role.ENCODER
                n.contains("decoder") -> WhisperOrtModelPackage.Role.DECODER
                n.startsWith("vocab") -> WhisperOrtModelPackage.Role.TOKENIZER
                else -> WhisperOrtModelPackage.Role.CONFIG
            })
        }
        val staging = temp.newFolder("stg-${System.nanoTime()}")
        base.forEach { (n, b) -> File(staging, n).writeBytes(b) }
        val id = WhisperOrtModelPackage.Identity("m", "r", "int8", "CPU", 14)
        val r = WhisperOrtPackageInstaller.install(staging, File(temp.root, "pkg-${System.nanoTime()}"), id, cat)
        return (r as WhisperOrtPackageInstaller.Outcome.Ready).validated
    }
}
