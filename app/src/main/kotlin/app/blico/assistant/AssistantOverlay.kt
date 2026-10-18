package app.blico.assistant

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import app.blico.core.Origin
import app.blico.ui.Aura
import app.blico.ui.AuroraEdge
import app.blico.ui.BlicoBadge
import app.blico.ui.BlicoEyes
import app.blico.ui.OriginBadge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.content.ClipData
import app.blico.core.Outcome
import app.blico.ui.LocalBlico
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoLink
import app.blico.ui.BlicoTheme
import app.blico.ui.PanelLabel
import app.blico.ui.StatusMark
import app.blico.core.ToolCall
import app.blico.voice.ListeningState
import app.blico.voice.ListeningState.Phase

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantOverlay(
    state: ListeningState,
    needsPermission: Boolean,
    thinking: Boolean,
    outcome: Outcome?,
    origin: Origin?,
    onRetry: () -> Unit,
    onChoose: (ToolCall) -> Unit,
    onConfirm: (ToolCall) -> Unit,
    onOpenSetup: () -> Unit,
    onClose: () -> Unit,
) {
    BlicoTheme {
        val p = LocalBlico.current
        val aura = auraFor(state.phase, needsPermission, thinking, outcome, origin)
        Box(Modifier.fillMaxSize()) {
            // Tocar fora do cartão fecha o Blico. O véu é leve: o app aberto continua visível atrás.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.08f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
            AuroraEdge(aura, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
                // O cartão sobe com uma mola curta ao abrir.
                val rise = remember { Animatable(0f) }
                LaunchedEffect(Unit) { rise.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .graphicsLayer {
                            translationY = (1f - rise.value) * 24.dp.toPx()
                            alpha = rise.value.coerceIn(0f, 1f)
                        }
                        .clip(OverlayShape)
                        .background(p.card.copy(alpha = 0.92f))
                        .border(1.dp, p.separator.copy(alpha = 0.6f), OverlayShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(18.dp),
                ) {
                    if (needsPermission) {
                        Text("O Blico precisa do microfone", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Abra o app uma vez para liberar a permissão.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        BlicoButton("Abrir o Blico", onClick = onOpenSetup)
                        return@Column
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BlicoEyes(aura)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (outcome != null && !thinking) "Blico" else state.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = p.text,
                            )
                            caption(state.phase, thinking, outcome, origin)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = p.muted)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        StateBadge(state.phase, thinking, outcome, origin)
                    }

                    // O que a pessoa disse fica pequeno em cima; a resposta é o destaque.
                    if (state.text.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text("VOCÊ", style = PanelLabel, color = p.muted)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            state.text,
                            style = if (outcome == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                            color = if (outcome == null) p.text else p.muted,
                        )
                    }

                    if (thinking) {
                        Spacer(Modifier.height(14.dp))
                        Text("Pensando…", style = MaterialTheme.typography.bodyLarge, color = p.muted)
                    }

                    outcome?.let { out ->
                        Spacer(Modifier.height(14.dp))
                        Text("BLICO", style = PanelLabel, color = p.muted)
                        Spacer(Modifier.height(2.dp))
                        OutcomeDetails(out, onChoose = onChoose, onConfirm = onConfirm, onShowBrowser = onClose)
                    }

                    val finished = !thinking && (state.phase == Phase.FAILED || (state.phase == Phase.DONE && outcome != null && outcome !is Outcome.Done))
                    if (finished) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            BlicoButton("Falar de novo", onClick = onRetry)
                            BlicoLink("Fechar", onClick = onClose)
                            if (state.phase == Phase.FAILED) {
                                BlicoLink("Configurar", onClick = onOpenSetup)
                            }
                        }
                    }

                    val metrics = listOfNotNull(
                        state.setupMs?.let { "microfone em ${it} ms" },
                        state.latencyMs?.let { "texto ${it} ms após a fala" },
                    )
                    if (metrics.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(metrics.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = p.muted)
                    }
                }
            }
        }
    }
}

private val OverlayShape = RoundedCornerShape(28.dp)

/** A cor da luz: ouvir e pensar em azul, o resultado na cor de onde rodou, o Confirmar em laranja. */
internal fun auraFor(phase: Phase, needsPermission: Boolean, thinking: Boolean, outcome: Outcome?, origin: Origin?): Aura = when {
    needsPermission -> Aura.REST
    thinking || (outcome == null && phase == Phase.PROCESSING) -> Aura.THINKING
    outcome is Outcome.Confirm -> Aura.CONFIRM
    outcome is Outcome.Failed || outcome is Outcome.NotUnderstood -> Aura.REST
    outcome != null -> if ((origin ?: Origin.DEVICE).kind == Origin.Kind.DEVICE) Aura.DEVICE else Aura.CLOUD
    phase == Phase.LISTENING || phase == Phase.HEARING -> Aura.LISTENING
    else -> Aura.REST
}

/** A linha pequena sob o título: o que está acontecendo com o seu dado agora. */
private fun caption(phase: Phase, thinking: Boolean, outcome: Outcome?, origin: Origin?): String? = when {
    thinking -> "entendendo o pedido"
    outcome is Outcome.Confirm -> "texto montado da chamada, não do modelo"
    outcome is Outcome.Failed -> null
    outcome != null -> when ((origin ?: Origin.DEVICE).kind) {
        Origin.Kind.DEVICE -> "nada saiu do aparelho"
        Origin.Kind.CLOUD -> "dados mascarados antes de sair"
        Origin.Kind.WEB -> "só a pesquisa saiu, para a web"
        Origin.Kind.SERVICE -> "na sua conta ${origin?.name}"
    }
    phase == Phase.LISTENING || phase == Phase.HEARING -> "transcrição no celular"
    else -> null
}

/** Selo do estado: `[~] ouvindo`, `[.] pensando`, `[!] confirmar` ou de onde veio a resposta. */
@Composable
private fun StateBadge(phase: Phase, thinking: Boolean, outcome: Outcome?, origin: Origin?) {
    val p = LocalBlico.current
    when {
        thinking -> BlicoBadge("[.] pensando", p.blue, p.blueSoft)
        outcome is Outcome.Confirm -> BlicoBadge("[!] confirmar", p.orange, p.orangeSoft)
        phase == Phase.FAILED || outcome is Outcome.Failed -> BlicoBadge("[x] falhou", p.danger, p.orangeSoft)
        outcome is Outcome.NotUnderstood -> StatusMark(on = false)
        outcome != null -> OriginBadge(origin ?: Origin.DEVICE)
        phase == Phase.LISTENING || phase == Phase.HEARING -> BlicoBadge("[~] ouvindo", p.blue, p.blueSoft)
        else -> StatusMark(on = false)
    }
}

/**
 * O botão do Confirmar. Recusa o toque quando outra janela cobre o Blico, para que um app por cima
 * não leve a pessoa a confirmar sem ver o cartão, e tira a tela de capturas enquanto está aberto.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ConfirmButton(onConfirm: () -> Unit) {
    val p = LocalBlico.current
    var covered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    SecureWhileShown()
    Box(
        Modifier.pointerInteropFilter { ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                covered = ev.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
            }
            false // só observa; o toque segue para o botão
        },
    ) {
        BlicoButton("Confirmar", onClick = { if (covered) blocked = true else onConfirm() }, color = p.orange)
    }
    if (blocked) {
        Text(
            "Tem uma janela de outro app por cima do Blico. Feche-a e toque de novo.",
            style = MaterialTheme.typography.bodySmall,
            color = p.danger,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** FLAG_SECURE na janela enquanto o cartão está na tela: fica fora de capturas e da tela de recentes. */
@Composable
private fun SecureWhileShown() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * O resultado de um pedido: a resposta, as opções de escolha, os arquivos ou o cartão Confirmar.
 * O mesmo no cartão do botão lateral e na tela inicial. [onShowBrowser] tira o cartão da frente
 * quando a pesquisa já abriu por trás dele; sem ele, só avisa que abriu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OutcomeDetails(
    out: Outcome,
    onChoose: (ToolCall) -> Unit,
    onConfirm: (ToolCall) -> Unit,
    onShowBrowser: (() -> Unit)? = null,
    origin: Origin? = null,
) {
    val p = LocalBlico.current
    Column {
        if (out is Outcome.Answer) {
            AnswerDetails(out, onChoose, onShowBrowser, origin)
            return@Column
        }
        Text(
            out.spoken,
            style = MaterialTheme.typography.titleMedium,
            color = if (out is Outcome.Failed) p.danger else p.text,
        )
        if (origin != null && out is Outcome.Done) {
            Spacer(Modifier.height(6.dp))
            OriginBadge(origin)
        }
        // Escolha entre contatos: cada botão já leva a chamada completa.
        if (out.choices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                out.choices.forEach { (label, call) ->
                    BlicoButton(label, onClick = { onChoose(call) }, primary = false)
                }
            }
        }
        // Arquivos encontrados: as ações voltam como chamadas, iguais às de voz.
        if (out is Outcome.Files) {
            Spacer(Modifier.height(8.dp))
            FileResults(out.items, onAction = onChoose)
        }
        // Classe 2: o texto vem do resumo estruturado, nunca do modelo.
        if (out is Outcome.Confirm) {
            Spacer(Modifier.height(10.dp))
            out.summary.fields.forEach { (k, v) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(k.uppercase(), style = PanelLabel, color = p.muted, modifier = Modifier.width(84.dp).padding(top = 3.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium, color = p.text)
                }
            }
            Spacer(Modifier.height(12.dp))
            ConfirmButton { onConfirm(out.call) }
        }
    }
}

/**
 * Resposta no card: o texto grande e rolável, a fonte e o caminho para o navegador.
 * Pergunta pontual oferece "Ver no navegador"; pesquisa ampla já abriu e o card guarda o resumo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerDetails(out: Outcome.Answer, onChoose: (ToolCall) -> Unit, onShowBrowser: (() -> Unit)?, origin: Origin?) {
    val p = LocalBlico.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Text(
        out.spoken,
        style = if (out.spoken.length <= 120) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        color = p.text,
        modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
    )
    val meta = listOfNotNull(
        out.source?.let { "FONTE · ${it.uppercase()}" },
        "PESQUISA ABERTA NO NAVEGADOR".takeIf { out.browserOpened },
    )
    if (origin != null || meta.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Fora do cartão do botão lateral, que já leva o selo no topo, o selo vem junto da fonte.
            if (origin != null) {
                OriginBadge(origin)
                Spacer(Modifier.width(8.dp))
            }
            Text(meta.joinToString("  ·  "), style = PanelLabel, color = p.muted)
        }
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val search = out.search
        when {
            search != null && !out.browserOpened ->
                BlicoButton("Ver no navegador", onClick = { onChoose(ToolCall("abrir_pesquisa", mapOf("consulta" to search))) }, primary = false)
            out.browserOpened && onShowBrowser != null ->
                BlicoButton("Ir para a pesquisa", onClick = onShowBrowser, primary = false)
        }
        BlicoLink("Copiar", onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Blico", out.spoken))) } })
    }
}
