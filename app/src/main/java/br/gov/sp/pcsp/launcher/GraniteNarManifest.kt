package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Manifesto do pacote de modelos: caminho relativo do arquivo → bytes + SHA-256.
 *
 * Exigência da Fase 7 do plano: **"SHA-256 antes da ativação"**. Sem isso, um download
 * truncado, corrompido ou servido errado seria ativado sem nenhuma checagem — e o sintoma
 * apareceria depois, como "o modelo não transcreve", sem pista da causa.
 *
 * ⚠️ **DEFEITO CORRIGIDO (14/09/2026)**: o parser lia apenas a chave `name`, enquanto o
 * manifesto realmente publicado usa **`caminho_relativo`**. Resultado medido contra o objeto
 * publicado (`models/granite/4.1-nar/v2/manifest.json`, 25 arquivos): **mapa vazio em toda
 * circunstância** — a verificação de hash existia no papel e **nunca** operou. Os testes da JVM
 * não pegaram porque as fixtures usavam `name`, um formato que o publicado não tem. Agora as
 * duas chaves são aceitas e existe um teste de regressão com o formato REAL.
 *
 * O parse é isolado do Android (só `JSONObject` + `File`) para poder ser testado na JVM.
 */
object GraniteNarManifest {

    /** Um item do manifesto. */
    data class Item(val nome: String, val bytes: Long, val sha256: String)

    enum class Estado { VALIDO, INVALIDO, AUSENTE }

    /**
     * Resultado EXPLÍCITO de leitura do manifesto.
     *
     * Antes, a leitura devolvia mapa vazio tanto para "manifesto válido" quanto para "não deu
     * para ler" — e o chamador tratava os dois como "não verificar", que é a falha de projeto
     * que o plano manda corrigir ("substitua o mapa vazio silencioso por resultado explícito").
     */
    data class Resultado(val estado: Estado, val itens: List<Item>, val motivo: String?) {
        val porNome: Map<String, Item> get() = itens.associateBy { it.nome }
    }

    /** Hash do arquivo em streaming (não carrega o conteúdo na memória). */
    fun sha256(file: File, buffer: ByteArray = ByteArray(1 shl 20)): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { entrada ->
            while (true) {
                val lidos = entrada.read(buffer)
                if (lidos <= 0) break
                md.update(buffer, 0, lidos)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** Nome do item: o publicado usa `caminho_relativo`; `name` fica aceito por compatibilidade. */
    private fun nomeDe(item: JSONObject): String =
        sequenceOf("caminho_relativo", "name", "arquivo", "file", "path")
            .map { item.optString(it).trim() }
            .firstOrNull { it.isNotBlank() } ?: ""

    /**
     * Lê o JSON e devolve nome→sha256 — **tolerante**, para o caminho de uso de pacote já
     * instalado (pacote legítimo antigo pode não ter hashes; a ausência não pode impedir o uso).
     * Só entradas com hash de 64 dígitos entram.
     */
    fun parse(json: String): Map<String, String> =
        parseEstrito(json).itens.associate { it.nome to it.sha256 }

    /**
     * Leitura **estrita** para o caminho de DOWNLOAD: valida a estrutura e recusa em vez de
     * seguir com informação incompleta.
     *
     * Recusa quando (gates do plano, seção 1.2): JSON inválido; lista `arquivos` ausente/vazia;
     * nome ausente ou repetido; caminho absoluto (`/...`, `C:...`); `..` no caminho; `sha256`
     * que não seja 64 dígitos hexadecimais; `bytes` ausente, não numérico ou ≤ 0.
     */
    fun parseEstrito(json: String): Resultado {
        val raiz = runCatching { JSONObject(json) }.getOrNull()
            ?: return Resultado(Estado.INVALIDO, emptyList(), "JSON inválido")

        val lista = raiz.optJSONArray("arquivos")
            ?: return Resultado(Estado.INVALIDO, emptyList(), "sem a lista 'arquivos'")
        if (lista.length() == 0) {
            return Resultado(Estado.INVALIDO, emptyList(), "'arquivos' vazio")
        }

        val itens = mutableListOf<Item>()
        val vistos = mutableSetOf<String>()
        for (i in 0 until lista.length()) {
            val o = lista.optJSONObject(i)
                ?: return Resultado(Estado.INVALIDO, emptyList(), "item $i não é objeto")
            val nome = nomeDe(o)
            if (nome.isBlank()) {
                return Resultado(Estado.INVALIDO, emptyList(), "item $i sem nome/caminho_relativo")
            }
            if (nome.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(nome)) {
                return Resultado(Estado.INVALIDO, emptyList(), "caminho absoluto: $nome")
            }
            if (nome.split('/').any { it == ".." }) {
                return Resultado(Estado.INVALIDO, emptyList(), "traversal no caminho: $nome")
            }
            if (!vistos.add(nome)) {
                return Resultado(Estado.INVALIDO, emptyList(), "nome repetido: $nome")
            }
            val sha = o.optString("sha256").trim().lowercase()
            if (!HEX64.matches(sha)) {
                return Resultado(Estado.INVALIDO, emptyList(), "sha256 inválido em $nome")
            }
            val bytes = o.optLong("bytes", -1L)
            if (bytes <= 0L) {
                return Resultado(Estado.INVALIDO, emptyList(), "bytes inválido em $nome")
            }
            itens.add(Item(nome, bytes, sha))
        }
        return Resultado(Estado.VALIDO, itens, null)
    }

    /**
     * Confere [arquivo] contra o hash esperado — **tolerante**.
     *
     * Devolve true também quando `esperado` é nulo/vazio (arquivo não listado): a ausência de
     * hash não pode impedir o uso de um pacote legítimo antigo — mas **nunca** aprova um arquivo
     * cujo hash é conhecido e diverge.
     */
    fun confere(arquivo: File, esperado: String?, buffer: ByteArray = ByteArray(1 shl 20)): Boolean {
        if (esperado.isNullOrBlank()) return true
        if (!arquivo.isFile) return false
        return sha256(arquivo, buffer).equals(esperado.trim().lowercase(), ignoreCase = true)
    }

    /**
     * Confere **exigindo** hash (caminho de DOWNLOAD novo/atualização): arquivo sem hash
     * publicado é recusado, porque ativar sem verificação é exatamente a falha silenciosa que a
     * Fase 7 existe para impedir.
     */
    fun confereEstrito(arquivo: File, esperado: String?, buffer: ByteArray = ByteArray(1 shl 20)): Boolean {
        val exp = esperado?.trim()?.lowercase()
        if (exp.isNullOrBlank() || !HEX64.matches(exp)) return false
        if (!arquivo.isFile) return false
        return sha256(arquivo, buffer).equals(exp, ignoreCase = true)
    }
}