package app.blico.core

import app.blico.core.tools.GoogleAccount
import app.blico.core.tools.GoogleResponse
import app.blico.core.tools.googleTools
import app.blico.core.tools.parseDay
import app.blico.core.tools.rawMail
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleToolsTest {
    private class FakeGoogle(var connected0: Boolean = true) : GoogleAccount {
        val requests = mutableListOf<Triple<String, String, String?>>()
        var status = 200
        override val connected get() = connected0

        override suspend fun request(method: String, url: String, body: String?): GoogleResponse {
            requests += Triple(method, url, body)
            if (status != 200) return GoogleResponse(status, "")
            val response = when {
                "calendar" in url && method == "GET" -> """{"items":[
                    {"summary":"Dentista","start":{"dateTime":"2026-10-17T14:00:00-03:00"}},
                    {"summary":"Aniversário da Ana","start":{"date":"2026-10-17"}}]}"""
                "/messages?" in url -> """{"messages":[{"id":"m1"}]}"""
                "/messages/m1" in url -> """{"snippet":"Chego às 8","payload":{"headers":[{"name":"From","value":"Ana <ana@x.com>"},{"name":"Subject","value":"Jantar"}]}}"""
                else -> "{}"
            }
            return GoogleResponse(200, response)
        }
    }

    private val google = FakeGoogle()
    private val registry = ToolRegistry(googleTools(google))
    private val policy = RiskPolicy(registry)
    private val ctx = ExecContext(
        "t",
        object : DeviceGateway {
            override suspend fun perform(action: DeviceAction) = DeviceResult.Done
        },
        object : ContactDirectory {
            override suspend fun find(query: String) = emptyList<Contact>()
        },
        object : PlaceBook {
            override fun resolve(alias: String): String? = null
        },
        clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneId.of("America/Sao_Paulo")),
    )

    private suspend fun run(tool: String, vararg args: Pair<String, String>): ToolResult {
        val call = ToolCall(tool, mapOf(*args))
        assertTrue(registry.validate(call).isEmpty(), "${registry.validate(call)}")
        return registry[tool]!!.run(call, ctx)
    }

    @Test
    fun `ler executa direto, criar evento e enviar param no Confirmar`() {
        assertEquals(Decision.Execute, policy.decide(ToolCall("agenda_ver")))
        assertEquals(Decision.Execute, policy.decide(ToolCall("gmail_ler")))
        assertEquals(Decision.Execute, policy.decide(ToolCall("gmail_rascunho", mapOf("para" to "a@x.com", "assunto" to "a", "texto" to "b"))))
        assertIs<Decision.Confirm>(policy.decide(ToolCall("agenda_criar", mapOf("titulo" to "Dentista", "inicio" to "2026-10-18T14:00"))))
        val send = policy.decide(ToolCall("gmail_enviar", mapOf("para" to "ana@x.com", "assunto" to "Oi", "texto" to "Tudo bem?")))
        assertIs<Decision.Confirm>(send)
        assertEquals(ConfirmationSummary("Enviar e-mail", listOf("para" to "ana@x.com", "assunto" to "Oi", "texto" to "Tudo bem?")), send.summary)
    }

    @Test
    fun `sem login as ferramentas somem dos modelos`() {
        val off = FakeGoogle(connected0 = false)
        val assistant = Assistant(emptyList(), ToolRegistry(googleTools(off)))
        assertEquals(emptyList(), assistant.modelTools())
        off.connected0 = true
        assertEquals(5, assistant.modelTools().size)
    }

    @Test
    fun `agenda do dia no fuso do aparelho`() = runTest {
        val r = run("agenda_ver", "dia" to "hoje")
        assertEquals(ToolResult.Success("Hoje: 14:00, Dentista; o dia todo, Aniversário da Ana"), r)
        val url = google.requests.single().second
        assertTrue("timeMin=2026-10-17T00%3A00-03%3A00" in url, url)
        assertTrue("timeMax=2026-10-18T00%3A00-03%3A00" in url, url)
    }

    @Test
    fun `cria evento com fim pela duracao`() = runTest {
        val r = run("agenda_criar", "titulo" to "Dentista", "inicio" to "2026-10-18T14:00", "duracao_min" to "30")
        assertEquals(ToolResult.Success("Pus Dentista na agenda, 18/10 às 14:00"), r)
        val body = google.requests.single().third!!
        assertTrue("\"start\":{\"dateTime\":\"2026-10-18T14:00-03:00\"}" in body, body)
        assertTrue("\"end\":{\"dateTime\":\"2026-10-18T14:30-03:00\"}" in body, body)
    }

    @Test
    fun `le remetente, assunto e trecho`() = runTest {
        val r = run("gmail_ler", "busca" to "is:unread")
        assertEquals(ToolResult.Success("De Ana <ana@x.com>: Jantar. Chego às 8"), r)
        assertTrue("q=is%3Aunread" in google.requests.first().second)
    }

    @Test
    fun `e-mail sai em utf-8 sem cabecalho injetado`() {
        val raw = String(Base64.getUrlDecoder().decode(rawMail("ana@x.com\r\nBcc: outro@x.com", "Olá", "Até já")))
        assertTrue(raw.startsWith("To: ana@x.comBcc: outro@x.com\r\nSubject: =?UTF-8?B?"))
        assertTrue(raw.endsWith("\r\n\r\nAté já"))
    }

    @Test
    fun `login vencido pede para entrar de novo`() = runTest {
        google.status = 401
        val r = run("gmail_ler")
        assertIs<ToolResult.Failure>(r)
        assertEquals("Preciso que você entre de novo na conta Google", r.spoken)
    }

    @Test
    fun `dias por extenso`() {
        val today = LocalDate.of(2026, 10, 17)
        assertEquals(today.plusDays(1), parseDay("Amanhã", today))
        assertEquals(LocalDate.of(2026, 12, 1), parseDay("2026-12-01", today))
        assertEquals(null, parseDay("semana que vem", today))
    }
}
