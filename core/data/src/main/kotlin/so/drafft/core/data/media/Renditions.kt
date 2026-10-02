package so.drafft.core.data.media

import kotlin.math.ceil

// Ports Drafft/Services/Media/Renditions.swift.

/** A size in pixels (the iPhone code's `CGSize` in pixels). */
data class PixelSize(val width: Double, val height: Double) {
    constructor(width: Int, height: Int) : this(width.toDouble(), height.toDouble())
}

/**
 * Which copy of a server photo a frame needs, like a web page's `srcset`. The media Worker serves each
 * photo at a few widths ([ladder], its WIDTHS in cloudflare/media-worker, made once and kept next to the
 * original) and the original (2048 px on its long edge) above them.
 *
 * - The width a frame needs depends on the photo's proportions: filling a tall card with a 4:5 photo
 *   takes the card's height times 0.8, a landscape photo much more. Unknown proportions count as square
 *   (never blurry, sometimes larger than needed).
 * - The smallest copy at least that wide is the one to download; a larger one already on this phone
 *   stands in for it ([candidates], best first).
 * - The copy is decoded at the frame's size in pixels ([decodeSize]), not the downloaded copy's: memory
 *   holds what the screen shows, whatever was downloaded.
 */
object Renditions {
    /** Widths the media Worker serves; any other is the original. */
    val ladder = listOf(160, 320, 640, 1_080, 1_440)

    /** A step up the ladder is worth more than a 5 % upscale nobody sees. */
    const val tolerance = 0.95

    /**
     * The widest copy a deck card, a grid or any photo but an open profile's asks for: 1 080, about 2.4
     * pixels a point on a 3x phone's card, which a photo doesn't show (Instagram's order of magnitude for a
     * full-width photo), for about 40 % less to download and to keep in memory than 1 440. An open
     * profile's photos ([asked]'s `detail`), looked at closely, may take 1 440 and the original.
     */
    const val everydayWidth = 1_080.0

    /** The width to ask for: what the frame needs, at most [everydayWidth] unless it's an open profile's photo. */
    fun asked(needed: Double, detail: Boolean): Double = if (detail) needed else minOf(needed, everydayWidth)

    /** On a limited connection, the width asked of a full copy: a step lighter (1 080 for a 1 344 card). */
    const val limitedShare = 0.75

    /** Source pixels wide enough to fill [pixels] (aspect fill) with a photo [aspect] wide for 1 high. */
    fun neededWidth(pixels: PixelSize, aspect: Double?): Double {
        if (aspect == null || !aspect.isFinite() || aspect <= 0) return maxOf(pixels.width, pixels.height)
        return maxOf(pixels.width, pixels.height * aspect)
    }

    /** The smallest rendition covering [covering], null for the original. */
    fun width(covering: Double): Int? = ladder.firstOrNull { it.toDouble() >= covering * tolerance }

    /**
     * Every copy that can be shown for [covering], best first: the rendition covering it, the larger ones,
     * then the original (null).
     */
    fun candidates(covering: Double): List<Int?> {
        val start = width(covering) ?: return listOf(null)
        return ladder.filter { it >= start } + listOf(null)
    }

    /**
     * A small copy shown first on a slow connection, sharpened when the right one arrives: a quarter of
     * the width needed, at least 160 px.
     */
    fun previewWidth(covering: Double): Int = width(maxOf(160.0, covering / 4)) ?: ladder.last()

    /**
     * The decoded size: the frame in pixels, its width rounded up to 64 px so frames a few points apart
     * share one copy in memory, its height following the frame's exact proportions. Every copy of a photo
     * in one frame (small, sharp) is cropped alike, so a sharper one lands exactly over the last.
     */
    fun decodeSize(pixels: PixelSize): PixelSize {
        val width = maxOf(64.0, ceil(pixels.width / 64) * 64)
        if (pixels.width <= 0 || pixels.height <= 0) return PixelSize(width, width)
        return PixelSize(width, Math.round(width * pixels.height / pixels.width).toDouble())
    }
}
