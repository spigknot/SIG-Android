package br.gov.sp.pcsp.launcher

object WhisperNative {
    init {
        NativeDependencyManager.loadLibrary(SigApplication.appInstance, "sig_whisper")
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
    external fun filterVad(
        inputWavPath: String,
        outputWavPath: String,
        sileroModelPath: String,
        mode: Int,
        aggressiveness: Int
    ): String
    external fun systemInfo(): String
    external fun buildInfo(): String
    external fun backendInfo(): String
    external fun lastError(): String
    external fun lastLoadLog(): String
    external fun releaseModel()
}
