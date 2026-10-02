package so.drafft.core.data.media

// The platform-neutral part of images: the cache sizes, the download priorities and the media Worker's sized links.
// Loading, decoding and caching the photos is Coil's, configured in core:ui with these numbers (`ImageStore`, and
// `installImages` on Android).

/**
 * Every photo that isn't bundled with the app (on the server, or picked on this phone) goes through one
 * image pipeline (Coil, in core:ui):
 *
 * - the copy downloaded is the smallest the frame needs (`Renditions`, from the photo's proportions), or
 *   a larger one already on this phone; the media Worker makes each copy once and keeps it;
 * - decoded in the background, straight at the frame's size in pixels and cropped to it, never the full
 *   bitmap on the main thread: memory holds what the screen shows;
 * - a memory cache sized to the phone, and a capped disk cache of the downloaded bytes (keys never
 *   change, so a copy is downloaded once);
 * - both caches keyed by the object and width (`MediaURL.canonical`), never by its signed link: a photo
 *   stays cached when its link is renewed;
 * - on a slow connection (`NetworkQuality`), fewer downloads at once, so the one on screen finishes first.
 */
object Images {
    /**
     * Decoded photos kept in memory (the system can still evict them earlier): a twentieth of the
     * phone's memory, between 96 and 256 MB. A deck card is about 7.5 MB (its frame at 3x).
     */
    fun memoryLimit(physicalMemory: Long): Long = minOf(256L shl 20, maxOf(96L shl 20, physicalMemory / 20))

    /** Downloaded bytes kept on disk. */
    const val diskLimit: Long = 300L shl 20

    /** Downloads running at once: six share a fast line; on a slow one, two. */
    const val downloads = 6
    const val limitedDownloads = 2

    /**
     * Download order among photos waiting: the deck card in
     * play first, then the ones behind it, then what's fetched ahead.
     */
    enum class Priority { VERY_LOW, LOW, NORMAL, HIGH, VERY_HIGH }

    /** The request header that carries a photo's [Priority] to the download queue (never sent). */
    const val PRIORITY_HEADER = "x-drafft-priority"

    /**
     * The request header that names the photo (its cache key) to the download queue (never sent): a
     * card moving up the deck raises its download's priority (`PhotoDownloads.prioritize`).
     */
    const val PHOTO_HEADER = "x-drafft-photo"

    // MARK: Copies served by the media Worker

    /**
     * A photo link with `&w=` set to [width] (null: the original). Only a signed link goes through the
     * media Worker, which serves the widths; any other is left as it is. `w` isn't part of the
     * signature, and the caches keep one copy per width (`MediaURL.canonical`). Null for a link that
     * isn't one.
     */
    fun sized(name: String, width: Int?): String? {
        if (runCatching { java.net.URI(name) }.isFailure) return null
        if (MediaURL.key(name) == null || MediaURL.expiry(name) == null) return name
        return MediaURL.withWidth(name, width)
    }
}
