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
import androidx.compose.material3.OutlinedTextField
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
import app.blico.core.Outcome
import app.blico.memory.PrefsPlaceBook
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

        val contactsGranted =
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            resumeTick++
        }
        val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
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

            Step(
                title = "5. Contatos",
                done = contactsGranted,
                detail = "Para achar o número quando você diz \"manda pro João\". A agenda não sai do celular.",
            ) {
                Button(onClick = { askContacts.launch(Manifest.permission.READ_CONTACTS) }) { Text("Permitir contatos") }
            }

            Places()
            Cloud()

            Spacer(Modifier.height(8.dp))
            ListenTest(transcriber, enabled = micGranted)
            CommandTest()
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

    /** Endereços de casa e trabalho, usados por "pede um uber pra casa" e "me leva pro trabalho". */
    @Composable
    private fun Places() {
        val places = (application as BlicoApp).places
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Lugares", style = MaterialTheme.typography.titleMedium)
                Text("Usados no Uber e na navegação.", style = MaterialTheme.typography.bodySmall)
                PrefsPlaceBook.ALIASES.forEach { alias ->
                    var value by remember { mutableStateOf(places.resolve(alias).orEmpty()) }
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            places.set(alias, it)
                        },
                        label = { Text(alias.replaceFirstChar(Char::uppercase)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }

    /** Servidor da nuvem (Cloudflare Worker). Sem ele, o Blico só entende os comandos básicos. */
    @Composable
    private fun Cloud() {
        val app = application as BlicoApp
        var endpoint by remember { mutableStateOf(app.cloudEndpoint) }
        var token by remember { mutableStateOf(if (app.cloudToken.isEmpty()) "" else TOKEN_MASK) }
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                val ready = endpoint.startsWith("https://") && token.isNotEmpty()
                Text((if (ready) "✓ " else "○ ") + "Nuvem", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Para perguntas e pedidos que os comandos básicos não cobrem. Nomes, números e endereços vão mascarados.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        app.cloudEndpoint = it
                    },
                    label = { Text("Endereço do servidor") },
                    placeholder = { Text("https://blico.seu-usuario.workers.dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        // Colar por cima da máscara não pode gravar os pontos junto com o token.
                        val clean = it.replace("•", "")
                        token = if (it == TOKEN_MASK) it else clean
                        if (it != TOKEN_MASK) app.cloudToken = clean
                    },
                    label = { Text("Token do aparelho") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    /** Roda um comando digitado, igual ao falado. Útil para testar sem falar. */
    @Composable
    private fun CommandTest() {
        val app = application as BlicoApp
        val scope = rememberCoroutineScope()
        var text by remember { mutableStateOf("") }
        var result by remember { mutableStateOf<Outcome?>(null) }

        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Testar um comando", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("timer de 1 minuto") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                Button(enabled = text.isNotBlank(), onClick = {
                    scope.launch {
                        val out = app.assistant.handle(text, app.execContext { startActivity(it) })
                        result = out
                        app.say(out.spoken)
                    }
                }) { Text("Executar") }
                result?.let { Text(it.spoken, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp)) }
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

    private companion object {
        /** Mostrado no campo quando já existe um token salvo; o token em si nunca volta para a tela. */
        const val TOKEN_MASK = "••••••••"
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
