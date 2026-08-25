package br.gov.sp.pcsp.launcher

object ModelSelectionSummary {
    fun current(): String {
        val transcription = TranscriptionModelStore.selectedConfig()
        val history = ModelServerStore.selectedConfig(TextModelPurpose.HISTORY)
        val statement = ModelServerStore.selectedConfig(TextModelPurpose.STATEMENT)
        return "Modelo de transcrição: ${transcription.displayName()}\n" +
            "Modelo de histórico:      ${history.displayName()}\n" +
            "Modelo de oitiva:         ${statement.displayName()}"
    }

    private fun TranscriptionModelStore.Config.displayName(): String {
        return "$name ($modelName)"
    }

    private fun ModelServerStore.Config.displayName(): String {
        return "$name ($modelName)"
    }
}
