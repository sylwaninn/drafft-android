package so.drafft.core.ui.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.FaceDetector
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import so.drafft.core.data.platform.PermissionPrompter

// Ports SelfieCamera (Drafft/Features/Verification/SelfieCaptureView.swift): AVFoundation and Vision
// become CameraX and the platform's on-device face detector (`android.media.FaceDetector`).

/**
 * The front camera: frames for the live face check, and one photo when asked. Analysis runs on its
 * own thread; results come back on the main thread.
 */
internal class AndroidFrontCamera(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : FrontCamera {
    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        // TextureView: the preview is clipped to the rounded frame like any other view.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var frame = 0

    override var onFaces: ((List<FaceBox>) -> Unit)? = null

    override suspend fun requestAccess(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        return PermissionPrompter.request(listOf(Manifest.permission.CAMERA)) == PermissionPrompter.Result.GRANTED
    }

    /** False when there's no front camera (an emulator without one). */
    override suspend fun start(): Boolean {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull() ?: return false
        this.provider = provider
        if (!runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)) return false
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(executor, ::analyze) }
        val still = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        capture = still
        return runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis, still)
        }.isSuccess
    }

    override fun stop() {
        provider?.unbindAll()
        capture = null
    }

    /** The screen is gone: the camera and the analysis thread with it. */
    fun release() {
        stop()
        onFaces = null
        executor.shutdown()
    }

    override suspend fun capture(): CapturedPhoto? {
        val still = capture ?: return null
        val proxy = suspendCancellableCoroutine<ImageProxy?> { done ->
            still.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) = done.resume(image)
                    override fun onError(exception: ImageCaptureException) = done.resume(null)
                },
            )
        } ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = proxy.use { upright(it, mirrored = true) }
            val jpeg = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.toByteArray()
            }
            CapturedPhoto(bitmap.asImageBitmap(), jpeg, faces(bitmap))
        }
    }

    /** Every 4th frame (about 7 checks a second) is plenty, and keeps the phone cool. */
    private fun analyze(image: ImageProxy) {
        image.use {
            frame += 1
            if (frame % 4 != 0) return
            val faces = faces(upright(it, mirrored = false))
            main.post { onFaces?.invoke(faces) }
        }
    }

    /** The frame's pixels turned upright (the app is portrait only), mirrored as the person sees themselves. */
    private fun upright(image: ImageProxy, mirrored: Boolean): Bitmap {
        val source = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0 && !mirrored) return source
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirrored) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    companion object {
        /** The detector works on a small copy: faces that fill an oval don't need more. */
        private const val DETECTION_WIDTH = 320

        /** A face is about this many eye distances wide (the detector only gives the eyes). */
        private const val FACE_WIDTH_IN_EYES = 2.3f

        /** The faces in [bitmap], in 0...1 of its frame. */
        fun faces(bitmap: Bitmap): List<FaceBox> {
            val scale = DETECTION_WIDTH.toFloat() / bitmap.width
            // FaceDetector wants RGB_565 and an even width.
            val width = (DETECTION_WIDTH / 2) * 2
            val height = (bitmap.height * scale).toInt().coerceAtLeast(2)
            val small = Bitmap.createScaledBitmap(bitmap, width, height, true).copy(Bitmap.Config.RGB_565, false)
            val found = arrayOfNulls<FaceDetector.Face>(4)
            val count = runCatching { FaceDetector(width, height, found.size).findFaces(small, found) }.getOrDefault(0)
            val mid = android.graphics.PointF()
            return found.take(count).filterNotNull().map { face ->
                face.getMidPoint(mid)
                val w = face.eyesDistance() * FACE_WIDTH_IN_EYES
                val h = w * 1.25f
                FaceBox((mid.x - w / 2) / width, (mid.y - h * 0.4f) / height, w / width, h / height)
            }
        }
    }
}
