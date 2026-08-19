package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * VACINA: garante que as classes do FFmpegKit (e a dependência
 * smart-exception-java, que o FFmpegKitConfig.<clinit> exige) estão no
 * classpath do APK.
 *
 * Histórico: o commit 8007dfb removeu o smart-exception-java como
 * "dependência redundante" e TODAS as ferramentas ffmpeg quebraram em
 * runtime (NoClassDefFoundError) — o build passava normalmente. Este teste
 * quebra o build imediatamente se qualquer uma destas classes sumir.
 */
class FfmpegKitClasspathTest {

    @Test
    fun `FFmpegKitConfig e a dependencia smart-exception estao no classpath`() {
        assertNotNull(Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig"))
        assertNotNull(Class.forName("com.arthenica.smartexception.java.Exceptions"))
        assertNotNull(Class.forName("com.arthenica.ffmpegkit.FFmpegKit"))
    }
}
