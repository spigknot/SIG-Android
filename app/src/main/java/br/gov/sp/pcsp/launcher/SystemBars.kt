package br.gov.sp.pcsp.launcher

import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

fun AppCompatActivity.keepContentInsideSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, true)
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK

    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = true
        window.isNavigationBarContrastEnforced = true
    }
}
