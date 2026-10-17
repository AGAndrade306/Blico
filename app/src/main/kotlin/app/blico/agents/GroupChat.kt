package app.blico.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.blico.BlicoApp
import app.blico.core.AgentEvent
import app.blico.core.AgentProfile
import app.blico.core.GroupMessage
import app.blico.core.GroupResult
import app.blico.core.GroupRun
import app.blico.core.Outcome
import app.blico.ui.BlicoAvatar
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoChip
import app.blico.ui.BlicoLink
import app.blico.ui.LocalBlico
import kotlinx.coroutines.launch

/**
 * Todos os agentes numa conversa só. Chame cada um por @nome; sem @, fala com o escolhido embaixo.
 * Um agente que cita @outro na resposta passa a vez a ele.
 */
@Composable
fun GroupChat(app: BlicoApp, members: List<AgentProfile>, onBack: () -> Unit) {
    val p = LocalBlico.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<Line>() }
    var transcript by remember { mutableStateOf(emptyList<GroupMessage>()) }
    var text by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(members.firstOrNull()?.name.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf<GroupRun?>(null) }
    var waiting by remember { mutableStateOf<Outcome?>(null) }
    val list = rememberLazyListState()

    fun ctx() = app.execContext { context.startActivity(it) }

    fun launchStep(step: suspend ((AgentEvent) -> Unit) -> GroupResult) {
        scope.launch {
            busy = true
            paused = null
            waiting = null
            val r = step { e -> if (e !is AgentEvent.Routed || e.provider == "nuvem") lines += Line.Event(e) }
            busy = false
            transcript = r.transcript
            paused = r.paused
            waiting = r.outcome.takeIf { r.paused != null }
            r.outcome?.let { app.say(it.spoken) }
        }
    }

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BlicoLink("‹", onClick = onBack)
            members.take(5).forEach { m ->
                BlicoAvatar(m.name, size = 26.dp)
                Spacer(Modifier.width(2.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Grupo", style = MaterialTheme.typography.titleMedium)
                Text(members.joinToString { it.name }, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 1)
            }
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (lines.isEmpty()) {
                item {
                    Text(
                        "Chame os agentes por @nome, como \"@manha alarme às 6 @avisos avisa o João\". Um agente pode passar a vez a outro citando @nome.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.muted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(lines) { line -> LineView(line, lead = null) }
            val out = waiting
            val stopped = paused
            if (out != null && stopped != null && !busy) {
                item {
                    WaitingCard(
                        out,
                        onConfirm = { call -> launchStep { e -> app.groupRunner.resume(stopped, call, members, ctx(), confirmed = true, onEvent = e) } },
                        onChoose = { call -> launchStep { e -> app.groupRunner.resume(stopped, call, members, ctx(), confirmed = false, onEvent = e) } },
                        onCancel = {
                            paused = null
                            waiting = null
                            lines += Line.Event(AgentEvent.Said(stopped.paused?.agent?.name ?: "grupo", "Cancelado. Nada foi feito nesse passo.", failed = true))
                        },
                    )
                }
            }
            if (busy) item { Text("Pensando…", style = MaterialTheme.typography.bodySmall, color = p.muted) }
        }

        // Quem recebe a mensagem sem @; tocar de novo põe o @nome no texto.
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(members) { m ->
                BlicoChip("@${m.name}", selected = m.name == target, onClick = {
                    if (m.name == target) text = (text.trimEnd() + " @${m.name} ").trimStart() else target = m.name
                })
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Mensagem para @$target") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            BlicoButton("Enviar", enabled = text.isNotBlank() && !busy && target.isNotEmpty(), onClick = {
                val ask = text.trim()
                text = ""
                lines += Line.You(ask)
                val history = transcript
                launchStep { e -> app.groupRunner.send(ask, members, history, target, ctx(), onEvent = e) }
            })
        }
    }
}
