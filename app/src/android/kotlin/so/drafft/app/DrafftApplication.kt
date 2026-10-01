package so.drafft.app

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import so.drafft.app.platform.AndroidHaptics
import so.drafft.app.platform.IcuDates
import so.drafft.app.platform.ProcessForegroundReturns
import so.drafft.core.data.AppModel
import so.drafft.core.data.Diagnostics
import so.drafft.core.data.backend.BackendConfig
import so.drafft.core.data.di.androidChatModule
import so.drafft.core.data.di.androidDataModule
import so.drafft.core.data.di.androidServicesModule
import so.drafft.core.data.di.chatModule
import so.drafft.core.data.di.dataModule
import so.drafft.core.data.di.sessionsStoreNotificationsModule
import so.drafft.core.data.platform.ForegroundReturns
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.data.telemetry.AndroidTelemetry
import so.drafft.core.data.telemetry.TelemetryConfig
import so.drafft.core.model.AppLanguage
import so.drafft.core.model.Localization
import so.drafft.core.ui.platform.installDrafftUi
import so.drafft.core.ui.platform.installImages
import so.drafft.core.ui.theme.LanguageObservation

/** The iPhone's `DrafftApp.init`: diagnostics, images, the store, dates, haptics, the language. */
class DrafftApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Crash reporting first: a crash anywhere in the launch below is caught.
        val telemetry = telemetryConfig()
        AndroidTelemetry.startCrashReporting(this, telemetry)
        val koin = startKoin {
            androidContext(this@DrafftApplication)
            modules(
                appModule,
                dataModule, androidDataModule,
                chatModule, androidChatModule,
                sessionsStoreNotificationsModule, androidServicesModule,
            )
        }.koin
        // The language picked last (the phone's, the first time), before the first screen draws.
        val saved = koin.get<KeyValueStore>().getString(AppModel.LANGUAGE_KEY)
        Localization.use(AppLanguage.fromCode(saved) ?: AppLanguage.deviceDefault(currentLocales()))
        // Text in the new language as soon as it's picked (texts observe it, see LanguageObservation).
        LanguageObservation.install()
        IcuDates.install()
        Haptics.engine = AndroidHaptics(this)
        installDrafftUi()
        installImages(this)
        AndroidTelemetry.startAnalytics(this, telemetry, koin.get())
        koin.get<Diagnostics>().start()
        // Starts the foreground refresh and the push token fetch.
        koin.get<so.drafft.core.data.notifications.NotificationService>()
    }

    private fun telemetryConfig(): TelemetryConfig {
        val info = packageManager.getPackageInfo(packageName, 0)
        return TelemetryConfig(
            sentryDSN = BuildConfig.SENTRY_DSN,
            postHogKey = BuildConfig.POSTHOG_API_KEY,
            postHogHost = BuildConfig.POSTHOG_HOST,
            environment = BuildConfig.ENVIRONMENT.ifEmpty { "production" },
            version = info.versionName ?: "",
            build = info.longVersionCode.toString(),
            isDebugBuild = BuildConfig.DEBUG,
        )
    }

    private fun currentLocales(): List<java.util.Locale> {
        val list = resources.configuration.locales
        return List(list.size()) { list[it] }
    }
}

/** What only the app knows: the build's backend (BuildConfig per flavor) and process-level events. */
val appModule = module {
    single {
        BackendConfig(
            url = BuildConfig.SUPABASE_URL,
            publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            revenueCatAPIKey = BuildConfig.REVENUECAT_API_KEY,
            smsCodeLifetime = BuildConfig.SMS_CODE_LIFETIME.toDouble(),
            turnstileSiteKey = BuildConfig.TURNSTILE_SITE_KEY,
            environment = BuildConfig.ENVIRONMENT,
        )
    }
    single<ForegroundReturns> { ProcessForegroundReturns() }
}
