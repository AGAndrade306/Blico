package app.blico

import android.app.Application
import android.content.Intent
import app.blico.core.Assistant
import app.blico.core.ExecContext
import app.blico.core.RuleRouter
import app.blico.core.ToolRegistry
import app.blico.core.tools.builtinTools
import app.blico.executor.AndroidDeviceGateway
import app.blico.memory.AndroidContactDirectory
import app.blico.memory.PrefsPlaceBook
import app.blico.voice.AndroidSpeechOutput
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.UUID

/** Peças compartilhadas pelo processo. A fala vive aqui para não ser cortada quando o cartão fecha. */
class BlicoApp : Application() {
    val speech by lazy { AndroidSpeechOutput(this) }

    /** Escopo do processo, para a fala continuar depois que o cartão fecha. */
    val scope = MainScope()
    val places by lazy { PrefsPlaceBook(this) }
    private val contacts by lazy { AndroidContactDirectory(this) }

    /** Etapa 2 em diante: depois das regras entram o modelo local e a nuvem. */
    val assistant by lazy { Assistant(providers = listOf(RuleRouter()), registry = ToolRegistry(builtinTools())) }

    fun say(text: String) {
        scope.launch { speech.speak(text) }
    }

    fun execContext(launcher: (Intent) -> Unit = { startActivity(it) }) = ExecContext(
        requestId = UUID.randomUUID().toString(),
        device = AndroidDeviceGateway(this, launcher),
        contacts = contacts,
        places = places,
    )
}
