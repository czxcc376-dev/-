package me.rerere.rikkahub.ui.pages.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import me.rerere.rikkahub.R

/**
 * ClBI 启动动画。
 *
 * 特色：
 * 1. 深青底 + 呼吸的同心光环（呼应图标里的「眼睛/镜头」母题，不旋转、只脉动）；
 * 2. 角色立绘从中心柔和放大浮现（二次元登场感）；
 * 3. 品牌名 ClBI 逐字浮现，末尾一条渐变扫光划过；
 * 4. 整体约 1.4s，结束后淡出，不阻塞启动。
 *
 * 全程 Compose 绘制，无外部依赖，低端机也不掉帧。
 */
@Composable
fun SplashOverlay(
    onFinished: () -> Unit,
) {
    val bgTop = Color(0xFF123B36)
    val bgBottom = Color(0xFF081B19)
    val accent = Color(0xFF7FE3C9)

    val reveal = remember { Animatable(0f) }
    val fadeOut = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(durationMillis = 620, easing = LinearOutSlowInEasing))
        delay(260)
        fadeOut.animateTo(0f, tween(durationMillis = 320, easing = FastOutSlowInEasing))
        onFinished()
    }

    // 点击任意位置可立即跳过启动动画，不让用户等。
    val skip = remember { mutableStateOf(false) }
    LaunchedEffect(skip.value) {
        if (skip.value) {
            fadeOut.animateTo(0f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
            onFinished()
        }
    }

    val infinite = rememberInfiniteTransition(label = "splashRings")
    val ring by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
        ),
        label = "ring",
    )
    val logoBreath by infinite.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "breath",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(fadeOut.value)
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
            .pointerInput(Unit) { detectTapGestures { skip.value = true } },
        contentAlignment = Alignment.Center,
    ) {
        // 同心光环：外扩 + 淡出，脉动节奏
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension * 0.22f
            repeat(3) { i ->
                val p = ((ring + i / 3f) % 1f)
                val radius = base + p * size.minDimension * 0.42f
                val a = (1f - p) * 0.35f * reveal.value
                drawCircle(
                    color = accent.copy(alpha = a),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.6.dp.toPx()),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 角色立绘浮现
            Image(
                painter = painterResource(R.drawable.splash_logo),
                contentDescription = null,
                // 用 PNG 直读，避免经过通用图片加载器带来首帧延迟。
                modifier = Modifier
                    .size(168.dp)
                    .clip(CircleShape)
                    .scale(0.82f + 0.18f * reveal.value)
                    .alpha(reveal.value),
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 品牌名：逐字浮现
            BrandWordmark(
                text = stringResource(R.string.app_name),
                accent = accent,
                progress = reveal.value,
            )
        }
    }
}

@Composable
private fun BrandWordmark(
    text: String,
    accent: Color,
    progress: Float,
) {
    // 每个字母的透明度按进度错开出现，形成「逐字浮现」。
    val letters = text.toCharArray()

    // 用 Canvas 在文字下方画一条渐变扫光
    Box(contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            letters.forEachIndexed { index, ch ->
                val start = index * 0.18f
                val local = ((progress - start) / 0.5f).coerceIn(0f, 1f)
                Text(
                    text = ch.toString(),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = local),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alpha(local),
                )
            }
        }
        Canvas(
            modifier = Modifier
                .size(width = 140.dp, height = 40.dp)
                .alpha(((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)),
        ) {
            val y = size.height * 0.86f
            val sweep = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, accent, Color.Transparent),
                    startX = -size.width * 0.5f + sweep * size.width * 2f,
                    endX = size.width * 0.5f + sweep * size.width * 2f,
                ),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}
