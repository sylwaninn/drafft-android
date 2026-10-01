package so.drafft.core.data.telemetry

import androidx.compose.runtime.snapshotFlow
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import so.drafft.core.data.AppModel
import so.drafft.core.data.backend.Backend
import so.drafft.core.data.platform.KeyValueStore

/**
 * Keeps [Telemetry] in step with the account: who is signed in (Supabase Auth's session, the one
 * source), and the few facts dashboards split by (language, drafft tempo, where the person is in the
 * app). Started once by the root, for as long as the app runs.
 */
class TelemetrySession(
    private val backend: Backend,
    private val defaults: KeyValueStore,
) {
    val consent: AnalyticsConsent get() = Telemetry.consent

    /** The person's answer (You › Privacy & data), kept on the phone and applied at once. */
    fun setConsent(value: AnalyticsConsent) {
        if (value == Telemetry.consent) return
        AnalyticsConsent.save(value, defaults)
        // Applied first: after a refusal not even the refusal goes to PostHog (Sentry's breadcrumbs keep it).
        Telemetry.applyConsent(value)
        Telemetry.track(AnalyticsEvent.AnalyticsConsentChanged(value))
    }

    suspend fun watch(app: AppModel) = coroutineScope {
        launch {
            backend.client.auth.sessionStatus.collect { status ->
                when (status) {
                    // A session read back from the phone may not carry its user yet: the account's id
                    // then comes from Auth, and nothing changes while neither has it.
                    is SessionStatus.Authenticated ->
                        (status.session.user?.id ?: backend.userID?.toString())?.let { Telemetry.signedIn(it.lowercase()) }
                    is SessionStatus.NotAuthenticated -> Telemetry.signedIn(null)
                    else -> Unit
                }
            }
        }
        launch {
            snapshotFlow { app.language.code }.distinctUntilChanged().collect { Telemetry.register("app_language", it) }
        }
        launch {
            snapshotFlow { app.phase }.distinctUntilChanged().collect { Telemetry.register("app_phase", it.name.lowercase()) }
        }
        launch {
            snapshotFlow { app.premiumUntil != null && app.isPremium }.distinctUntilChanged().collect { premium ->
                Telemetry.register("is_premium", premium)
                Telemetry.describeAccount(mapOf("is_premium" to premium, "language" to app.language.code))
            }
        }
    }
}
