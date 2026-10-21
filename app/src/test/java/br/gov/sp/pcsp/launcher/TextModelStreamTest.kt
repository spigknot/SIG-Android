package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

class TextModelStreamTest {
    private fun read(text: String, type: String = "text/event-stream", updates: MutableList<String> = mutableListOf()): String =
        TextModelStream.read(text.reader().buffered(), type, updates::add) { JSONObject(it).getString("output_text") }

    @Test fun chatKeepsSpacesAndIgnoresReasoning() {
        val updates = mutableListOf<String>()
        val text = """
            : ping

            data: {"choices":[{"delta":{"reasoning_content":"interno"}}]}

            data: {"choices":[{"delta":{"content":"João"}}]}

            data: {"choices":[{"delta":{"content":" da Silva"}}]}

            data: [DONE]

        """.trimIndent()
        assertEquals("João da Silva", read(text, updates = updates))
        assertEquals(listOf("João", "João da Silva"), updates)
    }

    @Test fun responsesDoesNotDuplicateFinalText() {
        val text = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Oitiva\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"output_text\":\"Oitiva\"}}\n\n"
        assertEquals("Oitiva", read(text))
    }

    @Test fun ndjsonWorks() {
        assertEquals("Um texto", read("{\"response\":\"Um \",\"done\":false}\n{\"response\":\"texto\",\"done\":true}\n", "application/x-ndjson"))
    }

    @Test fun interruptedOrFailedStreamCannotSucceed() {
        for (tail in listOf("", "data: {\"type\":\"error\",\"error\":{\"message\":\"falhou\"}}\n\n",
            "data: {\"type\":\"response.incomplete\"}\n\n", "data: INVALID\n\n")) {
            val updates = mutableListOf<String>()
            assertThrows(IllegalStateException::class.java) {
                read("data: {\"choices\":[{\"delta\":{\"content\":\"parcial\"}}]}\n\n$tail", updates = updates)
            }
            assertEquals(listOf("parcial"), updates)
        }
    }

    @Test fun splitUtf8CharactersRemainIntact() {
        val data = "data: {\"choices\":[{\"delta\":{\"content\":\"Ação 😀\"}}]}\n\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8)
        val input = object : ByteArrayInputStream(data) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
        }
        assertEquals("Ação 😀", TextModelStream.read(InputStreamReader(input, Charsets.UTF_8).buffered(), "text/event-stream", {}) { "" })
    }

    @Test fun modelIdsAndLabelsFollowServerChanges() {
        val previous = ModelServerStore.localModelId()
        try {
            for (id in listOf("/models/Gemma4-E4B-it-Q4_K_M.gguf", "outro-modelo-v2")) {
                ModelServerStore.updateLocalModel(LocalTextModel.parseId("""{"data":[{"id":"$id"}]}"""))
                val config = ModelServerStore.configForParts(ModelServerStore.LOCAL_TEXT_SERVER_NAME, "none")
                assertEquals(id, config.modelName)
                assertEquals(LocalTextModel.label(id), config.displayName)
                assertEquals(LocalTextModel.label(id), RequestModelLabel.from(config))
            }
            assertEquals("servidor (Gemma4-E4B-it-Q4_K_M)", LocalTextModel.label("/models/Gemma4-E4B-it-Q4_K_M.gguf"))
            assertEquals("servidor (local)", LocalTextModel.label(""))
            assertThrows(IllegalStateException::class.java) { LocalTextModel.parseId("{\"data\":[]}") }
        } finally {
            ModelServerStore.updateLocalModel(previous)
        }
    }
}
