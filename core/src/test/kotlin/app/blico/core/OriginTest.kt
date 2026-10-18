package app.blico.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OriginTest {
    private val timer = Outcome.Done("Timer de 10 minutos", ToolCall("timer", mapOf("minutos" to "10")))

    @Test
    fun regrasEModeloLocalFicamNoCelular() {
        assertEquals(Origin.DEVICE, Origin.of("regras", timer))
        assertEquals(Origin.DEVICE, Origin.of("local", Outcome.Answer("Copenhague.")))
        assertEquals(Origin.DEVICE, Origin.of("busca", Outcome.Answer("Copenhague.", source = "Gemma no celular")))
        assertEquals(Origin.DEVICE, Origin.of(null, timer))
    }

    @Test
    fun nuvemQuandoOPedidoFoiAoServidor() {
        assertEquals(Origin.CLOUD, Origin.of("nuvem", timer))
        assertEquals(Origin.CLOUD, Origin.of("nuvem", Outcome.Answer("Resumo.")))
        // A nuvem que pediu a busca continua sendo nuvem: é o que mais saiu do aparelho.
        assertEquals(Origin.CLOUD, Origin.of("nuvem", Outcome.Answer("Ulã Bator.", source = "pt.wikipedia.org")))
    }

    @Test
    fun buscaQueSaiuDoAparelhoEWeb() {
        assertEquals(Origin.WEB, Origin.of("busca", Outcome.Answer("Ulã Bator.", source = "pt.wikipedia.org")))
        assertEquals(Origin.WEB, Origin.of("regras", Outcome.Answer("A capital é Ulã Bator.", source = "pt.wikipedia.org + Gemma no celular")))
    }

    @Test
    fun contaDoGoogleGanhaOSeloDoServico() {
        val criar = ToolCall("agenda_criar", mapOf("titulo" to "Reunião"))
        assertEquals(Origin.GOOGLE, Origin.of("nuvem", Outcome.Done("Evento criado", criar)))
        assertEquals(Origin.GOOGLE, Origin.of(null, Outcome.Done("Rascunho pronto", ToolCall("gmail_rascunho"))))
        assertEquals("[google]", Origin.GOOGLE.badge)
    }

    @Test
    fun nomeGuardadoVoltaParaOMesmoSelo() {
        listOf(Origin.DEVICE, Origin.CLOUD, Origin.WEB, Origin.GOOGLE).forEach { assertEquals(it, Origin.named(it.name)) }
        assertEquals(Origin(Origin.Kind.SERVICE, "notion"), Origin.named("notion"))
    }
}
