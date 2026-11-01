package br.gov.sp.pcsp.launcher

/**
 * Leitura de WAV PCM para a rota ORT (adendo 17 §3).
 *
 * Separa o que é FORMATO (converte) do que é CORRUPÇÃO (erro terminal) em
 * decisão pura sobre bytes — sem Android, sem I/O — para que a leitura real
 * seja provável em JVM com arquivos RIFF verdadeiros (inclusive com chunks
 * extras como `LIST`, como o c.wav de 30,016 s). Antes, qualquer exceção caía
 * na conversão: um arquivo truncado era "consertado" pelo FFmpeg e transcrito
 * pela metade como se fosse o inteiro.
 *
 * O chamador (rota) aplica o teto de tamanho ANTES de alocar e converte o
 * [Leitura] no seu erro tipado; nada aqui toca em arquivo.
 */
object WhisperOrtWav {

    /** Desfecho tipado da leitura dos bytes. */
    sealed class Leitura {
        /** PCM 16 kHz mono pronto para o frontend. */
        data class Pcm(
            val amostras: FloatArray,
            val taxa: Int,
            val canais: Int,
            val bits: Int,
        ) : Leitura()

        /** Formato diferente do exigido: vale tentar converter. */
        data class PrecisaConversao(val motivo: String) : Leitura()

        /** Arquivo inválido: converter só esconderia a causa. */
        data class Corrompido(val motivo: String) : Leitura()
    }

    /**
     * Lê WAV PCM16 16 kHz mono de [bytes].
     *
     * Percorre os chunks (pula `LIST` e outros ancilares); lê só `tamDados`
     * declarados do `data` (nunca além do chunk). Arquivo TRUNCADO não é "um
     * WAV mais curto": cabeçalho que promete mais amostras do que existem é
     * corrupção. Chunk corrompido não prende o worker (avanço negativo/zero
     * é recusado).
     */
    fun ler(bytes: ByteArray): Leitura {
        if (bytes.size <= 44) return Leitura.Corrompido("arquivo pequeno demais")
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return Leitura.PrecisaConversao("nao e RIFF")
        if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return Leitura.PrecisaConversao("nao e WAVE")
        var canais = 0
        var taxa = 0
        var bits = 0
        var inicioDados = -1
        var tamDados = 0
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val tamanho = (bytes[i + 4].toInt() and 0xFF) or
                ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                ((bytes[i + 6].toInt() and 0xFF) shl 16) or
                ((bytes[i + 7].toInt() and 0xFF) shl 24)
            if (id == "fmt ") {
                // Validação ESTRUTURAL: o chunk precisa ter ao menos 16 bytes para
                // os campos abaixo existirem; sem isso a leitura estourava o buffer.
                if (tamanho < 16) return Leitura.Corrompido("chunk fmt com $tamanho bytes (< 16)")
                if (i + 24 > bytes.size) return Leitura.Corrompido("chunk fmt além do fim do arquivo")
                val formato = (bytes[i + 8].toInt() and 0xFF) or ((bytes[i + 9].toInt() and 0xFF) shl 8)
                // Só PCM inteiro é lido aqui; float/extensível vai para conversão.
                if (formato != 1) return Leitura.PrecisaConversao("formato WAV $formato: esta rota exige PCM")
                canais = (bytes[i + 10].toInt() and 0xFF) or ((bytes[i + 11].toInt() and 0xFF) shl 8)
                taxa = (bytes[i + 12].toInt() and 0xFF) or ((bytes[i + 13].toInt() and 0xFF) shl 8) or
                    ((bytes[i + 14].toInt() and 0xFF) shl 16) or ((bytes[i + 15].toInt() and 0xFF) shl 24)
                bits = (bytes[i + 22].toInt() and 0xFF) or ((bytes[i + 23].toInt() and 0xFF) shl 8)
            } else if (id == "data") {
                inicioDados = i + 8
                val disponivel = bytes.size - inicioDados
                if (tamanho < 0) return Leitura.Corrompido("chunk data com tamanho invalido")
                if (tamanho > disponivel) {
                    return Leitura.Corrompido(
                        "arquivo WAV truncado: cabecalho declara $tamanho bytes de audio, existem $disponivel",
                    )
                }
                tamDados = tamanho
                break
            }
            if (tamanho < 0 || tamanho > bytes.size) return Leitura.Corrompido("chunk $id com tamanho invalido")
            i += 8 + tamanho + (tamanho and 1)
        }
        WhisperOrtPcm.rejectionForSampleRate(taxa)?.let { return Leitura.PrecisaConversao(it) }
        if (canais != 1) return Leitura.PrecisaConversao("audio com $canais canais: esta rota exige mono")
        if (bits != 16) return Leitura.PrecisaConversao("audio com $bits bits: esta rota exige PCM 16")
        if (inicioDados <= 0 || tamDados <= 0) return Leitura.Corrompido("chunk data ausente ou vazio")
        val pcm = ByteArray(tamDados)
        System.arraycopy(bytes, inicioDados, pcm, 0, tamDados)
        return Leitura.Pcm(WhisperOrtPcm.fromInt16Le(pcm, channels = 1), taxa, canais, bits)
    }
}
