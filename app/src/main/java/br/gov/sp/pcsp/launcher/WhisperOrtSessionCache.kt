package br.gov.sp.pcsp.launcher

import java.io.File

/**
 * Cache de sessões ORT com posse por LEASE e compute exclusivo
 * (ORT1-M3, adendo5 §2 / adendo6 §2).
 *
 * Três defeitos apontados pela revisão e corrigidos aqui:
 *
 * 1. **Dois donos do mesmo backend**: `acquire` devolvia o backend direto, então
 *    A.encode(estado1) e B.encode(estado2) podiam intercalar no MESMO hidden/KV.
 *    Agora o chamador recebe um [Lease] e precisa de [tryBeginCompute]; o segundo
 *    dono é RECUSADO enquanto houver compute ativo naquela entrada.
 * 2. **Release atrasado**: `release(key)` de A podia reduzir B e permitir fechar
 *    B em uso. Agora a liberação é por TOKEN de lease, idempotente: um release
 *    antigo não afeta uma nova tentativa.
 * 3. **Chave solta**: pedir arquivos B com a Key de A devolvia A. [Key.fromPackage]
 *    deriva a chave do pacote VALIDADO (identidade + versão imutável) e a
 *    [acquire] confere que os arquivos informados pertencem àquela versão.
 *
 * A abertura roda dentro do miss, antes de publicar a entrada: falha de factory
 * ou de `open` não deixa entrada válida e fecha o parcial.
 */
class WhisperOrtSessionCache(
    private val factory: (File, File, File, Int) -> WhisperOrtBackend = { encoder, decoder, past, threads ->
        WhisperOrtSession(encoder, decoder, past, threads)
    },
    private val open: (WhisperOrtBackend) -> Unit = { backend ->
        (backend as? WhisperOrtSession)?.open()
    },
    private val clock: () -> Long = { System.nanoTime() },
    private val maxEntries: Int = Int.MAX_VALUE,
) {

    /** Identidade completa da sessão em cache. */
    data class Key(
        val engine: String,
        val provider: String,
        val runtime: String,
        val model: String,
        val revision: String,
        val configHash: String,
        val threads: Int,
        /** Chave da VERSÃO do pacote validado (imutável). */
        val packageVersion: String = "",
    ) {
        fun asString(): String =
            "$engine/$provider/$runtime/$model/$revision/cfg$configHash/t$threads/pkg$packageVersion"

        companion object {
            /**
             * Deriva a chave do pacote VALIDADO.
             *
             * Existe para não haver key e arquivos montados em separado: a revisão
             * é a `versionKey` imutável e o conjunto de arquivos entra no hash de
             * configuração — trocar o export muda a chave, por construção.
             */
            fun fromPackage(
                pacote: WhisperOrtPackageInstaller.Validated,
                threads: Int = WhisperOrtSession.DEFAULT_THREADS,
                runtime: String = WhisperOrtTranscriptionProducer.DEFAULT_RUNTIME,
                config: WhisperOrtGenerationConfig = WhisperOrtGenerationConfig.frozenExport(),
            ): Key = keyFor(
                identity = pacote.identity,
                packageVersion = pacote.versionKey,
                runtime = runtime,
                config = config,
                threads = threads,
            )
        }
    }

    /**
     * Posse de UMA entrada, com token próprio.
     *
     * O token é o que impede um `release` atrasado de liberar outra tentativa.
     */
    class Lease internal constructor(
        val key: Key,
        val backend: WhisperOrtBackend,
        internal val token: Long,
        /** `true` quando reaproveitou uma sessão já aberta (hit do cache). */
        val hit: Boolean = false,
        /** Tempo gasto na abertura quando houve miss; 0 no hit. */
        val openMillis: Long = 0L,
        /** Geração da entrada: um release de geração antiga não reduz a nova. */
        val entryGeneration: Long = 0L,
    ) {
        /** `false` depois de liberado; uso após liberação é erro do chamador. */
        var released: Boolean = false
            internal set
    }

    /** Contadores observáveis do cache. */
    data class Stats(
        val hits: Int,
        val misses: Int,
        val opens: Int,
        val openRefusals: Int,
        val computeRefusals: Int,
        val staleReleases: Int,
        val lastOpenMillis: Long,
        val live: Int,
        val inUse: Int,
        val computing: Int,
    )

    private class Entry(
        val backend: WhisperOrtBackend,
        val packageVersion: String,
        /** Identidade monotônica da entrada: release antigo não atinge a nova. */
        val generation: Long,
        /** Custo de abertura DESTA entrada (ms); 0 = desconhecido. */
        val openMillis: Long = 0L,
    ) {
        val tokens = HashSet<Long>()
        var computeToken: Long? = null
    }

    private val lock = java.util.concurrent.locks.ReentrantLock(true)
    private val entries = HashMap<Key, Entry>()
    private var nextToken = 1L
    private var nextEntryGeneration = 1L
    private var hits = 0
    private var misses = 0
    private var opens = 0
    private var refusals = 0
    private var computeRefusals = 0
    private var staleReleases = 0

    /** Motivo da última recusa de abertura (a causa não pode desaparecer). */
    @Volatile
    private var ultimaRecusa: String? = null
    private var lastOpenMillis = 0L

    /**
     * Pega um lease para [key], abrindo se necessário.
     *
     * [packageVersion] precisa casar com a versão de onde vêm [encoder],
     * [decoder] e [decoderWithPast]: arquivos de outro pacote com a mesma chave
     * são recusados. Devolve `null` quando a abertura falha.
     */
    fun acquire(
        key: Key,
        packageVersion: String,
        encoder: File,
        decoder: File,
        decoderWithPast: File,
    ): Lease? {
        lock.lock()
        try {
            val existente = entries[key]
            if (existente != null) {
                if (existente.packageVersion != packageVersion) return null
                hits++
                val token = nextToken++
                existente.tokens.add(token)
                // Os fatos vão no lease: quem consome não precisa mais ler
                // contadores globais (`hits`/`lastOpenMillis`) que misturam jobs.
                return Lease(key, existente.backend, token, hit = true, entryGeneration = existente.generation)
            }
            misses++
            if (abrirEntrada(key, packageVersion, encoder, decoder, decoderWithPast) != null) return null
            val entrada = entries[key] ?: return null
            val token = nextToken++
            entrada.tokens.add(token)
            return Lease(
                key, entrada.backend, token,
                hit = false,
                openMillis = entrada.openMillis,
                entryGeneration = entrada.generation,
            )
        } finally {
            lock.unlock()
        }
    }

    /**
     * Abre a sessão e publica a entrada. Roda COM o lock retido.
     *
     * Caminho ÚNICO de abertura (usado por [acquire] e [adquirir]): a causa da
     * falha é registrada e devolvida na mesma operação, sem janela de corrida.
     * Devolve `null` quando abriu, ou a causa quando recusou.
     */
    private fun abrirEntrada(
        chave: Key,
        packageVersion: String,
        encoder: File,
        decoder: File,
        decoderWithPast: File,
    ): String? {
        // Política da rota: uma configuração quente. Nunca expulsa leases nem
        // compute; sem espaço seguro, recusa antes de abrir outro backend.
        while (entries.size >= maxEntries.coerceAtLeast(1)) {
            val idle = entries.entries.firstOrNull {
                it.value.tokens.isEmpty() && it.value.computeToken == null
            } ?: return "cache ocupado: limite de sessoes atingido"
            try {
                idle.value.backend.close()
            } catch (failure: Exception) {
                return "cache: falha ao fechar sessao anterior: ${failure.message}"
            }
            entries.remove(idle.key)
        }
        val inicio = clock()
        // factory PODE lançar: antes isso escapava do acquire e virava exceção
        // fora do try do produtor, sem falha tipada por job.
        val criado = try {
            factory(encoder, decoder, decoderWithPast, chave.threads)
        } catch (failure: Throwable) {
            refusals++
            ultimaRecusa = "factory: " + failure.javaClass.simpleName + ": " + failure.message
            return ultimaRecusa
        }
        val aberto = try {
            open(criado)
            true
        } catch (failure: Throwable) {
            runCatching { criado.close() }
            refusals++
            ultimaRecusa = "open: " + failure.javaClass.simpleName + ": " + failure.message
            false
        }
        if (!aberto) return ultimaRecusa
        val ms = (clock() - inicio) / 1_000_000
        lastOpenMillis = ms
        opens++
        val geracaoEntrada = nextEntryGeneration++
        entries[chave] = Entry(criado, packageVersion, geracaoEntrada, ms)
        return null
    }

    /**
     * Toma o compute EXCLUSIVO da entrada.
     *
     * `false` quando outro lease já está computando — o chamador deve recusar a
     * admissão (ou enfileirar), nunca rodar dois estados sobre o mesmo hidden/KV.
     */
    fun tryBeginCompute(lease: Lease): Boolean {
        lock.lock()
        try {
            if (lease.released) return false
            val entry = entries[lease.key] ?: return false
            if (lease.token !in entry.tokens) return false
            if (entry.computeToken != null) {
                computeRefusals++
                return false
            }
            entry.computeToken = lease.token
            return true
        } finally {
            lock.unlock()
        }
    }

    /** Encerra o compute do lease (idempotente). */
    fun endCompute(lease: Lease) {
        lock.lock()
        try {
            val entry = entries[lease.key] ?: return
            if (entry.computeToken == lease.token) entry.computeToken = null
        } finally {
            lock.unlock()
        }
    }

    /**
     * Resultado ATÔMICO da aquisição: o lease ou a causa DESTA tentativa,
     * capturada na mesma operação protegida.
     *
     * O callback externo sobre o global `lastRefusal` não elimina a corrida:
     * entre o `acquire` e a leitura, outra thread podia trocar o motivo. Aqui a
     * causa viaja no próprio retorno.
     */
    sealed class Aquisicao {
        data class Ok(val lease: Lease) : Aquisicao()
        data class Recusada(val motivo: String) : Aquisicao()
    }

    /**
     * Pega um lease a partir do pacote VALIDADO.
     *
     * É a forma que amarra chave e arquivos à MESMA origem. Antes era possível
     * chamar `acquire(keyDeA, versaoDeB, encoderDeC, ...)` com partes montadas
     * independentemente e o cache aceitava — a chave viajava como string solta.
     * Aqui a chave é DERIVADA do pacote (versionKey + conjunto de arquivos), então
     * chave e bytes não podem divergir.
     */
    fun adquirir(
        pacote: WhisperOrtPackageInstaller.Validated,
        threads: Int = WhisperOrtSession.DEFAULT_THREADS,
        runtime: String = WhisperOrtTranscriptionProducer.DEFAULT_RUNTIME,
        config: WhisperOrtGenerationConfig = WhisperOrtGenerationConfig.frozenExport(),
    ): Aquisicao {
        val chave = Key.fromPackage(pacote = pacote, threads = threads, runtime = runtime, config = config)
        lock.lock()
        try {
            val existente = entries[chave]
            if (existente != null) {
                if (existente.packageVersion != pacote.versionKey) {
                    return Aquisicao.Recusada(
                        "versão do pacote divergente: sessão é ${existente.packageVersion}, pedido ${pacote.versionKey}",
                    )
                }
                hits++
                val token = nextToken++
                existente.tokens.add(token)
                return Aquisicao.Ok(
                    Lease(chave, existente.backend, token, hit = true, entryGeneration = existente.generation),
                )
            }
            misses++
            val causa = abrirEntrada(chave, pacote.versionKey, pacote.encoder, pacote.decoder, pacote.decoderWithPast)
            if (causa != null) return Aquisicao.Recusada(causa)
            val entrada = entries[chave]
                ?: return Aquisicao.Recusada("sessão sumiu após abertura")
            val token = nextToken++
            entrada.tokens.add(token)
            return Aquisicao.Ok(
                Lease(
                    chave, entrada.backend, token,
                    hit = false,
                    openMillis = entrada.openMillis,
                    entryGeneration = entrada.generation,
                ),
            )
        } finally {
            lock.unlock()
        }
    }

    /**
     * Libera a posse deste lease.
     *
     * Idempotente e por TOKEN: um release antigo de A não reduz B. Devolve `true`
     * quando liberou algo; `false` quando era repetido/obsoleto.
     */
    fun release(lease: Lease): Boolean {
        lock.lock()
        try {
            if (lease.released) {
                staleReleases++
                return false
            }
            val entryAtual = entries[lease.key]
            // Release DURANTE compute não pode soltar a posse física: a sessão
            // segue em run. Recusa e mantém o pin até endCompute.
            if (entryAtual != null && entryAtual.computeToken == lease.token) {
                computeRefusals++
                return false
            }
            lease.released = true
            val entry = entries[lease.key] ?: return false
            if (!entry.tokens.remove(lease.token)) {
                staleReleases++
                return false
            }
            if (entry.computeToken == lease.token) entry.computeToken = null
            return true
        } finally {
            lock.unlock()
        }
    }

    /**
     * Descarta a entrada **só** sem posses e sem compute.
     *
     * Devolve `false` quando há uso ativo — fechar em execução derrubaria o job.
     */
    fun invalidate(key: Key): Boolean {
        lock.lock()
        try {
            val entry = entries[key] ?: return false
            if (entry.tokens.isNotEmpty() || entry.computeToken != null) return false
            entries.remove(key)
            runCatching { entry.backend.close() }
            return true
        } finally {
            lock.unlock()
        }
    }

    /** Motivo da última recusa de abertura, ou `null`. */
    fun lastRefusal(): String? = ultimaRecusa

    /** Custo de load da última abertura (ms). */
    fun lastOpenMillis(): Long {
        lock.lock()
        try {
            return lastOpenMillis
        } finally {
            lock.unlock()
        }
    }

    /** Retrato dos contadores para o relatório por job. */
    fun stats(): Stats {
        lock.lock()
        try {
            return Stats(
                hits = hits,
                misses = misses,
                opens = opens,
                openRefusals = refusals,
                computeRefusals = computeRefusals,
                staleReleases = staleReleases,
                lastOpenMillis = lastOpenMillis,
                live = entries.size,
                inUse = entries.values.count { it.tokens.isNotEmpty() },
                computing = entries.values.count { it.computeToken != null },
            )
        } finally {
            lock.unlock()
        }
    }

    /** Fecha tudo; recusa com uso ou compute ativo. */
    fun closeAll(): Boolean {
        lock.lock()
        try {
            if (entries.values.any { it.tokens.isNotEmpty() || it.computeToken != null }) return false
            entries.values.forEach { runCatching { it.backend.close() } }
            entries.clear()
            return true
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /**
         * Chave derivada do PACOTE VALIDADO.
         *
         * Inclui a versão do pacote: trocar o pacote é outra sessão, e a chave
         * deixa de poder ser montada com caminhos de outro export.
         */
        fun keyFor(
            identity: WhisperOrtModelPackage.Identity,
            packageVersion: String,
            runtime: String,
            config: WhisperOrtGenerationConfig,
            threads: Int,
        ): Key = Key(
            engine = "ORT",
            provider = identity.provider,
            runtime = runtime,
            model = identity.model,
            revision = identity.revision,
            configHash = configHash(config),
            threads = threads,
            packageVersion = packageVersion,
        )

        /** Hash curto e estável da configuração de geração. */
        fun configHash(config: WhisperOrtGenerationConfig): String {
            val texto = buildString {
                append(config.maxLength).append('|')
                append(config.eosTokenId).append('|')
                append(config.decoderStartTokenId).append('|')
                append(config.suppressTokens.sorted().joinToString(",")).append('|')
                append(config.beginSuppressTokens.sorted().joinToString(","))
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(texto.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.substring(0, 12)
        }
    }
}
