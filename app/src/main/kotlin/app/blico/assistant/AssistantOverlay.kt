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
import app.blico.R
import app.blico.core.Outcome
import app.blico.ui.LocalRiso
import app.blico.ui.RisoButton
import app.blico.ui.RisoCard
import app.blico.ui.RisoLink
import app.blico.ui.RisoTheme
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
    RisoTheme {
        val p = LocalRiso.current
        // Tocar fora do cartão fecha o Blico.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .safeDrawingPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            RisoCard(
                shape = RoundedCornerShape(30.dp),
                shadow = 6.dp,
                contentPadding = PaddingValues(22.dp),
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
                        RisoButton("Abrir o Blico", onClick = onOpenSetup)
                        return@Column
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.blico_logo), contentDescription = null, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(state.label, style = MaterialTheme.typography.titleMedium, color = p.green, modifier = Modifier.weight(1f))
                        StatusDot(state.phase)
                    }

                    if (state.text.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(state.text, style = MaterialTheme.typography.headlineSmall)
                    }

                    val metrics = listOfNotNull(
                        state.setupMs?.let { "microfone em ${it} ms" },
                        state.latencyMs?.let { "texto ${it} ms após a fala" },
                    )
                    if (metrics.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            metrics.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = p.muted,
                        )
                    }

                    if (thinking) {
                        Spacer(Modifier.height(12.dp))
                        Text("Pensando…", style = MaterialTheme.typography.bodyLarge, color = p.muted)
                    }

                    outcome?.let { out ->
                        Spacer(Modifier.height(12.dp))
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
                                    RisoButton(label, onClick = { onChoose(call) }, primary = false)
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
                                    Text(k, style = MaterialTheme.typography.labelLarge, color = p.green, modifier = Modifier.width(64.dp))
                                    Text(v, style = MaterialTheme.typography.bodyMedium, color = p.text)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            RisoButton("Confirmar", onClick = { onConfirm(out.call) })
                        }
                    }

                    val finished = !thinking && (state.phase == Phase.FAILED || (state.phase == Phase.DONE && outcome != null && outcome !is Outcome.Done))
                    if (finished) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RisoButton("Falar de novo", onClick = onRetry)
                            RisoLink("Fechar", onClick = onClose)
                            if (state.phase == Phase.FAILED) {
                                RisoLink("Configurar", onClick = onOpenSetup)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(phase: Phase) {
    val p = LocalRiso.current
    val color = when (phase) {
        Phase.LISTENING, Phase.HEARING -> p.pink
        Phase.FAILED -> p.danger
        else -> p.green
    }
    Box(
        Modifier
            .size(12.dp)
            .background(color, CircleShape),
    )
}
