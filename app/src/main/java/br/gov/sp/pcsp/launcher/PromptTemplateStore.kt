package br.gov.sp.pcsp.launcher

import android.os.Environment
import java.io.File

object PromptTemplateStore {

    private const val PERSON_INSTRUCTION_MARKER = "{{INSTRUCAO_PESSOA}}"
    private const val SELECTED_NAME_MARKER = "{{NOME_SELECIONADO}}"

    fun ensureDefaults() {
        runCatching {
            val dir = promptDirectory().apply { mkdirs() }
            createIfMissing(File(dir, "historico.txt"), TranscriptAssistantClient.DEFAULT_HISTORY_PROMPT)
            createIfMissing(File(dir, "partes.txt"), TranscriptAssistantClient.DEFAULT_PARTS_PROMPT)
            createIfMissing(File(dir, "oitiva.txt"), TranscriptAssistantClient.DEFAULT_STATEMENT_TEMPLATE)
            createIfMissing(File(dir, "oitiva_parte.txt"), DEFAULT_STATEMENT_PERSON_INSTRUCTION)
            createIfMissing(File(dir, "LEIA-ME.txt"), README)
        }
    }

    fun historyPrompt(): String {
        return readPrompt("historico.txt", TranscriptAssistantClient.DEFAULT_HISTORY_PROMPT)
    }

    fun partsPrompt(): String {
        return readPrompt("partes.txt", TranscriptAssistantClient.DEFAULT_PARTS_PROMPT)
    }

    fun statementPrompt(selectedName: String?): String {
        val template = readPrompt("oitiva.txt", TranscriptAssistantClient.DEFAULT_STATEMENT_TEMPLATE)
        val name = selectedName?.trim().orEmpty()
        val instruction = if (name.isBlank()) {
            ""
        } else {
            readPrompt("oitiva_parte.txt", DEFAULT_STATEMENT_PERSON_INSTRUCTION)
                .replace(SELECTED_NAME_MARKER, name)
        }
        return template
            .replace(PERSON_INSTRUCTION_MARKER, instruction)
            .replace(SELECTED_NAME_MARKER, name)
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()
    }

    fun promptDirectory(): File {
        return File(File(Environment.getExternalStorageDirectory(), "SIG"), "Prompts")
    }

    private fun readPrompt(fileName: String, defaultValue: String): String {
        ensureDefaults()
        return runCatching {
            File(promptDirectory(), fileName)
                .takeIf { it.exists() }
                ?.readText(Charsets.UTF_8)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: defaultValue
        }.getOrDefault(defaultValue)
    }

    private fun createIfMissing(file: File, content: String) {
        if (!file.exists()) file.writeText(content.trim() + "\n", Charsets.UTF_8)
    }

    private val README = """
        MODELOS DE PROMPT DO SIG

        Estes arquivos são lidos novamente a cada clique. Não é necessário reiniciar o aplicativo depois de editá-los.

        historico.txt
        Texto de sistema usado pelo botão Histórico.

        partes.txt
        Texto de sistema usado pela requisição paralela que identifica os nomes.
        A resposta deve continuar sendo uma lista JSON, por exemplo: ["MARIA","JOAO"].

        oitiva.txt
        Texto de sistema usado pelo botão Oitiva.

        oitiva_parte.txt
        Instrução acrescentada ao prompt de oitiva quando um nome estiver selecionado.
        Use {{NOME_SELECIONADO}} onde o nome escolhido deve aparecer.

        Marcadores disponíveis em oitiva.txt:
        {{INSTRUCAO_PESSOA}}
        Vira a frase completa que define o ponto de vista da pessoa selecionada. Se nenhum nome estiver selecionado, o marcador desaparece.

        {{NOME_SELECIONADO}}
        Vira somente o nome atualmente selecionado. Se nenhum nome estiver selecionado, o marcador desaparece.

        A transcrição ou o histórico exibido na caixa não precisa ser colocado nestes arquivos. O app o envia separadamente como mensagem do usuário.

        Se um arquivo for apagado, o app o recriará com o modelo padrão. Se ficar vazio, o modelo padrão será usado naquela requisição.
    """.trimIndent()

    private const val DEFAULT_STATEMENT_PERSON_INSTRUCTION =
        "A oitiva conterá os fatos do ponto de vista de {{NOME_SELECIONADO}}, colocando na oitiva dele(a) as coisas que ele(a) presenciou e sabe, pois essa será a oitiva dele(a)."
}
