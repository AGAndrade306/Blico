package app.blico

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.blico.ui.DashedRule
import app.blico.ui.LocalBlico
import app.blico.ui.PanelLabel
import app.blico.ui.screen

enum class Tab(val label: String) { HOME("Início"), AGENTS("Agentes"), SETTINGS("Ajustes") }

/** A casca do app: a aba aberta em cima e a barra de abas embaixo. Voltar leva ao Início. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Shell(tab: Tab, onTab: (Tab) -> Unit, content: @Composable (Tab) -> Unit) {
    BackHandler(enabled = tab != Tab.HOME) { onTab(Tab.HOME) }
    Column(Modifier.fillMaxSize().screen().imePadding()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            content(tab)
        }
        // Com o teclado aberto a barra sai, para sobrar espaço para o campo.
        if (!WindowInsets.isImeVisible) TabBar(tab, onTab)
    }
}

@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    val p = LocalBlico.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        DashedRule(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(60.dp).selectableGroup()) {
            Tab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .semantics { selected = on }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onTab(t) }
                        .padding(top = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Aba aberta entre colchetes, como num menu de terminal.
                    Text(
                        if (on) "[ ${t.label.uppercase()} ]" else t.label.uppercase(),
                        style = PanelLabel.copy(fontSize = 12.sp),
                        color = if (on) p.text else p.muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(if (on) "^" else " ", style = PanelLabel, color = p.green)
                }
            }
        }
    }
}
