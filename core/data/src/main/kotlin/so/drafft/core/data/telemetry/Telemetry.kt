package so.drafft.core.data.telemetry

import kotlin.coroutines.cancellation.CancellationException

// The iPhone app has the same object, events and rules (Drafft/Services/Telemetry/Core/Telemetry.swift).
// docs/telemetry.md is the plan both apps follow (events, properties, privacy).

/**
 * What leaves the phone about how the app behaves, callable from anywhere (model and screens), like
 * [so.drafft.core.data.platform.Haptics]. Two services, each with its job:
 *
 * - **Sentry** ([crashes]): crashes, ANRs, unexpected errors, slow requests, the app's own logs. For
 *   the service's reliability and security (legitimate interest, named in the privacy policy): always
 *   on in a build that has a DSN, tied to the account's id, never to a name, an email or a number.
 * - **PostHog** ([analytics]): how the app is used, as the events of [AnalyticsEvent], nothing typed
 *   by people. Linked to the account only with the person's consent ([AnalyticsConsent]); without it
 *   the events stay anonymous (a random id of this install), and a refusal sends nothing at all.
 *
 * Both engines are installed at launch (`installTelemetry`, src/android). Until then, in unit tests and
 * in a build without keys, every call does nothing. [PrivacyGuard] checks everything sent.
 */
object Telemetry {
    /** Sentry's side. */
    interface CrashReporter {
        /** The account's id (the Supabase user id), or null signed out. */
        fun setUser(id: String?)
        fun setTag(key: String, value: String?)
        fun breadcrumb(crumb: Breadcrumb)
        fun capture(error: Throwable, report: ErrorReport)
        fun message(text: String, level: Level, report: ErrorReport)
        /** A structured log line (Sentry Logs), searchable with the events of the same session. */
        fun log(level: Level, text: String, attributes: Map<String, Any>)
        /** A timed operation (a request, a load): a child of the running trace, or a trace of its own. */
        fun startSpan(operation: String, description: String): Span
    }

    /** PostHog's side. */
    interface Analytics {
        fun capture(name: String, properties: Map<String, Any>)
        fun screen(name: String, properties: Map<String, Any>)
        /** Links this install's events to the account (only with [AnalyticsConsent.GRANTED]). */
        fun identify(id: String, properties: Map<String, Any>)
        fun setPersonProperties(properties: Map<String, Any>)
        /** A property sent with every event from now on ("super property"). */
        fun register(key: String, value: Any)
        /** A new anonymous id: what follows can't be tied to what came before (sign-out, deletion). */
        fun reset()
        /** Whether anything is sent: off while the person refused. */
        fun setEnabled(enabled: Boolean)
        fun flush()
    }

    interface Span {
        fun setData(key: String, value: Any)
        fun finish(ok: Boolean)
    }

    enum class Level { DEBUG, INFO, WARNING, ERROR, FATAL }

    /** A step that comes with the next error report (Sentry's breadcrumb). */
    data class Breadcrumb(
        /** "navigation", "http", "ui", "product", "auth", "log"... */
        val category: String,
        val message: String,
        val level: Level = Level.INFO,
        val data: Map<String, Any> = emptyMap(),
    )

    /** Where an error happened, to group and search them. */
    data class ErrorReport(
        /** The feature: "discover", "chat", "purchase"... (Sentry tag `area`). */
        val area: String,
        /** What it was doing: "swipe", "load_deck"... (Sentry tag `action`). */
        val action: String? = null,
        val extra: Map<String, Any> = emptyMap(),
        /** Groups events that are the same problem whatever the message says. */
        val fingerprint: List<String>? = null,
    )

    private object NoCrashes : CrashReporter {
        override fun setUser(id: String?) = Unit
        override fun setTag(key: String, value: String?) = Unit
        override fun breadcrumb(crumb: Breadcrumb) = Unit
        override fun capture(error: Throwable, report: ErrorReport) = Unit
        override fun message(text: String, level: Level, report: ErrorReport) = Unit
        override fun log(level: Level, text: String, attributes: Map<String, Any>) = Unit
        override fun startSpan(operation: String, description: String): Span = NoSpan
    }

    private object NoAnalytics : Analytics {
        override fun capture(name: String, properties: Map<String, Any>) = Unit
        override fun screen(name: String, properties: Map<String, Any>) = Unit
        override fun identify(id: String, properties: Map<String, Any>) = Unit
        override fun setPersonProperties(properties: Map<String, Any>) = Unit
        override fun register(key: String, value: Any) = Unit
        override fun reset() = Unit
        override fun setEnabled(enabled: Boolean) = Unit
        override fun flush() = Unit
    }

    private object NoSpan : Span {
        override fun setData(key: String, value: Any) = Unit
        override fun finish(ok: Boolean) = Unit
    }

    @Volatile
    var crashes: CrashReporter = NoCrashes

    @Volatile
    var analytics: Analytics = NoAnalytics

    /** Guards the state below: the identity transitions can come from any thread (Auth, the model, a screen). */
    private val lock = Any()

    /** What the person said about usage analytics (applied by [applyConsent]). */
    @Volatile
    var consent: AnalyticsConsent = AnalyticsConsent.UNKNOWN
        private set

    /** The signed-in account, kept to identify again when the consent changes. */
    private var userID: String? = null

    private var identified = false

    /** The super properties, sent again after a reset (PostHog's reset forgets them). */
    private val registered = LinkedHashMap<String, Any>()

    /** The last person properties described, sent again once the account is identified. */
    private val person = LinkedHashMap<String, Any>()

    /** The screen on show, for the next error report and the events' `screen` property. */
    @Volatile
    var currentScreen: Screen? = null
        private set

    // Product analytics

    /** A product event: to PostHog (if allowed), and as a breadcrumb for the next error report. */
    fun track(event: AnalyticsEvent) {
        // A cancelled task is not a failure: the screen went away, nothing happened worth counting.
        if (event.properties["reason"] == ErrorKind.CANCELLED.id) return
        val properties = PrivacyGuard.properties(event.name, event.properties)
        crashes.breadcrumb(Breadcrumb("product", event.name, data = properties))
        if (consent == AnalyticsConsent.DENIED) return
        val withScreen = currentScreen?.let { properties + ("screen" to it.id) } ?: properties
        analytics.capture(event.name, withScreen)
    }

    /** A screen shown. Repeats of the one on show are ignored (a recomposition, a tab tapped again). */
    fun screen(screen: Screen, properties: Map<String, Any> = emptyMap()) {
        val from = synchronized(lock) {
            if (currentScreen == screen) return
            currentScreen.also { currentScreen = screen }
        }
        crashes.setTag("screen", screen.id)
        crashes.breadcrumb(Breadcrumb("navigation", screen.id, data = from?.let { mapOf("from" to it.id) } ?: emptyMap()))
        if (consent == AnalyticsConsent.DENIED) return
        analytics.screen(screen.id, PrivacyGuard.properties(screen.id, properties))
    }

    // Identity

    /**
     * The signed-in account (null signed out). Sentry always gets the id; PostHog only with the
     * person's consent. Signing out (or into another account) starts a new anonymous id.
     */
    fun signedIn(id: String?) {
        val reset = synchronized(lock) {
            val previous = userID
            userID = id
            (previous != null && previous != id).also {
                // Another account (or none): what described the last one isn't theirs.
                if (it) {
                    identified = false
                    person.clear()
                }
            }
        }
        crashes.setUser(id)
        if (reset) resetAnalytics()
        identifyIfAllowed()
    }

    /** Facts about the account for analytics (person properties), only while identified. */
    fun describeAccount(properties: Map<String, Any>) {
        val safe = PrivacyGuard.properties("person", properties)
        val identifiedNow = synchronized(lock) {
            person.putAll(safe)
            identified
        }
        safe.forEach { (k, v) -> crashes.setTag(k, render(v)) }
        if (identifiedNow) analytics.setPersonProperties(safe)
    }

    /** Sent with every event and error report (the app's language, the build's environment...). */
    fun register(key: String, value: Any) {
        val safe = PrivacyGuard.properties("register", mapOf(key to value))
        synchronized(lock) { registered.putAll(safe) }
        safe.forEach { (k, v) ->
            crashes.setTag(k, render(v))
            analytics.register(k, v)
        }
    }

    fun applyConsent(value: AnalyticsConsent) {
        val (before, withdrawn) = synchronized(lock) {
            val before = consent
            consent = value
            // Withdrawn: what follows is anonymous again, under a new id.
            val withdrawn = value != AnalyticsConsent.GRANTED && identified
            if (withdrawn) identified = false
            before to withdrawn
        }
        analytics.setEnabled(value != AnalyticsConsent.DENIED)
        if (withdrawn) resetAnalytics()
        identifyIfAllowed()
        if (before != value) crashes.breadcrumb(Breadcrumb("consent", "analytics ${value.id}"))
    }

    /** A new anonymous id, with the super properties registered again on it. */
    private fun resetAnalytics() {
        val again = synchronized(lock) { registered.toMap() }
        analytics.reset()
        again.forEach { (k, v) -> analytics.register(k, v) }
    }

    private fun identifyIfAllowed() {
        val (id, described) = synchronized(lock) {
            val id = userID
            if (id == null || consent != AnalyticsConsent.GRANTED || identified) return
            identified = true
            id to person.toMap()
        }
        analytics.identify(id, emptyMap())
        // Described before the account was identified (or before the consent): sent now.
        if (described.isNotEmpty()) analytics.setPersonProperties(described)
    }

    // Errors and logs

    /**
     * An error the code didn't expect. What's normal on a phone (offline, cancelled, a refusal the
     * server explains to the person) only goes in the breadcrumbs: Sentry alerts on what needs a fix.
     */
    fun unexpected(error: Throwable, area: String, action: String? = null, extra: Map<String, Any> = emptyMap()) {
        if (error is CancellationException) return
        val kind = ErrorKind.of(error)
        val report = ErrorReport(area, action, PrivacyGuard.properties("error", extra + ("kind" to kind.id)))
        if (kind.reportable) {
            crashes.capture(error, report)
        } else {
            crashes.breadcrumb(
                Breadcrumb("error", "${area}${action?.let { ".$it" } ?: ""}: ${kind.id}", Level.WARNING, report.extra),
            )
        }
    }

    /**
     * A failure as an event property: the server's code when it gave one (`daily_like_limit`), else the
     * kind of failure (`offline`, `server`...). Never the message.
     */
    fun reason(error: Throwable): String {
        val code = when (error) {
            is so.drafft.core.data.backend.Backend.EmailAlreadyRegistered -> "email_taken"
            is so.drafft.core.data.backend.Backend.PhoneAlreadyRegistered -> "phone_taken"
            is so.drafft.core.data.backend.ProfileSync.SyncError.Refused -> error.code
            is so.drafft.core.data.verification.VerificationError -> error.reason
            is so.drafft.core.data.store.Store.StoreError.NotLinked -> "not_linked"
            is so.drafft.core.data.store.Store.StoreError.Failed -> error.problem.name.lowercase()
            // Supabase Auth's own codes: `invalid_credentials`, `otp_expired`, `weak_password`...
            is io.github.jan.supabase.exceptions.RestException -> error.error
            else -> so.drafft.core.data.backend.ServerMessage.code(error)
        }
        return code?.lowercase()?.takeIf { it.length <= 60 && PrivacyGuard.isCode(it) } ?: ErrorKind.of(error).id
    }

    /** Something wrong without an exception (a state that shouldn't happen): an event at [level]. */
    fun problem(text: String, area: String, level: Level = Level.WARNING, extra: Map<String, Any> = emptyMap()) {
        val scrubbed = PrivacyGuard.scrub(text)
        crashes.message(scrubbed, level, ErrorReport(area, extra = PrivacyGuard.properties("problem", extra), fingerprint = listOf(area, scrubbed)))
    }

    fun breadcrumb(category: String, message: String, level: Level = Level.INFO, data: Map<String, Any> = emptyMap()) {
        crashes.breadcrumb(Breadcrumb(category, PrivacyGuard.scrub(message), level, PrivacyGuard.properties(category, data)))
    }

    fun log(level: Level, text: String, attributes: Map<String, Any> = emptyMap()) {
        crashes.log(level, PrivacyGuard.scrub(text), PrivacyGuard.properties("log", attributes))
    }

    // Performance

    /** Times [block] as [operation] (a span, or a trace of its own when nothing runs yet). */
    suspend fun <T> trace(operation: String, description: String, block: suspend (Span) -> T): T {
        val span = crashes.startSpan(operation, description)
        var ok = false
        try {
            return block(span).also { ok = true }
        } finally {
            span.finish(ok)
        }
    }

    /** A value as Sentry shows it (a tag, an extra): a list of codes as `a,b`, like the iPhone. */
    fun render(value: Any): String = if (value is Collection<*>) value.joinToString(",") else value.toString()

    /** Back to nothing installed (unit tests). */
    fun uninstall() {
        crashes = NoCrashes
        analytics = NoAnalytics
        synchronized(lock) {
            consent = AnalyticsConsent.UNKNOWN
            userID = null
            identified = false
            currentScreen = null
            registered.clear()
            person.clear()
        }
    }
}
