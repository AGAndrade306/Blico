package app.blico.core

import kotlinx.coroutines.CancellationException

/**
 * Benchmark de comandos para comparar cérebros (modelo local, nuvem) com as mesmas frases.
 * Os casos ficam em `benchmark/comandos.tsv`; rodar e pontuar não depende do Android.
 */
object Benchmark {
    sealed interface Expected {
        data class Call(val tool: String, val args: Map<String, String>) : Expected

        /** Pergunta que o próprio cérebro responde. */
        data object Answer : Expected

        /** Precisa de informação atual: o certo é passar a vez (confiança baixa). */
        data object Escalate : Expected
    }

    data class Case(val text: String, val expected: Expected) {
        val group: String get() = when (expected) {
            is Expected.Call -> "ações"
            Expected.Answer -> "perguntas"
            Expected.Escalate -> "passar para a nuvem"
        }
    }

    data class Result(val case: Case, val decision: LlmDecision?, val millis: Long, val error: String? = null) {
        fun passed(minConfidence: Double): Boolean = decision != null && passes(case, decision, minConfidence)

        /** O que o cérebro fez, numa linha: a ferramenta e os argumentos, a resposta ou "passou a vez". */
        fun got(minConfidence: Double): String = when {
            error != null -> "erro: ${error.substringAfter(": ")}"
            decision == null -> "nada"
            decision.confidence < minConfidence -> "passou a vez"
            else -> decision.calls.firstOrNull()?.let { c -> c.tool + c.args.entries.joinToString(", ", " (", ")") { "${it.key}=${it.value}" }.takeIf { c.args.isNotEmpty() }.orEmpty() }
                ?: decision.answer?.let { "“${it.take(80)}”" }
                ?: "nada"
        }

        /** O que era esperado, numa linha. */
        val wanted: String get() = when (val e = case.expected) {
            is Expected.Call -> e.tool + e.args.entries.joinToString(", ", " (", ")") { "${it.key}≈${it.value}" }.takeIf { e.args.isNotEmpty() }.orEmpty()
            Expected.Answer -> "responder"
            Expected.Escalate -> "passar a vez"
        }
    }

    data class Report(val results: List<Result>, val minConfidence: Double) {
        val passed: Int get() = results.count { it.passed(minConfidence) }

        fun byGroup(): Map<String, Pair<Int, Int>> =
            results.groupBy { it.case.group }.mapValues { (_, rs) -> rs.count { it.passed(minConfidence) } to rs.size }

        fun percentileMillis(p: Double): Long {
            val sorted = results.map { it.millis }.sorted()
            if (sorted.isEmpty()) return 0
            return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)]
        }

        /** Texto curto para a tela: acerto geral, por grupo e tempo. */
        fun summary(): String = buildString {
            append("Acertou $passed de ${results.size}")
            byGroup().forEach { (g, r) -> append("\n$g: ${r.first} de ${r.second}") }
            append("\nTempo típico ${seconds(percentileMillis(0.5))}, lento ${seconds(percentileMillis(0.9))}")
        }

        private fun seconds(ms: Long) = "%.1f s".format(java.util.Locale.forLanguageTag("pt-BR"), ms / 1000.0)
    }

    /** Linhas `esperado<TAB>comando<TAB>chave=trecho;...`; `#` começa comentário. */
    fun parse(tsv: String): List<Case> = tsv.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val cols = line.split('\t')
            require(cols.size >= 2) { "linha sem comando: $line" }
            val expected = when (val e = cols[0].trim()) {
                "resposta" -> Expected.Answer
                "nuvem" -> Expected.Escalate
                else -> Expected.Call(e, parseArgs(cols.getOrNull(2).orEmpty()))
            }
            Case(cols[1].trim(), expected)
        }
        .toList()

    private fun parseArgs(s: String): Map<String, String> =
        s.split(';').map { it.trim() }.filter { it.contains('=') }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    /** Os 100 comandos que vêm com o Blico. */
    fun builtin(): List<Case> {
        val text = Benchmark::class.java.getResourceAsStream("/benchmark/comandos.tsv")!!.bufferedReader().use { it.readText() }
        return parse(text)
    }

    fun passes(case: Case, decision: LlmDecision, minConfidence: Double): Boolean {
        val confident = decision.confidence >= minConfidence
        return when (val e = case.expected) {
            Expected.Escalate -> !confident
            Expected.Answer -> confident && decision.calls.isEmpty() && !decision.answer.isNullOrBlank()
            is Expected.Call -> {
                val call = decision.calls.firstOrNull()
                confident && call != null && call.tool == e.tool &&
                    e.args.all { (k, want) -> FileSearch.simplify(call.args[k].orEmpty()).contains(FileSearch.simplify(want)) }
            }
        }
    }

    /** Roda os casos um a um. [now] devolve milissegundos; nos testes é um relógio falso. */
    suspend fun run(
        provider: LlmProvider,
        cases: List<Case>,
        tools: List<Tool>,
        minConfidence: Double = 0.6,
        now: () -> Long = System::currentTimeMillis,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (Result) -> Unit = {},
    ): Report {
        val results = cases.mapIndexed { i, case ->
            val start = now()
            val result = try {
                Result(case, provider.chooseTools(LlmRequest(case.text, tools)), now() - start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result(case, null, now() - start, e.toString())
            }
            onResult(result)
            onProgress(i + 1, cases.size)
            result
        }
        return Report(results, minConfidence)
    }
}
