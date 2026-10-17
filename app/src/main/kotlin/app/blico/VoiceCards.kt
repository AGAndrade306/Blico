package app.blico

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import app.blico.core.NeuralVoices
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoChip
import app.blico.ui.BlicoProgress
import app.blico.ui.LocalBlico
import app.blico.voice.PackState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SAMPLE = "Oi, eu sou o Blico. Mandei a mensagem para o João e marquei o alarme para as sete e meia."

/** Vozes neurais: baixar, escolher e ouvir. Rodam no celular, sem internet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeuralVoiceCard(app: BlicoApp) {
    val p = LocalBlico.current
    val scope = rememberCoroutineScope()
    val states by app.voicePacks.states.collectAsState()
    var selected by remember { mutableStateOf(app.voicePrefs.neural) }
    val busy = states.values.any { it is PackState.Downloading || it is PackState.Unpacking }
    // O DownloadManager não avisa o andamento: enquanto algo baixa, a tela pergunta a cada segundo.
    LaunchedEffect(busy) {
        while (busy) {
            app.voicePacks.poll()
            delay(1000)
        }
    }

    fun choose(id: String?) {
        val before = NeuralVoices.byId(selected)?.pack
        selected = id
        app.voicePrefs = app.voicePrefs.copy(neural = id)
        scope.launch {
            if (before != NeuralVoices.byId(id)?.pack) app.neuralSpeech.unload()
            app.speech.stop()
            app.speech.speak(SAMPLE)
        }
    }

    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Vozes neurais", style = MaterialTheme.typography.titleMedium)
        Text(
            "Vozes naturais que rodam no próprio celular, sem internet. As do Kokoro são as mais naturais e vêm juntas num download de 132 MB; as do Piper são leves e falam na hora.",
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )
        Spacer(Modifier.height(6.dp))
        BlicoChip("Usar a voz do sistema", selected = selected == null, onClick = { choose(null) })

        NeuralVoices.all.forEach { voice ->
            val state = states[voice.pack.id] ?: PackState.Missing
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(voice.name + if (voice.id == selected && state == PackState.Ready) " · em uso" else "", style = MaterialTheme.typography.labelLarge)
                    Text(voice.detail, style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
            }
            when (state) {
                PackState.Missing, is PackState.Failed -> {
                    if (state is PackState.Failed) Text(state.message, style = MaterialTheme.typography.bodySmall, color = p.danger)
                    BlicoButton(
                        "Baixar · ${voice.pack.approxBytes / 1_000_000} MB",
                        primary = false,
                        onClick = { app.voicePacks.download(voice.pack) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                is PackState.Downloading -> {
                    BlicoProgress(state.done.toFloat() / state.total.coerceAtLeast(1), Modifier.padding(top = 8.dp, bottom = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Baixando: ${state.done / 1_000_000} de ${state.total / 1_000_000} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.muted,
                            modifier = Modifier.weight(1f),
                        )
                        BlicoButton("Cancelar", primary = false, onClick = { app.voicePacks.cancel(voice.pack) })
                    }
                }
                PackState.Unpacking -> Text("Preparando a voz…", style = MaterialTheme.typography.bodySmall, color = p.muted)
                PackState.Ready -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (voice.id == selected) {
                        BlicoButton("Ouvir", onClick = { scope.launch { app.speech.stop(); app.speech.speak(SAMPLE) } })
                    } else {
                        BlicoButton("Usar esta voz", onClick = { choose(voice.id) })
                    }
                    // Apagar tira o pacote inteiro: no Kokoro, as três vozes.
                    BlicoButton("Apagar", primary = false, onClick = {
                        scope.launch {
                            if (NeuralVoices.byId(selected)?.pack == voice.pack) {
                                app.neuralSpeech.unload()
                                selected = null
                                app.voicePrefs = app.voicePrefs.copy(neural = null)
                            }
                            app.voicePacks.delete(voice.pack)
                        }
                    })
                }
            }
        }
    }
}
