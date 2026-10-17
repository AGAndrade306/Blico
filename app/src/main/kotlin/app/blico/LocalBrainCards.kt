package app.blico

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.blico.brain.LocalModel
import app.blico.brain.ModelDownload
import app.blico.core.Benchmark
import app.blico.ui.BlicoButton
import app.blico.ui.BlicoLink
import app.blico.ui.LocalBlico
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Escolher, baixar e apagar o Gemma que roda no celular. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalModelCard(app: BlicoApp) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var download by remember { mutableStateOf(app.models.poll()) }
    var error by remember { mutableStateOf<String?>(null) }
    // O DownloadManager não avisa o andamento; enquanto baixa, a tela pergunta a cada segundo.
    LaunchedEffect(download is ModelDownload.Running, refresh) {
        while (download is ModelDownload.Running) {
            delay(1000)
            val now = app.models.poll()
            if (now !is ModelDownload.Running) scope.launch { app.local.unload() }
            download = now
        }
    }
    // Disco e preferências não são observáveis: relidos quando algo muda aqui.
    val installed = remember(refresh, download) { LocalModel.entries.filter(app.models::isInstalled).toSet() }
    val selected = remember(refresh, download) { app.models.selected?.takeIf { it in installed } }

    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column {
            Text((if (selected != null) "✓ " else "○ ") + "Cérebro no celular", style = MaterialTheme.typography.titleMedium)
            Text(
                "O Gemma entende pedidos fora dos comandos básicos e responde perguntas sem internet. " +
                    "O download é feito uma vez, pelo Wi-Fi ou pelos dados móveis, se você liberar.",
                style = MaterialTheme.typography.bodySmall,
            )
            LocalModel.entries.forEach { model ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "${model.label} · ${gb(model.approxBytes)}" + if (model == selected) " · em uso" else "",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(model.detail, style = MaterialTheme.typography.bodySmall)
                val running = (download as? ModelDownload.Running)?.takeIf { it.model == model }
                when {
                    running != null -> {
                        BlicoProgress(running.done.toFloat() / running.total.coerceAtLeast(1), Modifier.padding(top = 8.dp, bottom = 4.dp))
                        Text("${gb(running.done)} de ${gb(running.total)}", style = MaterialTheme.typography.bodySmall)
                        running.waiting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalBlico.current.muted) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            if (running.canUseMobileData) {
                                BlicoButton("Usar dados móveis", onClick = {
                                    error = app.models.useMobileData()
                                    download = app.models.poll()
                                })
                            }
                            BlicoButton("Cancelar", primary = false, onClick = {
                                app.models.cancel()
                                download = ModelDownload.Idle
                            })
                        }
                    }
                    model in installed -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        if (model != selected) {
                            BlicoButton("Usar este", onClick = {
                                app.models.selected = model
                                scope.launch { app.local.unload() }
                                refresh++
                            })
                        }
                        BlicoButton("Apagar", primary = false, onClick = {
                            scope.launch {
                                app.local.unload()
                                app.models.delete(model)
                                refresh++
                            }
                        })
                    }
                    else -> BlicoButton(
                        "Baixar",
                        enabled = download !is ModelDownload.Running,
                        modifier = Modifier.padding(top = 6.dp),
                        onClick = {
                            error = app.models.download(model)
                            download = app.models.poll()
                        },
                    )
                }
            }
            val failed = (download as? ModelDownload.Failed)?.message
            (error ?: failed)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * Mede o cérebro do celular nos 100 comandos do benchmark: acerto e tempo.
 * Roda no app, então continua se a tela fechar; os resultados aparecem um a um.
 */
@Composable
fun LocalBenchmarkCard(app: BlicoApp) {
    val p = LocalBlico.current
    val state by app.benchmark.state.collectAsState()
    var showMisses by remember { mutableStateOf(false) }
    val ready = app.local.isReady()

    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Benchmark do cérebro no celular", style = MaterialTheme.typography.titleMedium)
        Text(
            if (ready || state.running) {
                "Roda 100 pedidos variados direto no Gemma, sem as regras e sem a nuvem, e mostra quanto ele acerta e quanto demora. " +
                    "Pode sair desta tela: ele continua rodando."
            } else {
                "Baixe um modelo em Cérebro no celular para medir."
            },
            style = MaterialTheme.typography.bodySmall,
            color = p.muted,
        )
        Spacer(Modifier.height(10.dp))

        if (state.running) {
            val done = state.results.size
            Text(
                "${state.label}: $done de ${state.total}" + if (done > 0) " · acertou ${state.passed}" else " · carregando o modelo…",
                style = MaterialTheme.typography.bodyMedium,
            )
            BlicoProgress(done.toFloat() / state.total.coerceAtLeast(1), Modifier.padding(vertical = 8.dp))
            // Os últimos pedidos, do mais novo para o mais antigo.
            state.results.takeLast(5).asReversed().forEach { r -> ResultRow(r, state.minConfidence) }
            BlicoButton("Parar", primary = false, onClick = { app.benchmark.stop() }, modifier = Modifier.padding(top = 6.dp))
        } else {
            BlicoButton(if (state.last == null) "Rodar benchmark" else "Rodar de novo", enabled = ready, onClick = {
                showMisses = false
                app.benchmark.start()
            })
        }

        state.last?.takeIf { !state.running }?.let { last ->
            Spacer(Modifier.height(12.dp))
            Text("Último resultado · ${last.label} · ${last.at}", style = MaterialTheme.typography.labelMedium, color = p.muted)
            Text(last.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            if (last.misses.isNotEmpty()) {
                BlicoLink(
                    if (showMisses) "Esconder os erros" else "Ver os ${last.misses.size} erros",
                    onClick = { showMisses = !showMisses },
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (showMisses) {
                    last.misses.forEach { miss ->
                        Text(miss, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 5.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: Benchmark.Result, minConfidence: Double) {
    val p = LocalBlico.current
    val ok = r.passed(minConfidence)
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(if (ok) "✓" else "✗", color = if (ok) p.green else p.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(r.case.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                (if (ok) "" else "esperado ${r.wanted} · ") + "veio ${r.got(minConfidence)} · " +
                    "%.1f s".format(java.util.Locale.forLanguageTag("pt-BR"), r.millis / 1000.0),
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
            )
        }
    }
}

private fun gb(bytes: Long) = "%.1f GB".format(java.util.Locale.forLanguageTag("pt-BR"), bytes / 1e9)
