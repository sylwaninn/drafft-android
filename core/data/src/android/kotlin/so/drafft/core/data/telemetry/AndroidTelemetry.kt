package so.drafft.core.data.telemetry

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import so.drafft.core.data.platform.KeyValueStore

/**
 * Installs [Telemetry]'s engines on Android, in two steps of `Application.onCreate`. Starting never
 * throws: an SDK that fails to start leaves that service off and the app running.
 */
object AndroidTelemetry {
    /** First thing at launch, before anything that could crash: Sentry and the log bridge. */
    fun startCrashReporting(context: Context, config: TelemetryConfig) = safely {
        // Screens are published after the UI pass that changed them (ScreenTracker).
        val main = Handler(Looper.getMainLooper())
        ScreenTracker.post = { main.post(it) }
        SentryCrashReporter.start(context, config)?.let { Telemetry.crashes = it }
        TelemetryLogHandler.install()
        Telemetry.register("app_environment", config.environment)
    }

    /** Once the saved settings can be read: PostHog, with the person's saved answer. */
    fun startAnalytics(context: Context, config: TelemetryConfig, defaults: KeyValueStore) = safely {
        val consent = AnalyticsConsent.load(defaults)
        PostHogAnalytics.start(context, config, optedOut = consent == AnalyticsConsent.DENIED)?.let { Telemetry.analytics = it }
        Telemetry.applyConsent(consent)
        // Registered again now that PostHog is here: it goes with every event.
        Telemetry.register("app_environment", config.environment)
        flushInTheBackground()
    }

    /**
     * What's waiting goes when the app leaves the front: PostHog's own lifecycle integration only notes
     * "Application Backgrounded", and the process may be killed before its next scheduled flush.
     */
    private fun flushInTheBackground() {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = Telemetry.flush()
        }
        val add = { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        if (Looper.myLooper() == Looper.getMainLooper()) add() else Handler(Looper.getMainLooper()).post(add)
    }
}
