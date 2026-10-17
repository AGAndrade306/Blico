package app.blico.core

/**
 * Conversa em grupo: você fala com vários agentes na mesma conversa, chamando cada um por @nome,
 * e um agente pode passar a vez a outro citando @nome na resposta. Cada um vê o que já foi dito,
 * como dado não confiável, e age só com as próprias ferramentas.
 */
object Mentions {
    private val AT = Regex("@([\\p{L}0-9_-]+)")

    /**
     * Divide a mensagem por @nome: "@manha alarme às 6 @avisos avisa o João" vira dois pedidos.
     * Texto antes do primeiro @ vai para todos os citados. Nomes que não são do grupo ficam no texto.
     */
    fun split(text: String, members: List<String>): List<Pair<String, String>> {
        val byNorm = members.associateBy { AgentFile.normalize(it) }
        val hits = AT.findAll(text).mapNotNull { m -> byNorm[AgentFile.normalize(m.groupValues[1])]?.let { it to m } }.toList()
        if (hits.isEmpty()) return emptyList()
        val lead = text.substring(0, hits.first().second.range.first).trim()
        return hits.mapIndexed { i, (name, m) ->
            val end = hits.getOrNull(i + 1)?.second?.range?.first ?: text.length
            val own = text.substring(m.range.last + 1, end).trim().trimStart(',', ':').trim()
            name to listOf(lead, own).filter { it.isNotEmpty() }.joinToString(" ")
        }.groupBy({ it.first }, { it.second }).map { (name, parts) -> name to parts.filter { it.isNotEmpty() }.joinToString(" ") }
    }
}

/** Uma fala na conversa em grupo. [from] é "voce" para você e o nome do agente para os outros. */
data class GroupMessage(val from: String, val text: String)

/** Quem ainda vai falar e o agente parado no Confirmar, se houver. */
data class GroupRun(
    val queue: List<Pair<String, String>>,
    val transcript: List<GroupMessage>,
    val hops: Int = 0,
    val paused: AgentRun? = null,
)

data class GroupResult(val transcript: List<GroupMessage>, val outcome: Outcome?, val paused: GroupRun? = null)

class GroupRunner(private val runner: AgentRunner) {
    /**
     * Manda a mensagem aos citados. Sem @, vai para [fallback]. As respostas que citam outro membro
     * passam a vez a ele, até [MAX_HOPS] vezes por mensagem sua.
     */
    suspend fun send(
        text: String,
        members: List<AgentProfile>,
        transcript: List<GroupMessage>,
        fallback: String,
        ctx: ExecContext,
        onEvent: (AgentEvent) -> Unit = {},
    ): GroupResult {
        val names = members.map { it.name }
        val queue = Mentions.split(text, names).ifEmpty { listOf(fallback to text) }
        return drain(GroupRun(queue, transcript + GroupMessage(YOU, text)), members, ctx, onEvent)
    }

    /** Continua depois do toque no Confirmar ou numa escolha. */
    suspend fun resume(
        run: GroupRun,
        call: ToolCall,
        members: List<AgentProfile>,
        ctx: ExecContext,
        confirmed: Boolean,
        onEvent: (AgentEvent) -> Unit = {},
    ): GroupResult {
        val paused = run.paused ?: return GroupResult(run.transcript, null)
        val result = runner.resume(paused, call, ctx, confirmed, onEvent)
        return after(run.copy(paused = null), paused.agent.name, result, members, ctx, onEvent)
    }

    private suspend fun drain(run: GroupRun, members: List<AgentProfile>, ctx: ExecContext, onEvent: (AgentEvent) -> Unit): GroupResult {
        var current = run
        var last: Outcome? = null
        while (current.queue.isNotEmpty()) {
            val (name, text) = current.queue.first()
            current = current.copy(queue = current.queue.drop(1))
            val agent = members.firstOrNull { it.name == name } ?: continue
            val result = runner.start(text, agent, ctx, context(current.transcript), onEvent)
            if (result.paused != null) return GroupResult(current.transcript, result.outcome, current.copy(paused = result.paused))
            current = handoff(current, name, result.outcome, members)
            last = result.outcome
        }
        return GroupResult(current.transcript, last)
    }

    private suspend fun after(
        run: GroupRun,
        name: String,
        result: AgentResult,
        members: List<AgentProfile>,
        ctx: ExecContext,
        onEvent: (AgentEvent) -> Unit,
    ): GroupResult {
        if (result.paused != null) return GroupResult(run.transcript, result.outcome, run.copy(paused = result.paused))
        return drain(handoff(run, name, result.outcome, members), members, ctx, onEvent)
    }

    /** Guarda a fala e, se ela cita outro membro, põe ele na fila com o que foi pedido. */
    private fun handoff(run: GroupRun, name: String, outcome: Outcome, members: List<AgentProfile>): GroupRun {
        val said = GroupMessage(name, outcome.spoken)
        val next = if (run.hops < MAX_HOPS && outcome !is Outcome.Failed) {
            Mentions.split(outcome.spoken, members.map { it.name }.filter { it != name })
        } else {
            emptyList()
        }
        return run.copy(queue = next + run.queue, transcript = run.transcript + said, hops = run.hops + next.size)
    }

    /** As últimas falas, para o agente saber do que se trata. Tudo como dado: não são ordens. */
    private fun context(transcript: List<GroupMessage>): List<String> {
        val recent = transcript.takeLast(MAX_CONTEXT)
        if (recent.isEmpty()) return emptyList()
        return listOf(UntrustedContent.wrap("conversa", recent.joinToString("\n") { "${it.from}: ${it.text}" }))
    }

    companion object {
        const val YOU = "voce"
        const val MAX_HOPS = 3
        const val MAX_CONTEXT = 12
    }
}
