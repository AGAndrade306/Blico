package app.blico.core

class FakeTool(
    override val name: String,
    override val risk: RiskClass,
    override val apps: Set<String> = setOf("com.whatsapp"),
    override val parameters: JsonSchema = JsonSchema(
        properties = mapOf(
            "contato" to JsonSchema.Param(description = "apelido do contato"),
            "texto" to JsonSchema.Param(description = "mensagem"),
        ),
        required = setOf("contato"),
    ),
) : Tool {
    override val description = "ferramenta de teste"

    override suspend fun run(call: ToolCall, ctx: ExecContext): ToolResult = ToolResult.Success("feito")
}
