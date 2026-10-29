package br.gov.sp.pcsp.launcher

/**
 * R56/F01-F02 — COORDENADOR DE DRENO do logger no teardown.
 *
 * História do defeito (nota5 §2, adendo13, adendo14 §2):
 *  - o teardown sinalizava o writer **antes** de esperar os produtores, então o
 *    consumidor podia sair enquanto um produtor ainda ia publicar terminal/ACK;
 *  - `join()` devolve `Unit` em Kotlin, então `join(...) != null` "provava"
 *    fechamento com thread viva;
 *  - e um **teto** de 60 s que declarava perda e fechava o writer ainda perdia
 *    eventos posteriores. Aumentar o limite só deslocava a corrida.
 *
 * Regra atual (correta):
 *  1. PENDING/observabilidade são DIAGNÓSTICO, não autorização para fechar.
 *     O logger fica apto a receber enquanto houver produtor legítimo em voo —
 *     por quanto tempo for necessário.
 *  2. O marco (`marcoDiagnosticoMs`) só publica um evento de pendência; o laço
 *     continua até todos os produtores terminarem.
 *  3. Só então o writer é fechado — uma única vez — e `writerVivo()` decide
 *     entre "encerrado" e "pendente" (nunca `join` como prova).
 *  4. Falha de consulta de produtor é INDETERMINADO: não conta como término.
 *
 * O que NÃO faz: interromper inferência, descartar a fila, encerrar por prazo
 * ou liberar owner. `dormir` é injetável para teste curto com latch.
 */
class LoggerTeardownCoordinator(
    private val produtores: List<Pair<String, () -> Boolean>>,
    private val publicar: (String) -> Unit,
    private val fecharWriter: () -> Unit,
    private val writerVivo: () -> Boolean,
    private val dormir: (Long) -> Unit = { Thread.sleep(it) },
) {

    /** Resultado observável do teardown. */
    data class Resultado(
        val drenado: Boolean,
        val marcoDiagnostico: Boolean,
        val writerPendente: Boolean,
        val esperaMs: Long,
        val publicados: List<String>,
    )

    fun executar(
        marcoDiagnosticoMs: Long = 60_000L,
        passoMs: Long = 1_000L,
        joinSlots: Int = 3,
    ): Resultado {
        val publicados = mutableListOf<String>()
        fun emitir(evento: String) {
            publicados += evento
            runCatching { publicar(evento) }
        }

        fun vivos(): List<String> = produtores
            .filterNot { (_, terminou) -> runCatching { terminou() }.getOrDefault(false) }
            .map { it.first }

        var drenado = true
        var marco = false
        var espera = 0L

        // Fase 1 — leitura inicial.
        val vivosIniciais = vivos()
        if (vivosIniciais.isNotEmpty()) {
            drenado = false
            vivosIniciais.forEach { emitir("teardown_produtor_vivo:$it") }
            emitir("teardown_drain_pendente:produtor_em_voo;writer_conservado")
        }

        // Fase 2 — observa ATÉ o fim real. O marco é só diagnóstico.
        while (!drenado) {
            dormir(passoMs)
            espera += passoMs
            val restantes = vivos()
            if (restantes.isEmpty()) {
                drenado = true
                break
            }
            if (!marco && espera >= marcoDiagnosticoMs) {
                marco = true
                emitir("teardown_drain_pendente:marco_atingido;consumidor_conservado")
            }
        }

        // Fase 3 — fechamento ÚNICO, só com dreno comprovado.
        runCatching { fecharWriter() }
        var writerPendente = false
        repeat(joinSlots) {
            if (runCatching { writerVivo() }.getOrDefault(false)) dormir(passoMs)
        }
        if (runCatching { writerVivo() }.getOrDefault(false)) {
            writerPendente = true
            emitir("teardown_writer_pendente:join_timeout")
        }
        return Resultado(drenado, marco, writerPendente, espera, publicados)
    }
}
