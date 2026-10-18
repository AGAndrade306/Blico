package app.blico.core

import app.blico.core.capability.Capabilities
import app.blico.core.capability.Fact
import app.blico.core.capability.InMemoryStore
import app.blico.core.capability.MemoryAwareProvider
import app.blico.core.capability.MemoryText
import app.blico.core.capability.MemoriaCapability
import app.blico.core.capability.SistemaCapability
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoriaTest {
    private val store = InMemoryStore()
    private val caps = Capabilities(listOf(SistemaCapability(), MemoriaCapability(store)))
    private val router = RuleRouter(caps.rules())
    private val registry = ToolRegistry(caps.tools())
    private val assistant = Assistant(listOf(router), registry)
    private val ctx = ExecContext("t", object : DeviceGateway {
        override suspend fun perform(action: DeviceAction) = DeviceResult.Done
    }, object : ContactDirectory {
        override suspend fun find(query: String) = emptyList<Contact>()
    }, object : PlaceBook {
        override fun resolve(alias: String): String? = null
    })

    private fun assertCall(text: String, tool: String, vararg args: Pair<String, String>) =
        assertEquals(ToolCall(tool, mapOf(*args)), router.parse(text), "frase: $text")

    @Test
    fun `frases de memoria`() {
        assertCall("lembra que a Ana é minha irmã", "memoria_guardar", "fato" to "A Ana é minha irmã")
        assertCall("Blico, guarda aí que eu prefiro corredor no avião", "memoria_guardar", "fato" to "Eu prefiro corredor no avião")
        assertCall("o que você sabe sobre a Ana?", "memoria_consultar", "assunto" to "a Ana")
        assertCall("o que você lembra de mim", "memoria_consultar", "assunto" to "tudo")
        assertCall("mostra minhas memórias", "memoria_consultar", "assunto" to "tudo")
        assertCall("esquece que eu moro em Pinheiros", "memoria_esquecer", "assunto" to "eu moro em Pinheiros")
        assertCall("apaga da memória o endereço antigo", "memoria_esquecer", "assunto" to "o endereço antigo")
        // Lembrete é outra coisa, e "apaga a lanterna" continua sendo a lanterna.
        assertEquals(null, router.parse("me lembra de ligar pro médico")?.tool?.takeIf { it.startsWith("memoria") })
        assertCall("apaga a lanterna", "lanterna", "estado" to "desligar")
    }

    @Test
    fun `guardar passa pelo Confirmar com o texto exato e marca o sensivel`() = runTest {
        val out = assistant.handle("lembra que meu CPF é 123.456.789-09", ctx)
        assertIs<Outcome.Confirm>(out)
        assertEquals("Guardar na memória", out.summary.title)
        assertTrue(out.summary.fields.any { it.first == "Atenção" })
        assertTrue(store.all().isEmpty())

        assertIs<Outcome.Done>(assistant.execute(out.call, ctx, confirmed = true))
        assertTrue(store.all().single().sensitive)
    }

    @Test
    fun `consultar acha pelas palavras e diz quando nao sabe`() = runTest {
        assertEquals("Ainda não guardei nada. Diga \"lembra que…\".", (assistant.handle("o que você sabe sobre a Ana", ctx) as Outcome.Answer).spoken)
        store.add("A Ana é minha irmã", false)
        store.add("O dentista é na Rua Augusta", false)
        assertEquals("A Ana é minha irmã.", (assistant.handle("o que você sabe sobre a Ana", ctx) as Outcome.Answer).spoken)
        assertEquals("Não tenho nada guardado sobre o Pedro.", (assistant.handle("o que você sabe sobre o Pedro", ctx) as Outcome.Answer).spoken)
        assertTrue(assistant.readsPrivate("memoria_consultar"))
    }

    @Test
    fun `esquecer acha, confirma e apaga, e com varios pergunta qual`() = runTest {
        store.add("Eu moro em Pinheiros", false)
        val out = assistant.handle("esquece que eu moro em Pinheiros", ctx)
        assertIs<Outcome.Confirm>(out)
        assertEquals(listOf("Fato" to "Eu moro em Pinheiros"), out.summary.fields)
        assertIs<Outcome.Done>(assistant.execute(out.call, ctx, confirmed = true))
        assertTrue(store.all().isEmpty())

        store.add("O João gosta de café", false)
        store.add("O João é do financeiro", false)
        val choose = assistant.handle("esquece o que você sabe sobre o João", ctx)
        assertIs<Outcome.Choose>(choose)
        assertEquals(2, choose.options.size)
        assertIs<Outcome.Confirm>(assistant.execute(choose.options[0].call, ctx))
        assertFalse(registry["memoria_apagar"]!!.exposedToModels)
    }

    @Test
    fun `modelo do celular recebe os fatos ligados ao pedido, como contexto`() = runTest {
        store.add("A Ana é minha irmã", false)
        store.add("O dentista é na Rua Augusta", false)
        var seen: LlmRequest? = null
        val local = object : LlmProvider {
            override val id = "local"
            override suspend fun chooseTools(request: LlmRequest): LlmDecision {
                seen = request
                return LlmDecision(emptyList(), 0.0)
            }
        }
        MemoryAwareProvider(local, store).chooseTools(LlmRequest("manda mensagem pra minha irmã", emptyList()))
        assertEquals(1, seen!!.context.size)
        assertTrue(seen!!.context[0].contains("A Ana é minha irmã") && !seen!!.context[0].contains("dentista"))
        assertTrue(LocalBrain.userMessage(seen!!).contains("A Ana é minha irmã"))

        MemoryAwareProvider(local, store) { false }.chooseTools(LlmRequest("manda mensagem pra minha irmã", emptyList()))
        assertTrue(seen!!.context.isEmpty())
    }

    @Test
    fun `sensivel`() {
        assertTrue(MemoryText.isSensitive("a senha do banco é 1234"))
        assertTrue(MemoryText.isSensitive("o telefone do João é 11 98765-4321"))
        assertTrue(MemoryText.isSensitive("tomo remédio para pressão"))
        assertFalse(MemoryText.isSensitive("a Ana é minha irmã"))
    }

    @Test
    fun `busca ignora palavras vazias`() {
        val facts = listOf(Fact("1", "A Ana é minha irmã", 1), Fact("2", "Prefiro café sem açúcar", 2))
        assertEquals(listOf("1"), MemoryText.search(facts, "quem é a minha irmã").map { it.id })
        assertEquals(emptyList(), MemoryText.search(facts, "o que é"))
    }
}
