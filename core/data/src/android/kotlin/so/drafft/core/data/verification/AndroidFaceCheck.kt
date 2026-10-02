package so.drafft.core.data.verification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import so.drafft.core.data.media.AndroidPhotoCompressor

/**
 * [FaceCheck.Engine] on ML Kit's on-device face detection: the
 * photo at 1024 px at most, upright, decoded off the main thread.
 */
class AndroidFaceCheck(private val context: Context) : FaceCheck.Engine {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.05f)
                .build(),
        )
    }

    override suspend fun faceAreas(photo: String): List<Double> {
        val image = uprightImage(photo) ?: return emptyList()
        return faces(image, rotationDegrees = 0).map { (it.width() * it.height()).toDouble() }
    }

    /**
     * The faces ML Kit finds, as boxes in 0...1 of the upright image, origin at the bottom left (like
     * Vision's, so the selfie framing reads them the same way). [rotationDegrees] turns a camera frame
     * upright.
     */
    suspend fun faces(image: Bitmap, rotationDegrees: Int): List<RectF> {
        val found = try {
            detector.process(InputImage.fromBitmap(image, rotationDegrees)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        val sideways = rotationDegrees == 90 || rotationDegrees == 270
        val w = (if (sideways) image.height else image.width).toFloat()
        val h = (if (sideways) image.width else image.height).toFloat()
        return found.map { face ->
            val box = face.boundingBox
            val left = (box.left / w).coerceIn(0f, 1f)
            val right = (box.right / w).coerceIn(0f, 1f)
            val top = (box.top / h).coerceIn(0f, 1f)
            val bottom = (box.bottom / h).coerceIn(0f, 1f)
            // Flipped vertically: `top` is the box's upper edge measured from the bottom.
            RectF(left, 1f - bottom, right, 1f - top)
        }
    }

    /** The photo at 1024 px at most, upright (a picked file), or a bundled image. */
    private suspend fun uprightImage(path: String): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            if (path.startsWith("/")) {
                AndroidPhotoCompressor.decodeUpright(File(path).readBytes(), 1024)
            } else {
                val res = context.resources.getIdentifier(path, "drawable", context.packageName)
                if (res == 0) null else BitmapFactory.decodeResource(context.resources, res)
            }
        }.getOrNull()
    }
}
