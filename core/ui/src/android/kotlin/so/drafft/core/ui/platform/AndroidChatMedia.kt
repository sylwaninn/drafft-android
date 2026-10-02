package so.drafft.core.ui.platform

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The Android side of the chat's media pieces in [PlatformUi]: the photo and video picker, the system
// camera, a video's length and poster, and the share sheet.

/** Where camera captures and picked videos are kept (declared in res/xml/file_paths.xml). */
private fun capturesDir(context: Context): File = File(context.cacheDir, "captures").apply { mkdirs() }
private fun sharedDir(context: Context): File = File(context.cacheDir, "shared").apply { mkdirs() }
private fun authority(context: Context): String = "${context.packageName}.files"

@Composable
internal fun rememberChatMediaPicker(maxSelection: Int, onPicked: (List<PickedMedia>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deliver = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(max(2, maxSelection)),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val items = withContext(Dispatchers.IO) { uris.take(maxSelection).map { readPicked(context, it) ?: PickedMedia.Unreadable } }
            deliver.value(items)
        }
    }
    return remember(launcher) {
        { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    }
}

/** A photo as its bytes; a video copied into the app's cache (the picker's grant ends with the screen). */
private fun readPicked(context: Context, uri: Uri): PickedMedia? = runCatching {
    val resolver = context.contentResolver
    val type = resolver.getType(uri).orEmpty()
    if (type.startsWith("video/")) {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "mp4"
        val dest = File(capturesDir(context), "${UUID.randomUUID()}.$ext")
        resolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        PickedMedia.Video(dest.path)
    } else {
        resolver.openInputStream(uri)?.use { it.readBytes() }?.let { PickedMedia.Photo(it) }
    }
}.getOrNull()

@Composable
internal fun rememberSystemCamera(onCapture: (CameraCapture) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val available = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val scope = rememberCoroutineScope()
    val deliver = rememberUpdatedState(onCapture)
    // The files the camera writes into, made for each capture.
    val targets = remember { arrayOfNulls<File>(2) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val photo = targets[0]
        val video = targets[1]
        targets.fill(null)
        if (result.resultCode != Activity.RESULT_OK) {
            photo?.delete()
            video?.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val capture = withContext(Dispatchers.IO) {
                when {
                    video != null && video.length() > 0 -> {
                        photo?.delete()
                        CameraCapture.Video(video.path)
                    }
                    photo != null && photo.length() > 0 -> {
                        video?.delete()
                        val bytes = photo.readBytes()
                        photo.delete()
                        CameraCapture.Photo(bytes)
                    }
                    else -> null
                }
            } ?: return@launch
            deliver.value(capture)
        }
    }
    val launchCamera: () -> Unit = remember(camera) {
        {
            val dir = capturesDir(context)
            val photo = File(dir, "${UUID.randomUUID()}.jpg")
            val video = File(dir, "${UUID.randomUUID()}.mp4")
            targets[0] = photo
            targets[1] = video
            val photoUri = FileProvider.getUriForFile(context, authority(context), photo)
            val videoUri = FileProvider.getUriForFile(context, authority(context), video)
            val flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            val takePhoto = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                .addFlags(flags)
                .apply { clipData = ClipData.newRawUri("", photoUri) }
            // Videos up to 3 minutes, high quality.
            val takeVideo = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, videoUri)
                .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 180)
                .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
                .addFlags(flags)
                .apply { clipData = ClipData.newRawUri("", videoUri) }
            // One entry point, photo or video: the person picks in the chooser, then in the camera.
            val chooser = Intent.createChooser(takePhoto, null)
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takeVideo))
            runCatching { camera.launch(chooser) }.onFailure { targets.fill(null) }
        }
    }
    // The app declares CAMERA, so the system camera intents need it granted first.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }
    if (!available) return null
    return remember(launchCamera, permission) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                permission.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

internal suspend fun readVideoInfo(url: String): VideoInfo? = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        if (url.startsWith("http")) retriever.setDataSource(url, emptyMap()) else retriever.setDataSource(url.removePrefix("file://"))
        val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        val thumb = frame?.let { bitmap ->
            // The poster frame, at most 600 px on its long side: enough for a chat bubble.
            val scale = minOf(1f, 600f / max(bitmap.width, bitmap.height))
            val sized = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }
            ByteArrayOutputStream().use { out ->
                sized.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        }
        VideoInfo(duration = millis / 1000.0, thumbnail = thumb)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
internal fun rememberShareSheet(): (ShareItem) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context) {
        { item ->
            scope.launch {
                val intent = withContext(Dispatchers.IO) { shareIntent(context, item) } ?: return@launch
                runCatching {
                    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}

private fun shareIntent(context: Context, item: ShareItem): Intent? = runCatching {
    when (item) {
        is ShareItem.Image -> {
            val file = File(sharedDir(context), "${UUID.randomUUID()}.jpg").apply { writeBytes(item.data) }
            fileIntent(context, file, "image/jpeg")
        }
        is ShareItem.Video -> {
            if (item.url.startsWith("http")) {
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.url)
            } else {
                val source = File(item.url.removePrefix("file://"))
                // Only the cache folders are shared through the provider: anything else is copied there.
                val file = if (source.parentFile == capturesDir(context) || source.parentFile == sharedDir(context)) {
                    source
                } else {
                    File(sharedDir(context), source.name).also { source.copyTo(it, overwrite = true) }
                }
                fileIntent(context, file, "video/mp4")
            }
        }
    }
}.getOrNull()

private fun fileIntent(context: Context, file: File, type: String): Intent {
    val uri = FileProvider.getUriForFile(context, authority(context), file)
    return Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri("", uri) }
}
