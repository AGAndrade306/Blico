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

    @Test
    fun `mascara documentos, cartao, cep e chave pix`() {
        val input = "Meu CPF é 529.982.247-25, o CNPJ da firma 11.222.333/0001-81, " +
            "cartão 4111 1111 1111 1111, CEP 01310-100 e a chave 123e4567-e89b-42d3-a456-426614174000."
        val r = redactor.redact(input)
        assertEquals(
            "Meu CPF é cpf_1, o CNPJ da firma cnpj_1, cartão cartao_1, CEP cep_1 e a chave chave_pix_1.",
            r.text,
        )
        assertEquals(input, redactor.restore(r.text, r.mapping))
    }

    @Test
    fun `documentos sem pontuacao so com digito verificador valido`() {
        assertEquals("cpf_1 e cnpj_1", redactor.redact("52998224725 e 11222333000181").text)
        // Mesmo tamanho, dígito errado: é só um número (um pedido, um protocolo).
        assertEquals("pedido 12345678900", redactor.redact("pedido 12345678900").text)
        assertEquals("cartão 4111111111111112", redactor.redact("cartão 4111111111111112").text)
    }

    @Test
    fun `numeros comuns ficam como estao`() {
        listOf("alarme às 06:30", "timer de 25 minutos", "reunião em 2026", "nota 9.5").forEach {
            assertEquals(it, redactor.redact(it).text)
        }
    }

    @Test
    fun `telefone continua telefone ao lado dos documentos`() {
        val r = redactor.redact("CPF 529.982.247-25, fone (11) 98765-4321")
        assertEquals("CPF cpf_1, fone telefone_1", r.text)
    }
}
