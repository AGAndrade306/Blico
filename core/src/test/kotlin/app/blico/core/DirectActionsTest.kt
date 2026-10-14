package app.blico.core

import app.blico.core.tools.builtinTools
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DirectActionsTest {
    private val actions = mutableListOf<DeviceAction>()
    private var answer: (DeviceAction) -> DeviceResult = { DeviceResult.Done }
    private val device = object : DeviceGateway {
        override suspend fun perform(action: DeviceAction): DeviceResult {
            actions += action
            return answer(action)
        }
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
    private val registry = ToolRegistry(builtinTools())
    private val assistant = Assistant(listOf(RuleRouter()), registry)

    private fun ctx(whatsapp: Boolean = true, calls: Boolean = true) =
        ExecContext("t", device, contacts, places, direct = DirectActions(whatsapp, calls))

    @Test
    fun `envio direto pede o Confirmar antes de tocar em qualquer coisa`() = runTest {
        val out = assistant.handle("manda pro João que chego já", ctx())
        out as Outcome.Confirm
        assertTrue(actions.isEmpty(), "nada pode acontecer antes do toque: $actions")
        assertEquals("Mandar para João Silva no WhatsApp?", out.spoken)
        assertEquals(listOf("Para" to "João Silva · (11) 98765-4321", "Mensagem" to "chego já"), out.summary.fields)

        val done = assistant.execute(out.call, ctx(), confirmed = true)
        assertIs<Outcome.Done>(done)
        assertEquals("Enviei para João Silva.", done.spoken)
        assertEquals(listOf<DeviceAction>(DeviceAction.SendWhatsApp("5511987654321", "chego já")), actions)
    }

    @Test
    fun `sem o toque a chamada de enviar so devolve o cartao`() = runTest {
        val call = ToolCall("whatsapp_enviar", mapOf("contato" to "X", "telefone" to "11999999999", "texto" to "oi"))
        assertIs<Outcome.Confirm>(assistant.execute(call, ctx()))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `envio direto desligado continua so preparando`() = runTest {
        val out = assistant.handle("manda pro João que chego já", ctx(whatsapp = false))
        assertIs<Outcome.Done>(out)
        assertIs<DeviceAction.OpenUri>(actions.single())
    }

    @Test
    fun `sem texto nao ha o que enviar e a conversa so abre`() = runTest {
        val out = assistant.handle("manda mensagem pro João", ctx())
        assertIs<Outcome.Done>(out)
        assertIs<DeviceAction.OpenUri>(actions.single())
    }

    @Test
    fun `contato ambiguo escolhe primeiro e confirma depois`() = runTest {
        val choose = assistant.handle("manda pra Ana que já vou", ctx())
        choose as Outcome.Choose
        val confirm = assistant.execute(choose.options[1].call, ctx())
        confirm as Outcome.Confirm
        assertEquals("21 92222-2222", confirm.call.args["telefone"])
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `se o toque em enviar falha a mensagem fica pronta`() = runTest {
        answer = { DeviceResult.Failed("botão enviar não apareceu") }
        val call = ToolCall("whatsapp_enviar", mapOf("contato" to "João Silva", "telefone" to "11987654321", "texto" to "oi"))
        val out = assistant.execute(call, ctx(), confirmed = true)
        out as Outcome.Failed
        assertEquals("Não consegui enviar sozinho. A mensagem para João Silva ficou pronta, toque em enviar.", out.spoken)
        assertEquals("botão enviar não apareceu", out.reason)
    }

    @Test
    fun `ligacao direta confirma e liga`() = runTest {
        val out = assistant.handle("liga pro João", ctx())
        out as Outcome.Confirm
        assertEquals("Ligar para João Silva?", out.spoken)
        assertTrue(actions.isEmpty())
        val done = assistant.execute(out.call, ctx(), confirmed = true)
        assertEquals("Ligando para João Silva.", done.spoken)
        assertEquals(listOf<DeviceAction>(DeviceAction.Call("(11) 98765-4321")), actions)
    }

    @Test
    fun `sem permissao de ligar o numero vai para o discador`() = runTest {
        answer = { if (it is DeviceAction.Call) DeviceResult.Failed("sem CALL_PHONE") else DeviceResult.Done }
        val call = ToolCall("ligar_agora", mapOf("contato" to "João Silva", "telefone" to "11987654321"))
        val out = assistant.execute(call, ctx(), confirmed = true)
        assertIs<Outcome.Failed>(out)
        assertEquals(listOf(DeviceAction.Call("11987654321"), DeviceAction.Dial("11987654321")), actions)
    }

    @Test
    fun `ligacao direta desligada abre o discador`() = runTest {
        assistant.handle("liga pro João", ctx(calls = false))
        assertEquals(listOf<DeviceAction>(DeviceAction.Dial("(11) 98765-4321")), actions)
    }

    @Test
    fun `enviar e ligar de verdade nao sao oferecidos aos modelos`() {
        val names = assistant.modelTools().map { it.name }
        assertFalse("whatsapp_enviar" in names)
        assertFalse("ligar_agora" in names)
        assertEquals(RiskClass.COMMIT, registry["whatsapp_enviar"]!!.risk)
        assertEquals(RiskClass.COMMIT, registry["ligar_agora"]!!.risk)
    }

    @Test
    fun `texto na tela precisa ser o confirmado`() {
        assertTrue(DirectSend.sameText("  chego já\n", "chego já"))
        assertTrue(DirectSend.sameText("chego   já", "chego já"))
        assertFalse(DirectSend.sameText("chego ja", "chego já"))
        assertFalse(DirectSend.sameText("chego já!", "chego já"))
        assertFalse(DirectSend.sameText(null, "chego já"))
        assertFalse(DirectSend.sameText("", ""))
    }
}
