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
        // Applied first: after a refusal not even the refusal is sent (nothing is, and no breadcrumb of it either).
        Telemetry.applyConsent(value)
        Telemetry.track(AnalyticsEvent.AnalyticsConsentChanged(value))
    }

    companion object {
        /**
         * The screen under any sheet or pushed screen: welcome, sign-up, or the current tab. While the
         * tabs are walked invisibly ([prebuilding]) it stays on Discover: nobody sees the others.
         */
        fun baseScreen(app: AppModel, prebuilding: Boolean = false): Screen = when (app.phase) {
            AppModel.Phase.WELCOME -> Screen.WELCOME
            AppModel.Phase.ONBOARDING -> Screen.ONBOARDING
            AppModel.Phase.MAIN -> if (prebuilding) Screen.DISCOVER else when (app.tab) {
                AppModel.Tab.DISCOVER -> Screen.DISCOVER
                AppModel.Tab.LIKES -> Screen.LIKES
                AppModel.Tab.SESSIONS -> Screen.SESSIONS
                AppModel.Tab.CHATS -> Screen.CHATS
                AppModel.Tab.ME -> Screen.ME
            }
        }
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
        // What every event and error report carries about the app's state, and the person's facts again
        // when the language, drafft tempo or the phase changes.
        launch {
            snapshotFlow { Triple(app.language.code, app.premiumUntil != null && app.isPremium, app.phase) }
                .distinctUntilChanged()
                .collect { (language, premium, phase) ->
                    Telemetry.register("app_language", language)
                    Telemetry.register("app_phase", phase.name.lowercase())
                    Telemetry.register("is_premium", premium)
                    Telemetry.describeAccount(mapOf("is_premium" to premium, "language" to language))
                }
        }
    }
}
