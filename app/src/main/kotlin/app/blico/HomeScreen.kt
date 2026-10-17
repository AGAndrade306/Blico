package app.blico

import android.app.AlarmManager
import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.blico.assistant.OutcomeDetails
import app.blico.core.ActivityLog
import app.blico.core.Assistant
import app.blico.core.ExecContext
import app.blico.core.Outcome
import app.blico.core.TranscriptionEvent
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoChip
import app.blico.ui.BlicoLink
import app.blico.ui.CardShape
import app.blico.ui.DashedRule
import app.blico.ui.DottedRing
import app.blico.ui.LiveDot
import app.blico.ui.LocalBlico
import app.blico.ui.PanelLabel
import app.blico.ui.SectionLabel
import app.blico.voice.ListeningState
import app.blico.voice.ListeningState.Phase
import app.blico.voice.OnDeviceTranscriber
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PT = Locale.forLanguageTag("pt-BR")
private val HOUR = DateTimeFormatter.ofPattern("HH:mm", PT)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", PT)

/** Pedidos que as regras entendem sem modelo, para quem ainda não sabe o que pedir. */
private val SUGGESTIONS = listOf("timer de 10 minutos", "me acorda às 7", "toca Djavan", "pesquisa preço do dólar", "fotos de ontem")

/**
 * Início: o painel do assistente. Falar (só depois do toque), digitar, ver o que o Blico fez hoje.
 * [visible] falso fecha o microfone, para nada ficar ouvindo com o app em segundo plano.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    app: BlicoApp,
    tick: Int,
    visible: Boolean,
    pendingSteps: Int,
    micGranted: Boolean,
    onAskMic: () -> Unit,
    onOpenSettings: () -> Unit,
    launch: (Intent) -> Unit,
) {
    val p = LocalBlico.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcriber = remember { OnDeviceTranscriber(context) }
    var listening by remember { mutableStateOf<ListeningState?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var typed by remember { mutableStateOf("") }
    val activity by app.activity.collectAsState()

    fun stopListening() {
        job?.cancel()
        job = null
        if (listening?.phase != Phase.DONE) listening = null
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    LaunchedEffect(visible) { if (!visible) stopListening() }

    fun act(step: suspend (Assistant, ExecContext) -> Outcome) {
        scope.launch {
            thinking = true
            val out = try {
                step(app.assistant, app.execContext(launch))
            } finally {
                thinking = false
            }
            outcome = out
            app.record(out)
            app.say(out.spoken)
        }
    }

    fun ask(text: String) {
        stopListening()
        listening = null
        app.speech.stop()
        outcome = null
        act { assistant, ctx -> assistant.handle(text, ctx) }
    }

    fun talk() {
        if (job != null) return stopListening()
        if (!micGranted) return onAskMic()
        app.speech.stop() // o toque interrompe a fala, e o microfone não ouve o próprio Blico
        app.warmUpLocal()
        app.warmUpVoice()
        outcome = null
        listening = ListeningState()
        job = scope.launch {
            try {
                transcriber.listen().collect { event ->
                    listening = listening?.reduce(event)
                    if (event is TranscriptionEvent.Final) act { assistant, ctx -> assistant.handle(event.text, ctx) }
                }
            } finally {
                job = null
            }
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("BLICO", style = MaterialTheme.typography.displaySmall, color = p.text, modifier = Modifier.weight(1f))
            val cloud = app.cloudFallback && app.cloudConfigured
            LiveDot(if (cloud) p.muted else p.green)
            Spacer(Modifier.width(6.dp))
            Text(if (cloud) "CELULAR + NUVEM" else "SÓ NO CELULAR", style = PanelLabel, color = p.text)
        }

        if (pendingSteps > 0) {
            BlicoCard(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(CardShape).clickable(onClick = onOpenSettings),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (pendingSteps == 1) "Falta 1 passo para o Blico ficar pronto" else "Faltam $pendingSteps passos para o Blico ficar pronto",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("AJUSTES →", style = PanelLabel, color = p.text)
                }
            }
        }

        TalkTile(listening, active = job != null, onClick = ::talk)

        if (thinking || outcome != null) {
            BlicoCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (thinking) {
                    Text("Pensando…", style = MaterialTheme.typography.bodyLarge, color = p.muted)
                }
                outcome?.let { out ->
                    OutcomeDetails(
                        out,
                        onChoose = { call -> act { assistant, ctx -> assistant.execute(call, ctx) } },
                        onConfirm = { call -> act { assistant, ctx -> assistant.execute(call, ctx, confirmed = true) } },
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("ou digite um pedido") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (typed.isNotBlank()) { ask(typed); typed = "" } }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            BlicoButton("Ir", enabled = typed.isNotBlank() && !thinking, onClick = { ask(typed); typed = "" })
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val alarm = remember(tick, activity) { nextAlarm(context.getSystemService(AlarmManager::class.java)) }
            Widget("Próximo alarme", alarm?.first ?: "--:--", alarm?.second, Modifier.weight(1f))
            val today = ActivityLog.countToday(activity, System.currentTimeMillis(), ZoneId.systemDefault())
            Widget("Feito hoje", today.toString(), if (today == 1) "ação" else "ações", Modifier.weight(1f))
        }

        SectionLabel("Experimente")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTIONS.forEach { s -> BlicoChip(s, selected = false, onClick = { ask(s) }) }
        }

        SectionLabel("Atividade") {
            if (activity.isNotEmpty()) BlicoLink("Limpar", onClick = app::clearActivity)
        }
        if (activity.isEmpty()) {
            Text("Nada por aqui ainda. O que o Blico fizer aparece nesta lista e fica só no celular.", style = MaterialTheme.typography.bodySmall, color = p.muted)
        }
        activity.take(8).forEach { e -> LogRow(e) }
    }
}

/** O bloco escuro: anel de pontos e o que o Blico está fazendo. Tocar de novo para de ouvir. */
@Composable
private fun TalkTile(state: ListeningState?, active: Boolean, onClick: () -> Unit) {
    val p = LocalBlico.current
    val spin = rememberInfiniteTransition(label = "anel")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "giro")
    val (title, detail) = when {
        state == null -> "Toque e fale" to "ou segure o botão lateral"
        state.phase == Phase.FAILED -> state.label to "Toque para tentar de novo"
        else -> state.label to state.text.ifBlank { if (active) "Toque para parar" else "" }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(p.ink)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            DottedRing(Modifier.size(64.dp).rotate(if (active) angle else 0f), color = p.green, strokeWidth = 3.5.dp, dots = 24)
            LiveDot(if (active) p.green else p.onInk, size = if (active) 18.dp else 14.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = p.onInk)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = p.onInk.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun Widget(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    val p = LocalBlico.current
    BlicoCard(modifier, contentPadding = PaddingValues(14.dp)) {
        Text(label.uppercase(), style = PanelLabel, color = p.muted)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.displayMedium, color = p.text)
        if (unit != null) Text(unit.uppercase(), style = PanelLabel, color = p.muted)
    }
}

@Composable
private fun LogRow(e: ActivityLog.Entry) {
    val p = LocalBlico.current
    val at = Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault())
    val time = if (at.toLocalDate() == LocalDate.now()) at.format(HOUR) else at.format(WEEKDAY).trimEnd('.') + " " + at.format(HOUR)
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(e.text, style = MaterialTheme.typography.labelMedium, color = p.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(time, style = MaterialTheme.typography.labelSmall, color = p.muted)
    }
    DashedRule(Modifier.fillMaxWidth())
}

/** Hora do próximo alarme do relógio do sistema, com o dia da semana quando não é hoje. */
private fun nextAlarm(alarms: AlarmManager?): Pair<String, String?>? {
    val at = alarms?.nextAlarmClock?.triggerTime ?: return null
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (time.toLocalDate()) {
        today -> "hoje"
        today.plusDays(1) -> "amanhã"
        else -> time.format(WEEKDAY).trimEnd('.')
    }
    return time.format(HOUR) to day
}
