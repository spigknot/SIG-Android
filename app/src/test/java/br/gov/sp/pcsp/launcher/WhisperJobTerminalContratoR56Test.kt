package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R56/B03 — contrato PRODUTOR -> CONSUMIDOR do terminal do worker.
 *
 * Origem do defeito (correção do KDoc, checkpoint9):
 *  - o ACK quebrado do caminho LIVE foi observado no bruto
 *    `c3-r56b02c-cancel-open-1.jsonl` (terminal `act1.job1.gen1` sem ACK →
 *    `job_owner_gone_sem_ack` → `job_stuck`);
 *  - o literal `RECORDED_TERMINAL` da rota de GRAVAÇÃO foi descoberto por
 *    LEITURA DO FONTE, não por uma execução de gravação naquele C3.
 *
 * `WhisperJobLifecycle.terminalKind` só reconhece SUCCESS/CANCELLED/ERROR
 * (WORKER_ACK) e LIVE_TERMINAL (OWNER_DECLARED); qualquer outro literal cai em
 * UNKNOWN e o dono nunca recebe ACK.
 *
 * Estes testes são SUPLEMENTARES: varrem a fonte real e falham se um literal
 * fora do contrato voltar. Eles NÃO cobrem o encadeamento assíncrono do valor de
 * `desfechoGravacao` — para isso existe o teste estrutural abaixo, que exige o
 * cálculo no worker ANTES de qualquer `runOnUiThread`.
 */
class WhisperJobTerminalContratoR56Test {

    private val fonte = File("src/main/java/br/gov/sp/pcsp/launcher/WhisperActivity.kt")

    @Test
    fun `a fonte existe para o teste nao passar em vazio`() {
        assertTrue("fonte ausente: ${fonte.absolutePath}", fonte.isFile)
    }

    @Test
    fun `desfecho da gravacao e calculado no worker antes da UI`() {
        val texto = fonte.readText()
        val ini = texto.indexOf("private fun transcribeRecordedWav")
        assertTrue("função não encontrada", ini >= 0)
        val trecho = texto.substring(ini)
        val calc = trecho.indexOf("desfechoGravacao = when (terminalGravacao)")
        val tipado = trecho.indexOf("val terminalGravacao = when (parsed)")
        val ui = trecho.indexOf("runOnUiThread", calc.coerceAtLeast(0))
        val fim = trecho.indexOf("} finally {", calc.coerceAtLeast(0))
        // adendo18: o desfecho passou a sair de UM mapeamento tipado
        // (terminalGravacao) para ACK e publicação nunca divergirem.
        assertTrue("mapeamento tipado do terminal ausente", tipado >= 0)
        assertTrue("cálculo do desfecho ausente", calc >= 0)
        assertTrue("desfecho deve derivar do mapeamento tipado", calc > tipado)
        assertTrue("runOnUiThread deve vir DEPOIS do cálculo (calc=$calc ui=$ui)", ui > calc)
        assertTrue("finally deve vir depois do cálculo", fim > calc)
        val dentroDaUi = trecho.substring(ui, fim)
        assertTrue(
            "o Runnable da UI não pode escrever no desfecho (corrida com o finally)",
            !dentroDaUi.contains("desfechoGravacao =")
        )
    }

    @Test
    fun `gravacao publica resultado proprio no ledger antes da UI`() {
        // adendo18/19: ACK não é resultado. O export só tem identidade própria se
        // o produtor abrir job/tentativa e PUBLICAR o payload — e isso não pode
        // depender da fila de UI (Activity destruída ainda publica).
        val texto = fonte.readText()
        val ini = texto.indexOf("private fun transcribeRecordedWav")
        assertTrue("função não encontrada", ini >= 0)
        val trecho = texto.substring(ini)
        assertTrue("beginJob ausente", trecho.contains("TranscriptionJobResults.ledger.beginJob("))
        assertTrue("beginAttempt ausente", trecho.contains("TranscriptionJobResults.ledger.beginAttempt("))
        assertTrue("appendSegment ausente", trecho.contains("TranscriptionJobResults.ledger.appendSegment("))
        assertTrue("finalizador ausente", trecho.contains("GravacaoResultadoFinal("))
        val pub = trecho.indexOf("finalizador.publicar(")
        assertTrue("publicação ausente", pub >= 0)
        val ancora = trecho.indexOf("val terminalGravacao")
        val uiDepois = trecho.indexOf("runOnUiThread", ancora)
        assertTrue("publicação deve vir ANTES do post de UI", pub in 1 until uiDepois)
        // adendo19: nenhum retorno/catch pode deixar o job admitido sem resultado.
        assertTrue(
            "falha pré-parse precisa publicar ERROR",
            trecho.contains("finalizador.publicar(JobTerminalState.ERROR, \"model_load_failed\")")
        )
        assertTrue(
            "finally precisa convergir o desfecho",
            trecho.contains("finalizador.publicado == null")
        )
        // O terminal publicado não pode ser literal: tem de ser o tipado.
        val literal = Regex("""publishJob\s*\([^)]*"[A-Z_]+"\s*\)""").containsMatchIn(trecho)
        assertTrue("publishJob não aceita literal de terminal", !literal)
    }

    @Test
    fun `finishJob nao recebe literal fora do contrato`() {
        val texto = fonte.readText()
        val literais = Regex("""finishJob\s*\([^)]*?"([A-Z_]+)"""")
            .findAll(texto)
            .map { it.groupValues[1] }
            .toSet()
        val permitidos = setOf("SUCCESS", "CANCELLED", "ERROR", "LIVE_TERMINAL")
        assertEquals(
            "literais de terminal fora do contrato (caem em UNKNOWN e travam o ACK)",
            emptySet<String>(),
            literais - permitidos
        )
    }

    @Test
    fun `ack do live tem sink no worker e nao depende so da UI`() {
        val texto = fonte.readText()
        // 1. o sink existe e e' idempotente por geracao
        assertTrue("helper ausente", texto.contains("private fun ackTerminalLive("))
        assertTrue("dedupe por geracao ausente", texto.contains("liveAckGenEmitido"))
        // 2. completeLiveMicStop nao chama finishJob direto (passa pelo sink)
        val ini = texto.indexOf("private fun completeLiveMicStop()")
        val fim = texto.indexOf("private fun runLiveMicLoop(")
        assertTrue("bloco de stop nao encontrado", ini >= 0 && fim > ini)
        assertTrue(
            "completeLiveMicStop deve usar o sink",
            texto.substring(ini, fim).contains("ackTerminalLive(")
        )
        // 3. as saidas do laco que hoje dependiam do post de UI chamam o sink
        val laco = texto.substring(fim)
        val chamadas = Regex("""sinkDoJob\.concluir\(""").findAll(laco).count()
        assertTrue("saidas do laco sem sink (encontradas $chamadas)", chamadas >= 3)
    }

    @Test
    fun `excecao sem dreno nao fabrica ACK`() {
        val texto = fonte.readText()
        val ini = texto.indexOf("private fun runLiveMicLoop(")
        val fim = texto.indexOf("private fun runLiveMicLoopCorpo(")
        assertTrue("wrapper não encontrado", ini >= 0 && fim > ini)
        val bloco = texto.substring(ini, fim)
        assertTrue("wrapper deve rastrear término comprovado", bloco.contains("corpoConcluiu"))
        val posIf = bloco.indexOf("if (corpoConcluiu)")
        val posAck = bloco.indexOf("sinkDoJob.concluir(")
        assertTrue("ACK precisa estar sob `corpoConcluiu` (if=$posIf ack=$posAck)",
            posIf >= 0 && posAck > posIf)
        assertTrue("ramo de exceção deve publicar PENDING",
            bloco.contains("live_terminal_pendente"))
    }

    @Test
    fun `worker nao busca o sink em campo mutavel da Activity`() {
        val texto = fonte.readText()
        val ini = texto.indexOf("private fun runLiveMicLoop(")
        val fim = texto.indexOf("/** R8/A1: emite um evento do caminho ao vivo")
        assertTrue("bloco do worker não delimitado", ini >= 0 && fim > ini)
        // Remove comentários antes de checar: o KDoc cita o campo justamente
        // para dizer que NÃO o usa.
        val codigo = texto.substring(ini, fim).lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "worker não pode resolver o dono por terminalSinkAtual (nota11 §2)",
            !codigo.contains("terminalSinkAtual")
        )
        assertTrue(
            "worker não pode passar pelo fallback ackTerminalLive",
            !codigo.contains("ackTerminalLive(")
        )
        assertTrue(
            "sink precisa viajar com o job",
            codigo.contains("sinkDoJob: WhisperJobTerminalSink")
        )
    }

    @Test
    fun `terminalKind cobre exatamente o contrato declarado`() {
        assertEquals(
            WhisperJobLifecycle.TerminalKind.WORKER_ACK,
            WhisperJobLifecycle.terminalKind("SUCCESS")
        )
        assertEquals(
            WhisperJobLifecycle.TerminalKind.WORKER_ACK,
            WhisperJobLifecycle.terminalKind("CANCELLED")
        )
        assertEquals(
            WhisperJobLifecycle.TerminalKind.WORKER_ACK,
            WhisperJobLifecycle.terminalKind("ERROR")
        )
        assertEquals(
            WhisperJobLifecycle.TerminalKind.OWNER_DECLARED,
            WhisperJobLifecycle.terminalKind("LIVE_TERMINAL")
        )
        assertEquals(
            WhisperJobLifecycle.TerminalKind.UNKNOWN,
            WhisperJobLifecycle.terminalKind("RECORDED_TERMINAL")
        )
    }
}
