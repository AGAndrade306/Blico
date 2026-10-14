package app.blico

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.blico.ui.LocalBlico
import app.blico.ui.BlicoCard
import app.blico.ui.BlicoProgress
import kotlinx.coroutines.Job
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

/** Mede o cérebro do celular nos 100 comandos do benchmark: acerto e tempo. */
@Composable
fun LocalBenchmarkCard(app: BlicoApp) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<String?>(null) }
    var misses by remember { mutableStateOf<List<String>>(emptyList()) }
    val ready = app.local.isReady()

    BlicoCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column {
            Text("Benchmark do cérebro no celular", style = MaterialTheme.typography.titleMedium)
            Text(
                if (ready) {
                    "Roda 100 pedidos variados direto no Gemma, sem as regras e sem a nuvem, e mostra quanto ele acerta e quanto demora."
                } else {
                    "Baixe um modelo em Cérebro no celular para medir."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            if (job == null) {
                BlicoButton("Rodar benchmark", enabled = ready, onClick = {
                    summary = null
                    misses = emptyList()
                    job = scope.launch {
                        try {
                            val tools = app.assistant.modelTools()
                            val report = Benchmark.run(app.local, Benchmark.builtin(), tools) { done, total -> progress = "$done de $total" }
                            summary = "${app.models.selected?.label} na ${app.local.backend ?: "CPU"}\n" + report.summary()
                            misses = report.results.filterNot { it.passed(report.minConfidence) }.map { r ->
                                val got = r.error ?: r.decision?.let { d ->
                                    d.calls.firstOrNull()?.let { c -> "${c.tool} ${c.args}" } ?: d.answer?.take(60) ?: "passou a vez"
                                }
                                "“${r.case.text}” → $got"
                            }
                        } finally {
                            job = null
                        }
                    }
                })
            } else {
                Text("Rodando: $progress", style = MaterialTheme.typography.bodyMedium)
                BlicoButton("Parar", primary = false, onClick = { job?.cancel() })
            }
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (misses.isNotEmpty()) {
                Text("Erros", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                misses.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun gb(bytes: Long) = "%.1f GB".format(java.util.Locale.forLanguageTag("pt-BR"), bytes / 1e9)
