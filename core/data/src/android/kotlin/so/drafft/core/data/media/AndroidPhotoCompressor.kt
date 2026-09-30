package so.drafft.core.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.drafft.core.model.ThumbHash

/**
 * [PhotoCompressor.Engine] on Android: decoded close to the target size (`inSampleSize`, never the full
 * 48 MP bitmap), scaled to 2048 px on the long edge, turned upright from its EXIF orientation, and
 * re-encoded as JPEG without any metadata (only pixels leave the phone).
 */
class AndroidPhotoCompressor(private val context: Context) : PhotoCompressor.Engine {
    override suspend fun prepare(data: ByteArray, maxPixelSize: Int, quality: Double): PreparedPhoto =
        withContext(Dispatchers.Default) { encode(decodeUpright(data, maxPixelSize), quality) }

    override suspend fun prepare(fileAt: String, maxPixelSize: Int, quality: Double): PreparedPhoto {
        val data = withContext(Dispatchers.IO) { read(fileAt) } ?: throw MediaPreparationError.UnreadableImage
        return prepare(data, maxPixelSize, quality)
    }

    override suspend fun savePicked(data: ByteArray): String? {
        val photo = runCatching { prepare(data, PhotoCompressor.maxPixelSize, PhotoCompressor.quality) }.getOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "photo-${UUID.randomUUID()}.jpg")
            runCatching { file.writeBytes(photo.data) }.map { file.path }.getOrNull()
        }
    }

    private fun read(path: String): ByteArray? = runCatching {
        if (path.startsWith("content:") || path.startsWith("file:")) {
            context.contentResolver.openInputStream(path.toUri())?.use { it.readBytes() }
        } else {
            File(path).readBytes()
        }
    }.getOrNull()

    companion object {
        /** The photo at [maxPixelSize] at most on its long edge, upright. */
        fun decodeUpright(data: ByteArray, maxPixelSize: Int): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw MediaPreparationError.UnreadableImage
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixelSize) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                data, 0, data.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            ) ?: throw MediaPreparationError.UnreadableImage
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            return transform(decoded, orientation, maxPixelSize)
        }

        /** Scales down to [maxPixelSize] and applies the EXIF orientation, in one pass. */
        private fun transform(source: Bitmap, orientation: Int, maxPixelSize: Int): Bitmap {
            val scale = minOf(1f, maxPixelSize.toFloat() / max(source.width, source.height))
            val matrix = Matrix().apply {
                postScale(scale, scale)
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                }
            }
            if (matrix.isIdentity) return source
            val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            if (result !== source) source.recycle()
            return result
        }

        /** Encodes an already decoded image (a video poster frame, for instance). */
        fun encode(image: Bitmap, quality: Double): PreparedPhoto {
            // JPEG has no alpha: a transparent picture lands on white rather than black.
            val opaque = if (image.hasAlpha()) {
                Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(image, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)) }
                }
            } else {
                image
            }
            val out = ByteArrayOutputStream()
            if (!opaque.compress(Bitmap.CompressFormat.JPEG, (quality * 100).roundToInt(), out)) {
                throw MediaPreparationError.EncodingFailed
            }
            return PreparedPhoto(out.toByteArray(), opaque.width, opaque.height, thumbHash(opaque))
        }

        /** Base64 ThumbHash of an image, downscaled to fit 100×100 first (the encoder's limit). */
        fun thumbHash(image: Bitmap): String? = runCatching {
            val scale = minOf(1.0, 100.0 / max(image.width, image.height))
            val w = max(1, (image.width * scale).roundToInt())
            val h = max(1, (image.height * scale).roundToInt())
            val small = Bitmap.createScaledBitmap(image, w, h, true)
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgba = ByteArray(w * h * 4)
            for (i in pixels.indices) {
                val p = pixels[i]
                rgba[i * 4] = (p shr 16 and 0xFF).toByte()
                rgba[i * 4 + 1] = (p shr 8 and 0xFF).toByte()
                rgba[i * 4 + 2] = (p and 0xFF).toByte()
                rgba[i * 4 + 3] = (p ushr 24).toByte()
            }
            ThumbHash.base64(w, h, rgba)
        }.getOrNull()

        /** A decoded preview as a bitmap (for the photo views' placeholder, core:ui). */
        fun bitmap(preview: ThumbHash.Image): Bitmap {
            val pixels = IntArray(preview.width * preview.height) { i ->
                val r = preview.rgba[i * 4].toInt() and 0xFF
                val g = preview.rgba[i * 4 + 1].toInt() and 0xFF
                val b = preview.rgba[i * 4 + 2].toInt() and 0xFF
                val a = preview.rgba[i * 4 + 3].toInt() and 0xFF
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Bitmap.createBitmap(pixels, preview.width, preview.height, Bitmap.Config.ARGB_8888)
        }
    }
}
