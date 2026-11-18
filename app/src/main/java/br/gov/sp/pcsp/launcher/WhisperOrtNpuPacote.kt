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
 * R101 §T02 — pacote do encoder W8A16/INT16 do SM8550, entregue sob demanda pelo
 * **R2** (mesma origem e mesmo desenho do pacote QAIRT: ZIP validado por SHA-256,
 * extração em staging, ativação por rename e marcador de versão).
 *
 * A v2 leva **só** o contexto EPContext e o wrapper. As cinco bibliotecas V73
 * saíram do pacote: a T02 comparou, por SHA-256, as libs validadas na M3 com as
 * do pacote QAIRT do [QairtDependencyManager] e elas são **byte a byte iguais**.
 * O Whisper passou a carregar do QAIRT — o mesmo runtime, o mesmo diretório e o
 * mesmo `ADSP_LIBRARY_PATH` do Granite, sem uma segunda instância do QNN no
 * processo. O que o pacote leva está em [ARQUIVOS] e no `manifest.json` interno,
 * que é a fonte de verdade por arquivo: nome, bytes e SHA-256. O SHA do ZIP
 * pinado no código garante o transporte; o manifesto garante cada arquivo
 * extraído. Divergência em qualquer um dos dois = **CPU**, nunca rota HTP com
 * artefato errado.
 *
 * Política (a mesma do mecanismo atual, sem UI bloqueante):
 * - **nunca baixa sozinho**: o download é uma ação explícita do usuário, com
 *   plano, progresso e cancelamento (R101 §T03); até concluir, a transcrição
 *   usa a CPU;
 * - confere espaço antes de baixar (ZIP no cache + extração no `no_backup`);
 * - nunca apaga a versão ativa antes do staging estar completo.
 */
object WhisperOrtNpuPacote {

    const val VERSAO = "2"
    const val SOC = WhisperOrtHtpAdmissao.SOC_SM8550
    const val HTP_ARCH = "73"
    const val DTYPE = WhisperOrtHtpConversao.DTYPE_I16

    /**
     * R103/T02 — o manifesto PUBLICADO do v2 grava `dtype: "i16"`, enquanto o
     * conversor da rota trabalha com `int16_le`. Tabela explícita e fechada:
     * somente o valor publicado é aceito e ele é traduzido para o dtype canônico
     * guardado em [Manifesto]. Nenhuma correspondência "por parecido" e nenhuma
     * republicação do pacote: o ZIP `ec25399a…` continua intocável.
     */
    private val DTYPE_PUBLICADO_PARA_CONVERSAO: Map<String, String> = mapOf(
        "i16" to DTYPE,
    )

    /**
     * Pacote publicado no bucket do projeto. O prefixo segue o padrão descoberto
     * na T01 (`packages/<familia>/...`), com a versão imutável no caminho.
     */
    const val URL_PACOTE =
        "https://pub-6476622beda24c82875cb84f11f660ea.r2.dev/packages/npu-w8a16/sm8550/2/sig-whisper-w8a16-sm8550-v2.zip"

    /** SHA-256 do ZIP publicado (transporte). Conferido pelo script da T04. */
    const val SHA256_ZIP = "ec25399a3c4ea2911b5142399cee792dc3e6cb5a86a6c1140930b8983bda5a2c"

    /** Bytes do ZIP publicado (677.532.464 = contexto + wrapper + manifesto, ZIP_STORED). */
    const val BYTES_ZIP = 677_532_464L

    /** Soma dos bytes extraídos (contexto + wrapper): base do espaço necessário. */
    const val BYTES_CONTEUDO = 677_531_596L

    /** Nome do objeto QAIRT no R2 — só para o plano de download da tela. */
    const val NOME_ZIP_QAIRT = "sig-qairt-arm64-v8a-v1.zip"

    const val NOME_MANIFESTO = "manifest.json"
    const val MARCADOR = ".installed"

    const val CONTEXTO = "sig_whisper_enc_w8a16.bin"
    const val WRAPPER = "sig_whisper_enc_w8a16_i16io.onnx"

    /**
     * Conteúdo do pacote v2. As libs V73 **não** entram aqui: quem as carrega é
     * o pacote QAIRT único ([QairtDependencyManager]).
     */
    val ARQUIVOS: List<String> = listOf(CONTEXTO, WRAPPER)

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
     * bater, e os dois arquivos exigidos têm de estar listados com bytes > 0 e
     * SHA-256 de 64 dígitos. Manifesto parcial é recusa — nunca "quase pronto".
     */
    fun lerManifesto(texto: String): Manifesto? = runCatching {
        val raiz = JSONObject(texto)
        if (raiz.optString("schema") != SCHEMA) return null
        val versao = raiz.optString("versao")
        val soc = raiz.optString("soc")
        val arch = raiz.optString("htp_arch")
        // Tabela fechada: "i16" (publicado) -> DTYPE canônico; qualquer outro
        // valor, inclusive o dtype canônico escrito direto, é recusado.
        val dtype = DTYPE_PUBLICADO_PARA_CONVERSAO[raiz.optString("dtype")] ?: return null
        if (versao != VERSAO || soc != SOC || arch != HTP_ARCH) return null
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
     * Marcador do pacote: a ASSINATURA do manifesto (mesma linha do formato
     * original, para o pacote instalado antes desta rodada continuar legível) e,
     * a partir da R105, a impressão de disco de cada arquivo — bytes, data de
     * modificação e SHA. É essa impressão que permite a conferência rápida de
     * cada processo sem rehash de 646 MiB, no mesmo desenho do QAIRT e das
     * dependências nativas.
     */
    data class Marcador(
        /** Linha de assinatura, idêntica a [textoMarcadorEsperado]. */
        val assinatura: String,
        val itens: List<ItemMarcador>,
    ) {
        /** Marcador do formato anterior (só a assinatura): autoriza 1 processo. */
        val legado: Boolean get() = itens.isEmpty()
    }

    /** Impressão de disco de um arquivo do pacote. */
    data class ItemMarcador(val nome: String, val bytes: Long, val mtime: Long, val sha256: String)

    /**
     * Resultado da conferência rápida (por processo). Três desfechos, distintos
     * de propósito:
     *
     * - [Ok]: marcador da versão instalada confere com o manifesto e cada arquivo
     *   existe, com o tamanho e a data registrados. A rota HTP PODE ser admitida
     *   já no primeiro pedido do processo;
     * - [Reverificar]: falta o marcador, o formato/versão é anterior, ou o pacote
     *   foi marcado por falha de sessão. A verificação completa (SHA de todos os
     *   arquivos, que reescreve o marcador) decide — enquanto ela não termina, a
     *   rota é CPU;
     * - [Recusar]: o tamanho ou a data de um arquivo DIVERGE do que foi instalado.
     *   Não há o que reverificar: o pacote em disco não é o pacote conferido, e a
     *   rota é CPU até nova instalação.
     */
    sealed class Conferencia {
        data class Ok(
            val dir: File,
            val manifesto: Manifesto,
            /** Marcador do formato anterior: admite 1 processo e manda regravar. */
            val legado: Boolean = false,
        ) : Conferencia()

        data class Reverificar(val motivo: String) : Conferencia()
        data class Recusar(val motivo: String) : Conferencia()
    }

    /**
     * Arquivo-sinal de "reverificação completa pendente".
     *
     * Gravado quando a sessão HTP falha ao abrir (ou quando uma ordem já rodou
     * pela metade no QNN) e lido no início do processo seguinte: o pacote volta a
     * ser conferido por SHA antes de autorizar a rota. Fica FORA do diretório da
     * versão para não confundir a conferência rápida do que foi instalado.
     */
    const val REVERIFICAR = ".reverificar"

    /**
     * Arquivo-sinal de pacote CORROMPIDO (R106).
     *
     * Gravado quando a verificação completa por SHA terminou e o conteúdo em
     * disco NÃO é o do manifesto (SHA, bytes ou arquivo ausente). Fica FORA do
     * diretório da versão, como o [REVERIFICAR], e **nada é apagado por causa
     * dele**: a entrada fixa passa a oferecer "Reinstalar aceleração NPU" e o
     * usuário decide. É apagado quando uma verificação completa passa (botão
     * "Verificar novamente") ou quando uma instalação nova conclui.
     */
    const val CORROMPIDO = ".corrompido"

    /** Motivo registrado quando o pacote está marcado como corrompido. */
    fun corrompido(raiz: File): String? = File(raiz, CORROMPIDO)
        .takeIf { it.isFile }
        ?.let { runCatching { it.readText() }.getOrNull()?.trim() }

    fun corrompido(context: Context): String? = corrompido(raiz(context))

    /** Grava (atomicamente) o sinal de corrompido com o motivo da verificação. */
    internal fun marcarCorrompido(raiz: File, motivo: String) {
        raiz.mkdirs()
        gravarAtomico(raiz, CORROMPIDO, motivo)
    }

    /**
     * Estado RÁPIDO do pacote (sem hash): marcador presente, arquivos com o
     * tamanho e a data registrados na instalação. É a conferência de CADA
     * processo; a verificação por SHA é a [verificarCompleto].
     *
     * `Ausente` quando não há marcador ou falta um arquivo; `Invalido` quando o
     * marcador não confere com o manifesto ou quando tamanho/data divergem. A UI
     * usa este estado para "NPU ativa" — por isso ele nunca hasheia.
     */
    fun estado(context: Context): Estado = estadoEm(dirDaVersao(context))

    /** Texto canônico do marcador: versão + hash do manifesto (sem tocar em arquivos). */
    fun textoMarcadorEsperado(manifesto: Manifesto): String =
        "versao=${manifesto.versao} arquivos=${manifesto.itens.size} " +
            "sha=${manifesto.itens.joinToString(",") { it.sha256 }}"

    /**
     * Marcador completo, com a impressão de disco de cada arquivo do manifesto.
     *
     * A primeira linha é EXATAMENTE [textoMarcadorEsperado] — o pacote instalado
     * pela R104 tem só essa linha e continua sendo reconhecido (formato legado),
     * o que evita rebaixar um pacote íntegro a "não instalado" só pela mudança de
     * formato. As linhas `item=` são lidas do DIRETÓRIO FINAL: a data de
     * modificação só é estável depois da ativação por rename.
     */
    internal fun montarMarcador(manifesto: Manifesto, dir: File): String = buildString {
        appendLine(textoMarcadorEsperado(manifesto))
        appendLine(SEGREDO_MARCADOR_FORMATO)
        for (item in manifesto.itens) {
            val arquivo = File(dir, item.nome)
            append("item=").append(item.nome)
                .append(" bytes=").append(arquivo.length())
                .append(" mtime=").append(arquivo.lastModified())
                .append(" sha=").append(item.sha256)
                .appendLine()
        }
    }.trimEnd()

    /** Formato do marcador desta versão (a presença da linha já é o "v2"). */
    private const val SEGREDO_MARCADOR_FORMATO = "formato=sig-whisper-npu-marcador/v2"

    /**
     * Grava o marcador de forma ATÔMICA: arquivo temporário no mesmo diretório e
     * `rename` por cima (no POSIX o rename substitui de forma atômica).
     *
     * R105 — vacina da C1: `writeText` trunca antes de escrever, e a conferência
     * rápida de OUTRA thread/processo (a tela abre enquanto a verificação completa
     * roda) lia o `.installed` pela metade, devolvia `marcador_ilegivel` e deixava
     * a rota em CPU com o pacote íntegro. Com rename, quem lê vê o marcador
     * anterior ou o novo — nunca um truncado.
     */
    internal fun gravarMarcador(dir: File, texto: String): Boolean =
        gravarAtomico(dir, MARCADOR, texto)

    /**
     * Grava um arquivo pequeno do controle do pacote (marcador ou sinal) de
     * forma ATÔMICA: temporário no mesmo diretório + `rename` por cima.
     *
     * R105 — vacina da C1: `writeText` trunca antes de escrever, e a conferência
     * rápida de OUTRA thread/processo (a tela abre enquanto a verificação completa
     * roda) lia o `.installed` pela metade, devolvia `marcador_ilegivel` e deixava
     * a rota em CPU com o pacote íntegro. Com rename, quem lê vê o marcador
     * anterior ou o novo — nunca um truncado.
     */
    private fun gravarAtomico(dir: File, nome: String, texto: String): Boolean = runCatching {
        val destino = File(dir, nome)
        val temporario = File(dir, "$nome.tmp")
        temporario.writeText(texto)
        // POSIX (Android): rename por cima SUBSTITUI de forma atômica — o leitor
        // vê o marcador anterior ou o novo, nunca um truncado.
        if (temporario.renameTo(destino)) {
            true
        } else if (runCatching { destino.delete() }.getOrDefault(false) && temporario.renameTo(destino)) {
            // Host de teste Windows não substitui no rename; a garantia de
            // atomicidade que importa é a do aparelho (POSIX).
            true
        } else {
            runCatching { temporario.delete() }
            false
        }
    }.getOrDefault(false)

    /**
     * Lê o marcador. Devolve `null` quando nem a assinatura do manifesto está
     * presente ou quando uma linha `item=` está malformada — nesse caso a
     * verificação completa (e não a rápida) decide.
     */
    internal fun lerMarcador(texto: String): Marcador? {
        val linhas = texto.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val assinatura = linhas.firstOrNull() ?: return null
        if (!assinatura.startsWith("versao=")) return null
        if (linhas.size == 1) return Marcador(assinatura, emptyList())
        if (linhas[1] != SEGREDO_MARCADOR_FORMATO) return null
        val itens = ArrayList<ItemMarcador>(linhas.size - 2)
        for (linha in linhas.drop(2)) {
            if (!linha.startsWith("item=")) return null
            val campos = linha.removePrefix("item=").split(" ")
            // nome é o único campo que pode conter espaço; os três últimos são fixos.
            if (campos.size < 4) return null
            val nome = campos.dropLast(3).joinToString(" ")
            val bytes = campos[campos.size - 3].removePrefix("bytes=").toLongOrNull() ?: return null
            val mtime = campos[campos.size - 2].removePrefix("mtime=").toLongOrNull() ?: return null
            val sha = campos.last().removePrefix("sha=")
            if (nome.isBlank() || sha.length != 64) return null
            itens.add(ItemMarcador(nome, bytes, mtime, sha.lowercase()))
        }
        return Marcador(assinatura, itens)
    }

    /**
     * Conferência rápida de um diretório instalado, com a raiz onde vive o sinal
     * de reverificação. É o seam sem `Context` do que roda em cada processo.
     */
    internal fun conferenciaEm(raiz: File, dir: File): Conferencia {
        // Falha de sessão no processo anterior: o SHA decide antes de nova rota.
        if (File(raiz, REVERIFICAR).isFile) return Conferencia.Reverificar("sessao_anterior")
        // R106 — pacote já marcado como corrompido pela verificação completa: a
        // conferência rápida NÃO readmite (nem com marcador e data em ordem); a
        // rota volta pelo botão "Verificar novamente" ou por uma reinstalação.
        corrompido(raiz)?.let { return Conferencia.Recusar("corrompido:$it") }
        return conferenciaMarcador(dir)
    }

    /** Conferência do marcador contra o disco (sem o sinal de reverificação). */
    internal fun conferenciaMarcador(dir: File): Conferencia {
        val marcador = File(dir, MARCADOR)
        if (!marcador.isFile) return Conferencia.Reverificar("marcador_ausente")
        val lido = runCatching { lerMarcador(marcador.readText()) }.getOrNull()
            ?: return Conferencia.Reverificar("marcador_ilegivel")
        val manifesto = runCatching {
            lerManifesto(File(dir, NOME_MANIFESTO).readText())
        }.getOrNull() ?: return Conferencia.Reverificar("manifesto_ilegivel")
        if (lido.assinatura != textoMarcadorEsperado(manifesto)) {
            return Conferencia.Reverificar("marcador_divergente")
        }
        // Existência e tamanho valem SEMPRE; a data só quando o marcador a tem.
        for (item in manifesto.itens) {
            val arquivo = File(dir, item.nome)
            if (!arquivo.isFile) return Conferencia.Recusar("ausente:${item.nome}")
            if (arquivo.length() != item.bytes) {
                return Conferencia.Recusar("tamanho:${item.nome}:${arquivo.length()}!=${item.bytes}")
            }
        }
        if (lido.legado) {
            // Formato anterior: a assinatura confere, mas a data não foi gravada.
            // Admite ESTE processo e agenda a verificação completa, que regrava o
            // marcador com a impressão de disco (e derruba a rota se o SHA falhar).
            return Conferencia.Ok(dir, manifesto, legado = true)
        }
        val porNome = lido.itens.associateBy { it.nome }
        for (item in manifesto.itens) {
            val registro = porNome[item.nome] ?: return Conferencia.Reverificar("marcador_incompleto")
            if (registro.bytes != item.bytes || registro.sha256 != item.sha256) {
                return Conferencia.Reverificar("marcador_divergente")
            }
            val arquivo = File(dir, item.nome)
            if (arquivo.lastModified() != registro.mtime) {
                return Conferencia.Recusar("data:${item.nome}")
            }
        }
        if (lido.itens.size != manifesto.itens.size) return Conferencia.Reverificar("marcador_incompleto")
        return Conferencia.Ok(dir, manifesto)
    }

    /** Conferência rápida do pacote instalado, no padrão de cada processo. */
    fun conferenciaRapida(context: Context): Conferencia =
        conferenciaEm(raiz(context), dirDaVersao(context))

    /**
     * Marca o pacote para verificação COMPLETA no próximo processo (falha de
     * sessão HTP). Barato e sem efeito imediato: quem lê é [conferenciaEm].
     */
    fun marcarParaReverificacao(raiz: File) {
        runCatching {
            raiz.mkdirs()
            File(raiz, REVERIFICAR).writeText("sessao")
        }
    }

    /** `true` quando há reverificação completa pendente. */
    fun precisaReverificar(raiz: File): Boolean = File(raiz, REVERIFICAR).isFile

    /**
     * Verificação COMPLETA (todos os SHA) sobre um diretório, sem exigir marcador
     * prévio: é ela que MIGRA o marcador do formato anterior, confere a instalação
     * e rearma o pacote depois de uma falha de sessão. Em sucesso, regrava o
     * marcador com a impressão de disco atual e apaga o sinal de reverificação.
     */
    @VisibleForTesting
    internal fun verificarCompletoEm(
        raiz: File,
        dir: File,
        hash: (File) -> String = this::sha256,
    ): Estado {
        val textoManifesto = File(dir, NOME_MANIFESTO)
        val manifesto = runCatching { lerManifesto(textoManifesto.readText()) }.getOrNull()
        return when {
            manifesto == null && !textoManifesto.isFile -> Estado.Ausente("sem manifesto em $dir")
            manifesto == null -> {
                // O manifesto em disco não é o contrato do pacote: corrompido.
                marcarCorrompido(raiz, "manifesto_ilegivel")
                Estado.Invalido("manifesto ilegivel")
            }
            else -> {
                val motivo = conferirArquivos(manifesto, dir, hash)
                if (motivo != null) {
                    // R106 — o conteúdo em disco não é o do manifesto. Marca (sem
                    // apagar NADA) e mantém a rota em CPU até o usuário agir.
                    marcarCorrompido(raiz, motivo)
                    Estado.Invalido(motivo)
                } else {
                    if (!gravarMarcador(dir, montarMarcador(manifesto, dir))) {
                        Estado.Invalido("marcador_nao_gravado")
                    } else {
                        runCatching { File(raiz, REVERIFICAR).delete() }
                        // R106 — pacote conferido por SHA: a marca de corrompido
                        // não vale mais (é ela que barrava a conferência rápida).
                        runCatching { File(raiz, CORROMPIDO).delete() }
                        Estado.Pronto(dir, manifesto)
                    }
                }
            }
        }
    }

    /** Verificação COMPLETA (todos os SHA) do pacote instalado. */
    @VisibleForTesting
    internal fun verificarCompleto(context: Context, hash: (File) -> String = this::sha256): Estado =
        verificarCompletoEm(raiz(context), dirDaVersao(context), hash)

    /**
     * Verifica o pacote em SEGUNDO PLANO, **sem baixar nada** (R101 §T03).
     *
     * O download agora é ação explícita do usuário ([instalar] chamado pela
     * tela). O que continua acontecendo sozinho é a conferência de SHA do pacote
     * que já está em disco: barata perto da abertura da sessão e é ela que
     * autoriza a rota HTP.
     *
     * R106/T02 — a chamada é IDEMPOTENTE por processo: a primeira agenda, as
     * seguintes não fazem nada (nem quando a conferência rápida recusa de novo).
     * Devolve `true` quando ESTA chamada agendou.
     *
     * Nunca lança e nunca bloqueia quem chamou.
     */
    fun verificarEmSegundoPlano(context: Context): Boolean =
        verificacaoDoProcesso.agendar { iniciarVerificacao(context, "automatica", null) }

    /**
     * Verificação completa pedida pelo USUÁRIO ("Verificar novamente"), sem
     * download. Diferente da automática, não é limitada pela marca de processo:
     * é uma ação explícita e pode ser repetida. O resultado é devolvido na
     * thread chamadora (a de fundo criada aqui), para a tela atualizar o rótulo.
     */
    fun verificarAgora(context: Context, aoTerminar: (Estado) -> Unit = {}) {
        iniciarVerificacao(context, "manual", aoTerminar)
    }

    private fun iniciarVerificacao(
        context: Context,
        origem: String,
        aoTerminar: ((Estado) -> Unit)?,
    ) {
        if (!emAndamento.compareAndSet(false, true)) return
        val app = context.applicationContext
        val raiz = raiz(app)
        Thread({
            var estado: Estado = Estado.Invalido("interno")
            try {
                val atual = verificarCompletoEm(raiz, File(raiz, VERSAO))
                estado = atual
                when (atual) {
                    is Estado.Pronto ->
                        WhisperOrtHtpRota.pacoteRevalidado(atual.manifesto, "revalidado:$origem")
                    is Estado.Invalido ->
                        WhisperOrtHtpRota.pacoteIndisponivel("verificacao:" + atual.motivo)
                    is Estado.Ausente -> WhisperOrtHtpRota.pacoteIndisponivel("ausente")
                }
            } catch (falha: Throwable) {
                android.util.Log.w(TAG, "verificacao NPU lancou: ${falha.javaClass.simpleName}: ${falha.message}")
                WhisperOrtHtpRota.pacoteIndisponivel("interno:" + (falha.message ?: "falha"))
            } finally {
                emAndamento.set(false)
            }
            runCatching { aoTerminar?.invoke(estado) }
        }, "whisper-npu-verificacao").apply { isDaemon = true }.start()
    }

    /**
     * Agendador de UMA execução por processo (R106/T02).
     *
     * O `AtomicBoolean` que guardava a verificação era liberado no `finally`, o
     * que permitia uma nova rodada a cada recusa; a ordem exige **uma única
     * verificação agendada por processo**. Este seam é puro e testado em JVM:
     * `agendar` devolve `true` só na primeira vez e executa o bloco na hora.
     */
    internal class AgendamentoUnico {
        private val agendada = AtomicBoolean(false)

        fun agendar(executar: () -> Unit): Boolean {
            if (!agendada.compareAndSet(false, true)) return false
            executar()
            return true
        }

        val jaAgendada: Boolean get() = agendada.get()
    }

    private val verificacaoDoProcesso = AgendamentoUnico()
    private val emAndamento = AtomicBoolean(false)

    /**
     * Plano de download da ação "Ativar aceleração NPU" (regra PURA).
     *
     * O QAIRT só entra na lista quando ainda não está instalado: o usuário vê
     * exatamente o que vai baixar, e o pacote da NPU nunca repete as bibliotecas.
     */
    fun planoDeDownload(qairtInstalado: Boolean): DownloadSizeFormat.Plano {
        val itens = ArrayList<DownloadSizeFormat.Arquivo>(2)
        itens.add(DownloadSizeFormat.Arquivo("sig-whisper-w8a16-sm8550-v$VERSAO.zip", BYTES_ZIP))
        if (!qairtInstalado) {
            itens.add(DownloadSizeFormat.Arquivo(NOME_ZIP_QAIRT, QairtDependencyManager.downloadSize()))
        }
        return DownloadSizeFormat.Plano(rotulo = "Aceleração NPU (SM8550)", arquivos = itens)
    }

    /** Bytes que a ação vai baixar, considerando o que já está instalado. */
    fun bytesParaBaixar(qairtInstalado: Boolean, npuInstalado: Boolean): Long {
        val npu = if (npuInstalado) 0L else BYTES_ZIP
        return npu + if (qairtInstalado) 0L else QairtDependencyManager.downloadSize()
    }

    /**
     * Espaço livre necessário para a ação inteira (regra PURA).
     *
     * Cada pacote precisa do ZIP **e** da extração: o QAIRT extrai ~148 MB de um
     * ZIP de ~53 MB e o NPU extrai 677 MB de um ZIP do mesmo tamanho (ZIP_STORED).
     * A folga de 64 MiB cobre staging e cabeçalhos — o `install` confere de novo
     * antes de cada etapa, então esta conta é a promessa feita ao usuário.
     */
    fun espacoNecessario(qairtInstalado: Boolean, npuInstalado: Boolean): Long {
        var total = 0L
        if (!npuInstalado) total += BYTES_ZIP + BYTES_CONTEUDO + 64L * 1024 * 1024
        if (!qairtInstalado) {
            total += QairtDependencyManager.downloadSize() + 148L * 1024 * 1024 + 64L * 1024 * 1024
        }
        return total
    }

    /**
     * Política de rede, agora **informativa**: `null` = rede não medida; texto =
     * motivo do aviso ("rede medida", "sem rede ativa"...). Quem decide é o
     * usuário, no diálogo — a R101 tirou o download automático, então esta função
     * não barra mais nada; ela só deixa a tela dizer a verdade sobre o custo.
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

    /** Cancelamento cooperativo do download/extracao (a tela tem botao). */
    class Cancelado : IllegalStateException("download cancelado")

    /**
     * Baixa, valida o ZIP, extrai em staging, confere o manifesto por arquivo e
     * ativa por rename. Idempotente quando já está pronto.
     *
     * @param cancelado consultado entre blocos do download e entre entradas da
     *   extração; `true` aborta com [Cancelado] e deixa o pacote anterior intacto.
     */
    fun instalar(
        context: Context,
        cancelado: () -> Boolean = { false },
        onProgresso: (Progresso) -> Unit = { },
    ): Result<Unit> = runCatching {
        val raiz = raiz(context)
        val dir = File(raiz, VERSAO)
        // R105/T01 — o SHA completo é conferido NA INSTALAÇÃO e na troca de versão.
        // Pacote já extraído e íntegro NÃO é baixado de novo: a verificação
        // completa reescreve o marcador (inclusive o de formato anterior) e libera
        // a rota. Só conteúdo divergente volta ao download.
        when (val completo = verificarCompletoEm(raiz, dir)) {
            is Estado.Pronto -> {
                WhisperOrtHtpRota.pacoteVerificado(completo.manifesto)
                return@runCatching
            }
            else -> Unit
        }
        instalarEm(
            cacheDir = context.cacheDir,
            raiz = raiz,
            cancelado = cancelado,
            onProgresso = onProgresso,
        ).getOrThrow()
    }

    /**
     * R103/T02 — o mesmo fluxo de [instalar] com diretórios, origem do ZIP e o
     * aviso à rota injetados. É o seam usado pelo teste JVM de ponta a ponta
     * contra o ZIP publicado; a lógica de produção não muda, só recebe parâmetros.
     */
    @VisibleForTesting
    internal fun instalarEm(
        cacheDir: File,
        raiz: File,
        urlPacote: String = URL_PACOTE,
        cancelado: () -> Boolean = { false },
        onProgresso: (Progresso) -> Unit = { },
        aoMarcarVerificado: (Manifesto) -> Unit = { WhisperOrtHtpRota.pacoteVerificado(it) },
    ): Result<Unit> = runCatching {
        check(SHA256_ZIP.length == 64 && SHA256_ZIP.any { it != '0' }) {
            "SHA-256 do pacote NPU nao configurado"
        }
        val zipBytes = if (BYTES_ZIP > 0L) BYTES_ZIP else 0L
        raiz.mkdirs()
        val destino = File(raiz, VERSAO)
        val arquivoZip = File(cacheDir, "whisper-npu-v$VERSAO.zip.download")

        if (zipBytes > 0L && cacheDir.usableSpace < zipBytes + 32L * 1024 * 1024) {
            error("espaco insuficiente para baixar ${zipBytes / 1_048_576} MB")
        }
        val staging = File(raiz, ".staging-${System.nanoTime()}")
        staging.deleteRecursively()
        // R103/T02.4: o try cobre também download e validação do SHA. Qualquer
        // saída (sucesso, SHA divergente, cancelamento, espaço, extração) apaga
        // o .zip.download do cache.
        try {
            baixar(arquivoZip, urlPacote, cancelado, onProgresso)
            if (cancelado()) throw Cancelado()
            onProgresso(Progresso(arquivoZip.length(), arquivoZip.length(), "validando"))
            check(sha256(arquivoZip).equals(SHA256_ZIP, ignoreCase = true)) {
                "SHA-256 do pacote NPU nao confere"
            }
            onProgresso(Progresso(arquivoZip.length(), arquivoZip.length(), "instalando"))
            staging.mkdirs()
            val estimado = maxOf(arquivoZip.length(), BYTES_ZIP) + 96L * 1024 * 1024
            check(raiz.usableSpace >= estimado) {
                "espaco insuficiente para extrair: precisa ${estimado / 1_048_576} MB"
            }
            extrairZip(arquivoZip, staging, cancelado)
            val manifesto = runCatching {
                lerManifesto(File(staging, NOME_MANIFESTO).readText())
            }.getOrNull() ?: error("manifesto ausente/invalido no pacote")
            val precisa = manifesto.bytesDescompactados() + 64L * 1024 * 1024
            check(raiz.usableSpace >= precisa) {
                "espaco insuficiente para extrair: precisa ${precisa / 1_048_576} MB"
            }
            val motivo = conferirArquivos(manifesto, staging)
            check(motivo == null) { "pacote incompleto: $motivo" }
            gravarMarcador(staging, textoMarcadorEsperado(manifesto))

            val anterior = File(raiz, ".previous")
            anterior.deleteRecursively()
            if (destino.exists() && !destino.renameTo(anterior)) destino.deleteRecursively()
            val ativado = staging.renameTo(destino) || runCatching {
                staging.copyRecursively(destino, overwrite = true)
                staging.deleteRecursively()
                destino.exists()
            }.getOrDefault(false)
            check(ativado) { "falha ao ativar o pacote NPU" }
            // R105/T01 — o marcador é REGRAVADO no diretório final: a data de
            // modificação de cada arquivo só é estável depois da ativação (o
            // fallback por cópia não preserva mtime). É essa impressão que a
            // conferência de cada processo compara.
            check(gravarMarcador(destino, montarMarcador(manifesto, destino))) {
                "marcador do pacote NPU nao gravado"
            }
            check(estadoEm(destino) is Estado.Pronto) { "pacote NPU nao ficou ativo" }
            anterior.deleteRecursively()
            // R106 — instalação nova e conferida: nenhum sinal de corrupção vale.
            runCatching { File(raiz, CORROMPIDO).delete() }
            runCatching { File(raiz, REVERIFICAR).delete() }
            // Instalado E conferido por SHA: só agora a rota pode pedir HTP.
            aoMarcarVerificado(manifesto)
        } finally {
            staging.deleteRecursively()
            arquivoZip.delete()
        }
    }

    /**
     * Estado rápido sobre um diretório já instalado (seam puro, sem Context).
     *
     * R105/T01 — mapeia a conferência do marcador: marcador/formato ausente vira
     * `Ausente` (a UI oferece a instalação), divergência de tamanho ou de data
     * vira `Invalido` (a rota é CPU e a tela não diz "NPU ativa").
     */
    internal fun estadoEm(destino: File): Estado = when (val c = conferenciaMarcador(destino)) {
        is Conferencia.Ok -> Estado.Pronto(destino, c.manifesto)
        is Conferencia.Reverificar -> when (c.motivo) {
            "marcador_ausente" -> Estado.Ausente("sem marcador em $destino")
            else -> Estado.Invalido(c.motivo)
        }
        is Conferencia.Recusar -> when {
            c.motivo.startsWith("ausente:") -> Estado.Ausente(c.motivo)
            else -> Estado.Invalido(c.motivo)
        }
    }

    private fun baixar(
        destino: File,
        url: String,
        cancelado: () -> Boolean,
        onProgresso: (Progresso) -> Unit,
    ) {
        destino.parentFile?.mkdirs()
        if (url.startsWith("file:", ignoreCase = true)) {
            val origem = File(java.net.URI(url))
            BufferedInputStream(FileInputStream(origem)).use { entrada ->
                gravarComProgresso(destino, entrada, origem.length(), cancelado, onProgresso)
            }
            return
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 300_000
            setRequestProperty("User-Agent", "SIG-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(conn.responseCode in 200..299) { "download NPU retornou HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0L } ?: BYTES_ZIP
            BufferedInputStream(conn.inputStream).use { entrada ->
                gravarComProgresso(destino, entrada, total, cancelado, onProgresso)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun gravarComProgresso(
        destino: File,
        entrada: java.io.InputStream,
        total: Long,
        cancelado: () -> Boolean,
        onProgresso: (Progresso) -> Unit,
    ) {
        FileOutputStream(destino).use { saida ->
            val buffer = ByteArray(256 * 1024)
            var baixados = 0L
            while (true) {
                if (cancelado()) throw Cancelado()
                val n = entrada.read(buffer)
                if (n < 0) break
                saida.write(buffer, 0, n)
                baixados += n
                onProgresso(Progresso(baixados, total, "baixando"))
            }
            saida.fd.sync()
        }
    }

    /** Extrai normalizando `\` (ZIP gerado no Windows) e recusando caminho fora da raiz. */
    private fun extrairZip(arquivo: File, destino: File, cancelado: () -> Boolean = { false }) {
        val canonico = destino.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(arquivo))).use { zip ->
            while (true) {
                if (cancelado()) throw Cancelado()
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
