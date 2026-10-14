package app.blico.core

/** O que o cartão Confirmar mostra: um título e pares rótulo/valor vindos da chamada estruturada. */
data class ConfirmationSummary(
    val title: String,
    val fields: List<Pair<String, String>> = emptyList(),
)
