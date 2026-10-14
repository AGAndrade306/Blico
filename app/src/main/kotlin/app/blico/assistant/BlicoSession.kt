package app.blico.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.blico.MainActivity
import app.blico.core.TranscriptionEvent
import app.blico.voice.ListeningState
import app.blico.voice.OnDeviceTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Uma interação: abre no toque longo do botão lateral, ouve até a fala acabar e mostra o texto.
 * O microfone só abre aqui, depois do gesto, e fecha quando a sessão some.
 */
class BlicoSession(context: Context) : VoiceInteractionSession(context) {
    private val owner = ComposeOwner()
    private val transcriber = OnDeviceTranscriber(context)
    private lateinit var scope: CoroutineScope
    private var listening: Job? = null
    private var root: View? = null

    private var state by mutableStateOf(ListeningState())
    private var needsPermission by mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        scope = MainScope()
        owner.onCreate()
    }

    override fun onCreateContentView(): View = ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent {
            AssistantOverlay(
                state = state,
                needsPermission = needsPermission,
                onRetry = ::startListening,
                onOpenSetup = ::openSetup,
                onClose = ::finish,
            )
        }
        root = this
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        owner.onShow()
        startListening()
    }

    override fun onHide() {
        stopListening()
        owner.onHide()
        super.onHide()
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        owner.onDestroy()
        super.onDestroy()
    }

    private fun startListening() {
        stopListening()
        state = ListeningState()
        needsPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) return

        listening = scope.launch {
            transcriber.listen().collect { event ->
                if (event is TranscriptionEvent.Listening) {
                    root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                state = state.reduce(event)
            }
        }
    }

    private fun stopListening() {
        listening?.cancel()
        listening = null
    }

    private fun openSetup() {
        startAssistantActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
