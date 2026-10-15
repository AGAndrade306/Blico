package app.blico.executor

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.blico.core.DirectSend
import app.blico.core.PaymentAppsBlocklist
import app.blico.core.tools.Apps

/**
 * Backend 3 da arquitetura: acessibilidade. Hoje faz uma coisa só: tocar em enviar numa
 * conversa do WhatsApp que o próprio Blico abriu, depois do toque em Confirmar.
 *
 * Não lê nem guarda mensagens. O único texto lido da tela é o da caixa de escrever,
 * comparado com o que foi confirmado; ele nunca vai para um modelo.
 * Os eventos chegam só do WhatsApp (`packageNames` no XML), e o serviço recusa qualquer outro app.
 */
class BlicoAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (current === this) current = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    // O envio é puxado pelo gateway, não pelos eventos.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Uma olhada na tela: em que pé está a conversa aberta com [text]. */
    internal fun tapSend(text: String): SendStep {
        val root = whatsAppRoot() ?: return SendStep.NotYet("o WhatsApp não abriu")
        val entry = root.findAccessibilityNodeInfosByViewId(ENTRY).firstOrNull()
            ?: return SendStep.NotYet("a caixa de mensagem não apareceu")
        val typed = entry.text?.toString()
        if (!DirectSend.sameText(typed, text)) {
            // Vazio pode ser a conversa ainda carregando; outro texto é motivo para não enviar.
            return SendStep.NotYet(if (typed.isNullOrBlank()) "a mensagem não apareceu na caixa" else "o texto na caixa não era o confirmado")
        }
        val send = sendButton(root) ?: return SendStep.NotYet("o botão enviar não apareceu")
        return if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) SendStep.Tapped else SendStep.NotYet("o toque em enviar não pegou")
    }

    /** Depois do toque: a caixa esvaziou, então a mensagem saiu. */
    internal fun sent(text: String): Boolean {
        val root = whatsAppRoot() ?: return false
        val typed = root.findAccessibilityNodeInfosByViewId(ENTRY).firstOrNull()?.text?.toString()
        return !DirectSend.sameText(typed, text)
    }

    private fun whatsAppRoot(): AccessibilityNodeInfo? {
        val roots = windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)
        return roots.firstOrNull { it.packageName == Apps.WHATSAPP && it.packageName !in PaymentAppsBlocklist.packages }
    }

    /** Pelo id do botão; se o WhatsApp mudar o id, pela descrição "Enviar". */
    private fun sendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(SEND).firstOrNull { it.isEnabled && it.isVisibleToUser }
            ?: SEND_LABELS.firstNotNullOfOrNull { label ->
                root.findAccessibilityNodeInfosByText(label).firstOrNull {
                    it.isClickable && it.isEnabled && it.contentDescription?.toString().equals(label, ignoreCase = true)
                }
            }

    internal sealed interface SendStep {
        data object Tapped : SendStep

        data class NotYet(val why: String) : SendStep
    }

    companion object {
        private const val ENTRY = "com.whatsapp:id/entry"
        private const val SEND = "com.whatsapp:id/send"
        private val SEND_LABELS = listOf("Enviar", "Send")

        @Volatile
        internal var current: BlicoAccessibilityService? = null
            private set

        /** Ligado nas configurações de acessibilidade do Android. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, BlicoAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
