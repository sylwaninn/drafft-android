package so.drafft.core.data.backend

import java.io.IOException
import kotlin.test.assertEquals
import org.junit.Test
import so.drafft.core.data.media.MediaUploadError
import so.drafft.core.model.L

// How a failed action is put in words (`ServerMessage.failure`).
class ServerMessageFailureTest {
    private val connection = L("Couldn't connect. Check your connection and try again.")

    @Test
    fun requestThatNeverGotThroughSaysConnection() {
        assertEquals(connection, ServerMessage.failure(IOException("offline")))
        // Wrapped by a client library: still the connection.
        assertEquals(connection, ServerMessage.failure(IllegalStateException("send failed", IOException("timeout"))))
    }

    @Test
    fun knownRefusalHasItsWords() {
        val words = ServerMessage.text(forCode = "media_limit")
        assertEquals(words, ServerMessage.failure(Backend.BackendError.Http(400, "media_limit")))
        assertEquals(words, ServerMessage.failure(MediaUploadError.Rejected("media_limit")))
    }

    @Test
    fun anythingElseIsGenericNeverTheServerReply() {
        assertEquals(ServerMessage.generic, ServerMessage.failure(Backend.BackendError.Http(500, "column x does not exist")))
        assertEquals(ServerMessage.generic, ServerMessage.failure(Backend.BackendError.Http(400, "something_new")))
        assertEquals(ServerMessage.generic, ServerMessage.failure(MediaUploadError.Http(502)))
        assertEquals(ServerMessage.generic, ServerMessage.failure(IllegalStateException("boom")))
    }
}
