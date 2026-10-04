package so.drafft.core.data.telemetry

/**
 * The screens people see, by a stable id (PostHog's `$screen_name`, Sentry's `screen` tag). Renaming
 * one breaks every chart built on it: add, don't rename.
 */
enum class Screen(val id: String) {
    // Signed out
    WELCOME("welcome"),
    EMAIL_SIGN_UP("email_sign_up"),
    EMAIL_LOG_IN("email_log_in"),
    EMAIL_CODE("email_code"),
    PASSWORD_RESET("password_reset"),
    ONBOARDING("onboarding"),

    // Gates in front of the tabs
    LOCATION_REQUIRED("location_required"),
    TERMS_CONSENT("terms_consent"),
    ACCOUNT_HOLD("account_hold"),

    // The tabs
    DISCOVER("discover"),
    LIKES("likes"),
    SESSIONS("sessions"),
    CHATS("chats"),
    ME("me"),

    // Pushed screens and sheets
    PROFILE_DETAIL("profile_detail"),
    MATCH("match"),
    CHAT("chat"),
    MEDIA_VIEWER("media_viewer"),
    PROPOSE_SESSION("propose_session"),
    SESSION("session"),
    FILTERS("filters"),
    SUPER_LIKE_COMPOSER("super_like_composer"),
    EXTRAS("extras"),
    PAYWALL("paywall"),
    SUBSCRIPTION("subscription"),
    EDIT_PROFILE("edit_profile"),
    NOTIFICATION_SETTINGS("notification_settings"),
    PHONE_VERIFICATION("phone_verification"),
    SELFIE_VERIFICATION("selfie_verification"),
    REPORT("report"),
    SUPPORT("support"),
    ACCOUNT("account"),
    INFO("info"),
}
