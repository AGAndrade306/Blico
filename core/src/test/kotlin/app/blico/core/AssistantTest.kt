package app.blico.core

import app.blico.core.tools.builtinTools
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AssistantTest {
    private val actions = mutableListOf<DeviceAction>()
    private val ctx = ExecContext(
        requestId = "t",
        device = object : DeviceGateway {
            override suspend fun perform(action: DeviceAction): DeviceResult = DeviceResult.Done.also { actions += action }
        },
        contacts = object : ContactDirectory {
            override suspend fun find(query: String) =
                listOf(Contact("Ana Paula", "11911111111"), Contact("Ana Lima", "21922222222"))
        },
        places = object : PlaceBook {
            override fun resolve(alias: String): String? = null
        },
    )
    private val registry = ToolRegistry(builtinTools())

    @Test
    fun `do texto a acao`() = runTest {
        val out = Assistant(listOf(RuleRouter()), registry).handle("timer de 5 minutos", ctx)
        assertEquals("Timer de 5 minutos", out.spoken)
        assertEquals(listOf<DeviceAction>(DeviceAction.SetTimer(300)), actions)
    }

    @Test
    fun `escolha volta e executa a opcao`() = runTest {
        val assistant = Assistant(listOf(RuleRouter()), registry)
        val choose = assistant.handle("manda pra Ana que já vou", ctx)
        choose as Outcome.Choose
        val done = assistant.execute(choose.options[1].call, ctx)
        assertIs<Outcome.Done>(done)
        assertEquals(DeviceAction.OpenUri("https://api.whatsapp.com/send?phone=5521922222222&text=j%C3%A1%20vou", "com.whatsapp"), actions.single())
    }

    @Test
    fun `nao entendido quando nenhum provedor tem confianca`() = runTest {
        val unsure = object : LlmProvider {
            override val id = "incerto"
            override suspend fun chooseTools(request: LlmRequest) = LlmDecision(listOf(ToolCall("timer", mapOf("segundos" to "1"))), 0.2)
        }
        val out = Assistant(listOf(RuleRouter(), unsure), registry).handle("resume minhas notificações", ctx)
        assertIs<Outcome.NotUnderstood>(out)
        assertEquals(emptyList(), actions)
    }

    @Test
    fun `provedor que falha nao derruba o pedido`() = runTest {
        val broken = object : LlmProvider {
            override val id = "quebrado"
            override suspend fun chooseTools(request: LlmRequest): LlmDecision = error("sem rede")
        }
        val out = Assistant(listOf(broken, RuleRouter()), registry).handle("timer de 1 minuto", ctx)
        assertIs<Outcome.Done>(out)
    }
}
