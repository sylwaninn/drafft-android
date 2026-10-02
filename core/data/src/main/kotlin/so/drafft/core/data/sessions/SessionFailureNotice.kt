package so.drafft.core.data.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.drafft.core.data.backend.ServerMessage
import so.drafft.core.model.L

/**
 * A session change the server turned down or couldn't receive: the card is already back as it was
 * ([SessionStore]), and this banner says why, in the app's words for the server's code. Shown above
 * everything, never blocking: it swipes up and leaves on its own. The model half of the banner (the
 * view is the app's `SessionFailureBanner`).
 */
class SessionFailureNotice(private val scope: CoroutineScope) {
    /** The sentence on screen, or null when hidden. */
    var message: String? by mutableStateOf(null)
        private set
    private var hide: Job? = null

    fun show(error: Throwable?) {
        message = text(error)
        hide?.cancel()
        hide = scope.launch {
            delay(DURATION)
            message = null
        }
    }

    fun dismiss() {
        hide?.cancel()
        message = null
    }

    companion object {
        val DURATION = 6.seconds

        /** The server's code in words (`ServerMessage`), the connection when it's that, else a generic line. */
        fun text(error: Throwable?): String {
            // `not_found` from a session call: the session, or the match behind it, is gone.
            if (error != null && ServerMessage.code(error) == "not_found") return L("This session is no longer available.")
            if (error != null) ServerMessage.text(error)?.let { return it }
            if (error is IOException) return L("Check your connection and try again.")
            return ServerMessage.generic
        }
    }
}
