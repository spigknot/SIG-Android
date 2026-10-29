package br.gov.sp.pcsp.launcher

/**
 * R18/A2 — IDENTIDADE DO MODELO (peça pura, host-testável).
 *
 * O defeito que esta peça existe para impedir: a R17 publicou "Q5 44,7 s", mas o
 * bruto do job dizia `ggml-base.bin` com o SHA do BASE. O evento de importação
 * não carregava identidade e o runner só esperava um `model_imported` em
 * QUALQUER ponto — então o braço mediu outro modelo e ninguém viu.
 *
 * Regras:
 *  - campo ausente é NA (UNKNOWN), nunca "passou";
 *  - tamanho E sha têm de bater; nome sozinho não vale;
 *  - divergência é MISMATCH com motivo, e a célula é INVALID/FAIL.
 */
object ModelIdentityGate {

    data class Identidade(val rotulo: String, val bytes: Long, val sha256: String)

    // Expectativas DOS CENÁRIOS DE TESTE (não são whitelist do app).
    val TURBO_F16 = Identidade(
        "Turbo F16", 1_624_555_275L,
        "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69"
    )
    val TURBO_Q5 = Identidade(
        "Turbo Q5_0", 574_041_195L,
        "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"
    )
    val BASE = Identidade(
        "BASE", 147_951_465L,
        "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
    )

    /** Resolve pelo rótulo do cenário, nunca pelo nome do arquivo. */
    fun esperado(rotulo: String?): Identidade? = when (rotulo?.trim()?.lowercase()) {
        "v3turbo", "turbo", "turbo_f16", "f16" -> TURBO_F16
        "q5turbo", "turbo_q5", "q5", "turbo_q5_0" -> TURBO_Q5
        "base" -> BASE
        else -> null
    }

    /** R18/A2 — identidade esperada a partir do NOME do arquivo de fixture.
     *  Só para o cenário declarar a expectativa; o confronto usa SHA + bytes. */
    fun esperadoPorArquivo(nome: String?): Identidade? {
        val n = nome?.lowercase() ?: return null
        return when {
            n.contains("q5_0") || n.contains("q5-") -> TURBO_Q5
            n.contains("turbo") -> TURBO_F16
            n.contains("base") -> BASE
            else -> null
        }
    }

    data class Carregado(
        val caminho: String,
        val nome: String,
        val bytes: Long,
        val sha256: String,
    )

    /**
     * Lê a chave do evento `model_loaded`, no formato real do fork:
     *   /caminho/modelo.bin|0|false|<sha256>:<bytes>
     * @return null quando o formato não é reconhecível (ausência ≠ zero).
     */
    fun parseLoaded(key: String?): Carregado? {
        val t = key?.trim().orEmpty()
        if (t.isEmpty()) return null
        val partes = t.split('|')
        if (partes.size < 4) return null
        val caminho = partes[0]
        val ident = partes[3]
        val i = ident.lastIndexOf(':')
        if (i <= 0) return null
        val sha = ident.substring(0, i).trim().lowercase()
        val bytes = ident.substring(i + 1).trim().toLongOrNull() ?: return null
        if (sha.length != 64 || sha.any { it !in "0123456789abcdef" }) return null
        return Carregado(
            caminho = caminho,
            nome = caminho.substringAfterLast('/'),
            bytes = bytes,
            sha256 = sha,
        )
    }

    enum class Veredito { PASS, MISMATCH, UNKNOWN }

    data class Resultado(val veredito: Veredito, val motivo: String)

    fun confrontar(esperado: Identidade?, carregado: Carregado?): Resultado = when {
        esperado == null -> Resultado(Veredito.UNKNOWN, "rotulo_sem_expectativa")
        carregado == null -> Resultado(Veredito.UNKNOWN, "loaded_ausente_ou_ilegivel")
        carregado.bytes != esperado.bytes ->
            Resultado(Veredito.MISMATCH,
                "bytes ${carregado.bytes} != ${esperado.bytes} (${esperado.rotulo})")
        carregado.sha256 != esperado.sha256 ->
            Resultado(Veredito.MISMATCH,
                "sha ${carregado.sha256.take(16)} != ${esperado.sha256.take(16)} (${esperado.rotulo})")
        else -> Resultado(Veredito.PASS, "identidade_ok:${esperado.rotulo}")
    }

    /**
     * R18/A2 — decisão da SELEÇÃO: arquivo publicado não é seleção aplicada.
     * Só conta como selecionado quando o callback é o corrente E o hash do
     * arquivo publicado é o esperado.
     */
    data class Selecao(val aplicada: Boolean, val sha256: String?, val motivo: String)

    fun selecao(aplicada: Boolean, shaPublicado: String?, esperado: Identidade?): Selecao = when {
        esperado == null -> Selecao(false, shaPublicado, "rotulo_sem_expectativa")
        shaPublicado.isNullOrBlank() -> Selecao(false, shaPublicado, "sha_ausente")
        shaPublicado.lowercase() != esperado.sha256 ->
            Selecao(false, shaPublicado, "sha_divergente_${esperado.rotulo}")
        !aplicada -> Selecao(false, shaPublicado, "stale_ou_recusada")
        else -> Selecao(true, shaPublicado, "selecionado_${esperado.rotulo}")
    }
}
