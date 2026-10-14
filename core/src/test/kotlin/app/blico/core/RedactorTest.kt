package app.blico.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RedactorTest {
    private val redactor = Redactor(
        known = mapOf(
            "João Silva" to "contato_1",
            "João" to "contato_2",
            "Rua das Flores, 120" to "endereco_casa",
        ),
    )

    @Test
    fun `mascara contatos, enderecos, telefones e emails`() {
        val input = "Manda pro João Silva que estou na Rua das Flores, 120. " +
            "Liga no (11) 98765-4321 ou escreve pra ana@exemplo.com.br"
        val r = redactor.redact(input)

        listOf("João", "Flores", "98765", "ana@").forEach { assertFalse(it in r.text, "vazou: $it em ${r.text}") }
        assertEquals(
            "Manda pro contato_1 que estou na endereco_casa. Liga no telefone_1 ou escreve pra email_1",
            r.text,
        )
    }

    @Test
    fun `nome curto nao quebra o nome longo`() {
        val r = redactor.redact("João e João Silva")
        assertEquals("contato_2 e contato_1", r.text)
    }

    @Test
    fun `nome dentro de outra palavra nao e trocado`() {
        assertEquals("Joãozinho", redactor.redact("Joãozinho").text)
    }

    @Test
    fun `mesmo telefone repetido usa o mesmo apelido`() {
        val r = redactor.redact("11 98765-4321 e de novo 11 98765-4321")
        assertEquals("telefone_1 e de novo telefone_1", r.text)
    }

    @Test
    fun `restaura na volta`() {
        val input = "Avisa o João Silva no 98765-4321"
        val r = redactor.redact(input)
        assertEquals(input, redactor.restore(r.text, r.mapping))
    }
}
