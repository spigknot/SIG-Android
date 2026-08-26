package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeysImportParserTest {
    @Test
    fun `separa pela ultima palavra e aceita nome de servico com espacos`() {
        val result = ApiKeysImportParser.parse(
            """
            AssemblyAI assembly-key
            Imei Check imei-key
            """.trimIndent()
        )

        assertEquals("assembly-key", result.keys[ApiKeysImportParser.Service.ASSEMBLYAI])
        assertEquals("imei-key", result.keys[ApiKeysImportParser.Service.IMEI_CHECK])
        assertTrue(result.ignoredLineNumbers.isEmpty())
    }

    @Test
    fun `mapeia servicos sem diferenciar maiusculas e minusculas`() {
        val result = ApiKeysImportParser.parse(
            """
            xAI xai-key
            deepseek deepseek-key
            DEEPGRAM deepgram-key
            elevenLABS eleven-key
            """.trimIndent()
        )

        assertEquals(4, result.keys.size)
        assertEquals("xai-key", result.keys[ApiKeysImportParser.Service.XAI])
        assertEquals("deepseek-key", result.keys[ApiKeysImportParser.Service.DEEPSEEK])
        assertEquals("deepgram-key", result.keys[ApiKeysImportParser.Service.DEEPGRAM])
        assertEquals("eleven-key", result.keys[ApiKeysImportParser.Service.ELEVENLABS])
    }

    @Test
    fun `ignora linhas invalidas e servicos desconhecidos sem expor a chave`() {
        val result = ApiKeysImportParser.parse(
            """
            AssemblyAI valid-key
            Servico Novo secret-key
            linha-incompleta
            """.trimIndent()
        )

        assertEquals(1, result.keys.size)
        assertEquals(listOf("Servico Novo"), result.unknownServices)
        assertEquals(listOf(2, 3), result.ignoredLineNumbers)
        assertTrue(result.unknownServices.none { it.contains("secret-key") })
    }
}
