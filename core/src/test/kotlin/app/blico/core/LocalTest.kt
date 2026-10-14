package app.blico.core

import app.blico.core.LocalBrain.RawCall
import app.blico.core.tools.builtinTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalTest {
    private val tools = builtinTools().filter { it.exposedToModels }

    @Test
    fun `ferramenta vira o json que o LiteRT-LM espera`() {
        val timer = tools.first { it.name == "timer" }
        val json = Json.parseToJsonElement(LocalBrain.toolJson(timer)).jsonObject
        assertEquals("timer", json["name"]!!.jsonPrimitive.content)
        val params = json["parameters"]!!.jsonObject
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertEquals("string", params["properties"]!!.jsonObject["segundos"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("segundos"), params["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `chamada valida tem confianca alta e argumentos em texto`() {
        val d = LocalBrain.decide(listOf(RawCall("timer", mapOf("segundos" to 300.0))), null, tools)
        assertEquals(listOf(ToolCall("timer", mapOf("segundos" to "300"))), d.calls)
        assertEquals(LocalBrain.CALL_CONFIDENCE, d.confidence)
    }

    @Test
    fun `ferramenta inventada e ignorada e a proxima valida vale`() {
        val d = LocalBrain.decide(
            listOf(RawCall("enviar_pix", mapOf("valor" to 10)), RawCall("ligar", mapOf("contato" to " Ana "))),
            null,
            tools,
        )
        assertEquals(ToolCall("ligar", mapOf("contato" to "Ana")), d.calls.single())
        assertEquals(0.0, LocalBrain.decide(listOf(RawCall("enviar_pix", emptyMap())), "feito", tools).confidence)
    }

    @Test
    fun `sem argumento obrigatorio a confianca fica abaixo do minimo`() {
        val d = LocalBrain.decide(listOf(RawCall("ligar", mapOf("contato" to null))), null, tools)
        assertTrue(d.confidence < 0.6)
    }

    @Test
    fun `nao sei passa a vez e resposta vira fala limpa`() {
        assertEquals(0.0, LocalBrain.decide(emptyList(), "NAO_SEI", tools).confidence)
        assertEquals(0.0, LocalBrain.decide(emptyList(), "  ", tools).confidence)
        val d = LocalBrain.decide(emptyList(), "**Machado de Assis** escreveu\n- Dom Casmurro.", tools)
        assertEquals("Machado de Assis escreveu Dom Casmurro.", d.answer)
        assertEquals(LocalBrain.ANSWER_CONFIDENCE, d.confidence)
    }

    @Test
    fun `argumentos numericos e listas viram texto`() {
        assertEquals("7", LocalBrain.argText(7.0))
        assertEquals("7.5", LocalBrain.argText(7.5))
        assertEquals("a, b", LocalBrain.argText(listOf("a", null, "b")))
        assertNull(LocalBrain.argText(""))
    }

    @Test
    fun `prompt traz a data em portugues`() {
        val now = ZonedDateTime.of(2026, 10, 14, 9, 5, 0, 0, ZoneId.of("America/Sao_Paulo"))
        val p = LocalBrain.systemPrompt(now)
        assertTrue("quarta-feira, 14 de outubro de 2026, 09:05" in p, p)
        assertTrue(LocalBrain.DONT_KNOW in p)
    }

    @Test
    fun `provedor desligado passa a vez sem chamar o de dentro`() = runTest {
        var called = false
        val inner = object : LlmProvider {
            override val id = "x"

            override suspend fun chooseTools(request: LlmRequest): LlmDecision {
                called = true
                return LlmDecision(emptyList(), 1.0, "oi")
            }
        }
        assertEquals(0.0, OptionalProvider(inner) { false }.chooseTools(LlmRequest("oi", tools)).confidence)
        assertFalse(called)
    }

    @Test
    fun `benchmark tem 100 casos com ferramentas que existem`() {
        val cases = Benchmark.builtin()
        assertEquals(100, cases.size)
        val names = tools.map { it.name }.toSet()
        cases.mapNotNull { it.expected as? Benchmark.Expected.Call }.forEach { assertTrue(it.tool in names, it.tool) }
        cases.mapNotNull { it.expected as? Benchmark.Expected.Call }.forEach { call ->
            val tool = tools.first { it.name == call.tool }
            call.args.keys.forEach { assertTrue(it in tool.parameters.properties, "${call.tool}.$it") }
        }
    }

    @Test
    fun `benchmark pontua acao resposta e passar a vez`() = runTest {
        val cases = Benchmark.parse(
            """
            # comentário
            timer	conta dez minutos	segundos=600
            resposta	quem pintou a Mona Lisa
            nuvem	quanto está o dólar
            alarme	me acorda às sete	hora=7
            """.trimIndent(),
        )
        val brain = object : LlmProvider {
            override val id = "falso"

            override suspend fun chooseTools(request: LlmRequest): LlmDecision = when {
                "minutos" in request.text -> LlmDecision(listOf(ToolCall("timer", mapOf("segundos" to "600"))), 0.85)
                "Mona" in request.text -> LlmDecision(emptyList(), 0.7, "Leonardo da Vinci.")
                "dólar" in request.text -> LlmDecision(emptyList(), 0.0)
                else -> error("travou")
            }
        }
        var t = 0L
        val report = Benchmark.run(brain, cases, tools, now = { t += 500; t })
        assertEquals(3, report.passed)
        assertEquals("travou", report.results.last().error?.substringAfter(": "))
        assertEquals(mapOf("ações" to (1 to 2), "perguntas" to (1 to 1), "passar para a nuvem" to (1 to 1)), report.byGroup())
        assertTrue(report.summary().startsWith("Acertou 3 de 4"), report.summary())
    }
}
