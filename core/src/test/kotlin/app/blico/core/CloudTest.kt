package app.blico.core

import app.blico.core.tools.builtinTools
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudTest {
    private val tools = builtinTools()

    @Test
    fun `pedido leva o catalogo de ferramentas no formato do servidor`() {
        val body = CloudProtocol.encode(LlmRequest("oi", tools))
        assertTrue("\"name\":\"whatsapp_preparar\"" in body, body)
        assertTrue("\"required\":[\"contato\"]" in body, body)
        assertTrue("\"text\":\"oi\"" in body, body)
    }

    @Test
    fun `resposta vira decisao`() {
        val d = CloudProtocol.decode("""{"calls":[{"tool":"timer","args":{"segundos":"60"}}],"answer":null,"confidence":0.9,"model":"m","extra":1}""")
        assertEquals(LlmDecision(listOf(ToolCall("timer", mapOf("segundos" to "60"))), 0.9, null), d)
        assertEquals("Doze.", CloudProtocol.decode("""{"calls":[],"answer":"Doze.","confidence":0.9}""").answer)
    }

    @Test
    fun `nada pessoal sai do aparelho e tudo volta no lugar`() = runTest {
        var sent = ""
        val cloud = CloudProvider { body ->
            sent = body
            // A nuvem só vê apelidos e responde com eles.
            """{"calls":[{"tool":"whatsapp_preparar","args":{"contato":"contato_1","texto":"te ligo no telefone_1"}}],"confidence":0.9}"""
        }
        val provider = RedactingProvider(cloud) {
            RedactingProvider.entitiesFrom(listOf("João Silva", "Ana Paula"), mapOf("casa" to "Rua das Flores, 120"))
        }
        val d = provider.chooseTools(LlmRequest("diz pro João Silva que te ligo no 11 98765-4321 quando sair da Rua das Flores, 120", tools))

        listOf("João", "98765", "Flores").forEach { assertFalse(it in sent, "vazou $it: $sent") }
        assertTrue("contato_1" in sent && "telefone_1" in sent && "endereco_casa" in sent, sent)
        assertEquals(mapOf("contato" to "João Silva", "texto" to "te ligo no 11 98765-4321"), d.calls.single().args)
    }

    @Test
    fun `assistente fala a resposta de uma pergunta`() = runTest {
        val cloud = CloudProvider { """{"calls":[],"answer":"Quinze por cento de oitenta é doze.","confidence":0.9}""" }
        val out = Assistant(listOf(RuleRouter(), cloud), ToolRegistry(tools)).handle("quanto é 15% de 80", ctx())
        assertEquals(Outcome.Answer("Quinze por cento de oitenta é doze."), out)
    }

    @Test
    fun `regras resolvem sem chamar a nuvem`() = runTest {
        var called = false
        val cloud = CloudProvider { called = true; "{}" }
        val out = Assistant(listOf(RuleRouter(), cloud), ToolRegistry(tools)).handle("timer de 1 minuto", ctx())
        assertIs<Outcome.Done>(out)
        assertFalse(called)
    }

    @Test
    fun `sem internet avisa que a nuvem nao respondeu`() = runTest {
        val cloud = CloudProvider { throw CloudUnavailable("Sem internet.") }
        val out = Assistant(listOf(RuleRouter(), cloud), ToolRegistry(tools)).handle("resume minhas notificações", ctx())
        out as Outcome.Failed
        assertEquals("Não consegui falar com a nuvem. Sem internet.", out.spoken)
    }

    @Test
    fun `chamada invalida da nuvem e recusada pela politica`() = runTest {
        val cloud = CloudProvider { """{"calls":[{"tool":"whatsapp_preparar","args":{"valor":"100"}}],"confidence":0.9}""" }
        val out = Assistant(listOf(cloud), ToolRegistry(tools)).handle("x", ctx())
        assertIs<Outcome.Failed>(out)
    }

    private fun ctx() = ExecContext(
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
    )
}
