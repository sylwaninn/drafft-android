package so.drafft.core.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.PlaybackControl

/**
 * A hold the drafft team put on the account (`profiles.moderation`, set from the dashboard): the
 * whole app gives way to a screen saying why (`AccountHoldView`), until it's lifted.
 */
enum class AccountHold(val rawValue: String) {
    /** Being checked: the app opens again by itself once it's cleared. */
    REVIEW("review"),

    /** A selfie is asked, to check the photos are really them. Sending it moves the account to review. */
    SELFIE("selfie"),

    /** Closed for good: its email and phone number can't sign up again. */
    BANNED("banned"),
}

/**
 * The hold against the server: heard live on the person's own Realtime topic (`UserChannel`), and read
 * again when signing in, coming back to the front, reconnecting, or when the server refuses an action
 * with `moderated`. No restart, no pull to refresh.
 */
class AccountModeration(
    private val backend: Backend,
    private val playback: PlaybackControl? = null,
) {
    /** Shown as is by the hold window. */
    var hold: AccountHold? by mutableStateOf(null)
        private set

    /**
     * Reads the account again (the hold is on its profile row, read in one go by
     * `AppModel.refreshAccount`, which applies it here). A failed read changes nothing (offline:
     * the last known state stays).
     */
    suspend fun load() {
        if (!backend.hasSession()) return
        refresh?.invoke()
    }

    /** Set by the app: reads the account's row and applies it (`AppModel.refreshAccount`). */
    var refresh: (suspend () -> Unit)? = null

    fun apply(new: AccountHold?) {
        if (new == hold) return
        if (new != null) {
            playback?.stop()
            Haptics.warning()
        } else {
            Haptics.success()
        }
        hold = new
    }

    /** Signed out: nothing to hold any more. */
    fun clear() {
        hold = null
    }
}
