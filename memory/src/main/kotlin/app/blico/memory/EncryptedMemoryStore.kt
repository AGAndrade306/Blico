package app.blico.memory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.blico.core.capability.Fact
import app.blico.core.capability.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A memória pessoal num arquivo do app cifrado com AES-GCM, chave no Android Keystore (não sai do aparelho
 * nem vai junto numa cópia). O arquivo inteiro é regravado a cada mudança: são poucos fatos, curtos.
 */
class EncryptedMemoryStore(context: Context) : MemoryStore {
    private val file = File(context.filesDir, "memoria.bin")
    private val lock = Mutex()
    private val _facts = MutableStateFlow<List<Fact>?>(null)

    /** Para a tela de Ajustes: nulo até a primeira leitura. */
    val facts: StateFlow<List<Fact>?> get() = _facts

    override suspend fun all(): List<Fact> = lock.withLock { loaded() }

    override suspend fun add(text: String, sensitive: Boolean): Fact = lock.withLock {
        val fact = Fact(UUID.randomUUID().toString(), text.trim(), System.currentTimeMillis(), sensitive)
        save(loaded() + fact)
        fact
    }

    override suspend fun remove(id: String): Boolean = lock.withLock {
        val now = loaded()
        val next = now.filterNot { it.id == id }
        if (next.size != now.size) save(next)
        next.size != now.size
    }

    suspend fun clear() = lock.withLock { save(emptyList()) }

    private suspend fun loaded(): List<Fact> = _facts.value ?: withContext(Dispatchers.IO) { read() }.also { _facts.value = it }

    private fun read(): List<Fact> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            val json = JSONArray(String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8))
            (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                Fact(o.getString("id"), o.getString("texto"), o.getLong("em"), o.optBoolean("sensivel"))
            }
        }.getOrElse { emptyList() } // Chave perdida (app reinstalado): começa do zero em vez de quebrar.
    }

    private suspend fun save(facts: List<Fact>) {
        withContext(Dispatchers.IO) {
            val json = JSONArray(facts.map { JSONObject().put("id", it.id).put("texto", it.text).put("em", it.at).put("sensivel", it.sensitive) })
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val tmp = File(file.parentFile, "memoria.tmp")
            tmp.writeBytes(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        _facts.value = facts
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "blico-memoria"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
