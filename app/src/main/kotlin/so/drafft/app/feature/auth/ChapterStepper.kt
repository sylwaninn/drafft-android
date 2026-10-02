package so.drafft.app.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import so.drafft.core.model.L
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold

/** One chapter of sign-up in the stepper: its name and how many steps it holds. */
@Immutable
data class ChapterSteps(val title: String, val steps: Int)

/**
 * Sign-up stepper, between Back and Skip in the header. Only the current chapter is open: its bar
 * takes the free width, fills step by step, and names the chapter under it. The others fold to short
 * ticks (solid when done, faint when ahead). Moving to the next chapter is one move: the finished bar
 * fills and folds while the next one opens, and the name blurs across.
 *
 * [current]: index of the current step across the whole flow.
 */
@Composable
fun ChapterStepper(chapters: List<ChapterSteps>, current: Int, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val p = DS.palette
    val openness = ArrayList<State<Float>>(chapters.size)
    val fills = ArrayList<State<Float>>(chapters.size)
    val actives = ArrayList<Boolean>(chapters.size)
    var start = 0
    chapters.forEachIndexed { i, chapter ->
        val done = current >= start + chapter.steps
        val active = !done && current >= start
        val fill = when {
            done -> 1f
            active -> (current - start + 1).toFloat() / maxOf(1, chapter.steps)
            else -> 0f
        }
        actives += active
        openness += animateFloatAsState(if (active) 1f else 0f, if (reduceMotion) Motion.gentle() else Motion.progress(), label = "chapterOpen$i")
        fills += animateFloatAsState(fill, if (reduceMotion) Motion.gentle() else Motion.progress(), label = "chapterFill$i")
        start += chapter.steps
    }
    val track = p.ink.copy(alpha = 0.12f)
    val ink = p.ink
    val label = accessibilityText(chapters, current)

    Layout(
        content = {
            chapters.forEachIndexed { i, chapter ->
                Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ChapterStepperBarHeight)
                            .drawBehind {
                                val r = CornerRadius(size.height / 2)
                                drawRoundRect(track, cornerRadius = r)
                                val w = size.width * fills[i].value.coerceIn(0f, 1f)
                                if (w > 0f) drawRoundRect(ink, size = Size(w, size.height), cornerRadius = r)
                            },
                    )
                    AnimatedVisibility(
                        visible = actives[i],
                        // The label swaps with a fade and a slight scale (no cheap blur transition in Compose).
                        enter = if (reduceMotion) fadeIn(Motion.gentle()) else fadeIn(Motion.progress()) + scaleIn(Motion.progress(), initialScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)),
                        exit = if (reduceMotion) fadeOut(Motion.gentle()) else fadeOut(Motion.progress()) + scaleOut(Motion.progress(), targetScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)),
                    ) {
                        Text(
                            chapter.title,
                            style = TextStyles.caption.bold,
                            color = ink,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        },
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
    ) { measurables, constraints ->
        // Open chapter takes the room left by the folded ones; the widths move with the openness.
        val tick = TICK.roundToPx()
        val gap = DS.Space.xs.roundToPx()
        val n = measurables.size
        val total = if (constraints.hasBoundedWidth) constraints.maxWidth else (n * tick + (n - 1) * gap + 200)
        val free = (total - n * tick - (n - 1).coerceAtLeast(0) * gap).coerceAtLeast(0)
        val placeables = measurables.mapIndexed { i, m ->
            val w = (tick + free * openness[i].value.coerceIn(0f, 1f)).toInt().coerceAtLeast(0)
            m.measure(Constraints.fixedWidth(w))
        }
        // Reserve the name's line so the row never changes height as chapters open and fold.
        val minHeight = (ChapterStepperBarHeight + DS.Space.xs + 2.dp + 16.dp).roundToPx()
        val height = maxOf(minHeight, placeables.maxOfOrNull { it.height } ?: 0)
        layout(total, height) {
            var x = 0
            placeables.forEach {
                it.place(x, 0)
                x += it.width + gap
            }
        }
    }
}

/** A folded chapter: long enough to count, short enough to leave the room to the open one. */
private val TICK = 18.dp

/** The bar's height; the header levels it (not the name under it) with the back chevron. */
val ChapterStepperBarHeight = 5.dp

private fun accessibilityText(chapters: List<ChapterSteps>, current: Int): String {
    var start = 0
    chapters.forEachIndexed { i, c ->
        if (current < start + c.steps) {
            return L("%s, step %d of %d. Part %d of %d.", c.title, current - start + 1, c.steps, i + 1, chapters.size)
        }
        start += c.steps
    }
    return L("Sign-up complete")
}
