package app.blico.memory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import app.blico.core.Contact
import app.blico.core.ContactDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Busca na agenda do Android. Os contatos nunca saem do aparelho. */
class AndroidContactDirectory(private val context: Context) : ContactDirectory {

    override suspend fun find(query: String): List<Contact> = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext emptyList()
        }
        val wanted = simplify(query)
        val all = mutableListOf<Contact>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.IS_SUPER_PRIMARY),
            null,
            null,
            "${Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                all += Contact(name, number)
            }
        }
        rank(wanted, all)
    }

    override suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext emptyList()
        }
        val names = mutableListOf<String>()
        context.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let(names::add)
        }
        names.distinct()
    }

    private companion object {
        fun simplify(s: String): String =
            Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

        /** Nome igual primeiro; depois nome que começa com a busca; depois primeiro nome igual. */
        fun rank(wanted: String, all: List<Contact>): List<Contact> {
            val byName = all.groupBy { simplify(it.name) }
            byName[wanted]?.let { return it }
            val prefix = all.filter { simplify(it.name).startsWith("$wanted ") }
            if (prefix.isNotEmpty()) return prefix
            return all.filter { simplify(it.name).split(" ").contains(wanted) }
        }
    }
}
