package br.gov.sp.pcsp.launcher

import br.gov.sp.pcsp.launcher.WhisperOrtSelection.Engine
import br.gov.sp.pcsp.launcher.WhisperOrtSelection.Provider
import br.gov.sp.pcsp.launcher.WhisperOrtSelection.Route
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prova que o ORT não anuncia capacidade que não tem (ORT1-A03/A08/F05/F08):
 * provider/rota fora do escopo são RECUSADOS com motivo, e o CPP não ganha
 * restrição nova por tabela.
 */
class WhisperOrtSelectionTest {

    @Test
    fun `cpp continua aceito em todas as combinacoes`() {
        for (provider in Provider.values()) {
            for (route in Route.values()) {
                val selection = WhisperOrtSelection.resolve(Engine.CPP, provider, route)
                assertTrue(
                    "CPP/$provider/$route",
                    selection is WhisperOrtSelection.Selection.Accepted,
                )
            }
        }
    }

    @Test
    fun `ort cpu arquivo e a unica combinacao executavel`() {
        val accepted = WhisperOrtSelection.resolve(Engine.ORT, Provider.CPU, Route.FILE)
        assertTrue(accepted is WhisperOrtSelection.Selection.Accepted)

        for (provider in listOf(Provider.QNN_HTP, Provider.QNN_GPU)) {
            val rejected = WhisperOrtSelection.resolve(Engine.ORT, provider, Route.FILE)
            assertTrue("$provider deveria ser recusado", rejected is WhisperOrtSelection.Selection.Rejected)
            assertTrue(
                (rejected as WhisperOrtSelection.Selection.Rejected).reason.contains(provider.name),
            )
        }
    }

    @Test
    fun `gravacao e live seguem no cpp e sao recusados no ort`() {
        for (route in listOf(Route.RECORDING, Route.LIVE)) {
            val rejected = WhisperOrtSelection.resolve(Engine.ORT, Provider.CPU, route)
            assertTrue("$route deveria ser recusado", rejected is WhisperOrtSelection.Selection.Rejected)
            val reason = (rejected as WhisperOrtSelection.Selection.Rejected).reason
            assertTrue(reason.contains(route.name))
            assertTrue(reason.contains("FILE"))
        }
    }

    @Test
    fun `todo knob nao suportado tem motivo util e distinto`() {
        val reasons = WhisperOrtSelection.UnsupportedKnob.values().map {
            WhisperOrtSelection.knobRejection(it)
        }
        reasons.forEach { assertTrue(it.length > 20) }
        assertTrue("motivos precisam ser distintos", reasons.toSet().size == reasons.size)
        assertTrue(
            WhisperOrtSelection.knobRejection(WhisperOrtSelection.UnsupportedKnob.BEAM_SEARCH)
                .contains("greedy"),
        )
        assertTrue(
            WhisperOrtSelection.knobRejection(WhisperOrtSelection.UnsupportedKnob.AUTO_LANGUAGE)
                .contains("idioma"),
        )
    }
}
