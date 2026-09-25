package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Política de bucket/backend (P2 do plano) — regra PURA, sem Android.
 *
 * O plano pede explicitamente estes casos: fronteiras de tamanho (199/200/201, 399/400/401,
 * 799/800/801) e a política de bucket/backend validado. Eles são de regra pura — rodam na JVM,
 * não no DSP.
 *
 * O motivo da política: o grafo QUANTIZADO do bucket t2000 derruba o processo do Hexagon DSP na
 * abertura da sessão (medido no aparelho, 14/09) e o erro aparece depois, como `QNN 6001` no
 * sub-grafo seguinte.
 */
class GraniteNarBucketPolicyTest {

    // ---------- escolha do bucket: fronteiras ----------

    @Test
    fun `escolhe pega o menor bucket que caiba, nas fronteiras`() {
        // 200 frames = 4 s de audio (frames = amostras/320, amostras = 16000*t)
        assertEquals(200, GraniteNarBuckets.escolhe(199))
        assertEquals(200, GraniteNarBuckets.escolhe(200))
        assertEquals(400, GraniteNarBuckets.escolhe(201))   // 201 já não cabe em 200
        assertEquals(400, GraniteNarBuckets.escolhe(399))
        assertEquals(400, GraniteNarBuckets.escolhe(400))
        assertEquals(800, GraniteNarBuckets.escolhe(401))
        assertEquals(800, GraniteNarBuckets.escolhe(799))
        assertEquals(800, GraniteNarBuckets.escolhe(800))
        assertEquals(1200, GraniteNarBuckets.escolhe(801))
    }

    @Test
    fun `escolhe nao inventa bucket acima do maior exportado`() {
        assertEquals(2000, GraniteNarBuckets.escolhe(1999))
        assertEquals(2000, GraniteNarBuckets.escolhe(2000))
        // audio maior que o limite: devolve o maior (quem recusa é o teto de duração do engine)
        assertEquals(2000, GraniteNarBuckets.escolhe(2500))
    }

    @Test
    fun `escolhe respeita a lista do que esta disponivel`() {
        val soPequenos = intArrayOf(200, 400)
        assertEquals(400, GraniteNarBuckets.escolhe(350, soPequenos))
        // nada cabe: devolve o maior DISPONÍVEL (nunca um bucket ausente)
        assertEquals(400, GraniteNarBuckets.escolhe(900, soPequenos))
    }

    // ---------- política de backend ----------

    @Test
    fun `cpu aceita todos os buckets`() {
        for (t in GraniteNarBuckets.TODOS) {
            assertNull("t$t no CPU deveria ser permitido", GraniteNarBuckets.politicaBucket(t, false))
        }
    }

    @Test
    fun `acelerador aceita so os buckets liberados nesta fase`() {
        for (t in GraniteNarBuckets.LIBERADOS_NO_DSP) {
            assertNull("t$t deveria estar liberado", GraniteNarBuckets.politicaBucket(t, true))
        }
        for (t in GraniteNarBuckets.TODOS.filter { it !in GraniteNarBuckets.LIBERADOS_NO_DSP }) {
            val motivo = GraniteNarBuckets.politicaBucket(t, true)
            assertNotNull("t$t NÃO deveria ir ao DSP", motivo)
            assertTrue("o motivo tem de citar o bucket", motivo!!.contains("t$t"))
        }
    }

    @Test
    fun `a politica cobre exatamente o t2000 e os grandes`() {
        // trava contra "liberar tudo por engano": só 200 e 400 passam
        val liberados = GraniteNarBuckets.TODOS.filter { GraniteNarBuckets.politicaBucket(it, true) == null }
        assertEquals(listOf(200, 400), liberados.toList())
    }

    // ---------- buckets instalados ----------

    @Test
    fun `bucketsInstalados exige encoder E projector`() {
        val dir = File.createTempFile("buckets", "").let { it.delete(); it.mkdirs(); it }
        try {
            assertTrue(GraniteNarBuckets.bucketsInstalados(dir).isEmpty())

            // só o encoder: NÃO conta
            File(dir, GraniteNarBuckets.encoderFile(200)).writeText("x")
            assertTrue(GraniteNarBuckets.bucketsInstalados(dir).isEmpty())

            // encoder + projector: conta
            File(dir, GraniteNarBuckets.projectorFile(200)).writeText("y")
            assertEquals(listOf(200), GraniteNarBuckets.bucketsInstalados(dir).toList())

            // um segundo bucket completo
            File(dir, GraniteNarBuckets.encoderFile(400)).writeText("x")
            File(dir, GraniteNarBuckets.projectorFile(400)).writeText("y")
            assertEquals(listOf(200, 400), GraniteNarBuckets.bucketsInstalados(dir).toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nomes de arquivo por bucket seguem o contrato do pacote`() {
        assertEquals("granite-4.1-nar-encoder-t0400-fp16.onnx", GraniteNarBuckets.encoderFile(400))
        assertEquals("granite-4.1-nar-projector-t2000-fp16.onnx", GraniteNarBuckets.projectorFile(2000))
    }
}
