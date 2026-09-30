package so.drafft.core.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import so.drafft.core.ui.platform.PlatformUi

// Port of Drafft/DesignSystem/EdgeTone.swift.

/**
 * Whether what scrolls under a top bar is light or dark. The status bar flips its time and icons
 * the same way: dark ink over a light page or white block, white over a night block or a photo.
 * `TopBar` samples it and hands it to the bar through [LocalEdgeTone].
 */
enum class EdgeTone {
    LIGHT, DARK;

    /**
     * Header text over this tone. Fixed values, not `ink`: in dark mode the page is dark too, so
     * the rule is the same, light text over dark content, dark text over light content.
     */
    val ink: Color get() = if (this == DARK) Color.White else Color(red = 0.055f, green = 0.059f, blue = 0.047f)
}

/** What sits under the top bar right now; null at rest (nothing under it: the page's own ink). */
val LocalEdgeTone = compositionLocalOf<EdgeTone?> { null }

/**
 * Reads what is on screen under the header, the way the status bar does: a tiny snapshot (32 × 8)
 * of the content's band just below the status bar, averaged. The content pokes it each time it
 * draws (scrolling, animations); a snapshot is taken at most a dozen times a second, and hysteresis
 * keeps a mid-grey from flickering. Only [tone] is state, and it changes only when the tone flips.
 */
@Stable
class EdgeToneProbe {
    var tone by mutableStateOf(EdgeTone.LIGHT)
        private set

    // Set by the content's draw pass: plain references, no state.
    internal var layer: GraphicsLayer? = null
    internal var graphics: GraphicsContext? = null
    internal var density: Density = Density(1f)

    /** Where the header's text band starts in the content (the status bar's height), in pixels. */
    internal var bandTop = 0f

    /** Painted under the content in the snapshot, where the page shows through. */
    internal var page: Color = Color.White

    private val pokes = Channel<Unit>(Channel.CONFLATED)

    internal fun poke() {
        pokes.trySend(Unit)
    }

    /** Runs for as long as the page is shown (a `LaunchedEffect`). */
    suspend fun run(platform: PlatformUi) {
        for (unused in pokes) {
            delay(80)
            runCatching { sample(platform) }
        }
    }

    private suspend fun sample(platform: PlatformUi) {
        val source = layer ?: return
        val gc = graphics ?: return
        val width = source.size.width
        if (width <= 0) return
        val bandWidth = width * 0.7f
        val bandHeight = with(density) { 56.dp.toPx() }
        val top = bandTop
        val small = gc.createGraphicsLayer()
        try {
            small.record(density, LayoutDirection.Ltr, IntSize(32, 8)) {
                drawRect(page)
                scale(32f / bandWidth, 8f / bandHeight, pivot = Offset.Zero) {
                    translate(0f, -top) { drawLayer(source) }
                }
            }
            val pixels = platform.pixels(small.toImageBitmap()) ?: return
            var total = 0.0
            for (argb in pixels) {
                total += (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)) / 3.0
            }
            val luminance = total / pixels.size / 255
            val next = when {
                luminance < 0.42 -> EdgeTone.DARK
                luminance > 0.55 -> EdgeTone.LIGHT
                else -> tone
            }
            if (next != tone) tone = next
        } finally {
            gc.releaseGraphicsLayer(small)
        }
    }
}
