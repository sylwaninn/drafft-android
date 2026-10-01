package so.drafft.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.drafft.core.data.platform.Haptics
import so.drafft.core.ui.theme.Palette
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.Symbols
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeSource

// Port of Drafft/DesignSystem/EmptyStateSticker.swift.

private val StickerSide = 116.dp
private const val TILT_DEGREES = -6f
/** How far the fold sits inside the sticker's edge (dp), at rest and when the arrival starts. */
private const val REST_PEEL = 11f
private const val ARRIVAL_PEEL = 46f
/** The farthest the finger can move the corner (dp, rubber-banded on the way). */
private const val MAX_PULL = 24f
private const val EDGE = 7f

/**
 * An empty tab's sign as a sticker: the tab's icon filled (its Solar bold twin) on a white die-cut
 * edge, slightly tilted, its top-right corner never quite stuck. Arriving on the screen after a
 * while shows the last of it being pressed down; switching tabs back and forth doesn't replay it.
 * The loose corner follows the finger a little and springs back.
 */
@Composable
fun EmptyStateSticker(art: EmptyStateArt, modifier: Modifier = Modifier) {
    val key = art.symbol
    // Every tab stays composed: on screen is the current tab, with the tabs themselves showing.
    val onScreen = LocalTabIsCurrent.current && LocalTabsOnScreen.current
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val glyph = rememberVectorPainter(Symbols.vector("${art.symbol}-bold"))
    val sheet = remember(key, density) { StickerSheet.make(glyph, StickerSide.value, density) }

    // 0: still half peeled (the pose the arrival starts from), 1: pressed down, corner at rest.
    val stuck = remember { Animatable(if (StickerVisits.isDue(key)) 0f else 1f) }
    // Decided as the sticker comes on screen, so the first frame is already the starting pose.
    val arriving = remember(onScreen) { onScreen && StickerVisits.isDue(key) && !reduceMotion }
    var started by remember(onScreen) { mutableStateOf(false) }
    val pull = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(onScreen) {
        if (!onScreen) return@LaunchedEffect
        if (!arriving) { stuck.snapTo(1f); return@LaunchedEffect }
        stuck.snapTo(0f)
        started = true
        StickerVisits.leave(key)
        delay(50)
        stuck.animateTo(1f, Motion.springOf(0.42, 0.74f))
    }
    DisposableEffect(onScreen) {
        onDispose { if (onScreen) StickerVisits.leave(key) }
    }

    val progress = if (arriving && !started) 0f else stuck.value
    Box(
        modifier
            .size(StickerSide + 12.dp)
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                val release = {
                    scope.launch { pull.animateTo(Offset.Zero, Motion.bouncy()) }
                }
                detectDragGestures(
                    onDragStart = { Haptics.select() },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, amount ->
                    change.consume()
                    scope.launch { pull.snapTo(pull.value + amount) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(StickerSide)
                .graphicsLayer {
                    val scale = 1f + 0.06f * (1f - progress)
                    scaleX = scale
                    scaleY = scale
                    rotationZ = TILT_DEGREES
                },
        ) {
            val corner = corner(sheet.reach.dp.toPx(), progress, pull.value)
            drawPeeled(sheet, corner)
        }
    }
}

/**
 * Where the loose corner sits, from the top-right corner of the sticker: on the diagonal, as deep as
 * the arrival or the rest wants, then wherever the finger takes it.
 */
private fun DrawScope.corner(reach: Float, stuck: Float, pull: Offset): Offset {
    val inward = Offset(-1 / sqrt(2f), 1 / sqrt(2f))
    val depth = (ARRIVAL_PEEL + (REST_PEEL - ARRIVAL_PEEL) * stuck).dp.toPx()
    // The fold is halfway between the corner and where the corner lands.
    var v = inward * (2 * (reach + depth))
    val finger = hypot(pull.x, pull.y)
    if (finger > 0f) {
        val maxPull = MAX_PULL.dp.toPx()
        val k = maxPull / (finger + maxPull)
        // The finger moves on screen; the sticker is tilted.
        val a = -TILT_DEGREES * PI.toFloat() / 180f
        val x = pull.x * k
        val y = pull.y * k
        v += Offset(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
    }
    // Pushed back toward its corner, it stays a little loose.
    val along = v.x * inward.x + v.y * inward.y
    val least = 2 * (reach + 3.dp.toPx())
    if (along < least) v += inward * (least - along)
    return v
}

/**
 * The sticker, its corner folded back along the line halfway between the top-right corner and
 * `corner` (paper folding): the front is cut along that line, the part beyond it shows its back,
 * mirrored over the sticker.
 */
private fun DrawScope.drawPeeled(sheet: StickerSheet, corner: Offset) {
    val length = max(hypot(corner.x, corner.y), 0.001f)
    val normal = Offset(corner.x / length, corner.y / length)
    val fold = Offset(size.width + corner.x / 2, corner.y / 2)
    val shade = { alpha: Float -> ColorFilter.tint(Color.Black.copy(alpha = alpha)) }

    clipPath(halfPlane(fold, normal, size)) {
        drawImage(sheet.back, topLeft = Offset(0f, 1.dp.toPx()), colorFilter = shade(0.12f))
        drawImage(sheet.front)
    }
    val mirror = mirror(fold, normal)
    translate(-1.dp.toPx(), 2.dp.toPx()) {
        withTransform({ transform(mirror) }) {
            clipPath(halfPlane(fold, -normal, size)) { drawImage(sheet.back, colorFilter = shade(0.18f)) }
        }
    }
    withTransform({ transform(mirror) }) {
        clipPath(halfPlane(fold, -normal, size)) { drawImage(sheet.back) }
    }
}

/** Everything on the side of the line through [point] that [normal] points to. */
private fun halfPlane(point: Offset, normal: Offset, size: Size): Path {
    val far = 4 * max(max(size.width, size.height), 1f)
    val along = Offset(-normal.y, normal.x)
    fun at(s: Float, t: Float) = point + along * s + normal * t
    return Path().apply {
        val start = at(-far, 0f)
        moveTo(start.x, start.y)
        listOf(at(far, 0f), at(far, far), at(-far, far)).forEach { lineTo(it.x, it.y) }
        close()
    }
}

/** Reflection across the line through [point] perpendicular to [n] (a unit vector). */
private fun mirror(point: Offset, n: Offset): Matrix {
    val offset = 2 * (point.x * n.x + point.y * n.y)
    val a = 1 - 2 * n.x * n.x
    val b = -2 * n.x * n.y
    val d = 1 - 2 * n.y * n.y
    return Matrix(floatArrayOf(a, b, 0f, 0f, b, d, 0f, 0f, 0f, 0f, 1f, 0f, offset * n.x, offset * n.y, 0f, 1f))
}

/** When each sticker was last left, so a quick round of tabs doesn't replay its arrival. */
object StickerVisits {
    /** Away at least this long (seconds) and the sticker is pressed down again on the way back. */
    const val REPLAY_AFTER = 30.0
    private val left = mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()

    fun isDue(key: String): Boolean {
        val mark = left[key] ?: return true
        return mark.elapsedNow().inWholeMilliseconds / 1000.0 > REPLAY_AFTER
    }

    fun leave(key: String) { left[key] = TimeSource.Monotonic.markNow() }
}

/**
 * A sticker's two faces, drawn once per sign: the front (white die-cut edge around the filled sign)
 * and the back (the same outline in grey). [reach] (dp) is how far the outline sits from the square's
 * top-right corner, measured along the diagonal: where a fold starts to catch paper.
 */
private class StickerSheet(val front: ImageBitmap, val back: ImageBitmap, val reach: Float) {
    companion object {
        fun make(glyph: Painter, side: Float, density: Density): StickerSheet {
            val px = (side * density.density).roundToInt()
            val size = Size(px.toFloat(), px.toFloat())
            val edge = EDGE * density.density
            val inset = edge + 3 * density.density
            val box = Size(px - 2 * inset, px - 2 * inset)
            val fit = fitted(glyph.intrinsicSize, box)
            val origin = Offset(inset + (box.width - fit.width) / 2, inset + (box.height - fit.height) / 2)

            fun DrawScope.sign(color: Color, at: Offset) {
                translate(at.x, at.y) { with(glyph) { draw(fit, colorFilter = ColorFilter.tint(color)) } }
            }
            // The die cut: the sign spread out by `edge` in every direction (rings of copies, close
            // enough that thin strokes leave no gap), which also fills its small holes.
            fun DrawScope.outline(color: Color) {
                sign(color, origin)
                for (ring in 1..3) {
                    val r = edge * ring / 3
                    for (step in 0 until 32) {
                        val a = step / 32.0 * 2 * PI
                        sign(color, origin + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()))
                    }
                }
            }
            fun render(block: DrawScope.() -> Unit): ImageBitmap {
                val image = ImageBitmap(px, px)
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), size, block)
                return image
            }
            // Paper: the same tones in light and dark mode.
            val front = render {
                outline(Palette.Light.stickerPaper)
                sign(Palette.Light.stickerInk, origin)
            }
            val back = render { outline(Palette.Light.stickerBack) }
            return StickerSheet(front, back, reach(back, side))
        }

        private fun fitted(size: Size, box: Size): Size {
            if (size.width <= 0f || size.height <= 0f || !size.width.isFinite()) return box
            val k = min(box.width / size.width, box.height / size.height)
            return Size(size.width * k, size.height * k)
        }

        /** The nearest opaque pixel to the top-right corner, along the diagonal, in dp. */
        private fun reach(image: ImageBitmap, side: Float): Float {
            val w = image.width
            val h = image.height
            val pixels = IntArray(w * h)
            image.readPixels(pixels)
            var nearest = Int.MAX_VALUE
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if ((pixels[y * w + x] ushr 24) > 128) nearest = min(nearest, w - x + y)
                }
            }
            if (nearest == Int.MAX_VALUE) return side * 0.2f
            return nearest / sqrt(2f) * side / w
        }
    }
}
