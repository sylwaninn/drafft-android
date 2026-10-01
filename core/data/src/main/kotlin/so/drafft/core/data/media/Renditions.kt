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
     * The decoded size: the frame in pixels, rounded up to 64 px so frames a few points apart (and a
     * card in flight, the same card in the deck) share one copy in memory.
     */
    fun decodeSize(pixels: PixelSize): PixelSize {
        fun up(value: Double) = maxOf(64.0, ceil(value / 64) * 64)
        return PixelSize(up(pixels.width), up(pixels.height))
    }
}
