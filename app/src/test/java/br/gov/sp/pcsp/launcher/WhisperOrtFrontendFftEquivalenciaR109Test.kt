package br.gov.sp.pcsp.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * R109/T02 — a FFT de 400 pontos do produto contra o oráculo DFT, nos 210 clipes.
 *
 * Critérios PRÉ-REGISTRADOS pela ordem da R109 (os mesmos da R108, com o item
 * (b) endurecido):
 *
 * (a) log-mel em float32: `max |delta| <= 1e-5` em TODOS os 210 clipes
 *     (202 da M1-C + 8 da triagem da R97), com max, p99.9 e contagem de valores
 *     diferentes registrados;
 * (b) entrada INT16 do encoder HTP pela função do PRODUTO
 *     ([WhisperOrtHtpConversao.paraDtype], dtype `int16_le`): **zero** códigos
 *     diferentes. A R108 aceitava até 0,01% e `delta` de 1; a R109 exige
 *     igualdade bit a bit porque a FFT é exata nos 201 bins e a soma dos filtros
 *     é a mesma.
 *
 * O PCM vem de UM único utilitário ([WhisperOrtWav.ler]) para as duas
 * implementações.
 *
 * Caminhos em `D:/tmp/r109/r109-config.json` (override por `R109_CONFIG`). Sem o
 * arquivo o teste é PULADO (`Assume`), porque o corpus de fala não está no
 * repositório — a prova vale pelo recibo bruto da rodada, gravado em
 * `<saida>/t02-equivalencia.json`.
 */
class WhisperOrtFrontendFftEquivalenciaR109Test {

    private val configPath: String get() = System.getenv("R109_CONFIG") ?: "D:/tmp/r109/r109-config.json"

    private val config: JSONObject? by lazy {
        val file = File(configPath)
        if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else null
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Lista pinada: 202 da M1-C (ordem do arquivo) + 8 da triagem da R97. */
    private fun clipes(cfg: JSONObject): List<String> {
        val selecao = JSONObject(File(cfg.getString("selecao_m1c")).readText(Charsets.UTF_8))
        val itens = selecao.getJSONArray("itens")
        val ids = ArrayList<String>(itens.length() + 8)
        for (i in 0 until itens.length()) ids += itens.getJSONObject(i).getString("id")
        val triagem = cfg.getJSONArray("triagem_r97")
        for (i in 0 until triagem.length()) ids += triagem.getString(i)
        return ids
    }

    @Test
    fun `fft do produto reproduz o oraculo em sinais sinteticos`() {
        val produto = WhisperOrtFrontend()
        val oraculo = WhisperOrtFrontendDftOraculo()
        val n = WhisperOrtFrontend.N_SAMPLES
        val casos = listOf(
            "impulso" to FloatArray(n).also { it[1000] = 1f },
            "silencio" to FloatArray(n),
            "seno440" to FloatArray(n) { i ->
                if (i < 16_000) (0.5 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / 16_000.0)).toFloat() else 0f
            },
            "ruido" to run {
                var seed = 20_261_029L
                FloatArray(n) { _ ->
                    seed = (seed * 1_103_515_245L + 12_345L) and 0x7FFFFFFFL
                    val unit = seed.toDouble() / 0x7FFFFFFF.toDouble()
                    (0.2 * (unit * 2.0 - 1.0)).toFloat()
                }
            },
        )
        for ((nome, pcm) in casos) {
            val a = oraculo.logMel(pcm)
            val b = produto.logMel(pcm)
            assertEquals("$nome: tamanho", a.size, b.size)
            var max = 0.0
            for (i in a.indices) {
                val d = kotlin.math.abs(a[i] - b[i]).toDouble()
                if (d > max) max = d
            }
            assertTrue("$nome: max|delta| = $max > 1e-5", max <= 1e-5)
        }
    }

    @Test
    fun `fft do produto e o oraculo nos 210 clipes com a entrada int16 identica`() {
        val cfg = config
        assumeTrue("config ausente em $configPath: prova de corpus nao executada", cfg != null)
        requireNotNull(cfg)
        val corpus = File(cfg.getString("corpus"))
        assumeTrue("corpus ausente em $corpus", corpus.isDirectory)
        val saida = File(cfg.getString("saida")).also { it.mkdirs() }

        val produto = WhisperOrtFrontend()
        val oraculo = WhisperOrtFrontendDftOraculo()
        val ids = clipes(cfg)
        assertEquals("210 clipes (202 M1-C + 8 triagem R97), veio ${ids.size}", 210, ids.size)

        // Histograma EXATO de |delta| em float32: poucos valores distintos, então
        // cabe num mapa e o p99.9 sai exato (nada de amostragem).
        val histograma = HashMap<Int, Long>()
        var totalValores = 0L
        var maxGlobal = 0.0
        var diferem = 0L
        var codigosDiferem = 0L
        var codigoMaxDiff = 0
        val clipesComCodigoDiferente = JSONArray()
        val porClipe = JSONArray()
        val inicioNs = System.nanoTime()

        for ((indice, id) in ids.withIndex()) {
            val bytes = File(corpus, "$id.wav").readBytes()
            val leitura = WhisperOrtWav.ler(bytes)
            assertTrue("$id: leitura nao devolveu PCM ($leitura)", leitura is WhisperOrtWav.Leitura.Pcm)
            val pcm = (leitura as WhisperOrtWav.Leitura.Pcm).amostras

            val mOraculo = oraculo.logMel(pcm)
            val mProduto = produto.logMel(pcm)

            var maxClipe = 0.0
            var difClipe = 0L
            for (i in mOraculo.indices) {
                val bruto = kotlin.math.abs(mOraculo[i] - mProduto[i]).toDouble()
                totalValores++
                if (bruto != 0.0) {
                    diferem++
                    difClipe++
                    val bits = java.lang.Float.floatToIntBits(bruto.toFloat())
                    histograma[bits] = (histograma[bits] ?: 0L) + 1L
                }
                if (bruto > maxClipe) maxClipe = bruto
            }
            if (maxClipe > maxGlobal) maxGlobal = maxClipe

            // (b) entrada INT16 do encoder HTP, pela conversão do PRODUTO.
            val inteiroOraculo = WhisperOrtHtpConversao.paraDtype(
                mOraculo, WhisperOrtHtpConversao.DTYPE_I16,
            )
            val inteiroProduto = WhisperOrtHtpConversao.paraDtype(
                mProduto, WhisperOrtHtpConversao.DTYPE_I16,
            )
            assertEquals("$id: tamanho da entrada i16", inteiroOraculo.size, inteiroProduto.size)
            var difCodigosClipe = 0L
            var maiorCodigoClipe = 0
            var k = 0
            while (k < inteiroOraculo.size) {
                val a = ((inteiroOraculo[k].toInt() and 0xFF) or (inteiroOraculo[k + 1].toInt() shl 8)) and 0xFFFF
                val b = ((inteiroProduto[k].toInt() and 0xFF) or (inteiroProduto[k + 1].toInt() shl 8)) and 0xFFFF
                val d = kotlin.math.abs(a - b)
                if (d != 0) {
                    difCodigosClipe++
                    codigosDiferem++
                    if (d > maiorCodigoClipe) maiorCodigoClipe = d
                    if (d > codigoMaxDiff) codigoMaxDiff = d
                }
                k += 2
            }
            if (difCodigosClipe > 0) clipesComCodigoDiferente.put(id)

            porClipe.put(
                JSONObject().apply {
                    put("id", id)
                    put("wav_sha256", sha256(bytes))
                    put("amostras", pcm.size)
                    put("max_delta", maxClipe)
                    put("valores_diferentes", difClipe)
                    put("codigos_i16_diferentes", difCodigosClipe)
                    put("codigo_i16_max_delta", maiorCodigoClipe)
                },
            )
            if ((indice + 1) % 30 == 0) {
                println("R109-T02: ${indice + 1}/${ids.size} clipes")
            }
        }

        // p99.9 exato: menor valor cuja contagem acumulada atinge 99,9% do total.
        val ordenado = histograma.entries.sortedBy { java.lang.Float.intBitsToFloat(it.key) }
        val alvo = 0.999 * totalValores
        var acumulado = 0L
        var p999 = 0.0
        for (e in ordenado) {
            acumulado += e.value
            if (acumulado >= alvo) {
                p999 = java.lang.Float.intBitsToFloat(e.key).toDouble()
                break
            }
        }

        val recibo = JSONObject().apply {
            put("schema", "sig-whisper-npu-r109-equivalencia/v1")
            put("clipes", ids.size)
            put("valores_por_clipe", 128 * WhisperOrtFrontend.N_FRAMES)
            put("valores_totais", totalValores)
            put("criterio_a_max_delta", 1e-5)
            put("a_max_delta", maxGlobal)
            put("a_p999_delta", p999)
            put("a_valores_diferentes", diferem)
            put("a_valores_diferentes_fracao", diferem.toDouble() / totalValores)
            put("criterio_b_codigos_diferentes", 0)
            put("b_codigos_diferentes", codigosDiferem)
            put("b_codigos_diferentes_fracao", codigosDiferem.toDouble() / totalValores)
            put("b_codigo_max_delta", codigoMaxDiff)
            put("b_clipes_com_codigo_diferente", clipesComCodigoDiferente)
            put("conversao_produto", "WhisperOrtHtpConversao.paraDtype(dtype=int16_le)")
            put("corpus", cfg.getString("corpus"))
            put("selecao_m1c_sha256", sha256(File(cfg.getString("selecao_m1c")).readBytes()))
            put("duracao_s", (System.nanoTime() - inicioNs) / 1e9)
            put("por_clipe", porClipe)
        }
        File(saida, "t02-equivalencia.json").writeText(recibo.toString(1), Charsets.UTF_8)
        println("R109-T02: max|delta|=$maxGlobal p99.9=$p999 diferentes=$diferem/$totalValores")
        println("R109-T02: codigos i16 diferentes=$codigosDiferem maxdelta=$codigoMaxDiff")

        assertTrue("(a) max|delta| = $maxGlobal > 1e-5", maxGlobal <= 1e-5)
        assertEquals("(b) codigos i16 diferentes: $clipesComCodigoDiferente", 0L, codigosDiferem)
    }
}
