package br.gov.sp.pcsp.launcher

import android.app.Application

class SigApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread {
            AppCacheManager.cleanOlderThanOneDay(this)
        }.start()
    }
}
