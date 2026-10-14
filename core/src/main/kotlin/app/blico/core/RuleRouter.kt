package app.blico.core

/**
 * Primeiro nível do cérebro: entende os comandos mais comuns com regras, sem modelo nenhum.
 * Rápido, previsível e offline. O que não casar aqui vai para o modelo (local ou nuvem).
 */
class RuleRouter : LlmProvider {
    override val id = "regras"

    override suspend fun chooseTools(request: LlmRequest): LlmDecision {
        val call = parse(request.text)
        return if (call == null) LlmDecision(emptyList(), 0.0) else LlmDecision(listOf(call), 1.0)
    }

    fun parse(raw: String): ToolCall? {
        val text = Text.of(raw) ?: return null
        for (rule in RULES) {
            val m = rule.regex.matchEntire(text.lower) ?: continue
            rule.build(Match(m, text.original))?.let { return it }
        }
        return null
    }

    /** As regras casam no texto em minúsculas, mas os valores saem do original, com maiúsculas. */
    private class Text(val original: String, val lower: String) {
        companion object {
            fun of(raw: String): Text? {
                var original = raw.trim().replace(Regex("\\s+"), " ")
                var lower = original.lowercase()
                if (lower.length != original.length) original = lower // raro: caixa que muda o tamanho
                LEAD.find(lower)?.let { original = original.substring(it.range.last + 1); lower = lower.substring(it.range.last + 1) }
                TAIL.find(lower)?.let { original = original.substring(0, it.range.first); lower = lower.substring(0, it.range.first) }
                return Text(original.trim(), lower.trim()).takeIf { it.lower.isNotEmpty() && it.lower.length == it.original.length }
            }
        }
    }

    private class Match(private val m: MatchResult, private val original: String) {
        /** Valor do grupo no texto original, sem espaços nas pontas. */
        operator fun get(name: String): String? =
            m.groups[name]?.range?.let { original.substring(it.first, it.last + 1).trim() }?.takeIf { it.isNotEmpty() }

        /** Valor do grupo em minúsculas, para números e horários. */
        fun lower(name: String): String? = m.groups[name]?.value?.trim()
    }

    private class Rule(pattern: String, val build: (Match) -> ToolCall?) {
        val regex = Regex(pattern)
    }

    private companion object {
        private const val PRO = "(?:pro|pra|para o|para a|para|ao|à|a|o)"
        private const val OPT_ART = "(?:o |a |os |as )?"
        private const val SEND = "(?:manda|mande|envia|envie|escreve|escreva|fala|avisa|diz|diga)"
        private const val MSG = "(?: (?:uma |um )?(?:mensagem|msg|zap|whats|whatsapp|recado))?(?: no (?:whats|whatsapp|zap))?"
        private const val SEP = "(?:,? (?:dizendo|falando|avisando|perguntando)(?: que)?|,? que|:|,)"
        private const val FILE_WORDS = "arquivos?|pdfs?|documentos?|fotos?|imagens?|imagem|vídeos?|videos?|planilhas?|áudios?|audios?|gravaç(?:ão|ões)|apresentaç(?:ão|ões)|prints?|capturas? de tela"
        private const val TO_PLACE = "(?:pra|para|pro|até|ate|ao|à)"

        private fun g(m: Match, name: String) = m[name]

        val RULES = listOf(
            // "manda pro João que eu chego em dez minutos"
            Rule("^$SEND$MSG $PRO (?<contato>.+?)$SEP (?<texto>.+)$") { m ->
                ToolCall("whatsapp_preparar", mapOf("contato" to g(m, "contato")!!, "texto" to g(m, "texto")!!))
            },
            // "manda mensagem pro João"
            Rule("^$SEND$MSG $PRO (?<contato>.+)$") { m ->
                ToolCall("whatsapp_preparar", mapOf("contato" to g(m, "contato")!!))
            },
            // "liga pra mãe", "ligar para o João"
            Rule("^(?:liga|ligue|ligar|telefona|chama)(?: agora)? $PRO (?<contato>.+)$") { m ->
                ToolCall("ligar", mapOf("contato" to g(m, "contato")!!))
            },
            // "pede um uber pra casa", "chama um uber para o shopping"
            Rule("^(?:pede|peça|chama|chame|me chama)(?: um| uma)? uber(?: $TO_PLACE $OPT_ART(?<destino>.+))?$") { m ->
                g(m, "destino")?.let { ToolCall("uber_preparar", mapOf("destino" to it)) }
                    ?: ToolCall("abrir_app", mapOf("nome" to "Uber"))
            },
            Rule("^(?:pede|peça|chama|chame)(?: um| uma)? (?:99|noventa e nove|nove nove)(?: $TO_PLACE $OPT_ART(?<destino>.+))?$") { m ->
                ToolCall("noventa_e_nove_abrir", g(m, "destino")?.let { mapOf("destino" to it) } ?: emptyMap())
            },
            // "pede comida no ifood", "abre o ifood"
            Rule("^(?:pede|peça|quero pedir|vou pedir)(?: (?<busca>.+?))? (?:no|pelo) ifood$") { m ->
                ToolCall("ifood_abrir", g(m, "busca")?.let { mapOf("busca" to it) } ?: emptyMap())
            },
            // "me leva pra casa", "navega até o aeroporto", "como chego no shopping"
            Rule("^(?:me leva|leva|navega|navegar|vamos|rota|traça rota|traça uma rota|me guia)(?: $TO_PLACE)? $OPT_ART(?<destino>.+)$") { m ->
                ToolCall("navegar", mapOf("destino" to g(m, "destino")!!))
            },
            Rule("^como (?:chego|eu chego|faço pra chegar|vou)(?: em| no| na| ao| à| até| pra| para)? $OPT_ART(?<destino>.+)$") { m ->
                ToolCall("navegar", mapOf("destino" to g(m, "destino")!!))
            },
            // "me acorda às 7", "alarme para 6 e meia", "coloca um despertador pras 7h30"
            Rule("^(?:me acorda|me acorde|acorda|desperta|(?:cria |coloca |põe |poe |bota )?(?:um )?(?:alarme|despertador))(?: (?:às|as|pras|para as|para às|pra|para))? (?<hora>.+)$") { m ->
                PtNumbers.parseTime(m.lower("hora")!!)?.let { t ->
                    ToolCall("alarme", mapOf("hora" to "${t.hour}", "minuto" to "${t.minute}"))
                }
            },
            // "timer de 5 minutos", "marca 10 minutos", "cronômetro de meia hora"
            Rule("^(?:(?:cria |coloca |põe |poe |bota |liga )?(?:um )?(?:timer|temporizador|cronômetro|cronometro)(?: de| para| pra)?|marca|conta) (?<dur>.+)$") { m ->
                PtNumbers.parseDuration(m.lower("dur")!!)?.let { ToolCall("timer", mapOf("segundos" to "$it")) }
            },
            // Depois de alarme e timer, porque "coloca" também serve para eles. "toca Djavan", "toca meu mix no spotify", "coloca uma música do Legião"
            Rule("^(?:toca|toque|tocar|bota|coloca|põe|poe)(?: pra tocar)? (?<busca>.+?)(?: no spotify)?$") { m ->
                ToolCall("spotify_tocar", mapOf("busca" to g(m, "busca")!!))
            },
            // Antes da busca na web: "acha o pdf do contrato", "fotos de ontem", "cadê a planilha de gastos"
            Rule("^(?:(?:procura|procure|busca|busque|acha|ache|encontra|encontre|localiza|mostra|mostre|me mostra|me mostre|cadê|cade|onde (?:está|esta|tá|ta|fica))(?: (?:o|a|os|as|meu|minha|meus|minhas|aquele|aquela))? )?(?<tipo>$FILE_WORDS)(?: (?:de|do|da|dos|das|com|chamado|chamada|sobre|que tem))?(?: (?<termo>.+?))?(?: no (?:celular|telefone|aparelho))?$") { m ->
                val (termo, periodo) = FileSearch.splitPeriod(g(m, "termo"))
                val kind = FileKind.fromWord(m.lower("tipo")!!)
                ToolCall(
                    "buscar_arquivos",
                    buildMap {
                        termo?.let { put("termo", it) }
                        kind?.let { put("tipo", it.name.lowercase()) }
                        periodo?.let { put("periodo", it.name.lowercase()) }
                    },
                )
            },
            // "pesquisa preço do dólar", "procura na internet receita de bolo"
            Rule("^(?:pesquisa|pesquise|pesquisar|procura|procure|busca|busque|googla|google)(?: na internet| no google)?(?: por| sobre)? (?<q>.+?)(?: na internet| no google)?$") { m ->
                ToolCall("buscar_web", mapOf("consulta" to g(m, "q")!!))
            },
            // "abre o WhatsApp", "abrir câmera"
            Rule("^(?:abre|abra|abrir|inicia|inicie|entra no|entra na)(?: o| a)? (?<nome>.+)$") { m ->
                ToolCall("abrir_app", mapOf("nome" to g(m, "nome")!!))
            },
        )

        private val LEAD = Regex("^(?:(?:ei|oi|olá|ola|ok|hey),? )?(?:blico,? )?(?:por favor,? )?(?:você pode |voce pode |pode |consegue )?")
        private val TAIL = Regex("(?:,? por favor)?[.!?]*$")
    }
}
