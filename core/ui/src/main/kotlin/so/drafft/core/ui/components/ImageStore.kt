package so.drafft.core.ui.components

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.size.Size
import so.drafft.core.data.media.Images
import so.drafft.core.data.media.MediaPreviews
import so.drafft.core.model.ThumbHash
import so.drafft.core.ui.image.BundledImages
import java.io.File
import kotlin.math.roundToInt

// Port of Drafft/DesignSystem/ImageStore.swift, with the parts of Services/Media/Images.swift that
// size remote photos.

/**
 * Display-ready copies of the bundled photos, through Coil's memory cache.
 *
 * A bundled photo (1000 to 1400 px) decoded on the main thread the first time it's drawn froze the
 * tab bar on iOS when a tab full of avatars did it dozens of times. Here small displays get a
 * downsampled copy, blurred displays get the blur baked in once (no live blur), and the photos the
 * first screens need are prepared in the background at launch ([prewarm]).
 */
object ImageStore {
    /**
     * Bakes a blur of `radius` pixels into a request's decoded copy. Coil's transformations are
     * Android-only, so the Android side installs it at launch (`installDrafftUi()`); until then
     * blurred photos are drawn sharp.
     */
    @Volatile
    var blurEngine: (builder: ImageRequest.Builder, radius: Int) -> Unit = { _, _ -> }

    /** Pixel sizes shared by nearby displays (a 56 pt and a 64 pt avatar use one copy). 0 means the full photo. */
    fun bucket(pixels: Int?): Int {
        if (pixels == null) return 0
        return intArrayOf(96, 192, 384, 768).firstOrNull { it >= pixels } ?: 0
    }

    private fun key(name: String, bucket: Int, blur: Int) = "$name@${bucket}b$blur"

    /**
     * The request for a bundled photo drawn with a shorter side of [sidePx] pixels (null: full
     * size), blurred by [blurFraction] of its shorter side. A blurred copy is always 192 px:
     * `fraction` keeps it looking like a live blur of `fraction × side` on screen.
     */
    fun request(context: PlatformContext, name: String, sidePx: Int?, blurFraction: Float = 0f): ImageRequest {
        val blurred = blurFraction > 0f
        val bucket = if (blurred) 192 else bucket(sidePx)
        val blur = if (blurred) (192 * blurFraction).roundToInt() else 0
        val builder = ImageRequest.Builder(context)
            .data(BundledImages.resource(name) ?: name)
            .memoryCacheKey(key(name, bucket, blur))
            .size(if (bucket > 0) Size(bucket, bucket) else Size.ORIGINAL)
            .scale(Scale.FILL)
        if (blur > 0) blurEngine(builder, blur)
        return builder.build()
    }

    /**
     * Whether the full-size photo is already prepared (prewarmed). Otherwise the view falls back to
     * the platform's own decode rather than decoding on the spot (a stack of hidden photos, like the
     * welcome carousel, would all decode at once).
     */
    fun isPrepared(context: PlatformContext, name: String): Boolean =
        SingletonImageLoader.get(context).memoryCache?.get(MemoryCache.Key(key(name, 0, 0))) != null

    /** Prepares photos off the main thread so the first screens draw them without decoding. */
    fun prewarm(
        context: PlatformContext,
        density: Density,
        full: List<String>,
        small: List<Pair<String, Dp>>,
        blurred: List<Pair<String, Float>>,
    ) {
        val loader = SingletonImageLoader.get(context)
        full.forEach { loader.enqueue(request(context, it, null)) }
        small.forEach { (name, side) -> loader.enqueue(request(context, name, with(density) { side.roundToPx() })) }
        blurred.forEach { (name, fraction) -> loader.enqueue(request(context, name, null, fraction)) }
    }

    // MARK: Photos on the server or on this phone

    /**
     * Pixel sizes shared by nearby displays: a size change (rotation, animation) reuses a copy
     * instead of decoding again, and two avatars a few points apart share one.
     */
    fun remoteBucket(pixels: Int): Int = Images.bucket(pixels.toDouble())

    /**
     * The request for a photo drawn in a frame whose longer side is [pixels] (a [remoteBucket]):
     * `/…` is a file on this phone, `http…` a photo on the server (sized by [PhotoUrls.sizer]).
     * Square box, aspect fill: the copy covers the frame whatever its proportions. [blur]: a radius
     * as a share of the photo's shorter side, applied once when it's decoded. Cached by the object
     * ([PhotoUrls.canonical]), never by its signed link. [variant]: a rendition kept apart in the
     * caches (the server's blurred copy of a like), so it can never be served for another rendition
     * of the same object, or the other way round.
     */
    fun remoteRequest(context: PlatformContext, name: String, pixels: Int, blur: Float = 0f, variant: String? = null): ImageRequest {
        val local = name.startsWith("/")
        val canonical = (if (local) name else PhotoUrls.canonical(name)).let { key -> variant?.let { "$key#$it" } ?: key }
        val radius = if (blur > 0f) maxOf(1, (blur * pixels).toInt()) else 0
        val builder = ImageRequest.Builder(context)
            .data(if (local) File(name) else PhotoUrls.sizer(name, pixels))
            .memoryCacheKey("$canonical@${pixels}b$radius")
            .size(Size(pixels, pixels))
            .scale(Scale.FILL)
        if (!local) builder.diskCacheKey(canonical)
        if (radius > 0) blurEngine(builder, radius)
        return builder.build()
    }
}

/** Hooks the data layer sets for photos on the server. */
object PhotoUrls {
    /**
     * The link to download for a photo shown [width] pixels wide: the media Worker sends a copy at the
     * display width instead of the original when a smaller one is enough (`Images.sized`).
     */
    @Volatile
    var sizer: (url: String, width: Int) -> String = { url, width -> Images.sized(url, width) ?: url }

    /** The object a link points to, without its signature: the cache key, stable when the link is renewed. */
    @Volatile
    var canonical: (url: String) -> String = { it }

    /** The ThumbHash preview of a server photo, drawn while it loads (`MediaPreviews`); null when unknown. */
    @Volatile
    var preview: (url: String) -> ImageBitmap? = { url -> MediaPreviews.image(url)?.let(::previewBitmap) }

    private val previews = object : LinkedHashMap<ThumbHash.Image, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ThumbHash.Image, ImageBitmap>?) = size > 300
    }

    /** A ThumbHash's RGBA pixels (about 32 × 32) as a bitmap, made once per preview. */
    private fun previewBitmap(image: ThumbHash.Image): ImageBitmap = synchronized(previews) {
        previews.getOrPut(image) {
            val bitmap = ImageBitmap(image.width, image.height)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val rgba = image.rgba
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val i = (y * image.width + x) * 4
                    paint.color = Color(
                        red = rgba[i].toInt() and 0xFF,
                        green = rgba[i + 1].toInt() and 0xFF,
                        blue = rgba[i + 2].toInt() and 0xFF,
                        alpha = rgba[i + 3].toInt() and 0xFF,
                    )
                    canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
                }
            }
            bitmap
        }
    }
}

/**
 * Photos and video posters sent in chat (kept as bytes on the message): their size is read from the
 * header, without decoding, and the pixels are decoded in the background at the bubble's size.
 */
object MessageImage {
    /** Reads the upright pixel size from the header (installed on Android at launch). */
    @Volatile
    var sizeReader: (ByteArray) -> IntSize? = { null }

    /** Pixel size, upright (EXIF orientation applied), from the image header only. */
    fun size(data: ByteArray): IntSize? = sizeReader(data)

    fun request(context: PlatformContext, id: String, data: ByteArray, sidePx: Int): ImageRequest =
        ImageRequest.Builder(context)
            .data(data)
            .memoryCacheKey("message-$id-$sidePx")
            .size(Size(sidePx, sidePx))
            .scale(Scale.FILL)
            .build()
}
