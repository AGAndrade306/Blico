package app.blico.core

import app.blico.core.capability.Availability
import app.blico.core.capability.Capabilities
import app.blico.core.capability.Capability
import app.blico.core.capability.Permission
import app.blico.core.capability.SpeechRule
import app.blico.core.capability.SistemaCapability
import app.blico.core.tools.builtinTools
import app.blico.core.tools.toToolResult
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapabilityTest {
    private class Eco(var availability: Availability = Availability.Ready) : Capability {
        var ran = 0
        override val id = "eco"
        override val title = "Eco"
        override val summary = "Repete"
        override val tools: List<Tool> = listOf(
            object : Tool {
                override val name = "eco"
                override val description = "repete"
                override val parameters = JsonSchema(mapOf("texto" to JsonSchema.Param(description = "texto")), setOf("texto"))
                override val risk = RiskClass.READ
                override val apps = emptySet<String>()
                override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult {
                    ran++
                    return ToolResult.Success(call.args["texto"]!!)
                }
            },
        )
        override val rules = listOf(SpeechRule("^repete{F} (?<texto>.+)$") { m -> ToolCall("eco", mapOf("texto" to m.raw("texto")!!)) })
        override fun availability() = availability
    }

    private val ctx = ExecContext("t", object : DeviceGateway {
        override suspend fun perform(action: DeviceAction) = DeviceResult.Done
    }, object : ContactDirectory {
        override suspend fun find(query: String) = emptyList<Contact>()
    }, object : PlaceBook {
        override fun resolve(alias: String): String? = null
    })

    @Test
    fun `regra da funcionalidade entende com as muletas das regras de base`() {
        val router = RuleRouter(Capabilities(listOf(Eco())).rules())
        assertEquals(ToolCall("eco", mapOf("texto" to "Bom dia")), router.parse("Blico, repete pra mim Bom dia"))
    }

    @Test
    fun `desligada some dos modelos e explica quando pedida`() = runTest {
        val off = mutableSetOf("eco")
        val eco = Eco()
        val caps = Capabilities(listOf(eco)) { off }
        val tool = caps.tools().single()
        assertFalse(tool.exposedToModels)
        val r = tool.run(ToolCall("eco", mapOf("texto" to "oi")), ctx)
        assertIs<ToolResult.Failure>(r)
        assertTrue(r.spoken.contains("desligado"))
        assertEquals(0, eco.ran)

        off.clear()
        assertTrue(tool.exposedToModels)
        assertEquals(ToolResult.Success("oi"), tool.run(ToolCall("eco", mapOf("texto" to "oi")), ctx))
    }

    @Test
    fun `sem suporte some dos modelos, faltando permissao continua oferecida`() = runTest {
        val eco = Eco(Availability.Unsupported("Este aparelho não tem"))
        val tool = Capabilities(listOf(eco)).tools().single()
        assertFalse(tool.exposedToModels)
        assertEquals("Este aparelho não tem", (tool.run(ToolCall("eco", mapOf("texto" to "x")), ctx) as ToolResult.Failure).spoken)

        eco.availability = Availability.NeedsPermission(Permission.WRITE_SETTINGS)
        assertTrue(tool.exposedToModels)
    }

    @Test
    fun `ferramenta de uma funcionalidade passa pela mesma politica de risco`() {
        val registry = ToolRegistry(Capabilities(listOf(Eco())).tools())
        assertEquals(Decision.Execute, RiskPolicy(registry).decide(ToolCall("eco", mapOf("texto" to "oi"))))
        assertIs<Decision.Refuse>(RiskPolicy(registry).decide(ToolCall("eco")))
    }

    @Test
    fun `falta de permissao vira fala com o que liberar`() {
        val r = DeviceResult.NeedsPermission(Permission.DO_NOT_DISTURB).toToolResult("")
        assertIs<ToolResult.Failure>(r)
        assertTrue(r.spoken.contains("Acesso ao Não perturbe"), r.spoken)
    }

    @Test
    fun `nenhuma frase das regras de base muda de destino com as funcionalidades ligadas`() {
        val base = RuleRouter()
        val withCaps = RuleRouter(Capabilities(allCapabilitiesForTest()).rules())
        val phrases = basePhrases() + Benchmark.builtin().map { it.text }
        assertTrue(phrases.size > 100, "achou poucas frases: ${phrases.size}")
        // Estas são das funcionalidades de propósito; as regras de base mandavam para outro lugar.
        val intended = setOf("abre as configurações")
        val changed = phrases.filter { it !in intended && base.parse(it) != withCaps.parse(it) }
            .map { "$it: ${base.parse(it)} → ${withCaps.parse(it)}" }
        assertEquals(emptyList(), changed)
    }

    @Test
    fun `nomes de ferramenta nao colidem com as de base`() {
        ToolRegistry(builtinTools() + Capabilities(allCapabilitiesForTest()).tools())
    }

    companion object {
        fun allCapabilitiesForTest(): List<Capability> = listOf(SistemaCapability())

        /** As frases que os testes do [RuleRouter] conferem, lidas do próprio arquivo de teste. */
        fun basePhrases(): List<String> {
            val src = File("src/test/kotlin/app/blico/core/RuleRouterTest.kt").readText()
            return Regex("(?:assertCall|parse)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(src).map { it.groupValues[1] }.toList()
        }
    }
}
