package online.myesstu.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

/** Fades a block in and lets it rise a little. [order] staggers a list; it plays again when [key] changes. */
@Composable
fun Modifier.appear(order: Int, key: Any = Unit): Modifier {
    var shown by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        delay(min(order, 8) * 45L)
        shown = true
    }
    val progress by animateFloatAsState(if (shown) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "appear")
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 18.dp.toPx()
    }
}

/** The dot that marks a lesson in progress, with a ring that spreads out and fades. */
@Composable
fun LiveDot(color: Color, size: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "live")
    val spread by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "spread",
    )
    Canvas(Modifier.size(size * 2.4f)) {
        val core = this.size.minDimension / 2.4f / 2f
        drawCircle(color.copy(alpha = 0.45f * (1f - spread)), radius = core * (1f + 1.4f * spread))
        drawCircle(color, radius = core)
    }
}

