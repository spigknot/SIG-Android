package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R9/D-fix — VÍNCULO DE OCORRÊNCIA no dreno/reparo ([LiveTextCommitter]).
 *
 * Defeito (fase D, região 24): a região retida tinha o áudio re-decodificado
 * INCLUINDO o contexto `[ws, uniqueStart)` — áudio JÁ comprometido por janelas
 * anteriores — e o reparo devolvia texto de OUTRA ocorrência
 * (`"Amozze. Amoztra doze. A equipe de cã."`), que era ADOTADO (a hipótese do
 * modelo, correta, era perdida). Aqui o contrato exige:
 *  - (a) o áudio do reparo é o da PRÓPRIA região (>= uniqueStart) — nunca o
 *    contexto alheio;
 *  - (b) reparo que NÃO ancora na hipótese é REJEITADO (nada de lixo adotado;
 *    a região vira resultado PARCIAL com a hipótese preservada);
 *  - (c) resolução só por confirmação/reparo ancorado (estado tipado do D2).
 */
class LiveTextRepairBindingR9DTest {

    private fun pcm(samples: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = ((i * 53) % 2571) - 1200
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private val c09 = "Amostra onze. Amostra doze."                 // contexto já comprometido
    private val d01 = "A equipe de campo concluiu a inspeção técnica" // hipótese do MODELO
    private val lixo = "Amozze. Amoztra doze. A equipe de cã."        // reparo ALHEIO

    /** c09 (win 0, sem contexto) + d01 (win 1, contexto c09, UM segmento cobrindo a
     *  janela toda): a região retida fica com o intervalo começando em `ws` (o
     *  defeito da região 24). `segs=false` => janela 1 sem tempos reais (EST). */
    private fun setup(segs: Boolean): LiveTextCommitter {
        val c = LiveTextCommitter()
        c.onWindow(
            0, c09, 0L, 64000L, 0, null,
            listOf(LiveSegmentTime(c09, 0L, 4000L)), 1L, pcm(64000)
        )
        val tail = if (segs) listOf(LiveSegmentTime(d01, 0L, 8000L)) else null
        c.onWindow(
            1, d01, 64000L, 160000L, 32000, null, tail, 1L, pcm(128000)
        )
        return c
    }

    @Test
    fun `R9D-F1 audio do reparo e o da PROPRIA regiao (nao o contexto comprometido)`() {
        val c = setup(segs = true)
        val req = c.takeRepairRequest()
        assertTrue("região retida esperada", req != null)
        assertTrue(
            "startSample=${req!!.startSample} deve ficar >= uniqueStart=64000 (contexto alheio fora)",
            req.startSample >= 64000L
        )
        // reparo ANCORADO (re-decode limpo) é aceito e resolve a região
        assertTrue(c.completeRepair(req.regionId, d01, null))
        c.finalize()
        val ro = c.regionOutcome()
        assertEquals(1, ro.resolvedByRepair)
        assertTrue(ro.integral)
    }

    @Test
    fun `R9D-F2 janela sem tempo real NAO re-decodifica a janela inteira`() {
        val c = setup(segs = false)
        val req = c.takeRepairRequest()
        assertTrue("região retida esperada (EST)", req != null)
        assertTrue(
            "sem tempo real o áudio próprio é o do chunk; startSample=${req!!.startSample} >= 64000",
            req.startSample >= 64000L
        )
    }

    @Test
    fun `R9D-F3 reparo DIVERGENTE e REJEITADO e nunca entra no display`() {
        val c = setup(segs = true)
        val req = c.takeRepairRequest()
        assertTrue(req != null)
        assertFalse("reparo divergente deve ser rejeitado", c.completeRepair(req!!.regionId, lixo, null))
        c.finalize()
        val display = c.displayText()
        assertFalse("lixo 'cã' não pode ser adotado: \"$display\"", display.contains("cã"))
        assertFalse("lixo 'Amozze' não pode ser adotado: \"$display\"", display.contains("Amozze"))
        assertTrue("hipótese do MODELO preservada: \"$display\"", display.contains(d01))
        val ro = c.regionOutcome()
        assertEquals(0, ro.resolvedByRepair)
        assertEquals(1, ro.promotedUnconfirmed)
        assertEquals(1, ro.promotedRepairFailed)
        assertFalse(ro.integral)
    }

    @Test
    fun `R9D-F4 reparo ANCORADO parcial continua aceito (sem regressao)`() {
        val c = setup(segs = true)
        val req = c.takeRepairRequest()
        assertTrue(req != null)
        assertTrue(c.completeRepair(req!!.regionId, "A equipe de campo", null))
        c.finalize()
        val ro = c.regionOutcome()
        assertEquals(0, ro.resolvedByRepair)
        assertEquals(1, ro.promotedUnconfirmed)
        assertEquals(1, ro.promotedRepairPartial)
        assertTrue(c.displayText().contains("A equipe de campo"))
    }
}
