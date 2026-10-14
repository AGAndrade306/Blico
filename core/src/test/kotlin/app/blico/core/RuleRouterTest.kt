package app.blico.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuleRouterTest {
    private val router = RuleRouter()

    private fun assertCall(text: String, tool: String, vararg args: Pair<String, String>) =
        assertEquals(ToolCall(tool, mapOf(*args)), router.parse(text), "frase: $text")

    @Test
    fun `mensagem com texto preserva maiusculas e acentos`() {
        assertCall("Manda pro João que eu chego em 10 minutos", "whatsapp_preparar", "contato" to "João", "texto" to "eu chego em 10 minutos")
        assertCall("manda mensagem para a Mãe dizendo que já saí", "whatsapp_preparar", "contato" to "Mãe", "texto" to "já saí")
        assertCall("avisa o Pedro que vou atrasar", "whatsapp_preparar", "contato" to "Pedro", "texto" to "vou atrasar")
        assertCall("Blico, manda um zap pra Ana: tô chegando.", "whatsapp_preparar", "contato" to "Ana", "texto" to "tô chegando")
    }

    @Test
    fun `mensagem sem texto`() {
        assertCall("manda mensagem pro Carlos", "whatsapp_preparar", "contato" to "Carlos")
    }

    @Test
    fun `ligar`() {
        assertCall("liga pra mãe", "ligar", "contato" to "mãe")
        assertCall("Ligar para o João Silva", "ligar", "contato" to "João Silva")
    }

    @Test
    fun `uber 99 e ifood`() {
        assertCall("pede um uber pra casa", "uber_preparar", "destino" to "casa")
        assertCall("chama um Uber para o Shopping Iguatemi", "uber_preparar", "destino" to "Shopping Iguatemi")
        assertCall("pede um uber", "abrir_app", "nome" to "Uber")
        assertCall("chama um 99 pro trabalho", "noventa_e_nove_abrir", "destino" to "trabalho")
        assertCall("pede uma pizza no iFood", "ifood_abrir", "busca" to "uma pizza")
    }

    @Test
    fun `musica e navegacao`() {
        assertCall("toca Djavan no Spotify", "spotify_tocar", "busca" to "Djavan")
        assertCall("coloca pra tocar Legião Urbana", "spotify_tocar", "busca" to "Legião Urbana")
        assertCall("me leva pra casa", "navegar", "destino" to "casa")
        assertCall("como chego no aeroporto de Guarulhos", "navegar", "destino" to "aeroporto de Guarulhos")
    }

    @Test
    fun `alarme e timer`() {
        assertCall("me acorda às 7", "alarme", "hora" to "7", "minuto" to "0")
        assertCall("alarme para 6 e meia", "alarme", "hora" to "6", "minuto" to "30")
        assertCall("coloca um despertador pras 7h30", "alarme", "hora" to "7", "minuto" to "30")
        assertCall("me acorda às oito da noite", "alarme", "hora" to "20", "minuto" to "0")
        assertCall("timer de 5 minutos", "timer", "segundos" to "300")
        assertCall("cronômetro de meia hora", "timer", "segundos" to "1800")
        assertCall("marca uma hora e meia", "timer", "segundos" to "5400")
        assertCall("coloca um timer de 10 minutos", "timer", "segundos" to "600")
    }

    @Test
    fun `busca e abrir app`() {
        assertCall("pesquisa preço do dólar", "buscar_web", "consulta" to "preço do dólar")
        assertCall("procura na internet receita de bolo de cenoura", "buscar_web", "consulta" to "receita de bolo de cenoura")
        assertCall("abre o WhatsApp", "abrir_app", "nome" to "WhatsApp")
        assertCall("Pode abrir a câmera, por favor?", "abrir_app", "nome" to "câmera")
    }

    @Test
    fun `o que nao casa vai para o modelo`() {
        assertNull(router.parse("vê o que chegou de notificação e me diz o que é urgente"))
        assertNull(router.parse("me acorda quando der"))
        assertNull(router.parse(""))
    }
}
