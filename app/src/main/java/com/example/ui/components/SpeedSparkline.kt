package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.ui.theme.GoogleBluePrimary

@Composable
fun SpeedSparkline(
    history: List<Double>,
    modifier: Modifier = Modifier.height(60.dp),
    lineColor: Color = GoogleBluePrimary
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (history.isEmpty()) return@Canvas

        val w = size.width
        val h = size.height
        val maxSpeed = (history.maxOrNull() ?: 1.0).coerceAtLeast(10.0)
        val minSpeed = 0.0

        val stepX = if (history.size > 1) w / (history.size - 1) else w

        val path = Path()
        val fillPath = Path()

        history.forEachIndexed { i, speed ->
            val x = i * stepX
            val normalized = ((speed - minSpeed) / (maxSpeed - minSpeed)).toFloat().coerceIn(0f, 1f)
            val y = h - (normalized * (h - 8.dp.toPx())) - 4.dp.toPx()

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        val lastX = (history.size - 1) * stepX
        fillPath.lineTo(lastX, h)
        fillPath.close()

        // Gradient under curve
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    lineColor.copy(alpha = 0.25f),
                    lineColor.copy(alpha = 0.0f)
                ),
                startY = 0f,
                endY = h
            )
        )

        // Line
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Pulse dot on latest sample
        if (history.isNotEmpty()) {
            val lastSpeed = history.last()
            val normalizedLast = ((lastSpeed - minSpeed) / (maxSpeed - minSpeed)).toFloat().coerceIn(0f, 1f)
            val lastY = h - (normalizedLast * (h - 8.dp.toPx())) - 4.dp.toPx()

            drawCircle(
                color = lineColor.copy(alpha = 0.3f),
                radius = 6.dp.toPx(),
                center = Offset(lastX, lastY)
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = Offset(lastX, lastY)
            )
        }
    }
}
