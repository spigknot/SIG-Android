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

    @Test
    fun `mapeia muse voice com e sem prefixo meta`() {
        val result = ApiKeysImportParser.parse(
            """
            Muse LLM|1|secret-a
            MetaMuse LLM|2|secret-b
            """.trimIndent()
        )

        assertEquals("LLM|2|secret-b", result.keys[ApiKeysImportParser.Service.METAMUSE])
    }

    @Test
    fun `le o formato novo com uma chave por linha em qualquer ordem`() {
        val result = ApiKeysImportParser.parse(
            """
            Deepgram chave-deepgram
            ImeiCheck chave-imei
            xAI chave-xai
            Alibaba chave-alibaba
            Deepseek chave-deepseek
            Meta chave-meta
            AssemblyAI chave-assemblyai
            ElevenLabs chave-elevenlabs
            """.trimIndent()
        )

        assertEquals(8, result.keys.size)
        assertEquals("chave-deepseek", result.keys[ApiKeysImportParser.Service.DEEPSEEK])
        assertEquals("chave-xai", result.keys[ApiKeysImportParser.Service.XAI])
        assertEquals("chave-meta", result.keys[ApiKeysImportParser.Service.METAMUSE])
        assertEquals("chave-elevenlabs", result.keys[ApiKeysImportParser.Service.ELEVENLABS])
        assertEquals("chave-deepgram", result.keys[ApiKeysImportParser.Service.DEEPGRAM])
        assertEquals("chave-assemblyai", result.keys[ApiKeysImportParser.Service.ASSEMBLYAI])
        assertEquals("chave-alibaba", result.keys[ApiKeysImportParser.Service.ALIBABA])
        assertEquals("chave-imei", result.keys[ApiKeysImportParser.Service.IMEI_CHECK])
        assertTrue(result.ignoredLineNumbers.isEmpty())
    }

    @Test
    fun `a primeira palavra e o identificador e o resto e a chave`() {
        val result = ApiKeysImportParser.parse(
            """
            Meta LLM_1_parte_com_separadores
            Alibaba sk-ws-H.DDPDEDE.ZziK.MEYCIQCKK
            """.trimIndent()
        )

        assertEquals(
            "LLM_1_parte_com_separadores",
            result.keys[ApiKeysImportParser.Service.METAMUSE]
        )
        assertEquals(
            "sk-ws-H.DDPDEDE.ZziK.MEYCIQCKK",
            result.keys[ApiKeysImportParser.Service.ALIBABA]
        )
    }

    @Test
    fun `aceita rotulos com separador diferente do espaco`() {
        val result = ApiKeysImportParser.parse(
            """
            Deepgram: chave-1
            xAI=chave-2
            """.trimIndent()
        )

        assertEquals("chave-1", result.keys[ApiKeysImportParser.Service.DEEPGRAM])
        assertEquals("chave-2", result.keys[ApiKeysImportParser.Service.XAI])
    }

    @Test
    fun `mapeia alibaba fun asr qwen`() {
        val result = ApiKeysImportParser.parse(
            """
            Alibaba Fun ASR/Qwen alibaba-key-1
            alibaba alibaba-key-2
            """.trimIndent()
        )

        assertEquals("alibaba-key-2", result.keys[ApiKeysImportParser.Service.ALIBABA])
    }
}
