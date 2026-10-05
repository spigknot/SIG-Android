package br.gov.sp.pcsp.launcher

import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream

/**
 * Suporte compartilhado dos testes de pacotes nativos (contrato e official).
 *
 * A lógica de leitura/conferência de ZIP fica AQUI, única, para os dois
 * testes exercitarem o mesmo código: o de contrato (fixture minimal
 * `contract-min.zip`, roda em checkout limpo) e o official (ZIPs reais v10,
 * rodado na release — pulado quando ausentes).
 */
object NativeDepsTestSupport {

    /** Entrada de ZIP: nome completo e tamanho DESCOMPRIMIDO (bytes de payload). */
    data class Entrada(val nome: String, val tamanho: Long)

    /** Lê o ZIP de verdade: nome e tamanho descomprimido de cada entrada. */
    fun lerZip(bytes: ByteArray): List<Entrada> {
        val out = ArrayList<Entrada>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) { zis.closeEntry(); continue }
                zis.readBytes()          // consome o conteúdo, sem guardá-lo
                out.add(Entrada(e.name, e.size))
                zis.closeEntry()
            }
        }
        return out
    }

    /**
     * Conferência arquivo-a-arquivo (a MESMA regra do teste official):
     * devolve a lista de divergências entre o ZIP e a tabela esperada.
     * Lista vazia = OK.
     *
     * A tabela esperada usa o NOME CURTO (substringAfterLast('/')).
     */
    fun conferir(entradas: List<Entrada>, esperado: Map<String, Long>): List<String> {
        val out = ArrayList<String>()
        val porNome = entradas.associateBy { it.nome.substringAfterLast('/') }
        for ((nome, tamanho) in esperado) {
            val real = porNome[nome]
            when {
                real == null -> out.add("$nome: declarado na tabela mas AUSENTE no ZIP")
                real.tamanho != tamanho -> out.add(
                    "$nome: tamanho diverge (tabela $tamanho, ZIP ${real.tamanho})",
                )
            }
        }
        for (e in entradas) {
            val nome = e.nome.substringAfterLast('/')
            if (!esperado.containsKey(nome)) {
                out.add("$nome: existe no ZIP mas NAO foi declarado")
            }
        }
        return out
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Reempacota o ZIP escrevendo UM BYTE A MAIS na n-ésima entrada não-diretório.
     * O ZipOutputStream RECALCULA o tamanho a partir dos bytes escritos (adulterar
     * ZipEntry.size seria ignorado) — a adulteração real é o byte extra.
     * Devolve o ZIP adulterado; exige que haja ao menos 1 entrada não-diretório.
     */
    fun adulterarUmByte(bytes: ByteArray, indice: Int): ByteArray {
        val alvo = ArrayList<Int>()
        var i = 0
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory) alvo.add(i)
                zis.closeEntry()
                i++
            }
        }
        require(alvo.isNotEmpty()) { "ZIP sem entradas não-diretório" }

        val adulterado = ByteArrayOutputStream()
        var alterado = 0
        ZipInputStream(bytes.inputStream()).use { zis ->
            ZipOutputStream(adulterado).use { zos ->
                var idx = 0
                while (true) {
                    val e = zis.nextEntry ?: break
                    val dados = zis.readBytes()
                    zis.closeEntry()
                    if (!e.isDirectory) {
                        val e2 = ZipEntry(e.name)
                        zos.putNextEntry(e2)
                        zos.write(dados)
                        if (idx == indice) { zos.write(0); alterado++ }
                        zos.closeEntry()
                    }
                    idx++
                }
            }
        }
        check(alterado == 1) { "esperava exatamente 1 entrada adulterada, veio $alterado" }
        return adulterado.toByteArray()
    }

    /** Carrega uma fixture do classpath em `native-deps/`; null se ausente. */
    fun fixtureDoClasspath(nome: String): ByteArray? =
        NativeDepsTestSupport::class.java.classLoader
            ?.getResourceAsStream("native-deps/$nome")
            ?.readBytes()
}
