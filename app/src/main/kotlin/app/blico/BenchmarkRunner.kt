package app.blico

import android.content.Context
import app.blico.core.Benchmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Como está o benchmark: rodando, com os resultados que já saíram, ou o último completo. */
data class BenchmarkState(
    val running: Boolean = false,
    val label: String = "",
    val total: Int = 0,
    val results: List<Benchmark.Result> = emptyList(),
    val minConfidence: Double = 0.6,
    /** Resumo do último benchmark completo, guardado entre aberturas do app. */
    val last: SavedBenchmark? = null,
) {
    val passed: Int get() = results.count { it.passed(minConfidence) }
}

data class SavedBenchmark(val label: String, val at: String, val summary: String, val misses: List<String>)

/**
 * Roda o benchmark no escopo do app, não da tela: sair da tela ou apagar a tela não interrompe.
 * Os resultados aparecem um a um, e o resumo do último completo fica guardado.
 */
class BenchmarkRunner(private val app: BlicoApp, private val scope: CoroutineScope) {
    private val prefs = app.getSharedPreferences("benchmark", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(BenchmarkState(last = load()))
    val state: StateFlow<BenchmarkState> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val cases = Benchmark.builtin()
        val label = "${app.models.selected?.label ?: "Modelo"} na ${app.local.backend ?: "CPU"}"
        _state.update { it.copy(running = true, label = label, total = cases.size, results = emptyList()) }
        job = scope.launch {
            try {
                val report = Benchmark.run(app.local, cases, app.assistant.modelTools(), onResult = { r ->
                    _state.update { it.copy(results = it.results + r) }
                })
                // O motor só sabe se ficou na GPU ou na CPU depois de carregar.
                val finalLabel = "${app.models.selected?.label ?: "Modelo"} na ${app.local.backend ?: "CPU"}"
                val saved = SavedBenchmark(
                    label = finalLabel,
                    at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()),
                    summary = report.summary(),
                    misses = report.results.filterNot { it.passed(report.minConfidence) }
                        .map { "“${it.case.text}”\nesperado: ${it.wanted}\nveio: ${it.got(report.minConfidence)}" },
                )
                save(saved)
                _state.update { it.copy(running = false, label = finalLabel, last = saved) }
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false) }
                throw e
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { it.copy(running = false) }
    }

    private fun save(s: SavedBenchmark) {
        prefs.edit()
            .putString("rotulo", s.label)
            .putString("quando", s.at)
            .putString("resumo", s.summary)
            .putString("erros", s.misses.joinToString(SEPARATOR))
            .apply()
    }

    private fun load(): SavedBenchmark? {
        val summary = prefs.getString("resumo", null) ?: return null
        return SavedBenchmark(
            label = prefs.getString("rotulo", null).orEmpty(),
            at = prefs.getString("quando", null).orEmpty(),
            summary = summary,
            misses = prefs.getString("erros", null).orEmpty().split(SEPARATOR).filter { it.isNotBlank() },
        )
    }

    private companion object {
        const val SEPARATOR = "\u001e"
    }
}
