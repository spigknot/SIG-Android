package br.gov.sp.pcsp.launcher

object ModelSelectionSummary {
    fun current(): String {
        val transcription = TranscriptionModelStore.selectedConfig()
        val text = ModelServerStore.selectedConfig()
        return "Modelo de transcrição: ${transcription.displayName()}\n" +
            "Modelo de texto:          ${text.displayName()}"
    }

    private fun TranscriptionModelStore.Config.displayName(): String {
        return "$name ($modelName)"
    }

    private fun ModelServerStore.Config.displayName(): String {
        return "$name ($modelName)"
    }
}
