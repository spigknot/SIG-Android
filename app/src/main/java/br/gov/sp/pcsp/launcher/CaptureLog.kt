package br.gov.sp.pcsp.launcher

import org.json.JSONObject

/**
 * R16/C1 — leitor ESTRITO do log de captura append-only do committer.
 *
 * Função pura (host, sem modelo/ADB). Distingue:
 * - `PASS`: log íntegro, janelas extraíveis em ordem;
 * - `INCOMPLETE`: captura truncada (linha inválida/JSON parcial) ou com
 *   buraco de sequência — o replay não pode concluir;
 * - `FAIL`: ordem alterada, campo obrigatório ausente ou schema divergente.
 *
 * O replay ([CaptureReplayTest]) só aceita `PASS`; qualquer outro estado
 * impede aprovar equivalência — nunca se "completa" um log quebrado.
 */
object CaptureLog {

    data class Resultado(
        val estado: String,
        val problema: String,
        val janelas: List<JSONObject> = emptyList(),
    )

    fun parse(linhas: List<String>): Resultado {
        val uteis = linhas.mapIndexedNotNull { i, l ->
            val t = l.trim()
            if (t.isEmpty()) null else i to t
        }
        if (uteis.isEmpty()) return Resultado("INCOMPLETE", "log vazio", emptyList())
        val objs = mutableListOf<JSONObject>()
        for ((num, texto) in uteis) {
            val o = try {
                JSONObject(texto)
            } catch (e: Exception) {
                return Resultado("INCOMPLETE", "linha ${num + 1} invalida (truncada?)", emptyList())
            }
            if (o.optInt("v", -1) !in setOf(1, 2)) {
                return Resultado("FAIL", "linha ${num + 1} schema v inválida", emptyList())
            }
            objs.add(o)
        }
        // sequência: o conjunto tem de ser {1..N} contíguo; fora de ordem é
        // FAIL (ordem alterada), com falta é INCOMPLETE (buraco/truncada).
        val seqs = objs.map { it.optInt("seq", -1) }
        if (seqs.any { it < 1 }) {
            return Resultado("FAIL", "seq ausente/invalida", emptyList())
        }
        val ordenadas = seqs.sorted()
        val contiguas = (1..objs.size).toList()
        if (ordenadas != contiguas) {
            val falta = contiguas.firstOrNull { it !in seqs }
            return Resultado("INCOMPLETE", "buraco de sequencia: falta seq=$falta", emptyList())
        }
        if (seqs != ordenadas) {
            val pos = seqs.indices.first { seqs[it] != ordenadas[it] }
            return Resultado("FAIL", "ordem alterada na posicao $pos (seq=${seqs[pos]})", emptyList())
        }
        // identidade da sessão: todas as linhas que a declaram têm de concordar.
        val ids = objs.mapNotNull { o -> o.optString("capture_id").takeIf { it.isNotEmpty() } }
            .distinct()
        if (ids.size > 1) {
            return Resultado("FAIL", "capture_id divergente (${ids.size} sessões no arquivo)", emptyList())
        }
        // R18/D3 — schema v2 exige capture_id em TODA linha. Antes, um arquivo
        // v2 sem nenhum id passava como se fosse v1.
        if (objs.any { it.optInt("v", 1) >= 2 }) {
            val semId = objs.firstOrNull { it.optString("capture_id").isEmpty() }
            if (semId != null) {
                return Resultado("FAIL",
                    "linha seq=${semId.optInt("seq")} sem capture_id (schema v2)", emptyList())
            }
        }

        // reparos: campos obrigatórios + pareamento pedido→resultado/falha.
        val pedidos = HashSet<String>()
        val pendentes = HashSet<String>()
        for (o in objs.filter { it.optString("ev") == "repair" }) {
            for (campo in listOf("idx", "t1", "t2", "ok", "fase")) {
                if (!o.has(campo)) {
                    return Resultado("FAIL", "repair seq=${o.optInt("seq")} sem campo $campo", emptyList())
                }
            }
            val fase = o.optString("fase")
            if (fase !in setOf("pedido", "resultado", "falha")) {
                return Resultado("FAIL", "repair seq=${o.optInt("seq")} fase inválida", emptyList())
            }
            val chave = o.optString("regiao", "-")
            if (fase == "pedido") {
                pedidos.add(chave)
                pendentes.add(chave)
            } else {
                if (!pedidos.contains(chave)) {
                    return Resultado("FAIL",
                        "repair seq=${o.optInt("seq")} fase=$fase sem pedido correspondente (regiao=$chave)",
                        emptyList())
                }
                // R18/D3 — desfecho fecha o pedido; sem ele o fecho é INCOMPLETE.
                pendentes.remove(chave)
            }
        }
        // janelas: campos obrigatórios (inclui PCM) + idx contíguo
        val janelas = objs.filter { it.optString("ev") == "window_in" }
        var idxEsperado = 0
        for (w in janelas) {
            for (campo in listOf("idx", "t0", "t1", "ctx", "texto", "pcmBytes", "pcmSha256")) {
                if (!w.has(campo)) {
                    return Resultado("FAIL", "window_in seq=${w.optInt("seq")} sem campo $campo", emptyList())
                }
            }
            val idx = w.optInt("idx", -1)
            if (idx < idxEsperado) {
                return Resultado("FAIL", "janela fora de ordem (idx=$idx, esperado>=$idxEsperado)", emptyList())
            }
            if (idx > idxEsperado) {
                return Resultado("INCOMPLETE", "janela faltante (idx esperado=$idxEsperado, obtido=$idx)", emptyList())
            }
            idxEsperado = idx + 1
        }
        // R17/C1 — SESSÃO FECHADA (avaliada DEPOIS do schema: violação de campo
        // é FAIL; ausência de fecho é INCOMPLETE). Um arquivo que termina numa
        // linha completa mas sem `capture_end` é TRUNCAMENTO LIMPO — o defeito
        // que a R16 aprovava (o seq ficava "contíguo" e passava).
        val evs = objs.map { it.optString("ev") }
        if (evs.firstOrNull() != "capture_start") {
            return Resultado("FAIL", "primeira linha não é capture_start", emptyList())
        }
        if (!evs.contains("final")) {
            return Resultado("INCOMPLETE", "captura sem snapshot final", emptyList())
        }
        if (evs.lastOrNull() != "capture_end") {
            return Resultado("INCOMPLETE",
                "captura sem capture_end (truncamento limpo?) — última linha: ${evs.lastOrNull()}",
                emptyList())
        }
        // R18/D3 — FECHO COERENTE. Os quatro contraexemplos da revisão passavam
        // aqui; cada um vira FAIL com motivo próprio.
        // SEMÂNTICA DO WRITER (CommitCapture.fechar): `seq_final` é o seq da
        // linha `final` e `escritas` conta as linhas gravadas ANTES do
        // `capture_end` — ambos iguais a (linhas - 1) num arquivo bem formado.
        val fim = objs.last()
        val linhasAntesDoFim = objs.size - 1
        if (fim.has("valida") && !fim.optBoolean("valida", true)) {
            return Resultado("FAIL", "capture_end.valida=false (escrita invalidada)", emptyList())
        }
        if (fim.has("seq_final") && fim.optInt("seq_final", -1) != linhasAntesDoFim) {
            return Resultado("FAIL",
                "capture_end.seq_final=${fim.optInt("seq_final")} != final esperado=$linhasAntesDoFim",
                emptyList())
        }
        if (fim.has("escritas") && fim.optInt("escritas", -1) != linhasAntesDoFim) {
            return Resultado("FAIL",
                "capture_end.escritas=${fim.optInt("escritas")} != linhas antes do fecho=$linhasAntesDoFim",
                emptyList())
        }
        if (pendentes.isNotEmpty()) {
            return Resultado("INCOMPLETE",
                "reparo sem desfecho no fecho: ${pendentes.sorted()}", emptyList())
        }
        return Resultado("PASS", "ok", janelas)
    }
}
