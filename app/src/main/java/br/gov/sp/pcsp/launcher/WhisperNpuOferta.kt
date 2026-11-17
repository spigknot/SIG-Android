package br.gov.sp.pcsp.launcher

import android.content.Context

/**
 * R102 §T01 — política da oferta de aceleração NPU na tela do Whisper.
 *
 * Decisão do usuário (17/11/2026, 17:31): a oferta automática acontece **uma
 * única vez**. Depois de "Agora não", a recusa fica gravada e a tela não pergunta
 * de novo; quem mudar de ideia usa a entrada fixa "Ativar aceleração NPU", que
 * abre o MESMO diálogo. A entrada desaparece (ou passa a dizer "NPU ativa") quando
 * os dois pacotes já estão prontos.
 *
 * A decisão é pura e a persistência é uma porta injetável: os dois requisitos da
 * ordem ("não pergunta de novo depois de recusar" e "a entrada abre o diálogo")
 * são provados em JVM, sem Activity nem Android.
 */
object WhisperNpuOferta {

    /** Chave da recusa, no mesmo arquivo de preferências da ferramenta. */
    const val PREF_CHAVE = "whisper_npu_oferta_recusada"

    /** Porta de persistência — a Activity usa [Preferencias]; o teste usa memória. */
    interface Armazenamento {
        fun recusado(): Boolean
        fun registrarRecusa()
    }

    /** Implementação de produção: sobrevive ao fechamento e recriação da tela. */
    class Preferencias(context: Context) : Armazenamento {
        private val prefs = context.applicationContext
            .getSharedPreferences("whisper", Context.MODE_PRIVATE)

        override fun recusado(): Boolean = prefs.getBoolean(PREF_CHAVE, false)

        override fun registrarRecusa() {
            prefs.edit().putBoolean(PREF_CHAVE, true).apply()
        }
    }

    /** O que a tela deve fazer; separado para a prova JVM ser inequívoca. */
    enum class Acao {
        OFERECER,
        NAO_OFERECER,
        ABRIR_ENTRADA_FIXA,
    }

    /** Oferta automática: nunca depois de recusar, nunca com pacotes prontos. */
    fun acaoAutomatica(recusado: Boolean, pacotesProntos: Boolean): Acao = when {
        pacotesProntos -> Acao.NAO_OFERECER
        recusado -> Acao.NAO_OFERECER
        else -> Acao.OFERECER
    }

    /**
     * Entrada fixa: abre o diálogo MESMO depois de uma recusa — é exatamente a
     * saída para quem mudou de ideia. Some quando já não há o que ativar.
     */
    fun acaoEntradaFixa(pacotesProntos: Boolean): Acao =
        if (pacotesProntos) Acao.NAO_OFERECER else Acao.ABRIR_ENTRADA_FIXA

    fun deveOferecerAutomaticamente(armazenamento: Armazenamento, pacotesProntos: Boolean): Boolean =
        acaoAutomatica(armazenamento.recusado(), pacotesProntos) == Acao.OFERECER

    fun entradaFixaVisivel(armazenamento: Armazenamento, pacotesProntos: Boolean): Boolean =
        acaoEntradaFixa(pacotesProntos) == Acao.ABRIR_ENTRADA_FIXA
}
