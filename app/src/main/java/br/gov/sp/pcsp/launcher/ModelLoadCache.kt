package br.gov.sp.pcsp.launcher

/** R3/B2: cache do modelo carregado com DONO/sessão explícito.
 *
 * O fast-path "já está carregado" só vale quando:
 *  1. a chave (caminho do modelo | backend | flash attention) confere;
 *  2. (modo owned) o contexto nativo AINDA existe e a transferência de posse
 *     para a sessão atual é feita ATOMICAMENTE na fonte de verdade — o
 *     [assumeOwnership] chama o nativo (sob g_mutex) que reconfere a MESMA
 *     chave real (caminho+backend+FA) antes de trocar o dono, SEM recarga. É
 *     isso que permite jobs sucessivos no mesmo modelo ficarem rápidos sem
 *     "cache falso": um release antigo atrasado vira no-op (o dono atual não
 *     bate) e o contexto nunca é repassado como válido sem existir.
 *  3. (modo legado, lib sem tokens) garantia reduzida DOCUMENTADA: só
 *     chave+sessão; não há como conferir existência/posse no nativo — o B0
 *     recarrega o modelo por job (não se finge garantia owned).
 */
class ModelLoadCache(
    /** Dono atual do contexto nativo: 0 = nenhum; -1 = lib sem o getter. */
    private val contextOwner: () -> Long = { -1L },
    /** true quando a lib expõe o contrato owned (tokens de contexto). */
    private val ownedNative: () -> Boolean = { false },
) {

    private var key: String? = null

    /** Sessão que registrou/assumiu o carregamento atual (0 = vazio). */
    @Volatile
    var loadedGeneration: Long = 0L
        private set

    /** Há carregamento registrado? (diagnóstico/teste) */
    fun hasEntry(): Boolean = key != null

    /** Hit do fast-path. [isCurrent] e [assumeOwnership] são consultados NA
     * chamada; em caso de hit a posse do contexto passa a ser de
     * [sessionGeneration]. */
    fun tryReuse(
        sessionGeneration: Long,
        wantedKey: String,
        isCurrent: (Long) -> Boolean,
        assumeOwnership: (Long) -> Boolean,
    ): Boolean {
        if (key == null || key != wantedKey) return false
        if (ownedNative()) {
            if (contextOwner() == 0L) return false
            if (!assumeOwnership(sessionGeneration)) return false
        } else {
            if (loadedGeneration != sessionGeneration && !isCurrent(loadedGeneration)) return false
        }
        loadedGeneration = sessionGeneration
        return true
    }

    /** Registra carga COMPLETA (loadModel nativo assumiu a posse). */
    fun record(sessionGeneration: Long, newKey: String) {
        key = newKey
        loadedGeneration = sessionGeneration
    }

    fun clear() {
        key = null
        loadedGeneration = 0L
    }
}
