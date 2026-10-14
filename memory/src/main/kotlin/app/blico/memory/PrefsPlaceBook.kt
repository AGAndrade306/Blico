package app.blico.memory

import android.content.Context
import app.blico.core.PlaceBook

/**
 * Lugares com apelido (casa, trabalho), no armazenamento privado do app.
 * TODO: criptografar em repouso quando a memória em arquivos entrar.
 */
class PrefsPlaceBook(context: Context) : PlaceBook {
    private val prefs = context.getSharedPreferences("lugares", Context.MODE_PRIVATE)

    override fun resolve(alias: String): String? = prefs.getString(key(alias), null)?.takeIf { it.isNotBlank() }

    fun set(alias: String, address: String) {
        prefs.edit().putString(key(alias), address.trim()).apply()
    }

    private fun key(alias: String) = when (val a = alias.trim().lowercase()) {
        "escritório", "escritorio" -> "trabalho"
        "minha casa" -> "casa"
        else -> a
    }

    companion object {
        val ALIASES = listOf("casa", "trabalho")
    }
}
