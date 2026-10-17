package app.blico.core

import java.text.Normalizer

/**
 * Agentes como arquivos Markdown, no estilo das skills do OpenClaw e do Hermes: um cabeçalho
 * curto entre linhas `---` e, abaixo, as instruções em texto livre.
 *
 * ```
 * ---
 * nome: manha
 * descricao: Prepara a manhã
 * gatilhos: [bom dia, prepara a manhã]
 * ferramentas: [alarme, timer]
 * max_passos: 4
 * equipe: [avisos]
 * ---
 * Ponha o alarme e o timer que eu pedir e diga em uma frase o que ficou pronto.
 * ```
 */
object AgentFile {
    private val NAME = Regex("[a-z0-9_-]{1,40}")
    private val KEYS = setOf("nome", "descricao", "gatilhos", "ferramentas", "max_passos", "equipe")

    /** Lê um arquivo. Erros viram [IllegalArgumentException] com a frase que a tela mostra. */
    fun parse(text: String): AgentProfile {
        val lines = text.replace("\r\n", "\n").trimStart().lines()
        require(lines.firstOrNull()?.trim() == "---") { "O arquivo precisa começar com uma linha ---" }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        require(end >= 0) { "Falta a linha --- que fecha o cabeçalho" }
        val header = lines.subList(1, end + 1)
        val body = lines.drop(end + 2).joinToString("\n").trim()

        val fields = linkedMapOf<String, String>()
        header.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.forEach { line ->
            val key = line.substringBefore(':', "").trim()
            require(key.isNotEmpty() && line.contains(':')) { "Linha sem chave: $line" }
            require(key in KEYS) { "Campo desconhecido: $key" }
            fields[key] = line.substringAfter(':').trim()
        }

        val name = fields["nome"].orEmpty()
        require(NAME.matches(name)) { "nome precisa ter só letras minúsculas sem acento, números, - ou _" }
        require(body.isNotEmpty()) { "Faltam as instruções abaixo do cabeçalho" }
        val maxSteps = fields["max_passos"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("max_passos precisa ser um número") }
        return AgentProfile(
            name = name,
            instructions = body,
            tools = list(fields["ferramentas"]).toSet(),
            maxSteps = maxSteps ?: AgentProfile.DEFAULT_MAX_STEPS,
            description = fields["descricao"].orEmpty(),
            triggers = list(fields["gatilhos"]),
            team = list(fields["equipe"]),
        )
    }

    /** `[a, b]` ou `a, b`; aspas em volta de cada item são tiradas. */
    private fun list(value: String?): List<String> =
        value.orEmpty().trim().removePrefix("[").removeSuffix("]")
            .split(',')
            .map { it.trim().trim('"', '\'').trim() }
            .filter { it.isNotEmpty() }

    /** O texto do arquivo para um agente, no mesmo formato que [parse] lê. Usado pelo editor de campos. */
    fun render(agent: AgentProfile): String = buildString {
        append("---\n")
        append("nome: ").append(agent.name).append('\n')
        if (agent.description.isNotBlank()) append("descricao: ").append(agent.description.trim()).append('\n')
        if (agent.triggers.isNotEmpty()) append("gatilhos: ").append(agent.triggers.joinToString(", ", "[", "]")).append('\n')
        if (agent.tools.isNotEmpty()) append("ferramentas: ").append(agent.tools.sorted().joinToString(", ", "[", "]")).append('\n')
        if (agent.team.isNotEmpty()) append("equipe: ").append(agent.team.joinToString(", ", "[", "]")).append('\n')
        append("max_passos: ").append(agent.maxSteps).append('\n')
        append("---\n")
        append(agent.instructions.trim()).append('\n')
    }

    /** Membros da equipe que não existem entre os agentes. */
    fun unknownMembers(agent: AgentProfile, all: List<AgentProfile>): List<String> = agent.team.filter { m -> all.none { it.name == m } }

    /** Ferramentas que o agente lista e que não existem no catálogo. */
    fun unknownTools(agent: AgentProfile, registry: ToolRegistry): List<String> = agent.tools.filter { registry[it] == null }.sorted()

    /**
     * Qual agente o pedido chama: "agente manhã, ..." ou "usa o agente manhã ..." pelo nome,
     * ou um gatilho dito no pedido. Devolve o agente e o pedido sem o chamado pelo nome.
     */
    fun match(text: String, agents: List<AgentProfile>): Pair<AgentProfile, String>? {
        val norm = normalize(text)
        val byName = Regex("^(?:usa(?:r)?\\s+o\\s+|chama\\s+o\\s+)?agente\\s+([a-z0-9_-]+)[\\s,.:]*(.*)$").find(norm)
        if (byName != null) {
            val wanted = byName.groupValues[1]
            agents.firstOrNull { normalize(it.name) == wanted }?.let { agent ->
                // O resto do pedido sai do texto original, para manter maiúsculas e acentos.
                val words = text.trim().split(Regex("\\s+"))
                val at = words.indexOfFirst { normalize(it).trim(',', '.', ':') == wanted }
                val rest = words.drop(at + 1).joinToString(" ").trimStart(',', '.', ':', ' ')
                return agent to rest.ifEmpty { agent.description.ifEmpty { agent.name } }
            }
        }
        for (agent in agents) {
            val hit = agent.triggers.map(::normalize).filter { it.isNotEmpty() }.any { t ->
                Regex("(?<![\\p{L}\\d])${Regex.escape(t)}(?![\\p{L}\\d])").containsMatchIn(norm)
            }
            if (hit) return agent to text.trim()
        }
        return null
    }

    /** Minúsculas, sem acento e com espaços simples, para "Manhã" casar com "manha". */
    internal fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Os agentes que o Blico cria na primeira vez, para servir de exemplo. */
    val EXAMPLES: List<Pair<String, String>> = listOf(
        "manha" to """
            ---
            nome: manha
            descricao: Prepara a manhã com alarme e timer
            gatilhos: [prepara a manhã, rotina da manhã]
            ferramentas: [alarme, timer]
            max_passos: 4
            ---
            Ponha os alarmes e timers que eu pedir, um de cada vez.
            Se eu não disser a hora, use 7h. No fim, diga em uma frase o que ficou pronto.
        """.trimIndent(),
        "organizador" to """
            ---
            nome: organizador
            descricao: Coordena a manhã e os avisos, pedindo a cada agente a sua parte
            gatilhos: [organiza meu dia]
            ferramentas: [navegar]
            equipe: [manha, avisos]
            max_passos: 6
            ---
            Divida o pedido em partes e peça cada parte ao agente certo da equipe:
            alarmes e timers para manha, mensagens para avisos.
            No fim, diga em uma frase o que cada um fez.
        """.trimIndent(),
        "avisos" to """
            ---
            nome: avisos
            descricao: Avisa as pessoas pelo WhatsApp
            gatilhos: [avisa todo mundo, avisa a turma]
            ferramentas: [whatsapp_preparar]
            max_passos: 5
            ---
            Mande a mesma mensagem para cada pessoa que eu citar, uma de cada vez.
            Não invente pessoas nem números. No fim, diga para quem ficou pronto.
        """.trimIndent(),
    )
}
