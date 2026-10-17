package app.blico.core

import app.blico.core.tools.builtinTools
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuiltinToolsTest {
    private class FakeDevice : DeviceGateway {
        val actions = mutableListOf<DeviceAction>()
        override suspend fun perform(action: DeviceAction): DeviceResult {
            actions += action
            return DeviceResult.Done
        }
    }

    private val device = FakeDevice()
    private val contacts = object : ContactDirectory {
        override suspend fun find(query: String) = when (query.lowercase()) {
            "joão" -> listOf(Contact("João Silva", "(11) 98765-4321"))
            "ana" -> listOf(Contact("Ana Paula", "11 91111-1111"), Contact("Ana Lima", "21 92222-2222"))
            else -> emptyList()
        }
    }
    private val places = object : PlaceBook {
        override fun resolve(alias: String) = if (alias.lowercase() == "casa") "Rua das Flores, 120, São Paulo" else null
    }
    private val ctx = ExecContext("t", device, contacts, places)
    private val registry = ToolRegistry(builtinTools())

    private suspend fun run(tool: String, vararg args: Pair<String, String>): ToolResult {
        val call = ToolCall(tool, mapOf(*args))
        assertTrue(registry.validate(call).isEmpty(), "chamada inválida: ${registry.validate(call)}")
        return registry[tool]!!.run(call, ctx)
    }

    @Test
    fun `whatsapp abre a conversa com numero e texto codificados`() = runTest {
        val r = run("whatsapp_preparar", "contato" to "João", "texto" to "chego já & tal")
        assertIs<ToolResult.Success>(r)
        assertEquals(
            DeviceAction.OpenUri("https://api.whatsapp.com/send?phone=5511987654321&text=chego%20j%C3%A1%20%26%20tal", "com.whatsapp"),
            device.actions.single(),
        )
    }

    @Test
    fun `contato ambiguo pede escolha com o telefone ja preenchido`() = runTest {
        val r = run("whatsapp_preparar", "contato" to "Ana", "texto" to "oi")
        r as ToolResult.NeedsChoice
        assertEquals(listOf("Ana Paula", "Ana Lima"), r.options.map { it.label })
        assertEquals("21 92222-2222", r.options[1].call.args["telefone"])
        assertTrue(device.actions.isEmpty())
    }

    @Test
    fun `contato desconhecido compartilha o texto no whatsapp`() = runTest {
        run("whatsapp_preparar", "contato" to "Zé", "texto" to "oi")
        assertEquals(DeviceAction.ShareText("oi", "com.whatsapp"), device.actions.single())
    }

    @Test
    fun `uber resolve o apelido do lugar`() = runTest {
        run("uber_preparar", "destino" to "casa")
        val uri = (device.actions.single() as DeviceAction.OpenUri).uri
        assertTrue(uri.endsWith("dropoff%5Bformatted_address%5D=Rua%20das%20Flores%2C%20120%2C%20S%C3%A3o%20Paulo"), uri)
    }

    @Test
    fun `apelido sem endereco cadastrado avisa em vez de abrir`() = runTest {
        val r = run("uber_preparar", "destino" to "trabalho")
        assertIs<ToolResult.Failure>(r)
        assertTrue(device.actions.isEmpty())
    }

    @Test
    fun `busca vai para o DuckDuckGo`() = runTest {
        run("buscar_web", "consulta" to "preço do dólar & euro")
        val search = device.actions.single() as DeviceAction.WebSearch
        assertEquals("https://duckduckgo.com/?q=pre%C3%A7o%20do%20d%C3%B3lar%20%26%20euro", search.url)
    }

    @Test
    fun `alarme timer e ligar`() = runTest {
        run("alarme", "hora" to "6", "minuto" to "30")
        run("timer", "segundos" to "300")
        run("ligar", "contato" to "João")
        assertEquals(
            listOf(DeviceAction.SetAlarm(6, 30), DeviceAction.SetTimer(300), DeviceAction.Dial("(11) 98765-4321")),
            device.actions,
        )
    }

    @Test
    fun `toda chamada do roteador e valida para o registro e passa na politica`() {
        val router = RuleRouter()
        val policy = RiskPolicy(registry)
        listOf(
            "manda pro João que já vou", "liga pra mãe", "pede um uber pra casa", "toca Djavan",
            "me leva pro aeroporto", "me acorda às 7", "timer de 5 minutos", "pesquisa clima", "abre a câmera",
            "chama um 99", "pede comida no ifood",
        ).forEach { frase ->
            val call = router.parse(frase)!!
            assertEquals(Decision.Execute, policy.decide(call), "frase: $frase")
        }
    }
}
