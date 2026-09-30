package so.drafft.core.data.media

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Ports Drafft/Services/Media/MediaUploader.swift.

/** Mirrors `purposes` in the backend's media-upload-url function (types and size limits live there). */
enum class MediaPurpose(val rawValue: String) {
    PROFILE_PHOTO("profile_photo"),
    PROFILE_VIDEO("profile_video"),
    VIDEO_POSTER("video_poster"),
    VOICE_INTRO("voice_intro"),
    CHAT_PHOTO("chat_photo"),
    CHAT_VIDEO("chat_video"),
    CHAT_VOICE("chat_voice"),
    CHAT_FILE("chat_file"),
}

/** A presigned PUT to the media bucket, valid for [expiresIn] seconds. */
@Serializable
data class UploadTicket(
    val key: String,
    val uploadUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val publicUrl: String,
    val expiresIn: Int,
)

fun interface UploadTicketProviding {
    suspend fun ticket(purpose: MediaPurpose, contentType: String, byteSize: Long): UploadTicket
}

sealed class MediaUploadError(message: String) : Exception(message) {
    data class Rejected(val code: String) : MediaUploadError("rejected: $code")
    data object TicketExpired : MediaUploadError("ticket expired") { private fun readResolve(): Any = TicketExpired }
    data class Http(val status: Int) : MediaUploadError("http $status")
}

/** Asks the media-upload-url Edge Function for a ticket. */
class EdgeFunctionTicketProvider(
    /** https://<project>.supabase.co/functions/v1 */
    val functionsURL: String,
    /** An account on hold can't upload to chats: the hold screen takes over and says why. */
    private val onAccountHeld: () -> Unit = { MediaUploads.onAccountHeld() },
    /** The signed-in user's current access token. */
    private val accessToken: suspend () -> String,
) : UploadTicketProviding {
    @Serializable
    private data class Body(val purpose: String, val contentType: String, val byteSize: Long)

    @Serializable
    private data class Failure(val code: String)

    override suspend fun ticket(purpose: MediaPurpose, contentType: String, byteSize: Long): UploadTicket {
        val response = MediaUploader.shared.client.post(functionsURL.trimEnd('/') + "/media-upload-url") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            setBody(TextContent(json.encodeToString(Body.serializer(), Body(purpose.rawValue, contentType, byteSize)), ContentType.Application.Json))
        }
        val status = response.status.value
        val data = response.readRawBytes().decodeToString()
        if (status != 200) {
            val failure = runCatching { json.decodeFromString(Failure.serializer(), data) }.getOrNull()
            if (failure != null) {
                if (status == 403 && failure.code == "moderated") withContext(Dispatchers.Main.immediate) { onAccountHeld() }
                throw MediaUploadError.Rejected(failure.code)
            }
            throw MediaUploadError.Http(status)
        }
        return json.decodeFromString(UploadTicket.serializer(), data)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Uploads files straight to the media bucket (a PUT to the ticket's signed URL), with progress.
 *
 * The iPhone hands the upload to a background URLSession. Here it runs in the app's process (the
 * caller's coroutine): the person is waiting on it, and Android keeps a recently used app's process
 * alive for the few seconds a photo or a short video takes. Transient failures are retried.
 */
class MediaUploader(internal val client: HttpClient = defaultClient()) {
    /**
     * PUTs a file to a ticket's URL, retrying transient failures twice (after 1 s, then 2 s).
     * A 403 means the ticket expired: ask for a new one and call again.
     */
    suspend fun upload(file: File, ticket: UploadTicket, progress: ((Double) -> Unit)? = null) {
        var attempt = 0
        while (true) {
            try {
                put(file, ticket, progress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaUploadError.TicketExpired) {
                throw e
            } catch (e: Exception) {
                attempt += 1
                if (attempt >= 3) throw e
                delay((1L shl (attempt - 1)).seconds)
            }
        }
    }

    private suspend fun put(file: File, ticket: UploadTicket, progress: ((Double) -> Unit)?) {
        // Content-Length comes from the file itself; it matches the signed byteSize because the ticket
        // was requested for this exact file. Content-Type goes with the body (Ktor sets it from there).
        val type = ticket.headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        val body = FileContent(file, type?.let(ContentType::parse) ?: ContentType.Application.OctetStream, progress)
        val response = client.put(ticket.uploadUrl) {
            for ((name, value) in ticket.headers) {
                if (name.equals("content-length", ignoreCase = true) || name.equals("content-type", ignoreCase = true)) continue
                header(name, value)
            }
            setBody(body)
        }
        when (val status = response.status.value) {
            in 200 until 300 -> Unit
            403 -> throw MediaUploadError.TicketExpired
            else -> throw MediaUploadError.Http(status)
        }
    }

    /** The file's bytes, streamed from disk, reporting how much has gone. */
    private class FileContent(
        private val file: File,
        override val contentType: ContentType,
        private val progress: ((Double) -> Unit)?,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = file.length()

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val total = contentLength
            var sent = 0L
            val buffer = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val n = withContext(Dispatchers.IO) { input.read(buffer) }
                    if (n < 0) break
                    channel.writeFully(buffer, 0, n)
                    sent += n
                    if (total > 0) progress?.invoke(sent.toDouble() / total)
                }
            }
        }
    }

    companion object {
        val shared: MediaUploader by lazy { MediaUploader() }

        private fun defaultClient() = HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                config {
                    connectTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(60, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    // The iPhone's resource timeout: an hour for the whole upload.
                    callTimeout(60, TimeUnit.MINUTES)
                }
            }
        }
    }
}

// Prepare, then upload

/** What the backend needs to register an uploaded profile photo or video (add_profile_media). */
data class UploadedMedia(
    val key: String,
    val width: Int,
    val height: Int,
    val thumbHash: String?,
    /** Videos only. */
    val duration: Double? = null,
    val posterKey: String? = null,
)

object MediaUploads {
    /**
     * Called (on the main thread) when the server refuses a ticket because the account is on hold:
     * installed at launch to post `Backend.Event.ACCOUNT_HELD_BY_SERVER`.
     */
    @Volatile
    var onAccountHeld: () -> Unit = {}

    /** Where upload files are written before they go (the app's cache directory, set at launch). */
    @Volatile
    var temporaryDirectory: File = File(System.getProperty("java.io.tmpdir"))

    /** Compress, then upload. Ticket expiry (10 min) is handled by asking for a fresh one once. */
    suspend fun photo(
        data: ByteArray,
        purpose: MediaPurpose = MediaPurpose.PROFILE_PHOTO,
        tickets: UploadTicketProviding,
        uploader: MediaUploader = MediaUploader.shared,
        progress: ((Double) -> Unit)? = null,
    ): UploadedMedia {
        val photo = PhotoCompressor.prepare(data)
        val file = temporaryFile(photo.data, "jpg")
        try {
            val key = send(file, photo.data.size.toLong(), photo.contentType, purpose, tickets, uploader, progress)
            return UploadedMedia(key, photo.width, photo.height, photo.thumbHash)
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    /**
     * Transcodes, uploads the poster, then the video. Progress covers the video bytes. A chat video's
     * poster goes to the chat folder (`posterPurpose = CHAT_PHOTO`): only chat objects are signed for the
     * other member of the match.
     */
    suspend fun video(
        source: String,
        purpose: MediaPurpose = MediaPurpose.PROFILE_VIDEO,
        settings: VideoCompressor.Settings = VideoCompressor.Settings.profile,
        posterPurpose: MediaPurpose = MediaPurpose.VIDEO_POSTER,
        tickets: UploadTicketProviding,
        uploader: MediaUploader = MediaUploader.shared,
        progress: ((Double) -> Unit)? = null,
    ): UploadedMedia {
        val video = VideoCompressor.prepare(source, settings)
        val videoFile = File(video.fileURL)
        try {
            val posterFile = temporaryFile(video.poster.data, "jpg")
            val posterKey = try {
                send(posterFile, video.poster.data.size.toLong(), video.poster.contentType, posterPurpose, tickets, uploader, null)
            } finally {
                withContext(Dispatchers.IO) { posterFile.delete() }
            }
            val key = send(videoFile, video.byteSize, video.contentType, purpose, tickets, uploader, progress)
            return UploadedMedia(key, video.width, video.height, video.poster.thumbHash, video.duration, posterKey)
        } finally {
            withContext(Dispatchers.IO) { videoFile.delete() }
        }
    }

    /**
     * A recording (.m4a, AAC 48 kb/s mono), as is: it's already small. A voice intro, or a voice message
     * (`purpose = CHAT_VOICE`).
     */
    suspend fun voice(
        file: String,
        purpose: MediaPurpose = MediaPurpose.VOICE_INTRO,
        tickets: UploadTicketProviding,
        uploader: MediaUploader = MediaUploader.shared,
    ): String {
        val f = File(file)
        val size = withContext(Dispatchers.IO) { f.length() }
        return send(f, size, "audio/mp4", purpose, tickets, uploader, null)
    }

    private suspend fun send(
        file: File, byteSize: Long, contentType: String, purpose: MediaPurpose,
        tickets: UploadTicketProviding, uploader: MediaUploader, progress: ((Double) -> Unit)?,
    ): String {
        var ticket = tickets.ticket(purpose, contentType, byteSize)
        try {
            uploader.upload(file, ticket, progress)
        } catch (e: MediaUploadError.TicketExpired) {
            ticket = tickets.ticket(purpose, contentType, byteSize)
            uploader.upload(file, ticket, progress)
        }
        return ticket.key
    }

    private suspend fun temporaryFile(data: ByteArray, extension: String): File = withContext(Dispatchers.IO) {
        File(temporaryDirectory, "upload-${UUID.randomUUID()}.$extension").apply { writeBytes(data) }
    }
}
