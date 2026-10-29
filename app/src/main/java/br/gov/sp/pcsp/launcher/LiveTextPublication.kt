package br.gov.sp.pcsp.launcher

/**
 * R8/D (INTEGRIDADE) — POLÍTICA de publicação do texto do committer na UI.
 *
 * Defeito R8-D: o texto RESOLVIDO por resolução ordenada/re-decode restrito
 * (reparo) ou DRENADO no terminal existia no [LiveTextCommitter] (contadores
 * committed_words/display batiam) mas NUNCA era levado ao texto final visível:
 * w01 (palavra única retida+reparada) terminava INVISÍVEL no widget;
 * f05a/f03 perdiam a cauda promovida no EOF; d01-inject não exibia a frase
 * re-decodificada (só contadores). A promoção até o texto final é feita aqui.
 *
 * Fontes (mesmo rótulo sai no evento `clock_ui_publish`):
 * - [SOURCE_COMMIT]: fim de janela — publica o display oficial como sempre (R7);
 * - [SOURCE_REPAIR]: passe de reparo — publica SÓ quando o visível mudou;
 * - [SOURCE_TERMINAL]: terminal NORMAL (stop/EOF) — publica SEMPRE o display
 *   final (promoção do que resolveu depois da última janela); cancel não
 *   publica texto novo;
 * - [SOURCE_CANCEL_DROP]: cancel derruba o overlay incremental SEMPRE (mesmo
 *   com display vazio — o comprometido é a única verdade que permanece).
 *
 * Objeto PURO (sem Android): usado pela WhisperActivity, pelos testes JVM e
 * pelo reproducer standalone da fase D (RED→GREEN do defeito de integridade).
 */
object LiveTextPublication {

    const val SOURCE_COMMIT = "commit"
    const val SOURCE_REPAIR = "repair"
    const val SOURCE_TERMINAL = "terminal_final"
    const val SOURCE_CANCEL_DROP = "cancel_drop_incremental"

    /**
     * Texto a publicar na UI agora; `null` = não mexer (nada novo a mostrar).
     *
     * @param lastPublished último texto publicado pelo caminho ao vivo (commit/
     *   repair/terminal/incremental), base do "só quando mudou".
     * @param terminalNormal true = terminal NORMAL (stop/EOF) — publica o
     *   display final mesmo que igual ao já exibido (fecha a promoção).
     */
    fun textToPublish(
        source: String,
        display: String,
        lastPublished: String?,
        terminalNormal: Boolean = false,
    ): String? {
        val d = display.trim()
        return when (source) {
            // Cancel: o overlay incremental NUNCA sobrevive — mesmo que o
            // display fique vazio, o widget reflete o comprometido.
            SOURCE_CANCEL_DROP -> d
            SOURCE_TERMINAL -> if (terminalNormal) d.ifEmpty { null } else null
            SOURCE_REPAIR -> if (d.isEmpty() || d == lastPublished) null else d
            else -> d.ifEmpty { null }
        }
    }
}
