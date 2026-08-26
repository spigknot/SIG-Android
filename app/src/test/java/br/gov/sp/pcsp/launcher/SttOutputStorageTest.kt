package br.gov.sp.pcsp.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SttOutputStorageTest {
    @Test
    fun `usa a pasta publica quando o acesso foi concedido`() {
        val publicRoot = File("/storage/emulated/0/SIG/Whisper")
        val appSpecificRoot = File("/data/user/0/br.gov.sp.pcsp.launcher/files/Whisper")

        assertEquals(
            publicRoot,
            SttOutputStorage.chooseRoot(publicRoot, appSpecificRoot, publicStorageAvailable = true)
        )
    }

    @Test
    fun `usa a pasta privada quando o acesso publico nao foi concedido`() {
        val publicRoot = File("/storage/emulated/0/SIG/Whisper")
        val appSpecificRoot = File("/data/user/0/br.gov.sp.pcsp.launcher/files/Whisper")

        assertEquals(
            appSpecificRoot,
            SttOutputStorage.chooseRoot(publicRoot, appSpecificRoot, publicStorageAvailable = false)
        )
    }
}
