package app.blico.assistant

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.content.ClipData
import app.blico.R
import app.blico.core.Outcome
import app.blico.ui.LocalBlico
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoLink
import app.blico.ui.BlicoTheme
import app.blico.ui.PanelLabel
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
    onRetry: () -> Unit,
    onChoose: (ToolCall) -> Unit,
    onConfirm: (ToolCall) -> Unit,
    onOpenSetup: () -> Unit,
    onClose: () -> Unit,
) {
    BlicoTheme {
        val p = LocalBlico.current
        // Tocar fora do cartão fecha o Blico.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .safeDrawingPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            BlicoCard(
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column {
                    if (needsPermission) {
                        Text("O Blico precisa do microfone", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Abra o app uma vez para liberar a permissão.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        BlicoButton("Abrir o Blico", onClick = onOpenSetup)
                        return@Column
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.blico_logo), contentDescription = null, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(state.label, style = MaterialTheme.typography.titleMedium, color = p.text, modifier = Modifier.weight(1f))
                        StatusDot(state.phase)
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

@Composable
private fun StatusDot(phase: Phase) {
    val p = LocalBlico.current
    val color = when (phase) {
        Phase.LISTENING, Phase.HEARING -> p.green
        Phase.FAILED -> p.danger
        else -> p.muted
    }
    Box(
        Modifier
            .size(12.dp)
            .background(color, CircleShape),
    )
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
) {
    val p = LocalBlico.current
    Column {
        if (out is Outcome.Answer) {
            AnswerDetails(out, onChoose, onShowBrowser)
            return@Column
        }
        Text(
            out.spoken,
            style = MaterialTheme.typography.titleMedium,
            color = if (out is Outcome.Failed) p.danger else p.text,
        )
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
            BlicoButton("Confirmar", onClick = { onConfirm(out.call) })
        }
    }
}

/**
 * Resposta no card: o texto grande e rolável, a fonte e o caminho para o navegador.
 * Pergunta pontual oferece "Ver no navegador"; pesquisa ampla já abriu e o card guarda o resumo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerDetails(out: Outcome.Answer, onChoose: (ToolCall) -> Unit, onShowBrowser: (() -> Unit)?) {
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
    if (meta.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(meta.joinToString("  ·  "), style = PanelLabel, color = p.muted)
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
