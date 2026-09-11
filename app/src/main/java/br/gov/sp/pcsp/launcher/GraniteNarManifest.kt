package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Manifesto do pacote de modelos: nome do arquivo → SHA-256.
 *
 * Exigência da Fase 7 do plano: **"SHA-256 antes da ativação"**. Sem isso, um download
 * truncado, corrompido ou servido errado seria ativado sem nenhuma checagem — e o sintoma
 * apareceria depois, como "o modelo não transcreve", sem pista da causa.
 *
 * O manifesto publicado fica em `<PACKAGE_BASE_URL>/manifest.json` e lista cada arquivo com
 * `name`, `bytes` e `sha256`. O parse é isolado do Android (só `JSONObject` + `File`) para
 * poder ser testado na JVM.
 */
object GraniteNarManifest {

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

    /**
     * Lê o JSON do manifesto e devolve nome→sha256 (só entradas com hash não vazio).
     *
     * Devolve mapa vazio quando o JSON não tem a lista esperada — o chamador trata a
     * ausência como "não verificar" em vez de falhar (pacote antigo pode não ter hashes).
     */
    fun parse(json: String): Map<String, String> {
        val saida = mutableMapOf<String, String>()
        val raiz = runCatching { JSONObject(json) }.getOrNull() ?: return saida
        val lista = raiz.optJSONArray("arquivos") ?: return saida
        for (i in 0 until lista.length()) {
            val item = lista.optJSONObject(i) ?: continue
            val nome = item.optString("name")
            if (nome.isBlank()) continue
            val sha = item.optString("sha256").trim().lowercase()
            if (sha.length == 64) saida[nome] = sha
        }
        return saida
    }

    /**
     * Confere [arquivo] contra o hash esperado.
     *
     * Devolve true também quando `esperado` é nulo/vazio (arquivo não listado no manifesto):
     * a ausência de hash não pode impedir o uso de um pacote legítimo antigo — mas **nunca**
     * aprova um arquivo cujo hash é conhecido e diverge.
     */
    fun confere(arquivo: File, esperado: String?, buffer: ByteArray = ByteArray(1 shl 20)): Boolean {
        if (esperado.isNullOrBlank()) return true
        if (!arquivo.isFile) return false
        return sha256(arquivo, buffer).equals(esperado.trim().lowercase(), ignoreCase = true)
    }
}
