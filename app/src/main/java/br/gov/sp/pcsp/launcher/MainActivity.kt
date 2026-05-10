
package br.gov.sp.pcsp.launcher
import br.gov.sp.pcsp.launcher.ToolsActivity

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Botão Ferramentas
        findViewById<android.widget.Button>(R.id.button_tools).setOnClickListener {
            startActivity(android.content.Intent(this, ToolsActivity::class.java))
        }

        // Botão Talão Web (URL fixa)
        findViewById<Button>(R.id.btnTalao).setOnClickListener {
            openInChromeTab("https://dipol.policiacivil.sp.gov.br/talaoweb")
        }

        // Botão Infoseg (pega URL do atributo android:tag no XML)
        findViewById<Button>(R.id.button_infoseg).setOnClickListener {
            openInChromeTab("http://infoseg.sinesp.gov.br/infoseg2/")
        }

        // Botão eSAJ (pega URL do atributo android:tag no XML)
        findViewById<Button>(R.id.button_esaj).setOnClickListener {
            openInChromeTab("https://esaj.tjsp.jus.br/cpopg/open.do")
        }

        // Botão BNMP (pega URL do atributo android:tag no XML)
        findViewById<Button>(R.id.button_bnmp).setOnClickListener {
            openInChromeTab("https://portalbnmp.cnj.jus.br/#/pesquisa-peca")
        }

        // Botão BNMP (pega URL do atributo android:tag no XML)
        findViewById<Button>(R.id.button_mind7).setOnClickListener {
            openInChromeTab("https://mind-7.org/painel/")
        }

        // Caso adicione mais botões no futuro:
        // findViewById<Button>(R.id.btnOutro).setOnClickListener { /* ... */ }
    }

    private fun openInChromeTab(url: String) {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        try {
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: ActivityNotFoundException) {
            // Fallback: abre no navegador padrão
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
