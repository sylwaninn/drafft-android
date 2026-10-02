package so.drafft.core.data.platform

// The protocol; `PermissionButton` is core:ui's.

/** Where a system permission stands, in the three cases a screen acts on. */
enum class PermissionStatus {
    /** Never asked (or asked and dismissed, Android can still prompt): the system prompt can still show. */
    NOT_ASKED,
    ALLOWED,

    /** Refused for good: the system won't prompt again, only Settings can turn it on. */
    DENIED,
}

/**
 * A system permission the app asks for. Notifications and the calendar conform today; location, camera,
 * microphone and photos can join the same way. A conformer keeps [permission] current by itself (it's
 * snapshot state, re-read each time the app comes back to the front, from Settings included), so screens
 * only read it and never refresh it themselves.
 *
 * The action for a permission that isn't on: [requestPermission] the first time, then [openSettings]
 * once refused (never a dead, disabled button).
 */
interface SystemPermission {
    val permission: PermissionStatus

    /** The system prompt (through [PermissionPrompter]). Returns whether it's allowed now. */
    suspend fun requestPermission(): Boolean

    /** This permission's own page in Settings when Android has one, the app's page otherwise. */
    fun openSettings()
}

/**
 * Shows Android's runtime permission prompt. The prompt needs an Activity, so the app installs the
 * [engine] from `MainActivity` (an `ActivityResultLauncher` for `RequestMultiplePermissions`); services
 * call [request] from [SystemPermission.requestPermission]. Without an activity on screen the request
 * answers [Result.DENIED] at once and nothing is recorded as refused.
 */
object PermissionPrompter {
    enum class Result {
        GRANTED,

        /** Dismissed or refused once: Android can still show the prompt. */
        DENIED,

        /** Refused for good ("Don't allow" twice, or "Don't ask again"): only Settings now. */
        DENIED_FOR_GOOD,
    }

    fun interface Engine {
        /** Asks for all of [permissions] (Android permission names); granted only when every one is. */
        suspend fun request(permissions: List<String>): Result
    }

    @Volatile
    var engine: Engine = Engine { Result.DENIED }

    suspend fun request(permissions: List<String>): Result = engine.request(permissions)
}
