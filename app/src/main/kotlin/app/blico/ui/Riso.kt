package app.blico.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.blico.R
import kotlin.random.Random

/**
 * Risografia: papel creme, duas tintas (o verde e o rosa do logo), grão de impressão
 * e a segunda cor levemente fora de registro. Tudo vem daqui, para a UI ter uma cara só.
 */
@Immutable
data class RisoPalette(
    val paper: Color,
    val card: Color,
    val green: Color,
    val pink: Color,
    val text: Color,
    val muted: Color,
    val onGreen: Color,
    val danger: Color,
    val dark: Boolean,
)

val LightRiso = RisoPalette(
    paper = Color(0xFFFBF9EF),
    card = Color(0xFFFFFDF6),
    green = Color(0xFF139A2E),
    pink = Color(0xFFF6A3EC),
    text = Color(0xFF14361D),
    muted = Color(0xFF4F6B55),
    onGreen = Color(0xFFFBF9EF),
    danger = Color(0xFFD7263D),
    dark = false,
)

val DarkRiso = RisoPalette(
    paper = Color(0xFF121712),
    card = Color(0xFF1A211A),
    green = Color(0xFF3CC45A),
    pink = Color(0xFFF6A3EC),
    text = Color(0xFFF2EFE2),
    muted = Color(0xFFB4BEAE),
    onGreen = Color(0xFF0E140E),
    danger = Color(0xFFFF6B7A),
    dark = true,
)

val LocalRiso = staticCompositionLocalOf { LightRiso }

/** Bagel Fat One para títulos (redonda e gorda como o logo), Rubik para o resto. */
val Display = FontFamily(Font(R.font.bagel_fat_one))
val Body = FontFamily(
    Font(R.font.rubik_regular, FontWeight.Normal),
    Font(R.font.rubik_medium, FontWeight.Medium),
    Font(R.font.rubik_bold, FontWeight.Bold),
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.body() = copy(fontFamily = Body)
    fun TextStyle.display() = copy(fontFamily = Display, fontWeight = FontWeight.Normal)
    return Typography(
        displayLarge = base.displayLarge.display(),
        displayMedium = base.displayMedium.display(),
        displaySmall = base.displaySmall.display(),
        headlineLarge = base.headlineLarge.display(),
        headlineMedium = base.headlineMedium.display().copy(fontSize = 34.sp, lineHeight = 38.sp),
        headlineSmall = base.headlineSmall.body().copy(fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.display().copy(fontSize = 24.sp),
        titleMedium = base.titleMedium.display().copy(fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
        titleSmall = base.titleSmall.body().copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.body(),
        bodyMedium = base.bodyMedium.body(),
        bodySmall = base.bodySmall.body().copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.body().copy(fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp),
        labelMedium = base.labelMedium.body().copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.body(),
    )
}

@Composable
fun RisoTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkRiso else LightRiso
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.green, onPrimary = p.onGreen, secondary = p.pink, onSecondary = p.onGreen,
            background = p.paper, onBackground = p.text, surface = p.card, onSurface = p.text,
            surfaceVariant = p.card, onSurfaceVariant = p.muted, outline = p.green, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.green, onPrimary = p.onGreen, secondary = p.pink, onSecondary = p.text,
            background = p.paper, onBackground = p.text, surface = p.card, onSurface = p.text,
            surfaceVariant = p.card, onSurfaceVariant = p.muted, outline = p.green, error = p.danger,
        )
    }
    CompositionLocalProvider(LocalRiso provides p) {
        MaterialTheme(colorScheme = scheme, typography = remember { typography() }, content = content)
    }
}

/** Ruído de impressão: pontinhos aleatórios, gerados uma vez e repetidos como textura. */
private val grainBitmap: ImageBitmap by lazy {
    val size = 160
    val rnd = Random(7)
    val pixels = IntArray(size * size) {
        val v = rnd.nextFloat()
        when {
            v < 0.10f -> (0x26 shl 24) // ponto escuro
            v < 0.16f -> (0x30 shl 24) or 0xFFFFFF // falha de tinta, clara
            else -> 0
        }
    }
    android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Grão por cima do que já foi desenhado, só onde há tinta ou papel (respeita o recorte). */
fun Modifier.grain(strength: Float = 1f): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush = ShaderBrush(ImageShader(grainBitmap, TileMode.Repeated, TileMode.Repeated)),
        alpha = strength.coerceIn(0f, 1f),
    )
}

/** Fundo de página: papel com grão. */
@Composable
fun Modifier.paper(): Modifier = background(LocalRiso.current.paper).grain(0.8f)

/**
 * Título com a segunda tinta fora de registro: o rosa por baixo, deslocado,
 * e o verde por cima, como numa impressão em duas passadas.
 */
@Composable
fun RisoTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    offset: Dp = 2.5.dp,
) {
    val p = LocalRiso.current
    Box(modifier) {
        Text(text, style = style, color = p.pink, modifier = Modifier.offset(offset, offset))
        Text(text, style = style, color = p.green)
    }
}

private val CardShape = RoundedCornerShape(22.dp)

/** Cartão impresso: papel, contorno em tinta verde e um bloco rosa deslocado atrás. */
@Composable
fun RisoCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    shadow: Dp = 5.dp,
    accent: Color = LocalRiso.current.pink,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalRiso.current
    Box(modifier.padding(end = shadow, bottom = shadow)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .clip(shape)
                .background(accent)
                .grain(),
        )
        Column(
            Modifier
                .clip(shape)
                .background(p.card)
                .border(BorderStroke(2.dp, p.green), shape)
                .grain(0.6f)
                .padding(contentPadding),
            content = content,
        )
    }
}

private val PillShape = RoundedCornerShape(50)

/**
 * Botão de tinta: o principal é verde cheio com sombra rosa; o secundário é contorno.
 * Ao apertar, ele "afunda" sobre a sombra.
 */
@Composable
fun RisoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val p = LocalRiso.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val lift = if (pressed || !enabled) 0.dp else 3.dp
    Box(modifier.padding(end = 3.dp, bottom = 3.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(3.dp, 3.dp)
                .clip(PillShape)
                .background(if (enabled) p.pink else p.pink.copy(alpha = 0.35f)),
        )
        val fill = when {
            !enabled -> p.card
            primary -> p.green
            else -> p.card
        }
        Box(
            Modifier
                .offset(3.dp - lift, 3.dp - lift)
                .clip(PillShape)
                .background(fill)
                .border(2.dp, if (enabled) p.green else p.muted.copy(alpha = 0.5f), PillShape)
                .grain(if (primary) 0.9f else 0.5f)
                .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    !enabled -> p.muted
                    primary -> p.onGreen
                    else -> p.green
                },
            )
        }
    }
}

/** Botão só de texto, para ações de saída como "Fechar". */
@Composable
fun RisoLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalRiso.current
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = p.green,
        modifier = modifier
            .clip(PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** Etiqueta de escolha: selecionada fica cheia de verde, as outras só com contorno. */
@Composable
fun RisoChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalRiso.current
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) p.onGreen else p.green,
        modifier = modifier
            .padding(vertical = 3.dp)
            .clip(PillShape)
            .background(if (selected) p.green else Color.Transparent)
            .border(1.5.dp, p.green, PillShape)
            .grain(if (selected) 0.8f else 0f)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Marca de passo: círculo rosa com ✓ quando feito, só contorno quando falta. */
@Composable
fun RisoMark(done: Boolean?, modifier: Modifier = Modifier) {
    val p = LocalRiso.current
    if (done == null) return
    Box(
        modifier
            .clip(PillShape)
            .background(if (done) p.green else Color.Transparent)
            .border(2.dp, p.green, PillShape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (done) "✓" else " ", style = MaterialTheme.typography.labelMedium, color = p.onGreen)
    }
}
