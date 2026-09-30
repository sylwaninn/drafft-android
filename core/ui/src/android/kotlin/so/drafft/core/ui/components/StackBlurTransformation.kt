package so.drafft.core.ui.components

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bakes a blur into a decoded copy, once (locked likes): never a live blur on each card. The copy is
 * already small (the request's size), so three box passes on its pixels cost well under a
 * millisecond per photo and look like a Gaussian of [sigma] pixels.
 */
internal class StackBlurTransformation(private val sigma: Int) : Transformation() {
    override val cacheKey: String = "drafft.blur-$sigma"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val source = if (input.config == Bitmap.Config.HARDWARE) input.copy(Bitmap.Config.ARGB_8888, false) else input
        val w = source.width
        val h = source.height
        if (w < 2 || h < 2 || sigma <= 0) return source
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        boxBlur(pixels, w, h, boxRadius(sigma.toDouble()))
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    override fun equals(other: Any?) = other is StackBlurTransformation && other.sigma == sigma
    override fun hashCode() = sigma
}

/** Three box passes of this radius approximate a Gaussian of [sigma]: σ² = ((2r + 1)² - 1) / 4. */
private fun boxRadius(sigma: Double): Int = ((sqrt(4 * sigma * sigma + 1) - 1) / 2).roundToInt().coerceAtLeast(1)

/** Blurs [pixels] (ARGB, [w] × [h]) in place, edges clamped. */
internal fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
    val tmp = IntArray(pixels.size)
    repeat(3) {
        pass(pixels, tmp, w, h, radius) // rows, written transposed
        pass(tmp, pixels, h, w, radius) // former columns, written back upright
    }
}

/** A horizontal box blur of [src] (rows of [w]) written transposed into [dst]. */
private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val div = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var a = 0
        var red = 0
        var green = 0
        var blue = 0
        for (i in -r..r) {
            val p = src[row + i.coerceIn(0, w - 1)]
            a += p ushr 24
            red += (p shr 16) and 0xFF
            green += (p shr 8) and 0xFF
            blue += p and 0xFF
        }
        for (x in 0 until w) {
            dst[x * h + y] = ((a / div) shl 24) or ((red / div) shl 16) or ((green / div) shl 8) or (blue / div)
            val add = src[row + minOf(x + r + 1, w - 1)]
            val drop = src[row + maxOf(x - r, 0)]
            a += (add ushr 24) - (drop ushr 24)
            red += ((add shr 16) and 0xFF) - ((drop shr 16) and 0xFF)
            green += ((add shr 8) and 0xFF) - ((drop shr 8) and 0xFF)
            blue += (add and 0xFF) - (drop and 0xFF)
        }
    }
}
