package br.gov.sp.pcsp.launcher

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Adendo 16 §2/§4 — baixa o pacote ORT REAL (turbo, ~1442 MB) pelo fluxo do
 * produto ([WhisperOrtModelDownload] + [WhisperOrtPackageInstaller]) quando o
 * pacote ativo não é o do catálogo (ex.: double esparso de bancada).
 *
 * Motivação medida: o `ort-model` do aparelho pode conter arquivos com tamanho
 * certo mas conteúdo inválido (`ORT_INVALID_PROTOBUF` ao abrir o encoder) —
 * inferência real é impossível sem o pacote íntegro. Idempotente: com o pacote
 * real ativo, nada é baixado.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOrtBaixarPacoteRealTest {

    @Test
    fun baixarPacoteRealQuandoAtivoNaoEIntegro() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val root = WhisperOrtPackageFlow.raiz(ctx)
        val antes = WhisperOrtPackageFlow.estado(ctx)
        Log.i("BAIXAR-ORT-16", "estado antes: $antes")
        if (antes is WhisperOrtPackageFlow.Estado.Pronto) {
            // Íntegro só se o encoder abrir de verdade (tamanho certo não basta:
            // double esparso tem tamanho cheio e quebra no protobuf).
            val valido = runCatching {
                if (!NativeDependencyManager.isInstalled(ctx)) {
                    val ins = NativeDependencyManager.install(ctx) {}
                    assertTrue("nativas: ${ins.exceptionOrNull()?.message}", ins.isSuccess)
                }
                val rt = NativeDependencyManager.loadOrtRuntime(ctx) {}
                assertTrue("runtime: ${rt.exceptionOrNull()?.message}", rt.isSuccess)
                val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
                try {
                    val versao = WhisperOrtPackageInstaller.open(root, WhisperOrtModelCatalog.identity())
                    assertTrue("open deveria ser Ready, veio $versao", versao is WhisperOrtPackageInstaller.Outcome.Ready)
                    val arq = File(File(root, "versions/${(versao as WhisperOrtPackageInstaller.Outcome.Ready).validated.versionKey}"), "encoder_model_int8.onnx")
                    env.createSession(arq.absolutePath)
                    true
                } finally {
                    env.close()
                }
            }
            if (valido.getOrDefault(false)) {
                Log.i("BAIXAR-ORT-16", "pacote ativo abre de verdade; nada a baixar")
                return
            }
            // Double esparso de bancada: tamanho cheio, conteúdo zerado fora das
            // janelas do fingerprint. O install confia no diretório da versão
            // existente e não regrava bytes — por isso é preciso remover o
            // diretório esparso antes de baixar/instalar o pacote íntegro.
            // (Bancada controlada usa backend fake; não depende destes bytes.)
            val versao = WhisperOrtPackageInstaller.open(root, WhisperOrtModelCatalog.identity())
            if (versao is WhisperOrtPackageInstaller.Outcome.Ready) {
                val dir = File(root, "versions/${versao.validated.versionKey}")
                Log.i("BAIXAR-ORT-16", "removendo double esparso: ${dir.path}")
                dir.deleteRecursively()
                File(root, "active").delete()
            }
            Log.i("BAIXAR-ORT-16", "pacote ativo NÃO abre (${valido.exceptionOrNull()?.message}); baixando real")
        }
        val staging = File(ctx.filesDir, "ort-staging-real-${System.nanoTime()}")
        try {
            val baixado = WhisperOrtModelDownload.baixar(
                WhisperOrtModelCatalog.active, staging,
                isCancelada = { false },
                onProgresso = { p ->
                    Log.i(
                        "BAIXAR-ORT-16",
                        "progresso ${p.arquivoAtual} ${p.baixadosTotal}/${p.total}",
                    )
                },
            )
            assertTrue("download falhou: $baixado", baixado is WhisperOrtModelDownload.Resultado.Pronto)
            val instalado = WhisperOrtPackageInstaller.install(
                (baixado as WhisperOrtModelDownload.Resultado.Pronto).staging,
                root, WhisperOrtModelCatalog.identity(), WhisperOrtModelCatalog.active,
            )
            assertTrue("install recusou: $instalado", instalado is WhisperOrtPackageInstaller.Outcome.Ready)
            val depois = WhisperOrtPackageFlow.estado(ctx)
            Log.i("BAIXAR-ORT-16", "estado depois: $depois")
            assertTrue("pacote deveria estar Pronto, veio $depois", depois is WhisperOrtPackageFlow.Estado.Pronto)
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }
}
