package app.blico

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.blico.voice.ListeningState
import app.blico.voice.OnDeviceTranscriber
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Tela de configuração: permissões, assistente padrão e um teste de escuta. */
class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    SetupScreen(resumeTick)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++ // Rechecar permissões e papel ao voltar das Configurações.
    }

    // [tick] muda a cada onResume; a recomposição relê permissões e papel, que não são observáveis.
    @Suppress("UNUSED_PARAMETER")
    @Composable
    private fun SetupScreen(tick: Int) {
        val transcriber = OnDeviceTranscriber(this)
        val micGranted =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val roleManager = getSystemService(RoleManager::class.java)
        val isAssistant = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        val onDevice = transcriber.isAvailable()

        val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            resumeTick++
        }

        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("Blico", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Assistente que roda no seu celular. Configure os passos abaixo.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            Step(
                title = "1. Microfone",
                done = micGranted,
                detail = "Só abre depois do seu gesto, nunca fica ouvindo.",
            ) {
                Button(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Permitir microfone") }
            }

            Step(
                title = "2. Reconhecimento de voz no aparelho",
                done = onDevice,
                detail = if (onDevice) {
                    "Disponível. Se der erro de idioma, baixe o pacote de português."
                } else {
                    "Indisponível neste aparelho. Instale ou atualize o app de reconhecimento de fala do sistema."
                },
            ) {
                OutlinedButton(onClick = { transcriber.requestLanguageDownload() }) { Text("Baixar português") }
            }

            Step(
                title = "3. Assistente padrão",
                done = isAssistant,
                detail = "Em Apps padrão › App de assistente digital, escolha o Blico.",
            ) {
                Button(onClick = ::openDefaultApps) { Text("Abrir Apps padrão") }
            }

            Step(
                title = "4. Botão lateral",
                done = null,
                detail = "Configurações › Recursos avançados › Botão lateral › Pressionar e segurar › Ativar assistente digital.",
            ) {
                OutlinedButton(onClick = ::openSideKeySettings) { Text("Abrir Configurações") }
            }

            Spacer(Modifier.height(8.dp))
            ListenTest(transcriber, enabled = micGranted)
        }
    }

    @Composable
    private fun Step(title: String, done: Boolean?, detail: String, action: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                val mark = when (done) {
                    true -> "✓ "
                    false -> "○ "
                    null -> ""
                }
                Text(mark + title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
                if (done != true) {
                    Spacer(Modifier.height(8.dp))
                    action()
                }
            }
        }
    }

    /** Testa a transcrição sem depender do papel de assistente. */
    @Composable
    private fun ListenTest(transcriber: OnDeviceTranscriber, enabled: Boolean) {
        val scope = rememberCoroutineScope()
        var state by remember { mutableStateOf<ListeningState?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }

        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Testar aqui", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = enabled,
                    onClick = {
                        job?.cancel()
                        state = ListeningState()
                        job = scope.launch {
                            transcriber.listen().collect { state = state?.reduce(it) }
                        }
                    },
                ) { Text("Falar") }
                state?.let { s ->
                    Spacer(Modifier.height(8.dp))
                    Text(s.label, style = MaterialTheme.typography.labelLarge)
                    if (s.text.isNotBlank()) Text(s.text, style = MaterialTheme.typography.bodyLarge)
                    val metrics = listOfNotNull(
                        s.setupMs?.let { "microfone em $it ms" },
                        s.latencyMs?.let { "texto $it ms após a fala" },
                    )
                    if (metrics.isNotEmpty()) Text(metrics.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    private fun openDefaultApps() = openFirst(
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    private fun openSideKeySettings() = openFirst(Intent(Settings.ACTION_SETTINGS))

    private fun openFirst(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // tenta o próximo
            }
        }
    }
}
