package br.gov.sp.pcsp.launcher

import org.json.JSONObject

/** Identificador e nome legível do modelo anunciado pelo servidor de texto local. */
internal object LocalTextModel {
    fun parseId(json: String): String {
        val data = JSONObject(json).optJSONArray("data")
        return (0 until (data?.length() ?: 0)).mapNotNull { data?.optJSONObject(it)?.opt("id") as? String }
            .firstOrNull { it.isNotBlank() }?.trim()
            ?: throw IllegalStateException("O servidor local não anunciou nenhum modelo em /v1/models.")
    }

    fun label(modelId: String): String {
        val filename = modelId.replace('\\', '/').substringAfterLast('/')
        val name = if (filename.endsWith(".gguf", ignoreCase = true)) filename.dropLast(5) else filename
        return if (name.isBlank()) ModelServerStore.LOCAL_TEXT_SERVER_NAME else "servidor ($name)"
    }
}
