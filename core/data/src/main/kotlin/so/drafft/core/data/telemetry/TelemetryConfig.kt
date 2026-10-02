package so.drafft.core.data.telemetry

import java.net.URI

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
    /** "production", "staging" or "local" ([environmentID] never gives anything else). */
    val environment: String,
    /** "0.1.0" */
    val version: String,
    /** The build number. */
    val build: String,
    val isDebugBuild: Boolean,
) {
    /** Sentry's release name, the one the build uploads its mapping files under. */
    val release: String get() = "so.drafft.app@$version+$build"

    /** Off without a DSN, and with a DSN outside Sentry's EU region (the data stays in the EU). */
    val hasSentry: Boolean get() = isEUDSN(sentryDSN)

    /** Off without a key, and with a host other than PostHog's EU cloud. */
    val hasPostHog: Boolean get() = postHogKey.isNotBlank() && isEUHost(postHogHost)

    /**
     * Share of traces kept (performance): every one in staging and local, where traffic is small and
     * each slow request matters; 20% in production, enough for percentiles at a fraction of the quota.
     */
    val tracesSampleRate: Double get() = if (environment == "production") 0.2 else 1.0

    /**
     * Share of lone backend requests traced (a request inside a sampled trace always is): dozens per
     * session, so 2% in production still gives every endpoint's percentiles at scale.
     */
    val requestSampleRate: Double get() = if (environment == "production") 0.02 else 1.0

    /** Share of traced sessions profiled (the code paths behind a slow trace). */
    val profileSampleRate: Double get() = if (environment == "production") 0.05 else 0.0

    companion object {
        /**
         * The three environments a build can be. Anything else (a typo, a flavor added later) is "unknown":
         * a misconfigured build never counts as production in the dashboards.
         */
        fun environmentID(raw: String): String = if (raw in setOf("production", "staging", "local")) raw else "unknown"

        private val euDSN = Regex("^https://[0-9a-f]+@o[0-9]+\\.ingest\\.de\\.sentry\\.io/[0-9]+$")

        /** `https://<key>@o<org>.ingest.de.sentry.io/<project>`: a DSN of Sentry's EU region. */
        fun isEUDSN(dsn: String): Boolean = euDSN.matches(dsn)

        /**
         * `https://eu.i.posthog.com`, by its parsed host (not a prefix: `https://eu.evil.com` or
         * `https://eu.i.posthog.com@evil.com` are not PostHog's EU cloud). No user, port, path (but a lone
         * `/`), query or fragment.
         */
        fun isEUHost(host: String): Boolean = runCatching {
            val uri = URI(host)
            uri.scheme == "https" && uri.host == "eu.i.posthog.com" && uri.userInfo == null && uri.port == -1 &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") && uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
    }
}
