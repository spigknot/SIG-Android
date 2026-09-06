package br.gov.sp.pcsp.launcher

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Benchmark debug reproduzível do NAR, dirigido por ADB.
 *
 * Emite uma linha JSON por evento com o prefixo [GraniteNarBenchmarkProtocol.PREFIX].
 * O fallback CPU é recusado por padrão para impedir resultados falsamente acelerados.
 */
class GraniteNarSmokeTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val runId = GraniteNarBenchmarkProtocol.safeRunId(intent.getStringExtra(EXTRA_RUN_ID))
            val startedAt = SystemClock.elapsedRealtime()
            var outcome = "failed"
            var completedMeasuredRuns = 0
            try {
                val backendName = intent.getStringExtra(EXTRA_BACKEND) ?: GraniteExecutionBackend.CPU.name
                val backend = GraniteExecutionBackend.valueOf(backendName.uppercase())
                val requireFullAcceleration = intent.getBooleanExtra(EXTRA_REQUIRE_FULL_ACCELERATION, true)
                val warmupRuns = boundedExtra(EXTRA_WARMUP_RUNS, defaultValue = 0, minimum = 0, maximum = 5)
                val measuredRuns = boundedExtra(EXTRA_MEASURED_RUNS, defaultValue = 1, minimum = 1, maximum = 20)
                val loadOnly = intent.getBooleanExtra(EXTRA_LOAD_ONLY, false)
                val includeText = intent.getBooleanExtra(EXTRA_INCLUDE_TEXT, false)
                val audioPath = checkNotNull(intent.getStringExtra(EXTRA_AUDIO_PATH)) {
                    "Extra obrigatório ausente: $EXTRA_AUDIO_PATH"
                }
                val audio = File(audioPath)
                check(audio.isFile) { "Áudio de teste não encontrado: $audioPath" }

                emit(
                    runId,
                    "start",
                    linkedMapOf(
                        "requested_backend" to backend.name,
                        "strict" to requireFullAcceleration,
                        "warmup_runs" to warmupRuns,
                        "measured_runs" to measuredRuns,
                        "load_only" to loadOnly,
                        "audio_bytes" to audio.length(),
                        "app_version" to BuildConfig.VERSION_NAME,
                        "device" to deviceSnapshot(),
                        "memory" to memorySnapshot(),
                    ),
                )

                val loadCollector = GraniteNarBenchmarkProtocol.Collector()
                val loadStartedAt = SystemClock.elapsedRealtime()
                val loaded = GraniteNarEngine.load(
                    context = this,
                    backend = backend,
                    requireFullAcceleration = requireFullAcceleration,
                    onLog = logger(loadCollector),
                    onFallbackPrompt = { false },
                )
                check(loaded) { GraniteNarEngine.lastError() }
                val effectiveBackend = GraniteNarEngine.loadedBackend()
                check(effectiveBackend == backend) {
                    "Backend efetivo inesperado: $effectiveBackend (solicitado: $backend)"
                }
                emit(
                    runId,
                    "load",
                    linkedMapOf(
                        "status" to "passed",
                        "requested_backend" to backend.name,
                        "effective_backend" to effectiveBackend.name,
                        "strict" to requireFullAcceleration,
                        "elapsed_ms" to SystemClock.elapsedRealtime() - loadStartedAt,
                        "session_load_ms" to loadCollector.sessionLoadMs,
                        "memory" to memorySnapshot(),
                        "thermal_status" to thermalStatus(),
                        "battery_temperature_c" to batteryTemperatureC(),
                    ),
                )

                if (!loadOnly) {
                    repeat(warmupRuns) { index ->
                        runInference(
                            runId = runId,
                            kind = "warmup",
                            index = index + 1,
                            backend = backend,
                            audio = audio,
                            includeText = includeText,
                        )
                    }
                    repeat(measuredRuns) { index ->
                        runInference(
                            runId = runId,
                            kind = "measured",
                            index = index + 1,
                            backend = backend,
                            audio = audio,
                            includeText = includeText,
                        )
                        completedMeasuredRuns++
                    }
                }
                outcome = "passed"
            } catch (error: Throwable) {
                emit(
                    runId,
                    "error",
                    linkedMapOf(
                        "error_type" to error.javaClass.name,
                        "message" to (error.message ?: error.toString()).take(MAX_ERROR_CHARS),
                        "engine_error" to GraniteNarEngine.lastError().take(MAX_ERROR_CHARS),
                        "memory" to memorySnapshot(),
                        "thermal_status" to thermalStatus(),
                    ),
                )
                Log.e(GraniteNarBenchmarkProtocol.TAG, "NAR_BENCH_FAILED run_id=$runId", error)
            } finally {
                val releaseStartedAt = SystemClock.elapsedRealtime()
                GraniteNarEngine.release()
                emit(
                    runId,
                    "end",
                    linkedMapOf(
                        "status" to outcome,
                        "measured_runs_completed" to completedMeasuredRuns,
                        "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                        "release_ms" to SystemClock.elapsedRealtime() - releaseStartedAt,
                        "memory" to memorySnapshot(),
                        "thermal_status" to thermalStatus(),
                        "battery_temperature_c" to batteryTemperatureC(),
                    ),
                )
                runOnUiThread { finish() }
            }
        }.start()
    }

    private fun runInference(
        runId: String,
        kind: String,
        index: Int,
        backend: GraniteExecutionBackend,
        audio: File,
        includeText: Boolean,
    ) {
        val collector = GraniteNarBenchmarkProtocol.Collector()
        val memoryBefore = memorySnapshot()
        val startedAt = SystemClock.elapsedRealtime()
        val transcript = GraniteNarEngine.transcribeFile(
            wavFile = audio,
            onProgress = { progress ->
                Log.i(GraniteNarBenchmarkProtocol.TAG, "NAR_PROGRESS run_id=$runId kind=$kind index=$index value=$progress")
            },
            onLog = logger(collector),
        )
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val fields = linkedMapOf<String, Any?>(
            "status" to "passed",
            "kind" to kind,
            "index" to index,
            "effective_backend" to backend.name,
            "elapsed_ms" to elapsed,
            "engine_total_ms" to collector.engineTotalMs,
            "stage_ms" to collector.stageMs,
            "dimensions" to collector.dimensions,
            "transcript_chars" to transcript.length,
            "transcript_sha256" to GraniteNarBenchmarkProtocol.sha256(transcript),
            "memory_before" to memoryBefore,
            "memory_after" to memorySnapshot(),
            "thermal_status" to thermalStatus(),
            "battery_temperature_c" to batteryTemperatureC(),
        )
        if (includeText) fields["transcript"] = transcript.take(MAX_TRANSCRIPT_CHARS)
        emit(runId, "inference", fields)
    }

    private fun logger(collector: GraniteNarBenchmarkProtocol.Collector): (String) -> Unit = { line ->
        collector.accept(line)
        Log.i(GraniteNarBenchmarkProtocol.TAG, "NAR_LOG $line")
    }

    private fun boundedExtra(name: String, defaultValue: Int, minimum: Int, maximum: Int): Int {
        val value = intent.getIntExtra(name, defaultValue)
        check(value in minimum..maximum) { "$name fora do intervalo $minimum..$maximum: $value" }
        return value
    }

    private fun emit(runId: String, event: String, fields: Map<String, Any?>) {
        val payload = linkedMapOf<String, Any?>(
            "protocol" to GraniteNarBenchmarkProtocol.VERSION,
            "run_id" to runId,
            "event" to event,
            "elapsed_realtime_ms" to SystemClock.elapsedRealtime(),
        )
        payload.putAll(fields)
        Log.i(
            GraniteNarBenchmarkProtocol.TAG,
            GraniteNarBenchmarkProtocol.PREFIX + GraniteNarBenchmarkProtocol.json(payload),
        )
    }

    private fun deviceSnapshot(): Map<String, Any?> = linkedMapOf(
        "manufacturer" to Build.MANUFACTURER,
        "brand" to Build.BRAND,
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "hardware" to Build.HARDWARE,
        "soc_manufacturer" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
        "soc_model" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
        "android_sdk" to Build.VERSION.SDK_INT,
        "android_release" to Build.VERSION.RELEASE,
        "supported_abis" to Build.SUPPORTED_ABIS.toList(),
    )

    private fun memorySnapshot(): Map<String, Any?> {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        return linkedMapOf(
            "total_pss_kb" to info.totalPss,
            "total_private_dirty_kb" to info.totalPrivateDirty,
            "native_pss_kb" to info.nativePss,
            "dalvik_pss_kb" to info.dalvikPss,
            "other_pss_kb" to info.otherPss,
            "native_heap_allocated_bytes" to Debug.getNativeHeapAllocatedSize(),
            "java_heap_used_bytes" to runtime.totalMemory() - runtime.freeMemory(),
            "java_heap_committed_bytes" to runtime.totalMemory(),
            "java_heap_max_bytes" to runtime.maxMemory(),
        )
    }

    private fun thermalStatus(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        getSystemService(PowerManager::class.java)?.currentThermalStatus
    } else {
        null
    }

    private fun batteryTemperatureC(): Double? {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra("temperature", Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    }

    private companion object {
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_REQUIRE_FULL_ACCELERATION = "require_full_acceleration"
        const val EXTRA_AUDIO_PATH = "audio_path"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_WARMUP_RUNS = "warmup_runs"
        const val EXTRA_MEASURED_RUNS = "measured_runs"
        const val EXTRA_LOAD_ONLY = "load_only"
        const val EXTRA_INCLUDE_TEXT = "include_text"
        const val MAX_ERROR_CHARS = 2_000
        const val MAX_TRANSCRIPT_CHARS = 2_000
    }
}
