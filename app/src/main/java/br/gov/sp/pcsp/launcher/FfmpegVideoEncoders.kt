package br.gov.sp.pcsp.launcher

import java.util.Locale

/** Catálogo de encoders de vídeo do aparelho e escolha do encoder por tarefa.
 *
 * Port fiel de `video_encoders.py` do SIG Windows, trocando os encoders de lá
 * (NVENC/QSV/AMF/libx264/libx265) pelos do Android: hardware
 * (`*_mediacodec`, um item por encoder do aparelho, com o nome do MediaCodec) e
 * software (`libx264`).
 *
 * Regras (cada escolha devolve um MOTIVO, que vai para o log):
 * - CPU: nunca usa hardware.
 * - Hardware: prefere o melhor encoder do codec pedido e só cai no software
 *   quando (a) o aparelho não tem encoder daquele codec, (b) o Avançado forçou
 *   um encoder que não passou na sondagem, ou (c) no automático o trecho a
 *   reencodar é curto demais para a inicialização do hardware compensar.
 * - Nada de rebaixamento silencioso: falha de hardware repete a tarefa na CPU
 *   sem trocar a preferência do usuário (a execução trata isso).
 *
 * Não toca Android: recebe catálogo/sondagem por parâmetro. */
object FfmpegVideoEncoders {

    const val PATH_HARDWARE = "hardware"
    const val PATH_CPU = "cpu"
    const val ADVANCED_AUTO = "auto"

    val PATH_LABELS = listOf(
        PATH_HARDWARE to "Hardware",
        PATH_CPU to "CPU"
    )

    val pathLabel: (String) -> String = { path ->
        PATH_LABELS.firstOrNull { it.first == path }?.second ?: "Hardware"
    }

    /** Trecho curto: abaixo disto a inicialização do hardware não compensa
     * (mesma regra e mesmo limite medidos no Windows). */
    const val SHORT_JOB_SECONDS = 3.0

    /** Um encoder concreto do catálogo do aparelho (um por combinação suportada). */
    data class Option(
        val key: String,
        val label: String,
        val path: String,
        val codec: String,
        val encoder: String,
        val priority: Int,
        val codecName: String? = null
    )

    /** Encoder escolhido + por que (o motivo vai para o log/etiqueta da tela).
     * `forced` marca a escolha que veio do Avançado (liga o `-codec_name`). */
    data class Choice(val option: Option, val reason: String, val forced: Boolean = false)

    private val CODEC_ALIASES = mapOf(
        "h264" to "h264",
        "avc" to "h264",
        "avc1" to "h264",
        "hevc" to "hevc",
        "h265" to "hevc",
        "hvc1" to "hevc",
        "hev1" to "hevc"
    )

    /** Codec do catálogo ("h264"/"hevc"); vazio quando não há par no catálogo. */
    fun normalizeCodec(codec: String?): String =
        CODEC_ALIASES[codec?.trim()?.lowercase(Locale.ROOT).orEmpty()] ?: ""

    /** Opções do catálogo, filtradas por codec e/ou caminho (hardware/CPU). */
    fun catalogOptions(catalog: List<Option>, codec: String = "", path: String = ""): List<Option> {
        val target = if (codec.isBlank()) "" else normalizeCodec(codec)
        return catalog
            .filter { (target.isBlank() || it.codec == target) && (path.isBlank() || it.path == path) }
            .sortedWith(compareBy({ it.priority }, { it.encoder }))
    }

    /** Encoders de hardware distintos (por família/nome), na ordem de preferência. */
    fun advancedOptions(available: List<Option>): List<Option> {
        val seen = mutableListOf<String>()
        val result = mutableListOf<Option>()
        available
            .filter { it.path == PATH_HARDWARE }
            .sortedWith(compareBy({ it.priority }, { it.encoder }))
            .forEach { option ->
                if (option.key !in seen) {
                    seen += option.key
                    result += option
                }
            }
        return result
    }

    /** Escolhe o encoder para UMA tarefa. `null` quando não existe opção viável. */
    fun resolve(
        codec: String,
        path: String,
        available: List<Option>,
        advanced: String = ADVANCED_AUTO,
        seconds: Double = 0.0,
        shortJobSeconds: Double = SHORT_JOB_SECONDS
    ): Choice? {
        val target = normalizeCodec(codec).ifBlank { "h264" }
        val viable = available.filter { it.codec == target }
        val software = viable.firstOrNull { it.path == PATH_CPU }

        if (path == PATH_CPU) {
            if (software == null) return null
            return Choice(software, "modo CPU escolhido pelo usuário (${software.encoder})")
        }

        val hardware = viable.filter { it.path == PATH_HARDWARE }
        if (advanced.isNotBlank() && advanced != ADVANCED_AUTO) {
            val forced = hardware.firstOrNull { it.key == advanced }
            if (forced != null) {
                return Choice(forced, "${forced.label} forçado no Avançado", forced = true)
            }
            if (software == null) return null
            return Choice(
                software,
                "$advanced não passou na sondagem deste aparelho; usando CPU (${software.encoder})"
            )
        }

        if (hardware.isEmpty()) {
            if (software == null) return null
            return Choice(
                software,
                "o hardware deste aparelho não tem encoder ${target.uppercase(Locale.ROOT)}; " +
                    "usando CPU (${software.encoder})"
            )
        }

        val preferred = hardware.minWithOrNull(compareBy({ it.priority }, { it.encoder })) ?: hardware.first()
        if (seconds > 0.0 && seconds < shortJobSeconds && software != null) {
            val secondsText = String.format(Locale.US, "%.2f", seconds)
            val limitText = if (shortJobSeconds % 1.0 == 0.0) {
                shortJobSeconds.toInt().toString()
            } else {
                String.format(Locale.US, "%s", shortJobSeconds)
            }
            return Choice(
                software,
                "trecho curto de ${secondsText}s (< ${limitText}s): a inicialização do hardware " +
                    "não compensa; usando CPU (${software.encoder})"
            )
        }
        return Choice(
            preferred,
            "hardware disponível para ${target.uppercase(Locale.ROOT)}: ${preferred.label}"
        )
    }

    /** Tag `hvc1` para HEVC em MP4 (sem ela alguns players não abrem o arquivo). */
    fun hevcTagArguments(encoder: String, suffix: String): List<String> {
        val name = encoder.trim().lowercase(Locale.ROOT)
        if (!name.startsWith("libx265") && !name.startsWith("hevc_")) return emptyList()
        val extension = suffix.trim().lowercase(Locale.ROOT).removePrefix(".")
        if (extension !in setOf("mp4", "mov", "m4v")) return emptyList()
        return listOf("-tag:v", "hvc1")
    }

    /** Argumentos que forçam um encoder de hardware específico do aparelho. */
    fun codecNameArguments(option: Option): List<String> = codecNameArguments(option.codecName)

    fun codecNameArguments(codecName: String?): List<String> =
        codecName?.takeIf { it.isNotBlank() }?.let { listOf("-codec_name", it) } ?: emptyList()

    /** Equivalente de CPU para o codec (libx264 no H.264; no Android NÃO existe
     * software de HEVC — devolve `null` e o chamador avisa o usuário). */
    fun cpuEquivalent(codec: String, catalog: List<Option>): Option? {
        val target = normalizeCodec(codec).ifBlank { "h264" }
        return catalog.firstOrNull { it.path == PATH_CPU && it.codec == target }
    }

    /** Falha que parece ser do ENCODER de hardware (e não do arquivo/filtro):
     * só nesses casos a tarefa é repetida na CPU. Marcadores do Windows
     * (NVENC/QSV/AMF/VAAPI/D3D) + os do MediaCodec do Android. */
    fun isHardwareEncoderError(message: String): Boolean {
        val lower = message.lowercase(Locale.ROOT)
        val markers = listOf(
            "nvenc", "cuda", "qsv", "mfx", "amf", "vaapi", "d3d11", "d3d12",
            "hardware device", "device setup failed", "encoder initialization",
            "initializing output stream", "no capable devices", "session limit",
            "mediacodec", "omx", "codec init", "configure failed",
            "no codec", "codec creation", "init failed"
        )
        return markers.any { lower.contains(it) }
    }
}
