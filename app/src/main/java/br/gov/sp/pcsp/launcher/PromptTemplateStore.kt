package br.gov.sp.pcsp.launcher

import android.os.Environment
import java.io.File

/**
 * Acesso aos prompts do SIG.
 *
 * Os quatro prompts da ferramenta Ocorrência (histórico e oitiva, sistema e
 * usuário) são resolvidos pelo [PromptStoreCore] dentro de `SIG/Prompts`:
 * `padrao/` é o padrão do app (seed dos assets, atualizável pelo R2),
 * `custom/` são os prompts do usuário e `ativo.properties` diz qual está em
 * uso em cada slot. Os prompts de partes e de qualificação seguem no formato
 * legado na raiz da pasta, sem mudança de comportamento.
 *
 * Tudo é lido a cada requisição: editar pela tela Configurações > PROMPTS ou
 * direto na pasta vale sem recompilar o aplicativo.
 */
object PromptTemplateStore {

    private val legacyFiles = listOf(
        "partes_system.txt",
        "partes_user_botao_historico.txt",
        "partes_user_botao_detectar.txt",
        "qualificacao_system.txt",
        "qualificacao_user.txt",
    )

    private val core: PromptStoreCore by lazy {
        PromptStoreCore(promptDirectory()) { fileName -> bundledPrompt(fileName) }
    }

    /** Cria o layout, semeia o padrão pelos assets e migra arquivos legados. Idempotente. */
    fun ensureDefaults() {
        runCatching {
            core.ensureLayout()
            ensureLegacyFiles()
        }
    }

    // ── slots da ferramenta Ocorrência (histórico e oitiva) ─────────────

    fun historySystemPrompt(): String = runCatching {
        ensureDefaults()
        core.renderHistorySystem()
    }.getOrElse { bundledPrompt(PromptSlot.HISTORY_SYSTEM.file) }

    fun historyUserPrompt(transcription: String): String = runCatching {
        ensureDefaults()
        core.renderHistoryUser(transcription)
    }.getOrElse { fillHistoryUserPrompt(bundledPrompt(PromptSlot.HISTORY_USER.file), transcription) }

    fun partsSystemPrompt(): String = readLegacy("partes_system.txt")

    /** Mantido de propósito (decisão do dono, 11/09/2026): variante a partir da transcrição (o fluxo atual usa FromHistory). Não remover em limpezas. */
    fun partsUserPromptFromTranscription(transcription: String): String =
        readLegacy("partes_user_botao_historico.txt")
            .replace("{{{conteudo_caixa_transcricao}}}", transcription.trim())
            .trim()

    fun partsUserPromptFromHistory(history: String): String =
        readLegacy("partes_user_botao_detectar.txt")
            .replace(STATEMENT_HISTORY_MARKER, history.trim())
            .trim()

    fun statementSystemPrompt(): String = runCatching {
        ensureDefaults()
        core.renderStatementSystem()
    }.getOrElse { bundledPrompt(PromptSlot.STATEMENT_SYSTEM.file) }

    fun statementUserPrompt(selectedName: String?, material: String): String = runCatching {
        ensureDefaults()
        core.renderStatementUser(selectedName, material)
    }.getOrElse {
        fillStatementUserPrompt(bundledPrompt(PromptSlot.STATEMENT_USER.file), selectedName, material)
    }

    // ── API da tela Configurações > PROMPTS ─────────────────────────────

    /** Linhas da lista: em cada slot, o `Padrão` e depois os customizados. */
    fun entries(): List<PromptEntry> {
        ensureDefaults()
        return core.entries()
    }

    /** Texto de um prompt pelo slot e id (para carregar na caixa de edição). */
    fun readEntry(slot: PromptSlot, id: String): String = runCatching {
        ensureDefaults()
        core.read(slot, id)
    }.getOrDefault("")

    /** Id em uso no slot. */
    fun activeId(slot: PromptSlot): String = runCatching {
        ensureDefaults()
        core.activeId(slot)
    }.getOrDefault(PROMPT_DEFAULT_ID)

    /** Passa a usar outro prompt no slot; `null` quando deu certo. */
    fun setActive(slot: PromptSlot, id: String): String? = runCatching {
        ensureDefaults()
        core.setActive(slot, id)
    }.getOrElse { it.message ?: "Não consegui trocar o prompt." }

    /** Grava um customizado; `overwrite=false` recusa id já existente. */
    fun saveCustom(slot: PromptSlot, id: String, text: String, overwrite: Boolean): String? = runCatching {
        ensureDefaults()
        core.saveCustom(slot, id, text, overwrite)
    }.getOrElse { it.message ?: "Não consegui salvar o prompt." }

    /** Próximo id livre para o SALVAR COMO. */
    fun suggestId(slot: PromptSlot, base: String): String = runCatching {
        core.suggestId(slot, base)
    }.getOrDefault("prompt")

    /** Id normalizado de um nome digitado/extraído de arquivo (mesma regra do núcleo). */
    fun sanitizeId(raw: String): String = runCatching { core.sanitizeId(raw) }.getOrDefault("")

    /** Exclui um customizado (recusa o `Padrão`); `null` quando deu certo. */
    fun deletePrompt(slot: PromptSlot, id: String): String? = runCatching {
        ensureDefaults()
        core.deleteCustom(slot, id)
    }.getOrElse { it.message ?: "Não consegui excluir o prompt." }

    /** Importa um `.txt` escolhido pelo usuário e deixa o resultado em uso. */
    fun importPrompt(slot: PromptSlot, filename: String, text: String): String? = runCatching {
        ensureDefaults()
        core.importPrompt(slot, filename, text)
    }.getOrElse { it.message ?: "Não consegui importar o prompt." }

    /** Slot de um arquivo importado, ou `null` quando não dá para deduzir. */
    fun inferSlot(filename: String, text: String): PromptSlot? =
        runCatching { core.inferSlot(filename, text) }.getOrNull()

    /** Troca o padrão pelos prompts baixados do R2 (all-or-nothing). */
    fun applyDefaults(files: Map<String, String>): String? = runCatching {
        ensureDefaults()
        core.applyDefaults(files)
    }.getOrElse { it.message ?: "Não consegui gravar os prompts baixados." }

    /** Prompts baixados que diferem do padrão atual; vazio = já está atualizado. */
    fun defaultsChanged(files: Map<String, String>): List<String> = runCatching {
        ensureDefaults()
        core.changedDefaults(files)
    }.getOrDefault(files.keys.sorted())

    /** `null` quando o texto serve para o slot; senão o motivo. */
    fun validate(slot: PromptSlot, text: String): String? =
        runCatching { core.validate(slot, text) }.getOrNull()

    /** UTF-8 estrito do corpo baixado; `null` quando não é UTF-8 válido. */
    fun decodeStrict(bytes: ByteArray): String? = runCatching { core.decodeStrict(bytes) }.getOrNull()

    fun promptDirectory(): File {
        return File(File(Environment.getExternalStorageDirectory(), "SIG"), "Prompts")
    }

    private fun readLegacy(fileName: String): String = runCatching {
        ensureLegacyFiles()
        File(promptDirectory(), fileName)
            .takeIf { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: bundledPrompt(fileName)
    }.getOrElse { bundledPrompt(fileName) }

    private fun ensureLegacyFiles() {
        val dir = promptDirectory().apply { mkdirs() }
        legacyFiles.forEach { fileName ->
            val target = File(dir, fileName)
            if (!target.exists()) copyBundledPrompt(fileName, target)
        }
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

    // O LEIA-ME.txt da pasta é gerado pelo PromptStoreCore.
}
