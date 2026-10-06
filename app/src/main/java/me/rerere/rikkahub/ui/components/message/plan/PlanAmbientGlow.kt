package me.rerere.rikkahub.ui.components.message.plan

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.setValue
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * 计划卡片的「氛围光效」。
 *
 * 设计取向：**不要转圈**。旋转的锥形渐变会让人误以为在加载，也会让边框看起来在漂移。
 * 这里改成：
 * - 一圈固定的水平渐变描边（左→右流动的色带，静止不旋转）；
 * - 叠加极缓慢的呼吸透明度，让卡片「活着」但不抢戏；
 * - 边框严格贴合自身尺寸，圆角与卡片一致，不产生错位/大小偏差。
 *
 * @param active 计划仍在推进：光效略亮；完成/取消：转为很淡的静态收束。
 */
@Composable
fun PlanAmbientGlow(
    modifier: Modifier = Modifier,
    colors: List<Color>,
    active: Boolean = true,
    cornerRadius: Dp = 16.dp,
    strokeWidth: Dp = 1.5.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val isPerformanceMode = LocalSettings.current.displaySetting.performanceMode
    val transition = rememberInfiniteTransition(label = "planGlow")
    val breath by if (isPerformanceMode) {
        remember(active) { androidx.compose.runtime.mutableStateOf(if (active) 0.8f else 0.3f) }
    } else {
        transition.animateFloat(
            initialValue = if (active) 0.55f else 0.22f,
            targetValue = if (active) 1f else 0.38f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = if (active) 2600 else 5200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
    }

    val palette = if (colors.isEmpty()) {
        listOf(Color(0xFF7C8CFF), Color(0xFF9D5CFF), Color(0xFF39D6C8), Color(0xFF7C8CFF))
    } else {
        colors
    }

    // Specular sheen: a light band that sweeps across every ~10s
    val sheenTransition = rememberInfiniteTransition(label = "planSheen")
    val sheenOffset by sheenTransition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sheenOffset",
    )
    val sheenAlpha = if (isPerformanceMode) 0f else 0.08f

    Box(modifier = modifier) {
        // 固定渐变描边：不旋转，只呼吸。
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val stroke = strokeWidth.toPx()
                    val inset = stroke / 2f
                    val radius = cornerRadius.toPx()
                    val sheen = Brush.linearGradient(
                        colors = palette.map { it.copy(alpha = it.alpha * breath) },
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    )
                    drawRoundRect(
                        brush = sheen,
                        topLeft = Offset(inset, inset),
                        size = Size(
                            width = (size.width - stroke).coerceAtLeast(0f),
                            height = (size.height - stroke).coerceAtLeast(0f),
                        ),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = stroke),
                    )
                    // Dynamic specular sheen: a narrow light band sweeping left to right
                    if (sheenAlpha > 0f) {
                        val sheenX = size.width * sheenOffset
                        val sheenWidth = size.width * 0.15f
                        val sheenGradient = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = sheenAlpha),
                                Color.Transparent,
                            ),
                            startX = sheenX - sheenWidth / 2,
                            endX = sheenX + sheenWidth / 2,
                        )
                        drawRoundRect(
                            brush = sheenGradient,
                            topLeft = Offset(inset, inset),
                            size = Size(
                                width = (size.width - stroke).coerceAtLeast(0f),
                                height = (size.height - stroke).coerceAtLeast(0f),
                            ),
                            cornerRadius = CornerRadius(radius, radius),
                            style = Stroke(width = stroke * 2f),
                        )
                    }
                },
        )
        content()
    }
}
