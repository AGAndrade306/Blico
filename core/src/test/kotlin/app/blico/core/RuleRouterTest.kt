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
        assertCall("toca Djavan no Spotify", "tocar_musica", "busca" to "Djavan", "app" to "Spotify")
        assertCall("coloca pra tocar Legião Urbana", "tocar_musica", "busca" to "Legião Urbana")
        assertCall("me leva pra casa", "navegar", "destino" to "casa")
        assertCall("como chego no aeroporto de Guarulhos", "navegar", "destino" to "aeroporto de Guarulhos")
    }

    @Test
    fun `musica vai para o app que a pessoa disse`() {
        assertCall("toca skillex no YouTube music", "tocar_musica", "busca" to "skillex", "app" to "YouTube music")
        assertCall("toca Marisa Monte no Deezer", "tocar_musica", "busca" to "Marisa Monte", "app" to "Deezer")
        assertCall("coloca lo-fi pelo Apple Music", "tocar_musica", "busca" to "lo-fi", "app" to "Apple Music")
        assertCall("toca o clipe novo da Anitta no YouTube", "tocar_musica", "busca" to "o clipe novo da Anitta", "app" to "YouTube")
        // Um "no" que não é app fica na busca.
        assertCall("toca Samba no Pé", "tocar_musica", "busca" to "Samba no Pé")
    }

    @Test
    fun `navegacao pelo waze`() {
        assertCall("me leva pra casa pelo Waze", "navegar", "destino" to "casa", "app" to "Waze")
        assertCall("como chego no aeroporto no waze", "navegar", "destino" to "aeroporto", "app" to "waze")
        assertCall("me leva pro trabalho no Maps", "navegar", "destino" to "trabalho")
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
    fun `muletas da fala ficam fora da busca`() {
        assertCall("pesquisa pra mim qual é a capital da Dinamarca", "buscar_web", "consulta" to "qual é a capital da Dinamarca")
        assertCall("pesquisa para mim qual é a capital da Dinamarca", "buscar_web", "consulta" to "qual é a capital da Dinamarca")
        assertCall("pesquisa aí pra mim o resultado do jogo do Flamengo", "buscar_web", "consulta" to "o resultado do jogo do Flamengo")
        assertCall("pesquisa qual é a capital da Dinamarca pra mim", "buscar_web", "consulta" to "qual é a capital da Dinamarca")
        assertCall("Blico, pesquisa no Google pra mim previsão do tempo amanhã, por favor", "buscar_web", "consulta" to "previsão do tempo amanhã")
        assertCall("você pode pesquisar pra mim sobre a história do Brasil?", "buscar_web", "consulta" to "a história do Brasil")
        assertCall("me faz um favor, procura na internet pra mim receita de pão de queijo", "buscar_web", "consulta" to "receita de pão de queijo")
        assertCall("dá uma pesquisada sobre carros elétricos", "buscar_web", "consulta" to "carros elétricos")
        assertCall("busca rapidinho cotação do euro", "buscar_web", "consulta" to "cotação do euro")
        assertCall("quero que você pesquise horário da missa", "buscar_web", "consulta" to "horário da missa")
    }

    @Test
    fun `muletas da fala ficam fora das outras intencoes`() {
        assertCall("liga aí pra mãe", "ligar", "contato" to "mãe")
        assertCall("liga pra mãe pra mim", "ligar", "contato" to "mãe")
        assertCall("pode ligar agora para o João Silva", "ligar", "contato" to "João Silva")
        assertCall("manda aí pro João que eu chego em 10 minutos", "whatsapp_preparar", "contato" to "João", "texto" to "eu chego em 10 minutos")
        assertCall("pede pra mim um uber pra casa", "uber_preparar", "destino" to "casa")
        assertCall("chama aí um 99 pro trabalho", "noventa_e_nove_abrir", "destino" to "trabalho")
        assertCall("toca aí Djavan", "tocar_musica", "busca" to "Djavan")
        assertCall("toca pra mim um pouco de Legião Urbana", "tocar_musica", "busca" to "Legião Urbana")
        assertCall("coloca Djavan pra mim no Spotify", "tocar_musica", "busca" to "Djavan", "app" to "Spotify")
        assertCall("me leva aí pra casa", "navegar", "destino" to "casa")
        assertCall("me acorda aí às 7", "alarme", "hora" to "7", "minuto" to "0")
        assertCall("coloca pra mim um timer de 5 minutos", "timer", "segundos" to "300")
        assertCall("abre aí o WhatsApp", "abrir_app", "nome" to "WhatsApp")
        assertCall("abre o WhatsApp pra mim", "abrir_app", "nome" to "WhatsApp")
        assertCall("procura pra mim o pdf do contrato", "buscar_arquivos", "termo" to "contrato", "tipo" to "pdf")
    }

    @Test
    fun `texto da mensagem fica como foi dito`() {
        assertCall("manda pro João que ele traga o livro pra mim", "whatsapp_preparar", "contato" to "João", "texto" to "ele traga o livro pra mim")
    }

    @Test
    fun `o que nao casa vai para o modelo`() {
        assertNull(router.parse("vê o que chegou de notificação e me diz o que é urgente"))
        assertNull(router.parse("me acorda quando der"))
        assertNull(router.parse(""))
    }
}
