package so.drafft.core.data.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.chat.ChatService
import so.drafft.core.data.media.MediaURL
import so.drafft.core.data.media.MediaUploads
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.notifications.NotificationService
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.sessions.SessionCalendar
import so.drafft.core.data.sessions.SessionFailureNotice
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.store.PurchaseCredit
import so.drafft.core.data.verification.BackendPhoneVerifier
import so.drafft.core.data.verification.PhoneVerificationModel
import so.drafft.core.data.verification.PhoneVerifying

/** A service's own scope on the main thread (the iPhone's `@MainActor` singletons), never cancelled. */
private fun mainScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * Sessions, purchases, notifications, verification and photo moderation, the platform-neutral half.
 * `androidServicesModule` (src/android) provides their platform pieces: `CalendarWriter`,
 * `LocalNotifications`, `Store` (RevenueCat), the photo and video compressors and the face check.
 */
val sessionsStoreNotificationsModule = module {
    single { SessionFailureNotice(mainScope()) }
    single { SessionCalendar(get(), get(), get()) }
    single { SessionStore(get(), get(), get(), mainScope()) }
    single { PurchaseCredit(get(), get(), get(), mainScope()) }
    single {
        PhotoModeration(
            backend = get(),
            defaults = get(),
            lifecycle = get(),
            network = get(),
            syncPushToken = { get<NotificationService>().syncPushToken() },
            scope = mainScope(),
        )
    }
    single {
        NotificationService(
            backend = get(),
            platform = get(),
            defaults = get(),
            appInfo = get(),
            sessions = get(),
            photoModeration = get(),
            lifecycle = get(),
            chat = { getOrNull<ChatService>() },
            scope = mainScope(),
        )
    }
    // The media helpers called from anywhere (static, like the iPhone's enums) get their pieces at
    // launch. An account on hold can't upload: the hold screen takes over (Backend's app-wide event).
    single(createdAtStart = true) {
        val backend = get<Backend>()
        MediaURL.install(backend, get())
        MediaUploads.onAccountHeld = { backend.post(Backend.Event.ACCOUNT_HELD_BY_SERVER) }
        MediaUploads.temporaryDirectory = get<AppInfo>().cacheDir
        MediaSetup
    }
    single<PhoneVerifying> { BackendPhoneVerifier(get()) }
    // One per phone step (sign-up, You › Phone), on the screen's scope, like the iPhone's `@State` model.
    factory { (scope: CoroutineScope) -> PhoneVerificationModel(get(), get<Backend>().config.smsCodeLifetime, scope) }
}

/** Marks that [MediaURL] and [MediaUploads] are set up (an eager Koin singleton). */
object MediaSetup
