package app.blico.agents

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.blico.BlicoApp
import app.blico.ui.BlicoTheme
import app.blico.ui.screen

/**
 * A casa dos agentes enquanto o app não tem abas. Quando a casca de navegação chegar,
 * [AgentsScreen] vira uma aba e esta tela some.
 */
class AgentsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val clara = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = clara, navigationBarStyle = clara)
        super.onCreate(savedInstanceState)
        setContent {
            BlicoTheme(dark = false) {
                Box(Modifier.fillMaxSize().screen()) {
                    AgentsScreen(application as BlicoApp, onClose = ::finish)
                }
            }
        }
    }
}
