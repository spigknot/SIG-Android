package br.gov.sp.pcsp.launcher

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.autofill.AutofillManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        private const val TAG = "PCSP-Launcher"
    }

    private lateinit var webView: WebView

    // Bridge chamada pelo JavaScript quando um input ganha foco
    inner class AndroidAutofillBridge {
        @JavascriptInterface
        fun onInputFocus() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val afm = getSystemService(AutofillManager::class.java)
                    afm?.requestAutofill(webView)
                    Log.d(TAG, "Autofill solicitado via JS focus")
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao solicitar autofill (JS): ${e.message}")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepContentInsideSystemBars()
        setContentView(R.layout.activity_webview)

        val startUrl = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
        webView = findViewById(R.id.webview)

        WebView.setWebContentsDebuggingEnabled(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }

        // Permite injetar e receber mensagens do JS
        webView.addJavascriptInterface(AndroidAutofillBridge(), "AndroidAutofill")

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = settings.userAgentString + " PCSPLauncher/1.0"

            webChromeClient = object : WebChromeClient() {}

            webViewClient = object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean = false

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "Carregando: $url")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)

                    // 1) Solicita o painel de Autofill quando a página termina
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            val afm = getSystemService(AutofillManager::class.java)
                            afm?.requestAutofill(view)
                        } catch (e: Exception) {
                            Log.w(TAG, "Falha ao solicitar autofill (onPageFinished): ${e.message}")
                        }
                    }

                    // 2) Ativa autocomplete e adiciona listener de foco em inputs
                    view.evaluateJavascript(
                        """
                        (function(){
                          try {
                            var inputs = document.querySelectorAll('input');
                            inputs.forEach(function(i){
                              var type = (i.type||'').toLowerCase();
                              var name = ((i.name||'') + ' ' + (i.id||'')).toLowerCase();

                              if (type === 'password') {
                                i.autocomplete = 'current-password';
                              } else if (/user|usuario|login|email|cpf/.test(name)) {
                                i.autocomplete = 'username';
                              } else if (!i.autocomplete || i.autocomplete.toLowerCase() === 'off') {
                                i.autocomplete = 'on';
                              }

                              i.addEventListener('focus', function(){
                                try {
                                  if (window.AndroidAutofill && AndroidAutofill.onInputFocus) {
                                    AndroidAutofill.onInputFocus();
                                  }
                                } catch(e) {}
                              }, {passive:true});
                            });
                          } catch(e){}
                        })();
                        """.trimIndent(),
                        null
                    )
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        val desc = error.description
                        Log.e(TAG, "WebView error: $desc (${error.errorCode})")
                        view.loadData(
                            """
                            <html><body style='font-family:sans-serif;padding:16px'>
                            <h3>Falha ao carregar</h3>
                            <p>Tente novamente mais tarde.</p>
                            <small>${desc}</small>
                            </body></html>
                            """.trimIndent(),
                            "text/html",
                            "UTF-8"
                        )
                    }
                }

                override fun onReceivedSslError(
                    view: WebView, handler: SslErrorHandler, error: SslError
                ) {
                    Log.e(TAG, "SSL error: $error")
                    handler.cancel()
                }
            }
        }

        // Carrega a URL de início
        webView.loadUrl(startUrl)

        onBackPressedDispatcher.addCallback(this) {
            if (this@WebViewActivity::webView.isInitialized && webView.canGoBack()) {
                webView.goBack()
            } else {
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    // (Opcional) abrir a mesma URL no navegador externo
    private fun openInChrome(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível abrir no navegador: ${e.message}")
        }
    }
}
