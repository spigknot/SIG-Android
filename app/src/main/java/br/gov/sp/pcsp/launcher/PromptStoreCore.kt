package br.gov.sp.pcsp.launcher

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Properties

/**
 * Slot de prompt da ferramenta Ocorrência: liga o arquivo ao rótulo mostrado
 * na tela PROMPTS.
 *
 * São os quatro únicos slots gerenciados pela tela (histórico e oitiva, sistema
 * e usuário). Os prompts de partes e de qualificação continuam no formato
 * legado na raiz da pasta e ficam fora deste escopo.
 */
enum class PromptSlot(val file: String, val label: String) {
    HISTORY_SYSTEM("historico_system.txt", "Histórico · sistema"),
    HISTORY_USER("historico_user.txt", "Histórico · usuário"),
    STATEMENT_SYSTEM("oitiva_system.txt", "Oitiva · sistema"),
    STATEMENT_USER("oitiva_user.txt", "Oitiva · usuário");

    /** Id da pasta/entrada (nome do arquivo sem `.txt`). */
    val id: String
        get() = file.substringBeforeLast(".")

    /** `true` nos slots de prompt de sistema (o seletor System/User da tela). */
    val isSystem: Boolean
        get() = this == HISTORY_SYSTEM || this == STATEMENT_SYSTEM
}

/** Id do prompt padrão do app: o que a ferramenta Ocorrência usa até o usuário trocar. */
const val PROMPT_DEFAULT_ID = "padrao"

/** URL público do bucket `prompts` no Cloudflare R2 (fonte: pasta `prompts/` do SIG Windows). */
const val R2_PROMPTS_BASE_URL = "https://pub-916eee09ee6c4c20ad6a51523e965071.r2.dev"

/** Único objeto que o botão "baixar prompts atualizados" busca: ignora a subpasta e os demais prompts. */
val R2_PROMPT_KEYS: List<String> = PromptSlot.values().map { it.file }

/** Marcador que a requisição de histórico preenche com a transcrição. */
const val HISTORY_TRANSCRIPT_MARKER = "{{conteudo_caixa_transcricao}}"

/** Marcador que a requisição de oitiva preenche com o histórico/material. */
const val STATEMENT_HISTORY_MARKER = "{{{conteudo_caixa_historico}}}"

/** Marcador anterior, ainda aceito na leitura do prompt da oitiva. */
const val STATEMENT_HISTORY_LEGACY_MARKER = "{{{INSERIR_AQUI_O_CONTEUDO_DA_CAIXA_DE_TEXTO_DO_HISTORICO}}}"

/** Nome da parte selecionada no prompt da oitiva. */
const val STATEMENT_NAME_MARKER = "{{NOME_SELECIONADO}}"

/** Teto de um prompt: os maiores hoje têm ~7 KiB, então 64 KiB é folga generosa. */
const val MAX_PROMPT_BYTES = 64 * 1024

/** Uma linha da lista da tela PROMPTS. */
data class PromptEntry(val slot: PromptSlot, val id: String, val active: Boolean) {
    /** Só o prompt padrão é protegido contra sobrescrita. */
    val isDefault: Boolean
        get() = id == PROMPT_DEFAULT_ID

    /** Rótulo da linha: slot + id. */
    val label: String
        get() = "${slot.label} — ${if (isDefault) "Padrão" else id}"

    /**
     * Rótulo da opção na lista da seção: `Padrão` para o prompt do app e, para
     * os customizados, o nome do arquivo sem extensão truncado em 10
     * caracteres com `"..."` quando passar disso (nunca com `"..."` se couber).
     */
    val optionLabel: String
        get() = when {
            isDefault -> "Padrão"
            id.length > 10 -> id.take(10) + "..."
            else -> id
        }
}

/** Preenche o prompt de usuário do histórico com a transcrição (marcador do slot). */
fun fillHistoryUserPrompt(prompt: String, transcription: String): String =
    prompt.replace(HISTORY_TRANSCRIPT_MARKER, transcription.trim()).trim()

/** Preenche o prompt de usuário da oitiva com o nome da parte e o material. */
fun fillStatementUserPrompt(prompt: String, selectedName: String?, material: String): String =
    prompt
        .replace(STATEMENT_NAME_MARKER, selectedName?.trim().orEmpty())
        .replace(STATEMENT_HISTORY_MARKER, material.trim())
        // Aceita também o marcador anterior, para um prompt externo mais antigo
        // continuar funcionando.
        .replace(STATEMENT_HISTORY_LEGACY_MARKER, material.trim())
        .trim()

/**
 * Núcleo dos prompts editáveis: layout da pasta `SIG/Prompts`, migração dos
 * arquivos legados, resolução do prompt ativo por slot, gravação de
 * customizados e validação do padrão baixado do R2.
 *
 * Seam sem Android (só `java.io`/`java.util`): o `PromptTemplateStore` cuida do
 * Environment e dos assets, a tela cuida da UI — a regra testável mora aqui
 * (`PromptStoreCoreTest`).
 *
 * Layout:
 * ```
 * padrao/<arquivo>        padrão do app (seed dos assets; trocado pelo R2)
 * custom/<slot>/<id>.txt  prompts do usuário
 * ativo.properties        qual id está em uso em cada slot
 * ```
 */
class PromptStoreCore(
    private val root: File,
    private val assetReader: (String) -> String?,
) {
    private val padraoDir: File
        get() = File(root, "padrao")

    private val activeFile: File
        get() = File(root, "ativo.properties")

    private fun customDir(slot: PromptSlot): File = File(File(root, "custom"), slot.id)

    private fun customFile(slot: PromptSlot, id: String): File = File(customDir(slot), "${sanitizeId(id)}.txt")

    // ── layout e migração ────────────────────────────────────────────────

    /** Cria o layout, semeia o padrão a partir dos assets e migra o legado da raiz. Idempotente. */
    @Synchronized
    fun ensureLayout() {
        padraoDir.mkdirs()
        File(root, "custom").mkdirs()
        val actives = readActives()
        var persist = false
        for (slot in PromptSlot.values()) {
            customDir(slot).mkdirs()
            val assetText = runCatching { assetReader(slot.file) }.getOrNull()
            val padraoFile = File(padraoDir, slot.file)
            if (assetText != null && !padraoFile.isFile) {
                writeAtomically(padraoFile, assetText)
            }
            val legacy = File(root, slot.file)
            if (legacy.isFile) {
                val legacyText = runCatching { legacy.readText(Charsets.UTF_8) }.getOrDefault("")
                if (assetText != null && legacyText.trim() == assetText.trim()) {
                    legacy.delete()
                } else {
                    // Conteúdo editado pelo usuário: vira customizado e continua em uso
                    // (o nome recebe sufixo se já existir um custom com esse id).
                    val id = freeId(slot, slot.id)
                    writeAtomically(customFile(slot, id), legacyText)
                    actives[slot.file] = id
                    legacy.delete()
                    persist = true
                }
            }
            val current = actives[slot.file]
            if (current == null || (current != PROMPT_DEFAULT_ID && !customFile(slot, current).isFile)) {
                actives[slot.file] = PROMPT_DEFAULT_ID
                persist = true
            }
        }
        if (persist) writeActives(actives)
        ensureReadme()
    }

    // ── resolução ───────────────────────────────────────────────────────

    /** Id em uso no slot (`padrao` quando nada foi escolhido). */
    fun activeId(slot: PromptSlot): String = readActives()[slot.file] ?: PROMPT_DEFAULT_ID

    /** Conteúdo do padrão do slot (pasta `padrao/`, com fallback no asset embutido). */
    fun readPadrao(slot: PromptSlot): String =
        readFile(File(padraoDir, slot.file)) ?: runCatching { assetReader(slot.file) }.getOrNull() ?: ""

    /** Conteúdo de um prompt pelo id (`padrao` resolve para a pasta `padrao/`). */
    fun read(slot: PromptSlot, id: String): String =
        if (id == PROMPT_DEFAULT_ID) readPadrao(slot) else readFile(customFile(slot, id)) ?: ""

    /** Conteúdo do prompt em uso — é o que a ferramenta Ocorrência envia. */
    fun readActive(slot: PromptSlot): String = read(slot, activeId(slot)).ifBlank { readPadrao(slot) }

    // ── montagem das requisições (é este texto que sai na rede) ─────────

    /** Prompt de sistema do histórico em uso. */
    fun renderHistorySystem(): String = readActive(PromptSlot.HISTORY_SYSTEM)

    /** Prompt de usuário do histórico em uso, com a transcrição inserida. */
    fun renderHistoryUser(transcription: String): String =
        fillHistoryUserPrompt(readActive(PromptSlot.HISTORY_USER), transcription)

    /** Prompt de sistema da oitiva em uso. */
    fun renderStatementSystem(): String = readActive(PromptSlot.STATEMENT_SYSTEM)

    /** Prompt de usuário da oitiva em uso, com nome da parte e material inseridos. */
    fun renderStatementUser(selectedName: String?, material: String): String =
        fillStatementUserPrompt(readActive(PromptSlot.STATEMENT_USER), selectedName, material)

    /** Todas as linhas da tela: de cada slot, o `Padrão` primeiro e depois os customizados. */
    fun entries(): List<PromptEntry> = PromptSlot.values().flatMap { slot ->
        val active = activeId(slot)
        val customs = (customDir(slot).listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.equals("txt", ignoreCase = true) }
            .map { it.nameWithoutExtension }
            .filter { it.isNotBlank() }
            .sorted()
        listOf(PromptEntry(slot, PROMPT_DEFAULT_ID, active == PROMPT_DEFAULT_ID)) +
            customs.map { PromptEntry(slot, it, active == it) }
    }

    // ── escrita ─────────────────────────────────────────────────────────

    /** Marca qual prompt o slot passa a usar. */
    @Synchronized
    fun setActive(slot: PromptSlot, id: String): String? {
        if (sanitizeId(id).isEmpty()) return "Prompt sem nome."
        if (id != PROMPT_DEFAULT_ID && !customFile(slot, id).isFile) {
            return "O prompt '$id' não existe em ${slot.label}."
        }
        val actives = readActives()
        actives[slot.file] = id
        writeActives(actives)
        return null
    }

    /**
     * Grava um customizado. `overwrite=false` recusa id já existente (usado pelo
     * SALVAR COMO); a importação de arquivo usa `overwrite=true`.
     */
    @Synchronized
    fun saveCustom(slot: PromptSlot, rawId: String, text: String, overwrite: Boolean): String? {
        val id = sanitizeId(rawId)
        if (id.isEmpty()) return "Dê um nome válido para o prompt."
        if (id.equals(PROMPT_DEFAULT_ID, ignoreCase = true)) {
            return "O prompt Padrão não pode ser sobrescrito; use SALVAR COMO com outro nome."
        }
        validate(slot, text)?.let { return it }
        val target = customFile(slot, id)
        if (target.isFile && !overwrite) return "Já existe um prompt chamado '$id' em ${slot.label}."
        writeAtomically(target, text)
        return null
    }

    /**
     * Troca o padrão pelos prompts baixados do R2. **All-or-nothing**: se algum
     * reprovar na validação nada é gravado, então um download parcial nunca
     * deixa o padrão inconsistente. Os customizados não são tocados.
     */
    @Synchronized
    fun applyDefaults(files: Map<String, String>): String? {
        val missing = PromptSlot.values().map { it.file }.filter { it !in files }
        if (missing.isNotEmpty()) return "download incompleto: falta ${missing.joinToString(", ")}"
        for (slot in PromptSlot.values()) {
            validate(slot, files.getValue(slot.file))?.let { return "${slot.label}: $it" }
        }
        padraoDir.mkdirs()
        for (slot in PromptSlot.values()) {
            writeAtomically(File(padraoDir, slot.file), files.getValue(slot.file))
        }
        return null
    }

    /**
     * Prompts baixados que diferem do padrão atual (lista vazia = já está
     * atualizado). É o que permite ao botão de atualizar dizer "nada novo"
     * em vez de regravar o mesmo conteúdo.
     */
    fun changedDefaults(files: Map<String, String>): List<String> = PromptSlot.values()
        .map { it.file }
        .filter { key ->
            val baixado = files[key] ?: return@filter true
            baixado != readPadrao(slotFor(key))
        }

    private fun slotFor(file: String): PromptSlot =
        PromptSlot.values().firstOrNull { it.file == file } ?: PromptSlot.HISTORY_SYSTEM

    /**
     * Remove um customizado. Recusa o `Padrão` (intocável, mesma regra do
     * `Salvar`) e ids inexistentes; se o excluído estava em uso, o slot volta
     * a usar o `Padrão`.
     */
    @Synchronized
    fun deleteCustom(slot: PromptSlot, id: String): String? {
        val normalized = sanitizeId(id)
        if (normalized.isEmpty()) return "Prompt sem nome."
        if (id == PROMPT_DEFAULT_ID || normalized == PROMPT_DEFAULT_ID) {
            return "O prompt Padrão não pode ser excluído."
        }
        val target = customFile(slot, id)
        if (!target.isFile) return "O prompt '$normalized' não existe em ${slot.label}."
        if (!target.delete()) return "Não consegui excluir o arquivo do prompt '$normalized'."
        val actives = readActives()
        if (actives[slot.file] == normalized) {
            actives[slot.file] = PROMPT_DEFAULT_ID
            writeActives(actives)
        }
        return null
    }

    /** Importa um `.txt` escolhido pelo usuário: grava no slot informado e deixa em uso. */
    @Synchronized
    fun importPrompt(slot: PromptSlot, filename: String, text: String): String? {
        val id = sanitizeId(filename.substringAfterLast("/").substringAfterLast("\\").substringBeforeLast("."))
            .ifEmpty { "importado" }
        if (id.equals(PROMPT_DEFAULT_ID, ignoreCase = true)) {
            return "Renomeie o arquivo: 'padrao' é o id do prompt padrão."
        }
        validate(slot, text)?.let { return it }
        writeAtomically(customFile(slot, id), text)
        return setActive(slot, id)
    }

    // ── validação ───────────────────────────────────────────────────────

    /** `null` quando o texto serve para o slot; senão o motivo (mensagem para o usuário). */
    fun validate(slot: PromptSlot, text: String): String? {
        val value = text.trim()
        if (value.isEmpty()) return "o prompt está vazio"
        if (text.toByteArray(Charsets.UTF_8).size > MAX_PROMPT_BYTES) {
            return "o prompt passa de ${MAX_PROMPT_BYTES / 1024} KiB"
        }
        when (slot) {
            PromptSlot.HISTORY_USER ->
                if (!value.contains(HISTORY_TRANSCRIPT_MARKER)) {
                    return "falta o marcador $HISTORY_TRANSCRIPT_MARKER (a transcrição não seria inserida)"
                }
            PromptSlot.STATEMENT_USER ->
                if (!value.contains(STATEMENT_HISTORY_MARKER) && !value.contains(STATEMENT_HISTORY_LEGACY_MARKER)) {
                    return "falta o marcador de histórico da oitiva"
                }
            else -> Unit
        }
        return null
    }

    /** Decodifica bytes como UTF-8 estrito; `null` quando não é UTF-8 válido. */
    fun decodeStrict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Slot de um arquivo importado: nome do arquivo, depois marcador do conteúdo, depois heurística. */
    fun inferSlot(filename: String, text: String): PromptSlot? {
        val base = normalizeName(filename)
        PromptSlot.values().firstOrNull { it.id == base }?.let { return it }
        if (text.contains(HISTORY_TRANSCRIPT_MARKER)) return PromptSlot.HISTORY_USER
        if (text.contains(STATEMENT_HISTORY_MARKER) || text.contains(STATEMENT_HISTORY_LEGACY_MARKER)) {
            return PromptSlot.STATEMENT_USER
        }
        val wantsUser = base.contains("user") || base.contains("usuario")
        val wantsStatement = base.contains("oitiva") || base.contains("statement") || base.contains("declarac")
        val wantsHistory = base.contains("historico") || base.contains("history")
        return when {
            wantsHistory && wantsUser -> PromptSlot.HISTORY_USER
            wantsHistory -> PromptSlot.HISTORY_SYSTEM
            wantsStatement && wantsUser -> PromptSlot.STATEMENT_USER
            wantsStatement -> PromptSlot.STATEMENT_SYSTEM
            else -> null
        }
    }

    /** Próximo id livre para o SALVAR COMO (`base`, `base_2`, `base_3`, …). */
    fun suggestId(slot: PromptSlot, base: String): String {
        val start = sanitizeId(base).ifEmpty { "prompt" }
        if (!File(customDir(slot), "$start.txt").isFile) return start
        var attempt = 2
        while (File(customDir(slot), "${start}_$attempt.txt").isFile) attempt++
        return "${start}_$attempt"
    }

    /** Normaliza um nome de arquivo/id para algo seguro como nome de arquivo. */
    fun sanitizeId(raw: String): String = normalizeName(raw).take(40)

    // ── internos ────────────────────────────────────────────────────────

    private fun normalizeName(raw: String): String =
        raw.trim()
            .substringAfterLast("/").substringAfterLast("\\")
            .substringBeforeLast(".")
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_', '-')

    private fun freeId(slot: PromptSlot, desired: String): String {
        val start = sanitizeId(desired).ifEmpty { slot.id }
        if (!File(customDir(slot), "$start.txt").isFile) return start
        var attempt = 2
        while (File(customDir(slot), "${start}_$attempt.txt").isFile) attempt++
        return "${start}_$attempt"
    }

    private fun readFile(file: File): String? = runCatching {
        file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun readActives(): MutableMap<String, String> {
        val props = Properties()
        runCatching {
            activeFile.takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
        }
        val values = mutableMapOf<String, String>()
        for (slot in PromptSlot.values()) {
            values[slot.file] = props.getProperty(slot.file)?.takeIf { it.isNotBlank() } ?: PROMPT_DEFAULT_ID
        }
        return values
    }

    private fun writeActives(values: Map<String, String>) {
        val props = Properties()
        values.forEach { (key, value) -> props.setProperty(key, value) }
        activeFile.parentFile?.mkdirs()
        runCatching { activeFile.outputStream().use { props.store(it, "Prompt em uso por slot") } }
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            temp.writeText(text, Charsets.UTF_8)
            if (temp.renameTo(target)) return
        }
        runCatching { target.writeText(text, Charsets.UTF_8) }
        temp.delete()
    }

    private fun ensureReadme() {
        val file = File(root, "LEIA-ME.txt")
        val current = runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else "" }.getOrDefault("")
        if (current.contains("padrao/")) return
        writeAtomically(file, README)
    }

    private companion object {
        val README = """
            PROMPTS DO SIG

            padrao/<arquivo>      Padrão do app (é o que a ferramenta Ocorrência usa até
                                  você trocar). Atualizado pelo botão "baixar prompts
                                  atualizados" na tela Configurações > PROMPTS; a tela
                                  nunca sobrescreve este conteúdo por edição.
            custom/<slot>/<id>.txt Seus prompts (edições e arquivos importados).
            ativo.properties      Qual prompt está em uso em cada slot.

            Os quatro prompts da ferramenta Ocorrência:
              historico_system.txt / historico_user.txt  botão Histórico.
                O de usuário usa {{conteudo_caixa_transcricao}}.
              oitiva_system.txt / oitiva_user.txt        botão Oitiva.
                O de usuário usa {{{conteudo_caixa_historico}}} e aceita
                {{NOME_SELECIONADO}} por compatibilidade.

            Prompts fora da tela PROMPTS (formato antigo, nesta pasta):
              partes_system.txt               extração de partes (sistema).
              partes_user_botao_historico.txt junto com Histórico; usa
                                              {{{conteudo_caixa_transcricao}}}.
              partes_user_botao_detectar.txt  botão Detectar; usa
                                              {{{conteudo_caixa_historico}}}.
              qualificacao_system.txt         qualificação (sistema).
              qualificacao_user.txt           aceita {{{TEXTO_DA_CAIXA_AQUI}}},
                                              {{FIELD_IDS}} e {{RAW_TEXT}}.

            Tudo é relido a cada requisição: edite pela tela Configurações >
            PROMPTS ou direto na pasta, sem recompilar o aplicativo.
        """.trimIndent()
    }
}
