package com.example.opendash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.opendash.ui.theme.*
import kotlin.math.*

/**
 * Draggable joystick pad.
 * onMove receives normalized X/Y in [-1, 1]. Zero when released.
 */
@Composable
fun Joystick(
    size: Dp = 132.dp,
    onMove: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val maxPx = remember(size) { size.value / 2f - 24f }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colorStops = arrayOf(0f to Surf2, 1f to Surf1),
                )
            )
            .border(1.dp, Line2, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val halfPx = size.toPx() / 2f
                        var dx = offset.x - halfPx
                        var dy = offset.y - halfPx
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist > maxPx) {
                            dx = (dx / dist) * maxPx
                            dy = (dy / dist) * maxPx
                        }
                        knobOffset = Offset(dx, dy)
                        onMove(Offset(dx / maxPx, dy / maxPx))
                    },
                    onDrag = { change, _ ->
                        val halfPx = size.toPx() / 2f
                        var dx = change.position.x - halfPx
                        var dy = change.position.y - halfPx
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist > maxPx) {
                            dx = (dx / dist) * maxPx
                            dy = (dy / dist) * maxPx
                        }
                        knobOffset = Offset(dx, dy)
                        onMove(Offset(dx / maxPx, dy / maxPx))
                    },
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onMove(Offset.Zero)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onMove(Offset.Zero)
                    },
                )
            },
    ) {
        // Guide cross-hairs
        Canvas(Modifier.fillMaxSize()) {
            val s = size.toPx()
            val guides = listOf(
                Offset(s * 0.5f, s * 0.14f) to Offset(s * 0.5f, s * 0.32f),
                Offset(s * 0.5f, s * 0.86f) to Offset(s * 0.5f, s * 0.68f),
                Offset(s * 0.14f, s * 0.5f) to Offset(s * 0.32f, s * 0.5f),
                Offset(s * 0.86f, s * 0.5f) to Offset(s * 0.68f, s * 0.5f),
            )
            guides.forEach { (start, end) ->
                drawLine(Line3, start, end, strokeWidth = 2f, cap = StrokeCap.Round)
            }
        }

        // Knob
        Canvas(
            Modifier
                .size(44.dp)
                .offset { androidx.compose.ui.unit.IntOffset(knobOffset.x.toInt(), knobOffset.y.toInt()) }
        ) {
            val r = 22.dp.toPx()
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(GoldBright, GoldDeep),
                    start = Offset(r * 0.4f, r * 0.4f),
                    end = Offset(r * 1.6f, r * 1.6f),
                ),
                radius = r,
            )
            // Shadow highlight
            drawCircle(
                color = Color.White.copy(alpha = 0.4f),
                radius = r,
                style = Stroke(1.dp.toPx()),
            )
            // Dimple
            drawCircle(Color(0xFF1A1402).copy(alpha = 0.5f), radius = 6.dp.toPx())
        }
    }
}
