package so.drafft.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display

/** What a pause puts on hold. Discover always is; everything else works as usual. */
object PauseScope {
    /** Likes is paused with Discover: answering a like creates a match. Flip to keep it open. */
    const val locksLikes = true
}

/**
 * `.pausedLock()`: while the profile is paused, the tab's content is greyed and untouchable, with
 * one white block over it saying why and a way to resume. Only discovery is locked (see
 * [PauseScope]): chats, sessions, reports and the profile keep working.
 */
@Composable
fun PausedLock(
    modifier: Modifier = Modifier,
    locked: Boolean = true,
    content: @Composable () -> Unit,
) {
    val app = LocalAppModel.current
    val paused = locked && app.profilePaused
    val progress by animateFloatAsState(if (paused) 1f else 0f, Motion.snappy(), label = "pausedLock")
    val paint = remember { Paint() }
    Box(modifier) {
        Box(
            Modifier
                .then(if (paused) Modifier.clearAndSetSemantics { } else Modifier)
                .drawWithContent {
                    val t = progress
                    if (t <= 0f) {
                        drawContent()
                    } else {
                        paint.colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - t) })
                        paint.alpha = 1f - 0.6f * t
                        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                        drawContent()
                        drawContext.canvas.restore()
                    }
                },
        ) { content() }
        if (paused) {
            // Untouchable while paused: every touch on the content stops here.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    },
            )
        }
        AnimatedVisibility(
            visible = paused,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = DS.Space.lg),
            enter = scaleIn(Motion.snappy(), initialScale = 0.96f) + fadeIn(Motion.snappy()),
            exit = scaleOut(Motion.snappy(), targetScale = 0.96f) + fadeOut(Motion.snappy()),
        ) {
            PausedNotice(onResume = {
                Haptics.success()
                app.profilePaused = false
            })
        }
    }
}

@Composable
private fun PausedNotice(onResume: () -> Unit) {
    val p = DS.palette
    val shape = RoundedCornerShape(DS.Radius.xl)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
            .background(p.canvas, shape)
            .padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).background(p.lime, CircleShape), contentAlignment = Alignment.Center) {
            DrafftIcon("pause", size = symbolBox(20f), tint = p.onLime)
        }
        Text(
            L("Your profile is paused"),
            Modifier.semantics { heading() },
            style = display(22f),
            color = p.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            L("You're hidden from Discover, and discovery waits until you resume. Your chats and sessions carry on."),
            style = TextStyles.body,
            color = p.body,
            textAlign = TextAlign.Center,
        )
        DrafftButton(L("Resume my profile"), onClick = onResume, fullWidth = false)
    }
}
