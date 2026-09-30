package so.drafft.core.data.platform

/**
 * The phone's notifications (the iPhone's `UNUserNotificationCenter` and `registerForRemoteNotifications`).
 * Android: `NotificationManagerCompat` with channels, the POST_NOTIFICATIONS permission and Firebase
 * Cloud Messaging (`AndroidLocalNotifications`, `DrafftMessagingService`).
 */
interface LocalNotifications {
    /** Android notification channels: each is its own switch in the system's settings for the app. */
    enum class Channel(val id: String) {
        MATCHES("matches"),
        LIKES("likes"),
        MESSAGES("messages"),
        SESSIONS("sessions"),

        /** The account's own news: a photo refused, a hold, drafft tempo's weekly boost. */
        ACCOUNT("account"),
    }

    /** A notification shown now. [info] comes back to `NotificationService.didReceive` when it's tapped. */
    data class Notification(
        val title: String,
        val body: String,
        val channel: Channel,
        /** Notifications of one conversation are grouped (the iPhone's `threadIdentifier`). */
        val threadID: String? = null,
        val info: Map<String, String> = emptyMap(),
        /** A profile photo (a file path or a bundled image name) shown as the notification's picture. */
        val photo: String? = null,
    )

    /** Where the permission stands right now (read again by the caller whenever the app comes back). */
    fun permission(): PermissionStatus

    /** The system prompt (Android 13 and later; before that notifications are on unless turned off). */
    suspend fun requestPermission(): Boolean

    /** The app's notification settings page. */
    fun openSettings()

    /** The Firebase Cloud Messaging token, or null (no Firebase configuration, no Play services, offline). */
    suspend fun pushToken(): String?

    fun post(notification: Notification)

    /** Calls [action] each time the app comes back to the front (from Settings included). */
    fun onForeground(action: () -> Unit)
}
