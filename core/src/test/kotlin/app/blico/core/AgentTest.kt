package app.blico.core

import app.blico.core.tools.builtinTools
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTest {
    private val actions = mutableListOf<DeviceAction>()
    private val device = object : DeviceGateway {
        override suspend fun perform(action: DeviceAction): DeviceResult = DeviceResult.Done.also { actions += action }
    }
    private val contacts = object : ContactDirectory {
        override suspend fun find(query: String) = when (query.lowercase()) {
            "joão" -> listOf(Contact("João Silva", "(11) 98765-4321"))
            "ana" -> listOf(Contact("Ana Paula", "11 91111-1111"), Contact("Ana Lima", "21 92222-2222"))
            else -> emptyList()
        }
    }
    private val places = object : PlaceBook {
        override fun resolve(alias: String): String? = null
    }
    private fun ctx(whatsapp: Boolean = false) = ExecContext("t", device, contacts, places, direct = DirectActions(whatsapp = whatsapp))

    private val registry = ToolRegistry(builtinTools())
    private val assistant = Assistant(listOf(RuleRouter()), registry)

    /** Cérebro roteirizado: devolve as decisões em ordem e guarda o que recebeu. */
    private class Scripted(vararg decisions: LlmDecision) : LlmProvider {
        override val id = "roteiro"
        private val queue = ArrayDeque(decisions.toList())
        val requests = mutableListOf<LlmRequest>()

        override suspend fun chooseTools(request: LlmRequest): LlmDecision {
            requests += request
            return queue.removeFirstOrNull() ?: LlmDecision(emptyList(), 0.0)
        }
    }

    private fun call(tool: String, vararg args: Pair<String, String>) = LlmDecision(listOf(ToolCall(tool, mapOf(*args))), 0.9)

    private fun answer(text: String) = LlmDecision(emptyList(), 0.9, text)

    private val agent = AgentProfile("rotina", "Prepare a manhã.")

    @Test
    fun `varios passos ate a resposta, com o historico indo ao cerebro`() = runTest {
        val brain = Scripted(
            call("alarme", "hora" to "7", "minuto" to "0"),
            call("timer", "segundos" to "600"),
            answer("Alarme às 7 e timer de 10 minutos."),
        )
        val result = AgentRunner(listOf(brain), assistant).start("prepara minha manhã", agent, ctx())

        assertEquals(Outcome.Answer("Alarme às 7 e timer de 10 minutos."), result.outcome)
        assertNull(result.paused)
        assertEquals(listOf("alarme", "timer"), result.steps.map { it.call.tool })
        assertEquals(2, actions.size)
        assertEquals(listOf(0, 1, 2), brain.requests.map { it.history.size })
        assertEquals("Prepare a manhã.", brain.requests.first().instructions)
    }

    @Test
    fun `resultado de ferramenta volta como dado nao confiavel`() = runTest {
        val brain = Scripted(call("timer", "segundos" to "60"), answer("Pronto."))
        AgentRunner(listOf(brain), assistant).start("timer", agent, ctx())
        val result = brain.requests[1].history.single().result
        assertTrue(result.startsWith("<<<DADO_NAO_CONFIAVEL origem=ferramenta:timer"), result)
    }

    @Test
    fun `classe 2 pausa no Confirmar e so continua depois do toque`() = runTest {
        val brain = Scripted(
            call("whatsapp_preparar", "contato" to "João", "texto" to "chego já"),
            answer("Mandei para o João."),
        )
        val runner = AgentRunner(listOf(brain), assistant)
        val paused = runner.start("avisa o João", agent, ctx(whatsapp = true))

        val confirm = assertIs<Outcome.Confirm>(paused.outcome)
        assertNotNull(paused.paused)
        assertTrue(actions.isEmpty(), "nada antes do toque: $actions")
        assertEquals(1, brain.requests.size)

        val done = runner.resume(paused.paused!!, confirm.call, ctx(whatsapp = true), confirmed = true)
        assertEquals(Outcome.Answer("Mandei para o João."), done.outcome)
        assertEquals(DeviceAction.SendWhatsApp("5511987654321", "chego já"), actions.single())
        assertEquals("whatsapp_enviar", done.steps.single().call.tool)
    }

    @Test
    fun `escolha pausa e a opcao tocada continua o agente`() = runTest {
        val brain = Scripted(call("whatsapp_preparar", "contato" to "Ana", "texto" to "oi"), answer("Pronto."))
        val runner = AgentRunner(listOf(brain), assistant)
        val paused = runner.start("avisa a Ana", agent, ctx())
        val choose = assertIs<Outcome.Choose>(paused.outcome)

        val done = runner.resume(paused.paused!!, choose.options[0].call, ctx(), confirmed = false)
        assertEquals(Outcome.Answer("Pronto."), done.outcome)
        assertEquals(1, actions.size)
    }

    @Test
    fun `ferramenta fora do agente e recusada`() = runTest {
        val brain = Scripted(call("ligar", "contato" to "João"))
        val restrito = AgentProfile("timers", "Só timers.", tools = setOf("timer"))
        val result = AgentRunner(listOf(brain), assistant).start("liga pro João", restrito, ctx())

        assertIs<Outcome.Failed>(result.outcome)
        assertTrue(actions.isEmpty())
        assertEquals(listOf("timer"), brain.requests.single().tools.map { it.name })
    }

    @Test
    fun `limite de passos encerra o agente`() = runTest {
        val brain = Scripted(
            call("timer", "segundos" to "60"),
            call("timer", "segundos" to "120"),
            call("timer", "segundos" to "180"),
        )
        val curto = AgentProfile("curto", "", maxSteps = 2)
        val result = AgentRunner(listOf(brain), assistant).start("timers", curto, ctx())

        assertEquals(2, actions.size)
        assertEquals(Outcome.Answer("Parei depois de 2 passos. Fiz 2 passos."), result.outcome)
    }

    @Test
    fun `o mesmo passo duas vezes seguidas para o laco`() = runTest {
        val brain = Scripted(call("timer", "segundos" to "60"), call("timer", "segundos" to "60"))
        val result = AgentRunner(listOf(brain), assistant).start("timer", agent, ctx())
        assertEquals(1, actions.size)
        assertIs<Outcome.Answer>(result.outcome)
    }

    @Test
    fun `falha encerra sem tentar de novo`() = runTest {
        val brain = Scripted(call("ligar", "contato" to "Ninguém"), call("timer", "segundos" to "60"))
        val result = AgentRunner(listOf(brain), assistant).start("liga", agent, ctx())
        assertIs<Outcome.Failed>(result.outcome)
        assertEquals(1, brain.requests.size)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `cerebro incerto passa para o proximo provedor`() = runTest {
        val unsure = Scripted(LlmDecision(listOf(ToolCall("timer", mapOf("segundos" to "1"))), 0.2))
        val cloud = Scripted(answer("Não sei fazer isso."))
        val result = AgentRunner(listOf(unsure, cloud), assistant).start("faz algo", agent, ctx())
        assertEquals(Outcome.Answer("Não sei fazer isso."), result.outcome)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `limite de passos tem teto`() {
        assertFailsWith<IllegalArgumentException> { AgentProfile("x", "", maxSteps = 50) }
    }

    @Test
    fun `instrucoes e passos entram na mensagem do modelo local`() {
        val msg = LocalBrain.userMessage(
            LlmRequest(
                "prepara a manhã",
                emptyList(),
                instructions = "Use alarmes.",
                history = listOf(AgentStep(ToolCall("alarme", mapOf("hora" to "7")), "Alarme às 7")),
            ),
        )
        assertTrue("Use alarmes." in msg && "1. alarme(hora=7)" in msg && "Alarme às 7" in msg, msg)
        assertEquals("só o pedido", LocalBrain.userMessage(LlmRequest("só o pedido", emptyList())))
    }

    @Test
    fun `passos e instrucoes saem mascarados e com os mesmos apelidos`() = runTest {
        var sent = ""
        val cloud = CloudProvider { body -> sent = body; """{"calls":[],"answer":"Avisei contato_1 no telefone_1.","confidence":0.9}""" }
        val provider = RedactingProvider(cloud) { RedactingProvider.entitiesFrom(listOf("João Silva"), emptyMap()) }
        val d = provider.chooseTools(
            LlmRequest(
                "avisa o João Silva",
                emptyList(),
                instructions = "Sempre avise o João Silva no 11 98765-4321.",
                history = listOf(AgentStep(ToolCall("whatsapp_preparar", mapOf("contato" to "João Silva")), "Conversa com 11 98765-4321 aberta")),
            ),
        )
        listOf("João", "98765").forEach { assertFalse(it in sent, "vazou $it: $sent") }
        assertTrue("\"instructions\":\"Sempre avise o contato_1 no telefone_1.\"" in sent, sent)
        assertTrue("\"result\":\"Conversa com telefone_1 aberta\"" in sent, sent)
        assertEquals("Avisei João Silva no 11 98765-4321.", d.answer)
    }
}
