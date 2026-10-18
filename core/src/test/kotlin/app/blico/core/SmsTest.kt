package app.blico.core

import app.blico.core.capability.Capabilities
import app.blico.core.capability.SmsCapability
import app.blico.core.capability.SmsPort
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeSms : SmsPort {
    var allowed = true
    val sent = mutableListOf<Pair<String, String>>()

    override fun canSend() = allowed

    override suspend fun send(phone: String, text: String): DeviceResult {
        sent += phone to text
        return DeviceResult.Done
    }
}

class SmsTest {
    private val sms = FakeSms()
    private val opened = mutableListOf<DeviceAction>()
    private val caps = Capabilities(listOf(SmsCapability(sms)))
    private val router = RuleRouter(caps.rules())
    private val assistant = Assistant(listOf(router), ToolRegistry(caps.tools()))
    private val ctx = ExecContext("t", object : DeviceGateway {
        override suspend fun perform(action: DeviceAction): DeviceResult {
            opened += action
            return DeviceResult.Done
        }
    }, object : ContactDirectory {
        override suspend fun find(query: String) = if (query.lowercase() == "joão") listOf(Contact("João Silva", "(11) 98765-4321")) else emptyList()
    }, object : PlaceBook {
        override fun resolve(alias: String): String? = null
    })

    @Test
    fun `frases`() {
        assertEquals(
            ToolCall("sms_preparar", mapOf("contato" to "João", "texto" to "eu chego às 8")),
            router.parse("manda um SMS pro João dizendo que eu chego às 8"),
        )
        assertEquals(ToolCall("sms_preparar", mapOf("contato" to "mãe", "texto" to "tô chegando")), router.parse("manda mensagem de texto pra mãe: tô chegando"))
        assertEquals(ToolCall("sms_preparar", mapOf("contato" to "Ana")), router.parse("envia um sms para a Ana"))
    }

    @Test
    fun `com permissao, envia depois do Confirmar`() = runTest {
        val out = assistant.handle("manda um SMS pro João dizendo que eu chego às 8", ctx)
        assertIs<Outcome.Confirm>(out)
        assertEquals("Mandar SMS para João Silva", out.summary.title)
        assertTrue(sms.sent.isEmpty())
        assertEquals("SMS enviado para João Silva.", assistant.execute(out.call, ctx, confirmed = true).spoken)
        assertEquals(listOf("(11) 98765-4321" to "eu chego às 8"), sms.sent)
    }

    @Test
    fun `sem permissao, deixa pronto no app de mensagens`() = runTest {
        sms.allowed = false
        val out = assistant.handle("manda um SMS pro João dizendo que eu chego às 8", ctx)
        assertIs<Outcome.Done>(out)
        assertEquals(DeviceAction.OpenUri("smsto:11987654321?body=eu%20chego%20%C3%A0s%208"), opened.single())
        assertTrue(sms.sent.isEmpty())
    }
}
