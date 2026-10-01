package so.drafft.core.data.telemetry

import android.content.Context
import so.drafft.core.data.platform.KeyValueStore

/** Installs [Telemetry]'s engines on Android, in two steps of `Application.onCreate`. */
object AndroidTelemetry {
    /** First thing at launch, before anything that could crash: Sentry and the log bridge. */
    fun startCrashReporting(context: Context, config: TelemetryConfig) {
        SentryCrashReporter.start(context, config)?.let { Telemetry.crashes = it }
        TelemetryLogHandler.install()
        Telemetry.register("app_environment", config.environment)
    }

    /** Once the saved settings can be read: PostHog, with the person's saved answer. */
    fun startAnalytics(context: Context, config: TelemetryConfig, defaults: KeyValueStore) {
        val consent = AnalyticsConsent.load(defaults)
        PostHogAnalytics.start(context, config, optedOut = consent == AnalyticsConsent.DENIED)?.let { Telemetry.analytics = it }
        Telemetry.applyConsent(consent)
        // Registered again now that PostHog is here: it goes with every event.
        Telemetry.register("app_environment", config.environment)
    }
}
