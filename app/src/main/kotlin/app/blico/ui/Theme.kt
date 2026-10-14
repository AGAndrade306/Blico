package app.blico.ui

import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.blico.R

/**
 * Visual no padrão Apple: fundo cinza-claro agrupado, cartões brancos sem borda,
 * cantos de 12 dp, uma cor de destaque só (o verde do logo) e tipografia Inter, a mais próxima da SF.
 */
@Immutable
data class BlicoPalette(
    val background: Color,
    val card: Color,
    val green: Color,
    val text: Color,
    val muted: Color,
    val separator: Color,
    /** Fundo de botões secundários e opções não marcadas. */
    val fill: Color,
    val onGreen: Color,
    val danger: Color,
    val dark: Boolean,
)

val LightBlico = BlicoPalette(
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    green = Color(0xFF139A2E),
    text = Color(0xFF000000),
    muted = Color(0xFF6C6C70),
    separator = Color(0xFFC6C6C8),
    fill = Color(0xFFE9E9EE),
    onGreen = Color(0xFFFFFFFF),
    danger = Color(0xFFD70015),
    dark = false,
)

val DarkBlico = BlicoPalette(
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    green = Color(0xFF30D158),
    text = Color(0xFFFFFFFF),
    muted = Color(0xFF8E8E93),
    separator = Color(0xFF38383A),
    fill = Color(0xFF2C2C2E),
    onGreen = Color(0xFF000000),
    danger = Color(0xFFFF453A),
    dark = true,
)

val LocalBlico = staticCompositionLocalOf { LightBlico }

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Escala do iOS (Large Title 34, Title 22, Headline 17, Body 17, Footnote 13), em Inter. */
private fun typography(): Typography {
    fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
        TextStyle(fontFamily = Inter, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)
    return Typography(
        displayLarge = style(40, 46, FontWeight.Bold, -0.6),
        displayMedium = style(36, 42, FontWeight.Bold, -0.5),
        displaySmall = style(34, 41, FontWeight.Bold, -0.4),
        headlineLarge = style(28, 34, FontWeight.Bold, -0.3),
        headlineMedium = style(28, 34, FontWeight.Bold, -0.3),
        headlineSmall = style(22, 28, FontWeight.SemiBold, -0.2),
        titleLarge = style(22, 28, FontWeight.SemiBold, -0.2),
        titleMedium = style(17, 22, FontWeight.SemiBold, -0.2),
        titleSmall = style(15, 20, FontWeight.SemiBold, -0.1),
        bodyLarge = style(17, 22, tracking = -0.2),
        bodyMedium = style(15, 20, tracking = -0.1),
        bodySmall = style(13, 18),
        labelLarge = style(17, 22, FontWeight.SemiBold, -0.2),
        labelMedium = style(13, 18, FontWeight.Medium),
        labelSmall = style(12, 16),
    )
}

/** [dark] nulo segue o sistema; o app fixa o claro, que é mais fácil de ler. */
@Composable
fun BlicoTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val p = if (dark ?: isSystemInDarkTheme()) DarkBlico else LightBlico
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.green, onPrimary = p.onGreen, secondary = p.green, onSecondary = p.onGreen,
            background = p.background, onBackground = p.text, surface = p.card, onSurface = p.text,
            surfaceVariant = p.fill, onSurfaceVariant = p.muted, outline = p.separator, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.green, onPrimary = p.onGreen, secondary = p.green, onSecondary = p.onGreen,
            background = p.background, onBackground = p.text, surface = p.card, onSurface = p.text,
            surfaceVariant = p.fill, onSurfaceVariant = p.muted, outline = p.separator, error = p.danger,
        )
    }
    CompositionLocalProvider(LocalBlico provides p) {
        MaterialTheme(colorScheme = scheme, typography = remember { typography() }, content = content)
    }
}

/** Fundo de tela agrupada. */
@Composable
fun Modifier.screen(): Modifier = background(LocalBlico.current.background)

/** Título grande, como no topo das telas do iOS. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.displaySmall) {
    Text(text, style = style, color = LocalBlico.current.text, modifier = modifier)
}

val CardShape = RoundedCornerShape(12.dp)
private val ControlShape = RoundedCornerShape(12.dp)
private val ChipShape = RoundedCornerShape(8.dp)

/** Grupo de conteúdo: branco, sem borda nem sombra, sobre o fundo cinza. */
@Composable
fun BlicoCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(LocalBlico.current.card)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Botão: o principal é verde cheio; o secundário é cinza com texto verde.
 * Ao tocar, escurece de leve, como no iOS.
 */
@Composable
fun BlicoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val p = LocalBlico.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .padding(vertical = 4.dp)
            .defaultMinSize(minHeight = 44.dp)
            .alpha(if (!enabled) 0.4f else if (pressed) 0.7f else 1f)
            .clip(ControlShape)
            .background(if (primary) p.green else p.fill)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (primary) p.onGreen else p.green)
    }
}

/** Ação só de texto, como "Fechar". */
@Composable
fun BlicoLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = LocalBlico.current.green,
        modifier = modifier
            .alpha(if (pressed) 0.5f else 1f)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

/** Opção de escolha: marcada fica verde cheia; as outras, cinza. */
@Composable
fun BlicoChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalBlico.current
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) p.onGreen else p.text,
        modifier = modifier
            .padding(vertical = 3.dp)
            .clip(ChipShape)
            .background(if (selected) p.green else p.fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Marca de passo: círculo verde com ✓ quando feito, só o contorno quando falta. */
@Composable
fun BlicoMark(done: Boolean?, modifier: Modifier = Modifier) {
    val p = LocalBlico.current
    if (done == null) return
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (done) p.green else Color.Transparent)
            .border(1.5.dp, if (done) p.green else p.separator, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Text("✓", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = p.onGreen)
    }
}

/** Barra de progresso fina: trilho cinza, preenchimento verde. */
@Composable
fun BlicoProgress(fraction: Float, modifier: Modifier = Modifier) {
    val p = LocalBlico.current
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.fill)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(p.green))
    }
}
