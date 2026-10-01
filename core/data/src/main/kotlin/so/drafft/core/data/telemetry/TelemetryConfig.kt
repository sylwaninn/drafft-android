package so.drafft.core.data.telemetry

/**
 * The build's telemetry keys (config/<flavor>.properties, through BuildConfig). Both are public by
 * design: a Sentry DSN only lets an app send events, a PostHog project key only lets it capture.
 * An empty value turns that service off: the app works the same, nothing is sent.
 */
data class TelemetryConfig(
    val sentryDSN: String,
    val postHogKey: String,
    /** PostHog's EU cloud (`https://eu.i.posthog.com`): the data stays in the EU, like the backend. */
    val postHogHost: String,
    /** "production", "staging" or "local". */
    val environment: String,
    /** "0.1.0" */
    val version: String,
    /** The build number. */
    val build: String,
    val isDebugBuild: Boolean,
) {
    /** Sentry's release name, the one the build uploads its mapping files under. */
    val release: String get() = "so.drafft.app@$version+$build"

    val hasSentry: Boolean get() = sentryDSN.isNotBlank()
    val hasPostHog: Boolean get() = postHogKey.isNotBlank() && postHogHost.startsWith("https://")

    /**
     * Share of traces kept (performance): every one in staging and local, where traffic is small and
     * each slow request matters; 20% in production, enough for percentiles at a fraction of the quota.
     */
    val tracesSampleRate: Double get() = if (environment == "production") 0.2 else 1.0

    /** Share of traced sessions profiled (the code paths behind a slow trace). */
    val profileSampleRate: Double get() = if (environment == "production") 0.05 else 0.0
}
