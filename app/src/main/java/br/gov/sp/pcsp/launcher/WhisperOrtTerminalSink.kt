package br.gov.sp.pcsp.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Sink de terminais da rota ORT com **recibo de entrega**.
 *
 * Problema que resolve: o terminal era uma linha em `terminais.jsonl`. Isso
 * informa, mas não permite recuperar nem confirmar entrega — se a tela morre, o
 * consumidor não tem como saber o que já foi entregue, e uma falha de gravação
 * ficava só no log.
 *
 * Contrato:
 * - **publicar**: grava o terminal em arquivo PRÓPRIO por job/geração
 *   (`terminais/<job>-<gen>.json`), de forma atômica (tmp + rename). Devolve um
 *   [Recibo] com caminho, SHA-256 do conteúdo e instante. Falha → `null`, e o
 *   chamador sabe que NÃO houve entrega.
 * - **pendentes**: terminais publicados que ainda não têm recibo de entrega
 *   (`<job>-<gen>.ack`). É a fila de reentrega após morte/recriação da tela.
 * - **confirmar**: grava o ack, com o SHA-256 do conteúdo entregue — um ack de
 *   conteúdo diferente é recusado, para não confirmar payload trocado.
 *
 * O JSONL continua sendo escrito como ÍNDICE (leitura rápida/observação), mas não
 * é a fonte de verdade: a fonte é o arquivo por job.
 */
object WhisperOrtTerminalSink {

    private const val DIR = "ort-route"

    /** Recibo de uma publicação efetiva. */
    data class Recibo(
        val jobId: String,
        val attempt: Long,
        val generation: Long,
        val caminho: String,
        val sha256: String,
        val publicadoEmMillis: Long,
    )

    private fun dir(app: Context) = dir(File(app.filesDir, DIR))

    private fun dir(base: File) = File(base, "terminais")

    private fun arquivo(app: Context, jobId: String, generation: Long) =
        arquivo(File(app.filesDir, DIR), jobId, generation)

    private fun arquivo(base: File, jobId: String, generation: Long) =
        File(dir(base), "${seguro(jobId)}-$generation.json")

    private fun ack(app: Context, jobId: String, generation: Long) =
        ack(File(app.filesDir, DIR), jobId, generation)

    private fun ack(base: File, jobId: String, generation: Long) =
        File(dir(base), "${seguro(jobId)}-$generation.ack")

    /** Evita que um jobId estranho escape do diretório do sink. */
    private fun seguro(nome: String): String = nome.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Publica o terminal e devolve o recibo.
     *
     * `null` significa **não entregue** (diretório/rename/escrita falharam); quem
     * chama não pode tratar como sucesso nem fabricar ack.
     */
    fun publicar(app: Context, terminal: WhisperOrtAdmission.Terminal): Recibo? =
        publicar(File(app.filesDir, DIR), terminal)

    /** Variante testável: mesma política, raiz explícita. */
    fun publicar(base: File, terminal: WhisperOrtAdmission.Terminal): Recibo? {
        val destino = arquivo(base, terminal.jobId, terminal.generation)
        val linha = JSONObject()
            .put("job", terminal.jobId)
            .put("attempt", terminal.attempt)
            .put("generation", terminal.generation)
            .put("engine", terminal.engine)
            .put("provider", terminal.provider)
            .put("input", terminal.inputSha256)
            .put("outcome", terminal.outcome)
            .put("texto", terminal.text)
            .put("bytes", terminal.textBytes)
            .put("text_sha256", terminal.textSha256)
            .put("millis", terminal.totalMillis)
            .put("partial", terminal.partial)
            .put("error", terminal.error ?: JSONObject.NULL)
        val bytes = linha.toString().toByteArray(Charsets.UTF_8)
        // IDEMPOTENTE: repetir o MESMO terminal não regrava nem apaga o anterior.
        // Payload DIFERENTE para o mesmo job/geração é conflito e é RECUSADO —
        // sobrescrever apagaria o que já foi confirmado.
        if (destino.isFile) {
            val atual = runCatching { destino.readBytes() }.getOrNull()
            if (atual != null && atual.contentEquals(bytes)) {
                return Recibo(
                    jobId = terminal.jobId,
                    attempt = terminal.attempt,
                    generation = terminal.generation,
                    caminho = destino.absolutePath,
                    sha256 = sha256(bytes),
                    publicadoEmMillis = destino.lastModified(),
                )
            }
            return null
        }
        val entregue = runCatching {
            destino.parentFile?.mkdirs()
            val temporario = File(destino.parentFile, destino.name + ".tmp")
            temporario.writeBytes(bytes)
            if (destino.exists() && !destino.delete()) return@runCatching false
            if (!temporario.renameTo(destino)) return@runCatching false
            destino.isFile && destino.length() == bytes.size.toLong()
        }.getOrDefault(false)
        if (!entregue) return null

        // Índice best-effort: útil para observação, nunca a fonte de verdade.
        runCatching {
            File(base, "terminais.jsonl")
                .appendText(linha.toString() + System.lineSeparator(), Charsets.UTF_8)
        }
        return Recibo(
            jobId = terminal.jobId,
            attempt = terminal.attempt,
            generation = terminal.generation,
            caminho = destino.absolutePath,
            sha256 = sha256(bytes),
            publicadoEmMillis = System.currentTimeMillis(),
        )
    }

    /** Terminais publicados sem ack — fila de reentrega. */
    fun pendentes(app: Context): List<Recibo> = pendentes(File(app.filesDir, DIR))

    /** Variante testável: mesma política, raiz explícita. */
    fun pendentes(base: File): List<Recibo> {
        val pasta = dir(base)
        if (!pasta.isDirectory) return emptyList()
        val arquivos = pasta.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return arquivos.mapNotNull { f ->
            // `nomeBase` (e não `base`): o nome local sombreava o parâmetro File e
            // fazia `ack(base, ...)` receber uma String.
            val nomeBase = f.nameWithoutExtension
            val partes = nomeBase.split('-').takeLast(2)
            if (partes.size != 2) return@mapNotNull null
            val jobId = nomeBase.removeSuffix("-" + partes[1])
            val generation = partes[1].toLongOrNull() ?: return@mapNotNull null
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@mapNotNull null
            // ACK só vale com CONTEÚDO igual ao SHA atual: vazio, ilegível ou de um
            // payload antigo (arquivo republicado) mantém o terminal pendente.
            val ackFile = ack(base, jobId, generation)
            val ackSha = if (ackFile.isFile) {
                runCatching { ackFile.readText(Charsets.UTF_8).trim() }.getOrNull()
            } else {
                null
            }
            if (ackSha != null && ackSha.isNotBlank() && ackSha == sha256(bytes)) return@mapNotNull null
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                ?: return@mapNotNull null
            Recibo(
                jobId = json.optString("job", jobId),
                attempt = json.optLong("attempt", 0L),
                generation = generation,
                caminho = f.absolutePath,
                sha256 = sha256(bytes),
                publicadoEmMillis = f.lastModified(),
            )
        }
    }

    /**
     * Confirma a entrega de [recibo].
     *
     * Recusa quando o conteúdo atual não corresponde ao SHA do recibo: confirmar
     * payload trocado seria pior que não confirmar.
     */
    fun confirmar(app: Context, recibo: Recibo): Boolean = confirmar(File(app.filesDir, DIR), recibo)

    /** Variante testável: mesma política, raiz explícita. */
    fun confirmar(base: File, recibo: Recibo): Boolean {
        val origem = File(recibo.caminho)
        if (!origem.isFile) return false
        val atual = runCatching { sha256(origem.readBytes()) }.getOrNull() ?: return false
        if (atual != recibo.sha256) return false
        val destino = ack(base, recibo.jobId, recibo.generation)
        return runCatching {
            destino.parentFile?.mkdirs()
            destino.writeText(recibo.sha256, Charsets.UTF_8)
            destino.isFile
        }.getOrDefault(false)
    }
}
