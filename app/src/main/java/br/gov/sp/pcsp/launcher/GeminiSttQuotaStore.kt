package br.gov.sp.pcsp.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Registro privado da cota local Gemini por hash da chave; não armazena credenciais ou áudio. */
object GeminiSttQuotaStore {
    private val policies = mutableMapOf<String, GeminiSttRequestPolicy>()

    @Synchronized fun policy(apiKey: String): GeminiSttRequestPolicy {
        val scope = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return policies.getOrPut(scope) {
            val prefs = SigApplication.appInstance.getSharedPreferences("gemini_stt_quota", Context.MODE_PRIVATE)
            val json = JSONObject(prefs.getString(scope, "{}") ?: "{}")
            val headers = json.optJSONObject("headers") ?: JSONObject()
            val starts = json.optJSONArray("starts") ?: JSONArray()
            val state = GeminiSttRequestPolicy.State(
                day = json.optString("day"), used = json.optInt("used"), dailyBlocked = json.optBoolean("dailyBlocked"),
                next = json.optLong("next"), blocked = json.optLong("blocked"), responses = json.optInt("responses"),
                errors = json.optInt("errors"), limited = json.optInt("limited"), observed = json.optLong("observed"),
                status = json.optInt("status"), starts = (0 until starts.length()).map { starts.getLong(it) },
                headers = headers.keys().asSequence().associateWith { headers.getString(it) },
            )
            GeminiSttRequestPolicy(state, save = { s ->
                val record = JSONObject().put("day", s.day).put("used", s.used).put("dailyBlocked", s.dailyBlocked)
                    .put("next", s.next).put("blocked", s.blocked).put("responses", s.responses)
                    .put("errors", s.errors).put("limited", s.limited).put("observed", s.observed)
                    .put("status", s.status).put("starts", JSONArray(s.starts)).put("headers", JSONObject(s.headers))
                check(prefs.edit().putString(scope, record.toString()).commit()) { "Não foi possível persistir a cota Gemini." }
            })
        }
    }
}
