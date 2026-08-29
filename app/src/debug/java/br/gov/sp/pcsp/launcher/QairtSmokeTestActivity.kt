package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File

/** Entry point somente debug para aceitação QNN no aparelho, sem depender da UI. */
class QairtSmokeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            try {
                val arch = checkNotNull(QairtDependencyManager.htpArchitecture(this)) {
                    "Arquitetura HTP não detectada"
                }
                Log.i(TAG, "HTP_STUB_OK arch=v$arch")
                QairtDependencyManager.loadQnnNatives(this, "htp", arch)
                Log.i(TAG, "HTP_LIBS_OK arch=v$arch")

                if (intent.getBooleanExtra(EXTRA_LOAD_MODEL, false)) {
                    val loaded = GraniteEngine.load(
                        context = this,
                        backend = GraniteExecutionBackend.NPU_QNN_HTP,
                        onLog = { Log.i(TAG, it) },
                        onFallbackPrompt = { false },
                    )
                    check(loaded) { GraniteEngine.lastError() }
                    check(GraniteEngine.loadedBackend() == GraniteExecutionBackend.NPU_QNN_HTP) {
                        "Sessão criada com backend inesperado: ${GraniteEngine.loadedBackend()}"
                    }
                    Log.i(TAG, "HTP_SESSION_OK")

                    intent.getStringExtra(EXTRA_AUDIO_PATH)?.let { audioPath ->
                        val audio = File(audioPath)
                        check(audio.isFile) { "Áudio de teste não encontrado: $audioPath" }
                        val transcript = GraniteEngine.transcribeFile(audio) { progress ->
                            Log.i(TAG, "HTP_INFERENCE_PROGRESS $progress")
                        }
                        Log.i(TAG, "HTP_INFERENCE_OK chars=${transcript.length} text=$transcript")
                    }
                    GraniteEngine.release()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "HTP_SMOKE_FAILED", error)
            } finally {
                runOnUiThread { finish() }
            }
        }.start()
    }

    private companion object {
        const val TAG = "QairtSmoke"
        const val EXTRA_LOAD_MODEL = "load_model"
        const val EXTRA_AUDIO_PATH = "audio_path"
    }
}
