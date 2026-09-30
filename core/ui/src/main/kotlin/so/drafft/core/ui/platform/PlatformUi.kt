package so.drafft.core.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import so.drafft.core.ui.theme.DS

/**
 * The composable pieces that need the Android SDK or an Android-only library (Media3, windows,
 * RenderEffect), behind one interface so shared code stays platform-neutral. The Android
 * implementation (`AndroidPlatformUi`, src/android) is provided at the root through [LocalPlatformUi].
 * Screens call the top-level wrappers ([VideoPlayer]...), not the members.
 *
 * Later additions (camera preview, pickers, permission prompts, native share) join here the same way.
 */
@Stable
interface PlatformUi {
    /**
     * Whether pinned edges blur the content scrolling under them (Android 12+, RenderEffect), or
     * fall back to a soft scrim of the page colour. Read by `Modifier.progressiveBlur`.
     */
    val blursEdges: Boolean

    /** A video filling [modifier]'s frame (cropped like `scaledToFill`), no controls. */
    @Composable
    fun VideoPlayer(url: String, modifier: Modifier, autoplay: Boolean, muted: Boolean, loop: Boolean, contentScale: ContentScale)

    /**
     * A window over everything (sheets included), full screen and edge to edge, without a dim or a
     * window animation: `FullScreenCover` animates its content itself. System back calls
     * [onDismissRequest].
     */
    @Composable
    fun FullScreenWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit)

    /**
     * The ARGB pixels of a small rendered image (the edge-tone probe's 32 × 8 snapshot), or null.
     * On Android a layer snapshot is a hardware bitmap, which has to be copied before it's read.
     */
    fun pixels(image: ImageBitmap): IntArray?
}

/** Harmless stand-in (previews, the JVM check): no blur, a night frame for video, covers drawn in place. */
object DefaultPlatformUi : PlatformUi {
    override val blursEdges: Boolean = false

    @Composable
    override fun VideoPlayer(url: String, modifier: Modifier, autoplay: Boolean, muted: Boolean, loop: Boolean, contentScale: ContentScale) {
        Box(modifier.background(DS.palette.night))
    }

    @Composable
    override fun FullScreenWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) { content() }
    }

    override fun pixels(image: ImageBitmap): IntArray? =
        IntArray(image.width * image.height).also { image.readPixels(it) }
}

/** The platform's pieces, provided at the root (`AndroidPlatformUi` on Android). */
val LocalPlatformUi = staticCompositionLocalOf<PlatformUi> { DefaultPlatformUi }

/** A video that fills its frame, no controls: muted and looping by default, like the profile videos. */
@Composable
fun VideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoplay: Boolean = true,
    muted: Boolean = true,
    loop: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
) {
    LocalPlatformUi.current.VideoPlayer(url, modifier, autoplay, muted, loop, contentScale)
}
