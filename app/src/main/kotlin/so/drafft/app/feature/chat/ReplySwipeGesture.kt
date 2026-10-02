package so.drafft.app.feature.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.abs
import kotlin.math.max

/**
 * Swipe right on a message to reply. A drag that only begins on a clearly rightward, horizontal
 * movement: any vertical drag gives up at once and the thread scrolls as usual (the scroll view only
 * sees the events this gesture leaves unconsumed). [onChanged] gets the horizontal distance from the
 * start, in dp; [onEnded] the release (or the system cancelling the touch).
 */
fun Modifier.replySwipeGesture(onChanged: (Float) -> Unit, onEnded: () -> Unit): Modifier = composed {
    val changed by rememberUpdatedState(onChanged)
    val ended by rememberUpdatedState(onEnded)
    val slop = LocalViewConfiguration.current.touchSlop
    // Where the row sits, read only when a touch lands (a plain reference, never state).
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    this
        .onPlaced { placed[0] = it }
        .pointerInput(Unit) {
            val edge = 28f * density
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                // From the screen's left edge, it's the system's swipe back, not a reply.
                val windowX = placed[0]?.takeIf { it.isAttached }?.localToWindow(down.position)?.x ?: down.position.x
                if (windowX < edge) return@awaitEachGesture
                var dx = 0f
                var dy = 0f
                // Decide once past the touch slop: rightward and at least 1.5 times more sideways than
                // vertical. Anything else is left to the scroll view.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    val delta = change.positionChange()
                    dx += delta.x
                    dy += delta.y
                    if (abs(dx) > slop || abs(dy) > slop) {
                        if (dx > 0 && abs(dx) > abs(dy) * 1.5f) {
                            change.consume()
                            break
                        }
                        return@awaitEachGesture
                    }
                }
                try {
                    changed(max(0f, dx) / density)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dx += change.positionChange().x
                        change.consume()
                        changed(max(0f, dx) / density)
                    }
                } finally {
                    ended()
                }
            }
        }
}
