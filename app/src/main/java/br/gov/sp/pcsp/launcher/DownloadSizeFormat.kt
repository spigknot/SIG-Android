package br.gov.sp.pcsp.launcher

/**
 * Formatação e detalhamento dos tamanhos de download (seam PURO, sem Android).
 *
 * Existe por três defeitos medidos (25/09/2026) nas telas de download do app:
 *
 * 1. **Divergência de base.** O mesmo arquivo aparecia como "3.3 GB" numa linha
 *    do diálogo (decimal) e entrava na conta como "3112 MB" (binário) — o
 *    usuário via dois números para a mesma coisa. Agora há UMA base em todo o
 *    app: **decimal (1 KB = 1000 B)**, a mesma do manifesto publicado e a que
 *    [GraniteNarLlm.Variante.tamanhoLegivel] já usava (e que os testes travam).
 * 2. **`formatBytes` sem unidade GB.** O `GraniteActivity.formatBytes` só
 *    tinha B/KB/MB, então o pacote do NAR aparecia como "4700.13 MB" — um
 *    download de 4,93 GB que ninguém lê como viável no celular.
 * 3. **Falta do detalhamento por arquivo.** Os diálogos diziam só o total
 *    ("componentes nativos (~41 MB)") ou NADA (Whisper, Texto, atualização do
 *    app). O usuário não sabia o que estava sendo baixado.
 *
 * O formato do detalhamento é o MESMO do log do SigUpdater (SIG Windows):
 *
 *     15:04:22  Verificando pacotes disponíveis.
 *     15:04:23  Sincronização 20260925_002: 41 arquivo(s) para baixar, 3 para remover.
 *     15:04:23  Baixando arquivo 7/41: lib/libsig_llama.so (68%)
 *     15:04:25  Aplicando a sincronização com rollback protegido...
 *     15:04:31  Instalação concluída. Log: ...
 *
 * Aqui o cabeçalho vem antes da lista e a lista é sempre completa (o plano é
 * conhecido antes de perguntar "Baixar?"), com o total no fim — a lista é o
 * contrato; [Linha] carrega o que a tela mostra.
 */
object DownloadSizeFormat {
    /** Unidades decimais (SI): o que o manifesto e a R2 publicam. */
    private val UNIDADES = arrayOf("B", "KB", "MB", "GB", "TB")

    /**
     * Tamanho legível em base DECIMAL, com uma casa a partir de KB.
     *
     * 885.098 B -> "885.098 B"? Não: bytes são inteiros, então "885.098 B" vira
     * "885 KB" por division exata. O truque é manter "B" só abaixo de 1000 e
     * arredondar o resto — nunca mostrar "0 MB" para um arquivo de 885 KB.
     */
    fun legivel(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        if (bytes < 1000L) return "$bytes B"
        var valor = bytes.toDouble()
        var unidade = 0
        while (valor >= 1000.0 && unidade < UNIDADES.lastIndex) {
            valor /= 1000.0
            unidade++
        }
        // >= 100 GB não é caso de download do SIG, mas não pode estourar o array.
        return "%.1f %s".format(valor, UNIDADES[unidade])
    }

    /**
     * Um arquivo do plano de download.
     *
     * @param nome nome exibido (o do ZIP/URL, sem diretório quando irrelevante)
     * @param bytes tamanho total do arquivo
     * @param jaBaixado true quando o arquivo já está íntegro no aparelho — o
     *   plano mostra só o que FALTA, mas o breakdown completo ajuda a entender
     *   o pacote
     */
    data class Arquivo(val nome: String, val bytes: Long, val jaBaixado: Boolean = false)

    /**
     * O plano de download: a lista de arquivos + o total, pronto para o diálogo.
     *
     * @param rotulo nome do pacote ("Componentes nativos", "Granite 4.1 NAR"...)
     * @param arquivos itens a baixar (ordem do plano, não por tamanho)
     * @param totalFallbackBytes total quando [arquivos] não descreve tudo (ex.:
     *   o ZIP nativo: o app baixa UM arquivo, o ZIP)
     */
    data class Plano(
        val rotulo: String,
        val arquivos: List<Arquivo>,
        val totalFallbackBytes: Long = 0L,
    ) {
        /** Soma dos itens detalhados. */
        val totalDetalhado: Long get() = arquivos.sumOf { it.bytes }

        /** Total a anunciar: detalhado quando há detalhe, senão o fallback. */
        val totalBytes: Long
            get() = if (arquivos.isEmpty()) totalFallbackBytes else totalDetalhado

        /** Só o que falta baixar (o resto já está no aparelho). */
        val pendenteBytes: Long
            get() = arquivos.filterNot { it.jaBaixado }.sumOf { it.bytes }

        /** true quando não há nada a fazer (tudo já baixado). */
        val completo: Boolean get() = arquivos.isNotEmpty() && arquivos.all { it.jaBaixado }
    }

    /**
     * O texto do plano, no estilo do log do SigUpdater.
     *
     * ```
     * 25 arquivo(s) · total 4,93 GB · faltam 4,93 GB
     * encoder-pesos.data ............ 1,04 GB
     * llm-fp16.onnx.data ............ 3,11 GB
     * ```
     *
     * A lista é SEMPRE COMPLETA: nada é agrupado em "outros N arquivo(s)".
     * O agrupamento existia para caber na tela, mas o pedido é o contrário — o
     * usuário quer ler tudo. O corte para caber é responsibility do DIÁLOGO
     * ([DownloadPlanDialog] usa ScrollView); aqui a verdade sai inteira.
     *
     * Separador por ponto para alinhar em fonte monoespaçada, como o quadro de
     * lote do RemoteStt (a coluna de tamanho é END, o resto é a etiqueta).
     */
    fun plano(plano: Plano): String {
        if (plano.arquivos.isEmpty()) {
            val total = if (plano.totalFallbackBytes > 0L) " · total ${legivel(plano.totalFallbackBytes)}" else ""
            return "arquivo único$total"
        }
        return cabecalho(plano) + "\n" + linhas(plano.arquivos)
    }

    /**
     * Só a primeira linha: "18 arquivo(s) · total 71,0 MB · faltam 71,0 MB".
     *
     * Separado de [plano] para a tela poder mostrar o total no status e a lista
     * rolável embaixo, sem repetir a contagem.
     */
    fun cabecalho(plano: Plano): String {
        if (plano.arquivos.isEmpty()) {
            val total = if (plano.totalFallbackBytes > 0L) " · total ${legivel(plano.totalFallbackBytes)}" else ""
            return "1 arquivo$total"
        }
        val cab = StringBuilder()
        cab.append(plano.arquivos.size).append(" arquivo(s)")
        if (plano.totalDetalhado > 0L) cab.append(" · total ${legivel(plano.totalDetalhado)}")
        if (plano.pendenteBytes != plano.totalDetalhado) {
            cab.append(" · faltam ${legivel(plano.pendenteBytes)}")
        }
        return cab.toString()
    }

    /**
     * Uma linha por arquivo, alinhadas: "✓ encoder-pesos.data   1,04 GB".
     *
     * O `✓` marca o que já está íntegro no aparelho; a lista mostra o plano
     * inteiro, então essa marca é o que permite ao usuário ver o que falta.
     */
    fun linhas(arquivos: List<Arquivo>): String {
        if (arquivos.isEmpty()) return ""
        // O nome é TRUNCADO no meio (não só padEnd): um `lib/libQnnHtpPrepare.so`
        // de 30 caracteres estoura a largura útil do diálogo, e o padding sozinho
        // não impede a linha de ser larga demais.
        val largura = arquivos.maxOf { it.nome.length }.coerceAtMost(38)
        val cab = StringBuilder()
        for (item in arquivos) {
            cab.append(if (item.jaBaixado) "✓" else " ").append(' ')
            cab.append(item.nome.truncado(largura).padEnd(largura))
            cab.append(' ').append(legivel(item.bytes))
            cab.append('\n')
        }
        return cab.toString().trimEnd('\n')
    }

    /**
     * Resumo de UMA linha para o título/estado quando não cabe a lista.
     *
     * "Componentes nativos: 18 arquivo(s) · total 67,7 MB"
     */
    fun resumoLinha(plano: Plano): String {
        if (plano.arquivos.isEmpty()) {
            val total = if (plano.totalFallbackBytes > 0L) " · total ${legivel(plano.totalFallbackBytes)}" else ""
            return "${plano.rotulo}: 1 arquivo$total"
        }
        val sufixo = if (plano.completo) " · já instalado" else " · total ${legivel(plano.totalBytes)}"
        return "${plano.rotulo}: ${plano.arquivos.size} arquivo(s)$sufixo"
    }

    /**
     * Acumulador do LOG do download, escrito linha a linha como o SigUpdater.
     *
     * A tela vai "colando" as linhas conforme o download avança: primeiro a
     * intenção ("Iniciando download"), depois uma linha por arquivo concluído, e
     * no fim o total. O usuário rola para cima e lê tudo antes de continuar.
     *
     * É PURO (sem Android) para o comportamento ser testável na JVM: o que se
     * testa aqui é a ordem das linhas e a ausência de duplicata — a colagem na
     * tela é responsabilidade do [DownloadPlanDialog].
     */
    class Log {
        private val linhas = mutableListOf<String>()

        /** As linhas acumuladas, na ordem em que foram compostas. */
        fun linhas(): List<String> = linhas.toList()

        /** O texto completo, como o log do SigUpdater mostra no fim. */
        fun texto(): String = linhas.joinToString("\n")

        /**
         * Primeira linha: o que vai ser baixado e de quanto tamanho.
         *
         * @param linha a composição já pronta (normalmente [cabecalho])
         */
        fun inicio(linha: String) {
            linhas += linha
        }

        /**
         * Uma linha de progresso ou de arquivo.
         *
         * Repetir exatamente a mesma linha seguidas vezes é ignorado: o
         * callback de progresso dispara dezenas de vezes por segundo e sem isso
         * o log vira milhares de linhas iguais.
         */
        fun etapa(linha: String) {
            if (linhas.lastOrNull() == linha) return
            linhas += linha
        }

        /** Arquivo concluído (o `✓` do breakdown do plano). */
        fun concluido(nome: String, bytes: Long) {
            linhas += "✓ $nome · ${legivel(bytes)}"
        }

        /**
         * Fim: total baixado e a confirmação.
         *
         * @param total total efetivamente baixado (0 = desconhecido)
         */
        fun fim(total: Long = 0L) {
            val sufixo = if (total > 0L) " · ${legivel(total)} no total" else ""
            linhas += "Download concluído$sufixo"
        }
    }

    /**
     * Cabeçalho do progresso durante o download (a linha que o SigUpdater escreve
     * em `status_var`): "Baixando: 68% (45,2 MB de 67,7 MB)".
     */
    fun progresso(downloaded: Long, total: Long, percent: Int): String =
        if (total > 0L) {
            "Baixando: $percent% (${legivel(downloaded)} de ${legivel(total)})"
        } else {
            "Baixando: $percent% (${legivel(downloaded)})"
        }

    /**
     * Linha de "qual arquivo está indo agora" (o `sync_file` do SigUpdater):
     * "Baixando arquivo 7/18: lib/libsig_llama.so (68%)".
     *
     * @param nome nome exibido do arquivo
     */
    fun arquivoAtual(indice: Int, total: Int, nome: String, percent: Int): String =
        "Baixando arquivo $indice/$total: $nome ($percent%)"

    /**
     * A MESMA linha, tolerante a total desconhecido.
     *
     * Sem o percentual (rede sem Content-Length) não se escreve "(0%)": ficaria
     * "0%" para um arquivo já pela metade, que é pior do que omitir o número.
     */
    fun arquivoAtualSemPercentual(indice: Int, total: Int, nome: String): String =
        "Baixando arquivo $indice/$total: $nome"

    /**
     * Corta o nome no MEIO quando passa da largura (igual ao `ellipsize=MIDDLE`
     * do quadro de lote do RemoteStt): o começo e o fim do nome dizem mais que
     * só o começo, e o sufixo da extensão é o que distingue `Q4_0` de `Q4_K_M`.
     */
    private fun String.truncado(largura: Int): String {
        if (length <= largura) return this
        val sobra = largura - 1
        val inicio = sobra / 2
        val fim = sobra - inicio
        return take(inicio) + "…" + takeLast(fim)
    }
}
