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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.blico.voice.ListeningState
import app.blico.voice.ListeningState.Phase

@Composable
internal fun AssistantOverlay(
    state: ListeningState,
    needsPermission: Boolean,
    onRetry: () -> Unit,
    onOpenSetup: () -> Unit,
    onClose: () -> Unit,
) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        // Tocar fora do cartão fecha o Blico.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
                .safeDrawingPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.padding(20.dp)) {
                    if (needsPermission) {
                        Text("O Blico precisa do microfone", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Abra o app uma vez para liberar a permissão.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onOpenSetup) { Text("Abrir o Blico") }
                        return@Column
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(state.phase)
                        Spacer(Modifier.size(10.dp))
                        Text(state.label, style = MaterialTheme.typography.titleMedium)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (state.phase == Phase.DONE || state.phase == Phase.FAILED) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRetry) { Text("Falar de novo") }
                            TextButton(onClick = onClose) { Text("Fechar") }
                            if (state.phase == Phase.FAILED) {
                                TextButton(onClick = onOpenSetup) { Text("Configurar") }
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
    val color = when (phase) {
        Phase.LISTENING, Phase.HEARING -> Color(0xFF2E7D32)
        Phase.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .size(12.dp)
            .background(color, CircleShape),
    )
}
