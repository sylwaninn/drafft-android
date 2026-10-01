package so.drafft.core.data.telemetry

import so.drafft.core.data.platform.KeyValueStore

/**
 * What the person said about usage analytics (PostHog), kept on the phone.
 *
 * - [UNKNOWN] (not asked yet): events go without the account, under a random id of this install that
 *   a sign-out renews. Audience measurement the person can object to (the privacy policy says how).
 * - [GRANTED]: events are linked to the account's id (the same pseudonymous id as Sentry, RevenueCat
 *   and the backend), so a journey can be followed across devices and support can find it.
 * - [DENIED]: nothing goes to PostHog. Crash and error reports (Sentry) still go: they keep the
 *   service working and safe, and carry no usage.
 *
 * Asking belongs to You › Privacy & data, once the iPhone catalog has its words (docs/telemetry.md).
 */
enum class AnalyticsConsent(val id: String) {
    UNKNOWN("unknown"),
    GRANTED("granted"),
    DENIED("denied");

    companion object {
        const val KEY = "telemetry.analyticsConsent"

        fun load(defaults: KeyValueStore): AnalyticsConsent =
            defaults.getString(KEY)?.let { saved -> entries.firstOrNull { it.id == saved } } ?: UNKNOWN

        fun save(value: AnalyticsConsent, defaults: KeyValueStore) {
            defaults.putString(KEY, value.id)
        }
    }
}
