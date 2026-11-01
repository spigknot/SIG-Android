package br.gov.sp.pcsp.launcher

import org.junit.Assert.*
import org.junit.Test

/**
 * Adendo 17 §1 — especificação de modelo capturada na admissão/download.
 *
 * small + turbo têm catálogos, identidades, pacotes e chaves de cache
 * próprios; nada do turbo é reutilizado para o small (arquivos, SHA,
 * revisão, tokenizer, config, dims da sessão). `tiny`/`base` seguem com
 * recusa explícita. O gate é [WhisperOrtModelCatalog.modeloParaPedido] +
 * [WhisperOrtPackageFlow.Estado.ModeloNaoSuportado]; a chave do cache deriva
 * da identidade validada do pacote ([WhisperOrtSessionCache.keyFor]), então
 * sessões de modelos diferentes nunca se misturam.
 */
class WhisperOrtSelecaoModeloTest {

    @Test fun tarefaSmallCorrespondeAoExportCongelado() {
        // Fixture original da revisão 36050c46…; o SHA é o do catálogo do pacote.
        val bytes = requireNotNull(javaClass.getResourceAsStream("/ort/generation-config-small.json"))
            .use { it.readBytes() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            WhisperOrtModelCatalog.activeSmall.single { it.name == "generation_config.json" }.sha256,
            sha,
        )
        val config = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
        val tasks = config.getJSONObject("task_to_id")
        val actual = WhisperOrtModelCatalog.taskIdsPara(WhisperOrtModelCatalog.MODEL_SMALL)
        assertEquals("transcribe vem do export Small", tasks.getInt("transcribe"), actual.transcribe)
        assertEquals("translate vem do export Small", tasks.getInt("translate"), actual.translate)
        assertEquals(config.getInt("no_timestamps_token_id"), actual.noTimestamps)
    }

    @Test fun turboSmallTinyEBaseSaoSuportados_outrosNao() {
        // ADENDO 21 §1 — Tiny/Base MULTILINGUES têm export real medido.
        assertTrue(WhisperOrtModelCatalog.suportado(null))
        assertTrue(WhisperOrtModelCatalog.suportado(""))
        assertTrue(WhisperOrtModelCatalog.suportado("v3Turbo"))
        assertTrue(WhisperOrtModelCatalog.suportado(WhisperOrtModelCatalog.MODEL))
        assertTrue(WhisperOrtModelCatalog.suportado("small"))
        assertTrue(WhisperOrtModelCatalog.suportado(WhisperOrtModelCatalog.MODEL_SMALL))
        assertTrue(WhisperOrtModelCatalog.suportado("tiny"))
        assertTrue(WhisperOrtModelCatalog.suportado(WhisperOrtModelCatalog.MODEL_TINY))
        assertTrue(WhisperOrtModelCatalog.suportado("base"))
        assertTrue(WhisperOrtModelCatalog.suportado(WhisperOrtModelCatalog.MODEL_BASE))
        // Fora do catálogo (prova de recusa preservada com identidade REAL
        // desconhecida — não é mais tiny/base).
        assertFalse(WhisperOrtModelCatalog.suportado("medium"))
        assertFalse(WhisperOrtModelCatalog.suportado("large"))
        assertFalse(WhisperOrtModelCatalog.suportado("tiny.en"))
        assertFalse(WhisperOrtModelCatalog.suportado("base.en"))
    }

    @Test fun pedidoResolveParaModeloEfetivo() {
        assertEquals(WhisperOrtModelCatalog.MODEL, WhisperOrtModelCatalog.modeloParaPedido(null))
        assertEquals(WhisperOrtModelCatalog.MODEL, WhisperOrtModelCatalog.modeloParaPedido(""))
        assertEquals(WhisperOrtModelCatalog.MODEL, WhisperOrtModelCatalog.modeloParaPedido("v3Turbo"))
        assertEquals(
            WhisperOrtModelCatalog.MODEL_SMALL,
            WhisperOrtModelCatalog.modeloParaPedido("small"),
        )
        // ADENDO 21 §1 — rótulos da tela resolvem para o export medido:
        assertEquals(WhisperOrtModelCatalog.MODEL_TINY, WhisperOrtModelCatalog.modeloParaPedido("tiny"))
        assertEquals(WhisperOrtModelCatalog.MODEL_BASE, WhisperOrtModelCatalog.modeloParaPedido("base"))
        assertNull("fora do catálogo continua recusando", WhisperOrtModelCatalog.modeloParaPedido("medium"))
    }

    @Test fun tamanhoDoPacoteConfereComCatalogo() {
        val turbo = WhisperOrtModelCatalog.active.sumOf { it.sizeBytes }
        assertEquals(turbo, WhisperOrtModelCatalog.tamanhoPacoteBytes())
        assertEquals(turbo, WhisperOrtModelCatalog.tamanhoPacoteBytes(WhisperOrtModelCatalog.MODEL))
        // Trava contra troca silenciosa do pacote: ~1,4 GB do turbo.
        assertTrue(
            "pacote ORT deveria ter ~1,4 GB, tem ${turbo / 1_048_576L} MB",
            turbo > 1_400_000_000L,
        )
        val small = WhisperOrtModelCatalog.activeSmall.sumOf { it.sizeBytes }
        assertEquals(small, WhisperOrtModelCatalog.tamanhoPacoteBytes(WhisperOrtModelCatalog.MODEL_SMALL))
        // Small é ~390 MB (não 1,4 GB): confundir os pacotes aparece aqui.
        assertTrue(
            "pacote small deveria ter ~390 MB, tem ${small / 1_048_576L} MB",
            small in 300_000_000L..500_000_000L,
        )
        // ADENDO 21 §1 — Tiny (~74 MB) e Base (~130 MB) têm pacotes PRÓPRIOS
        // medidos: nenhuma confusão com small/turbo na exibição de download.
        val tiny = WhisperOrtModelCatalog.activeTiny.sumOf { it.sizeBytes }
        val base = WhisperOrtModelCatalog.activeBase.sumOf { it.sizeBytes }
        assertEquals(tiny, WhisperOrtModelCatalog.tamanhoPacoteBytes(WhisperOrtModelCatalog.MODEL_TINY))
        assertEquals(base, WhisperOrtModelCatalog.tamanhoPacoteBytes(WhisperOrtModelCatalog.MODEL_BASE))
        assertTrue(
            "pacote tiny deveria ter ~74 MB, tem ${tiny / 1_048_576L} MB",
            tiny in 60_000_000L..110_000_000L,
        )
        assertTrue(
            "pacote base deveria ter ~130 MB, tem ${base / 1_048_576L} MB",
            base in 110_000_000L..170_000_000L,
        )
        assertNotEquals(turbo, tiny)
        assertNotEquals(small, base)
    }

    @Test fun identidadesDistintasPorModelo() {
        val turbo = WhisperOrtModelCatalog.identity()
        val small = WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL_SMALL)
        assertEquals(WhisperOrtModelCatalog.MODEL, turbo.model)
        assertEquals(WhisperOrtModelCatalog.MODEL_SMALL, small.model)
        assertEquals("360ebcde2559d60bb474678be3c1de9ef347d01a", turbo.revision)
        assertEquals(WhisperOrtModelCatalog.REVISION_SMALL, small.revision)
        assertNotEquals(turbo.key, small.key)
        // ADENDO 21 §1 — Tiny/Base têm identidade PRÓPRIA (revisão/arquivos
        // congelados, nada emprestado do small/turbo).
        val tinyK = WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL_TINY)
        val baseK = WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL_BASE)
        assertEquals(WhisperOrtModelCatalog.REVISION_TINY, tinyK.revision)
        assertEquals(WhisperOrtModelCatalog.REVISION_BASE, baseK.revision)
        assertNotEquals(turbo.key, tinyK.key)
        assertNotEquals(small.key, tinyK.key)
        assertNotEquals(small.key, baseK.key)
        assertNotEquals(tinyK.key, baseK.key)
    }

    @Test fun validateNaoMisturaDescritores() {
        val turboEntries = WhisperOrtModelCatalog.active.map {
            WhisperOrtModelPackage.FileEntry(it.name, it.sizeBytes, it.sha256, it.role)
        }
        // Manifesto turbo contra catálogo turbo: ok.
        assertNull(WhisperOrtModelCatalog.validate(turboEntries))
        // Manifesto turbo contra catálogo small: recusado (arquivos fora).
        val contraSmall = WhisperOrtModelCatalog.validate(
            turboEntries, WhisperOrtModelCatalog.activeSmall,
        )
        assertNotNull(contraSmall)
        // Manifesto small contra catálogo small: ok; contra turbo: recusado.
        val smallEntries = WhisperOrtModelCatalog.activeSmall.map {
            WhisperOrtModelPackage.FileEntry(it.name, it.sizeBytes, it.sha256, it.role)
        }
        assertNull(WhisperOrtModelCatalog.validate(smallEntries, WhisperOrtModelCatalog.activeSmall))
        assertNotNull(WhisperOrtModelCatalog.validate(smallEntries))
        // ADENDO 21 §1 — manifesto tiny/base contra CADA catálogo: nunca
        // mistura descritores (o small e o tiny têm vocab.json com o MESMO
        // sha por medida da fonte, mas os conjuntos NÃO se trocam).
        val tinyEntries = WhisperOrtModelCatalog.activeTiny.map {
            WhisperOrtModelPackage.FileEntry(it.name, it.sizeBytes, it.sha256, it.role)
        }
        assertNull(WhisperOrtModelCatalog.validate(tinyEntries, WhisperOrtModelCatalog.activeTiny))
        assertNotNull(WhisperOrtModelCatalog.validate(tinyEntries, WhisperOrtModelCatalog.activeSmall))
        val baseEntries = WhisperOrtModelCatalog.activeBase.map {
            WhisperOrtModelPackage.FileEntry(it.name, it.sizeBytes, it.sha256, it.role)
        }
        assertNull(WhisperOrtModelCatalog.validate(baseEntries, WhisperOrtModelCatalog.activeBase))
        assertNotNull(WhisperOrtModelCatalog.validate(baseEntries, WhisperOrtModelCatalog.activeTiny))
    }

    @Test fun modeloNaoSuportadoExplicaPedidoEDisponivel() {
        // ADENDO 21 §1 — a prova de recusa usa identidade REALMENTE fora do
        // catálogo ("medium"); tiny/base passaram a ter export.
        val estado = WhisperOrtPackageFlow.Estado.ModeloNaoSuportado(
            pedido = "medium",
            detalhe = "sem export ONNX validado; ORT atende turbo/small/tiny/base",
        )
        val texto = WhisperOrtPackageFlow.explicar(estado)
        assertTrue("deveria nomear o pedido, diz: $texto", texto.contains("medium"))
        assertTrue(
            "deveria nomear o disponível, diz: $texto",
            texto.contains(WhisperOrtModelCatalog.MODEL),
        )
        assertTrue("deveria trazer o tamanho real, diz: $texto", texto.contains("MB"))
    }

    @Test fun chaveDoCacheAmarraModeloRevisao() {
        val config = WhisperOrtGenerationConfig(
            suppressTokens = setOf(1, 2),
            beginSuppressTokens = setOf(3),
            maxLength = 448,
            eosTokenId = 50257,
            decoderStartTokenId = 50258,
        )
        val turbo = WhisperOrtSessionCache.keyFor(
            WhisperOrtModelCatalog.identity(), "v1", "test", config, 4,
        )
        val small = WhisperOrtSessionCache.keyFor(
            WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL_SMALL),
            "v1", "test", config, 4,
        )
        assertEquals("whisper-large-v3-turbo", turbo.model)
        assertEquals("whisper-small", small.model)
        assertNotEquals(
            "troca de modelo nunca pode reaproveitar sessão quente",
            turbo, small,
        )
        val tiny = WhisperOrtSessionCache.keyFor(
            WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL_TINY),
            "v1", "test", config, 4,
        )
        val tiny4 = WhisperOrtSessionCache.keyFor(
            WhisperOrtModelCatalog.identity(WhisperOrtModelCatalog.MODEL_TINY),
            "v1", "test", config, 8,
        )
        assertEquals("whisper-tiny", tiny.model)
        assertNotEquals(
            "a chave do cache inclui THREADS (adendo 20): 4 != 8",
            tiny, tiny4,
        )
    }


    @Test fun terminalCarregaModeloEfetivo() {
        val admission = WhisperOrtAdmission()
        val job = (admission.admit("T", 1, "ORT", "CPU", "c".repeat(64)) as WhisperOrtAdmission.Admitted.Ok).job
        val terminal = admission.complete(job, "SUCCESS", "texto", 100L, false, model = "whisper-small")
        assertNotNull(terminal)
        assertEquals("whisper-small", terminal!!.model)
    }

    @Test fun idsPorFamiliaMedidosNosMetadadosPrimarios() {
        // ADENDO 24 §1 — os valores vêm dos METADADOS PRIMÁRIOS congelados
        // (generation_config.json → task_to_id medido por revisão), NÃO da
        // função testada: tiny/base/small = 50359/50358/50363;
        // turbo (default) = 50360/50359/50364.
        val t = WhisperOrtModelCatalog.taskIdsPara(WhisperOrtModelCatalog.MODEL_TINY)
        val b = WhisperOrtModelCatalog.taskIdsPara(WhisperOrtModelCatalog.MODEL_BASE)
        val s = WhisperOrtModelCatalog.taskIdsPara(WhisperOrtModelCatalog.MODEL_SMALL)
        val tr = WhisperOrtModelCatalog.taskIdsPara(WhisperOrtModelCatalog.MODEL)
        assertEquals(50_359, t.transcribe); assertEquals(50_358, t.translate); assertEquals(50_363, t.noTimestamps)
        assertEquals(50_359, b.transcribe); assertEquals(50_358, b.translate); assertEquals(50_363, b.noTimestamps)
        assertEquals(50_359, s.transcribe); assertEquals(50_358, s.translate); assertEquals(50_363, s.noTimestamps)
        assertEquals(50_360, tr.transcribe); assertEquals(50_359, tr.translate); assertEquals(50_364, tr.noTimestamps)
        // Coerência: o prompt do TINY com 50360 (tarefa do turbo) é RECUSADO.
        val promptTiny = intArrayOf(
            WhisperOrtPrompt.START_OF_TRANSCRIPT, WhisperOrtPrompt.LANGUAGE_PT, 50_359,
        )
        assertNull(WhisperOrtPrompt.validate(promptTiny, 51_865, noTimestampsTokenId = 50_363, transcribeTokenId = 50_359, translateTokenId = 50_358))
        val promptTurboNoTiny = intArrayOf(
            WhisperOrtPrompt.START_OF_TRANSCRIPT, WhisperOrtPrompt.LANGUAGE_PT, 50_360,
        )
        assertNotNull(
            "tarefa do turbo no tiny é recusada (erro semântico não passa só porque cabe no vocab)",
            WhisperOrtPrompt.validate(promptTurboNoTiny, 51_865, noTimestampsTokenId = 50_363, transcribeTokenId = 50_359, translateTokenId = 50_358),
        )
        // O prompt do TURBO (50360) continua válido na própria família.
        val promptTurbo = intArrayOf(
            WhisperOrtPrompt.START_OF_TRANSCRIPT, WhisperOrtPrompt.LANGUAGE_PT, 50_360,
        )
        assertNull(WhisperOrtPrompt.validate(promptTurbo, 51_866, noTimestampsTokenId = 50_364, transcribeTokenId = 50_360, translateTokenId = 50_359))
    }
}
