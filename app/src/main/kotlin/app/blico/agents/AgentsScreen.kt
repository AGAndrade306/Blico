package app.blico.agents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.blico.BlicoApp
import app.blico.core.AgentFile
import app.blico.core.AgentProfile
import app.blico.memory.AgentStore
import app.blico.ui.BlicoAvatar
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoLink
import app.blico.ui.LocalBlico
import app.blico.ui.ScreenHeader
import app.blico.ui.SectionLabel

/** Para onde a tela de agentes está olhando. */
private sealed interface Page {
    data object List : Page

    /** [name] nulo é um agente novo. */
    data class Edit(val name: String?) : Page

    data class Chat(val name: String) : Page

    data object Group : Page
}

/** A aba Agentes: a lista, as contas, as equipes, o editor e as conversas. Voltar leva à lista. */
@Composable
fun AgentsScreen(app: BlicoApp) {
    var page by remember { mutableStateOf<Page>(Page.List) }
    var refresh by remember { mutableIntStateOf(0) }
    val entries = remember(refresh) { app.agents.entries() }
    val agents = entries.mapNotNull { it.agent }

    BackHandler(enabled = page != Page.List) { page = Page.List }

    when (val p = page) {
        Page.List -> AgentList(
            app = app,
            entries = entries,
            onOpen = { page = Page.Chat(it) },
            onGroup = { page = Page.Group },
            onEdit = { page = Page.Edit(it) },
        )
        is Page.Edit -> AgentEditor(
            app = app,
            entry = entries.firstOrNull { it.name == p.name },
            others = agents.filter { it.name != p.name },
            onDone = { refresh++; page = Page.List },
        )
        Page.Group -> GroupChat(app, agents, onBack = { page = Page.List })
        is Page.Chat -> {
            val agent = agents.firstOrNull { it.name == p.name }
            if (agent == null) {
                LaunchedEffect(p) { page = Page.List }
            } else {
                AgentChat(app, agent, onBack = { page = Page.List }, onEdit = { page = Page.Edit(agent.name) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentList(
    app: BlicoApp,
    entries: List<AgentStore.Entry>,
    onOpen: (String) -> Unit,
    onGroup: () -> Unit,
    onEdit: (String?) -> Unit,
) {
    val p = LocalBlico.current
    val agents = entries.mapNotNull { it.agent }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader("Agentes", "${agents.size} ${if (agents.size == 1) "agente" else "agentes"}")
        Text(
            "Cada agente tem instruções, gatilhos e as ferramentas que você liberou. Um agente com equipe divide o pedido e conversa com os outros até terminar. Enviar e ligar sempre param no Confirmar.",
            style = MaterialTheme.typography.bodyMedium,
            color = p.muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        if (agents.size >= 2) {
            BlicoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable(onClick = onGroup)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    agents.take(4).forEach { a ->
                        BlicoAvatar(a.name, size = 30.dp)
                        Spacer(Modifier.width(2.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Conversa em grupo", style = MaterialTheme.typography.titleMedium)
                        Text("Todos os agentes juntos. Chame cada um por @nome.", style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = p.muted)
                }
            }
        }

        SectionLabel("Contas")
        AccountsCard(app)

        val leads = agents.filter { it.team.isNotEmpty() }
        if (leads.isNotEmpty()) {
            SectionLabel("Equipes")
            leads.forEach { lead -> TeamCard(lead, agents, onOpen) }
        }

        SectionLabel("Todos os agentes")
        entries.forEach { e ->
            BlicoCard(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = e.agent != null) { onOpen(e.name) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BlicoAvatar(e.name)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.titleMedium)
                        val agent = e.agent
                        if (agent == null) {
                            Text("Não abre: ${e.error}", style = MaterialTheme.typography.bodySmall, color = p.danger)
                        } else {
                            if (agent.description.isNotBlank()) Text(agent.description, style = MaterialTheme.typography.bodyMedium)
                            Text(details(agent), style = MaterialTheme.typography.bodySmall, color = p.muted)
                            val problems = AgentFile.unknownTools(agent, app.registry) + AgentFile.unknownMembers(agent, agents)
                            if (problems.isNotEmpty()) {
                                Text("Não existem: ${problems.joinToString()}", style = MaterialTheme.typography.bodySmall, color = p.danger)
                            }
                        }
                    }
                    BlicoLink("Editar", onClick = { onEdit(e.name) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        BlicoButton("Novo agente", onClick = { onEdit(null) })
        Spacer(Modifier.height(24.dp))
    }
}

/** O líder e quem ele chama, como uma linha de avatares. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamCard(lead: AgentProfile, all: List<AgentProfile>, onOpen: (String) -> Unit) {
    val p = LocalBlico.current
    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(lead.name) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlicoAvatar(lead.name, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(lead.name, style = MaterialTheme.typography.titleMedium)
                Text("Coordena ${lead.team.size} ${if (lead.team.size == 1) "agente" else "agentes"}", style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            Text("Conversar ›", style = MaterialTheme.typography.labelMedium, color = p.green)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lead.team.forEach { name ->
                val exists = all.any { it.name == name }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BlicoAvatar(name, size = 24.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(name, style = MaterialTheme.typography.bodySmall, color = if (exists) p.text else p.danger)
                }
            }
        }
    }
}

internal fun details(agent: AgentProfile): String = buildList {
    add(if (agent.tools.isEmpty()) "todas as ferramentas" else "${agent.tools.size} ${if (agent.tools.size == 1) "ferramenta" else "ferramentas"}")
    if (agent.team.isNotEmpty()) add("equipe: ${agent.team.joinToString()}")
    add("até ${agent.maxSteps} passos")
    if (agent.triggers.isNotEmpty()) add(agent.triggers.joinToString(prefix = "\"", postfix = "\"", separator = "\", \""))
}.joinToString(" · ")
