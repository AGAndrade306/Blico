package app.blico.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Tons suaves para diferenciar os agentes na conversa. O verde do logo fica para o Blico e para você. */
private val TONES = listOf(
    Color(0xFF5E7CE2), Color(0xFFE2875E), Color(0xFF9A6BD6), Color(0xFF3FA7B5),
    Color(0xFFD65E8C), Color(0xFFB59A3F), Color(0xFF6B8E5A), Color(0xFF7A7A85),
)

/** A cor do agente sai do nome, então é sempre a mesma. */
fun agentTone(name: String): Color = TONES[Math.floorMod(name.hashCode(), TONES.size)]

@Composable
fun AgentAvatar(name: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(agentTone(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}
