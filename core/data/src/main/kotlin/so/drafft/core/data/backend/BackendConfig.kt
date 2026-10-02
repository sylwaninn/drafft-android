package so.drafft.core.data.backend

/**
 * The drafft backend (Supabase, EU): production or staging, picked by the build flavor (the
 * app's `BuildConfig`, provided through Koin). The publishable key is public by design: it only
 * identifies the project, and row-level security decides what each person can do.
 * Never put a secret key (sb_secret_..., service_role) in the app.
 */
data class BackendConfig(
    val url: String,
    val publishableKey: String,
    /** RevenueCat public SDK key of the matching project (its webhook feeds this backend). */
    val revenueCatAPIKey: String,
    /** How long an SMS code works: Auth's SMS OTP expiry, per environment (`SMS_CODE_LIFETIME`, seconds). */
    val smsCodeLifetime: Double = 600.0,
    /** Cloudflare Turnstile public site key, for the support form sent signed out (`TURNSTILE_SITE_KEY`). */
    val turnstileSiteKey: String = "",
    /** "" for production, "staging". */
    val environment: String = "",
    /**
     * Number of the Google Cloud project linked in Play Console (App integrity), for Play Integrity
     * (`PLAY_INTEGRITY_PROJECT_NUMBER`). Null: no device attestation.
     */
    val playIntegrityProjectNumber: Long? = null,
) {
    /** How long an email code works (Auth's email OTP expiry, 1 hour everywhere). */
    val emailCodeLifetime: Double get() = 3600.0

    val functionsURL: String get() = url.trimEnd('/') + "/functions/v1"

    /** What this build lacks to reach a backend (config/<flavor>.properties). Empty when configured. */
    val missing: List<String>
        get() = buildList {
            val file = "config/${environment.ifEmpty { "production" }}.properties"
            if (url.isBlank()) add("SUPABASE_URL ($file)")
            if (publishableKey.isBlank()) add("SUPABASE_PUBLISHABLE_KEY ($file)")
        }

    /** False: the app stops at launch and says what's [missing]. */
    val isConfigured: Boolean get() = missing.isEmpty()
}
