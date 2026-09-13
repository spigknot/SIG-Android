package br.gov.sp.pcsp.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vacina do catálogo de encoders do aparelho e do resolvedor por tarefa.
 *
 * Regras que estes testes travam (as mesmas do SIG Windows):
 * - o principal escolhe só ONDE processar (Hardware/CPU); o app decide o encoder;
 * - o Avançado tem "Automático" e os encoders de hardware que passaram na sondagem;
 * - o hardware é usado SEMPRE que possível: o software só entra quando é
 *   necessário (sem aquele codec, forçado indisponível, ou trecho curto demais)
 *   e sempre com motivo registrado;
 * - o codec do arquivo é preservado quando dá para reencodar (HEVC continua HEVC);
 * - HEVC sem encoder no aparelho devolve `null` (o chamador decide o aviso).
 */
class FfmpegVideoEncodersTest {

    private fun option(
        key: String,
        path: String,
        codec: String,
        encoder: String,
        priority: Int,
        label: String = key,
        codecName: String? = null
    ) = FfmpegVideoEncoders.Option(key, label, path, codec, encoder, priority, codecName)

    // Aparelho de exemplo: dois encoders de hardware de H.264 (Qualcomm e
    // genérico) + os pares HEVC + o software (libx264).
    private val qualcommH264 = option(
        "c2.qti.avc.encoder", FfmpegVideoEncoders.PATH_HARDWARE, "h264",
        "h264_mediacodec", 10, "Qualcomm (c2.qti.avc.encoder)", "c2.qti.avc.encoder"
    )
    private val genericH264 = option(
        "c2.android.avc.encoder", FfmpegVideoEncoders.PATH_HARDWARE, "h264",
        "h264_mediacodec", 30, "Genérico (c2.android.avc.encoder)", "c2.android.avc.encoder"
    )
    private val qualcommHevc = option(
        "c2.qti.hevc.encoder", FfmpegVideoEncoders.PATH_HARDWARE, "hevc",
        "hevc_mediacodec", 10, "Qualcomm (c2.qti.hevc.encoder)", "c2.qti.hevc.encoder"
    )
    private val cpuH264 = option(
        "cpu", FfmpegVideoEncoders.PATH_CPU, "h264", "libx264", 100, "CPU (libx264)"
    )

    @Test
    fun normalizeCodecAceitaAliasesDoArquivo() {
        assertEquals("h264", FfmpegVideoEncoders.normalizeCodec("H264"))
        assertEquals("h264", FfmpegVideoEncoders.normalizeCodec("avc1"))
        assertEquals("hevc", FfmpegVideoEncoders.normalizeCodec("h265"))
        assertEquals("hevc", FfmpegVideoEncoders.normalizeCodec("hvc1"))
        assertEquals("", FfmpegVideoEncoders.normalizeCodec("vp9"))
        assertEquals("", FfmpegVideoEncoders.normalizeCodec(null))
    }

    @Test
    fun catalogoFiltraPorCodecEPorCaminho() {
        val catalog = listOf(qualcommH264, genericH264, qualcommHevc, cpuH264)
        val hardwareH264 = FfmpegVideoEncoders.catalogOptions(catalog, codec = "h264", path = FfmpegVideoEncoders.PATH_HARDWARE)
        assertEquals(listOf(qualcommH264, genericH264), hardwareH264)
        val software = FfmpegVideoEncoders.catalogOptions(catalog, path = FfmpegVideoEncoders.PATH_CPU)
        assertEquals(listOf(cpuH264), software)
    }

    @Test
    fun avancadoListaUmItemPorEncoderDeHardwareNaOrdemDePreferencia() {
        val catalog = listOf(qualcommH264, genericH264, qualcommHevc, cpuH264)
        val advanced = FfmpegVideoEncoders.advancedOptions(catalog)
        // Hardware apenas, sem repetir por codec, prioridade menor primeiro.
        assertEquals(listOf("c2.qti.avc.encoder", "c2.qti.hevc.encoder", "c2.android.avc.encoder"), advanced.map { it.key })
    }

    @Test
    fun hardwareParaTrabalhoLongo() {
        val choice = FfmpegVideoEncoders.resolve(
            codec = "h264", path = FfmpegVideoEncoders.PATH_HARDWARE,
            available = listOf(cpuH264, qualcommH264), seconds = 30.0
        )
        assertEquals("h264_mediacodec", choice?.option?.encoder)
        assertTrue(choice!!.reason.contains("hardware disponível"))
    }

    @Test
    fun hardwareQuandoADuracaoEDesconhecida() {
        val choice = FfmpegVideoEncoders.resolve(
            codec = "h264", path = FfmpegVideoEncoders.PATH_HARDWARE,
            available = listOf(cpuH264, qualcommH264)
        )
        assertEquals("h264_mediacodec", choice?.option?.encoder)
    }

    @Test
    fun melhorEncoderDeHardwarePorPrioridade() {
        val choice = FfmpegVideoEncoders.resolve(
            codec = "h264", path = FfmpegVideoEncoders.PATH_HARDWARE,
            available = listOf(cpuH264, genericH264, qualcommH264), seconds = 20.0
        )
        assertEquals("c2.qti.avc.encoder", choice?.option?.key)
    }

    @Test
    fun hevcUsaOParHevc() {
        val choice = FfmpegVideoEncoders.resolve(
            codec = "hevc", path = FfmpegVideoEncoders.PATH_HARDWARE,
            available = listOf(cpuH264, qualcommHevc), seconds = 20.0
        )
        assertEquals("hevc_mediacodec", choice?.option?.encoder)
        // HEVC sem encoder de CPU no aparelho: CPU devolve null (não existe libx265).
        assertNull(
            FfmpegVideoEncoders.resolve(
                codec = "hevc", path = FfmpegVideoEncoders.PATH_CPU,
                available = listOf(cpuH264, qualcommHevc)
            )
        )
    }

    @Test
    fun cpuEscolhidaPeloUsuario() {
        val choice = FfmpegVideoEncoders.resolve(
            codec = "h264", path = FfmpegVideoEncoders.PATH_CPU,
            available = listOf(cpuH264, qualcommH264), seconds = 30.0
        )
        assertEquals("libx264", choice?.option?.encoder)
        assertTrue(choice!!.reason.contains("CPU"))
    }

    @Test
    fun trechoCurtoExplicaAIdaParaACpu() {
        val choice = FfmpegVideoEncoders.resolve(
            codec = "h264", path = FfmpegVideoEncoders.PATH_HARDWARE,
            available = listOf(cpuH264, qualcommH264), seconds = 0.6
        )
        assertEquals("libx264", choice?.option?.encoder)
        assertTrue(choice!!.reason.contains("curto"))
        assertEquals(3.0, FfmpegVideoEncoders.SHORT_JOB_SECONDS, 0.0)
    }

    @Test
    fun trechoNoLimiteAindaUsaHardware() {
        val choice = FfmpegVideoEncoders.resolve(
            codec = "h264", path = FfmpegVideoEncoders.PATH_HARDWARE,
            available = listOf(cpuH264, qualcommH264), seconds = FfmpegVideoEncoders.SHORT_JOB_SECONDS
        )
        assertEquals("h264_mediacodec", choice?.option?.encoder)
    }

    @Test
    fun hardwareSemOCodecPedidoCaiNaCpuComMotivo() {
        // Aparelho sem nenhum encoder de H.264 por hardware: o software entra com o motivo.
        val choice = FfmpegVideoEncoders.resolve(
            codec = "h264", path = FfmpegVideoEncoders.PATH_HARDWARE,
            available = listOf(cpuH264), seconds = 30.0
        )
        assertEquals("libx264", choice?.option?.encoder)
        assertTrue(choice!!.reason.contains("não tem encoder H264"))
        // E o caso HEVC: sem hardware HEVC e sem CPU HEVC (não existe libx265 no
        // Android), não há opção viável — o chamador é quem avisa o usuário.
        assertNull(
            FfmpegVideoEncoders.resolve(
                codec = "hevc", path = FfmpegVideoEncoders.PATH_HARDWARE,
                available = listOf(qualcommH264, cpuH264), seconds = 30.0
            )
        )
    }

    @Test
    fun forcadoNoAvancadoERespeitado() {
        val choice = FfmpegVideoEncoders.resolve(
            codec = "h264", path = FfmpegVideoEncoders.PATH_HARDWARE,
            advanced = "c2.android.avc.encoder",
            available = listOf(qualcommH264, genericH264, cpuH264), seconds = 0.4
        )
        assertEquals("c2.android.avc.encoder", choice?.option?.key)
        assertTrue(choice!!.reason.contains("forçado"))
    }

    @Test
    fun forcadoIndisponivelAvisaEVaiParaCpu() {
        val choice = FfmpegVideoEncoders.resolve(
            codec = "h264", path = FfmpegVideoEncoders.PATH_HARDWARE,
            advanced = "c2.outro.avc.encoder", available = listOf(cpuH264), seconds = 30.0
        )
        assertEquals("libx264", choice?.option?.encoder)
        assertTrue(choice!!.reason.contains("não passou na sondagem"))
    }

    @Test
    fun semOpcaoViavelDevolveNull() {
        assertNull(
            FfmpegVideoEncoders.resolve(
                codec = "h264", path = FfmpegVideoEncoders.PATH_CPU, available = emptyList()
            )
        )
    }

    @Test
    fun tagHvc1SoEmMp4ComHevc() {
        assertEquals(listOf("-tag:v", "hvc1"), FfmpegVideoEncoders.hevcTagArguments("hevc_mediacodec", ".mp4"))
        assertEquals(listOf("-tag:v", "hvc1"), FfmpegVideoEncoders.hevcTagArguments("libx265", "mov"))
        assertEquals(emptyList<String>(), FfmpegVideoEncoders.hevcTagArguments("h264_mediacodec", ".mp4"))
        assertEquals(emptyList<String>(), FfmpegVideoEncoders.hevcTagArguments("hevc_mediacodec", ".mkv"))
    }

    @Test
    fun encoderForcadoAcrescentaOCodecName() {
        assertEquals(
            listOf("-codec_name", "c2.qti.avc.encoder"),
            FfmpegVideoEncoders.codecNameArguments(qualcommH264)
        )
        assertEquals(emptyList<String>(), FfmpegVideoEncoders.codecNameArguments(cpuH264))
        assertEquals(emptyList<String>(), FfmpegVideoEncoders.codecNameArguments(null as String?))
    }

    @Test
    fun cpuEquivalenteSoExisteParaOCodecQueTemSoftware() {
        val catalog = listOf(qualcommH264, qualcommHevc, cpuH264)
        assertEquals("libx264", FfmpegVideoEncoders.cpuEquivalent("h264", catalog)?.encoder)
        // Sem libx265 no Android: HEVC não tem equivalente de CPU.
        assertNull(FfmpegVideoEncoders.cpuEquivalent("hevc", catalog))
    }

    @Test
    fun falhaDeHardwareEReconhecidaPelosMarcadores() {
        assertTrue(
            FfmpegVideoEncoders.isHardwareEncoderError(
                "mediacodec: Error creating a MediaCodec encoder: no codec found"
            )
        )
        assertTrue(FfmpegVideoEncoders.isHardwareEncoderError("[h264_nvenc @ 0x1] No capable devices found"))
        assertTrue(FfmpegVideoEncoders.isHardwareEncoderError("Error initializing output stream 0:0 -- Codec init failed"))
        // Erro de arquivo/filtro NÃO é falha de hardware.
        assertFalse(FfmpegVideoEncoders.isHardwareEncoderError("Invalid data found when processing input"))
        assertFalse(FfmpegVideoEncoders.isHardwareEncoderError("No such filter: 'naoexistefiltro'"))
    }
}
