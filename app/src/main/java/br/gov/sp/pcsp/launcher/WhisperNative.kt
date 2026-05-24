package br.gov.sp.pcsp.launcher

object WhisperNative {
    init {
        System.loadLibrary("sig_whisper")
    }

    interface Callback {
        fun onSegment(text: String, startMs: Long, endMs: Long)
        fun onProgress(progress: Int)
        fun onNativeLog(line: String)
    }

    external fun loadModel(modelPath: String, backendKind: Int, flashAttention: Boolean): Boolean
    external fun transcribe(
        wavPath: String,
        language: String,
        beamSize: Int,
        bestOf: Int,
        wordTimestamps: Boolean,
        vadFilter: Boolean,
        vadModelPath: String,
        callback: Callback
    ): String
    external fun cancelTranscription()
    external fun systemInfo(): String
    external fun buildInfo(): String
    external fun backendInfo(): String
    external fun lastError(): String
    external fun lastLoadLog(): String
    external fun releaseModel()
}
