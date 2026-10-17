package app.blico.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.blico.BlicoApp
import app.blico.core.AgentFile
import app.blico.core.AgentProfile
import app.blico.memory.AgentStore
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoChip
import app.blico.ui.BlicoLink
import app.blico.ui.LargeTitle
import app.blico.ui.LocalBlico

private val STEP_CHOICES = listOf(3, 5, 8, 10)

/**
 * Editor por campos: nome, o que faz, gatilhos, ferramentas, equipe e instruções. Grava o mesmo
 * Markdown que o arquivo; "Editar o arquivo" mostra o texto inteiro para quem preferir.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentEditor(app: BlicoApp, entry: AgentStore.Entry?, others: List<AgentProfile>, onDone: () -> Unit) {
    val p = LocalBlico.current
    val start = entry?.agent
    var name by remember { mutableStateOf(start?.name ?: "") }
    var description by remember { mutableStateOf(start?.description ?: "") }
    var triggers by remember { mutableStateOf(start?.triggers?.joinToString(", ") ?: "") }
    var tools by remember { mutableStateOf(start?.tools ?: emptySet()) }
    var team by remember { mutableStateOf(start?.team ?: emptyList()) }
    var steps by remember { mutableStateOf(start?.maxSteps ?: AgentProfile.DEFAULT_MAX_STEPS) }
    var instructions by remember { mutableStateOf(start?.instructions ?: "") }
    // Arquivo que não abre só pode ser corrigido no texto.
    var raw by remember { mutableStateOf(if (entry != null && start == null) entry.text else null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun profile(): AgentProfile = AgentProfile(
        name = name.trim(),
        instructions = instructions.trim(),
        tools = tools,
        maxSteps = steps,
        description = description.trim(),
        triggers = triggers.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        team = team,
    )

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        BlicoLink("‹ Agentes", onClick = onDone)
        LargeTitle(if (entry == null) "Novo agente" else entry.name, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))

        val text = raw
        if (text != null) {
            OutlinedTextField(
                value = text,
                onValueChange = { raw = it; error = null },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
            )
        } else {
            BlicoCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Field("Nome", name, { name = it.lowercase().replace(' ', '-'); error = null }, "letras minúsculas, sem acento: viagem, rotina")
                Field("O que faz", description, { description = it }, "Aparece na lista e ajuda a equipe a saber quando chamá-lo")
                Field("Gatilhos", triggers, { triggers = it }, "frases separadas por vírgula, como: organiza meu dia")
            }

            Section("FERRAMENTAS")
            BlicoCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    if (tools.isEmpty()) "Nenhuma marcada: o agente pode usar todas." else "O agente só usa as marcadas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.assistant.modelTools().sortedBy { it.name }.forEach { t ->
                        BlicoChip(t.name, selected = t.name in tools, onClick = {
                            tools = if (t.name in tools) tools - t.name else tools + t.name
                        })
                    }
                }
            }

            Section("EQUIPE")
            BlicoCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    "Agentes a quem este pode pedir ajuda. Eles recebem o pedido, fazem com as ferramentas deles e respondem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
                if (others.isEmpty()) Text("Crie outro agente para montar uma equipe.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { o ->
                        BlicoChip(o.name, selected = o.name in team, onClick = {
                            team = if (o.name in team) team - o.name else team + o.name
                        })
                    }
                }
            }

            Section("PASSOS NO MÁXIMO")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                STEP_CHOICES.forEach { n -> BlicoChip("$n", selected = n == steps, onClick = { steps = n }) }
            }

            Section("INSTRUÇÕES")
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it; error = null },
                placeholder = { Text("Como o agente deve agir, em português.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            )
        }

        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.danger, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        BlicoButton("Salvar", onClick = {
            val r = raw
            error = if (r != null) {
                app.agents.save(r, entry?.name)
            } else {
                runCatching { profile() }.fold({ app.agents.save(it, entry?.name) }, { it.message ?: "Agente inválido" })
            }
            if (error == null) onDone()
        })
        if (raw == null) {
            BlicoLink("Editar o arquivo", onClick = {
                raw = runCatching { AgentFile.render(profile()) }.getOrElse { entry?.text ?: "" }
            })
        }
        if (entry != null) {
            if (confirmDelete) {
                Text("Apagar o agente ${entry.name}?", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                BlicoButton("Apagar", onClick = { app.agents.delete(entry.name); onDone() }, primary = false)
            } else {
                BlicoLink("Apagar agente", onClick = { confirmDelete = true })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = LocalBlico.current.muted, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
