
package br.gov.sp.pcsp.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.File

/** Tela inicial: menu de ferramentas e atalhos. */

class MainActivity : AppCompatActivity() {

    private val locationPreferences by lazy { getSharedPreferences("location_share", Context.MODE_PRIVATE) }
    private var waitingForLocationPermission = false

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

        findViewById<View>(R.id.button_share_location).setOnClickListener {
            if (savedWhatsappNumber().isBlank()) {
                showLocationRecipientDialog(sendAfterSaving = true)
            } else {
                shareCurrentLocation()
            }
        }
        findViewById<View>(R.id.button_share_location).setOnLongClickListener {
            showLocationRecipientDialog(sendAfterSaving = false)
            true
        }
        NativeDependencyPrompt.showIfNeeded(this)
        instalarFaixaDependencias()
        AppUpdateChecker.check(this)
    }

    /**
     * Liga a faixa persistente de componentes nativos.
     *
     * A faixa é discreta e NÃO bloqueante: fica visível enquanto faltar o
     * pacote e some sozinha quando ele for instalado. É o caminho manual de
     * retorno que substitui o modal repetido do `onResume`.
     *
     * O `ScrollView` tem `paddingBottom` fixo de 168dp (folga para a barra de
     * ferramentas flutuante). Com a faixa visível no rodapé, o conteúdo
     * precisa rolar mais, então a folga é aumentada em tempo de execução —
     * caso contrário os atalhos de baixo ficariam escondidos atrás dela.
     */
    private fun instalarFaixaDependencias() {
        val faixa = findViewById<View>(R.id.faixa_dependencias) ?: return
        findViewById<View>(R.id.faixa_dependencias_botao)?.setOnClickListener {
            NativeDependencyPrompt.retryNow(this)
        }
        NativeDependencyPrompt.setFaixaListener { pendente ->
            if (isFinishing || isDestroyed) return@setFaixaListener
            faixa.visibility = if (pendente) View.VISIBLE else View.GONE
            // Densidade real: a folga da faixa precisa ser medida, não estimada.
            val altura = faixa.resources.displayMetrics.density
                .times(FAIXE_DEPENDENCIAS_ALTURA_DP).toInt()
            ajustarFolgaInferior(
                if (pendente) FOLGA_INFERIOR_BASE_DP.dp() + altura
                else FOLGA_INFERIOR_BASE_DP.dp()
            )
        }
    }

    /** Aplica a folga inferior no `ScrollView`, sem empilhar chamadas. */
    private fun ajustarFolgaInferior(px: Int) {
        val sc = findViewById<android.view.ViewGroup>(R.id.root)
            ?.getChildAt(1) as? android.widget.ScrollView ?: return
        if (sc.paddingBottom != px) sc.setPadding(sc.paddingLeft, sc.paddingTop, sc.paddingRight, px)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /**
     * Sincroniza o estado dos componentes nativos a cada volta à tela.
     *
     * BUG ORIGINAL (27/09, item 9 da rodada 4): o `showIfNeeded` só rodava uma
     * vez, no `onCreate`. Quem dispensasse o diálogo, fechasse o app no meio do
     * download ou perdesse o processo ficava sem componentes nativos e sem
     * caminho para baixá-los, sem nenhuma mensagem. Num bump de
     * `COMPONENT_VERSION` isso acontece sem ação do usuário.
     *
     * ⚠️ CORREÇÃO DESTA RODADA: a primeira correção reabria o MODAL a cada
     * `onResume`, o que garantia o caminho de volta mas impunha o diálogo
     * repetido — o usuário que tocou "Agora não" voltava a vê-lo a cada ida e
     * volta. Trocado por uma FAIXA persistente, discreta e não bloqueante, com
     * ação de retry. O `onResume` agora só ATUALIZA o estado dela; o modal
     * acontece uma vez, no `onCreate`.
     */
    override fun onResume() {
        super.onResume()
        NativeDependencyPrompt.atualizarEstado(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSION && waitingForLocationPermission) {
            waitingForLocationPermission = false
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                shareCurrentLocation()
            } else {
                Toast.makeText(this, "Permissão de localização não concedida.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLocationRecipientDialog(sendAfterSaving: Boolean) {
        val input = EditText(this).apply {
            hint = "Ex.: 5514981498731"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(savedWhatsappNumber())
            setSelectAllOnFocus(false)
        }
        AlertDialog.Builder(this)
            .setTitle("Número do WhatsApp")
            .setMessage("Informe o número com DDI e DDD, somente dígitos.")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val number = input.text?.toString()?.filter(Char::isDigit).orEmpty()
                if (number.length !in 10..15) {
                    Toast.makeText(this, "Informe um número de WhatsApp válido.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                saveWhatsappNumber(number)
                if (sendAfterSaving) shareCurrentLocation()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun savedWhatsappNumber(): String {
        val fromPreferences = locationPreferences.getString(PREF_WHATSAPP_NUMBER, "").orEmpty()
        if (fromPreferences.isNotBlank()) return fromPreferences
        val fromSharedStorage = runCatching {
            locationNumberFile().takeIf(File::exists)?.readText(Charsets.UTF_8)?.trim().orEmpty()
        }.getOrDefault("").filter(Char::isDigit)
        if (fromSharedStorage.isNotBlank()) {
            locationPreferences.edit().putString(PREF_WHATSAPP_NUMBER, fromSharedStorage).apply()
        }
        return fromSharedStorage
    }

    private fun saveWhatsappNumber(number: String) {
        locationPreferences.edit().putString(PREF_WHATSAPP_NUMBER, number).apply()
        runCatching {
            locationNumberFile().apply {
                parentFile?.mkdirs()
                writeText(number + "\n", Charsets.UTF_8)
            }
        }
    }

    private fun locationNumberFile(): File =
        File(File(Environment.getExternalStorageDirectory(), "SIG/config"), "whatsapp_localizacao.txt")

    private fun shareCurrentLocation() {
        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            waitingForLocationPermission = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
            runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            Toast.makeText(this, "Ative a localização do aparelho para enviar sua posição.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        Toast.makeText(this, "Obtendo localização...", Toast.LENGTH_SHORT).show()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                sendLocationToWhatsapp(location)
            }
        }
        runCatching {
            locationManager.requestLocationUpdates(provider, 0L, 0f, listener)
        }.onFailure {
            val last = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            if (last != null) sendLocationToWhatsapp(last)
            else Toast.makeText(this, "Não consegui obter sua localização.", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendLocationToWhatsapp(location: Location) {
        val number = savedWhatsappNumber()
        if (number.isBlank()) return
        val mapsLink = "https://www.google.com/maps?q=${location.latitude},${location.longitude}"
        val message = "Localização atual: $mapsLink"
        val encoded = URLEncoder.encode(message, StandardCharsets.UTF_8.name())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=$encoded"))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Não encontrei o WhatsApp neste aparelho.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 901
        private const val PREF_WHATSAPP_NUMBER = "whatsapp_number"

        /**
         * Altura estimada da faixa de dependências, em dp, usada só para abrir
         * espaço de rolagem. O valor real é medido pelo layout; se a faixa
         * quebrar texto em telas estreitas, este número subestimará a folga.
         */
        private const val FAIXE_DEPENDENCIAS_ALTURA_DP = 56f

        /** Folga inferior do `ScrollView` sem a faixa (barra flutuante). */
        private const val FOLGA_INFERIOR_BASE_DP = 168
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
