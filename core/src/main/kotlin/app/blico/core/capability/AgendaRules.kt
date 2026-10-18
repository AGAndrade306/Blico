package app.blico.core.capability

import app.blico.core.PtWhen
import app.blico.core.ToolCall

/** Frases de agenda e lembretes entendidas sem modelo. */
internal object AgendaRules {
    private const val DIA = "hoje|amanhã|amanha|depois de amanhã|depois de amanha|essa semana|esta semana|na semana|da semana|semana|" +
        "(?:na |no |nessa |nesta |neste |nesse )?(?:segunda|terça|terca|quarta|quinta|sexta|sábado|sabado|domingo)(?:-feira| feira)?|(?:no )?dia \\d{1,2}"
    private const val OPT_DIA = "(?: (?:de |pra |para |do |da )?(?<dia>$DIA))?"

    private fun dia(m: app.blico.core.RuleRouter.Match): Map<String, String> = m["dia"]?.let { mapOf("dia" to it) } ?: emptyMap()

    val all: List<SpeechRule> = listOf(
        // "me lembra de tirar o bolo daqui a 20 minutos", "me lembra amanhã às 9 de pagar a conta", "cria um lembrete pra sexta às 10 de ligar pro banco"
        SpeechRule("^(?:me (?:lembra|lembre|lembrar|avisa|avise|avisar)|(?:cria|crie|criar|coloca|coloque|põe|poe|bota|faz|faça|faca|marca)(?: um)? lembrete|lembrete){F}(?: (?:de|que|para|pra))? (?<resto>.+)$") { m ->
            val split = PtWhen.extract(m.raw("resto")!!) ?: return@SpeechRule null
            val texto = split.rest.removePrefix("de ").removePrefix("que ").removePrefix("pra ").trim()
            if (texto.isEmpty()) null else ToolCall("lembrete_criar", mapOf("texto" to texto, "quando" to split.phrase))
        },
        // "quais são meus lembretes", "meus lembretes"
        SpeechRule("^(?:quais (?:são |sao )?(?:os )?|mostra (?:os )?|lista (?:os )?)?(?:meus )?lembretes(?: marcados)?$") { ToolCall("lembretes_ver") },
        // "cancela o lembrete do bolo", "apaga o lembrete de pagar a conta"
        SpeechRule("^(?:cancela|cancele|cancelar|apaga|apague|tira|tire|desmarca|desmarque)(?: o)? lembrete(?: (?:de|do|da|pra|para))? (?<assunto>.+)$") { m ->
            ToolCall("lembrete_cancelar", mapOf("assunto" to m["assunto"]!!))
        },

        // "o que eu tenho hoje", "o que tenho amanhã na agenda", "tenho compromisso sexta?"
        SpeechRule("^(?:o que|que) (?:eu )?(?:tenho|tem)(?: (?:na agenda|marcado|de compromisso))?$OPT_DIA(?: (?:na agenda|marcado))?$") { m ->
            ToolCall("agenda_dia", dia(m))
        },
        SpeechRule("^(?:como (?:tá|ta|está|esta) |qual (?:é |e )?|mostra |me fala |lê |le )?(?:a )?(?:minha )?agenda$OPT_DIA$") { m -> ToolCall("agenda_dia", dia(m)) },
        SpeechRule("^(?:eu )?tenho (?:algum |alguma )?(?:compromisso|reunião|reuniao|coisa marcada)$OPT_DIA$") { m -> ToolCall("agenda_dia", dia(m)) },
        SpeechRule("^(?:quais (?:são |sao )?(?:os )?)?meus compromissos$OPT_DIA$") { m -> ToolCall("agenda_dia", dia(m)) },
        // "quando eu tô livre amanhã", "tenho horário livre sexta?"
        SpeechRule("^(?:quando|que horas|em que horário|em que horario|qual horário|qual horario) (?:eu )?(?:estou|tô|to|fico|tenho) (?:livre|horário livre|horario livre|disponível|disponivel)$OPT_DIA$") { m ->
            ToolCall("horarios_livres", dia(m))
        },
        SpeechRule("^(?:eu )?tenho (?:algum )?(?:horário|horario|tempo) livre$OPT_DIA$") { m -> ToolCall("horarios_livres", dia(m)) },

        // "marca reunião com o João amanhã às 15h", "agenda dentista sexta às 10", "coloca na agenda almoço com a Ana dia 25 ao meio-dia"
        SpeechRule("^(?:marca|marque|marcar|agenda|agende|agendar|cria um evento|crie um evento|cria um compromisso|(?:coloca|coloque|põe|poe|bota|anota|anote) na agenda)(?: (?:um|uma))?{F}(?: (?:de|pra|para))? (?<resto>.+)$") { m ->
            val split = PtWhen.extract(m.raw("resto")!!) ?: return@SpeechRule null
            val titulo = split.rest.trim()
            if (titulo.isEmpty() || titulo.length < 3) null else ToolCall("evento_criar", mapOf("titulo" to titulo.replaceFirstChar { it.uppercase() }, "quando" to split.phrase))
        },
    )
}
