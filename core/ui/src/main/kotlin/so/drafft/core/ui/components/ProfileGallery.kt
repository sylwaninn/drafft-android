package so.drafft.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import so.drafft.core.data.media.Images

/**
 * An open profile's gallery (`ProfileDetailView`): [height] high, full width. Its [size] in pixels is the
 * gallery's own once it has been measured, Discover's width until then. In Discover, a profile's first photo
 * is fetched for it ([warm]) on the tap that opens it, or after a long look at its card: never ahead of every
 * card, most are never opened.
 */
object ProfileGallery {
    val height = 440.dp

    private var measured by mutableStateOf(IntSize.Zero)
    private var guessed by mutableStateOf(IntSize.Zero)

    /** The gallery's size in pixels, zero while unknown. Snapshot state: an effect keyed on it waits for it. */
    val size: IntSize get() = if (measured != IntSize.Zero) measured else guessed

    /** The gallery's own size, which a guess never overrides. */
    fun measured(size: IntSize) {
        if (size.width > 0 && size.height > 0) measured = size
    }

    /** Discover's width and [height], until the gallery has been measured. */
    fun guess(width: Int, density: Density) {
        if (width > 0) guessed = IntSize(width, with(density) { height.roundToPx() })
    }

    /**
     * Fetches to disk the gallery copy of a profile's first photo ([ImageStore.fetch]): calls for the same
     * photo, and the gallery's own request, share one download, which a fetch never lowers.
     */
    suspend fun warm(context: PlatformContext, name: String, priority: Images.Priority) {
        val frame = size
        if (frame.width <= 0 || frame.height <= 0) return
        ImageStore.fetch(context, name, frame.width, frame.height, priority, detail = true)
    }
}
