package app.blico.agents

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.blico.BlicoApp
import app.blico.ui.BlicoCard
import app.blico.ui.LocalBlico

/** Atalho da tela inicial para a tela dos agentes, até o app ganhar abas. */
@Composable
fun AgentsEntryCard(app: BlicoApp) {
    val context = LocalContext.current
    val p = LocalBlico.current
    val agents = remember { app.agents.agents() }
    BlicoCard(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { context.startActivity(Intent(context, AgentsActivity::class.java)) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Agentes", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${agents.size} ${if (agents.size == 1) "agente" else "agentes"}, ${agents.count { it.team.isNotEmpty() }} com equipe. Configure e converse com eles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
            }
            agents.take(4).forEach { a ->
                AgentAvatar(a.name, size = 26.dp)
                Spacer(Modifier.width(4.dp))
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = p.muted)
        }
    }
}
