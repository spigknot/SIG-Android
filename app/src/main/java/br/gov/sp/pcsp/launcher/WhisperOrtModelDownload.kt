package br.gov.sp.pcsp.launcher

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Entrega gerenciada do pacote ORT (adendo 13 §2).
 *
 * Origem ADOTADA (decisão do escopo, não deste arquivo): `onnx-community/
 * whisper-large-v3-turbo`, revisão `360ebcde2559d60bb474678be3c1de9ef347d01a`,
 * no Hugging Face — a mesma origem congelada desde a ordem inicial. Os cinco
 * componentes coincidem com o catálogo ativo; as URLs são `resolve/<revisão>/
 * <path>`, com encoder/init/with_past em `onnx/` e `vocab.json` +
 * `generation_config.json` na raiz. Nenhum bucket novo, nenhum servidor novo.
 *
 * Contrato:
 * - confere espaço livre ANTES de baixar (soma do catálogo);
 * - baixa cada arquivo em staging PRIVADO (`<nome>.part` + rename), com
 *   progresso (bytes/total/arquivo), cancelamento entre blocos e validação de
 *   tamanho + SHA-256 por arquivo;
 * - a versão ATUAL continua utilizável se cancelar/falhar: o staging é um
 *   diretório à parte e a ativação atômica ([WhisperOrtPackageInstaller.install])
 *   só acontece depois, pelo chamador;
 * - o pacote em uso fica protegido: nada aqui toca `versions/` nem `active`.
 *
 * A abertura de conexão é injetável para prova HOST (servidor controlado).
 */
object WhisperOrtModelDownload {

    /** Revisão congelada do modelo (imutável; confere `fontes-modelo-verificadas.json`). */
    const val REVISION = "360ebcde2559d60bb474678be3c1de9ef347d01a"

    private const val BASE =
        "https://huggingface.co/onnx-community/whisper-large-v3-turbo/resolve/$REVISION"

    /** Caminho remoto de [nome] dentro da revisão (onnx/ ou raiz). */
    fun caminhoRemoto(nome: String): String =
        if (nome.endsWith(".onnx")) "onnx/$nome" else nome

    /** URL de download de [nome] na origem adotada. */
    fun urlDe(nome: String): String = "$BASE/${caminhoRemoto(nome)}"

    /** Progresso observável pela ferramenta. */
    data class Progresso(
        val baixadosTotal: Long,
        val total: Long,
        val arquivoAtual: String,
        val baixadosArquivo: Long,
        val totalArquivo: Long,
    )

    /** Desfecho tipado do download. */
    sealed class Resultado {
        /** Todos os arquivos baixados e íntegros em [staging], prontos para `install`. */
        data class Pronto(val staging: File) : Resultado()
        /** Pedido cancelado: parciais apagados, versão atual intacta. */
        data object Cancelado : Resultado()
        /** Nada foi publicado; parciais apagados. */
        data class Falhou(val motivo: String) : Resultado()
    }

    /**
     * Baixa [entradas] para [stagingDir] (criado/limpo por esta função).
     *
     * @param abrirConexao ponto de costura para servidor controlado (prova HOST).
     */
    fun baixar(
        entradas: List<WhisperOrtModelCatalog.Entry>,
        stagingDir: File,
        urlDe: (String) -> String = ::urlDe,
        isCancelada: () -> Boolean = { false },
        onProgresso: (Progresso) -> Unit = { },
        abrirConexao: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    ): Resultado {
        if (entradas.isEmpty()) return Resultado.Falhou("catálogo vazio")
        if (isCancelada()) return Resultado.Cancelado
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        val total = entradas.sumOf { it.sizeBytes }
        if (stagingDir.usableSpace < total) {
            return Resultado.Falhou(
                "espaço livre insuficiente: precisa de ${total / 1_048_576} MB",
            )
        }
        var baixadosTotal = 0L
        try {
            for (entrada in entradas) {
                if (isCancelada()) {
                    stagingDir.deleteRecursively()
                    return Resultado.Cancelado
                }
                val destino = File(stagingDir, entrada.name)
                val parcial = File(stagingDir, entrada.name + ".part")
                val codigo: Int
                var conexaoAberta: HttpURLConnection? = null
                try {
                    val conexao = abrirConexao(urlDe(entrada.name))
                    conexaoAberta = conexao
                    conexao.connectTimeout = 15_000
                    conexao.readTimeout = 30_000
                    conexao.connect()
                    codigo = conexao.responseCode
                    if (codigo != HttpURLConnection.HTTP_OK) {
                        stagingDir.deleteRecursively()
                        return Resultado.Falhou("${entrada.name}: HTTP $codigo")
                    }
                    val totalArquivo = conexao.contentLengthLong.takeIf { it > 0L } ?: entrada.sizeBytes
                    var baixadosArquivo = 0L
                    var ultimoAviso = 0L
                    // Sai do laço por flag e fecha os streams ANTES de apagar: retornar
                    // com o `outputStream` aberto faz o delete falhar no Windows e o
                    // parcial vaza (regressão observada em HOST).
                    var canceladoNoMeio = false
                    conexao.inputStream.use { origem ->
                        parcial.outputStream().use { saida ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                if (isCancelada()) {
                                    canceladoNoMeio = true
                                    break
                                }
                                val lidos = origem.read(buffer)
                                if (lidos < 0) break
                                if (lidos.toLong() > entrada.sizeBytes - baixadosArquivo) {
                                    throw java.io.IOException(
                                        "${entrada.name}: corpo excede o limite de ${entrada.sizeBytes} B do catalogo",
                                    )
                                }
                                saida.write(buffer, 0, lidos)
                                baixadosArquivo += lidos
                                val agora = System.nanoTime()
                                if (agora - ultimoAviso > 500_000_000L) {
                                    ultimoAviso = agora
                                    onProgresso(
                                        Progresso(
                                            baixadosTotal + baixadosArquivo, total,
                                            entrada.name, baixadosArquivo, totalArquivo,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    if (canceladoNoMeio || isCancelada()) {
                        stagingDir.deleteRecursively()
                        return Resultado.Cancelado
                    }
                } catch (falha: InterruptedException) {
                    Thread.currentThread().interrupt()
                    stagingDir.deleteRecursively()
                    return Resultado.Cancelado
                } catch (falha: Throwable) {
                    stagingDir.deleteRecursively()
                    return Resultado.Falhou("${entrada.name}: ${falha.javaClass.simpleName}: ${falha.message}")
                } finally {
                    runCatching { conexaoAberta?.disconnect() }
                }
                if (isCancelada()) {
                    stagingDir.deleteRecursively()
                    return Resultado.Cancelado
                }
                // HTTP incompleto: o corpo terminou antes do tamanho declarado.
                val bytesRecebidos = parcial.length()
                if (bytesRecebidos != entrada.sizeBytes) {
                    stagingDir.deleteRecursively()
                    return Resultado.Falhou(
                        "${entrada.name}: incompleto ($bytesRecebidos B de ${entrada.sizeBytes} B)",
                    )
                }
                if (!parcial.renameTo(destino)) {
                    parcial.copyTo(destino, overwrite = true)
                    parcial.delete()
                }
                val medido = sha256(destino, isCancelada)
                if (medido == null) {
                    stagingDir.deleteRecursively()
                    return Resultado.Cancelado
                }
                if (!medido.equals(entrada.sha256, ignoreCase = true)) {
                    stagingDir.deleteRecursively()
                    return Resultado.Falhou("${entrada.name}: SHA divergente")
                }
                baixadosTotal += entrada.sizeBytes
                onProgresso(Progresso(baixadosTotal, total, entrada.name, entrada.sizeBytes, entrada.sizeBytes))
            }
        } catch (falha: Throwable) {
            stagingDir.deleteRecursively()
            return Resultado.Falhou("falha inesperada: ${falha.javaClass.simpleName}: ${falha.message}")
        }
        if (isCancelada()) {
            stagingDir.deleteRecursively()
            return Resultado.Cancelado
        }
        return Resultado.Pronto(stagingDir)
    }

    private fun sha256(arquivo: File, isCancelada: () -> Boolean): String? {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        arquivo.inputStream().use { origem ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                if (isCancelada()) return null
                val lidos = origem.read(buffer)
                if (lidos < 0) break
                digest.update(buffer, 0, lidos)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
