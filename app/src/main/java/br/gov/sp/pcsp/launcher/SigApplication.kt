package br.gov.sp.pcsp.launcher

import android.app.Application
import android.content.Context

class SigApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // O onnxruntime-android registra um ContentProvider (ORTTelemetry) que roda
        // ANTES do Application.onCreate(). Ele chama System.loadLibrary("onnxruntime")
        // e falha se as libs externas do R2 ainda não estiverem registradas no
        // classloader. Por isso o registro do diretório nativo precisa acontecer aqui,
        // o mais cedo possível no processo.
        appInstance = this
        NativeDependencyManager.activateIfInstalled(this)
    }

    override fun onCreate() {
        super.onCreate()
        Thread {
            AppCacheManager.cleanOlderThanOneDay(this)
            PromptTemplateStore.ensureDefaults()
            NameDatabaseStore.ensureDefault(this)
        }.start()
    }

    companion object {
        lateinit var appInstance: Application
            private set
    }
}
