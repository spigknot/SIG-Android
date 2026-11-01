package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * R19/C1 — testes de barreira da reserva do worker.
 *
 * Cada caso corresponde a uma intercalação que a ordem exige: reserva antes do
 * init, recusa de segundo dono, ACK atrasado de outra geração e fim forçado.
 */
class WorkerReservationTest {

    private fun novo() = WorkerReservation.State()

    @Test
    fun `reserva simples e idempotente`() {
        val s = novo()
        assertEquals(WorkerReservation.Decision.RESERVED, s.reserve(1L))
        assertTrue(s.heldBy(1L))
        assertEquals(WorkerReservation.Decision.ALREADY_HELD, s.reserve(1L))
    }

    @Test
    fun `segundo dono e recusado e nao rouba a reserva`() {
        val s = novo()
        s.reserve(1L)
        assertEquals(WorkerReservation.Decision.REFUSED_OTHER_OWNER, s.reserve(2L))
        assertTrue(s.heldBy(1L))
        assertFalse(s.heldBy(2L))
    }

    @Test
    fun `release de geracao errada nao libera`() {
        val s = novo()
        s.reserve(1L)
        assertFalse(s.release(2L))
        assertTrue(s.heldBy(1L))
    }

    @Test
    fun `release do dono libera e permite a proxima`() {
        val s = novo()
        s.reserve(1L)
        assertTrue(s.release(1L))
        assertFalse(s.held())
        assertEquals(WorkerReservation.Decision.RESERVED, s.reserve(2L))
    }

    @Test
    fun `adopt da mesma geracao confirma e de outra recusa`() {
        val s = novo()
        s.reserve(5L)
        assertTrue(s.adopt(5L))
        assertFalse(s.adopt(6L))
        assertTrue(s.heldBy(5L))
    }

    @Test
    fun `adopt em reserva vazia assume a geracao do servico`() {
        val s = novo()
        assertTrue(s.adopt(9L))
        assertTrue(s.heldBy(9L))
    }

    @Test
    fun `ack atrasado de A depois de B nao derruba B`() {
        val s = novo()
        s.reserve(1L)
        s.release(1L)
        s.reserve(2L)
        // ACK/terminal atrasado da geração 1 chegando agora:
        assertFalse(s.release(1L))
        assertTrue(s.heldBy(2L))
    }

    @Test
    fun `clear devolve o dono anterior e libera`() {
        val s = novo()
        s.reserve(3L)
        assertEquals(3L, s.clear())
        assertFalse(s.held())
        assertEquals(-1L, s.clear())
    }

    @Test
    fun `geracao negativa e recusada`() {
        val s = novo()
        assertEquals(WorkerReservation.Decision.REFUSED_OTHER_OWNER, s.reserve(-1L))
        assertFalse(s.held())
    }

    @Test
    fun `store compartilhado aplica a mesma politica`() {
        WorkerReservationStore.clear()
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(7L))
        assertEquals(WorkerReservation.Decision.REFUSED_OTHER_OWNER, WorkerReservationStore.reserve(8L))
        assertTrue(WorkerReservationStore.adopt(7L))
        assertFalse(WorkerReservationStore.release(8L))
        assertTrue(WorkerReservationStore.release(7L))
        assertFalse(WorkerReservationStore.heldBy(7L))
        WorkerReservationStore.clear()
    }

    @Test
    fun `ort timestamp depois cpp sequencial recusava para sempre`() {
        // Adendo 17 §4 — mecanismo do bug real: a rota ORT reserva com
        // elapsedRealtime (~1,8e12) e o CPP com o id da sessão nativa (2).
        // Sem unificação de domínio, o CPP caía em REFUSED_STALE_GENERATION
        // para sempre depois de qualquer job ORT.
        val s = novo()
        val ortGen = 1_793_469_000_000L
        assertEquals(WorkerReservation.Decision.RESERVED, s.reserve(ortGen))
        assertTrue(s.release(ortGen, "SUCCESS"))
        assertEquals(
            WorkerReservation.Decision.REFUSED_STALE_GENERATION,
            s.reserve(2L),
        )
    }

    @Test
    fun `token comum permite cpp depois de ort`() {
        val s = novo()
        val ortGen = 1_793_469_000_000L
        assertEquals(WorkerReservation.Decision.RESERVED, s.reserve(ortGen))
        assertTrue(s.release(ortGen, "SUCCESS"))
        // Adendo 18 §2 — o token vem do alocador COMUM (base no relógio),
        // nunca da geração lógica pequena: o CPP seguinte não herda morte.
        val cppGen = WorkerReservation.Tokens.alocar(ortGen + 60_000L)
        assertTrue(cppGen > ortGen)
        assertEquals(WorkerReservation.Decision.RESERVED, s.reserve(cppGen))
        // Mesmo token reserva idempotente (dono reentrante).
        assertEquals(WorkerReservation.Decision.ALREADY_HELD, s.reserve(cppGen))
    }

    @Test
    fun `tokens sao unicos e monotonicos mesmo no mesmo tick`() {
        WorkerReservation.Tokens.resetParaTeste()
        val t1 = WorkerReservation.Tokens.alocar(1_793_469_000_000L)
        val t2 = WorkerReservation.Tokens.alocar(1_793_469_000_000L)
        val t3 = WorkerReservation.Tokens.alocar(1_793_469_000_000L)
        // Adendo 18 §2 — dois jobs no MESMO milissegundo não compartilham
        // identidade: relógio só estabelece a base, o contador garante o resto.
        assertNotEquals(t1, t2)
        assertNotEquals(t2, t3)
        assertTrue(t1 < t2 && t2 < t3)
    }

    @Test
    fun `token avança mesmo com relógio recuando`() {
        WorkerReservation.Tokens.resetParaTeste()
        val t1 = WorkerReservation.Tokens.alocar(1_793_469_000_000L)
        // Relógio "voltar" não deve existir; o contrato exige que o token
        // NUNCA volte:
        val t2 = WorkerReservation.Tokens.alocar(1_000L)
        assertTrue(t2 > t1)
    }

    @Test
    fun `token respeita base do relógio atual`() {
        WorkerReservation.Tokens.resetParaTeste()
        val t = WorkerReservation.Tokens.alocar(1_793_469_002_500L)
        assertTrue("token >= relógio atual (nunca abaixo)", t >= 1_793_469_002_500L)
    }

    @Test
    fun `token comum nunca é recusado pelo último terminal do domínio antigo`() {
        WorkerReservation.Tokens.resetParaTeste()
        val s = novo()
        // Cenário do adendo 18 §2: job de arquivo liberou reserva com terminal
        // no domínio do relógio; a gravação/live seguinte (produtor comum) tem
        // seu token ALOCADO DEPOIS do terminal, então ele sempre é > last.
        val tokenArquivo = WorkerReservation.Tokens.alocar(1_793_469_003_000L)
        assertEquals(WorkerReservation.Decision.RESERVED, s.reserve(tokenArquivo))
        assertTrue(s.release(tokenArquivo, "SUCCESS"))
        val tokenGravacao = WorkerReservation.Tokens.alocar(1_793_469_003_000L)
        assertNotEquals("tokens nunca repetem", tokenArquivo, tokenGravacao)
        assertEquals(
            "gravação/live após arquivo deve ter reserva garantida",
            WorkerReservation.Decision.RESERVED,
            s.reserve(tokenGravacao),
        )
    }

    @Test
    fun `ponte registra e esquece par token-logico`() {
        ReservationJobIdentity.registrar(100L, 7L)
        assertEquals(7L, ReservationJobIdentity.logicoDe(100L))
        ReservationJobIdentity.esquecer(100L)
        assertNull(ReservationJobIdentity.logicoDe(100L))
        // ADENDO 19 §1.4 — esquecer aceita SOMENTE o token (chave do domínio
        // da reserva); a geração lógica não é chave de remoção (domínios
        // distintos, ver teste de coincidência abaixo).
        ReservationJobIdentity.registrar(101L, 8L)
        ReservationJobIdentity.esquecer(8L)
        assertEquals("geração lógica NÃO é chave de remoção", 8L, ReservationJobIdentity.logicoDe(101L))
        ReservationJobIdentity.esquecer(101L)
        assertNull(ReservationJobIdentity.logicoDe(101L))
    }

    @Test
    fun `ponte ignora valores não positivos`() {
        ReservationJobIdentity.registrar(0L, 7L)
        ReservationJobIdentity.registrar(105L, 0L)
        ReservationJobIdentity.registrar(-3L, 7L)
        assertNull(ReservationJobIdentity.logicoDe(0L))
        assertNull(ReservationJobIdentity.logicoDe(105L))
        assertNull(ReservationJobIdentity.logicoDe(-3L))
        ReservationJobIdentity.esquecer(0L)
        ReservationJobIdentity.esquecer(-1L)
    }

    @Test
    fun `cadeia ORT-arquivo-gravacao-live-retorno a arquivo sem recusa nem reuso`() {
        // Adendo 18 §2 — a cadeia pedida pelo adendo, no STORE real (mesma
        // política do processo): todos os produtores reservam com token do
        // alocador comum; nenhum cai em REFUSED_STALE; dois jobs no mesmo
        // milissegundo não compartilham identidade; ACK atrasado do A não
        // derruba B; o retorno a arquivo depois do live também reserva.
        WorkerReservation.Tokens.resetParaTeste()
        WorkerReservationStore.clear()
        ReservationJobIdentity.registrar(0L, 0L)  // no-op defensivo
        val agora = 1_793_469_004_000L
        // jobs no MESMO tick (relógio discriminativo é o alocador, não o relógio)
        val ort = WorkerReservation.Tokens.alocar(agora)
        val arquivo = WorkerReservation.Tokens.alocar(agora)
        val gravacao = WorkerReservation.Tokens.alocar(agora)
        val live = WorkerReservation.Tokens.alocar(agora)
        assertNotEquals(ort, arquivo)
        assertNotEquals(arquivo, gravacao)
        assertNotEquals(gravacao, live)
        // ORT termina e ACK atrasado tenta liberar o token do ARQUIVO:
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(ort))
        assertTrue(WorkerReservationStore.release(ort, "SUCCESS"))
        assertFalse("ACK atrasado de outro produtor não libera B", WorkerReservationStore.release(arquivo, "SUCCESS"))
        // Arquivo → gravação → live → arquivo, todos admitidos:
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(arquivo))
        assertTrue(WorkerReservationStore.release(arquivo, "SUCCESS"))
        assertEquals("gravacao depois de arquivo reservada", WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(gravacao))
        assertTrue(WorkerReservationStore.release(gravacao, "CANCELLED"))
        assertEquals("live depois de gravacao reservada", WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(live))
        assertTrue(WorkerReservationStore.release(live, "SUCCESS"))
        val arquivo2 = WorkerReservation.Tokens.alocar(agora)
        assertEquals("retorno a arquivo depois do live", WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(arquivo2))
        assertTrue(WorkerReservationStore.release(arquivo2, "SUCCESS"))
        WorkerReservationStore.clear()
    }

    @Test
    fun `destroy do dono com evento atrasado não derruba o sucessor`() {
        // Adendo 18 §2 — destroy e serviço com eventos atrasados: A morreu,
        // o terminal/ACK dele chega tarde; o dono corrente é B (token sano)
        // e NENHUM release de A atinge B.
        WorkerReservation.Tokens.resetParaTeste()
        WorkerReservationStore.clear()
        val tokenA = WorkerReservation.Tokens.alocar(1_793_469_004_000L)
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(tokenA))
        // Sucessor (B) só pode reservar DEPOIS do terminal de A (o serviço
        // exige terminal do dono antes de soltar).
        assertTrue(WorkerReservationStore.release(tokenA, "CANCELLED"))
        val tokenB = WorkerReservation.Tokens.alocar(1_793_469_004_000L)
        assertEquals(WorkerReservation.Decision.RESERVED, WorkerReservationStore.reserve(tokenB))
        // ACK atrasado do A (registro de terminal atrasado por qualquer via):
        assertFalse(WorkerReservationStore.release(tokenA, "SUCCESS"))
        assertTrue("B continua dono", WorkerReservationStore.heldBy(tokenB))
        // Recusa de terminal do token de A iniciada pelo serviço (quarentena)
        // não mexe em B:
        assertFalse(WorkerReservationStore.release(tokenA, "ERROR"))
        assertTrue(WorkerReservationStore.heldBy(tokenB))
        assertTrue(WorkerReservationStore.release(tokenB, "SUCCESS"))
        WorkerReservationStore.clear()
    }

    @Test
    fun `esquecer remove pelo TOKEN e nao por coincidencia com a geracao logica`() {
        // ADENDO 19 §1.4 — token e geracao lógica são DOMÍNIOS distintos:
        // esquecer um NÚMERO que coincide com a geração de outro par não pode
        // remover esse par.
        ReservationJobIdentity.registrar(100L, 7L)
        assertEquals(7L, ReservationJobIdentity.logicoDe(100L))
        // esquecer(7L) é um call out NO DOMÍNIO DE TOKENS: nada o dono do par
        // (100,7) pode ser apagado por coincidência com a geração 7.
        ReservationJobIdentity.esquecer(7L)
        assertEquals("coincidência numérica NÃO remove o par", 7L, ReservationJobIdentity.logicoDe(100L))
        // Só o TOKEN exato remove:
        ReservationJobIdentity.esquecer(100L)
        assertNull(ReservationJobIdentity.logicoDe(100L))
        // Idempotente:
        ReservationJobIdentity.esquecer(100L)
        assertNull(ReservationJobIdentity.logicoDe(100L))
    }

    @Test
    fun `teto de retencao real atribui eviction FIFO e o dono vivo fica`() {
        // ADENDO 19 §1.4 — o limite declarado de 32 é EXERCITADO: entrar mais
        // de 32 pares remove o MAIS ANTIGO primeiro, o mais recente fica e o
        // par do dono vivo (último) nunca sai por teto.
        WorkerReservation.Tokens.resetParaTeste()
        val base = 1_793_470_000_000L
        val tokens = (0 until 40).map { i ->
            val t = WorkerReservation.Tokens.alocar(base + i)
            ReservationJobIdentity.registrar(t, (1_000L + i))
            t
        }
        // Os 8 mais antigos caem (40-32); os 32 mais recentes ficam:
        assertNull("par mais antigo evicted", ReservationJobIdentity.logicoDe(tokens[0]))
        assertNull("par na fronteira de eviction", ReservationJobIdentity.logicoDe(tokens[7]))
        assertEquals(
            "par na fronteira de retenção",
            1_000L + 8L, ReservationJobIdentity.logicoDe(tokens[8]),
        )
        assertEquals(
            "par do dono vivo (mais recente)",
            1_000L + 39L, ReservationJobIdentity.logicoDe(tokens[39]),
        )
        // Limpeza: os pares deste teste não podem condicionar os próximos
        // (JUnit não garante ordem dentro da classe; FIFO evicted por ordem).
        for (t in tokens) ReservationJobIdentity.esquecer(t)
    }
}
