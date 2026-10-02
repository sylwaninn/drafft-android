package so.drafft.core.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [VideoCompressor.Engine] on Media3 Transformer: HEVC (H.264 where the phone has no HEVC encoder:
 * `setEnableFallback`), long edge 1280, ~1.6 Mb/s video with a keyframe every 2 s,
 * AAC 96 kb/s, cut to the settings' length. HDR is tone-mapped to SDR, the rotation is baked into the
 * pixels (Transformer outputs upright frames), and the MP4 index goes at the front (the in-app muxer),
 * so playback starts on the first bytes. The poster is the first frame of the result
 * (`MediaMetadataRetriever`), prepared like a photo.
 */
@OptIn(UnstableApi::class)
class AndroidVideoCompressor(private val context: Context) : VideoCompressor.Engine {
    override suspend fun prepare(source: String, settings: VideoCompressor.Settings): PreparedVideo {
        val uri = if (source.startsWith("/")) Uri.fromFile(File(source)) else source.toUri()
        val info = withContext(Dispatchers.IO) { probe(uri) }

        // Upright frames at the source size, scaled to fit the long edge.
        val rotated = info.rotation == 90 || info.rotation == 270
        val uprightW = if (rotated) info.height else info.width
        val uprightH = if (rotated) info.width else info.height
        val scale = minOf(1.0, settings.maxLongEdge / max(uprightW, uprightH))
        val width = VideoCompressor.even(uprightW * scale)
        val height = VideoCompressor.even(uprightH * scale)

        val limitMs = settings.maxDuration?.let { (it * 1000).toLong() }
        val clipped = if (limitMs != null && info.durationMs > limitMs) limitMs else info.durationMs
        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            if (limitMs != null && info.durationMs > limitMs) {
                setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(limitMs).build())
            }
        }.build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(listOf(edited)).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val output = File(context.cacheDir, "video-${UUID.randomUUID()}.mp4")
        val result = try {
            export(composition, output, settings)
        } catch (e: Throwable) {
            output.delete()
            throw e
        }

        val poster = withContext(Dispatchers.Default) { poster(output, settings.maxLongEdge) }
        return PreparedVideo(
            fileURL = output.path,
            byteSize = output.length(),
            width = width,
            height = height,
            duration = (if (result.durationMs > 0) result.durationMs else clipped) / 1000.0,
            poster = poster,
        )
    }

    private class Probe(val width: Int, val height: Int, val rotation: Int, val durationMs: Long)

    private fun probe(uri: Uri): Probe {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(context, uri)
            } catch (e: RuntimeException) {
                throw MediaPreparationError.UnreadableVideo
            }
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                throw MediaPreparationError.NoVideoTrack
            }
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (width <= 0 || height <= 0) throw MediaPreparationError.UnreadableVideo
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            return Probe(width, height, int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), duration)
        } finally {
            retriever.release()
        }
    }

    /** Runs the export on the main thread (Transformer's looper) and waits for it; cancelling stops it. */
    private suspend fun export(composition: Composition, output: File, settings: VideoCompressor.Settings): ExportResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val encoders = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(settings.videoBitRate)
                            // A keyframe every 2 s keeps seeking and the first frame cheap.
                            .setiFrameIntervalSeconds(2f)
                            .build(),
                    )
                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(settings.audioBitRate).build())
                    .setEnableFallback(true)
                    .build()
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H265)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoders)
                    .setMuxerFactory(InAppMp4Muxer.Factory())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(exportResult)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(MediaPreparationError.ExportFailed(exportException.errorCodeName))
                            }
                        }
                    })
                    .build()
                continuation.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, output.path)
            }
        }

    /** The first frame of the transcoded video, upright, at most [maxLongEdge] on its long side. */
    private fun poster(video: File, maxLongEdge: Double): PreparedPhoto {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.path)
            val side = maxLongEdge.toInt()
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side)
                ?: throw MediaPreparationError.ExportFailed("no poster frame")
            return AndroidPhotoCompressor.encode(frame, PhotoCompressor.quality)
        } finally {
            retriever.release()
        }
    }
}
