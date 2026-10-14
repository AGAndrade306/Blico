package app.blico.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RiskPolicyTest {
    private val registry = ToolRegistry(
        listOf(
            FakeTool("abrir", RiskClass.READ),
            FakeTool("preparar", RiskClass.PREPARE),
            FakeTool("enviar", RiskClass.COMMIT),
            FakeTool("pagar", RiskClass.SENSITIVE),
            FakeTool("banco", RiskClass.READ, apps = setOf("com.nu.production")),
        ),
    )
    private val policy = RiskPolicy(registry)

    @Test
    fun `leitura e preparo executam direto`() {
        assertEquals(Decision.Execute, policy.decide(ToolCall("abrir", mapOf("contato" to "mãe"))))
        assertEquals(Decision.Execute, policy.decide(ToolCall("preparar", mapOf("contato" to "mãe"))))
    }

    @Test
    fun `compromisso sempre pede confirmacao`() {
        val decision = policy.decide(ToolCall("enviar", mapOf("contato" to "mãe", "texto" to "chego já")))
        assertIs<Decision.Confirm>(decision)
    }

    @Test
    fun `cartao vem da chamada estruturada e nao do texto do modelo`() {
        val decision = policy.decide(ToolCall("enviar", mapOf("texto" to "oi", "contato" to "mãe")))
        decision as Decision.Confirm
        assertEquals("enviar", decision.summary.title)
        assertEquals(listOf("contato" to "mãe", "texto" to "oi"), decision.summary.fields)
    }

    @Test
    fun `sensivel e recusado por padrao`() {
        assertIs<Decision.Refuse>(policy.decide(ToolCall("pagar", mapOf("contato" to "x"))))
    }

    @Test
    fun `app de banco e recusado mesmo em leitura`() {
        val decision = policy.decide(ToolCall("banco", mapOf("contato" to "x")))
        decision as Decision.Refuse
        assertTrue("com.nu.production" in decision.reason)
    }

    @Test
    fun `ferramenta desconhecida e recusada`() {
        assertIs<Decision.Refuse>(policy.decide(ToolCall("apagar_tudo")))
    }

    @Test
    fun `parametro obrigatorio ausente ou desconhecido e recusado`() {
        assertIs<Decision.Refuse>(policy.decide(ToolCall("enviar", mapOf("texto" to "oi"))))
        assertIs<Decision.Refuse>(policy.decide(ToolCall("enviar", mapOf("contato" to "mãe", "valor" to "100"))))
    }
}
