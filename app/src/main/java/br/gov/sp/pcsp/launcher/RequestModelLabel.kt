package br.gov.sp.pcsp.launcher

/** Nome curto do modelo efetivamente usado por uma requisição de texto. */
object RequestModelLabel {
    fun from(config: ModelServerStore.Config): String {
        val model = config.parameters.optString("model").ifBlank { config.modelName }
        return when {
            config.isProxy -> "IA-Proxy/${displayModel(model)}"
            config.provider == "servidor" || config.name == ModelServerStore.SERVER_GEMMA_NAME -> "servidor"
            else -> displayModel(config.name.ifBlank { model })
        }
    }

    private fun displayModel(value: String): String {
        val model = value.trim()
        // "grok-latest" é alias, não versão: mantém o nome cru; "grok-4.x..."
        // continua virando "Grok-4.x..." como antes.
        val versioned = model.startsWith("grok-", ignoreCase = true) &&
            model.length > "grok-".length &&
            model["grok-".length].isDigit()
        return if (versioned) {
            "Grok-${model.substringAfter('-')}"
        } else {
            model
        }
    }
}
