package br.gov.sp.pcsp.launcher

import java.io.File
import java.io.FileOutputStream

/**
 * Commit atômico de arquivo pequeno, compatível com minSdk 24 (ORT1-B04, adendo8 §6).
 *
 * Por que existe: o `moveFile` anterior apagava o destino ANTES do rename e, se o
 * processo morresse no meio, `active`/marcador podiam simplesmente não existir —
 * o pacote ficava instalado mas não selecionável. `restore` em `catch` só cobre o
 * processo vivo; não cobre interrupção.
 *
 * Contrato (o mesmo do `android.util.AtomicFile`, mas em `java.io` para poder ser
 * testado no HOST):
 * - a gravação vai para `<alvo>.tmp` e é sincronizada em disco (`fd.sync`);
 * - o anterior é preservado em `<alvo>.bak` antes da troca;
 * - `<alvo>.bak` é promovido de volta se a troca falhar;
 * - [recover] conserta o estado depois de uma interrupção: alvo ausente + tmp
 *   completo vale mais que nada, e tmp abandonado com alvo válido é descartado;
 * - [read] tenta o alvo e, se ele faltar, promove o tmp recuperável.
 *
 * Não presume lock implícito: quem chama serializa (o instalador já o faz).
 */
class WhisperOrtAtomicFileStore {

    /** Sufixo do temporário de gravação. */
    private val tmpSuffix = ".tmp"

    /** Sufixo do anterior preservado. */
    private val bakSuffix = ".bak"

    private fun tmp(target: File) = File(target.parentFile, target.name + tmpSuffix)
    private fun bak(target: File) = File(target.parentFile, target.name + bakSuffix)

    /**
     * Grava [content] em [target] de forma atômica.
     *
     * Devolve `false` quando qualquer etapa falha; nesse caso o conteúdo ANTERIOR
     * é restaurado (se existia) e o chamador reporta recusa tipada.
     */
    fun write(target: File, content: ByteArray): Boolean {
        target.parentFile?.mkdirs()
        val temporario = tmp(target)
        val anterior = bak(target)
        val tinhaAnterior = target.isFile
        try {
            FileOutputStream(temporario).use { out ->
                out.write(content)
                out.flush()
                out.fd.sync()
            }
            if (tinhaAnterior) {
                if (anterior.exists()) anterior.delete()
                if (!target.renameTo(anterior)) {
                    temporario.delete()
                    return false
                }
            }
            if (!temporario.renameTo(target)) {
                // Troca falhou: devolve o anterior ao lugar.
                if (tinhaAnterior && anterior.isFile()) anterior.renameTo(target)
                temporario.delete()
                return false
            }
            // Confere o conteúdo efetivo, não só a existência.
            if (!target.isFile || !target.readBytes().contentEquals(content)) {
                if (tinhaAnterior && anterior.isFile()) {
                    target.delete()
                    anterior.renameTo(target)
                }
                return false
            }
            if (anterior.exists()) anterior.delete()
            return true
        } catch (failure: Throwable) {
            runCatching { temporario.delete() }
            if (tinhaAnterior && anterior.isFile() && !target.isFile) {
                runCatching { anterior.renameTo(target) }
            }
            return false
        }
    }

    /**
     * Lê [target], promovendo o temporário se o alvo tiver desaparecido.
     *
     * Devolve `null` quando não há conteúdo recuperável.
     */
    fun read(target: File, validator: (ByteArray) -> Boolean = { true }): ByteArray? {
        if (target.isFile) {
            val bytes = runCatching { target.readBytes() }.getOrNull()
            if (bytes != null && validator(bytes)) return bytes
        }
        // Alvo ausente/inválido: tenta o temporário completo de uma gravação
        // interrompida e, se ele valida, promove.
        val temporario = tmp(target)
        if (temporario.isFile) {
            val bytes = runCatching { temporario.readBytes() }.getOrNull()
            if (bytes != null && validator(bytes)) {
                if (target.exists()) target.delete()
                if (temporario.renameTo(target)) return bytes
            }
        }
        // Último recurso: o anterior preservado.
        val anterior = bak(target)
        if (anterior.isFile) {
            val bytes = runCatching { anterior.readBytes() }.getOrNull()
            if (bytes != null && validator(bytes)) {
                if (!target.exists()) anterior.renameTo(target)
                return bytes
            }
        }
        return null
    }

    /**
     * Limpa sobras de uma gravação interrompida.
     *
     * Se o alvo existe e valida, o tmp é lixo; se o alvo não existe, [read] já
     * promoveu o tmp antes. Devolve `true` quando o estado final é consistente.
     */
    fun recover(target: File, validator: (ByteArray) -> Boolean = { true }): Boolean {
        val anterior = bak(target)
        val alvoValido = target.isFile && runCatching { validator(target.readBytes()) }.getOrDefault(false)
        val bakValido = anterior.isFile && runCatching { validator(anterior.readBytes()) }.getOrDefault(false)
        val temporario = tmp(target)

        if (alvoValido) {
            // Estado confirmado: tmp e bak são sobras e podem sair.
            if (temporario.exists()) temporario.delete()
            if (anterior.exists()) anterior.delete()
            return true
        }
        if (bakValido) {
            // O alvo está inválido/ausente, mas existe um estado confirmado no bak.
            // PROMOVE o bak — antes o código apagava esse backup e deixava o alvo
            // inválido, perdendo a única cópia boa.
            if (target.exists() && !target.delete()) return false
            if (!anterior.renameTo(target)) return false
            if (temporario.exists()) temporario.delete()
            return runCatching { validator(target.readBytes()) }.getOrDefault(false)
        }
        // Sem estado confirmado: ainda pode haver um tmp completo a promover.
        val lido = read(target, validator)
        if (lido != null) {
            if (temporario.exists()) temporario.delete()
            return true
        }
        return false
    }
}
