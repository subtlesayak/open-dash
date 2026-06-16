package com.example.opendash.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.ui.theme.*
import kotlin.math.*

/**
 * Circular Tripper Dash viewport — the signature element.
 * Mirrors the Royal Enfield Tripper round TFT display.
 *
 * @param size      Diameter of the circle
 * @param pan       Map pan offset (±46 units)
 * @param zoom      Map zoom scale (0.7–2.4)
 * @param distance  Next turn distance string
 * @param street    Current street name
 * @param live      Show live ring pulse (connected state)
 * @param compact   Hide distance/street labels (for small embeds)
 */
@Composable
fun CircularDash(
    size: Dp,
    pan: Offset = Offset.Zero,
    zoom: Float = 1f,
    distance: String = "400 m",
    street: String = "NH-707, Shimla",
    live: Boolean = true,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Live ring pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "live-ring")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 2.0f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "ring-scale",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "ring-alpha",
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            // Outer bezel rings via border layering
            .border(2.dp, Color(0xFF232729), CircleShape)
            .padding(2.dp)
            .border(6.dp, Color(0xFF0D0F10), CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFF14201F),
                        0.7f to Color(0xFF0C1413),
                        1f to Color(0xFF070B0B),
                    )
                )
            ),
    ) {
        // Map layer with pan/zoom
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = pan.x
                    translationY = pan.y
                    scaleX = zoom
                    scaleY = zoom
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
        ) {
            drawMapContents()
        }

        // Tick ring overlay
        Canvas(Modifier.fillMaxSize()) {
            drawTickRing()
        }

        // Live ring pulse (behind the rider marker)
        if (live) {
            Canvas(Modifier.fillMaxSize()) {
                // this.size is DrawScope.size (px)
                drawCircle(
                    color = Gold.copy(alpha = ringAlpha * 0.5f),
                    radius = this.size.minDimension * 0.12f * ringScale,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Rider marker (centre, fixed) — 26dp canvas
        Canvas(Modifier.size(26.dp)) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val r9 = 9.dp.toPx()
            drawCircle(color = Color(0xFF0C1413), radius = r9, center = Offset(cx, cy))
            drawCircle(color = Gold, radius = r9, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
            val path = Path().apply {
                moveTo(cx, 2.dp.toPx())
                lineTo(this@Canvas.size.width - 2.dp.toPx(), this@Canvas.size.height - 2.dp.toPx())
                lineTo(cx, this@Canvas.size.height - 5.dp.toPx())
                lineTo(2.dp.toPx(), this@Canvas.size.height - 2.dp.toPx())
                close()
            }
            drawPath(path, Gold)
        }

        // N compass
        Text(
            "N",
            color = Gold,
            fontFamily = GeistMonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.05f).sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = (size.value * 0.085f).dp),
        )

        // Non-compact overlays: next-turn banner + street label
        if (!compact) {
            // Next-turn banner
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = (size.value * 0.17f).dp)
                    .clip(CircleShape)
                    .background(Color(0xD00C0C0C))
                    .border(1.dp, GoldTint2, CircleShape)
                    .padding(horizontal = 13.dp, vertical = 5.dp),
            ) {
                Text(
                    distance,
                    color = TextHi,
                    fontFamily = GeistMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.062f).sp,
                )
            }

            // Street label at bottom
            Text(
                street,
                color = TextHi,
                fontFamily = GeistFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.052f).sp,
                letterSpacing = (-0.1).sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (size.value * 0.13f).dp)
                    .widthIn(max = size * 0.74f),
            )
        }

        // Glass highlight
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = 0.07f),
                        0.42f to Color.Transparent,
                    ),
                    center = Offset(size.toPx() * 0.3f, size.toPx() * 0.08f),
                    radius = size.toPx() * 0.6f,
                ),
            )
        }
    }
}

private fun DrawScope.drawMapContents() {
    val w = size.width
    val h = size.height

    // Terrain fill
    val terrainPath = Path().apply {
        moveTo(0f, h * 0.75f)
        cubicTo(w * 0.2f, h * 0.675f, w * 0.35f, h * 0.825f, w * 0.55f, h * 0.75f)
        cubicTo(w * 0.75f, h * 0.69f, w * 0.875f, h * 0.79f, w, h * 0.75f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(terrainPath, Color(0xFF101B1A))

    // Side roads
    val roadPaint = Paint().apply {
        color = Color(0xFF1D2A2A)
        strokeWidth = 5f
        strokeCap = StrokeCap.Round
        style = PaintingStyle.Stroke
    }
    drawContext.canvas.drawPath(Path().apply {
        moveTo(w * 0.1f, h * 0.2f)
        cubicTo(w * 0.3f, h * 0.35f, w * 0.3f, h * 0.6f, w * 0.15f, h * 0.85f)
    }, roadPaint)
    drawContext.canvas.drawPath(Path().apply {
        moveTo(w * 0.9f, h * 0.15f)
        cubicTo(w * 0.75f, h * 0.35f, w * 0.825f, h * 0.6f, w * 0.75f, h * 0.9f)
    }, roadPaint)
    drawContext.canvas.drawPath(Path().apply {
        moveTo(w * 0.05f, h * 0.55f)
        lineTo(w * 0.4f, h * 0.5f)
        lineTo(w * 0.75f, h * 0.56f)
        lineTo(w, h * 0.5f)
    }, roadPaint)

    // Active route (gold)
    val routePaint = Paint().apply {
        color = Gold
        strokeWidth = 7f
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        style = PaintingStyle.Stroke
    }
    val routePath = Path().apply {
        moveTo(w * 0.5f, h * 0.875f)
        cubicTo(w * 0.5f, h * 0.75f, w * 0.35f, h * 0.7f, w * 0.36f, h * 0.55f)
        cubicTo(w * 0.37f, h * 0.41f, w * 0.55f, h * 0.39f, w * 0.56f, h * 0.26f)
        cubicTo(w * 0.565f, h * 0.17f, w * 0.5f, h * 0.13f, w * 0.5f, h * 0.06f)
    }
    drawContext.canvas.drawPath(routePath, routePaint)

    // Dashed white overlay
    drawContext.canvas.drawPath(routePath, Paint().apply {
        color = Color(0x80FFF5DD)
        strokeWidth = 1.6f
        strokeCap = StrokeCap.Round
        style = PaintingStyle.Stroke
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 7f))
    })
}

private fun DrawScope.drawTickRing() {
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (i in 0 until 60) {
        val angle = (i.toFloat() / 60f) * 2f * PI.toFloat() - PI.toFloat() / 2f
        val major = i % 5 == 0
        val r1 = (if (major) 0.9f else 0.93f) * (size.width / 2f)
        val r2 = 0.96f * (size.width / 2f)
        drawLine(
            color = if (major) Gold.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.13f),
            start = Offset(cx + cos(angle) * r1, cy + sin(angle) * r1),
            end   = Offset(cx + cos(angle) * r2, cy + sin(angle) * r2),
            strokeWidth = if (major) 1.4f else 0.8f,
            cap = StrokeCap.Butt,
        )
    }
}
