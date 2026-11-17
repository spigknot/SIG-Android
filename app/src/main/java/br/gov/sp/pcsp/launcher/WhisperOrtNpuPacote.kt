package br.gov.sp.pcsp.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * R100 §2.3 — pacote do encoder W8A16/INT16 do SM8550, entregue sob demanda pelo
 * **R2** (mesma origem e mesmo desenho do pacote QAIRT: ZIP validado por SHA-256,
 * extração em staging, ativação por rename e marcador de versão).
 *
 * O que o pacote leva (cinco libs V73 + contexto + wrapper) está em [ARQUIVOS] e
 * no `manifest.json` interno, que é a fonte de verdade por arquivo: nome, bytes e
 * SHA-256. O SHA do ZIP pinado no código garante o transporte; o manifesto garante
 * cada arquivo extraído. Divergência em qualquer um dos dois = **CPU**, nunca
 * rota HTP com artefato errado.
 *
 * Política (a mesma do mecanismo atual, sem UI bloqueante):
 * - só baixa em rede **não medida** (Wi-Fi); rede medida ou ausente = CPU;
 * - confere espaço antes de baixar (ZIP no cache + extração no `no_backup`);
 * - nunca apaga a versão ativa antes do staging estar completo;
 * - nunca bloqueia a transcrição: quem chama agenda e segue em CPU.
 */
object WhisperOrtNpuPacote {

    const val VERSAO = "1"
    const val SOC = WhisperOrtHtpAdmissao.SOC_SM8550
    const val HTP_ARCH = "73"
    const val DTYPE = WhisperOrtHtpConversao.DTYPE_I16

    /**
     * Pacote publicado no bucket do projeto. O prefixo segue o padrão descoberto
     * na T01 (`packages/<familia>/...`), com a versão imutável no caminho.
     */
    const val URL_PACOTE =
        "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/packages/npu-w8a16/sm8550/1/sig-whisper-w8a16-sm8550-v1.zip"

    /** SHA-256 do ZIP publicado (transporte). Conferido pelo script da T04. */
    const val SHA256_ZIP = "4e190542e91c02f650127aa9b86d34ca3cdac13a90ec9f3d37dbe06ed22759e3"

    /** Bytes do ZIP publicado (777.366.069 = 7 arquivos + manifesto, ZIP_STORED). */
    const val BYTES_ZIP = 777_366_069L

    const val NOME_MANIFESTO = "manifest.json"
    const val MARCADOR = ".installed"

    const val CONTEXTO = "sig_whisper_enc_w8a16.bin"
    const val WRAPPER = "sig_whisper_enc_w8a16_i16io.onnx"

    /** As cinco bibliotecas V73 exigidas pela rota (ordem de carga do QNN). */
    val LIBS_V73 = listOf(
        "libQnnSystem.so",
        "libQnnHtp.so",
        "libQnnHtpPrepare.so",
        "libQnnHtpV73Stub.so",
        "libQnnHtpV73Skel.so",
    )

    val ARQUIVOS: List<String> = listOf(CONTEXTO, WRAPPER) + LIBS_V73

    private const val SCHEMA = "sig-whisper-npu-pacote/v1"
    private const val TAG = "WhisperOrtNpu"

    /** Item do manifesto: identidade por arquivo. */
    data class Item(val nome: String, val bytes: Long, val sha256: String)

    /** Manifesto do pacote (lido do ZIP, nunca inventado pelo app). */
    data class Manifesto(
        val versao: String,
        val soc: String,
        val htpArch: String,
        val dtype: String,
        val itens: List<Item>,
    ) {
        fun item(nome: String): Item? = itens.firstOrNull { it.nome == nome }
        fun bytesDescompactados(): Long = itens.sumOf { it.bytes }
    }

    /** Situação do pacote para a admissão. */
    sealed class Estado {
        data class Pronto(val dir: File, val manifesto: Manifesto) : Estado()
        data class Ausente(val motivo: String) : Estado()
        data class Invalido(val motivo: String) : Estado()
    }

    /** Progresso observável (só log; a UI não bloqueia por este download). */
    data class Progresso(val baixados: Long, val total: Long, val fase: String)

    fun raiz(context: Context): File = File(context.noBackupFilesDir, "whisper-npu")

    fun dirDaVersao(context: Context): File = File(raiz(context), VERSAO)

    /**
     * Lê o manifesto do texto JSON. Devolve `null` quando o conteúdo não é um
     * manifesto válido DESTE pacote: schema, versão, SoC, arch e dtype têm de
     * bater, e os sete arquivos exigidos têm de estar listados com bytes > 0 e
     * SHA-256 de 64 dígitos. Manifesto parcial é recusa — nunca "quase pronto".
     */
    fun lerManifesto(texto: String): Manifesto? = runCatching {
        val raiz = JSONObject(texto)
        if (raiz.optString("schema") != SCHEMA) return null
        val versao = raiz.optString("versao")
        val soc = raiz.optString("soc")
        val arch = raiz.optString("htp_arch")
        val dtype = raiz.optString("dtype")
        if (versao != VERSAO || soc != SOC || arch != HTP_ARCH || dtype != DTYPE) return null
        val lista = raiz.optJSONArray("arquivos") ?: return null
        val itens = ArrayList<Item>(lista.length())
        for (i in 0 until lista.length()) {
            val o = lista.optJSONObject(i) ?: return null
            val nome = o.optString("nome")
            val bytes = o.optLong("bytes", -1L)
            val sha = o.optString("sha256")
            if (nome.isBlank() || bytes <= 0L || sha.length != 64) return null
            if (sha.any { c -> c !in "0123456789abcdefABCDEF" }) return null
            itens.add(Item(nome, bytes, sha.lowercase()))
        }
        val nomes = itens.map { it.nome }.toSet()
        if (!nomes.containsAll(ARQUIVOS)) return null
        if (itens.size != ARQUIVOS.size) return null
        Manifesto(versao, soc, arch, dtype, itens)
    }.getOrNull()

    /**
     * Confere os arquivos extraídos contra o manifesto.
     *
     * Primeiro tamanho (barato) e só depois SHA-256 (caro): um contexto de
     * 646 MiB não é hasheado duas vezes por acidente. Devolve `null` quando está
     * tudo certo, ou o motivo estável da recusa (vai para o log e para a CPU).
     */
    fun conferirArquivos(
        manifesto: Manifesto,
        dir: File,
        hash: (File) -> String = this::sha256,
    ): String? {
        for (item in manifesto.itens) {
            val arquivo = File(dir, item.nome)
            if (!arquivo.isFile) return "ausente:${item.nome}"
            if (arquivo.length() != item.bytes) {
                return "tamanho:${item.nome}:${arquivo.length()}!=${item.bytes}"
            }
        }
        for (item in manifesto.itens) {
            val arquivo = File(dir, item.nome)
            val obtido = hash(arquivo)
            if (!obtido.equals(item.sha256, ignoreCase = true)) {
                return "sha:${item.nome}"
            }
        }
        return null
    }

    /**
     * Estado RÁPIDO (sem hash): marcador presente e os sete arquivos com o
     * tamanho declarado. A verificação por SHA é a [verificarCompleto], feita uma
     * vez por processo em segundo plano.
     */
    fun estado(context: Context): Estado {
        val dir = dirDaVersao(context)
        val marcador = File(dir, MARCADOR)
        if (!marcador.isFile) return Estado.Ausente("sem marcador em $dir")
        val manifesto = runCatching {
            lerManifesto(File(dir, NOME_MANIFESTO).readText())
        }.getOrNull() ?: return Estado.Invalido("manifesto ilegivel")
        if (marcador.readText().trim() != textoMarcadorEsperado(manifesto)) {
            return Estado.Invalido("marcador divergente")
        }
        for (item in manifesto.itens) {
            val arquivo = File(dir, item.nome)
            if (!arquivo.isFile) return Estado.Ausente("ausente:${item.nome}")
            if (arquivo.length() != item.bytes) return Estado.Invalido("tamanho:${item.nome}")
        }
        return Estado.Pronto(dir, manifesto)
    }

    /** Texto canônico do marcador: versão + hash do manifesto (sem tocar em arquivos). */
    fun textoMarcadorEsperado(manifesto: Manifesto): String =
        "versao=${manifesto.versao} arquivos=${manifesto.itens.size} " +
            "sha=${manifesto.itens.joinToString(",") { it.sha256 }}"

    /** Verificação COMPLETA (todos os SHA). Usada no install e 1x por processo. */
    @VisibleForTesting
    internal fun verificarCompleto(context: Context, hash: (File) -> String = this::sha256): Estado {
        val estado = estado(context)
        if (estado !is Estado.Pronto) return estado
        val motivo = conferirArquivos(estado.manifesto, estado.dir, hash)
        return if (motivo == null) estado else Estado.Invalido(motivo)
    }

    /**
     * Garante o pacote em SEGUNDO PLANO: se já está instalado, verifica; se não,
     * baixa e instala. Nunca lança e nunca bloqueia quem chamou. Uma thread por
     * processo (single-flight) — `iniciar` pode ser chamado a cada transcrição.
     */
    fun garantirEmSegundoPlano(context: Context) {
        if (!emAndamento.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                val estado = verificarCompleto(app)
                when (estado) {
                    is Estado.Pronto -> {
                        WhisperOrtHtpRota.pacoteVerificado(estado.manifesto)
                        return@Thread
                    }
                    is Estado.Invalido -> {
                        // Instalação corrompida: apaga e tenta baixar de novo.
                        android.util.Log.w(TAG, "pacote invalido (${estado.motivo}); reinstalando")
                        runCatching { dirDaVersao(app).deleteRecursively() }
                    }
                    is Estado.Ausente -> Unit
                }
                val politica = politicaDeRede(app)
                if (politica != null) {
                    android.util.Log.i(TAG, "download NPU adiado: $politica")
                    WhisperOrtHtpRota.pacoteIndisponivel(politica)
                    return@Thread
                }
                val resultado = instalar(app) { p ->
                    android.util.Log.i(
                        TAG,
                        "download NPU ${p.fase} ${p.baixados}/${p.total}",
                    )
                }
                resultado.fold(
                    onSuccess = {
                        val verificado = verificarCompleto(app)
                        when (verificado) {
                            is Estado.Pronto -> WhisperOrtHtpRota.pacoteVerificado(verificado.manifesto)
                            is Estado.Invalido -> {
                                android.util.Log.w(TAG, "pacote recem-instalado invalido: ${verificado.motivo}")
                                WhisperOrtHtpRota.pacoteIndisponivel("verificacao:" + verificado.motivo)
                            }
                            is Estado.Ausente -> WhisperOrtHtpRota.pacoteIndisponivel("ausente apos install")
                        }
                    },
                    onFailure = { falha ->
                        android.util.Log.w(TAG, "download NPU falhou: ${falha.javaClass.simpleName}: ${falha.message}")
                        WhisperOrtHtpRota.pacoteIndisponivel("download:" + (falha.message ?: "falha"))
                    },
                )
            } catch (falha: Throwable) {
                android.util.Log.w(TAG, "garantia NPU lancou: ${falha.javaClass.simpleName}: ${falha.message}")
                WhisperOrtHtpRota.pacoteIndisponivel("interno:" + (falha.message ?: "falha"))
            } finally {
                emAndamento.set(false)
            }
        }, "whisper-npu-pacote").apply { isDaemon = true }.start()
    }

    private val emAndamento = AtomicBoolean(false)

    /**
     * Política de rede: `null` = pode baixar; texto = motivo para NÃO baixar.
     * Rede medida nunca recebe 685 MiB sem o usuário pedir — a transcrição segue
     * em CPU.
     */
    fun politicaDeRede(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "sem ConnectivityManager"
        val rede = cm.activeNetwork ?: return "sem rede ativa"
        val caps = cm.getNetworkCapabilities(rede) ?: return "sem capabilities"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "sem internet"
        if (cm.isActiveNetworkMetered) return "rede medida"
        return null
    }

    /**
     * Baixa, valida o ZIP, extrai em staging, confere o manifesto por arquivo e
     * ativa por rename. Idempotente quando já está pronto.
     */
    fun instalar(
        context: Context,
        onProgresso: (Progresso) -> Unit = { },
    ): Result<Unit> = runCatching {
        if (estado(context) is Estado.Pronto && verificarCompleto(context) is Estado.Pronto) return@runCatching
        check(SHA256_ZIP.length == 64 && SHA256_ZIP.any { it != '0' }) {
            "SHA-256 do pacote NPU nao configurado"
        }
        val zipBytes = if (BYTES_ZIP > 0L) BYTES_ZIP else 0L
        val raiz = raiz(context).apply { mkdirs() }
        val destino = dirDaVersao(context)
        val arquivoZip = File(context.cacheDir, "whisper-npu-v$VERSAO.zip.download")

        if (zipBytes > 0L && context.cacheDir.usableSpace < zipBytes + 32L * 1024 * 1024) {
            error("espaco insuficiente para baixar ${zipBytes / 1_048_576} MB")
        }
        baixar(arquivoZip, onProgresso)
        onProgresso(Progresso(arquivoZip.length(), arquivoZip.length(), "validando"))
        check(sha256(arquivoZip).equals(SHA256_ZIP, ignoreCase = true)) {
            "SHA-256 do pacote NPU nao confere"
        }

        val staging = File(raiz, ".staging-${System.nanoTime()}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            onProgresso(Progresso(arquivoZip.length(), arquivoZip.length(), "instalando"))
            val estimado = maxOf(arquivoZip.length(), BYTES_ZIP) + 96L * 1024 * 1024
            check(raiz.usableSpace >= estimado) {
                "espaco insuficiente para extrair: precisa ${estimado / 1_048_576} MB"
            }
            extrairZip(arquivoZip, staging)
            val manifesto = runCatching {
                lerManifesto(File(staging, NOME_MANIFESTO).readText())
            }.getOrNull() ?: error("manifesto ausente/invalido no pacote")
            val precisa = manifesto.bytesDescompactados() + 64L * 1024 * 1024
            check(raiz.usableSpace >= precisa) {
                "espaco insuficiente para extrair: precisa ${precisa / 1_048_576} MB"
            }
            val motivo = conferirArquivos(manifesto, staging)
            check(motivo == null) { "pacote incompleto: $motivo" }
            File(staging, MARCADOR).writeText(textoMarcadorEsperado(manifesto))

            val anterior = File(raiz, ".previous")
            anterior.deleteRecursively()
            if (destino.exists() && !destino.renameTo(anterior)) destino.deleteRecursively()
            val ativado = staging.renameTo(destino) || runCatching {
                staging.copyRecursively(destino, overwrite = true)
                staging.deleteRecursively()
                destino.exists()
            }.getOrDefault(false)
            check(ativado) { "falha ao ativar o pacote NPU" }
            check(estado(context) is Estado.Pronto) { "pacote NPU nao ficou ativo" }
            anterior.deleteRecursively()
        } finally {
            staging.deleteRecursively()
            arquivoZip.delete()
        }
    }

    private fun baixar(destino: File, onProgresso: (Progresso) -> Unit) {
        destino.parentFile?.mkdirs()
        val conn = (URL(URL_PACOTE).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 300_000
            setRequestProperty("User-Agent", "SIG-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(conn.responseCode in 200..299) { "download NPU retornou HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: BYTES_ZIP
            BufferedInputStream(conn.inputStream).use { entrada ->
                FileOutputStream(destino).use { saida ->
                    val buffer = ByteArray(256 * 1024)
                    var baixados = 0L
                    while (true) {
                        val n = entrada.read(buffer)
                        if (n < 0) break
                        saida.write(buffer, 0, n)
                        baixados += n
                        onProgresso(Progresso(baixados, total, "baixando"))
                    }
                    saida.fd.sync()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Extrai normalizando `\` (ZIP gerado no Windows) e recusando caminho fora da raiz. */
    private fun extrairZip(arquivo: File, destino: File) {
        val canonico = destino.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(arquivo))).use { zip ->
            while (true) {
                val entrada = zip.nextEntry ?: break
                val nome = entrada.name.replace('\\', '/')
                val saida = File(destino, nome).canonicalFile
                check(saida.path.startsWith(canonico.path + File.separator)) { "entrada ZIP invalida" }
                if (entrada.isDirectory) {
                    saida.mkdirs()
                } else {
                    saida.parentFile?.mkdirs()
                    FileOutputStream(saida).use { zip.copyTo(it, 256 * 1024) }
                    saida.setReadable(true, true)
                }
                zip.closeEntry()
            }
        }
    }

    fun sha256(arquivo: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        arquivo.inputStream().use { entrada ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = entrada.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
