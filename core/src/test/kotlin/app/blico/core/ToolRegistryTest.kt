package app.blico.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolRegistryTest {
    @Test
    fun `chamada valida nao tem problemas`() {
        val registry = ToolRegistry(listOf(FakeTool("enviar", RiskClass.COMMIT)))
        assertTrue(registry.validate(ToolCall("enviar", mapOf("contato" to "mãe"))).isEmpty())
    }

    @Test
    fun `parametro em branco conta como ausente`() {
        val registry = ToolRegistry(listOf(FakeTool("enviar", RiskClass.COMMIT)))
        assertEquals(1, registry.validate(ToolCall("enviar", mapOf("contato" to " "))).size)
    }

    @Test
    fun `nome duplicado falha`() {
        assertFailsWith<IllegalArgumentException> {
            ToolRegistry(listOf(FakeTool("a", RiskClass.READ), FakeTool("a", RiskClass.READ)))
        }
    }
}
