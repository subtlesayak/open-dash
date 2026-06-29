package com.example.opendash.ui.screens

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Rect

internal const val DASH_ARTBOARD_ASPECT = 1458f / 850f

internal val DashViewportShape = GenericShape { size, _ ->
    val radius = size.width / 2f
    val centerY = size.height * (729f / 850f)
    val circleBounds = Rect(
        left = size.width / 2f - radius,
        top = centerY - radius,
        right = size.width / 2f + radius,
        bottom = centerY + radius,
    )
    val bottomAngle = Math.toDegrees(
        kotlin.math.asin(((size.height - centerY) / radius).coerceIn(-1f, 1f).toDouble()),
    ).toFloat()
    val startAngle = 180f - bottomAngle
    val sweepAngle = 180f + (bottomAngle * 2f)
    val bottomLeftX = size.width / 2f -
        radius * kotlin.math.cos(Math.toRadians(bottomAngle.toDouble())).toFloat()
    val bottomRightX = size.width - bottomLeftX

    moveTo(bottomLeftX, size.height)
    arcTo(circleBounds, startAngle, sweepAngle, false)
    lineTo(bottomRightX, size.height)
    lineTo(bottomLeftX, size.height)
    close()
}
