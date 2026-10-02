package so.drafft.core.data.notifications

import android.content.Intent
import android.os.Bundle
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import so.drafft.core.data.platform.AndroidLocalNotifications

/**
 * Firebase Cloud Messaging: a new token is registered with the backend ([NotificationService.didRegister]),
 * and a push that arrives while the app is running goes through [NotificationService.willPresent] before
 * it's shown. With the app in the background, FCM shows a push's notification itself and a tap opens
 * the app with its data as extras ([handleNotificationTap]).
 *
 * Declared by the app's manifest (service `so.drafft.core.data.notifications.DrafftMessagingService`,
 * not exported, intent filter `com.google.firebase.MESSAGING_EVENT`).
 */
class DrafftMessagingService : FirebaseMessagingService(), KoinComponent {
    private val notifications: NotificationService by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onNewToken(token: String) {
        scope.launch { notifications.didRegister(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val info = message.data
        val stream = info["sender"] == "stream.chat"
        // Stream's message pushes carry no text of ours unless the push template gives one: the same
        // words as a message from the app itself.
        if (stream && info["type"] != null && info["type"] != "message.new") return
        // FCM calls this on its own worker thread and expects the work done when it returns.
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                if (stream && !notifications.messages) return@withContext
                val show = withTimeoutOrNull(8.seconds) { notifications.willPresent(info) } ?: true
                if (!show) return@withContext
                val language = notifications.language
                val title = message.notification?.title ?: info["title"]
                    ?: NotificationText.title(NotificationText.Kind.Message, info["sender_name"].orEmpty(), language)
                val body = message.notification?.body ?: info["body"]
                    ?: NotificationText.body(NotificationText.Kind.Message, "", language)
                notifications.present(title, body, info)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * A notification's tap, from `MainActivity` (`onCreate` on a cold start, `onNewIntent` when the app was
 * running): its data goes to [NotificationService.didReceive], which parses where it leads ([PushTap]) and
 * keeps it until the tabs are on screen. Whether the intent came from a notification. The extras are
 * cleared once handled, and an intent relaunched from Recents (which carries the old extras again) is
 * ignored: a tap opens its place once.
 */
fun NotificationService.handleNotificationTap(intent: Intent?): Boolean {
    if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
    val extras = intent.extras ?: return false
    val fromPush = extras.containsKey("google.message_id") || extras.containsKey(AndroidLocalNotifications.EXTRA_MARKER)
    if (!fromPush) return false
    val info = extras.keySet()
        .filterNot { it.startsWith("google.") || it.startsWith("gcm.") || it == "from" || it == "collapse_key" }
        .mapNotNull { key -> extras.getString(key)?.let { key to it } }
        .toMap()
    intent.replaceExtras(Bundle())
    didReceive(info)
    return true
}
