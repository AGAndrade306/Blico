package app.blico

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.blico.core.capability.MemoryText
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.LocalBlico
import kotlinx.coroutines.launch

/**
 * O que o Blico lembra, para ver, acrescentar e apagar. Por voz, guardar e esquecer passam pelo Confirmar;
 * aqui é a própria pessoa digitando ou tocando, então vale direto.
 */
@Composable
fun MemoryCard(app: BlicoApp) {
    val p = LocalBlico.current
    val scope = rememberCoroutineScope()
    val facts by app.memory.facts.collectAsState()
    LaunchedEffect(Unit) { app.memory.all() }
    var draft by remember { mutableStateOf("") }

    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Memória", style = MaterialTheme.typography.titleMedium)
        Text(
            "O que você pediu para o Blico lembrar. Fica só no celular, cifrado, e só o modelo do celular lê. " +
                "Diga \"lembra que…\", \"o que você sabe sobre…\" ou \"esquece que…\".",
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )
        val list = facts.orEmpty().sortedByDescending { it.at }
        if (list.isEmpty()) {
            Text("Nada guardado ainda.", style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 8.dp))
        }
        for (fact in list) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    fact.text + if (fact.sensitive) "  [sensível]" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                BlicoButton("Apagar", primary = false, onClick = { scope.launch { app.memory.remove(fact.id) } })
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Novo fato") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            BlicoButton("Guardar", enabled = draft.isNotBlank(), onClick = {
                val text = MemoryText.clean(draft)
                draft = ""
                scope.launch { app.memory.add(text, MemoryText.isSensitive(text)) }
            })
            if (list.isNotEmpty()) {
                BlicoButton("Apagar tudo", primary = false, onClick = { scope.launch { app.memory.clear() } })
            }
        }
    }
}
