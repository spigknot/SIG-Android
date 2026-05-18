
package br.gov.sp.pcsp.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.shortcut_tools).setOnClickListener {
            startActivity(Intent(this, ToolsActivity::class.java))
        }

        findViewById<View>(R.id.shortcut_talao).setOnClickListener {
            openInChromeTab("https://dipol.policiacivil.sp.gov.br/talaoweb")
        }

        findViewById<View>(R.id.shortcut_infoseg).setOnClickListener {
            openInChromeTab("http://infoseg.sinesp.gov.br/infoseg2/")
        }

        findViewById<View>(R.id.shortcut_esaj).setOnClickListener {
            openInChromeTab("https://esaj.tjsp.jus.br/cpopg/open.do")
        }

        findViewById<View>(R.id.shortcut_bnmp).setOnClickListener {
            openInChromeTab("https://portalbnmp.cnj.jus.br/#/pesquisa-peca")
        }

        findViewById<View>(R.id.shortcut_mind7).setOnClickListener {
            openInChromeTab("https://mind-7.org/painel/")
        }
    }

    private fun openInChromeTab(url: String) {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        try {
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: ActivityNotFoundException) {
            // Fallback: abre no navegador padrão
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
