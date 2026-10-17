package app.blico.core

import app.blico.core.tools.builtinTools
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroupTest {
    private val names = listOf("manha", "avisos", "organizador")

    @Test
    fun `divide a mensagem por arroba`() {
        assertEquals(
            listOf("manha" to "alarme às 6", "avisos" to "avisa o João que atraso"),
            Mentions.split("@manha alarme às 6 @avisos avisa o João que atraso", names),
        )
    }

    @Test
    fun `texto antes do primeiro arroba vai para todos e nomes sem acento casam`() {
        assertEquals(
            listOf("manha" to "amanhã cedo: alarme", "avisos" to "amanhã cedo:"),
            Mentions.split("amanhã cedo: @Manhã alarme @avisos", names),
        )
    }

    @Test
    fun `arroba de quem nao esta no grupo fica no texto`() {
        assertEquals(listOf("avisos" to "fala com @joao"), Mentions.split("@avisos fala com @joao", names))
        assertEquals(emptyList(), Mentions.split("sem ninguém", names))
    }

    private val actions = mutableListOf<DeviceAction>()
    private val device = object : DeviceGateway {
        override suspend fun perform(action: DeviceAction): DeviceResult = DeviceResult.Done.also { actions += action }
    }
    private val contacts = object : ContactDirectory {
        override suspend fun find(query: String) =
            if (query.lowercase() == "joão") listOf(Contact("João Silva", "(11) 98765-4321")) else emptyList()
    }
    private val places = object : PlaceBook {
        override fun resolve(alias: String): String? = null
    }
    private fun ctx() = ExecContext("t", device, contacts, places, direct = DirectActions(whatsapp = true))
    private val assistant = Assistant(listOf(RuleRouter()), ToolRegistry(builtinTools()))
    private val manha = AgentProfile("manha", "Alarmes.", tools = setOf("alarme"))
    private val avisos = AgentProfile("avisos", "Mensagens.", tools = setOf("whatsapp_preparar"))
    private val members = listOf(manha, avisos)

    private class PerAgent(private val scripts: Map<String, ArrayDeque<LlmDecision>>) : LlmProvider {
        override val id = "roteiro"
        val requests = mutableListOf<LlmRequest>()

        override suspend fun chooseTools(request: LlmRequest): LlmDecision {
            requests += request
            return scripts[request.instructions]?.removeFirstOrNull() ?: LlmDecision(emptyList(), 0.0)
        }
    }

    private fun call(tool: String, vararg args: Pair<String, String>) = LlmDecision(listOf(ToolCall(tool, mapOf(*args))), 0.9)
    private fun answer(text: String) = LlmDecision(emptyList(), 0.9, text)

    @Test
    fun `cada citado responde e ve o que ja foi dito`() = runTest {
        val brain = PerAgent(
            mapOf(
                "Alarmes." to ArrayDeque(listOf(call("alarme", "hora" to "6"), answer("Alarme às 6."))),
                "Mensagens." to ArrayDeque(listOf(answer("Para quem?"))),
            ),
        )
        val group = GroupRunner(AgentRunner(listOf(brain), assistant))
        val r = group.send("@manha alarme às 6 @avisos me lembra de avisar", members, emptyList(), "manha", ctx())

        assertEquals(listOf("voce", "manha", "avisos"), r.transcript.map { it.from })
        assertEquals(DeviceAction.SetAlarm(6, 0), actions.single())
        val seen = brain.requests.last().context.single()
        assertTrue(seen.startsWith("<<<DADO_NAO_CONFIAVEL origem=conversa") && "manha: Alarme às 6." in seen, seen)
    }

    @Test
    fun `sem arroba vai para o agente escolhido`() = runTest {
        val brain = PerAgent(mapOf("Alarmes." to ArrayDeque(listOf(answer("Oi.")))))
        val r = GroupRunner(AgentRunner(listOf(brain), assistant)).send("bom dia", members, emptyList(), "manha", ctx())
        assertEquals(listOf("voce", "manha"), r.transcript.map { it.from })
    }

    @Test
    fun `agente passa a vez citando outro e o Confirmar para o grupo`() = runTest {
        val brain = PerAgent(
            mapOf(
                "Alarmes." to ArrayDeque(listOf(answer("Pus o alarme. @avisos avisa o João que acordo às 6"))),
                "Mensagens." to ArrayDeque(listOf(call("whatsapp_preparar", "contato" to "João", "texto" to "acordo às 6"), answer("Mandei."))),
            ),
        )
        val group = GroupRunner(AgentRunner(listOf(brain), assistant))
        val paused = group.send("@manha alarme", members, emptyList(), "manha", ctx())
        val confirm = assertIs<Outcome.Confirm>(paused.outcome)
        assertNotNull(paused.paused)
        assertTrue(actions.isEmpty())
        assertEquals("Pus o alarme. avisa o João que acordo às 6", brain.requests[1].text)

        val done = group.resume(paused.paused!!, confirm.call, members, ctx(), confirmed = true)
        assertEquals(listOf("voce", "manha", "avisos"), done.transcript.map { it.from })
        assertEquals("Mandei.", done.transcript.last().text)
        assertEquals(DeviceAction.SendWhatsApp("5511987654321", "acordo às 6"), actions.single())
    }

    @Test
    fun `passagens de vez tem limite`() = runTest {
        val pingpong = PerAgent(
            mapOf(
                "Alarmes." to ArrayDeque(List(5) { answer("@avisos sua vez") }),
                "Mensagens." to ArrayDeque(List(5) { answer("@manha sua vez") }),
            ),
        )
        val r = GroupRunner(AgentRunner(listOf(pingpong), assistant)).send("@manha começa", members, emptyList(), "manha", ctx())
        assertEquals(1 + 1 + GroupRunner.MAX_HOPS, r.transcript.size)
    }
}
