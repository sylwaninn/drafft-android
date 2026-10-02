package so.drafft.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.LocalIsNightSurface
import so.drafft.core.ui.theme.Motion
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class VerticalEdge { TOP, BOTTOM }

/**
 * A progressive blur of this content's own band along [edge]: the band ([band], in pixels) shows a
 * blurred copy of what scrolls there, opaque at the edge and fading out toward the inside, with no
 * colour band. Compose has no backdrop filter, so the content under a bar blurs itself: put it on
 * the scrolling content, under the bar (`EdgeBars` does it for you).
 *
 * With a [veil] (the surface's own tone), the blur replaces the sharp content instead of laying over
 * it, as a backdrop blur does, and a light veil keeps a bar's text readable whatever
 * scrolls under it. The first [solid] pixels from the edge (a bar's own height) are at full
 * strength: only the margin past the bar fades.
 *
 * Android 12+ blurs with a RenderEffect (only the band is blurred, from one recorded display list);
 * older versions ([so.drafft.core.ui.platform.PlatformUi.blursEdges] false) fade the band into a
 * soft scrim of the page colour instead. [alpha] is read at draw time: the blur fades in without
 * recomposing anything. With a [probe], the content also feeds the header's edge-tone sampling.
 */
@Composable
fun Modifier.progressiveBlur(
    edge: VerticalEdge,
    band: () -> Float,
    alpha: () -> Float = { 1f },
    maxRadius: Dp = 14.dp,
    probe: EdgeToneProbe? = null,
    solid: () -> Float = { 0f },
    veil: Color? = null,
): Modifier {
    val blurs = LocalPlatformUi.current.blursEdges
    val page = veil ?: DS.palette.canvasSoft
    return this then ProgressiveBlurElement(edge, band, alpha, maxRadius, blurs, page, probe, solid, veil != null)
}

private data class ProgressiveBlurElement(
    val edge: VerticalEdge,
    val band: () -> Float,
    val alpha: () -> Float,
    val maxRadius: Dp,
    val blurs: Boolean,
    val page: Color,
    val probe: EdgeToneProbe?,
    val solid: () -> Float,
    val veiled: Boolean,
) : ModifierNodeElement<ProgressiveBlurNode>() {
    override fun create() = ProgressiveBlurNode(edge, band, alpha, maxRadius, blurs, page, probe, solid, veiled)

    override fun update(node: ProgressiveBlurNode) {
        node.edge = edge
        node.band = band
        node.alpha = alpha
        node.maxRadius = maxRadius
        node.blurs = blurs
        node.page = page
        node.probe = probe
        node.solid = solid
        node.veiled = veiled
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "progressiveBlur"
    }
}

private class ProgressiveBlurNode(
    var edge: VerticalEdge,
    var band: () -> Float,
    var alpha: () -> Float,
    var maxRadius: Dp,
    var blurs: Boolean,
    var page: Color,
    var probe: EdgeToneProbe?,
    var solid: () -> Float,
    var veiled: Boolean,
) : androidx.compose.ui.Modifier.Node(), DrawModifierNode {
    private var content: GraphicsLayer? = null
    private var blurred: GraphicsLayer? = null
    private var radiusPx = -1f
    private val paint = Paint()

    override fun onDetach() {
        val gc = requireGraphicsContext()
        content?.let(gc::releaseGraphicsLayer)
        blurred?.let(gc::releaseGraphicsLayer)
        content = null
        blurred = null
        radiusPx = -1f
        probe?.let { if (it.layer != null) it.layer = null }
    }

    override fun ContentDrawScope.draw() {
        val a = alpha().coerceIn(0f, 1f)
        val bandPx = band().coerceIn(0f, size.height)
        val shows = a > 0f && bandPx > 0f
        val p = probe
        if (p == null && !(blurs && shows)) {
            drawContent()
            if (shows) drawScrim(bandPx, a)
            return
        }
        val gc = requireGraphicsContext()
        val layer = content ?: gc.createGraphicsLayer().also { content = it }
        layer.record { this@draw.drawContent() }
        val replaces = veiled && blurs && shows
        if (!replaces) drawLayer(layer)
        if (p != null) {
            p.layer = layer
            p.graphics = gc
            p.density = this
            p.page = page
            p.poke()
        }
        if (!shows) return
        if (!blurs) {
            drawScrim(bandPx, a)
            return
        }
        val r = maxRadius.toPx()
        val blur = blurred ?: gc.createGraphicsLayer().also { blurred = it }
        if (r != radiusPx) {
            blur.renderEffect = BlurEffect(r, r, TileMode.Clamp)
            radiusPx = r
        }
        // Only the band (plus a margin the blur reads from) is blurred, never the whole page.
        val top = if (edge == VerticalEdge.TOP) 0f else size.height - bandPx
        val margin = r * 2
        val sourceTop = if (edge == VerticalEdge.TOP) 0f else max(0f, top - margin)
        val sourceBottom = if (edge == VerticalEdge.TOP) min(size.height, bandPx + margin) else size.height
        val sourceHeight = ceil(sourceBottom - sourceTop).toInt().coerceAtLeast(1)
        blur.record(IntSize(ceil(size.width).toInt(), sourceHeight)) {
            translate(0f, -sourceTop) { drawLayer(layer) }
        }
        val rect = Rect(0f, top, size.width, top + bandPx)
        if (veiled) {
            // The blur replaces the sharp content in the band: the sharp copy is erased by the mask,
            // the veiled blur takes its place. At any alpha the two add up to a whole.
            val outside = if (edge == VerticalEdge.TOP) Rect(0f, rect.bottom, size.width, size.height) else Rect(0f, 0f, size.width, rect.top)
            clipRect(outside.left, outside.top, outside.right, outside.bottom) { drawLayer(layer) }
            paint.alpha = 1f
            drawContext.canvas.saveLayer(rect, paint)
            clipRect(rect.left, rect.top, rect.right, rect.bottom) { drawLayer(layer) }
            drawRect(mask(Color.Black.copy(alpha = a), top, bandPx), topLeft = rect.topLeft, size = rect.size, blendMode = BlendMode.DstOut)
            drawContext.canvas.restore()
            drawContext.canvas.saveLayer(rect, paint)
            drawRect(page.copy(alpha = VEIL), topLeft = rect.topLeft, size = rect.size)
            clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                translate(0f, sourceTop) { drawLayer(blur) }
            }
            drawRect(mask(Color.Black.copy(alpha = a), top, bandPx), topLeft = rect.topLeft, size = rect.size, blendMode = BlendMode.DstIn)
            drawContext.canvas.restore()
            return
        }
        paint.alpha = a
        drawContext.canvas.saveLayer(rect, paint)
        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
            translate(0f, sourceTop) { drawLayer(blur) }
        }
        // Opaque where the blur is strongest, transparent where it's gone, eased in between.
        drawRect(mask(Color.Black, top, bandPx), topLeft = rect.topLeft, size = rect.size, blendMode = BlendMode.DstIn)
        drawContext.canvas.restore()
    }

    /** Below Android 12: the band fades into the page colour, the same ramp as the blur's mask. */
    private fun ContentDrawScope.drawScrim(bandPx: Float, a: Float) {
        val top = if (edge == VerticalEdge.TOP) 0f else size.height - bandPx
        drawRect(
            mask(page.copy(alpha = 0.96f * a), top, bandPx),
            topLeft = Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, bandPx),
        )
    }

    private fun mask(color: Color, top: Float, height: Float): Brush {
        // Full strength over the bar itself, then the eased ramp over the margin past it.
        val hold = if (height > 0f) (solid() / height).coerceIn(0f, 0.95f) else 0f
        fun at(t: Float) = hold + (1f - hold) * t
        val ramp = arrayOf(
            0f to color,
            hold to color,
            at(0.3f) to color.copy(alpha = color.alpha * 0.85f),
            at(0.6f) to color.copy(alpha = color.alpha * 0.4f),
            at(0.85f) to color.copy(alpha = color.alpha * 0.1f),
            1f to color.copy(alpha = 0f),
        )
        return if (edge == VerticalEdge.TOP) {
            Brush.verticalGradient(*ramp, startY = top, endY = top + height)
        } else {
            Brush.verticalGradient(*ramp, startY = top + height, endY = top)
        }
    }
}

/** How much of the surface tone tints the blurred band, so a bar's text never sits on a busy smudge. */
private const val VEIL = 0.4f

// MARK: - The only ways to pin something to an edge

/**
 * Pins [topBar] and/or [bottomBar] over scrolling [content], which passes under them through a
 * [progressiveBlur]. Never a background colour. [content] gets the bars' heights as padding: give it
 * to the scroll view's content padding, so the content starts clear of the bars and scrolls under
 * them.
 *
 * The blur only shows when content actually sits under a bar (read from [scroll]): at the top of a
 * page, or on a page shorter than the screen, nothing is blurred. It fades in over 0.2 s. The bands
 * reach [DS.Space.xl] past the bars and cover the system bars.
 *
 * A top bar reads the tone under it ([LocalEdgeTone], for `TabTitle`) when [sampleTone] is on.
 * [navigationEdge]: a screen with a navigation bar (chat, sheets); without a [topBar] the blur covers
 * the status bar only.
 */
@Composable
fun EdgeBars(
    scroll: ScrollableState?,
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    navigationEdge: Boolean = false,
    sampleTone: Boolean = topBar != null && !navigationEdge,
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (PaddingValues) -> Unit,
) {
    val topCovered by remember(scroll) { derivedStateOf { scroll?.canScrollBackward == true } }
    val bottomCovered by remember(scroll) { derivedStateOf { scroll?.canScrollForward == true } }
    val fade = tween<Float>(200, easing = Motion.EaseOut)
    val topAlpha by animateFloatAsState(if (topCovered) 1f else 0f, fade, label = "topBlur")
    val bottomAlpha by animateFloatAsState(if (bottomCovered) 1f else 0f, fade, label = "bottomBlur")
    val bands = remember { Bands() }
    // The tone behind the bars: the night surface's, or the page's.
    val veil = if (LocalIsNightSurface.current) DS.palette.night else DS.palette.canvasSoft
    val probe = if (sampleTone) remember { EdgeToneProbe() } else null
    val platform = LocalPlatformUi.current
    if (probe != null) LaunchedEffect(probe, platform) { probe.run(platform) }
    val hasTop = topBar != null || navigationEdge
    val hasBottom = bottomBar != null
    val tone = if (topCovered && probe != null) probe.tone else null

    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val extra = DS.Space.xl.toPx()
        val statusTop = windowInsets.getTop(this)
        val top = topBar?.let { bar ->
            subcompose(Slot.TOP) {
                Box(Modifier.windowInsetsPadding(windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    CompositionLocalProvider(LocalEdgeTone provides tone) { bar() }
                }
            }.map { it.measure(loose) }
        }.orEmpty()
        val bottom = bottomBar?.let { bar ->
            subcompose(Slot.BOTTOM) {
                Box(Modifier.windowInsetsPadding(windowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) { bar() }
            }.map { it.measure(loose) }
        }.orEmpty()
        val topHeight = if (topBar != null) top.maxOfOrNull { it.height } ?: 0 else if (navigationEdge) statusTop else 0
        val bottomHeight = bottom.maxOfOrNull { it.height } ?: 0
        bands.top = if (hasTop) topHeight + extra else 0f
        bands.bottom = if (hasBottom) bottomHeight + extra else 0f
        bands.topSolid = topHeight.toFloat()
        bands.bottomSolid = bottomHeight.toFloat()
        probe?.bandTop = statusTop.toFloat()
        val padding = PaddingValues(top = topHeight.toDp(), bottom = bottomHeight.toDp())
        val body = subcompose(Slot.CONTENT) {
            var m: Modifier = Modifier
            if (hasTop || probe != null) m = m.progressiveBlur(VerticalEdge.TOP, { bands.top }, { if (hasTop) topAlpha else 0f }, probe = probe, solid = { bands.topSolid }, veil = veil)
            if (hasBottom) m = m.progressiveBlur(VerticalEdge.BOTTOM, { bands.bottom }, { bottomAlpha }, solid = { bands.bottomSolid }, veil = veil)
            Box(m) { content(padding) }
        }.map { it.measure(constraints) }
        val width = body.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = body.maxOfOrNull { it.height } ?: constraints.minHeight
        layout(width, height) {
            body.forEach { it.place(0, 0) }
            top.forEach { it.place(0, 0) }
            bottom.forEach { it.place(0, height - it.height) }
        }
    }
}

private enum class Slot { TOP, BOTTOM, CONTENT }

private class Bands {
    var top by mutableFloatStateOf(0f)
    var bottom by mutableFloatStateOf(0f)
    var topSolid by mutableFloatStateOf(0f)
    var bottomSolid by mutableFloatStateOf(0f)
}

/**
 * `.bottomBar { }`: pins [bar] to the bottom (validate buttons, the chat composer, above the
 * keyboard). Scroll content passes under it through a progressive blur. Never a background colour.
 */
@Composable
fun BottomBar(
    scroll: ScrollableState?,
    bar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (PaddingValues) -> Unit,
) = EdgeBars(scroll, modifier, bottomBar = bar, windowInsets = windowInsets, content = content)

/** `.topBar { }`: pins a custom header (`TabHeader`, step headers) to the top, with the same blur under it. */
@Composable
fun TopBar(
    scroll: ScrollableState?,
    bar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (PaddingValues) -> Unit,
) = EdgeBars(scroll, modifier, topBar = bar, windowInsets = windowInsets, content = content)

/**
 * `.blurredNavigationEdge()`: for screens with a navigation bar ([navigationBar], e.g.
 * `SheetNavBar`), the same blur under the status bar and the whole bar (title, avatar, buttons),
 * fading out just below it. Without a bar, only the status bar is covered (You has no title).
 */
@Composable
fun BlurredNavigationEdge(
    scroll: ScrollableState?,
    modifier: Modifier = Modifier,
    navigationBar: (@Composable () -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable (PaddingValues) -> Unit,
) = EdgeBars(scroll, modifier, topBar = navigationBar, navigationEdge = true, windowInsets = windowInsets, content = content)
