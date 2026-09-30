package so.drafft.core.data.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import org.koin.dsl.module
import so.drafft.core.data.AccountModeration
import so.drafft.core.data.AppModel
import so.drafft.core.data.Diagnostics
import so.drafft.core.data.OnboardingStore
import so.drafft.core.data.UserChannel
import so.drafft.core.data.backend.AppOpens
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.backend.DeviceIntegrity
import so.drafft.core.data.backend.ProfileSync
import so.drafft.core.data.backend.Safety
import so.drafft.core.data.location.LocationGate
import so.drafft.core.data.location.LocationOnce
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.audio.AudioPlayback
import so.drafft.core.data.platform.PlaybackControl

/**
 * The app's state and its backend, as singletons (the iPhone's `.shared`). Needs, from elsewhere:
 * a [so.drafft.core.data.backend.BackendConfig] (the app, from its BuildConfig), the platform services
 * (`androidDataModule`: KeyValueStore, LocationProvider, AppInfo, AppLifecycle, DeviceIntegrityProvider,
 * DiagnosticsSource), and the other services AppModel talks to (ChatService, Store, PurchaseCredit,
 * NotificationService, SessionStore, SessionCalendar, PhotoModeration, AudioPlayback).
 */
val dataModule = module {
    single {
        HttpClient(OkHttp) {
            // URLSession's default: a request may wait a minute for the server.
            install(HttpTimeout) {
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
        }
    }
    single { Backend.createClient(get(), get()) }
    // What stops audio when the account is put on hold or signs out.
    single<PlaybackControl> { PlaybackControl { get<AudioPlayback>().stop() } }
    single { Backend(config = get(), client = get(), http = get()) }
    single { ProfileSync(get(), get()) }
    single { Safety(get()) }
    single { AppOpens(get(), get()) }
    single { DeviceIntegrity(get(), get(), get()) }
    single { AccountModeration(get(), getOrNull<PlaybackControl>()) }
    single { OnboardingStore(get(), get(), get()) }
    single { LocationOnce(get(), get()) }
    single { LocationGate(get()) }
    single { UserChannel(get(), get(), get(), get()) }
    single { Diagnostics(get(), get()) }
    single {
        AppModel(
            backend = get(),
            chat = get(),
            store = get(),
            purchaseCredit = get(),
            notifications = get(),
            sessionStore = get(),
            sessionCalendar = get(),
            moderation = get(),
            photoModeration = get(),
            profileSync = get(),
            onboarding = get(),
            safety = get(),
            locationOnce = get(),
            defaults = get(),
            cacheDirectory = File(get<AppInfo>().noBackupDir, "LocalCache"),
            lifecycle = get(),
            playback = getOrNull(),
        )
    }
}
