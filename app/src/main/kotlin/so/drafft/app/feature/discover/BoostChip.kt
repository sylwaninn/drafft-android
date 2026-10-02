package so.drafft.app.feature.discover

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import so.drafft.core.data.AppModel
import so.drafft.core.model.L
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.monospacedDigits
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/**
 * Header chip for boosts. Idle: the bolt and how many you have. Running: one accent capsule with a
 * ring that drains around the bolt and a live mm:ss clock. A single surface either way, so it
 * never reads as a label sitting inside a button.
 */
@Composable
fun BoostChip(
    boosts: Int,
    endsAt: Instant?,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberSecondTicker(endsAt)
    val left = maxOf(0.0, endsAt?.let { Duration.between(now, it).toMillis() / 1000.0 } ?: 0.0)
    val running = left > 0
    val p = DS.palette
    val ink by animateColorAsState(if (running) p.onLime else p.ink, Motion.snappy(), label = "boostInk")
    val fill by animateColorAsState(if (running) p.lime else p.canvas, Motion.snappy(), label = "boostFill")
    val ring by animateFloatAsState(
        (left / AppModel.BOOST_DURATION_SECONDS).toFloat().coerceIn(0f, 1f),
        tween(1000, easing = LinearEasing),
        label = "boostRing",
    )
    val description = if (running) {
        L("Boost running, %d minutes %d seconds left", left.toInt() / 60, left.toInt() % 60)
    } else if (boosts == 1) {
        L("1 boost")
    } else {
        L("%d boosts", boosts)
    }
    PressScaleButton(
        onClick = action,
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
        scale = 0.94f,
        contentDescription = description,
    ) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 36.dp)
                .background(fill, CircleShape)
                .padding(start = DS.Space.sm, end = DS.Space.md),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (running) {
                    val track = p.onLime.copy(alpha = 0.2f)
                    val arc = p.onLime
                    Canvas(Modifier.fillMaxSize()) {
                        val w = 2.dp.toPx()
                        drawCircle(track, radius = (size.minDimension - w) / 2, style = Stroke(w))
                        drawArc(
                            arc, startAngle = -90f, sweepAngle = 360f * ring, useCenter = false,
                            topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                            style = Stroke(w, cap = StrokeCap.Round),
                        )
                    }
                }
                DrafftIcon("bolt", size = ((if (running) 9f else 13f) * 1.2f).dp, tint = ink)
            }
            RollingText(
                if (running) clock(left) else "$boosts",
                style = TextStyles.subheadline.heavy.monospacedDigits,
                color = ink,
                countsDown = running,
                maxLines = 1,
            )
        }
    }
}

/** 29:59, then 9:59: minutes without padding, seconds always two digits. */
internal fun clock(seconds: Double): String {
    val s = ceil(seconds).toInt()
    return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/**
 * The time now, refreshed every second while [until] lies ahead: one state write a second, only while
 * something counts down.
 */
@Composable
internal fun rememberSecondTicker(until: Instant?): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(until) {
        now = Instant.now()
        while (until != null && now.isBefore(until)) {
            delay(1000 - (System.currentTimeMillis() % 1000))
            now = Instant.now()
        }
    }
    return now
}
