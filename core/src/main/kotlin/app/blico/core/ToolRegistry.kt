package app.blico.core

/** Catálogo de ferramentas disponíveis e validação das chamadas que o modelo devolve. */
class ToolRegistry(tools: Iterable<Tool> = emptyList()) {
    private val byName = linkedMapOf<String, Tool>()

    init {
        tools.forEach(::register)
    }

    fun register(tool: Tool) {
        require(tool.name !in byName) { "Ferramenta duplicada: ${tool.name}" }
        byName[tool.name] = tool
    }

    operator fun get(name: String): Tool? = byName[name]

    val all: Collection<Tool> get() = byName.values

    /** Devolve os problemas da chamada; lista vazia quer dizer válida. */
    fun validate(call: ToolCall): List<String> {
        val tool = byName[call.tool] ?: return listOf("Ferramenta desconhecida: ${call.tool}")
        val schema = tool.parameters
        val missing = schema.required.filter { call.args[it].isNullOrBlank() }
            .map { "Parâmetro obrigatório ausente: $it" }
        val unknown = call.args.keys.filter { it !in schema.properties }
            .map { "Parâmetro desconhecido: $it" }
        return missing + unknown
    }
}
