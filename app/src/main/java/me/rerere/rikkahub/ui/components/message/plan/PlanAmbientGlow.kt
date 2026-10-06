package me.rerere.rikkahub.ui.components.message.plan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/**
 * 计划卡片的「流动氛围光效」。
 *
 * 用一层绕中心缓慢旋转的锥形渐变描边，模拟呼吸/流动的高光，比静态边框更有生命力，
 * 又不像 Material 那样有强制的样式语言。整体克制：低透明度、慢速、不抢内容。
 *
 * @param active 计划仍在推进时，光效更亮、转得更快；完成/取消后转为极淡的静态收束。
 */
@Composable
fun PlanAmbientGlow(
    modifier: Modifier = Modifier,
    colors: List<Color>,
    active: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "planGlow")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (active) 6000 else 14000,
                easing = LinearEasing,
            ),
        ),
        label = "glowAngle",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (active) 2200 else 4200, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "glowPulse",
    )

    val palette = if (colors.isEmpty()) {
        listOf(Color(0xFF6C7BFF), Color(0xFF9D5CFF), Color(0xFF38D6C7), Color(0xFF6C7BFF))
    } else {
        colors
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                val inset = stroke / 2f
                val corner = 18.dp.toPx()
                val brush = Brush.sweepGradient(
                    colors = palette.map { it.copy(alpha = it.alpha * pulse) },
                    center = Offset(size.width / 2f, size.height / 2f),
                )
                rotate(degrees = angle, pivot = Offset(size.width / 2f, size.height / 2f)) {
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(inset, inset),
                        size = Size(
                            width = (size.width - stroke).coerceAtLeast(0f),
                            height = (size.height - stroke).coerceAtLeast(0f),
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                        style = Stroke(width = stroke),
                    )
                }
            },
    ) {
        content()
    }
}
