package br.gov.sp.pcsp.launcher.experimental.npu

import android.content.Context
import org.json.JSONObject

internal data class NpuModelDescriptor(
    val id: String,
    val displayName: String,
    val checkpoint: String,
    val variant: String,
    val encoderRuntime: String,
    val decoderRuntime: String,
    val melBins: Int,
    val audioContextFrames: Int,
    val encoderOutputFrames: Int,
    val encoderOutputSize: Int,
    val downloadUrl: String?,
    val packageSize: Long?,
    val packageSha256: String?
)

internal object NpuModelManifest {
    fun load(context: Context): List<NpuModelDescriptor> {
        val json = context.assets.open("npu_model_manifest.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "schema de manifesto não suportado" }
        val models = root.getJSONArray("models")
        return (0 until models.length()).map { index ->
            val item = models.getJSONObject(index)
            NpuModelDescriptor(
                id = item.getString("id"),
                displayName = item.getString("displayName"),
                checkpoint = item.getString("checkpoint"),
                variant = item.getString("variant"),
                encoderRuntime = item.getString("encoderRuntime"),
                decoderRuntime = item.getString("decoderRuntime"),
                melBins = item.getInt("melBins"),
                audioContextFrames = item.getInt("audioContextFrames"),
                encoderOutputFrames = item.getInt("encoderOutputFrames"),
                encoderOutputSize = item.getInt("encoderOutputSize"),
                downloadUrl = item.optString("downloadUrl").takeIf { it.isNotBlank() && it != "null" },
                packageSize = item.optLong("packageSize", -1L).takeIf { it > 0L },
                packageSha256 = item.optString("packageSha256").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            )
        }
    }
}
