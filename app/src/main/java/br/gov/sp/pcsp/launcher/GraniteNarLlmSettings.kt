package br.gov.sp.pcsp.launcher

import android.content.Context

/**
 * Preferência da variante do LLM do Granite 4.1 NAR (somente prefs).
 *
 * Mesmo padrão do [GraniteParallelismSettings]: o valor vive em `SharedPreferences` e o
 * engine lê na carga. A escolha determina **qual par de arquivos** do LLM é baixado e usado
 * (`GraniteNarLlm.Variante`).
 *
 * A variante padrão é a de maior qualidade — quem nunca escolheu nada não paga o risco de
 * uma troca de qualidade sem saber.
 */
object GraniteNarLlmSettings {
    private const val PREFERENCES_NAME = "granite_nar_llm_settings"
    private const val KEY_VARIANTE = "llm_variante"

    /** Id da variante escolhida; cai no padrão (float) quando ausente ou desconhecida. */
    fun selectedId(context: Context): String {
        val salvo = context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VARIANTE, null)
        return GraniteNarLlm.porId(salvo).id
    }

    /** Variante escolhida (nunca nula). */
    fun selected(context: Context): GraniteNarLlm.Variante =
        GraniteNarLlm.porId(selectedId(context))

    /**
     * Grava a escolha. Devolve true quando **mudou** — o chamador usa isso para decidir se
     * precisa baixar a variante nova e fechar a sessão aberta.
     */
    fun select(context: Context, id: String): Boolean {
        val normalizado = GraniteNarLlm.porId(id).id
        val anterior = selectedId(context)
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VARIANTE, normalizado)
            .apply()
        return anterior != normalizado
    }
}
