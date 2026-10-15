package app.blico

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoChip
import app.blico.ui.BlicoMark
import app.blico.ui.LocalBlico

/**
 * Ligar e enviar até o fim. O toque no cartão Confirmar do Blico continua obrigatório (regra 2);
 * ele só substitui o toque no botão de ligar do discador e no enviar do WhatsApp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DirectActionsCard(app: BlicoApp, tick: Int) {
    val context = LocalContext.current
    val p = LocalBlico.current
    // Permissão e acessibilidade não são observáveis: [tick] muda ao voltar das Configurações.
    var local by remember { mutableIntStateOf(0) }
    val canCall = remember(tick, local) { app.canCall }
    val accessibility = remember(tick, local) { app.accessibilityOn }
    val askCall = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { local++ }

    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Ligar e enviar sem o toque extra", style = MaterialTheme.typography.titleMedium)
        Text(
            "Você confirma uma vez no cartão do Blico e ele faz o resto: completa a ligação e toca em enviar no WhatsApp. " +
                "Sem o seu toque em Confirmar, nada é enviado.",
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ligar direto", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            BlicoMark(canCall && app.directCalls)
        }
        if (canCall) {
            OnOff(app.directCalls) { app.directCalls = it; local++ }
        } else {
            Text("Precisa da permissão de fazer chamadas.", style = MaterialTheme.typography.bodySmall, color = p.muted)
            BlicoButton("Permitir ligações", onClick = { askCall.launch(Manifest.permission.CALL_PHONE) }, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enviar no WhatsApp", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            BlicoMark(accessibility && app.directWhatsApp)
        }
        if (accessibility) {
            OnOff(app.directWhatsApp) { app.directWhatsApp = it; local++ }
        } else {
            Text(
                "Ligue \"Blico: enviar no WhatsApp\" em Acessibilidade › Apps instalados. Ele só age no WhatsApp e não lê suas conversas.\n" +
                    "Se o Android disser que é uma configuração restrita: abra os dados do app, toque em ⋮ › Permitir configurações restritas e tente de novo.",
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                BlicoButton("Abrir Acessibilidade", onClick = {
                    context.openFirst(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                })
                BlicoButton("Dados do app", primary = false, onClick = {
                    context.openFirst(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                })
            }
        }
    }
}

@Composable
private fun OnOff(on: Boolean, set: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        BlicoChip("Até o fim", selected = on, onClick = { set(true) })
        BlicoChip("Só deixar pronto", selected = !on, onClick = { set(false) })
    }
}

private fun Context.openFirst(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // tenta o próximo
        }
    }
}
