package so.drafft.core.data.backend

/**
 * The drafft backend (Supabase, EU): production, staging or local, picked by the build flavor (the
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
    /** "" for production, "staging", "local". */
    val environment: String = "",
) {
    /** How long an email code works (Auth's email OTP expiry, 1 hour everywhere). */
    val emailCodeLifetime: Double get() = 3600.0

    val functionsURL: String get() = url.trimEnd('/') + "/functions/v1"

    /**
     * What this build lacks to reach a backend: the
     * local flavor needs its machine's URL and key in local.private.properties
     * (scripts/local-backend.sh), the others config/<flavor>.properties. Empty when configured.
     */
    val missing: List<String>
        get() = buildList {
            val file = if (environment == "local") "local.private.properties, run scripts/local-backend.sh"
            else "config/${environment.ifEmpty { "production" }}.properties"
            if (url.isBlank()) add("SUPABASE_URL ($file)")
            if (publishableKey.isBlank()) add("SUPABASE_PUBLISHABLE_KEY ($file)")
        }

    /** False: the app stops at launch and says what's [missing]. */
    val isConfigured: Boolean get() = missing.isEmpty()
}
