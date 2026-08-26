package br.gov.sp.pcsp.launcher

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class AppVersionTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {
    init {
        text = AppUpdateChecker.APP_VERSION
    }
}
