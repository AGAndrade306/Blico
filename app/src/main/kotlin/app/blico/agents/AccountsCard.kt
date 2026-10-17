package app.blico.agents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.blico.BlicoApp
import app.blico.accounts.GoogleLogin
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoLink
import app.blico.ui.LocalBlico
import kotlinx.coroutines.launch

/** As contas que os agentes usam. Hoje, o Google: Agenda e Gmail. */
@Composable
internal fun AccountsCard(app: BlicoApp) {
    val p = LocalBlico.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(app.google.connected) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun after(step: GoogleLogin.Step) {
        busy = false
        when (step) {
            GoogleLogin.Step.Done -> { connected = true; error = null }
            is GoogleLogin.Step.Failed -> error = step.reason
            is GoogleLogin.Step.Show -> Unit
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        after(app.google.finish(r.data))
    }

    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar("google", size = 36.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Conta Google", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (connected) "Agenda e Gmail liberados. Criar evento e enviar e-mail param no Confirmar."
                    else "Entre para os agentes verem a agenda e os e-mails. O acesso fica só no celular.",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger, modifier = Modifier.padding(top = 6.dp)) }
        if (connected) {
            BlicoLink("Sair da conta", onClick = { app.google.disconnect(); connected = false })
        } else {
            BlicoButton(if (busy) "Abrindo…" else "Entrar com Google", enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    when (val step = app.google.connect()) {
                        is GoogleLogin.Step.Show -> launcher.launch(IntentSenderRequest.Builder(step.intent.intentSender).build())
                        else -> after(step)
                    }
                }
            })
        }
    }
}
