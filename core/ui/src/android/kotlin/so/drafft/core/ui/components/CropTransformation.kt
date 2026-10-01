package so.drafft.core.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt

// The crop of Drafft/Services/Media/Images.swift (`.resize(size:unit:contentMode:crop:)`).

/**
 * Crops a decoded copy to the frame it fills ([width] × [height] pixels, centred): decoded aspect fill,
 * it still holds the edges the frame hides, and memory should keep only what the screen shows. A copy
 * smaller than the frame is cropped to its proportions, never scaled up (drawing scales it).
 */
internal class CropTransformation(private val width: Int, private val height: Int) : Transformation() {
    override val cacheKey: String = "drafft.crop-${width}x$height"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (width <= 0 || height <= 0 || input.width <= 0 || input.height <= 0) return input
        val scale = maxOf(width.toFloat() / input.width, height.toFloat() / input.height)
        val sourceWidth = (width / scale).roundToInt().coerceIn(1, input.width)
        val sourceHeight = (height / scale).roundToInt().coerceIn(1, input.height)
        val outWidth = if (scale < 1f) width else sourceWidth
        val outHeight = if (scale < 1f) height else sourceHeight
        if (sourceWidth == input.width && sourceHeight == input.height && outWidth == input.width && outHeight == input.height) {
            return input
        }
        val source = if (input.config == Bitmap.Config.HARDWARE) input.copy(Bitmap.Config.ARGB_8888, false) else input
        val left = (source.width - sourceWidth) / 2
        val top = (source.height - sourceHeight) / 2
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            source,
            Rect(left, top, left + sourceWidth, top + sourceHeight),
            Rect(0, 0, outWidth, outHeight),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    override fun equals(other: Any?) = other is CropTransformation && other.width == width && other.height == height
    override fun hashCode() = 31 * width + height
}
