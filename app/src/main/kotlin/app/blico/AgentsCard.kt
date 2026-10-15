package app.blico

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.blico.core.AgentFile
import app.blico.core.AgentResult
import app.blico.core.Outcome
import app.blico.memory.AgentStore
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoChip
import app.blico.ui.BlicoLink
import app.blico.ui.LocalBlico
import kotlinx.coroutines.launch

private val NEW_AGENT = """
    ---
    nome: novo
    descricao: O que este agente faz
    gatilhos: [frase que chama o agente]
    ferramentas: [alarme, timer]
    max_passos: 5
    ---
    Escreva aqui, em português, como o agente deve agir.
""".trimIndent()

/**
 * Agentes em arquivo: cada um tem instruções, gatilhos e as ferramentas que pode usar.
 * Diga "agente <nome>, ..." ou um gatilho e ele dá vários passos, parando no Confirmar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentsCard(app: BlicoApp) {
    val p = LocalBlico.current
    var refresh by remember { mutableIntStateOf(0) }
    val entries = remember(refresh) { app.agents.entries() }
    // Nome do arquivo em edição; "" é um agente novo.
    var editing by remember { mutableStateOf<String?>(null) }

    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Agentes", style = MaterialTheme.typography.titleMedium)
        Text(
            "Diga \"agente manha, ...\" ou um dos gatilhos. O agente dá vários passos com as ferramentas que você liberou e para no Confirmar antes de enviar ou ligar.",
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )

        val current = editing
        if (current != null) {
            AgentEditor(
                store = app.agents,
                name = current.ifEmpty { null },
                initial = entries.firstOrNull { it.name == current }?.text ?: NEW_AGENT,
                onDone = { editing = null; refresh++ },
            )
            return@BlicoCard
        }

        entries.forEach { e ->
            Spacer(Modifier.height(12.dp))
            Text(e.name, style = MaterialTheme.typography.titleSmall)
            val agent = e.agent
            if (agent == null) {
                Text("Não abre: ${e.error}", style = MaterialTheme.typography.bodySmall, color = p.danger)
            } else {
                if (agent.description.isNotEmpty()) Text(agent.description, style = MaterialTheme.typography.bodyMedium)
                val tools = if (agent.tools.isEmpty()) "todas" else agent.tools.sorted().joinToString()
                Text("Ferramentas: $tools · até ${agent.maxSteps} passos", style = MaterialTheme.typography.bodySmall, color = p.muted)
                if (agent.triggers.isNotEmpty()) {
                    Text("Gatilhos: " + agent.triggers.joinToString { "\"$it\"" }, style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
                val unknown = AgentFile.unknownTools(agent, app.registry)
                if (unknown.isNotEmpty()) {
                    Text("Ferramentas que não existem: ${unknown.joinToString()}", style = MaterialTheme.typography.bodySmall, color = p.danger)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlicoLink("Editar", onClick = { editing = e.name })
            }
        }

        Spacer(Modifier.height(8.dp))
        BlicoButton("Novo agente", onClick = { editing = "" }, primary = false)

        val ready = entries.mapNotNull { it.agent }
        if (ready.isNotEmpty()) AgentTest(app, ready.map { it.name })
    }
}

@Composable
private fun AgentEditor(store: AgentStore, name: String?, initial: String, onDone: () -> Unit) {
    val p = LocalBlico.current
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; error = null },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(top = 8.dp),
    )
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger) }
    Column {
        BlicoButton("Salvar", onClick = {
            error = store.save(text, name)
            if (error == null) onDone()
        })
        BlicoButton("Cancelar", onClick = onDone, primary = false)
        if (name != null) {
            if (confirmDelete) {
                Text("Apagar o agente $name?", style = MaterialTheme.typography.bodyMedium)
                BlicoButton("Apagar", onClick = { store.delete(name); onDone() }, primary = false)
            } else {
                BlicoLink("Apagar", onClick = { confirmDelete = true })
            }
        }
    }
}

/** Roda um agente com um pedido digitado, igual ao falado, com o cartão Confirmar aqui mesmo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentTest(app: BlicoApp, names: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun ctx() = app.execContext { context.startActivity(it) }
    var chosen by remember { mutableStateOf(names.first()) }
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AgentResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(step: suspend () -> AgentResult) {
        scope.launch {
            busy = true
            val r = step()
            busy = false
            result = r
            app.say(r.outcome.spoken)
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Testar um agente", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { n -> BlicoChip(n, selected = n == chosen, onClick = { chosen = n }) }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("alarme às 6 e timer de 10 minutos") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    BlicoButton(if (busy) "Pensando…" else "Executar", enabled = text.isNotBlank() && !busy, onClick = {
        val agent = app.agents.agents().firstOrNull { it.name == chosen } ?: return@BlicoButton
        run { app.agentRunner.start(text, agent, ctx()) }
    })

    val r = result ?: return
    Text(r.outcome.spoken, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    r.steps.forEachIndexed { i, s ->
        val args = s.call.args.toSortedMap().entries.joinToString { "${it.key}=${it.value}" }
        Text("${i + 1}. ${s.call.tool}($args)", style = MaterialTheme.typography.bodySmall, color = LocalBlico.current.muted)
    }
    val paused = r.paused ?: return
    when (val out = r.outcome) {
        is Outcome.Confirm -> {
            out.summary.fields.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
            BlicoButton("Confirmar", enabled = !busy, onClick = {
                run { app.agentRunner.resume(paused, out.call, ctx(), confirmed = true) }
            })
        }
        is Outcome.Choose -> out.options.forEach { o ->
            BlicoButton(o.label, primary = false, enabled = !busy, onClick = {
                run { app.agentRunner.resume(paused, o.call, ctx(), confirmed = false) }
            })
        }
        else -> Unit
    }
}
