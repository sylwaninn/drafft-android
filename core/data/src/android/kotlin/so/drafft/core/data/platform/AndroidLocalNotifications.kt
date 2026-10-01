package so.drafft.core.data.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await
import so.drafft.core.model.L

/**
 * [LocalNotifications] on `NotificationManagerCompat`, with one channel per kind (matches, likes,
 * messages, sessions, the account's news), the POST_NOTIFICATIONS permission (Android 13+) and FCM for
 * the push token.
 *
 * A notification's tap opens the app's launcher activity with the notification's data as extras
 * ([EXTRA_MARKER] set); `MainActivity` hands them to `NotificationService.didReceive` (see
 * `NotificationIntents.handle`). FCM's own notifications (app in the background) open it the same way,
 * with the push's data as extras.
 */
class AndroidLocalNotifications(
    private val context: Context,
    private val defaults: KeyValueStore,
) : LocalNotifications {
    private val manager = NotificationManagerCompat.from(context)
    private val nextID = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())

    override fun permission(): PermissionStatus {
        // Read at launch and at every return to the app: the channels exist (in the app's language) before
        // the first push, which FCM files by `channel_id` itself while the app is in the background, or in
        // its fallback channel when that one doesn't exist yet.
        ensureChannels()
        return status()
    }

    private fun status(): PermissionStatus = when {
        manager.areNotificationsEnabled() -> PermissionStatus.ALLOWED
        // Before Android 13 there's no prompt: off means turned off in Settings.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> PermissionStatus.DENIED
        defaults.getBoolean(DENIED_KEY) == true -> PermissionStatus.DENIED
        // Granted but every notification switched off in Settings.
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ->
            PermissionStatus.DENIED
        else -> PermissionStatus.NOT_ASKED
    }

    override suspend fun requestPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return manager.areNotificationsEnabled()
        when (PermissionPrompter.request(listOf(Manifest.permission.POST_NOTIFICATIONS))) {
            PermissionPrompter.Result.GRANTED -> defaults.remove(DENIED_KEY)
            PermissionPrompter.Result.DENIED_FOR_GOOD -> defaults.putBoolean(DENIED_KEY, true)
            PermissionPrompter.Result.DENIED -> Unit
        }
        return manager.areNotificationsEnabled()
    }

    override fun openSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override suspend fun pushToken(): String? = try {
        FirebaseMessaging.getInstance().token.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // No Firebase configuration in this build (google-services.json), no Play services, or offline.
        null
    }

    // Checked just below (notifications on), and a permission taken back meanwhile is caught.
    @SuppressLint("MissingPermission")
    override fun post(notification: LocalNotifications.Notification) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannels()
        val id = nextID.incrementAndGet()
        val tap = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_MARKER, true)
            notification.info.forEach { (k, v) -> putExtra(k, v) }
        }
        val pending = PendingIntent.getActivity(
            context, id, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, notification.channel.id)
            .setSmallIcon(smallIcon())
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(
                if (notification.channel == LocalNotifications.Channel.MESSAGES) NotificationCompat.CATEGORY_MESSAGE
                else NotificationCompat.CATEGORY_SOCIAL,
            )
        notification.threadID?.let { builder.setGroup(it) }
        notification.photo?.let(::thumbnail)?.let { builder.setLargeIcon(it) }
        try {
            manager.notify(id, builder.build())
        } catch (_: SecurityException) {
            // The permission was taken back meanwhile.
        }
    }

    override fun onForeground(action: () -> Unit) = AndroidForeground.observe(action)

    /** The channels, named in the app's language (renamed when it changes: same ids). */
    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        for (channel in LocalNotifications.Channel.entries) {
            val name = when (channel) {
                LocalNotifications.Channel.MATCHES -> L("New matches")
                LocalNotifications.Channel.LIKES -> L("Likes")
                LocalNotifications.Channel.MESSAGES -> L("Messages")
                LocalNotifications.Channel.SESSIONS -> L("Sessions")
                LocalNotifications.Channel.ACCOUNT -> L("Account")
            }
            system.createNotificationChannel(NotificationChannel(channel.id, name, NotificationManager.IMPORTANCE_HIGH))
        }
    }

    /** The app's monochrome notification icon (`ic_notification`), else its launcher icon. */
    private fun smallIcon(): Int {
        val res = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
        return if (res != 0) res else context.applicationInfo.icon
    }

    /**
     * Square, small copy of a profile photo (a file on the phone or a bundled image), as the
     * notification's picture.
     */
    private fun thumbnail(photo: String): Bitmap? {
        val source = if (photo.startsWith("/")) {
            BitmapFactory.decodeFile(photo)
        } else {
            val res = context.resources.getIdentifier(photo, "drawable", context.packageName)
            if (res == 0) null else BitmapFactory.decodeResource(context.resources, res)
        } ?: return null
        val side = 300
        val shorter = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - shorter) / 2, (source.height - shorter) / 2, shorter, shorter)
        return Bitmap.createScaledBitmap(cropped, side, side, true)
    }

    companion object {
        /** Set on the intent of a notification drafft posted. */
        const val EXTRA_MARKER = "so.drafft.notification"
        private const val DENIED_KEY = "permission.notifications.deniedForGood"
    }
}
