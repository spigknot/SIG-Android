package br.gov.sp.pcsp.launcher

/**
 * R100 §2.2 — ADMISSÃO da rota HTP do PRODUTO, em regra PURA.
 *
 * A tabela de SoC é a decisão de produto desta rodada: **SM8550 liga** a rota
 * HTP com o contexto e as libs V73; **SM8850 está na tabela e desligado** até a
 * aprovação da M4; qualquer outro SoC usa CPU. A leitura de
 * `Build.SOC_MANUFACTURER`/`Build.SOC_MODEL` acontece no chamador Android — aqui
 * só entram os valores, para o teste JVM poder provar cada ramo.
 *
 * Também mora aqui a política de **falhas consecutivas em processos distintos**:
 * três falhas seguidas desligam o HTP até a próxima versão do pacote. A memória
 * persistida é lida/gravada pelo chamador; a regra é esta função.
 */
object WhisperOrtHtpAdmissao {

    /** SoC admitido nesta rodada (OnePlus 11 / PJA110). */
    const val SOC_SM8550 = "SM8550"

    /** SoC presente na tabela, **desligado** até a M4 (R99). */
    const val SOC_SM8850 = "SM8850"

    const val FABRICANTE_QTI = "QTI"
    const val FABRICANTE_QUALCOMM = "Qualcomm"

    /** Falhas seguidas (uma por processo) que desligam o HTP até a próxima versão. */
    const val FALHAS_PARA_DESLIGAR = 3

    /** Uma arquitetura HTP por SoC admitido; `htpArch` vai para o QNN EP. */
    data class Soc(val modelo: String, val htpArch: String, val habilitado: Boolean)

    /**
     * Tabela de SoC desta versão. SM8850 fica com `habilitado = false`: a
     * cobertura só entra depois da M4, e a entrada existir aqui evita que o
     * aparelho caia em "SoC desconhecido" (o motivo reportado é `SOC_DESLIGADO`).
     */
    private val TABELA = listOf(
        Soc(SOC_SM8550, htpArch = "73", habilitado = true),
        Soc(SOC_SM8850, htpArch = "81", habilitado = false),
    )

    /** Motivo tipado — vai para log/resumo, nunca texto de usuário. */
    enum class Motivo {
        ADMITIDO,
        FABRICANTE_DIFERENTE,
        SOC_DESCONHECIDO,
        SOC_DESLIGADO,
        DESLIGADO_NO_PROCESSO,
        FALHAS_CONSECUTIVAS,

        /** R101 §T02 — o QAIRT (bibliotecas QNN) não está instalado. */
        QAIRT_AUSENTE,
        PACOTE_AUSENTE,
        PACOTE_NAO_VERIFICADO,
    }

    data class Decisao(val usarHtp: Boolean, val motivo: Motivo, val detalhe: String) {
        /** Rótulo da rota efetiva (`htp`/`cpu`), usado na chave do cache e no log. */
        val rota: String get() = if (usarHtp) ROTA_HTP else ROTA_CPU
    }

    const val ROTA_HTP = "htp"
    const val ROTA_CPU = "cpu"

    /**
     * R115 §T02.3 — rota que o pacote VERIFICADO permite:
     * - **v3**: HTP de ponta a ponta (encoder + projetor + decoder na HTP);
     * - **só v2**: rota atual (encoder HTP + decoders CPU);
     * - **nenhum**: CPU.
     *
     * Regra pura, sem Android: a versão vem do manifesto verificado.
     */
    enum class RotaNpu { PONTA_A_PONTA, ENCODER_HTP, CPU }

    fun rotaDaVersao(versaoVerificada: String?): RotaNpu = when (versaoVerificada) {
        WhisperOrtNpuPacote.VERSAO -> RotaNpu.PONTA_A_PONTA
        WhisperOrtNpuPacote.VERSAO_ANTERIOR -> RotaNpu.ENCODER_HTP
        else -> RotaNpu.CPU
    }

    /** `SM8550-AB` e `sm8550` caem na mesma entrada; sufixo de variante é ignorado. */
    fun normalizarSoc(modelo: String?): String =
        modelo.orEmpty().trim().uppercase().substringBefore('-').trim()

    fun socDe(modelo: String?): Soc? = TABELA.firstOrNull { it.modelo == normalizarSoc(modelo) }

    fun fabricanteAdmitido(fabricante: String?): Boolean =
        fabricante.orEmpty().trim().equals(FABRICANTE_QTI, ignoreCase = true) ||
            fabricante.orEmpty().trim().equals(FABRICANTE_QUALCOMM, ignoreCase = true)

    /** Memória persistida das falhas: quantas e de qual versão do pacote. */
    data class MemoriaFalhas(val falhas: Int = 0, val versao: String = "")

    /**
     * `true` quando o histórico já desligou ESTA versão do pacote. Versão nova
     * (ou memória de outra versão) libera a rota de novo.
     */
    fun bloqueadoPorFalhas(memoria: MemoriaFalhas, versaoPacote: String): Boolean =
        versaoPacote.isNotBlank() &&
            memoria.versao == versaoPacote &&
            memoria.falhas >= FALHAS_PARA_DESLIGAR

    /**
     * Decide a rota do pedido. A ordem importa: o motivo reportado é o PRIMEIRO
     * que impede, para o diagnóstico não esconder a causa real atrás de outra.
     *
     * R101 §T02 — a rota exige **os dois** pacotes: o QAIRT (bibliotecas QNN,
     * único no processo, o mesmo do Granite) e o pacote NPU v2 (contexto +
     * wrapper). Sem qualquer um deles a rota é CPU.
     */
    fun decidir(
        socModelo: String?,
        fabricante: String?,
        qairtPresente: Boolean,
        pacotePresente: Boolean,
        pacoteVerificado: Boolean,
        desligadoNoProcesso: Boolean,
        memoriaFalhas: MemoriaFalhas,
        versaoPacote: String,
    ): Decisao {
        val soc = socDe(socModelo)
        val detalheSoc = "soc=${normalizarSoc(socModelo).ifEmpty { "?" }} fabricante=${fabricante.orEmpty()}"
        if (!fabricanteAdmitido(fabricante)) {
            return Decisao(false, Motivo.FABRICANTE_DIFERENTE, detalheSoc)
        }
        if (soc == null) return Decisao(false, Motivo.SOC_DESCONHECIDO, detalheSoc)
        if (!soc.habilitado) return Decisao(false, Motivo.SOC_DESLIGADO, detalheSoc)
        if (desligadoNoProcesso) {
            return Decisao(false, Motivo.DESLIGADO_NO_PROCESSO, detalheSoc)
        }
        if (bloqueadoPorFalhas(memoriaFalhas, versaoPacote)) {
            return Decisao(
                false, Motivo.FALHAS_CONSECUTIVAS,
                "${memoriaFalhas.falhas} falhas na versao $versaoPacote",
            )
        }
        if (!qairtPresente) return Decisao(false, Motivo.QAIRT_AUSENTE, detalheSoc)
        if (!pacotePresente) return Decisao(false, Motivo.PACOTE_AUSENTE, detalheSoc)
        if (!pacoteVerificado) return Decisao(false, Motivo.PACOTE_NAO_VERIFICADO, detalheSoc)
        return Decisao(true, Motivo.ADMITIDO, detalheSoc)
    }
}
