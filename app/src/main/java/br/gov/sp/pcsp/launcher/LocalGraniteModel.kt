package br.gov.sp.pcsp.launcher

import org.json.JSONObject

/** Identificador do modelo Granite NAR anunciado pelo endpoint `/health`. */
internal object LocalGraniteModel {
    fun parseId(json: String): String {
        val modelId = JSONObject(json).opt("model") as? String
        return modelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("O servidor Granite NAR não anunciou nenhum modelo em /health.")
    }
}
