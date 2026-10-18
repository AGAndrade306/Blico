package app.blico.core.capability

import app.blico.core.RuleRouter
import app.blico.core.ToolCall

/**
 * Uma frase que as regras entendem sem modelo, trazida por uma [Capability].
 *
 * [pattern] casa com o pedido inteiro, já em minúsculas e sem "Blico," "por favor" e afins nas pontas.
 * Pode usar os mesmos trechos das regras de base, para todas falarem igual:
 * - `{F}`: muletas logo depois do verbo ("pra mim", "aí", "por favor", "rapidinho");
 * - `{PRO}`: "pro", "pra", "para o", "ao"...;
 * - `{TO}`: "pra", "até", "ao"... antes de um lugar;
 * - `{ART}`: artigo opcional ("o ", "a ", "os ", "as ").
 *
 * As regras das funcionalidades rodam antes das de base: "liga a lanterna" não pode virar ligação
 * para um contato chamado "lanterna". Por isso cada uma precisa ser bem ancorada, e o teste de
 * regressão confere que nenhuma frase das regras de base muda de destino.
 */
class SpeechRule(val pattern: String, val build: (RuleRouter.Match) -> ToolCall?)
