package br.gov.sp.pcsp.launcher

/**
 * R16/C1 — balanço de PCM da captura do committer (função pura, host).
 *
 * Amostras (16 kHz). Por janela `window_in`: `pcmBytes` (lido do microfone/
 * fixture na fronteira), `[t0,t1)` (intervalo único) e `ctx` (contexto
 * reenviado, overlap com a janela anterior — NÃO é áudio novo).
 *
 * Fechamento: `lido == unico + reenvio`. Contar o overlap como áudio único
 * é o vício que este balanço impede.
 */
object PcmBalance {

    data class Janela(
        val idx: Int,
        val t0: Long,
        val t1: Long,
        val ctx: Int,
        val pcmBytes: Long,
    )

    /** Intervalo `[ini,fim)` em amostras. */
    data class Faixa(val ini: Long, val fim: Long) {
        val amostras: Long get() = (fim - ini).coerceAtLeast(0L)
    }

    data class Balanco(
        val lidoAmostras: Long,
        val unicoAmostras: Long,
        val reenvioAmostras: Long,
        val fechado: Boolean,
        val detalhe: String,
        val lacunas: List<Faixa> = emptyList(),
        val sobreposicoes: List<Faixa> = emptyList(),
        val foraDeOrdem: Boolean = false,
    )

    /**
     * R17/C3 — mede a UNIÃO dos intervalos, não `max(t1)-min(t0)`.
     *
     * Lacuna e sobreposição do mesmo tamanho não se cancelam mais: cada uma é
     * reportada e qualquer uma delas impede o fechamento.
     */
    fun balanco(js: List<Janela>): Balanco {
        if (js.isEmpty()) return Balanco(0, 0, 0, true, "vazio")

        val lido = js.sumOf { it.pcmBytes / 2 }
        val reenvio = js.sumOf { it.ctx.toLong() }

        val ordenadas = js.sortedWith(compareBy({ it.t0 }, { it.t1 }))
        val foraDeOrdem = ordenadas.map { it.idx } != js.map { it.idx }

        val lacunas = ArrayList<Faixa>()
        val sobreposicoes = ArrayList<Faixa>()
        var cursorIni = ordenadas.first().t0
        var cursorFim = ordenadas.first().t1
        var unico = (cursorFim - cursorIni).coerceAtLeast(0L)

        for (j in ordenadas.drop(1)) {
            if (j.t1 <= j.t0) continue
            when {
                j.t0 > cursorFim -> {
                    lacunas.add(Faixa(cursorFim, j.t0))
                    unico += j.t1 - j.t0
                    cursorFim = j.t1
                }
                // contíguo: começa EXATAMENTE onde o anterior terminou.
                j.t0 == cursorFim -> {
                    unico += j.t1 - j.t0
                    cursorFim = j.t1
                }
                j.t1 <= cursorFim -> sobreposicoes.add(Faixa(j.t0, j.t1))
                else -> {
                    sobreposicoes.add(Faixa(j.t0, cursorFim))
                    unico += j.t1 - cursorFim
                    cursorFim = j.t1
                }
            }
            if (j.t0 < cursorIni) cursorIni = j.t0
        }

        // bytes ímpares = amostra incompleta (16 bits por amostra).
        val impar = js.any { it.pcmBytes % 2L != 0L }
        val fechado = lacunas.isEmpty() && sobreposicoes.isEmpty() && !impar &&
            lido == unico + reenvio

        val detalhe = buildString {
            append("lido=$lido unico=$unico reenvio=$reenvio")
            if (lacunas.isNotEmpty()) {
                append(" lacunas=").append(lacunas.sumOf { it.amostras })
                append("(").append(lacunas.size).append(")")
            }
            if (sobreposicoes.isNotEmpty()) {
                append(" sobreposicoes=").append(sobreposicoes.sumOf { it.amostras })
                append("(").append(sobreposicoes.size).append(")")
            }
            if (foraDeOrdem) append(" fora_de_ordem")
            if (impar) append(" bytes_impares")
        }

        return Balanco(
            lidoAmostras = lido,
            unicoAmostras = unico,
            reenvioAmostras = reenvio,
            fechado = fechado,
            detalhe = detalhe,
            lacunas = lacunas,
            sobreposicoes = sobreposicoes,
            foraDeOrdem = foraDeOrdem,
        )
    }
}
