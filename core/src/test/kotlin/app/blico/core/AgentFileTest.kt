package app.blico.core

import app.blico.core.tools.builtinTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentFileTest {
    private val file = """
        ---
        nome: manha
        descricao: Prepara a manhã
        gatilhos: [prepara a manhã, "rotina da manhã"]
        ferramentas: [alarme, timer]
        max_passos: 4
        ---
        Ponha o alarme.
        Diga o que ficou pronto.
    """.trimIndent()

    @Test
    fun `le cabecalho e instrucoes`() {
        val a = AgentFile.parse(file)
        assertEquals("manha", a.name)
        assertEquals("Prepara a manhã", a.description)
        assertEquals(listOf("prepara a manhã", "rotina da manhã"), a.triggers)
        assertEquals(setOf("alarme", "timer"), a.tools)
        assertEquals(4, a.maxSteps)
        assertEquals("Ponha o alarme.\nDiga o que ficou pronto.", a.instructions)
    }

    @Test
    fun `campos opcionais tem padrao`() {
        val a = AgentFile.parse("---\nnome: simples\n---\nFaça o que eu pedir.")
        assertEquals(emptySet(), a.tools)
        assertEquals(AgentProfile.DEFAULT_MAX_STEPS, a.maxSteps)
        assertEquals(emptyList(), a.triggers)
    }

    @Test
    fun `erros dizem o que corrigir`() {
        fun msg(text: String) = assertFailsWith<IllegalArgumentException> { AgentFile.parse(text) }.message.orEmpty()
        assertTrue("começar" in msg("nome: x\n---\noi"))
        assertTrue("fecha" in msg("---\nnome: x\noi"))
        assertTrue("nome" in msg("---\nnome: Manhã\n---\noi"))
        assertTrue("instruções" in msg("---\nnome: x\n---\n"))
        assertTrue("desconhecido" in msg("---\nnome: x\nmodelo: grande\n---\noi"))
        assertTrue("número" in msg("---\nnome: x\nmax_passos: muitos\n---\noi"))
        assertTrue("entre" in msg("---\nnome: x\nmax_passos: 50\n---\noi"))
    }

    @Test
    fun `exemplos sao validos e so usam ferramentas que existem`() {
        val registry = ToolRegistry(builtinTools())
        AgentFile.EXAMPLES.forEach { (name, text) ->
            val a = AgentFile.parse(text)
            assertEquals(name, a.name)
            assertEquals(emptyList(), AgentFile.unknownTools(a, registry))
        }
    }

    @Test
    fun `ferramenta inexistente aparece como aviso`() {
        val a = AgentFile.parse("---\nnome: x\nferramentas: [timer, voar]\n---\noi")
        assertEquals(listOf("voar"), AgentFile.unknownTools(a, ToolRegistry(builtinTools())))
    }

    private val agents = AgentFile.EXAMPLES.map { AgentFile.parse(it.second) }

    @Test
    fun `chamado pelo nome, com ou sem acento`() {
        val (a, rest) = AgentFile.match("Agente Manhã, alarme às 6 e timer de 10 minutos", agents)!!
        assertEquals("manha", a.name)
        assertEquals("alarme às 6 e timer de 10 minutos", rest)
        assertEquals("avisos", AgentFile.match("usa o agente avisos: diz pra Ana e pro João que atraso", agents)!!.first.name)
    }

    @Test
    fun `nome sem pedido usa a descricao`() {
        assertEquals("Prepara a manhã com alarme e timer", AgentFile.match("agente manha", agents)!!.second)
    }

    @Test
    fun `chamado por gatilho, com o pedido inteiro`() {
        val (a, rest) = AgentFile.match("Prepara a manha pra mim", agents)!!
        assertEquals("manha", a.name)
        assertEquals("Prepara a manha pra mim", rest)
    }

    @Test
    fun `pedido comum nao chama agente`() {
        assertNull(AgentFile.match("timer de 5 minutos", agents))
        assertNull(AgentFile.match("agente secreto, faz tudo", agents))
        // Gatilho só como palavra inteira.
        assertNull(AgentFile.match("avisa todo mundox", agents))
    }
}
