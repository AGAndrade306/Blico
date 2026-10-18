package app.blico.ui

import android.provider.Settings
import android.view.RoundedCorner
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * O estado do Blico como luz: a aurora na borda da tela e o anel do ícone mudam de cor conforme
 * onde está o seu dado. Em repouso, nada brilha.
 */
enum class Aura { REST, LISTENING, THINKING, DEVICE, CLOUD, CONFIRM }

private val Green = Color(0xFF2F7A4F)
private val GreenLight = Color(0xFF8FD0A6)
private val GreenPale = Color(0xFFC9E8C0)
private val GreenMid = Color(0xFF6FB98A)
private val Blue = Color(0xFF3155E8)
private val BlueLight = Color(0xFF7B93FF)
private val BluePale = Color(0xFFB9C6FF)
private val BlueSoft = Color(0xFF93A8FF)
private val BlueDeep = Color(0xFF1E3BB8)
private val Orange = Color(0xFFB9461A)
private val OrangeLight = Color(0xFFF2A07A)
private val OrangeMid = Color(0xFFE8622A)
private val OrangePale = Color(0xFFF8E3D8)

/** Cores e ritmo de cada estado: [turnMs] é uma volta da luz; [breatheMs], um ciclo do brilho interno. */
private class Look(val edge: List<Color>, val ring: List<Color>, val glow: Color, val turnMs: Int, val breatheMs: Int)

private val Listening = listOf(Blue, GreenLight, BlueLight, Green, Blue, BluePale, Blue)

private fun look(aura: Aura): Look? = when (aura) {
    Aura.REST -> null
    Aura.LISTENING -> Look(Listening, listOf(Blue, GreenLight, BlueLight, Blue), Blue.copy(alpha = 0.35f), 3500, 1400)
    Aura.THINKING -> Look(Listening, listOf(Blue, GreenLight, BlueLight, Blue), Blue.copy(alpha = 0.3f), 7000, 3200)
    Aura.DEVICE -> Look(listOf(Green, GreenLight, GreenPale, Green, GreenMid, Green), listOf(Green, GreenLight, Green), Green.copy(alpha = 0.32f), 7000, 3200)
    Aura.CLOUD -> Look(listOf(Blue, BlueSoft, BlueDeep, BluePale, Blue), listOf(Blue, BluePale, Blue), Blue.copy(alpha = 0.32f), 7000, 3200)
    Aura.CONFIRM -> Look(listOf(Orange, OrangeLight, OrangeMid, OrangePale, Orange), listOf(Orange, OrangeLight, Orange), Orange.copy(alpha = 0.32f), 9000, 1100)
}

/** "Remover animações" ligado no Android: a luz fica parada, sem girar nem respirar. */
@Composable
private fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

private val Still = mutableFloatStateOf(0f)
private val Full = mutableFloatStateOf(1f)

@Composable
private fun InfiniteTransition.turn(ms: Int, still: Boolean): State<Float> =
    if (still) Still else animateFloat(0f, 360f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = "giro")

/**
 * Aurora de privacidade: uma faixa de luz gira ao redor da tela inteira, com um brilho que respira
 * por dentro. Verde no celular, azul ao ouvir e na nuvem, laranja esperando o Confirmar. Vai por
 * cima de tudo e não pega toques.
 */
@Composable
fun AuroraEdge(aura: Aura, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val view = LocalView.current
    val fallback = with(LocalDensity.current) { 28.dp.toPx() }
    // Acompanha o canto arredondado da tela de verdade, lido na hora de desenhar, quando a janela
    // já sabe dele; sem a informação, um canto comum.
    val corner = remember(view) {
        { view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: fallback }
    }
    Crossfade(targetState = aura, modifier = modifier, animationSpec = tween(450), label = "aurora") { a ->
        look(a)?.let { AuroraLayers(it, corner, still) }
    }
}

@Composable
private fun AuroraLayers(l: Look, corner: () -> Float, still: Boolean) {
    val t = rememberInfiniteTransition(label = "aurora")
    val turn = t.turn(l.turnMs, still)
    val breathe = if (still) Full else t.animateFloat(
        0.5f, 1f, infiniteRepeatable(tween(l.breatheMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "respira",
    )
    Box(Modifier.fillMaxSize()) {
        // Brilho interno: uma faixa larga e muito borrada, metade fora da tela.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = breathe.value }
                .blur(40.dp, BlurredEdgeTreatment.Rectangle)
                .drawWithCache {
                    val w = 64.dp.toPx()
                    val r = corner()
                    onDrawBehind { drawRoundRect(l.glow, cornerRadius = CornerRadius(r), style = Stroke(w)) }
                },
        )
        // A faixa de luz que gira: uma borrada por baixo e uma nítida, fina, por cima.
        EdgeBand(l.edge, corner, width = 14.dp, blur = 10.dp, turn = turn)
        EdgeBand(l.edge, corner, width = 4.dp, blur = null, turn = turn)
    }
}

@Composable
private fun EdgeBand(colors: List<Color>, corner: () -> Float, width: Dp, blur: Dp?, turn: State<Float>) {
    val base = Modifier.fillMaxSize()
    Spacer(
        (if (blur != null) base.blur(blur, BlurredEdgeTreatment.Rectangle) else base).drawWithCache {
            val w = width.toPx()
            val r = (corner() - w / 2).coerceAtLeast(0f)
            val cx = size.width / 2
            val cy = size.height / 2
            // Gradiente cônico do centro da tela; girar a matriz gira a luz sem mexer na faixa.
            val shader = android.graphics.SweepGradient(cx, cy, colors.map { it.toArgb() }.toIntArray(), null)
            val matrix = android.graphics.Matrix()
            val brush = ShaderBrush(shader)
            onDrawBehind {
                matrix.setRotate(turn.value, cx, cy)
                shader.setLocalMatrix(matrix)
                drawRoundRect(
                    brush,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(r),
                    style = Stroke(w),
                )
            }
        },
    )
}

/**
 * Ícone vivo: dois olhos dentro de um anel que gira. Piscam em repouso, viram barras de voz ao ouvir,
 * olham para cima pensando e na nuvem, e pulsam esperando o Confirmar.
 */
@Composable
fun BlicoEyes(aura: Aura, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val p = LocalBlico.current
    val still = reducedMotion()
    val ring = look(aura)?.ring
    val t = rememberInfiniteTransition(label = "olhos")
    val turn = t.turn(4000, still || ring == null)
    val k = size.value / 44f

    // Cada estado mexe os olhos de um jeito; o resto fica parado. Piscar e olhar são fases de 0 a 1
    // em laço, e as curvas [closed] e [lookUp] dizem o que cada fase faz.
    val blink = if (still || aura !in BLINKS) Still else t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "pisca",
    )
    val left = if (still || aura != Aura.LISTENING) Full else t.animateFloat(
        0.35f, 1.25f, infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "voz1",
    )
    val right = if (still || aura != Aura.LISTENING) Full else t.animateFloat(
        0.35f, 1.25f, infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(180)), label = "voz2",
    )
    val gaze = if (still || aura !in LOOKS_UP) Still else t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "olha",
    )
    val pulse = if (still || aura != Aura.CONFIRM) Full else t.animateFloat(
        1f, 1.08f, infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "chama",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (ring == null) drawCircle(p.separator)
            else rotate(turn.value) { drawCircle(Brush.sweepGradient(ring)) }
        }
        Box(
            Modifier.fillMaxSize().padding((3 * k).dp).clip(CircleShape).background(p.card),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy((7 * k).dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    scaleX = pulse.value
                    scaleY = pulse.value
                    val up = lookUp(gaze.value)
                    translationX = 2.dp.toPx() * k * up
                    translationY = -2.dp.toPx() * k * up
                },
            ) {
                Eye(k, p.text) { closed(blink.value) * left.value }
                Eye(k, p.text) { closed(blink.value) * right.value }
            }
        }
    }
}

private val BLINKS = setOf(Aura.REST, Aura.DEVICE)

/** Piscada rápida no meio do ciclo: fecha até 10% em 46% e reabre em 48%. Fase 0 é olho aberto. */
private fun closed(phase: Float): Float = when {
    phase < 0.44f || phase > 0.48f -> 1f
    phase < 0.46f -> 1f - 0.9f * (phase - 0.44f) / 0.02f
    else -> 0.1f + 0.9f * (phase - 0.46f) / 0.02f
}

/** Olhar para cima e para o lado: parado até 30%, sobe até 45%, fica até 80% e volta. */
private fun lookUp(phase: Float): Float = when {
    phase < 0.3f -> 0f
    phase < 0.45f -> (phase - 0.3f) / 0.15f
    phase < 0.8f -> 1f
    else -> 1f - (phase - 0.8f) / 0.2f
}
private val LOOKS_UP = setOf(Aura.THINKING, Aura.CLOUD)

@Composable
private fun Eye(k: Float, color: Color, scale: () -> Float) {
    Box(
        Modifier
            .size((4 * k).dp, (10 * k).dp)
            .graphicsLayer { scaleY = scale() }
            .clip(RoundedCornerShape((2 * k).dp))
            .background(color),
    )
}
