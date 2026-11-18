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

    // -------------------------------------------------------------- R106/T02

    /**
     * Situação do pacote da NPU para a ENTRADA FIXA (R106). Distingue o pacote
     * que nunca foi instalado ([AUSENTE]) do que está em disco mas não passou na
     * conferência ([RECUSADO], ex.: data/tamanho) e do que a verificação completa
     * marcou como corrompido ([CORROMPIDO]) — cada um oferece uma saída
     * diferente, e nada é apagado sem ação do usuário.
     */
    enum class Situacao { PRONTO, AUSENTE, RECUSADO, CORROMPIDO }

    /**
     * Rótulo da entrada fixa. "Reinstalar aceleração NPU" só quando o pacote em
     * disco foi marcado como corrompido; recusado pela data continua "Ativar
     * aceleração NPU" (a conferência de novo pode resolver sem download algum).
     */
    fun rotuloEntradaFixa(situacao: Situacao, qairtInstalado: Boolean = true): String = when {
        situacao == Situacao.PRONTO && qairtInstalado -> "NPU ativa"
        situacao == Situacao.CORROMPIDO -> "Reinstalar aceleração NPU"
        else -> "Ativar aceleração NPU"
    }

    /**
     * A entrada fixa oferece "Verificar novamente" quando há pacote em disco que
     * não está pronto: a verificação completa por SHA decide, SEM download.
     */
    fun ofereceVerificarNovamente(situacao: Situacao): Boolean =
        situacao == Situacao.RECUSADO || situacao == Situacao.CORROMPIDO

    /**
     * Oferta AUTOMÁTICA (R106): só quando ainda não há pacote em disco. Com o
     * pacote presente e recusado/corrompido, quem decide é a entrada fixa — nada
     * de diálogo de download sozinho em cima de um pacote que já existe.
     */
    fun deveOferecerAutomaticamente(armazenamento: Armazenamento, situacao: Situacao): Boolean =
        situacao == Situacao.AUSENTE && !armazenamento.recusado()
}
