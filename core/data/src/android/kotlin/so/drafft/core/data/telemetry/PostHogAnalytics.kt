package so.drafft.core.data.telemetry

import android.content.Context
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.PostHogBeforeSend
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

// Ports Drafft/Services/Telemetry/PostHogAnalytics.swift.

/**
 * [Telemetry.Analytics] on PostHog, EU cloud. Only what [AnalyticsEvent] and [Screen] describe, plus
 * PostHog's own app lifecycle events (installed, updated, opened, backgrounded). No autocapture of
 * taps or exceptions, no session replay, no surveys: those would record what's on screen, people included.
 *
 * Person profiles exist only for identified accounts ([AnalyticsConsent.GRANTED]); anonymous events
 * stay anonymous events.
 */
class PostHogAnalytics private constructor() : Telemetry.Analytics {
    override fun capture(name: String, properties: Map<String, Any>) {
        PostHog.capture(event = name, properties = properties)
    }

    override fun screen(name: String, properties: Map<String, Any>) {
        PostHog.screen(screenTitle = name, properties = properties)
    }

    override fun identify(id: String) {
        PostHog.identify(distinctId = id)
    }

    override fun setPersonProperties(properties: Map<String, Any>) {
        PostHog.setPersonProperties(userPropertiesToSet = properties)
    }

    override fun register(key: String, value: Any) {
        PostHog.register(key, value)
    }

    override fun reset() {
        PostHog.reset()
    }

    override fun setEnabled(enabled: Boolean) {
        if (enabled) PostHog.optIn() else PostHog.optOut()
    }

    override fun flush() {
        PostHog.flush()
    }

    companion object {
        /** Null without a project key: PostHog stays off. [optedOut]: the person refused, nothing is sent. */
        fun start(context: Context, config: TelemetryConfig, optedOut: Boolean): PostHogAnalytics? {
            if (!config.hasPostHog) return null
            val options = PostHogAndroidConfig(apiKey = config.postHogKey, host = config.postHogHost).apply {
                captureApplicationLifecycleEvents = true
                captureDeepLinks = false
                // One activity, Compose screens: the app sends its own screens (Telemetry.screen).
                captureScreenViews = false
                captureElementInteractions = false
                captureRageClicks = false
                captureDeadClicks = false
                capturePushNotificationOpened = false
                capturePushNotificationSubscriptions = false
                sessionReplay = false
                surveys = false
                personProfiles = PersonProfiles.IDENTIFIED_ONLY
                optOut = optedOut
                // No feature flags yet: each preload is a billed request. Turn on with the first experiment.
                preloadFeatureFlags = false
                sendFeatureFlagEvent = false
                // Errors go to Sentry, with its own scrubbing: none of PostHog's exception autocapture.
                errorTrackingConfig.autoCapture = false
                errorTrackingConfig.captureNativeCrashes = false
                releaseIdentifier = config.release
                debug = config.isDebugBuild && config.environment == "local"
                // Small batches: a session is short and the app may be killed in the background.
                flushAt = 10
                flushIntervalSeconds = 30
                // The last guard on the way out, whatever called capture: the build's environment goes on every
                // event, PostHog's own `$` ones included and whatever the timing (a registered property can miss
                // the first lifecycle events), and the properties whose names are forbidden are dropped.
                val environment = config.environment
                addBeforeSend(
                    PostHogBeforeSend { event ->
                        // A copy: the event's own map may not be writable.
                        val properties = (event.properties ?: mutableMapOf()).toMutableMap()
                        properties["app_environment"] = environment
                        if (!event.event.startsWith("$")) properties.keys.removeAll(PrivacyGuard.forbidden)
                        event.copy(properties = properties)
                    },
                )
            }
            PostHogAndroid.setup(context, options)
            return PostHogAnalytics()
        }
    }
}
