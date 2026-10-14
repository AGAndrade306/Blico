package app.blico.core

sealed interface Decision {
    data object Execute : Decision

    data class Confirm(val summary: ConfirmationSummary) : Decision

    data class Refuse(val reason: String) : Decision
}

/**
 * Decide o destino de cada chamada antes de qualquer execução.
 * A ordem das regras importa: lista de bloqueio e validação vêm antes da classe de risco.
 */
class RiskPolicy(
    private val registry: ToolRegistry,
    private val blocklist: Set<String> = PaymentAppsBlocklist.packages,
) {
    fun decide(call: ToolCall): Decision {
        val tool = registry[call.tool]
            ?: return Decision.Refuse("Ferramenta desconhecida: ${call.tool}")

        val blocked = tool.apps intersect blocklist
        if (blocked.isNotEmpty()) {
            return Decision.Refuse("App bloqueado para automação: ${blocked.sorted().joinToString()}")
        }

        val problems = registry.validate(call)
        if (problems.isNotEmpty()) return Decision.Refuse(problems.joinToString("; "))

        return when (tool.risk) {
            RiskClass.READ, RiskClass.PREPARE -> Decision.Execute
            RiskClass.COMMIT -> Decision.Confirm(tool.summarize(call))
            RiskClass.SENSITIVE -> Decision.Refuse("Ação sensível recusada por padrão")
        }
    }
}
