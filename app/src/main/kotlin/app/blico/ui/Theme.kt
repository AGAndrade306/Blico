package app.blico.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.blico.R

/**
 * Visual "Painel": fundo cinza-pedra, cartões brancos como widgets, um bloco escuro para a ação
 * principal, números em matriz de pontos (Doto), texto em IBM Plex Sans e rótulos em IBM Plex Mono.
 * O verde do logo fica para o que está vivo: o ponto de status, o progresso, o que deu certo.
 */
@Immutable
data class BlicoPalette(
    val background: Color,
    val card: Color,
    /** Destaque: status, progresso e marcas de pronto. */
    val green: Color,
    val text: Color,
    val muted: Color,
    val separator: Color,
    /** Fundo de botões secundários e trilhos. */
    val fill: Color,
    val onGreen: Color,
    val danger: Color,
    val dark: Boolean,
    /** Bloco escuro do painel e botão principal. */
    val ink: Color = text,
    val onInk: Color = background,
)

val LightBlico = BlicoPalette(
    background = Color(0xFFEDEDEA),
    card = Color(0xFFFFFFFF),
    green = Color(0xFF1FA34A),
    text = Color(0xFF111111),
    muted = Color(0xFF6B6B64),
    separator = Color(0xFFBDBDB6),
    fill = Color(0xFFE2E2DD),
    onGreen = Color(0xFFFFFFFF),
    danger = Color(0xFFC62B1F),
    dark = false,
    ink = Color(0xFF111111),
    onInk = Color(0xFFEDEDEA),
)

val DarkBlico = BlicoPalette(
    background = Color(0xFF0F0F0E),
    card = Color(0xFF1B1B19),
    green = Color(0xFF3DDC6E),
    text = Color(0xFFEDEDEA),
    muted = Color(0xFF9C9C94),
    separator = Color(0xFF3A3A36),
    fill = Color(0xFF2A2A27),
    onGreen = Color(0xFF0F0F0E),
    danger = Color(0xFFFF6B5E),
    dark = true,
    ink = Color(0xFF2B2B28),
    onInk = Color(0xFFEDEDEA),
)

val LocalBlico = staticCompositionLocalOf { LightBlico }

val PlexSans = FontFamily(
    Font(R.font.plex_sans_400, FontWeight.Normal),
    Font(R.font.plex_sans_500, FontWeight.Medium),
    Font(R.font.plex_sans_600, FontWeight.SemiBold),
)

val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

/** Matriz de pontos, só para a marca e números grandes: em texto corrido ela cansa. */
val Doto = FontFamily(Font(R.font.doto_800, FontWeight.ExtraBold))

private fun typography(): Typography {
    fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
        TextStyle(fontFamily = PlexSans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)
    fun dots(size: Int, line: Int) =
        TextStyle(fontFamily = Doto, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
    return Typography(
        displayLarge = dots(48, 52),
        displayMedium = dots(40, 44),
        displaySmall = dots(32, 36),
        headlineLarge = sans(28, 34, FontWeight.SemiBold, -0.3),
        headlineMedium = sans(24, 30, FontWeight.SemiBold, -0.2),
        headlineSmall = sans(21, 27, FontWeight.Medium, -0.1),
        titleLarge = sans(20, 26, FontWeight.SemiBold, -0.1),
        titleMedium = sans(16, 22, FontWeight.SemiBold),
        titleSmall = sans(14, 20, FontWeight.SemiBold),
        bodyLarge = sans(16, 23),
        bodyMedium = sans(14, 20),
        bodySmall = sans(13, 18),
        labelLarge = sans(15, 20, FontWeight.Medium),
        labelMedium = TextStyle(fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 15.sp),
    )
}

/** Rótulo de painel: mono, caixa alta, espaçado. */
val PanelLabel = TextStyle(fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)

/** [dark] nulo segue o sistema. */
@Composable
fun BlicoTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val p = if (dark ?: isSystemInDarkTheme()) DarkBlico else LightBlico
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.ink, onPrimary = p.onInk, secondary = p.green, onSecondary = p.onGreen,
            background = p.background, onBackground = p.text, surface = p.card, onSurface = p.text,
            surfaceVariant = p.fill, onSurfaceVariant = p.muted, outline = p.separator, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.ink, onPrimary = p.onInk, secondary = p.green, onSecondary = p.onGreen,
            background = p.background, onBackground = p.text, surface = p.card, onSurface = p.text,
            surfaceVariant = p.fill, onSurfaceVariant = p.muted, outline = p.separator, error = p.danger,
        )
    }
    CompositionLocalProvider(LocalBlico provides p) {
        // Texto sem cor explícita segue o tema; sem isso, sai preto também no modo escuro.
        MaterialTheme(colorScheme = scheme, typography = remember { typography() }) {
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}

/** Fundo de tela. */
@Composable
fun Modifier.screen(): Modifier = background(LocalBlico.current.background)

/** Título grande em pontos. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.displaySmall) {
    Text(text, style = style, color = LocalBlico.current.text, modifier = modifier)
}

/** Topo de cada aba: o nome em pontos e uma linha mono de contexto. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val p = LocalBlico.current
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = MaterialTheme.typography.displaySmall, color = p.text)
            if (subtitle != null) Text(subtitle.uppercase(), style = PanelLabel, color = p.muted, modifier = Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

/** Título de seção: rótulo mono com uma linha tracejada até a borda. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val p = LocalBlico.current
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = PanelLabel, color = p.muted)
        Spacer(Modifier.width(10.dp))
        DashedRule(Modifier.weight(1f))
        trailing()
    }
}

/** Linha tracejada fina, a das listas do painel. */
@Composable
fun DashedRule(modifier: Modifier = Modifier) {
    val color = LocalBlico.current.separator
    Canvas(modifier.height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
    }
}

/** Ponto de status: verde quando algo está ligado ou ouvindo. */
@Composable
fun LiveDot(color: Color = LocalBlico.current.green, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

val CardShape = RoundedCornerShape(14.dp)
private val ControlShape = RoundedCornerShape(10.dp)
private val ChipShape = RoundedCornerShape(8.dp)

/** Widget do painel: branco, sem borda nem sombra, sobre o fundo de pedra. */
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

/** Botão: o principal é tinta escura cheia; o secundário é cinza com texto escuro. */
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
            .background(if (primary) p.ink else p.fill)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (primary) p.onInk else p.text)
    }
}

/** Ação só de texto, como "Fechar". */
@Composable
fun BlicoLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalBlico.current.text,
        modifier = modifier
            .alpha(if (pressed) 0.5f else 0.75f)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

/** Opção de escolha, em mono: marcada fica em tinta cheia; as outras, só o contorno. */
@Composable
fun BlicoChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalBlico.current
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) p.onInk else p.text,
        modifier = modifier
            .padding(vertical = 3.dp)
            .clip(ChipShape)
            .background(if (selected) p.ink else Color.Transparent)
            .border(1.dp, if (selected) p.ink else p.separator, ChipShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Marca de passo: círculo verde com ✓ quando feito, contorno pontilhado quando falta. */
@Composable
fun BlicoMark(done: Boolean?, modifier: Modifier = Modifier) {
    val p = LocalBlico.current
    if (done == null) return
    if (done) {
        Box(modifier.size(22.dp).clip(CircleShape).background(p.green), contentAlignment = Alignment.Center) {
            Text("✓", style = MaterialTheme.typography.labelMedium.copy(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold), color = p.onGreen)
        }
    } else {
        DottedRing(modifier.size(22.dp), color = p.separator, strokeWidth = 2.dp, dots = 14)
    }
}

/** Anel de pontos, a assinatura do painel: em volta do botão de falar e nos passos que faltam. */
@Composable
fun DottedRing(modifier: Modifier = Modifier, color: Color = LocalBlico.current.green, strokeWidth: Dp = 3.dp, dots: Int = 28) {
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val r = (size.minDimension - stroke) / 2
        val c = center
        repeat(dots) { i ->
            val a = 2 * Math.PI * i / dots
            drawCircle(color, radius = stroke / 2, center = Offset(c.x + r * Math.cos(a).toFloat(), c.y + r * Math.sin(a).toFloat()))
        }
    }
}

/** Progresso em pontos: verdes até onde chegou, cinza no resto. */
@Composable
fun BlicoProgress(fraction: Float, modifier: Modifier = Modifier) {
    val p = LocalBlico.current
    val f = fraction.coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val d = size.height
        val step = d * 1.6f
        val count = (size.width / step).toInt().coerceAtLeast(1)
        val lit = (count * f).toInt()
        repeat(count) { i ->
            drawCircle(if (i < lit) p.green else p.fill, radius = d / 2, center = Offset(i * step + d / 2, d / 2))
        }
    }
}

/** Cores dos avatares: tons que leem bem com texto claro, nos dois temas. */
private val AvatarColors = listOf(
    Color(0xFF1FA34A), Color(0xFF2F6FDB), Color(0xFFC2581E), Color(0xFF8A3FD1),
    Color(0xFF0F8C8C), Color(0xFFB8355A), Color(0xFF6B6B64),
)

/** A cor do avatar de [name], a mesma em todo lugar; serve também para o nome escrito ao lado. */
fun avatarColor(name: String): Color = AvatarColors[Math.floorMod(name.trim().lowercase().hashCode(), AvatarColors.size)]

/** Avatar redondo com a inicial em pontos. Sem [color], a cor sai do nome, sempre a mesma para ele. */
@Composable
fun BlicoAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 36.dp, color: Color? = null) {
    val bg = color ?: avatarColor(name)
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            initial,
            style = TextStyle(fontFamily = Doto, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.5f).sp),
            color = Color.White,
        )
    }
}
