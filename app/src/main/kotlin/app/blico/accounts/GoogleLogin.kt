package app.blico.accounts

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.blico.core.tools.GoogleAccount
import app.blico.core.tools.GoogleResponse
import app.blico.core.tools.googleLoginMessage
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Login no Google pelo Play Services. A pessoa escolhe a conta e aceita os acessos numa tela do
 * próprio Google; o Blico só recebe um acesso curto, guardado em memória, e pede outro em silêncio
 * quando ele vence. Nada passa pelo servidor do Blico.
 */
class GoogleLogin(private val context: Context) : GoogleAccount {
    private val prefs = context.getSharedPreferences("contas", Context.MODE_PRIVATE)
    private val client get() = Identity.getAuthorizationClient(context)
    private var token: String? = null
    private var tokenAt = 0L

    override val connected: Boolean get() = prefs.getBoolean(KEY, false)

    sealed interface Step {
        data object Done : Step

        /** O Google precisa mostrar a tela de contas e acessos; abra com startIntentSenderForResult. */
        data class Show(val intent: PendingIntent) : Step

        data class Failed(val reason: String) : Step
    }

    suspend fun connect(): Step = try {
        val r = authorize()
        val intent = r.pendingIntent
        if (r.hasResolution() && intent != null) Step.Show(intent) else accept(r)
    } catch (e: Exception) {
        Step.Failed(googleLoginMessage(e.message ?: e.toString()))
    }

    /** A volta da tela do Google. */
    fun finish(data: Intent?): Step = try {
        accept(client.getAuthorizationResultFromIntent(data))
    } catch (e: Exception) {
        Step.Failed(googleLoginMessage(e.message ?: "cancelado"))
    }

    /** Esquece o login aqui. O acesso some de vez em myaccount.google.com/permissions. */
    fun disconnect() {
        token = null
        prefs.edit().putBoolean(KEY, false).apply()
    }

    private fun accept(r: AuthorizationResult): Step {
        val t = r.accessToken ?: return Step.Failed("O Google não liberou o acesso")
        token = t
        tokenAt = System.currentTimeMillis()
        prefs.edit().putBoolean(KEY, true).apply()
        return Step.Done
    }

    /** Sem tela: já aceito antes, o Google devolve um acesso novo direto. */
    private suspend fun fresh(): String? {
        token?.takeIf { System.currentTimeMillis() - tokenAt < TOKEN_MS }?.let { return it }
        val r = runCatching { authorize() }.getOrNull() ?: return null
        if (r.hasResolution()) return null
        token = r.accessToken
        tokenAt = System.currentTimeMillis()
        return token
    }

    override suspend fun request(method: String, url: String, body: String?): GoogleResponse {
        // Sem acesso em silêncio, a ferramenta pede para entrar de novo.
        val access = fresh() ?: return GoogleResponse(401, "")
        return withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Authorization", "Bearer $access")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = conn.responseCode
                if (status == 401) token = null
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                GoogleResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun authorize(): AuthorizationResult = suspendCancellableCoroutine { cont ->
        val request = AuthorizationRequest.builder().setRequestedScopes(SCOPES.map(::Scope)).build()
        client.authorize(request)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    companion object {
        private const val KEY = "google"

        /** Acessos do Google duram uma hora; pedimos outro antes. */
        private const val TOKEN_MS = 50 * 60 * 1000L

        val SCOPES = listOf(
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
        )
    }
}
