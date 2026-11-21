package br.gov.sp.pcsp.launcher

import android.content.Context
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * R115/T04.2 — ATUALIZAÇÃO v2 → v3 pelo PRÓPRIO APP, no build de produto.
 *
 * O que o ensaio prova, na tela real:
 * 1. com o v2 instalado, a oferta aparece UMA vez: "Atualizar aceleração NPU (~990 MB)";
 * 2. o download só começa no toque em "Baixar";
 * 3. a verificação completa acontece (o pacote fica VERIFICADO antes de a rota mudar);
 * 4. a rota passa a ser a HTP de ponta a ponta (manifesto v3, `pontaAPonta`);
 * 5. o v2 é removido só depois disso, e os arquivos do v2 somem de uma vez;
 * 6. reabrir a tela NÃO oferece de novo e a entrada fixa diz "NPU ativa".
 *
 * A listagem via `run-as` (antes/depois) é feita no host, em volta desta célula.
 *
 * Args: `r115_saida` (jsonl).
 */
@RunWith(AndroidJUnit4::class)
class WhisperNpuR115AtualizacaoTest {

    private companion object {
        const val TAG = "R115-UPDATE"
        const val SCHEMA = "sig-whisper-npu-r115-atualizacao/v1"
    }

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dispositivo: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val args
        get() = InstrumentationRegistry.getArguments()

    private val saida: File
        get() = File(args.getString("r115_saida") ?: File(ctx.filesDir, "r115/atualizacao.jsonl").absolutePath)

    private fun linha(json: JSONObject) {
        saida.parentFile?.mkdirs()
        saida.appendText(json.toString() + "\n")
    }

    /** Preferências do motor: liga o ORT no ensaio e RESTAURA no fim. */
    private var ortAntes: Boolean = true
    private var threadsAntes: String = "4"

    @Before
    fun ligarOrtParaOEnsaio() {
        val prefs = ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE)
        ortAntes = WhisperOrtRoute.isEnabled(ctx)
        threadsAntes = prefs.getInt("whisper_threads", 4).toString()
        WhisperOrtRoute.setEnabled(ctx, true)
        linha(
            JSONObject().put("schema", SCHEMA).put("tipo", "prefs")
                .put("ort_antes", ortAntes).put("threads", threadsAntes).put("ort_durante", true),
        )
        Log.i(TAG, "prefs ort_antes=$ortAntes threads=$threadsAntes ort_durante=true")
    }

    @After
    fun restaurarPreferencias() {
        WhisperOrtRoute.setEnabled(ctx, ortAntes)
        val prefs = ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE)
        prefs.edit().putInt("whisper_threads", threadsAntes.toInt()).apply()
        linha(
            JSONObject().put("schema", SCHEMA).put("tipo", "prefs")
                .put("ort_restaurado", ortAntes).put("threads", threadsAntes),
        )
    }

    private fun versaoInstalada(): String {
        val estado = runCatching { WhisperOrtNpuPacote.estado(ctx) }.getOrNull()
        return (estado as? WhisperOrtNpuPacote.Estado.Pronto)?.manifesto?.versao ?: "nenhum"
    }

    private fun v2Dir(): File = File(WhisperOrtNpuPacote.raiz(ctx), WhisperOrtNpuPacote.VERSAO_ANTERIOR)

    private fun v3Dir(): File = File(WhisperOrtNpuPacote.raiz(ctx), WhisperOrtNpuPacote.VERSAO)

    private fun listar(dir: File): List<String> =
        dir.listFiles().orEmpty().sortedBy { it.name }.map { "${it.name}:${it.length()}" }

    /** Pós-condições do ensaio: v3 verificado, v2 fora, sem reoferta, rota HTP. */
    private fun conferirPosCondicoes(tipo: String) {
        WhisperOrtHtpRota.iniciar(ctx)
        val manifesto = (runCatching { WhisperOrtNpuPacote.estado(ctx) }.getOrNull()
            as? WhisperOrtNpuPacote.Estado.Pronto)?.manifesto
        val v3Lista = listar(v3Dir())
        val rota = WhisperOrtHtpRota.rotaDoPedido()
        linha(
            JSONObject().put("schema", SCHEMA).put("tipo", tipo)
                .put("versao", manifesto?.versao ?: "nenhum")
                .put("ponta_a_ponta", manifesto?.pontaAPonta ?: false)
                .put("contrato_ok", WhisperOrtNpuPacote.contratoConfere(manifesto?.contrato))
                .put("v2_existe", v2Dir().exists())
                .put("v3_arquivos", v3Lista.size)
                .put("v3_bytes", v3Dir().listFiles().orEmpty().sumOf { it.length() })
                .put("rota", rota)
                .put("motivo", WhisperOrtHtpRota.motivoRota()),
        )
        Log.i(TAG, "$tipo versao=${manifesto?.versao} v2=${v2Dir().exists()} rota=$rota motivo=${WhisperOrtHtpRota.motivoRota()}")
        assertEquals(WhisperOrtNpuPacote.VERSAO, manifesto?.versao)
        assertTrue("manifesto v3 de ponta a ponta", manifesto?.pontaAPonta == true)
        assertTrue("contrato v3 confere", WhisperOrtNpuPacote.contratoConfere(manifesto?.contrato))
        assertFalse("v2 removido", v2Dir().exists())
        assertTrue("v3 completo", v3Lista.size >= 7)
        assertEquals(WhisperOrtHtpAdmissao.ROTA_HTP, rota)
        // Reabrir a tela não oferece de novo e a entrada diz "NPU ativa".
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            selecionarNpuParaAceite(dispositivo, cenario)
            repeat(8) {
                assertTrue(
                    "a oferta não pode reaparecer com o v3 verificado",
                    !dispositivo.hasObject(By.textContains("Atualizar aceleração NPU (~990 MB)")),
                )
                Thread.sleep(500L)
            }
            var rotulo: String? = null
            cenario.onActivity { atividade ->
                rotulo = atividade.findViewById<android.widget.TextView>(R.id.button_backend)
                    ?.text?.toString()
            }
            assertEquals("NPU", rotulo)
        }
    }

    @Test
    fun atualizacao_pelo_app() {
        assumeTrue(
            "a atualização exige o SoC admitido",
            WhisperOrtHtpAdmissao.socDe(android.os.Build.SOC_MODEL)?.habilitado == true,
        )
        assumeTrue("a atualização exige o QAIRT instalado", QairtDependencyManager.isInstalled(ctx))
        val antes = versaoInstalada()
        val listaV2Antes = listar(v2Dir())
        linha(
            JSONObject().put("schema", SCHEMA).put("tipo", "pre").put("versao_antes", antes)
                .put("v2_arquivos", listaV2Antes.size).put("v2_lista", listaV2Antes.joinToString(","))
                .put("v2_bytes", v2Dir().listFiles().orEmpty().sumOf { it.length() })
                .put("pid", android.os.Process.myPid()),
        )
        Log.i(TAG, "antes versao=$antes v2_arquivos=${listaV2Antes.size} pid=${android.os.Process.myPid()}")
        if (antes == WhisperOrtNpuPacote.VERSAO) {
            conferirPosCondicoes("ja_atualizado")
            return
        }
        assumeTrue("requer o v2 instalado para atualizar", antes == WhisperOrtNpuPacote.VERSAO_ANTERIOR)

        val inicio = System.currentTimeMillis()
        ActivityScenario.launch(WhisperActivity::class.java).use { cenario ->
            selecionarNpuParaAceite(dispositivo, cenario)
            // 1) a escolha de NPU oferece atualização para o v3.
            val oferta = dispositivo.wait(
                Until.findObject(By.textContains("Atualizar aceleração NPU (~990 MB)")), 20_000L,
            )
            assertNotNull("o diálogo de atualização deve aparecer", oferta)
            linha(
                JSONObject().put("schema", SCHEMA).put("tipo", "oferta")
                    .put("titulo", oferta!!.text).put("ms", System.currentTimeMillis() - inicio),
            )
            // O tema do AlertDialog escreve o botão em CAIXA ALTA ("BAIXAR").
            val baixar = dispositivo.wait(Until.findObject(By.textContains("AIXAR")), 10_000L)
            assertNotNull("botão Baixar ausente", baixar)
            // 2) o download só começa AQUI.
            val tocado = System.currentTimeMillis()
            baixar!!.click()

            // 3) espera o pacote VERIFICADO (a verificação completa roda no instalador).
            val limite = tocado + 30 * 60_000L
            var versao = versaoInstalada()
            var v2Durante = listar(v2Dir())
            var erroNaTela = ""
            while (System.currentTimeMillis() < limite) {
                versao = versaoInstalada()
                v2Durante = listar(v2Dir())
                if (versao == WhisperOrtNpuPacote.VERSAO) break
                // Falha do instalador abre um AlertDialog "Aceleração NPU": registra
                // o texto e para, em vez de esperar 30 min por um erro silencioso.
                val erro = dispositivo.findObject(By.textContains("Falha ao instalar"))
                if (erro != null) {
                    erroNaTela = erro.text
                    break
                }
                Thread.sleep(2_000L)
            }
            val fim = System.currentTimeMillis()
            val v2Depois = listar(v2Dir())
            val v3Lista = listar(v3Dir())
            val manifesto = (runCatching { WhisperOrtNpuPacote.estado(ctx) }.getOrNull()
                as? WhisperOrtNpuPacote.Estado.Pronto)?.manifesto
            linha(
                JSONObject().put("schema", SCHEMA).put("tipo", "download")
                    .put("versao_depois", versao)
                    .put("ms", fim - tocado)
                    .put("v2_depois_arquivos", v2Depois.size)
                    .put("v2_depois_existe", v2Dir().exists())
                    .put("v3_arquivos", v3Lista.size)
                    .put("v3_lista", v3Lista.joinToString(","))
                    .put("v3_bytes", v3Dir().listFiles().orEmpty().sumOf { it.length() })
                    .put("v2_bytes_durante", v2Durante.size)
                    .put("ponta_a_ponta", manifesto?.pontaAPonta ?: false)
                    .put("contrato_ok", WhisperOrtNpuPacote.contratoConfere(manifesto?.contrato))
                    .put("erro_na_tela", erroNaTela),
            )
            Log.i(
                TAG,
                "depois versao=$versao ms=${fim - tocado} v2_existe=${v2Dir().exists()} " +
                    "v3_arquivos=${v3Lista.size} ponta=${manifesto?.pontaAPonta} pid=${android.os.Process.myPid()}",
            )

            // 4) o v3 está verificado e a rota é a de ponta a ponta.
            assertEquals("o pacote tem de ficar na versão 3", WhisperOrtNpuPacote.VERSAO, versao)
            assertTrue("o manifesto v3 tem de ser de ponta a ponta", manifesto?.pontaAPonta == true)
            assertTrue("o contrato do v3 tem de conferir", WhisperOrtNpuPacote.contratoConfere(manifesto?.contrato))
            // 5) o v2 saiu de uma vez, sem pacote meio apagado.
            assertFalse("o diretório do v2 tem de desaparecer", v2Dir().exists())
            assertTrue("o v3 tem os seis arquivos", v3Lista.size >= 7) // 6 arquivos + .installed + manifest
            assertTrue("nenhum túmulo de remoção pode sobrar",
                WhisperOrtNpuPacote.raiz(ctx).listFiles().orEmpty().none { it.name.startsWith(".remover-") })

            // 6) a entrada fixa passa a dizer "NPU ativa".
            var rotulo: String? = null
            cenario.onActivity { atividade ->
                rotulo = atividade.findViewById<android.widget.TextView>(R.id.button_backend)
                    ?.text?.toString()
            }
            linha(JSONObject().put("schema", SCHEMA).put("tipo", "entrada").put("rotulo", rotulo ?: ""))
            assertEquals("NPU", rotulo)
        }

        // 7) pós-condições (reabrir não oferece de novo; entrada "NPU ativa"; rota HTP).
        conferirPosCondicoes("pos_atualizacao")
    }
}
