package com.larp.launcher.core.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private class Star(
    val x: Float,
    val y: Float,
    val radius: Float,
    val drift: Int,
    val twinkle: Int,
    val phase: Float,
    val warm: Boolean
)

@Composable
fun StarField(modifier: Modifier = Modifier) {
    val stars = remember {
        val random = Random(7)
        List(90) {
            Star(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.6f + random.nextFloat() * 1.8f,
                drift = 1 + random.nextInt(3),
                twinkle = 2 + random.nextInt(5),
                phase = random.nextFloat(),
                warm = random.nextInt(5) == 0
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "stars")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 40000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "starsT"
    )
    val shoot by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shoot"
    )
    Canvas(modifier = modifier) {
        val density = this.density
        stars.forEach { star ->
            val py = ((star.y + t * star.drift) % 1f) * size.height
            val wave = (sin((t * star.twinkle + star.phase) * 2.0 * PI).toFloat() + 1f) / 2f
            val alpha = 0.25f + 0.75f * wave
            val color = if (star.warm) Color(0xFFFFE9B0) else Color.White
            val r = star.radius * density
            val center = Offset(star.x * size.width, py)
            drawCircle(color = color.copy(alpha = alpha * 0.25f), radius = r * 2.6f, center = center)
            drawCircle(color = color.copy(alpha = alpha), radius = r, center = center)
        }
        if (shoot < 0.22f) {
            val p = shoot / 0.22f
            val head = Offset(
                x = size.width * (0.1f + 0.9f * p),
                y = size.height * (0.05f + 0.35f * p)
            )
            val tail = Offset(head.x - 110f * density, head.y - 38f * density)
            val fade = if (p < 0.8f) 1f else (1f - p) / 0.2f
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.9f * fade)),
                    start = tail,
                    end = head
                ),
                start = tail,
                end = head,
                strokeWidth = 2f * density
            )
            drawCircle(color = Color.White.copy(alpha = fade), radius = 2f * density, center = head)
        }
    }
}
