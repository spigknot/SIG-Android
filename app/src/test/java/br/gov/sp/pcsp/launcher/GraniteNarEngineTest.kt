package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import kotlin.math.sin

/**
 * Testes do pipeline puro do Granite 4.1 NAR (front-end, fp16, CTC, interleave).
 * Sem Android — roda na JVM (testDebugUnitTest).
 */
class GraniteNarEngineTest {

    @Test
    fun `benchmark protocol emits valid escaped json`() {
        val encoded = GraniteNarBenchmarkProtocol.json(
            linkedMapOf(
                "event" to "inference",
                "text" to "linha 1\n\"ação\"",
                "stage_ms" to linkedMapOf("encoder" to 123L),
                "ok" to true,
            ),
        )

        val parsed = JSONObject(encoded)
        assertEquals("linha 1\n\"ação\"", parsed.getString("text"))
        assertEquals(123L, parsed.getJSONObject("stage_ms").getLong("encoder"))
        assertTrue(parsed.getBoolean("ok"))
    }

    @Test
    fun `benchmark collector parses stages dimensions and session loads`() {
        val collector = GraniteNarBenchmarkProtocol.Collector()
        collector.accept("ONNX encoder criado (CPU) em 4321ms")
        collector.accept("NAR entrada: samples=16000 frames=50 effective_frames=50")
        collector.accept("NAR etapa encoder: 987ms")
        collector.accept("NAR sequência: ctc_tokens=7 valid_audio=10 slots=15 llm_tokens=25")
        collector.accept("NAR inferência total: 1234ms")

        assertEquals(4321L, collector.sessionLoadMs["encoder"])
        assertEquals(987L, collector.stageMs["encoder"])
        assertEquals(50L, collector.dimensions["effective_frames"])
        assertEquals(25L, collector.dimensions["llm_tokens"])
        assertEquals(1234L, collector.engineTotalMs)
    }

    @Test
    fun `benchmark run id is safe and transcript hash is stable`() {
        assertEquals("cpu_pt_01", GraniteNarBenchmarkProtocol.safeRunId("cpu pt/01"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            GraniteNarBenchmarkProtocol.sha256("abc"),
        )
    }

    // ---- HalfFloat ----

    @Test
    fun `halffloat converts 1_0`() {
        // 1.0 em fp16 = 0x3C00
        assertEquals(1.0f, HalfFloat.toFloat(0x3C00), 0f)
    }

    @Test
    fun `halffloat converts 0 and negative`() {
        assertEquals(0.0f, HalfFloat.toFloat(0), 0f)
        assertEquals(-1.0f, HalfFloat.toFloat(0xBC00.toShort()), 0f)
    }

    @Test
    fun `halffloat converts subnormal`() {
        // menor subnormal positivo em fp16 = 0x0001 = 2^-24
        assertEquals(5.9604645e-8f, HalfFloat.toFloat(0x0001), 1e-12f)
    }

    // ---- CTC collapse ----

    @Test
    fun `ctc collapse removes repeats and blank`() {
        // vocab 3: [0, 1, 2]; blank = 100257 (fora do vocab pequeno p/ teste, usa BLANK real)
        val vocab = 100352
        val frames = 6
        // logits com picos em ids: 100257(blank), 5, 5, 100257, 6, 100257
        val logits = FloatArray(frames * vocab)
        val ids = longArrayOf(100257L, 5L, 5L, 100257L, 6L, 100257L)
        for (t in 0 until frames) logits[t * vocab + ids[t].toInt()] = 10f
        val out = GraniteNarCtc.collapseLogits(logits, frames, vocab)
        assertTrue(out.contentEquals(intArrayOf(5, 6)))
    }

    @Test
    fun `ctc collapse blank between identical preserves both`() {
        val vocab = 100352
        val frames = 3
        val logits = FloatArray(frames * vocab)
        val ids = longArrayOf(7L, 100257L, 7L)
        for (t in 0 until frames) logits[t * vocab + ids[t].toInt()] = 10f
        val out = GraniteNarCtc.collapseLogits(logits, frames, vocab)
        assertTrue(out.contentEquals(intArrayOf(7, 7)))
    }

    @Test
    fun `ctc collapse reads frame slice without copying logits`() {
        val vocab = 100352
        val logits = FloatArray(4 * vocab)
        val ids = intArrayOf(11, 12, 13, 14)
        for (frame in ids.indices) logits[frame * vocab + ids[frame]] = 10f

        val out = GraniteNarCtc.collapseLogits(
            java.nio.FloatBuffer.wrap(logits),
            frames = 2,
            vocab = vocab,
            frameOffset = 1,
        )

        assertTrue(out.contentEquals(intArrayOf(12, 13)))
    }

    // ---- Interleave ----

    @Test
    fun `interleave inserts blank slots between tokens`() {
        val out = GraniteNarInterleave.buildSlots(intArrayOf(5, 6, 7), blank = 100257)
        // total = max(2*3+1, min_edit_sequence_length=8) = 8 -> blank extra no fim
        assertTrue(out.contentEquals(intArrayOf(100257, 5, 100257, 6, 100257, 7, 100257, 100257)))
    }

    @Test
    fun `interleave enforces min edit sequence length`() {
        val out = GraniteNarInterleave.buildSlots(intArrayOf(5), blank = 100257, minEditSequenceLength = 8)
        assertEquals(8, out.size)
        assertEquals(5, out[1])
        assertTrue(out.all { it == 100257 || it == 5 })
    }

    @Test
    fun `interleave empty ctc produces all blanks`() {
        val out = GraniteNarInterleave.buildSlots(intArrayOf(), blank = 100257)
        assertEquals(8, out.size) // max(1, 8)
        assertTrue(out.all { it == 100257 })
    }

    // ---- Frontend ----

    @Test
    fun `frontend outFrames matches 2x stacking of mel`() {
        val fe = GraniteNarFrontend(FloatArray(80 * 257), FloatArray(512) { 1f })
        // 16000 samples -> 2*(16000//320) = 100 mel frames -> 50 out frames
        assertEquals(50, fe.outFrames(16000))
        assertEquals(100, fe.melFrameCount(16000))
    }

    @Test
    fun `frontend compute produces 160-dim finite features`() {
        val fe = GraniteNarFrontend(FloatArray(80 * 257) { 1f }, FloatArray(512) { 1f })
        val out = fe.compute(FloatArray(16000) { i ->
            sin(2.0 * Math.PI * 440.0 * i / 16000.0).toFloat()
        })
        assertEquals(50, out.frames)
        assertEquals(160, out.dim)
        assertEquals(50 * 160, out.data.size)
        assertTrue(out.data.all { it.isFinite() })
    }

    @Test
    fun `frontend silence is finite`() {
        val fe = GraniteNarFrontend(FloatArray(80 * 257), FloatArray(512) { 1f })
        val out = fe.compute(FloatArray(16000) { 0f })
        assertTrue(out.data.all { it.isFinite() })
    }

    // ---- Seleção de bucket (vacinado em 10/09: padding piora e nunca melhora) ----

    @Test
    fun `bucket picks the smallest that fits`() {
        assertEquals(200, GraniteNarBuckets.escolhe(165))
        assertEquals(200, GraniteNarBuckets.escolhe(1))
        assertEquals(200, GraniteNarBuckets.escolhe(200))
    }

    @Test
    fun `bucket steps up when the audio does not fit`() {
        assertEquals(400, GraniteNarBuckets.escolhe(201))
        assertEquals(400, GraniteNarBuckets.escolhe(400))
        assertEquals(800, GraniteNarBuckets.escolhe(401))
        assertEquals(1200, GraniteNarBuckets.escolhe(801))
        assertEquals(1600, GraniteNarBuckets.escolhe(1201))
        assertEquals(2000, GraniteNarBuckets.escolhe(1601))
    }

    @Test
    fun `bucket never returns less than the audio needs when one fits`() {
        // A regra que a medição de 10/09 justifica: padding entra na atenção (máscara
        // estática) e custa qualidade + 9,7x de tempo. Nunca escolher bucket < realFrames
        // enquanto houver um que caiba.
        for (t in 1..2000) {
            val b = GraniteNarBuckets.escolhe(t)
            assertTrue("bucket $b < frames $t", b >= t)
        }
    }

    @Test
    fun `bucket falls back to the largest when nothing fits`() {
        // Áudio acima do maior bucket: devolve o maior e o chamador decide (hoje: erro claro).
        assertEquals(2000, GraniteNarBuckets.escolhe(2001))
        assertEquals(2000, GraniteNarBuckets.escolhe(99_999))
    }

    @Test
    fun `bucket respects a custom available set`() {
        // Se só um subconjunto estiver instalado, a escolha usa o que existe.
        val soPares = intArrayOf(400, 800)
        assertEquals(400, GraniteNarBuckets.escolhe(200, soPares))
        assertEquals(800, GraniteNarBuckets.escolhe(500, soPares))
        assertEquals(800, GraniteNarBuckets.escolhe(900, soPares))   // nada cabe -> maior
    }

    @Test
    fun `legacy cleanup never touches a file the new package needs`() {
        // Invariante que protege o usuário: a limpeza do pacote antigo não pode apagar
        // nada que o pacote NOVO precise. Se alguém adicionar um nome à lista legada que
        // hoje é usado, este teste quebra.
        val doPacoteNovo = GraniteNarBuckets.TODOS.flatMap {
            listOf(GraniteNarBuckets.encoderFile(it), GraniteNarBuckets.projectorFile(it))
        } + listOf(
            "encoder-pesos.data", "projector-pesos.data",
            "granite-4.1-nar-llm-fp16.onnx", "granite-4.1-nar-llm-fp16.onnx.data",
            "nar_mel_filters.bin", "nar_stft_window.bin", "vocab.json",
            "nar_embed_tokens.bin", "preprocessor_config.json",
        )
        val colisao = GraniteNarBuckets.LEGADOS.intersect(doPacoteNovo.toSet())
        assertTrue("a limpeza apagaria arquivo do pacote novo: $colisao", colisao.isEmpty())
    }

    @Test
    fun `legacy list is exactly the old single-bucket package`() {
        assertEquals(3, GraniteNarBuckets.LEGADOS.size)
        assertTrue(GraniteNarBuckets.LEGADOS.contains("granite-4.1-nar-encoder-fp16.onnx"))
        // nomes com sufixo de bucket NUNCA são legados — são o pacote atual
        assertTrue(GraniteNarBuckets.LEGADOS.none { it.contains("-t0") || it.contains("-t1") || it.contains("-t2") })
    }

    @Test
    fun `bucket file names match the exported artifacts`() {
        assertEquals("granite-4.1-nar-encoder-t0200-fp16.onnx", GraniteNarBuckets.encoderFile(200))
        assertEquals("granite-4.1-nar-encoder-t2000-fp16.onnx", GraniteNarBuckets.encoderFile(2000))
        assertEquals("granite-4.1-nar-projector-t0800-fp16.onnx", GraniteNarBuckets.projectorFile(800))
        // zero-padding de 4 dígitos, como os arquivos publicados
        assertEquals("granite-4.1-nar-encoder-t0040-fp16.onnx", GraniteNarBuckets.encoderFile(40))
    }

    // ---- Variantes do LLM (escolha do usuário; dados mostrados na tela) ----

    @Test
    fun `llm variants are ordered from heaviest to lightest`() {
        val tamanhos = GraniteNarLlm.TODAS.map { it.bytes }
        assertEquals(tamanhos.sortedDescending(), tamanhos)
        assertTrue("o float deve ser o maior", GraniteNarLlm.FLOAT.bytes == tamanhos.max())
        // A faixa termina no 4-bit: 841 MB é a MENOR oferecida.
        assertEquals(GraniteNarLlm.QUATRO_BITS.bytes, tamanhos.min())
    }

    @Test
    fun `llm two bit variant is not offered to the user`() {
        // Medido em 11/09: 0/82 textos iguais, CER 0,79 (24,6x o float), 82/82 acima de 0,30.
        // Não é qualidade menor — é texto destruído. Não pode aparecer na tela.
        assertTrue(
            "o 2-bit nao pode estar na lista oferecida",
            GraniteNarLlm.TODAS.none { it.id == GraniteNarLlm.DOIS_BITS_REPROVADO.id },
        )
        // mas fica registrado, com o número medido, para ninguém tentar de novo
        assertTrue(GraniteNarLlm.DOIS_BITS_REPROVADO.cerDelta > 0.5)
        assertTrue(GraniteNarLlm.DOIS_BITS_REPROVADO.excedeCriterioPtBr())
    }

    @Test
    fun `llm variant ids are unique and stable`() {
        val ids = GraniteNarLlm.TODAS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // os ids aparecem em nome de arquivo publicado: não podem mudar sem republicar
        assertTrue(ids.contains("fp16"))
        assertTrue(ids.contains("int8b-blk128"))
        assertTrue(ids.contains("int4b-blk128"))
    }

    @Test
    fun `llm variant file names follow the device contract`() {
        val v = GraniteNarLlm.QUATRO_BITS
        assertEquals("granite-4.1-nar-llm-int4b-blk128.onnx", v.onnx)
        // o `.data` é o `external_data.location` gravado no artefato — precisa casar
        assertEquals("granite-4.1-nar-llm-int4b-blk128.onnx.data", v.data)
    }

    @Test
    fun `llm unknown id falls back to the highest quality`() {
        // Uma preferência corrompida não pode deixar o app sem modelo.
        assertEquals(GraniteNarLlm.FLOAT.id, GraniteNarLlm.porId(null).id)
        assertEquals(GraniteNarLlm.FLOAT.id, GraniteNarLlm.porId("").id)
        assertEquals(GraniteNarLlm.FLOAT.id, GraniteNarLlm.porId("nao-existe").id)
        assertEquals(GraniteNarLlm.QUATRO_BITS.id, GraniteNarLlm.porId("int4b-blk128").id)
    }

    /** Normaliza o separador decimal: a formatação usa a locale do sistema (pt-BR = vírgula). */
    private fun num(s: String) = s.replace(',', '.')

    @Test
    fun `llm sizes are human readable for the dialog`() {
        assertEquals("3.3 GB", num(GraniteNarLlm.FLOAT.tamanhoLegivel()))
        // 841,6 MB -> "842 MB" e 433,7 MB -> "434 MB" (arredonda, não trunca)
        assertEquals("842 MB", num(GraniteNarLlm.QUATRO_BITS.tamanhoLegivel()))
    }

    @Test
    fun `llm speed label is empty for the reference and informative for the others`() {
        // O float é a referência: mostrar "1.0× mais rápido" seria ruído.
        assertEquals("", GraniteNarLlm.FLOAT.ganhoLegivel())
        assertEquals("4.3× mais rápido", num(GraniteNarLlm.QUATRO_BITS.ganhoLegivel()))
    }

    @Test
    fun `llm quality label warns when pt-br exceeds the plan criterion`() {
        // Este é o achado que motivou a escolha explícita: o 4-bit excede o critério do
        // plano (+0,005) JUSTAMENTE em pt-BR, o idioma de uso. A tela precisa dizer isso.
        assertTrue(GraniteNarLlm.QUATRO_BITS.excedeCriterioPtBr())
        assertTrue(GraniteNarLlm.QUATRO_BITS.qualidadeLegivel().contains("pt-BR"))
        // O float é a referência: sem aviso.
        assertTrue(!GraniteNarLlm.FLOAT.excedeCriterioPtBr())
        assertEquals("mesma qualidade", GraniteNarLlm.FLOAT.qualidadeLegivel())
    }

    @Test
    fun `llm variant files never collide with the legacy cleanup list`() {
        // Mesma classe de invariante dos buckets: a limpeza não pode apagar a variante em uso.
        val arquivosDeVariantes = GraniteNarLlm.TODAS.flatMap { listOf(it.onnx, it.data) }
        val colisao = GraniteNarBuckets.LEGADOS.intersect(arquivosDeVariantes.toSet())
        assertTrue("a limpeza apagaria arquivo de variante: $colisao", colisao.isEmpty())
    }

    // ---- Integridade do pacote (Fase 7: "SHA-256 antes da ativação") ----

    @Test
    fun `manifest parse reads name to sha256`() {
        val json = """
            {"arquivos":[
              {"name":"a.onnx","bytes":10,"sha256":"${"a".repeat(64)}"},
              {"name":"b.data","bytes":20,"sha256":"${"B".repeat(64)}"}
            ]}
        """.trimIndent()
        val m = GraniteNarManifest.parse(json)
        assertEquals(2, m.size)
        // o hash é normalizado para minúsculas
        assertEquals("a".repeat(64), m["a.onnx"])
        assertEquals("b".repeat(64), m["b.data"])
    }

    @Test
    fun `manifest parse tolerates missing or malformed input`() {
        // Pacote antigo pode não ter hashes: ausência não pode explodir.
        assertEquals(0, GraniteNarManifest.parse("{}").size)
        assertEquals(0, GraniteNarManifest.parse("nao e json").size)
        assertEquals(0, GraniteNarManifest.parse("").size)
        // entradas incompletas são ignoradas, não viram hash inválido
        val parcial = """{"arquivos":[{"name":"x"},"lixo",{"sha256":"${"c".repeat(64)}"}]}"""
        assertTrue(GraniteNarManifest.parse(parcial).isEmpty())
        // sha de tamanho errado não entra (evita "verificar" contra lixo)
        val curto = """{"arquivos":[{"name":"y","sha256":"abc"}]}"""
        assertTrue(GraniteNarManifest.parse(curto).isEmpty())
    }

    @Test
    fun `manifest sha256 matches a known digest`() {
        // "abc" -> ba7816bf... é o vetor de teste padrão do SHA-256.
        val tmp = java.io.File.createTempFile("sha", ".bin")
        try {
            tmp.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                GraniteNarManifest.sha256(tmp),
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `manifest confere accepts a matching file and rejects a tampered one`() {
        val tmp = java.io.File.createTempFile("conf", ".bin")
        try {
            tmp.writeText("conteudo legitimo")
            val hash = GraniteNarManifest.sha256(tmp)
            assertTrue(GraniteNarManifest.confere(tmp, hash))
            assertTrue(GraniteNarManifest.confere(tmp, hash.uppercase()))  // case-insensitive

            tmp.writeText("conteudo ALTERADO")
            assertTrue("hash diferente tem de reprovar", !GraniteNarManifest.confere(tmp, hash))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `manifest confere does not block an unlisted file`() {
        // Arquivo que o manifesto não lista não é aprovado nem reprovado: segue o fluxo.
        // (Impedir seria quebrar pacotes legítimos sem hashes.)
        val tmp = java.io.File.createTempFile("nolist", ".bin")
        try {
            tmp.writeText("x")
            assertTrue(GraniteNarManifest.confere(tmp, null))
            assertTrue(GraniteNarManifest.confere(tmp, ""))
            // mas arquivo INEXISTENTE com hash esperado reprova
            val fantasma = java.io.File(tmp.parentFile, "nao_existe_${tmp.name}")
            assertTrue(!GraniteNarManifest.confere(fantasma, "a".repeat(64)))
        } finally {
            tmp.delete()
        }
    }

    /**
     * A duração máxima ANUNCIADA tem de ser o limite REAL.
     *
     * O limite é `frames <= T_FIXED` e `frames = amostras / (2*HOP)`. A mensagem de erro já usou
     * o HOP cru e anunciava **20 s** quando o limite real é **40 s** — o usuário evitaria áudios
     * de 25-30 s que funcionam. Este teste chama a função de PRODUÇÃO (`outFrames`) para provar
     * que o número anunciado é exatamente o ponto onde o áudio para de caber: se alguém mudar o
     * `HOP`, o empilhamento ou o `T_FIXED`, o teste falha junto com a mensagem.
     */
    @Test
    fun `max audio seconds equals the real frame limit`() {
        val frontend = GraniteNarFrontend(
            FloatArray(GraniteNarFrontend.N_MELS * GraniteNarFrontend.N_FREQS),
            FloatArray(GraniteNarFrontend.N_FFT),
        )
        // 2000 frames * 2 * hop 160 / 16000 Hz = 40 s
        assertEquals(40, GraniteNarEngine.MAX_AUDIO_SECONDS)

        val noLimite = GraniteNarEngine.MAX_AUDIO_SECONDS * GraniteNarEngine.SAMPLE_RATE
        assertTrue(
            "audio EXATAMENTE no limite anunciado tem de caber",
            frontend.outFrames(noLimite) <= GraniteNarEngine.T_FIXED,
        )
        // Um segundo a mais nao cabe: prova que o limite anunciado nao esta folgado demais.
        assertTrue(
            "um segundo alem do limite anunciado NAO pode caber",
            frontend.outFrames(noLimite + GraniteNarEngine.SAMPLE_RATE) > GraniteNarEngine.T_FIXED,
        )
        // E um audio curto tem de sobrar espaco (guarda contra limite anunciado pequeno demais).
        assertTrue(frontend.outFrames(10 * GraniteNarEngine.SAMPLE_RATE) < GraniteNarEngine.T_FIXED)
    }
}
