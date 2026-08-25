package br.gov.sp.pcsp.launcher

import android.os.Environment
import java.io.File

/**
 * Keeps the editable prompt files in SIG/Prompts in sync with the prompt
 * assets shipped by the Windows and Android applications.
 */
object PromptTemplateStore {

    private const val SELECTED_NAME_MARKER = "{{NOME_SELECIONADO}}"
    private const val TRANSCRIPT_MARKER = "{{conteudo_caixa_transcricao}}"
    private const val TRANSCRIPT_TRIPLE_MARKER = "{{{conteudo_caixa_transcricao}}}"
    private const val HISTORY_TRIPLE_MARKER = "{{{conteudo_caixa_historico}}}"
    private const val LEGACY_HISTORY_MARKER =
        "{{{INSERIR_AQUI_O_CONTEUDO_DA_CAIXA_DE_TEXTO_DO_HISTORICO}}}"

    private val promptFiles = listOf(
        "historico_system.txt",
        "historico_user.txt",
        "partes_system.txt",
        "partes_user_botao_historico.txt",
        "partes_user_botao_detectar.txt",
        "oitiva_system.txt",
        "oitiva_user.txt",
        "qualificacao_system.txt",
        "qualificacao_user.txt"
    )

    fun ensureDefaults() {
        runCatching {
            val dir = promptDirectory().apply { mkdirs() }
            promptFiles.forEach { fileName ->
                val target = File(dir, fileName)
                if (!target.exists()) copyBundledPrompt(fileName, target)
            }
            createIfMissing(File(dir, "LEIA-ME.txt"), README)
        }
    }

    fun historySystemPrompt(): String = readPrompt("historico_system.txt")

    fun historyUserPrompt(transcription: String): String =
        readPrompt("historico_user.txt")
            .replace(TRANSCRIPT_MARKER, transcription.trim())
            .trim()

    fun partsSystemPrompt(): String = readPrompt("partes_system.txt")

    fun partsUserPromptFromTranscription(transcription: String): String =
        readPrompt("partes_user_botao_historico.txt")
            .replace(TRANSCRIPT_TRIPLE_MARKER, transcription.trim())
            .trim()

    fun partsUserPromptFromHistory(history: String): String =
        readPrompt("partes_user_botao_detectar.txt")
            .replace(HISTORY_TRIPLE_MARKER, history.trim())
            .trim()

    fun statementSystemPrompt(): String = readPrompt("oitiva_system.txt")

    fun statementUserPrompt(selectedName: String?, material: String): String =
        readPrompt("oitiva_user.txt")
            .replace(SELECTED_NAME_MARKER, selectedName?.trim().orEmpty())
            .replace(HISTORY_TRIPLE_MARKER, material.trim())
            .replace(LEGACY_HISTORY_MARKER, material.trim())
            .trim()

    fun qualificationSystemPrompt(): String = readPrompt("qualificacao_system.txt")

    fun qualificationUserPrompt(fieldIds: List<String>, rawText: String): String {
        val knownFields = setOf(
            "nome", "nascimento", "rg", "cpf", "naturalidade", "sexo",
            "estado_civil", "profissao", "altura", "pele", "olhos", "cabelo",
            "pai", "mae", "instrucao", "endereco", "bairro", "cidade", "telefone"
        )
        val extras = fieldIds
            .map(String::trim)
            .filter { it.isNotBlank() && it !in knownFields }
            .distinct()
        val extraSuffix = if (extras.isEmpty()) "" else ", ${extras.joinToString(", ")}"
        val raw = rawText.trim()
        return readPrompt("qualificacao_user.txt")
            .replace(
                "{{{INSERIR_AQUI_OUTROS_DADOS_FORNECIDOS_PELO_USUARIO_SEPARANDO_POR_VIRGULA+ESPAÇO}}}",
                extraSuffix
            )
            .replace("{{{TEXTO_DA_CAIXA_AQUI}}}", raw)
            .replace("{{FIELD_IDS}}", fieldIds.joinToString(", "))
            .replace("{{RAW_TEXT}}", raw)
            .trim()
    }

    // Compatibility helpers for callers that still use the old API.
    fun historyPrompt(): String = historySystemPrompt()
    fun partsPrompt(): String = partsSystemPrompt()
    fun statementPrompt(selectedName: String?): String = statementSystemPrompt()

    fun promptDirectory(): File {
        return File(File(Environment.getExternalStorageDirectory(), "SIG"), "Prompts")
    }

    private fun readPrompt(fileName: String): String {
        ensureDefaults()
        return runCatching {
            File(promptDirectory(), fileName)
                .takeIf { it.isFile }
                ?.readText(Charsets.UTF_8)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: bundledPrompt(fileName)
        }.getOrElse { bundledPrompt(fileName) }
    }

    private fun copyBundledPrompt(fileName: String, target: File) {
        val content = bundledPrompt(fileName)
        if (content.isNotBlank()) target.writeText(content.trim() + "\n", Charsets.UTF_8)
    }

    private fun bundledPrompt(fileName: String): String {
        return runCatching {
            SigApplication.appInstance.assets
                .open("prompts/$fileName")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
                .trim()
        }.getOrDefault("")
    }

    private fun createIfMissing(file: File, content: String) {
        if (!file.exists()) file.writeText(content.trim() + "\n", Charsets.UTF_8)
    }

    private val README = """
        MODELOS DE PROMPT DO SIG

        Estes arquivos são lidos novamente a cada requisição. Edite-os diretamente
        nesta pasta para ajustar os prompts sem recompilar o aplicativo.

        historico_system.txt / historico_user.txt
        Prompt de sistema e prompt do usuário usados pelo botão Histórico.
        O arquivo do usuário usa {{conteudo_caixa_transcricao}}.

        partes_system.txt
        Prompt de sistema da extração de partes.

        partes_user_botao_historico.txt
        Prompt do usuário usado junto com Histórico. Usa {{{conteudo_caixa_transcricao}}}.

        partes_user_botao_detectar.txt
        Prompt do usuário usado pelo botão Detectar. Usa {{{conteudo_caixa_historico}}}.

        oitiva_system.txt / oitiva_user.txt
        Prompt de sistema e prompt do usuário usados pelo botão Oitiva.
        O arquivo do usuário usa {{{conteudo_caixa_historico}}}. O marcador
        {{NOME_SELECIONADO}} continua aceito por compatibilidade.

        qualificacao_system.txt / qualificacao_user.txt
        Prompts da qualificação. O arquivo do usuário aceita os marcadores
        {{{TEXTO_DA_CAIXA_AQUI}}}, {{FIELD_IDS}} e {{RAW_TEXT}}.
    """.trimIndent()
}
