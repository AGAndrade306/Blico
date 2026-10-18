package app.blico.ui

import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.layout.BoxWithConstraints
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
import app.blico.core.Origin

/**
 * Visual "Painel" com a paleta "Calma luminosa": fundo de argila clara, cartões quase brancos como
 * widgets, um bloco de tinta para a ação principal. O acabamento é de terminal, em ASCII: a marca em
 * letreiro, números e rótulos em IBM Plex Mono, progresso como `[####----]`, marcas `[x]`. O texto
 * corrido fica em IBM Plex Sans. Cada cor tem um significado e só aparece com ele: verde para o que
 * roda no celular e está pronto, azul para ouvindo, pensando e nuvem, laranja para o Confirmar e alertas.
 */
@Immutable
data class BlicoPalette(
    val background: Color,
    val card: Color,
    /** No celular e pronto: status, progresso, marcas e o selo `[celular]`. */
    val green: Color,
    val text: Color,
    val muted: Color,
    val separator: Color,
    /** Fundo de botões secundários e trilhos. */
    val fill: Color,
    val onGreen: Color,
    /** Alertas e falhas, no mesmo laranja do Confirmar. */
    val danger: Color,
    val dark: Boolean,
    /** Bloco escuro do painel e botão principal. */
    val ink: Color = text,
    val onInk: Color = background,
    /** Ouvindo, pensando e nuvem. */
    val blue: Color = green,
    /** Só o Confirmar. */
    val orange: Color = danger,
    /** Fundos claros dos selos, um por cor. */
    val greenSoft: Color = fill,
    val blueSoft: Color = fill,
    val orangeSoft: Color = fill,
)

val LightBlico = BlicoPalette(
    background = Color(0xFFEFE9E3),
    card = Color(0xFFFBF8F5),
    green = Color(0xFF2F7A4F),
    text = Color(0xFF1E1B19),
    muted = Color(0xFF6B625C),
    separator = Color(0xFFD9CFC6),
    fill = Color(0xFFE6DED6),
    onGreen = Color(0xFFFFFFFF),
    danger = Color(0xFFB9461A),
    dark = false,
    ink = Color(0xFF1E1B19),
    onInk = Color(0xFFF4EFEA),
    blue = Color(0xFF3155E8),
    orange = Color(0xFFB9461A),
    greenSoft = Color(0xFFE1EDE4),
    blueSoft = Color(0xFFE4E9FD),
    orangeSoft = Color(0xFFF8E3D8),
)

/** Grafite quente, sem preto puro; as cores de significado clareiam para ler no escuro. */
val DarkBlico = BlicoPalette(
    background = Color(0xFF1A1715),
    card = Color(0xFF24201D),
    green = Color(0xFF8FD0A6),
    text = Color(0xFFF1EBE5),
    muted = Color(0xFFA39890),
    separator = Color(0xFF3A3430),
    fill = Color(0xFF302A26),
    onGreen = Color(0xFF1A1715),
    danger = Color(0xFFF2A07A),
    dark = true,
    ink = Color(0xFF3A332E),
    onInk = Color(0xFFF1EBE5),
    blue = Color(0xFF93A8FF),
    orange = Color(0xFFF2A07A),
    greenSoft = Color(0xFF22332A),
    blueSoft = Color(0xFF232A45),
    orangeSoft = Color(0xFF3A261C),
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
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)

private fun typography(): Typography {
    fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
        TextStyle(fontFamily = PlexSans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)
    fun mono(size: Int, line: Int) =
        TextStyle(fontFamily = PlexMono, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    return Typography(
        displayLarge = mono(44, 50),
        displayMedium = mono(36, 42),
        displaySmall = mono(28, 34),
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

/** Título grande em mono. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.displaySmall) {
    Text(text, style = style, color = LocalBlico.current.text, modifier = modifier)
}

/** Topo de cada aba: o nome em mono, sublinhado com `=` como num terminal, e uma linha de contexto. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val p = LocalBlico.current
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = MaterialTheme.typography.displaySmall, color = p.text)
            Text("=".repeat(title.length), style = MaterialTheme.typography.displaySmall.copy(lineHeight = 18.sp), color = p.muted, maxLines = 1)
            if (subtitle != null) Text("> " + subtitle.uppercase(), style = PanelLabel, color = p.muted, modifier = Modifier.padding(top = 6.dp))
        }
        trailing()
    }
}

/** Título de seção: rótulo mono com uma linha tracejada até a borda. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val p = LocalBlico.current
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("// " + text.uppercase(), style = PanelLabel, color = p.muted)
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

/** Status em ASCII: `[*]` verde quando ligado, `[ ]` apagado. */
@Composable
fun StatusMark(on: Boolean, modifier: Modifier = Modifier, color: Color = LocalBlico.current.green) {
    val p = LocalBlico.current
    Text(if (on) "[*]" else "[ ]", style = PanelLabel, color = if (on) color else p.muted, modifier = modifier)
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

/**
 * Botão: o principal é tinta escura cheia; o secundário é cinza com texto escuro. [color] troca a
 * tinta do principal por uma cor de significado, como o laranja do Confirmar.
 */
@Composable
fun BlicoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    color: Color? = null,
) {
    val p = LocalBlico.current
    val fill = if (primary) color ?: p.ink else p.fill
    val label = when {
        !primary -> p.text
        color == null -> p.onInk
        p.dark -> p.background
        else -> Color.White
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .padding(vertical = 4.dp)
            .defaultMinSize(minHeight = 44.dp)
            .alpha(if (!enabled) 0.4f else if (pressed) 0.7f else 1f)
            .clip(ControlShape)
            .background(fill)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = label)
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

/** Marca de passo: `[x]` verde quando feito, `[ ]` quando falta. */
@Composable
fun BlicoMark(done: Boolean?, modifier: Modifier = Modifier) {
    val p = LocalBlico.current
    if (done == null) return
    Text(
        if (done) "[x]" else "[ ]",
        style = MaterialTheme.typography.titleMedium.copy(fontFamily = PlexMono),
        color = if (done) p.green else p.muted,
        modifier = modifier,
    )
}

/** Progresso em ASCII, `[######......]`, ocupando a largura que tiver. */
@Composable
fun BlicoProgress(fraction: Float, modifier: Modifier = Modifier) {
    val p = LocalBlico.current
    val f = fraction.coerceIn(0f, 1f)
    val style = MaterialTheme.typography.labelMedium
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Plex Mono tem avanço de 0,6 em; sobram duas casas para os colchetes.
        val cells = ((maxWidth.value / (style.fontSize.value * 0.6f)).toInt() - 2).coerceIn(4, 60)
        val lit = (cells * f).toInt()
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = p.muted)) { append("[") }
                withStyle(SpanStyle(color = p.green)) { append("#".repeat(lit)) }
                withStyle(SpanStyle(color = p.separator)) { append(".".repeat(cells - lit)) }
                withStyle(SpanStyle(color = p.muted)) { append("]") }
            },
            style = style,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Selo em mono, `[celular]`, com o fundo claro da cor. */
@Composable
fun BlicoBadge(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.clip(ChipShape).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** De onde veio o resultado: verde no celular, azul na nuvem e na web, neutro para serviços. */
@Composable
fun OriginBadge(origin: Origin, modifier: Modifier = Modifier, detail: String? = null) {
    val p = LocalBlico.current
    val text = if (detail.isNullOrBlank()) origin.badge else "${origin.badge} $detail"
    when (origin.kind) {
        Origin.Kind.DEVICE -> BlicoBadge(text, p.green, p.greenSoft, modifier)
        Origin.Kind.CLOUD, Origin.Kind.WEB -> BlicoBadge(text, p.blue, p.blueSoft, modifier)
        Origin.Kind.SERVICE -> BlicoBadge(text, p.muted, p.fill, modifier)
    }
}

/** Cores dos avatares: tons que leem bem com texto claro, nos dois temas. */
private val AvatarColors = listOf(
    Color(0xFF1FA34A), Color(0xFF2F6FDB), Color(0xFFC2581E), Color(0xFF8A3FD1),
    Color(0xFF0F8C8C), Color(0xFFB8355A), Color(0xFF6B6B64),
)

/** A cor do avatar de [name], a mesma em todo lugar; serve também para o nome escrito ao lado. */
fun avatarColor(name: String): Color = AvatarColors[Math.floorMod(name.trim().lowercase().hashCode(), AvatarColors.size)]

/** Avatar redondo com a inicial em mono. Sem [color], a cor sai do nome, sempre a mesma para ele. */
@Composable
fun BlicoAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 36.dp, color: Color? = null) {
    val bg = color ?: avatarColor(name)
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            initial,
            style = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.46f).sp),
            color = Color.White,
        )
    }
}
