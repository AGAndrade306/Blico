package app.blico

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import app.blico.ui.LocalRiso
import app.blico.ui.RisoButton
import app.blico.ui.RisoCard
import app.blico.ui.RisoChip
import app.blico.ui.RisoMark
import app.blico.ui.RisoTheme
import app.blico.ui.RisoTitle
import app.blico.ui.grain
import app.blico.ui.paper
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.blico.assistant.FileResults
import app.blico.core.Outcome
import app.blico.memory.PrefsPlaceBook
import app.blico.voice.ListeningState
import app.blico.voice.VoiceOption
import app.blico.voice.VoicePrefs
import app.blico.voice.OnDeviceTranscriber
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Tela de configuração: permissões, assistente padrão e um teste de escuta. */
class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Papel sempre claro: ícones escuros nas barras do sistema, mesmo no modo escuro.
        val clara = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = clara, navigationBarStyle = clara)
        super.onCreate(savedInstanceState)
        setContent {
            RisoTheme(dark = false) {
                Box(Modifier.fillMaxSize().paper()) {
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
            val steps = listOf(micGranted, onDevice, isAssistant, contactsGranted, Environment.isExternalStorageManager())
            Header(ready = steps.count { it }, total = steps.size)
            Section("Preparar")

            Step(
                title = "1. Microfone",
                done = micGranted,
                detail = "Só abre depois do seu gesto, nunca fica ouvindo.",
            ) {
                RisoButton("Permitir microfone", onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) })
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
                RisoButton("Baixar português", onClick = { transcriber.requestLanguageDownload() }, primary = false)
            }

            Step(
                title = "3. Assistente padrão",
                done = isAssistant,
                detail = "Em Apps padrão › App de assistente digital, escolha o Blico.",
            ) {
                RisoButton("Abrir Apps padrão", onClick = ::openDefaultApps)
            }

            Step(
                title = "4. Botão lateral",
                done = null,
                detail = "Configurações › Recursos avançados › Botão lateral › Pressionar e segurar › Ativar assistente digital.",
            ) {
                RisoButton("Abrir Configurações", onClick = ::openSideKeySettings, primary = false)
            }

            Step(
                title = "5. Contatos",
                done = contactsGranted,
                detail = "Para achar o número quando você diz \"manda pro João\". A agenda não sai do celular.",
            ) {
                RisoButton("Permitir contatos", onClick = { askContacts.launch(Manifest.permission.READ_CONTACTS) })
            }

            Step(
                title = "6. Arquivos",
                done = Environment.isExternalStorageManager(),
                detail = "Para \"acha o pdf do contrato\" e \"fotos de ontem\". A busca é feita no celular e nada sai dele.",
            ) {
                RisoButton("Liberar acesso aos arquivos", onClick = ::openAllFilesAccess)
            }

            Section("Personalizar")
            VoiceSettings()
            Places()
            LocalModelCard(application as BlicoApp)
            Cloud()

            Section("Testar")
            CommandTest()
            LocalBenchmarkCard(application as BlicoApp)
            ListenTest(transcriber, enabled = micGranted)
            Spacer(Modifier.height(24.dp))
        }
    }

    /** Logo, nome com a tinta rosa fora de registro e quantos passos já estão prontos. */
    @Composable
    private fun Header(ready: Int, total: Int) {
        val p = LocalRiso.current
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
            Image(
                painter = painterResource(R.drawable.blico_logo),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                RisoTitle("Blico", style = MaterialTheme.typography.displaySmall)
                Text("seu assistente, no seu celular", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        val all = ready == total
        Text(
            if (all) "Tudo pronto. Segure o botão lateral e fale." else "$ready de $total passos prontos",
            style = MaterialTheme.typography.labelLarge,
            color = p.text,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(p.pink)
                .grain()
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }

    /** Título de seção: pequeno, verde, com um traço rosa embaixo. */
    @Composable
    private fun Section(title: String) {
        val p = LocalRiso.current
        Spacer(Modifier.height(22.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp), color = p.green)
        Box(Modifier.padding(top = 4.dp, bottom = 6.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.pink))
    }

    /** Passo de configuração. Pronto, ele encolhe para uma linha; pendente, mostra o porquê e o botão. */
    @Composable
    private fun Step(title: String, done: Boolean?, detail: String, action: @Composable () -> Unit) {
        val p = LocalRiso.current
        RisoCard(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            accent = if (done == true) p.green.copy(alpha = 0.35f) else p.pink,
            contentPadding = if (done == true) PaddingValues(horizontal = 18.dp, vertical = 12.dp) else PaddingValues(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                RisoMark(done)
            }
            if (done != true) {
                Spacer(Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = p.muted)
                Spacer(Modifier.height(12.dp))
                action()
            }
        }
    }

    /** Endereços de casa e trabalho, usados por "pede um uber pra casa" e "me leva pro trabalho". */
    @Composable
    private fun Places() {
        val places = (application as BlicoApp).places
        RisoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
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
        var fallback by remember { mutableStateOf(app.cloudFallback) }
        RisoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
                val ready = endpoint.startsWith("https://") && token.isNotEmpty()
                Text((if (ready) "✓ " else "○ ") + "Nuvem", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Para o que nem os comandos básicos nem o cérebro do celular resolvem. Nomes, números e endereços vão mascarados.",
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
                Spacer(Modifier.height(10.dp))
                Text("Quando o cérebro do celular não souber", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    RisoChip("Perguntar à nuvem", selected = fallback, onClick = { fallback = true; app.cloudFallback = true })
                    RisoChip("Só no celular", selected = !fallback, onClick = { fallback = false; app.cloudFallback = false })
                }
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
        var route by remember { mutableStateOf<String?>(null) }

        RisoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
                Text("Testar um comando", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("timer de 1 minuto") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                RisoButton("Executar", enabled = text.isNotBlank(), onClick = {
                    scope.launch {
                        route = null
                        val start = System.currentTimeMillis()
                        var via: String? = null
                        val out = app.assistant.handle(text, app.execContext { startActivity(it) }) { via = it }
                        route = via?.let { "Entendido por: ${ROUTES[it] ?: it}, em %.1f s".format(java.util.Locale.forLanguageTag("pt-BR"), (System.currentTimeMillis() - start) / 1000.0) }
                        result = out
                        app.say(out.spoken)
                    }
                })
                result?.let { Text(it.spoken, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp)) }
                route?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                (result as? Outcome.Files)?.let { files ->
                    FileResults(files.items) { call ->
                        scope.launch {
                            val out = app.assistant.execute(call, app.execContext { startActivity(it) })
                            result = out
                            app.say(out.spoken)
                        }
                    }
                }
                (result as? Outcome.Confirm)?.let { confirm ->
                    confirm.summary.fields.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
                    RisoButton("Confirmar", onClick = {
                        scope.launch {
                            val out = app.assistant.execute(confirm.call, app.execContext { startActivity(it) }, confirmed = true)
                            result = out
                            app.say(out.spoken)
                        }
                    })
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

        RisoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
                Text("Testar aqui", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                RisoButton(
                    "Falar",
                    enabled = enabled,
                    onClick = {
                        job?.cancel()
                        state = ListeningState()
                        job = scope.launch {
                            transcriber.listen().collect { state = state?.reduce(it) }
                        }
                    },
                )
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

        /** Quem entendeu o comando, em palavras. */
        val ROUTES = mapOf("regras" to "comandos básicos", "local" to "cérebro do celular", "nuvem" to "nuvem")
    }

    private fun openDefaultApps() = openFirst(
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )

    private fun openSideKeySettings() = openFirst(Intent(Settings.ACTION_SETTINGS))

    private fun openAllFilesAccess() = openFirst(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )

    /** Tela do sistema com os motores de voz, para instalar vozes novas. */
    private fun openTtsSettings() = openFirst(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_SETTINGS))

    /** Motor, voz, velocidade e tom. Só aparecem vozes que funcionam sem internet. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun VoiceSettings() {
        val app = application as BlicoApp
        val scope = rememberCoroutineScope()
        var prefs by remember { mutableStateOf(app.voicePrefs) }
        val engines = remember { app.speech.engines() }
        val currentEngine = prefs.engine ?: app.speech.defaultEngine()
        var voices by remember { mutableStateOf<List<VoiceOption>?>(null) }
        LaunchedEffect(currentEngine) { voices = null; voices = app.speech.voices() }

        fun update(p: VoicePrefs) {
            prefs = p
            app.voicePrefs = p
        }

        RisoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column {
                Text("Voz", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Escolha o motor e a voz. Só aparecem vozes que funcionam sem internet, para o que o Blico fala não sair do celular.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (engines.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text("Motor", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        engines.forEach { e ->
                            RisoChip(
                                e.label,
                                selected = e.packageName == currentEngine,
                                onClick = { update(prefs.copy(engine = e.packageName, voice = null)) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Voz", style = MaterialTheme.typography.labelLarge)
                val list = voices
                when {
                    list == null -> Text("Carregando vozes…", style = MaterialTheme.typography.bodySmall)
                    list.isEmpty() -> Text(
                        "Esse motor não tem voz em português instalada. Instale em Configurações de voz.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RisoChip("Padrão", selected = prefs.voice == null, onClick = { update(prefs.copy(voice = null)) })
                        list.forEach { v ->
                            RisoChip(
                                v.label,
                                selected = prefs.voice == v.name,
                                onClick = {
                                    update(prefs.copy(voice = v.name))
                                    app.say("Oi, eu sou o Blico. Essa é a minha voz.")
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Velocidade: ${"%.1f".format(prefs.rate)}x", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = prefs.rate,
                    onValueChange = { prefs = prefs.copy(rate = it) },
                    onValueChangeFinished = { update(prefs) },
                    valueRange = 0.7f..1.6f,
                    steps = 8,
                )
                Text("Tom: ${"%.1f".format(prefs.pitch)}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = prefs.pitch,
                    onValueChange = { prefs = prefs.copy(pitch = it) },
                    onValueChangeFinished = { update(prefs) },
                    valueRange = 0.7f..1.3f,
                    steps = 5,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RisoButton("Ouvir", onClick = { scope.launch { app.speech.speak("Oi, eu sou o Blico. Essa é a minha voz.") } })
                    RisoButton("Instalar mais vozes", onClick = ::openTtsSettings, primary = false)
                }
            }
        }
    }

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
