package so.drafft.core.ui.components

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import so.drafft.core.data.media.Images

/**
 * An open profile's gallery (`ProfileDetailView`): [height] high, the sheet's width. Its size in pixels is
 * measured there once it's open ([size]), guessed from the window until then ([guess]). A profile's first
 * photo is fetched for it ([warm]) on the tap that opens it, or after a long look at its card: never ahead
 * of every card, most are never opened.
 */
object ProfileGallery {
    val height = 440.dp

    @Volatile
    var size: IntSize = IntSize.Zero

    /** The window's width and [height], until the gallery has been measured. */
    fun guess(windowWidth: Int, density: Density) {
        if (size == IntSize.Zero && windowWidth > 0) size = IntSize(windowWidth, with(density) { height.roundToPx() })
    }

    /**
     * Fetches to disk the gallery copy of a profile's first photo ([ImageStore.fetch]): the tap's joins and
     * raises a long look's, and the gallery's own request joins either. Cancelling the caller stops it.
     */
    suspend fun warm(context: PlatformContext, name: String, priority: Images.Priority) {
        val frame = size
        if (frame.width <= 0 || frame.height <= 0) return
        ImageStore.fetch(context, name, frame.width, frame.height, priority, detail = true)
    }
}
