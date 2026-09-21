package com.example.opendash.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.ui.theme.*

data class BarEntry(val label: String, val value: Float)

@Composable
fun OpenDashBarChart(
    data: List<BarEntry>,
    modifier: Modifier = Modifier,
    height: Dp = 108.dp,
) {
    if (data.isEmpty()) return
    val maxVal = data.maxOf { it.value } * 1.12f
    val primary = MaterialTheme.colorScheme.primary
    val primaryDim = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    val barRest = MaterialTheme.colorScheme.surfaceContainerHighest
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier
            .height(height)
            .padding(top = 8.dp),
    ) {
        data.forEach { entry ->
            val pct = entry.value / maxVal
            val best = entry.value == data.maxOf { it.value }
            val barH = (height.value - 26f) * pct

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = entry.value.toString(),
                    color = if (best) primary else secondaryText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = GeistMonoFamily,
                    modifier = Modifier.padding(bottom = 7.dp),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barH.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .drawBehind {
                            if (best) {
                                drawRect(
                                    brush = Brush.verticalGradient(listOf(primary, primaryDim)),
                                )
                            } else {
                                drawRect(color = barRest)
                            }
                        },
                )

                Text(
                    text = entry.label,
                    color = secondaryText,
                    fontSize = 10.5.sp,
                    fontFamily = GeistMonoFamily,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
        }
    }
}
