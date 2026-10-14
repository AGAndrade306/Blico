package app.blico.core.tools

import app.blico.core.ConfirmationSummary
import app.blico.core.DeviceResult
import app.blico.core.JsonSchema
import app.blico.core.RiskClass
import app.blico.core.Tool
import app.blico.core.ToolCall
import app.blico.core.ToolResult
import java.net.URLEncoder

internal abstract class BaseTool(
    override val name: String,
    override val description: String,
    override val risk: RiskClass,
    override val apps: Set<String>,
    params: Map<String, String> = emptyMap(),
    required: Set<String> = emptySet(),
    private val title: String = name,
) : Tool {
    override val parameters = JsonSchema(
        properties = params.mapValues { JsonSchema.Param(description = it.value) },
        required = required,
    )

    override fun summarize(call: ToolCall) = ConfirmationSummary(
        title = title,
        fields = parameters.properties.keys.mapNotNull { k -> call.args[k]?.let { k to it } },
    )
}

internal fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

/** Converte o resultado do aparelho na frase falada. */
internal fun DeviceResult.toToolResult(ok: String): ToolResult = when (this) {
    DeviceResult.Done -> ToolResult.Success(ok)
    is DeviceResult.NotInstalled -> ToolResult.Failure("não instalado: $what", "Não encontrei o $what no celular")
    is DeviceResult.Failed -> ToolResult.Failure(reason, "Não consegui, algo deu errado")
}

internal fun ToolCall.arg(key: String): String? = args[key]?.trim()?.takeIf { it.isNotEmpty() }

/** Só dígitos, com DDI 55 quando vier sem. Formato que o WhatsApp espera. */
internal fun phoneForWhatsApp(raw: String): String {
    val digits = raw.filter(Char::isDigit).trimStart('0')
    return if (digits.length in 10..11) "55$digits" else digits
}
