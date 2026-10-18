package app.blico

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.blico.core.capability.Availability
import app.blico.core.capability.Permission
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoChip
import app.blico.ui.BlicoMark
import app.blico.ui.LocalBlico

/**
 * Cada funcionalidade do Blico com o que ela faz, se está ligada e o que falta liberar.
 * Desligar uma tira as ferramentas dela dos modelos; o pedido por voz responde que está desligada.
 */
@Composable
fun CapabilitiesCard(app: BlicoApp, tick: Int) {
    val context = LocalContext.current
    val p = LocalBlico.current
    // Acessos especiais não são observáveis: [tick] muda ao voltar das Configurações.
    var local by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf<Permission?>(null) }
    // Negada de vez, o Android nem mostra o diálogo: abre os dados do app para liberar por lá.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        local++
        if (result.values.any { !it }) asked?.let { AndroidPermissions.openSettings(context, it) }
    }

    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Funcionalidades", style = MaterialTheme.typography.titleMedium)
        Text(
            "O que o Blico sabe fazer no celular. Desligue o que não quiser usar; o que precisa de acesso diz por quê.",
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )
        val status = remember(tick, local) { app.capabilities.status() }
        for ((cap, availability) in status) {
            val on = remember(tick, local) { app.capabilities.isOn(cap.id) }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cap.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                BlicoMark(on && availability == Availability.Ready)
            }
            Text(cap.summary, style = MaterialTheme.typography.bodySmall, color = p.muted)
            if (availability is Availability.Unsupported) {
                Text(availability.why, style = MaterialTheme.typography.bodySmall, color = p.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                BlicoChip("Ligado", selected = on, onClick = { app.setCapabilityOn(cap.id, true); local++ })
                BlicoChip("Desligado", selected = !on, onClick = { app.setCapabilityOn(cap.id, false); local++ })
            }
            if (on) {
                for (permission in cap.permissions) {
                    val granted = remember(tick, local) { AndroidPermissions.granted(context, permission) }
                    if (granted) continue
                    Text(
                        "Falta ${permission.label}, ${permission.why}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    BlicoButton("Liberar", primary = false, onClick = {
                        val runtime = AndroidPermissions.runtime(permission)
                        if (runtime.isNotEmpty()) {
                            asked = permission
                            ask.launch(runtime.toTypedArray())
                        } else AndroidPermissions.openSettings(context, permission)
                    }, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** Como cada [Permission] do `core` vira permissão do Android: pedido em diálogo ou tela de acesso especial. */
object AndroidPermissions {
    /** Permissões pedidas em diálogo. Vazia quando o acesso é especial, dado numa tela do sistema. */
    fun runtime(p: Permission): List<String> = when (p) {
        Permission.DO_NOT_DISTURB, Permission.WRITE_SETTINGS -> emptyList()
        Permission.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        Permission.NOTIFICATIONS -> listOf(Manifest.permission.POST_NOTIFICATIONS)
        Permission.SMS -> listOf(Manifest.permission.SEND_SMS)
    }

    fun granted(context: Context, p: Permission): Boolean = when (p) {
        Permission.DO_NOT_DISTURB -> context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        Permission.WRITE_SETTINGS -> Settings.System.canWrite(context)
        Permission.CALENDAR, Permission.NOTIFICATIONS, Permission.SMS ->
            runtime(p).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** Abre a tela onde a pessoa libera um acesso especial, já no Blico quando o Android deixa. */
    fun openSettings(context: Context, p: Permission) {
        val pkg = Uri.fromParts("package", context.packageName, null)
        val intents = when (p) {
            Permission.DO_NOT_DISTURB -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Permission.WRITE_SETTINGS -> listOf(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg))
            Permission.CALENDAR, Permission.NOTIFICATIONS, Permission.SMS -> emptyList()
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // tenta a próxima
            }
        }
    }
}
