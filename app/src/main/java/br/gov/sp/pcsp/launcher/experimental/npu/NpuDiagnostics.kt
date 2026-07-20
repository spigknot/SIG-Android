package br.gov.sp.pcsp.launcher.experimental.npu

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import br.gov.sp.pcsp.launcher.BuildConfig
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal object NpuDiagnostics {
    fun collect(context: Context): String {
        val packageManager = context.packageManager
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val storage = StatFs((context.getExternalFilesDir("npu_models") ?: context.filesDir).absolutePath)
        val vulkanLevel = packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?.version

        return buildString {
            appendLine("SIG ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Data: ${DateFormat.getDateTimeInstance().format(Date())}")
            appendLine("Feature experimental: ${BuildConfig.ENABLE_NPU_TESTS}")
            appendLine()
            appendLine("Dispositivo e Android")
            appendLine("Fabricante: ${Build.MANUFACTURER}")
            appendLine("Marca: ${Build.BRAND}")
            appendLine("Modelo: ${Build.MODEL}")
            appendLine("DEVICE: ${Build.DEVICE}")
            appendLine("HARDWARE: ${Build.HARDWARE}")
            appendLine("BOARD: ${Build.BOARD}")
            if (Build.VERSION.SDK_INT >= 31) {
                appendLine("SoC fabricante: ${Build.SOC_MANUFACTURER}")
                appendLine("SoC modelo: ${Build.SOC_MODEL}")
            } else {
                appendLine("SoC: API pública indisponível antes do Android 12")
            }
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
            appendLine("GPU/Vulkan")
            appendLine("Vulkan compute: ${yesNo(packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE))}")
            appendLine("Vulkan nível: ${formatVulkanVersion(vulkanLevel)}")
            appendLine()
            appendLine("Recursos")
            appendLine("RAM disponível: ${formatBytes(memory.availMem)}")
            appendLine("RAM total: ${formatBytes(memory.totalMem)}")
            appendLine("Memória baixa: ${yesNo(memory.lowMemory)}")
            appendLine("Espaço livre para pacotes: ${formatBytes(storage.availableBytes)}")
            appendLine("Estado térmico: ${thermalStatus(context)}")
            appendLine()
            append(NpuNativeProbe.diagnosticReport())
        }
    }

    private fun thermalStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "indisponível nesta API"
        val status = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "nenhum"
            PowerManager.THERMAL_STATUS_LIGHT -> "leve"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderado"
            PowerManager.THERMAL_STATUS_SEVERE -> "severo"
            PowerManager.THERMAL_STATUS_CRITICAL -> "crítico"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergência"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "desligamento"
            else -> "desconhecido ($status)"
        }
    }

    private fun formatVulkanVersion(encoded: Int?): String {
        if (encoded == null || encoded == 0) return "não anunciado"
        return "${encoded ushr 22}.${(encoded ushr 12) and 0x3ff}.${encoded and 0xfff}"
    }

    private fun formatBytes(bytes: Long): String {
        var value = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB")
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun yesNo(value: Boolean) = if (value) "sim" else "não"
}
