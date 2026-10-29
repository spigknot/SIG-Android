package br.gov.sp.pcsp.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R12/A2 — TESTES PERMANENTES DO CONTRATO POR JOB.
 *
 * Cobrem exatamente o defeito do P4 da R11 (export herdava o texto do widget:
 * os três passos saíram com o MESMO texto de 329 B, inclusive o passo CANCELADO
 * que nunca decodificou) e as cinco exigências da fase A2:
 *
 *  1. sucesso A → cancelamento B (sem decode) → sucesso C com frases
 *     diferentes: B não herda A nem vira sucesso; C é só de C;
 *  2. erro B em lote de DOIS arquivos + callbacks atrasados de A: nada de A
 *     atualiza C; o export de B é parcial do próprio B;
 *  3. parcial de B marcado como parcial E export VAZIO/sem resultado quando
 *     não existe parcial;
 *  4. fala com "relatorio.wav" e separadores permanece INTACTA (dado do
 *     produtor, sem regex de nome de arquivo/separador);
 *  5. resultado, erro, rc, terminal, status nativo e export concordam —
 *     combinação incoerente é recusada para WER.
 */
class TranscriptionJobResultTest {

    private val run = "p01t1"
    private val act = 1

    /** Abre job + tentativa e devolve o par (jobId, chave). */
    private fun open(l: TranscriptionJobLedger, owner: Long, input: String): Pair<Long, TranscriptionJobKey> {
        val jobId = l.beginJob(run, act, owner)
        return jobId to l.beginAttempt(jobId, owner, input, run, act)!!
    }

    private fun success(l: TranscriptionJobLedger, owner: Long, input: String, text: String): Long {
        val (jobId, key) = open(l, owner, input)
        l.appendSegment(key, text)
        assertNotNull(l.finishAttempt(key, JobTerminalState.SUCCESS, "ok"))
        assertNotNull(l.publishJob(jobId, owner, JobTerminalState.SUCCESS, "ok"))
        return jobId
    }

    // ------------------------------------------------------------------ 1

    @Test
    fun `sucesso A depois cancelamento B sem decode e sucesso C sem heranca`() {
        val l = TranscriptionJobLedger()
        val boundaryBeforeA = l.currentSeq()
        val jobA = success(l, owner = 11L, input = "a.wav", text = "bom dia a todos os presentes")
        // Regra do INSTRUMENTO: o passo exporta o terminal publicado DEPOIS da
        // fronteira do passo; sem publicação nova o passo não tem resultado.
        assertEquals(jobA, l.latestAfter(boundaryBeforeA)!!.key.jobId)
        val exportA = l.export()
        assertEquals("bom dia a todos os presentes", exportA.text)
        assertTrue(exportA.werEligible)
        assertEquals("completo", exportA.reason)
        assertEquals(jobA, l.lastGood()!!.key.jobId)

        // B: cancelado ANTES de qualquer decode (sem um único segmento).
        val (jobB, keyB) = open(l, owner = 12L, input = "b.wav")
        assertEquals("", l.textOf(keyB))
        val boundaryB = l.currentSeq()
        assertNull("antes do terminal, o passo B não tem resultado", l.latestAfter(boundaryB))
        assertNotNull(l.publishJob(jobB, 12L, JobTerminalState.CANCELLED, "cancelled"))
        assertEquals("o terminal de B é o do passo B", jobB, l.latestAfter(boundaryB)!!.key.jobId)
        assertTrue(l.exportOf(l.latestAfter(boundaryB)).empty)

        val exportB = l.export()
        assertEquals("", exportB.text)
        assertTrue(exportB.empty)
        assertFalse(exportB.partial)
        assertFalse(exportB.werEligible)
        assertEquals("cancelado_sem_decode", exportB.reason)
        assertFalse("B não pode herdar A", exportB.text.contains("bom dia"))
        // O último resultado BOM continua sendo A, COM a identidade de A.
        assertEquals(jobA, l.lastGood()!!.key.jobId)
        assertFalse(l.lastGood()!!.key.inputId == "b.wav")

        // C: novo sucesso, frase diferente.
        assertNull("sem fronteira não há resultado de passo", l.latestAfter(-1L))
        val boundaryC = l.currentSeq()
        val jobC = success(l, owner = 13L, input = "c.wav", text = "o relatorio anual foi aprovado ontem")
        assertEquals(jobC, l.latestAfter(boundaryC)!!.key.jobId)
        val exportC = l.export()
        assertEquals("o relatorio anual foi aprovado ontem", exportC.text)
        assertTrue(exportC.werEligible)
        assertEquals(jobC, exportC.result!!.key.jobId)
        assertEquals(13L, exportC.result!!.key.owner)
        assertEquals(jobC, l.lastGood()!!.key.jobId)
    }

    // ------------------------------------------------------------------ 2

    @Test
    fun `erro B em lote de dois arquivos e callbacks atrasados de A nao atualizam C`() {
        val l = TranscriptionJobLedger()
        success(l, owner = 21L, input = "a.wav", text = "texto do job a")
        val keyA = l.lastGood()!!.key

        // B: lote com dois arquivos; o segundo falha no decode.
        val jobB = l.beginJob(run, act, 22L)
        val kb1 = l.beginAttempt(jobB, 22L, "b1.wav", run, act)!!
        l.appendSegment(kb1, "primeira parte do lote")
        assertNotNull(l.finishAttempt(kb1, JobTerminalState.SUCCESS, "ok"))
        val kb2 = l.beginAttempt(jobB, 22L, "b2.wav", run, act)!!
        assertNotNull(l.finishAttempt(kb2, JobTerminalState.ERROR, "error"))
        assertNotNull(l.publishJob(jobB, 22L, JobTerminalState.ERROR, "error"))

        val exportB = l.export()
        assertFalse(exportB.empty)
        assertTrue(exportB.partial)
        assertFalse(exportB.werEligible)
        assertEquals("parcial_com_erro", exportB.reason)
        assertEquals("primeira parte do lote", exportB.text)
        assertFalse(exportB.text.contains("texto do job a"))
        assertEquals(1, exportB.result!!.blocks.size)

        // C: sucesso. Callbacks ATRASADOS de A e da 1ª tentativa de B chegam agora.
        val (jobC, keyC) = open(l, owner = 23L, input = "c.wav")
        assertFalse("segmento atrasado de A", l.appendSegment(keyA, "texto atrasado de a"))
        assertFalse("segmento atrasado de b1", l.appendSegment(kb1, "texto atrasado de b1"))
        assertNull("terminal atrasado de b1", l.finishAttempt(kb1, JobTerminalState.SUCCESS, "ok"))
        assertNull("terminal atrasado de A", l.publishJob(keyA.jobId, 21L, JobTerminalState.SUCCESS, "ok"))
        assertEquals("", l.textOf(keyA))
        l.appendSegment(keyC, "texto do job c")
        assertNotNull(l.finishAttempt(keyC, JobTerminalState.SUCCESS, "ok"))
        assertNotNull(l.publishJob(jobC, 23L, JobTerminalState.SUCCESS, "ok"))

        val exportC = l.export()
        assertEquals("texto do job c", exportC.text)
        assertTrue(exportC.werEligible)
        assertFalse(exportC.text.contains("atrasado"))
        assertEquals(jobC, exportC.result!!.key.jobId)
        assertEquals("c.wav", exportC.result!!.key.inputId)
        // Dois terminais do MESMO job: o segundo é ignorado (sem sobrescrever).
        assertNull(l.publishJob(jobC, 23L, JobTerminalState.ERROR, "error"))
        assertEquals("texto do job c", l.export().text)
    }

    // ------------------------------------------------------------------ 3

    @Test
    fun `parcial de B marcado e export vazio quando nao existe parcial`() {
        val l = TranscriptionJobLedger()
        // B: cancelado com fala já decodificada → PARCIAL do próprio B.
        val (jobB, keyB) = open(l, owner = 31L, input = "b.wav")
        l.appendSegment(keyB, "fala inicial do job b")
        assertNotNull(l.finishAttempt(keyB, JobTerminalState.CANCELLED, "cancelled"))
        assertNotNull(l.publishJob(jobB, 31L, JobTerminalState.CANCELLED, "cancelled"))

        val parcial = l.export()
        assertEquals("fala inicial do job b", parcial.text)
        assertFalse(parcial.empty)
        assertTrue(parcial.partial)
        assertFalse(parcial.werEligible)
        assertEquals("parcial_cancelado", parcial.reason)
        val metaParcial = parcial.metaJson()
        assertTrue(metaParcial.getBoolean("parcial"))
        assertFalse(metaParcial.getBoolean("wer_eligivel"))
        assertFalse(metaParcial.getBoolean("sem_resultado"))
        assertEquals("partial", metaParcial.getString("text_origin"))

        // C: cancelado sem nenhum parcial → export VAZIO e explicado.
        val (jobC, _) = open(l, owner = 32L, input = "c.wav")
        assertNotNull(l.publishJob(jobC, 32L, JobTerminalState.CANCELLED, "cancelled"))
        val vazio = l.export()
        assertEquals("", vazio.text)
        assertTrue(vazio.empty)
        assertFalse(vazio.partial)
        assertFalse(vazio.werEligible)
        assertEquals("cancelado_sem_decode", vazio.reason)
        val metaVazio = vazio.metaJson()
        assertTrue(metaVazio.getBoolean("sem_resultado"))
        assertFalse(metaVazio.getBoolean("wer_eligivel"))
    }

    // ------------------------------------------------------------------ 4

    @Test
    fun `fala com nome de arquivo e separadores permanece intacta`() {
        val l = TranscriptionJobLedger()
        val speech = "o relatorio.wav foi processado hoje\n" +
            "-------------------------------\n" +
            "segunda linha com ---- no meio e outro audio.mp3 citado"
        val jobId = success(l, owner = 41L, input = "d01_aprovado.wav", text = speech)

        val export = l.export()
        assertEquals("o texto do produtor sai byte a byte, sem regex", speech, export.text)
        val result = export.result!!
        assertEquals(jobId, result.key.jobId)
        assertEquals("d01_aprovado.wav", result.key.inputId)
        assertEquals(1, result.blocks.size)
        assertEquals(speech, result.blocks[0].text)
        assertEquals(speech.length, result.text.length)
        // O corpo continua com as linhas que uma limpeza genérica apagaria.
        assertTrue(export.text.contains("relatorio.wav"))
        assertTrue(export.text.contains("-------------------------------"))
        assertTrue(export.text.contains("outro audio.mp3"))
        // E o JSON estruturado carrega o MESMO corpo.
        val json = JSONObject(result.toJsonString())
        assertEquals(speech, json.getJSONArray("blocks").getJSONObject(0).getString("text"))
        assertEquals("d01_aprovado.wav", json.getString("input_id"))
    }

    // ------------------------------------------------------------------ 5

    @Test
    fun `resultado erro rc terminal nativo e export concordam`() {
        val l = TranscriptionJobLedger()

        val (jobOk, keyOk) = open(l, owner = 51L, input = "ok.wav")
        l.appendSegment(keyOk, "tudo certo")
        val okAttempt = l.finishAttempt(keyOk, JobTerminalState.SUCCESS, "ok")!!
        assertTrue(okAttempt.audit().isEmpty())
        l.publishJob(jobOk, 51L, JobTerminalState.SUCCESS, "ok")
        val ok = l.export()
        assertTrue(ok.werEligible)
        assertFalse(ok.empty)
        assertFalse(ok.partial)
        assertEquals(TranscriptionJobResult.RC_SUCCESS, ok.result!!.resultCode)

        val (jobCancel, keyCancel) = open(l, owner = 52L, input = "cancel.wav")
        l.appendSegment(keyCancel, "fala parcial")
        l.finishAttempt(keyCancel, JobTerminalState.CANCELLED, "cancelled")
        l.publishJob(jobCancel, 52L, JobTerminalState.CANCELLED, "cancelled")
        val cancel = l.export()
        assertEquals(JobTerminalState.CANCELLED, cancel.result!!.terminal)
        assertEquals(TranscriptionJobResult.RC_CANCELLED, cancel.result!!.resultCode)
        assertTrue(cancel.result!!.audit().isEmpty())
        assertTrue(cancel.partial)
        assertFalse(cancel.werEligible)

        val (jobErr, keyErr) = open(l, owner = 53L, input = "err.wav")
        l.finishAttempt(keyErr, JobTerminalState.ERROR, "error")
        l.publishJob(jobErr, 53L, JobTerminalState.ERROR, "error")
        val err = l.export()
        assertEquals(JobTerminalState.ERROR, err.result!!.terminal)
        assertEquals(TranscriptionJobResult.RC_ERROR, err.result!!.resultCode)
        assertTrue(err.result!!.audit().isEmpty())
        assertTrue(err.empty)
        assertEquals("erro_sem_decode", err.reason)

        val (jobNoSpeech, keyNoSpeech) = open(l, owner = 54L, input = "silencio.wav")
        l.finishAttempt(keyNoSpeech, JobTerminalState.NO_SPEECH, "no_speech")
        l.publishJob(jobNoSpeech, 54L, JobTerminalState.NO_SPEECH, "no_speech")
        val noSpeech = l.export()
        assertEquals(JobTerminalState.NO_SPEECH, noSpeech.result!!.terminal)
        assertEquals(TranscriptionJobResult.RC_NO_SPEECH, noSpeech.result!!.resultCode)
        assertTrue(noSpeech.result!!.audit().isEmpty())
        assertTrue(noSpeech.empty)
        assertFalse(noSpeech.werEligible)

        // Incoerência plantada: terminal SUCCESS com status nativo cancelado/rc 2.
        val chave = TranscriptionJobKey(run, act, 99L, 55L, "incoerente.wav", 9L)
        val tampered = TranscriptionJobResult(
            key = chave,
            terminal = JobTerminalState.SUCCESS,
            origin = TextOrigin.DECODE,
            text = "texto que nao veio deste job",
            blocks = listOf(TranscriptionTextBlock("incoerente.wav", "texto que nao veio deste job", TextOrigin.DECODE)),
            nativeStatus = "cancelled",
            resultCode = TranscriptionJobResult.RC_CANCELLED,
            publishedSeq = 1L,
        )
        val violations = tampered.audit()
        assertTrue(violations.contains("sucesso_status_nativo_cancelled"))
        assertTrue(violations.contains("sucesso_rc_2"))
        val exportTampered = l.exportOf(tampered)
        assertFalse("incoerente nunca é elegível a WER", exportTampered.werEligible)
        assertEquals("incoerente", exportTampered.reason)
    }

    // ------------------------------------------------- R13/A2 RED STALE

    @Test
    fun `beginAttempt tardio de job substituido é recusado sem tocar no job ativo`() {
        val l = TranscriptionJobLedger()
        val jobA = l.beginJob(run, act, 11L)
        val jobB = l.beginJob(run, act, 22L)
        val keyB = l.beginAttempt(jobB, 22L, "B.wav", run, act)!!
        assertTrue(l.appendSegment(keyB, "CONTEUDO_B"))
        assertNotNull(l.finishAttempt(keyB, JobTerminalState.SUCCESS, "ok"))

        // Chegada atrasada de A DEPOIS de B assumir: deve ser recusada.
        val lateA = l.beginAttempt(jobA, 11L, "A.wav", run, act)
        assertNull("beginAttempt tardio de job substituído deve ser recusado", lateA)
        // B continua publicável; A antigo não publica.
        assertNotNull(l.publishJob(jobB, 22L, JobTerminalState.SUCCESS, "ok"))
        assertNull(l.publishJob(jobA, 11L, JobTerminalState.SUCCESS, "ok"))
        val exportB = l.export()
        assertEquals("CONTEUDO_B", exportB.text)
        assertFalse(exportB.text.contains("ATRASADO"))
    }

    @Test
    fun `beginAttempt tardio com B parcial não apaga o parcial de B`() {
        val l = TranscriptionJobLedger()
        val jobA = l.beginJob(run, act, 11L)
        val jobB = l.beginJob(run, act, 22L)
        val keyB = l.beginAttempt(jobB, 22L, "B.wav", run, act)!!
        assertTrue(l.appendSegment(keyB, "PARCIAL_B"))

        // A atrasado tenta abrir enquanto B ainda está parcial.
        val lateA = l.beginAttempt(jobA, 11L, "A.wav", run, act)
        assertNull("abertura tardia não pode roubar a posse de B parcial", lateA)
        // O parcial de B sobrevive intacto.
        assertEquals("PARCIAL_B", l.textOf(keyB))
        assertNotNull(l.finishAttempt(keyB, JobTerminalState.SUCCESS, "ok"))
        assertNotNull(l.publishJob(jobB, 22L, JobTerminalState.SUCCESS, "ok"))
        assertEquals("PARCIAL_B", l.export().text)
    }

    // --------------------------------------------- R14/A2 BOUNDARY (export/identidade)

    @Test
    fun `latestAfter com sequencia negativa é erro de instrumentacao e devolve null`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        val key = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        assertTrue(l.appendSegment(key, "TEXTO_A"))
        assertNotNull(l.publishJob(job, 11L, JobTerminalState.SUCCESS, "ok"))

        // Sem markStepBoundary (seq -1) o instrumento NÃO pode aceitar o último
        // resultado como se fosse do passo: null = "passo não publicou".
        assertNull("seq negativa não é resultado vazio aprovado: é instrumentação ausente",
            l.latestAfter(-1L))
    }

    @Test
    fun `latestAfter devolve somente o terminal publicado DEPOIS da fronteira`() {
        val l = TranscriptionJobLedger()
        // Primeiro job (passo anterior)
        val job1 = l.beginJob(run, act, 11L)
        val k1 = l.beginAttempt(job1, 11L, "A.wav", run, act)!!
        l.appendSegment(k1, "PASSO_ANTERIOR")
        l.publishJob(job1, 11L, JobTerminalState.SUCCESS, "ok")

        // Fronteira marcada ANTES do job do passo
        val boundary = l.currentSeq()

        // O passo não publicou: o resultado anterior NÃO é exportado.
        assertNull("terminal anterior à fronteira não pertence ao passo", l.latestAfter(boundary))

        // Segundo job (o passo) publica → é este que o export aceita.
        val job2 = l.beginJob(run, act, 22L)
        val k2 = l.beginAttempt(job2, 22L, "B.wav", run, act)!!
        l.appendSegment(k2, "PASSO_ATUAL")
        l.publishJob(job2, 22L, JobTerminalState.SUCCESS, "ok")

        val after = l.latestAfter(boundary)
        assertNotNull("o terminal novo do passo deve ser aceito", after)
        assertEquals("PASSO_ATUAL", after!!.text)
        assertEquals(22L, after.key.owner)
    }

    // ------------------------------------------- R15/A3 LOTE (duplicação)

    @Test
    fun `R15 A3 dois arquivos no mesmo job nao duplicam o segundo`() {
        // Contraexemplo da R14 (compilado contra a classe real):
        //   BATCH_DUPLICATION text=primeiro | segundo | segundo
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        // arquivo A
        val kA = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        assertTrue(l.appendSegment(kA, "primeiro", 0L, 1000L))
        assertNotNull(l.finishAttempt(kA, JobTerminalState.SUCCESS, "ok"))
        // arquivo B (mesmo job, nova tentativa)
        val kB = l.beginAttempt(job, 11L, "B.wav", run, act)!!
        assertTrue(l.appendSegment(kB, "segundo", 1000L, 2000L))
        assertNotNull(l.finishAttempt(kB, JobTerminalState.SUCCESS, "ok"))
        val r = l.publishJob(job, 11L, JobTerminalState.SUCCESS, "ok")
        assertNotNull(r)
        assertEquals("SEM duplicação do 2º arquivo", "primeiro\nsegundo", r!!.text)
        // os blocos do job: um por segmento (o agregado NÃO é anexado quando os
        // segmentos já cobrem o texto da tentativa)
        val textos = r.blocks.map { it.text }
        assertEquals("blocos: 1 por segmento, sem agregado duplicado", 2, textos.size)
        assertEquals(1, textos.count { it == "segundo" })
        assertEquals(1, textos.count { it == "primeiro" })
    }

    @Test
    fun `R15 A3 tres arquivos distintos cada segmento entra uma vez`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        listOf("um" to "A.wav", "dois" to "B.wav", "tres" to "C.wav").forEach { (txt, arq) ->
            val k = l.beginAttempt(job, 11L, arq, run, act)!!
            assertTrue(l.appendSegment(k, txt))
            assertNotNull(l.finishAttempt(k, JobTerminalState.SUCCESS, "ok"))
        }
        val r = l.publishJob(job, 11L, JobTerminalState.SUCCESS, "ok")
        assertNotNull(r)
        assertEquals("um\ndois\ntres", r!!.text)
        listOf("um", "dois", "tres").forEach { t ->
            assertEquals("'$t' uma vez só", 1, r.blocks.count { it.text == t })
        }
    }

    @Test
    fun `R15 A3 dois arquivos com texto IDENTICO sao duas entradas legitimas`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        val kA = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        assertTrue(l.appendSegment(kA, "igual"))
        assertNotNull(l.finishAttempt(kA, JobTerminalState.SUCCESS, "ok"))
        val kB = l.beginAttempt(job, 11L, "B.wav", run, act)!!
        assertTrue(l.appendSegment(kB, "igual"))
        assertNotNull(l.finishAttempt(kB, JobTerminalState.SUCCESS, "ok"))
        val r = l.publishJob(job, 11L, JobTerminalState.SUCCESS, "ok")
        assertNotNull(r)
        // as DUAS entradas são legítimas: dois blocos "igual", um por tentativa
        val iguais = r!!.blocks.filter { it.text == "igual" }
        assertEquals("uma entrada por arquivo", 2, iguais.size)
        assertEquals("tentativas distintas", 2, iguais.map { it.attemptId }.distinct().size)
    }

    @Test
    fun `R15 A3 fechamento repetido nao duplica`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        val k = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        assertTrue(l.appendSegment(k, "texto"))
        assertNotNull(l.finishAttempt(k, JobTerminalState.SUCCESS, "ok"))
        // callback tardio: o segundo finishAttempt é recusado
        assertNull(l.finishAttempt(k, JobTerminalState.SUCCESS, "ok"))
        val r = l.publishJob(job, 11L, JobTerminalState.SUCCESS, "ok")
        assertNotNull(r)
        assertEquals("texto", r!!.text)
        assertEquals(1, r.blocks.count { it.text == "texto" })
    }

    @Test
    fun `R15 A3 cancel em B preserva o resultado de A`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        val kA = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        assertTrue(l.appendSegment(kA, "resultado de A"))
        assertNotNull(l.finishAttempt(kA, JobTerminalState.SUCCESS, "ok"))
        val kB = l.beginAttempt(job, 11L, "B.wav", run, act)!!
        assertTrue(l.appendSegment(kB, "parcial de B"))
        val rB = l.finishAttempt(kB, JobTerminalState.CANCELLED, "cancelled")
        assertNotNull(rB)
        assertEquals("apenas a parcial de B", "parcial de B", rB!!.text)
        val r = l.publishJob(job, 11L, JobTerminalState.CANCELLED, "cancelled")
        assertNotNull(r)
        assertTrue("A preservado", r!!.text.contains("resultado de A"))
        assertTrue("parcial de B preservada", r.text.contains("parcial de B"))
    }

    // ---------------------------------------- R16/B1 tentativa aberta

    @Test
    fun `R16 B1 abrir B com A aberta e RECUSADO e nao mistura blocos`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        val kA = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        assertTrue(l.appendSegment(kA, "velho"))
        // sem finish(A): abrir B tem de ser recusado
        val kB = l.beginAttempt(job, 11L, "B.wav", run, act)
        assertNull("nao pode abrir tentativa sobre outra aberta", kB)
        assertEquals("recusa contabilizada", 1, l.rejectedAttemptOpen)
        // A continua valida e publica so o dela
        assertNotNull(l.finishAttempt(kA, JobTerminalState.SUCCESS, "ok"))
        val r = l.publishJob(job, 11L, JobTerminalState.SUCCESS, "ok")
        assertNotNull(r)
        assertEquals("velho", r!!.text)
        assertEquals("um bloco", 1, r.blocks.count { it.text == "velho" })
    }

    @Test
    fun `R16 B1 apos finish(A) abrir B e permitido`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        val kA = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        assertTrue(l.appendSegment(kA, "primeiro"))
        assertNotNull(l.finishAttempt(kA, JobTerminalState.SUCCESS, "ok"))
        val kB = l.beginAttempt(job, 11L, "B.wav", run, act)
        assertNotNull("com A fechada, B abre", kB)
        assertTrue(l.appendSegment(kB!!, "segundo"))
        assertNotNull(l.finishAttempt(kB, JobTerminalState.SUCCESS, "ok"))
        val r = l.publishJob(job, 11L, JobTerminalState.SUCCESS, "ok")
        assertEquals("primeiro\nsegundo", r!!.text)
        assertEquals(0, l.rejectedAttemptOpen)
    }

    @Test
    fun `R14 E2 os tempos do segmento sao preservados no bloco`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        val key = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        assertTrue(l.appendSegment(key, "PRIMEIRO", 0L, 1200L))
        assertTrue(l.appendSegment(key, "SEGUNDO", 1200L, 3400L))
        val r = l.publishJob(job, 11L, JobTerminalState.SUCCESS, "ok")
        assertNotNull(r)
        val blocos = r!!.blocks
        assertEquals("dois segmentos com tempo", 2, blocos.size)
        assertEquals(0L, blocos[0].t0Ms)
        assertEquals(1200L, blocos[0].t1Ms)
        assertEquals(1200L, blocos[1].t0Ms)
        assertEquals(3400L, blocos[1].t1Ms)
        // o texto NÃO pode estar duplicado (o agregado + os segmentos)
        assertEquals("PRIMEIRO\nSEGUNDO", r.text)
    }

    @Test
    fun `R14 E2 segmento sem tempo fica null e nao zero`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        val key = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        assertTrue(l.appendSegment(key, "SEM_TEMPO"))
        val r = l.publishJob(job, 11L, JobTerminalState.SUCCESS, "ok")
        assertNotNull(r)
        val b = r!!.blocks.first { it.text == "SEM_TEMPO" }
        assertNull("tempo ausente é null, não zero", b.t0Ms)
        assertNull(b.t1Ms)
    }

    @Test
    fun `cancel com parcial publica CANCELLED com texto parcial do proprio job`() {
        val l = TranscriptionJobLedger()
        val job = l.beginJob(run, act, 11L)
        val key = l.beginAttempt(job, 11L, "A.wav", run, act)!!
        l.appendSegment(key, "PARCIAL_VALIDA")
        val r = l.publishJob(job, 11L, JobTerminalState.CANCELLED, "cancelado")
        assertNotNull(r)
        assertEquals(TextOrigin.PARTIAL, r!!.origin)
        assertFalse("parcial cancelada não é elegível a WER final", r.werEligible)
        assertEquals("PARCIAL_VALIDA", r.text)
    }

    @Test
    fun `cancel antes de qualquer parcial publica NONE sem texto de job anterior`() {
        val l = TranscriptionJobLedger()
        // Job anterior com texto
        val jobA = l.beginJob(run, act, 11L)
        val kA = l.beginAttempt(jobA, 11L, "A.wav", run, act)!!
        l.appendSegment(kA, "TEXTO_DO_JOB_ANTERIOR")
        l.publishJob(jobA, 11L, JobTerminalState.SUCCESS, "ok")

        // Job novo cancelado antes de qualquer parcial
        val jobB = l.beginJob(run, act, 22L)
        l.beginAttempt(jobB, 22L, "B.wav", run, act)
        val r = l.publishJob(jobB, 22L, JobTerminalState.CANCELLED, "cancelado")
        assertNotNull(r)
        assertEquals(TextOrigin.NONE, r!!.origin)
        assertTrue("sem decode ⇒ export vazio", r.text.isEmpty())
        assertFalse(r.werEligible)
    }
}
