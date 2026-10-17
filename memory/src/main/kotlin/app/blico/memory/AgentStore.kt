package app.blico.memory

import android.content.Context
import app.blico.core.AgentFile
import app.blico.core.AgentProfile
import java.io.File

/**
 * Os agentes, um arquivo Markdown por agente na pasta privada do app (`files/agentes`).
 * Só o Blico lê essa pasta. Na primeira vez, cria os agentes de exemplo.
 */
class AgentStore(context: Context) {
    private val dir = File(context.filesDir, "agentes")

    /** Um arquivo e o agente lido dele, ou o erro que impede de usá-lo. */
    data class Entry(val name: String, val text: String, val agent: AgentProfile?, val error: String?)

    /** Exemplos já criados uma vez. Um exemplo apagado não volta; um exemplo novo aparece na atualização. */
    private val seeded = File(dir, ".exemplos")

    init {
        dir.mkdirs()
        val done = if (seeded.exists()) seeded.readLines().toSet() else emptySet()
        val fresh = AgentFile.EXAMPLES.filter { (name, _) -> name !in done }
        fresh.forEach { (name, text) ->
            val f = File(dir, "$name.md")
            if (!f.exists()) f.writeText(text + "\n")
        }
        if (fresh.isNotEmpty()) seeded.writeText((done + fresh.map { it.first }).joinToString("\n"))
    }

    fun entries(): List<Entry> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
            .sortedBy { it.name }
            .map { f ->
                val text = f.readText()
                val parsed = runCatching { AgentFile.parse(text) }
                Entry(f.nameWithoutExtension, text, parsed.getOrNull(), parsed.exceptionOrNull()?.message)
            }

    /** Só os agentes que abrem sem erro. */
    fun agents(): List<AgentProfile> = entries().mapNotNull { it.agent }

    /**
     * Grava um agente montado pelo editor de campos.
     */
    fun save(agent: AgentProfile, previousName: String? = null): String? = save(AgentFile.render(agent), previousName)

    /**
     * Valida e grava. O arquivo leva o nome do agente; se o nome mudou, o arquivo antigo some.
     * Devolve o erro de leitura, ou nulo se gravou.
     */
    fun save(text: String, previousName: String? = null): String? {
        val agent = runCatching { AgentFile.parse(text) }.getOrElse { return it.message ?: "Arquivo inválido" }
        if (agent.name != previousName && File(dir, "${agent.name}.md").exists()) return "Já existe um agente chamado ${agent.name}"
        File(dir, "${agent.name}.md").writeText(text.trim() + "\n")
        if (previousName != null && previousName != agent.name) File(dir, "$previousName.md").delete()
        return null
    }

    fun delete(name: String) {
        File(dir, "$name.md").takeIf { it.parentFile == dir }?.delete()
    }
}
