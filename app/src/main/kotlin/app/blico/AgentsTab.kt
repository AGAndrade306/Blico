package app.blico

import androidx.compose.runtime.Composable
import app.blico.agents.AgentsScreen

/** Aba Agentes: lista, equipes, contas, editor e conversas. */
@Composable
fun AgentsTab(app: BlicoApp) {
    AgentsScreen(app)
}
