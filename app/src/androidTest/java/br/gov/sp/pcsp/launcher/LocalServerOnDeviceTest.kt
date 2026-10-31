package br.gov.sp.pcsp.launcher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prova ON-DEVICE do contrato do servidor STT local (Nemotron) executando o
 * próprio código do app no aparelho contra o `servidor` real.
 *
 * Cobre o que o teste unitário não alcança: DNS/resolução de `servidor` no
 * device, HTTP em claro, multipart com boundary do runtime e os tempos de
 * resposta reais. Se o `/health` não responder (servidor fora do ar), o teste
 * é PULADO explicitamente — nunca "verde" falso.
 *
 * Rodar no device/emulador com o servidor acessível:
 *   ./gradlew :app:connectedDebugAndroidTest --tests "*LocalServerOnDeviceTest*"
 */
@RunWith(AndroidJUnit4::class)
class LocalServerOnDeviceTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun audioFile(): java.io.File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val out = java.io.File(instrumentation.targetContext.cacheDir, "e2e_probe.wav")
        if (!out.exists()) {
            instrumentation.context.assets.open("probe.wav").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("fixture de áudio ausente no device", out.length() > 1024)
        return out
    }

    private fun serverModel(): String? {
        // Mesma detecção do app; null = servidor inalcançável -> pular.
        return LocalGraniteModel.fetchBlocking(client)?.also(TranscriptionModelStore::updateGraniteModel)
    }

    private fun post(spec: SttRequestSpec, audio: java.io.File): Pair<Int, String> {
        val fileField = requireNotNull(spec.fileField) { "Contrato STT sem campo de arquivo." }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            spec.multipartFields.forEach { field -> addFormDataPart(field.name, field.value) }
            addFormDataPart(fileField, audio.name, audio.asRequestBody("audio/wav".toMediaType()))
        }.build()
        val request = Request.Builder().url(spec.url).apply {
            spec.headers.forEach { header(it.name, it.value) }
        }.post(body).build()
        client.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    @Test
    fun ocorrencia_nemotron_diarizacao_timestamps_e_idioma() {
        val model = serverModel()
        assumeTrue("servidor STT inalcançável do device; pulando prova on-device", model != null)
        assumeTrue(
            "motor ativo não é Nemotron ($model); diarização/tempos exigem o Nemotron",
            TranscriptionModelStore.nemotronActive(),
        )
        val audio = audioFile()

        val language = SttLanguageSettings.nemotronLanguageParam("pt", "")
        val spec = SttRequestBuilders.localServerOccurrence(
            engine = LocalServerEngine.NEMOTRON,
            language = language,
            diarize = true,
            timestamps = true,
        )
        assertEquals("http://servidor:8100/v1/audio/transcriptions", spec.url)
        assertEquals("file", spec.fileField)
        assertTrue(spec.multipartFields.any { it.name == "response_format" && it.value == "verbose_json" })
        assertTrue(spec.multipartFields.any { it.name == "diarization" && it.value == "true" })
        assertTrue(spec.multipartFields.any { it.name == "timestamp_granularities[]" && it.value == "word" })
        assertTrue(spec.multipartFields.any { it.name == "language" && it.value == "pt" })

        val (code, body) = post(spec, audio)
        assertEquals("corpo de erro do servidor: ${body.take(240)}", 200, code)
        val shown = SttResponseParsers.formatLocalServerTranscript(body, diarize = true, timestamps = true)
        assertTrue("sem rótulo de falante: ${shown.take(200)}", shown.contains("Interlocutor 1:"))
        assertTrue("sem faixa de tempo: ${shown.take(200)}", shown.contains("[00:"))
        assertTrue("texto vazio", shown.length > 20)
    }

    @Test
    fun transcricao_nemotron_idioma_pt() {
        val model = serverModel()
        assumeTrue("servidor STT inalcançável do device; pulando prova on-device", model != null)
        assumeTrue(
            "motor ativo não é Nemotron ($model)",
            TranscriptionModelStore.nemotronActive(),
        )
        val audio = audioFile()

        val spec = SttRequestBuilders.localServerTranscription(
            engine = LocalServerEngine.NEMOTRON,
            language = SttLanguageSettings.nemotronLanguageParam("pt", ""),
        )
        assertEquals("http://servidor:8100/transcribe", spec.url)
        assertEquals("files", spec.fileField)

        val (code, body) = post(spec, audio)
        assertEquals("corpo de erro do servidor: ${body.take(240)}", 200, code)
        val text = SttResponseParsers.parseResponseItems(body)
            .joinToString("\n") { it.text.trim() }.trim()
        assertTrue("transcrição vazia", text.length > 20)
    }
}
