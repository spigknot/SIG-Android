package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import java.io.BufferedReader

/** Decodifica respostas incrementais de texto sem rede, Android ou estado de tela. */
internal object TextModelStream {
    fun read(
        reader: BufferedReader,
        contentType: String,
        onPartial: (String) -> Unit,
        extractJson: (String) -> String,
    ): String {
        val output = StringBuilder()
        val eventData = mutableListOf<String>()
        val ndjson = contentType.contains("ndjson", ignoreCase = true)
        var completed = false
        var terminal = false

        fun event(data: String) {
            if (data.trim() == "[DONE]") {
                completed = true
                terminal = true
                return
            }
            val root = try {
                JSONObject(data)
            } catch (error: Exception) {
                throw IllegalStateException("O servidor enviou um evento de texto inválido.", error)
            }
            val type = root.optString("type")
            if ((!root.isNull("error") && root.has("error")) ||
                type in setOf("error", "response.failed", "response.incomplete")) {
                val detail = root.optJSONObject("error")?.optString("message")
                    ?: root.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() }
                    ?: "Falha na transmissão da resposta do modelo.")
            }
            val delta: String = when {
                type == "response.output_text.delta" -> root.opt("delta") as? String ?: ""
                type == "response.completed" -> {
                    completed = true
                    terminal = true
                    if (output.isEmpty()) extractJson(root.getJSONObject("response").toString()) else ""
                }
                root.has("choices") -> {
                    val choices = root.optJSONArray("choices")
                    val choice = (0 until (choices?.length() ?: 0))
                        .mapNotNull { choices?.optJSONObject(it) }
                        .firstOrNull { it.optInt("index", 0) == 0 }
                    if (choice != null && !choice.isNull("finish_reason")) completed = true
                    choice?.optJSONObject("delta")?.opt("content") as? String ?: ""
                }
                root.has("done") -> {
                    completed = root.optBoolean("done")
                    terminal = completed
                    root.opt("response") as? String ?: ""
                }
                else -> ""
            }
            if (delta.isNotEmpty()) {
                output.append(delta)
                onPartial(output.toString())
            }
        }

        while (!terminal) {
            val line = reader.readLine() ?: break
            if (ndjson) {
                if (line.isNotBlank()) event(line)
            } else if (line.startsWith("data:")) {
                eventData += line.substring(5).removePrefix(" ")
            } else if (line.isEmpty() && eventData.isNotEmpty()) {
                event(eventData.joinToString("\n"))
                eventData.clear()
            }
        }
        if (!terminal && eventData.isNotEmpty()) event(eventData.joinToString("\n"))
        check(completed) { "A resposta foi interrompida antes de concluir o texto." }
        return output.toString().trim()
    }
}
