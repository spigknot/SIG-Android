package br.gov.sp.pcsp.launcher

import android.app.Application

class SigApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appInstance = this
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
