package app.blico.core

import app.blico.core.tools.BuscarWeb

/**
 * De onde veio um resultado, para o selo do card e da atividade. `[celular]` quando nada saiu do
 * aparelho, `[nuvem]` quando o pedido foi ao servidor, `[web]` quando uma busca saiu e o nome do
 * serviço (`[google]`) quando uma conta foi usada. O selo diz para onde o dado foi, então na dúvida
 * fica o que mais saiu: serviço, depois nuvem, depois web.
 */
data class Origin(val kind: Kind, val name: String) {
    enum class Kind { DEVICE, CLOUD, WEB, SERVICE }

    val badge: String get() = "[$name]"

    companion object {
        val DEVICE = Origin(Kind.DEVICE, "celular")
        val CLOUD = Origin(Kind.CLOUD, "nuvem")
        val WEB = Origin(Kind.WEB, "web")
        val GOOGLE = Origin(Kind.SERVICE, "google")

        private val GOOGLE_TOOLS = Regex("^(agenda|gmail)_")

        /**
         * [route] é o id de quem entendeu o pedido ("regras", "local", "nuvem", "busca"), ou nulo
         * quando o resultado veio de um toque no card.
         */
        fun of(route: String?, outcome: Outcome): Origin {
            val tool = when (outcome) {
                is Outcome.Done -> outcome.call.tool
                is Outcome.Confirm -> outcome.call.tool
                else -> null
            }
            return when {
                tool != null && GOOGLE_TOOLS.containsMatchIn(tool) -> GOOGLE
                route == "nuvem" -> CLOUD
                outcome is Outcome.Answer && outcome.source != null && outcome.source != BuscarWeb.LOCAL_SOURCE -> WEB
                else -> DEVICE
            }
        }

        /** Volta do nome guardado na atividade; nome desconhecido vira serviço com esse nome. */
        fun named(name: String): Origin = listOf(DEVICE, CLOUD, WEB, GOOGLE).firstOrNull { it.name == name } ?: Origin(Kind.SERVICE, name)
    }
}
