package app.blico.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.blico.BlicoApp
import app.blico.core.AgentEvent
import app.blico.core.AgentProfile
import app.blico.core.AgentResult
import app.blico.core.AgentRun
import app.blico.core.Outcome
import app.blico.core.ToolCall
import app.blico.ui.BlicoAvatar
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoLink
import app.blico.ui.LocalBlico
import app.blico.ui.avatarColor
import kotlinx.coroutines.launch

/** Uma linha da conversa: o que você pediu ou o que um agente fez. */
internal sealed interface Line {
    data class You(val text: String) : Line

    data class Event(val event: AgentEvent) : Line
}

private val ROUTES = mapOf("local" to "no celular", "nuvem" to "na nuvem")

/**
 * Conversa com um agente. Quando ele tem equipe, a tela mostra quem pediu o quê a quem, cada
 * ferramenta usada e as respostas, como um grupo. O cartão Confirmar aparece no meio da conversa.
 */
@Composable
fun AgentChat(app: BlicoApp, agent: AgentProfile, onBack: () -> Unit, onEdit: () -> Unit) {
    val p = LocalBlico.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember(agent.name) { mutableStateListOf<Line>() }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf<AgentRun?>(null) }
    var waiting by remember { mutableStateOf<Outcome?>(null) }
    val list = rememberLazyListState()

    fun ctx() = app.execContext { context.startActivity(it) }

    fun launchStep(step: suspend ((AgentEvent) -> Unit) -> AgentResult) {
        scope.launch {
            busy = true
            paused = null
            waiting = null
            // Só a nuvem merece aviso: é quando algo sai do celular, mascarado.
            val r = step { e -> if (e !is AgentEvent.Routed || e.provider == "nuvem") lines += Line.Event(e) }
            busy = false
            paused = r.paused
            waiting = r.outcome.takeIf { r.paused != null }
            app.say(r.outcome.spoken)
        }
    }

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BlicoLink("‹", onClick = onBack)
            BlicoAvatar(agent.name, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(agent.name, style = MaterialTheme.typography.titleMedium)
                val subtitle = if (agent.team.isEmpty()) agent.description else "com " + agent.team.joinToString()
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
            }
            BlicoLink("Editar", onClick = onEdit)
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (lines.isEmpty()) {
                item {
                    Text(
                        "Peça algo ao ${agent.name}." + if (agent.team.isNotEmpty()) " Ele pode chamar ${agent.team.joinToString()} e você acompanha a conversa aqui." else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.muted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(lines) { line -> LineView(line, lead = agent.name) }
            val out = waiting
            val stopped = paused
            if (out != null && stopped != null && !busy) {
                item {
                    WaitingCard(
                        out,
                        onConfirm = { call -> launchStep { e -> app.agentRunner.resume(stopped, call, ctx(), confirmed = true, onEvent = e) } },
                        onChoose = { call -> launchStep { e -> app.agentRunner.resume(stopped, call, ctx(), confirmed = false, onEvent = e) } },
                        onCancel = {
                            paused = null
                            waiting = null
                            lines += Line.Event(AgentEvent.Said(agent.name, "Cancelado. Nada foi feito nesse passo.", failed = true))
                        },
                    )
                }
            }
            if (busy) item { Text("Pensando…", style = MaterialTheme.typography.bodySmall, color = p.muted) }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Peça ao ${agent.name}") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            BlicoButton("Enviar", enabled = text.isNotBlank() && !busy, onClick = {
                val ask = text.trim()
                text = ""
                lines += Line.You(ask)
                launchStep { e -> app.agentRunner.start(ask, agent, ctx(), onEvent = e) }
            })
        }
    }
}

@Composable
internal fun LineView(line: Line, lead: String?) {
    val p = LocalBlico.current
    when (line) {
        is Line.You -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Bubble(line.text, background = p.green, color = p.onGreen)
        }
        is Line.Event -> when (val e = line.event) {
            is AgentEvent.Asked -> AgentLine(e.agent, "→ ${e.to}") { Bubble(e.text, background = p.card, color = p.text) }
            is AgentEvent.Said -> {
                // O líder responde a você; um membro responde ao líder.
                val to = if (lead == null || e.agent == lead) null else "→ $lead"
                AgentLine(e.agent, to) { Bubble(e.text, background = p.card, color = if (e.failed) p.danger else p.text) }
            }
            is AgentEvent.Acted -> {
                val args = e.call.args.toSortedMap().values.joinToString(", ")
                Text(
                    "${e.agent} usou ${e.call.tool}($args) · ${e.spoken}",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                    modifier = Modifier.padding(start = 44.dp),
                )
            }
            is AgentEvent.Waiting -> Text(
                "${e.agent} espera o seu toque",
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
                modifier = Modifier.padding(start = 44.dp),
            )
            is AgentEvent.Routed -> Text(
                "${e.agent} decidiu ${ROUTES[e.provider] ?: e.provider}",
                style = MaterialTheme.typography.labelSmall,
                color = p.muted,
                modifier = Modifier.padding(start = 44.dp),
            )
        }
    }
}

@Composable
private fun AgentLine(name: String, to: String?, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        BlicoAvatar(name, size = 32.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name + (to?.let { " $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = avatarColor(name))
            content()
        }
    }
}

@Composable
private fun Bubble(text: String, background: Color, color: Color) {
    Box(
        Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** O cartão Confirmar ou a escolha, dentro da conversa. O texto vem da chamada, nunca do modelo. */
@Composable
internal fun WaitingCard(
    out: Outcome,
    onConfirm: (ToolCall) -> Unit,
    onChoose: (ToolCall) -> Unit,
    onCancel: () -> Unit,
) {
    val p = LocalBlico.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.card)
            .padding(14.dp),
    ) {
        when (out) {
            is Outcome.Confirm -> {
                Text(out.summary.title, style = MaterialTheme.typography.titleMedium)
                out.summary.fields.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
                BlicoButton("Confirmar", onClick = { onConfirm(out.call) })
            }
            is Outcome.Choose -> {
                Text(out.spoken, style = MaterialTheme.typography.titleMedium)
                out.options.forEach { o -> BlicoButton(o.label, primary = false, onClick = { onChoose(o.call) }) }
            }
            else -> Unit
        }
        BlicoLink("Cancelar", onClick = onCancel)
    }
}
