package br.gov.sp.pcsp.launcher

/**
 * Matriz de capacidades do motor ORT nesta rodada (ORT1-A03/A08/F05/F08).
 *
 * Separa três coisas que o `backendKind` do whisper.cpp mistura: **motor**
 * (CPP x ORT), **provider** (CPU x QNN) e **rota** (arquivo x gravação x live).
 * O `backendKind` 0/1/2 do JNI continua significando exatamente o que significava
 * (CPU/Vulkan/OpenCL do whisper.cpp) e NUNCA recebe um valor novo para ORT —
 * renumera-lo quebraria lib antiga, ABI e histórico aceito.
 *
 * Default preservado: CPP/CPU continua sendo o motor padrão. ORT é opt-in e só
 * para arquivo, com CPU como único provider executável. QNN HTP/GPU existem como
 * enum porque o plano futuro precisa do nome, mas [rejectionFor] os recusa com
 * motivo explícito: sem implementação e sem prova, não há modo.
 *
 * Seam puro: sem Android, sem I/O.
 */
object WhisperOrtSelection {

    /** Motor de inferência escolhido pelo usuário. */
    enum class Engine {
        /** whisper.cpp via JNI (default do app; CPU/Vulkan/FA preservados). */
        CPP,

        /** ONNX Runtime em processo, sem JNI de whisper. */
        ORT,
    }

    /** Provider de execução do ONNX Runtime. */
    enum class Provider {
        /** Execution provider de CPU: o único habilitado nesta rodada. */
        CPU,

        /** QNN HTP: planejado; sem implementação e sem prova, recusado. */
        QNN_HTP,

        /** QNN GPU: planejado; sem implementação e sem prova, recusado. */
        QNN_GPU,
    }

    /** Rota de entrada do áudio. */
    enum class Route {
        /** Arquivo/mídia escolhida pelo usuário (SAF ou fixture do lab). */
        FILE,

        /** Gravação do microfone seguindo para transcrição. */
        RECORDING,

        /** Tempo real (streaming) — não existe no ORT desta rodada. */
        LIVE,
    }

    /** Providers realmente executáveis hoje. */
    val executableProviders: Set<Provider> = setOf(Provider.CPU)

    /** Rotas realmente atendidas pelo motor ORT hoje. */
    val executableRoutes: Set<Route> = setOf(Route.FILE)

    /** Parâmetro de geração que o motor ainda NÃO honra. */
    enum class UnsupportedKnob {
        BEAM_SEARCH,
        WORD_TIMESTAMPS,
        VAD_FILTER,
        AUTO_LANGUAGE,
        TRANSLATE,
        TIMESTAMPS,
    }

    /** Knobs recusados, com a razão que a UI pode mostrar sem inventar promessa. */
    private val knobReasons: Map<UnsupportedKnob, String> = mapOf(
        UnsupportedKnob.BEAM_SEARCH to "busca em feixe ainda não implementada no ORT (greedy, beam=1)",
        UnsupportedKnob.WORD_TIMESTAMPS to "timestamps por palavra exigem alinhamento que o ORT ainda não produz",
        UnsupportedKnob.VAD_FILTER to "VAD do whisper.cpp não está ligado ao caminho ORT",
        UnsupportedKnob.AUTO_LANGUAGE to "detecção automática de idioma não implementada: idioma é fixo no prefixo",
        UnsupportedKnob.TRANSLATE to "tradução para inglês não é oferecida nesta rodada (só transcribe)",
        UnsupportedKnob.TIMESTAMPS to "tokens de timestamp desligados: saída não tem marcação temporal",
    )

    /** Motivo textual de recusa do knob, ou `null` se o ORT o honra. */
    fun knobRejection(knob: UnsupportedKnob): String = knobReasons.getValue(knob)

    /**
     * Recusa a combinação (engine, provider, rota) com motivo, ou `null` quando
     * executável. O motor CPP não é validado aqui: ele continua com as regras
     * próprias (CPU/Vulkan/FA) e não deve ganhar restrição nova por tabela.
     */
    fun rejectionFor(engine: Engine, provider: Provider, route: Route): String? = when {
        engine == Engine.CPP -> null
        route !in executableRoutes ->
            "rota ${route.name} não suportada no ORT desta rodada (apenas FILE; gravação/live seguem no CPP)"
        provider !in executableProviders ->
            "provider ${provider.name} não executável nesta rodada: só CPU tem implementação e prova"
        else -> null
    }

    /**
     * Seleção efetiva a partir do que o usuário pediu.
     *
     * Devolve a escolha (motor + provider + rota) ou a recusa. Nunca cai em CPP
     * silenciosamente: fallback automático esconderia que o ORT falhou, e a
     * ordem só admite fallback antes do compute ou depois de ACK/dreno seguros,
     * em outra tentativa com motivo registrado.
     */
    fun resolve(engine: Engine, provider: Provider, route: Route): Selection =
        if (engine == Engine.CPP) {
            Selection.Accepted(engine, provider, route)
        } else {
            val rejection = rejectionFor(engine, provider, route)
            if (rejection == null) Selection.Accepted(engine, provider, route)
            else Selection.Rejected(engine, provider, route, rejection)
        }

    /** Resultado de [resolve]. */
    sealed class Selection {
        /** Combinação aceita; o motor deve ser carregado como pedido. */
        data class Accepted(val engine: Engine, val provider: Provider, val route: Route) : Selection()

        /** Combinação recusada; a mensagem é a razão mostrada ao usuário. */
        data class Rejected(
            val engine: Engine,
            val provider: Provider,
            val route: Route,
            val reason: String,
        ) : Selection()
    }
}
