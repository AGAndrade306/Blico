package app.blico

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.blico.ui.BlicoCard
import app.blico.ui.LocalBlico
import app.blico.ui.ScreenHeader

/** Aba Agentes. A tela de verdade vem do pacote `app.blico.agents`; até lá, um aviso. */
@Composable
fun AgentsTab(@Suppress("UNUSED_PARAMETER") app: BlicoApp) {
    val p = LocalBlico.current
    Column(Modifier.padding(horizontal = 16.dp)) {
        ScreenHeader("Agentes", "Em construção")
        BlicoCard(Modifier.fillMaxWidth()) {
            Text("Seus agentes vão morar aqui", style = MaterialTheme.typography.titleMedium)
            Text(
                "Cada um com um papel, as ferramentas que você liberar e uma conversa própria. Tudo que mexe no celular continua passando pelo Confirmar.",
                style = MaterialTheme.typography.bodyMedium,
                color = p.muted,
            )
        }
    }
}
