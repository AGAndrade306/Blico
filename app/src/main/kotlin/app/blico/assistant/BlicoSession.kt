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
import app.blico.BlicoApp
import app.blico.MainActivity
import app.blico.core.AgentFile
import app.blico.core.AgentResult
import app.blico.core.AgentRun
import app.blico.core.Assistant
import app.blico.core.ExecContext
import app.blico.core.Outcome
import app.blico.core.ToolCall
import app.blico.core.TranscriptionEvent
import app.blico.voice.ListeningState
import app.blico.voice.OnDeviceTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Uma interação: abre no toque longo do botão lateral, ouve até a fala acabar, entende e age.
 * O microfone só abre aqui, depois do gesto, e fecha quando a sessão some.
 */
class BlicoSession(context: Context) : VoiceInteractionSession(context) {
    private val app = context.applicationContext as BlicoApp
    private val owner = ComposeOwner()
    private val transcriber = OnDeviceTranscriber(context)
    private lateinit var scope: CoroutineScope
    private var work: Job? = null
    private var root: View? = null

    private var state by mutableStateOf(ListeningState())
    private var needsPermission by mutableStateOf(false)
    private var outcome by mutableStateOf<Outcome?>(null)
    private var thinking by mutableStateOf(false)

    /** Agente parado no cartão, esperando o Confirmar ou uma escolha. */
    private var pending: AgentRun? = null

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
                thinking = thinking,
                outcome = outcome,
                onRetry = ::startListening,
                onChoose = { call -> resumeOr(call, confirmed = false) },
                onConfirm = { call -> resumeOr(call, confirmed = true) },
                onOpenSetup = ::openSetup,
                onClose = ::finish,
            )
        }
        root = this
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        owner.onShow()
        app.warmUpLocal()
        app.warmUpVoice()
        startListening()
    }

    override fun onHide() {
        cancelWork()
        owner.onHide()
        super.onHide()
    }

    override fun onDestroy() {
        cancelWork()
        scope.cancel()
        owner.onDestroy()
        super.onDestroy()
    }

    private fun startListening() {
        cancelWork()
        app.speech.stop() // o gesto interrompe a fala, e o microfone não ouve o próprio Blico
        state = ListeningState()
        outcome = null
        pending = null
        needsPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) return

        work = scope.launch {
            transcriber.listen().collect { event ->
                if (event is TranscriptionEvent.Listening) {
                    root?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                state = state.reduce(event)
                if (event is TranscriptionEvent.Final) {
                    val agent = AgentFile.match(event.text, app.agents.agents())
                    if (agent != null) {
                        actAgent { ctx -> app.agentRunner.start(agent.second, agent.first, ctx) }
                    } else {
                        act { assistant, ctx -> assistant.handle(event.text, ctx) }
                    }
                }
            }
        }
    }

    /** Roda um passo do assistente e reage ao resultado. */
    private fun act(step: suspend (Assistant, ExecContext) -> Outcome) {
        scope.launch {
            thinking = true
            val out = step(app.assistant, app.execContext { context.startActivity(it) })
            thinking = false
            outcome = out
            app.record(out)
            app.say(out.spoken)
            // Ação feita: o app alvo já está abrindo por trás, então o cartão sai da frente.
            if (out is Outcome.Done) finish()
        }
    }

    /** Toque no cartão: um agente parado continua de onde estava; sem agente, é um passo só. */
    private fun resumeOr(call: ToolCall, confirmed: Boolean) {
        val run = pending
        if (run != null) {
            actAgent { ctx -> app.agentRunner.resume(run, call, ctx, confirmed) }
        } else {
            act { assistant, ctx -> assistant.execute(call, ctx, confirmed) }
        }
    }

    /** Roda o agente até a resposta ou até o próximo cartão. */
    private fun actAgent(step: suspend (ExecContext) -> AgentResult) {
        scope.launch {
            thinking = true
            pending = null
            val result = step(app.execContext { context.startActivity(it) })
            thinking = false
            pending = result.paused
            outcome = result.outcome
            app.say(result.outcome.spoken)
        }
    }

    private fun cancelWork() {
        work?.cancel()
        work = null
    }

    private fun openSetup() {
        startAssistantActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}

/** Atalho para o cartão: as opções de escolha e a chamada a confirmar. */
internal val Outcome.choices: List<Pair<String, ToolCall>>
    get() = when (this) {
        is Outcome.Choose -> options.map { it.label to it.call }
        else -> emptyList()
    }
