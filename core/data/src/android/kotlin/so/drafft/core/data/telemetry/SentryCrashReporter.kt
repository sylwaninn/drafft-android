package so.drafft.core.data.telemetry

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.ProfileLifecycle
import io.sentry.ISpan
import io.sentry.Sentry
import io.sentry.SentryAttributes
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryLogLevel
import io.sentry.SentryOptions
import io.sentry.SpanStatus
import io.sentry.TransactionOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.logger.SentryLogParameters
import io.sentry.protocol.User

/**
 * [Telemetry.CrashReporter] on Sentry (EU region, picked by the DSN). Crashes (Kotlin and native),
 * ANRs, the errors [Telemetry.unexpected] reports, app start and frame metrics, request traces, the
 * app's logs. What it never takes: screenshots, the view hierarchy (both would show people's photos
 * and messages), the IP address, the user's name or email.
 */
class SentryCrashReporter private constructor() : Telemetry.CrashReporter {
    override fun setUser(id: String?) {
        Sentry.setUser(id?.let { User().apply { this.id = it } })
    }

    override fun setTag(key: String, value: String?) {
        if (value == null) Sentry.removeTag(key) else Sentry.setTag(key, value)
    }

    override fun breadcrumb(crumb: Telemetry.Breadcrumb) {
        Sentry.addBreadcrumb(
            Breadcrumb().apply {
                category = crumb.category
                message = crumb.message
                level = crumb.level.sentry
                type = when (crumb.category) {
                    "navigation" -> "navigation"
                    "http" -> "http"
                    "error" -> "error"
                    "product", "ui" -> "user"
                    else -> "default"
                }
                crumb.data.forEach { (k, v) -> setData(k, v) }
            },
        )
    }

    override fun capture(error: Throwable, report: Telemetry.ErrorReport) {
        Sentry.captureException(error) { scope ->
            scope.setTag("area", report.area)
            report.action?.let { scope.setTag("action", it) }
            report.extra.forEach { (k, v) -> scope.setExtra(k, v.toString()) }
            report.fingerprint?.let { scope.fingerprint = it }
        }
    }

    override fun message(text: String, level: Telemetry.Level, report: Telemetry.ErrorReport) {
        Sentry.captureMessage(text, level.sentry) { scope ->
            scope.setTag("area", report.area)
            report.action?.let { scope.setTag("action", it) }
            report.extra.forEach { (k, v) -> scope.setExtra(k, v.toString()) }
            report.fingerprint?.let { scope.fingerprint = it }
        }
    }

    override fun log(level: Telemetry.Level, text: String, attributes: Map<String, Any>) {
        val parameters = SentryLogParameters.create(SentryAttributes.fromMap(attributes))
        Sentry.logger().log(level.log, parameters, text)
    }

    override fun startSpan(operation: String, description: String): Telemetry.Span {
        // Inside a running trace (a screen load, a flow): a child. Otherwise a trace of its own, so
        // every request shows in Performance with its own latency (sampled by tracesSampleRate).
        val parent = Sentry.getSpan()
        val span: ISpan = parent?.startChild(operation, description)
            ?: Sentry.startTransaction(description, operation, TransactionOptions().apply { isBindToScope = false })
        return SentrySpan(span)
    }

    private class SentrySpan(private val span: ISpan) : Telemetry.Span {
        override fun setData(key: String, value: Any) = span.setData(key, value)
        override fun finish(ok: Boolean) {
            if (span.isFinished) return
            span.finish(if (ok) SpanStatus.OK else SpanStatus.INTERNAL_ERROR)
        }
    }

    companion object {
        /**
         * Starts Sentry, first thing in `Application.onCreate` (auto-init is off in the manifest), so a
         * crash during launch is caught. Null without a DSN: Sentry stays off.
         */
        fun start(context: Context, config: TelemetryConfig): SentryCrashReporter? {
            if (!config.hasSentry) return null
            SentryAndroid.init(context) { options ->
                options.dsn = config.sentryDSN
                options.environment = config.environment
                options.release = config.release
                options.dist = config.build
                options.isDebug = false

                // Privacy: nothing that shows people, their messages or where they are. Default PII
                // (IP address, user details) stays off, Sentry's default; scrubbed() makes sure.
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                options.isEnableUserInteractionBreadcrumbs = false
                options.isEnableUserInteractionTracing = false
                options.setTracePropagationTargets(emptyList())

                // Crashes, hangs, release health.
                options.isAnrEnabled = true
                options.isReportHistoricalAnrs = true
                options.isAttachAnrThreadDump = true
                options.isEnableAutoSessionTracking = true
                options.maxBreadcrumbs = 150

                // Performance: app start, slow and frozen frames, request traces, some profiles. A request
                // outside any trace is a trace of its own: the most frequent kind, so the most sampled
                // down (the quota goes to app starts, screen loads and uploads, where slowness is felt).
                options.tracesSampler = SentryOptions.TracesSamplerCallback { context ->
                    val operation = context.transactionContext.operation
                    if (operation == "http.client") config.requestSampleRate else config.tracesSampleRate
                }
                // Profiles follow the sampled traces (the default, MANUAL, would never start one).
                options.profileSessionSampleRate = config.profileSampleRate
                options.profileLifecycle = ProfileLifecycle.TRACE
                options.isEnablePerformanceV2 = true
                options.isEnableFramesTracking = true

                // The app's logs (TelemetryLogHandler).
                options.logs.isEnabled = true

                options.beforeSend = SentryOptions.BeforeSendCallback { event, _: Hint -> scrubbed(event) }
                options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _: Hint ->
                    crumb.message = crumb.message?.let(PrivacyGuard::scrub)
                    crumb.getData("url")?.let { crumb.setData("url", PrivacyGuard.path(it.toString())) }
                    crumb
                }
                options.logs.beforeSend = SentryOptions.Logs.BeforeSendLogCallback { log ->
                    log.body = PrivacyGuard.scrub(log.body)
                    log
                }
                options.setTag("flavor", config.environment)
            }
            return SentryCrashReporter()
        }

        /** The last pass over an event: what an exception's message may carry, and the user's id only. */
        private fun scrubbed(event: SentryEvent): SentryEvent {
            event.message?.let { m ->
                m.formatted = m.formatted?.let(PrivacyGuard::scrub)
                m.message = m.message?.let(PrivacyGuard::scrub)
            }
            event.exceptions?.forEach { e -> e.value = e.value?.let(PrivacyGuard::scrub) }
            event.breadcrumbs?.forEach { b -> b.message = b.message?.let(PrivacyGuard::scrub) }
            event.user?.let { u ->
                u.email = null
                u.username = null
                u.ipAddress = null
                u.geo = null
            }
            event.request = null
            return event
        }

        private val Telemetry.Level.sentry: SentryLevel
            get() = when (this) {
                Telemetry.Level.DEBUG -> SentryLevel.DEBUG
                Telemetry.Level.INFO -> SentryLevel.INFO
                Telemetry.Level.WARNING -> SentryLevel.WARNING
                Telemetry.Level.ERROR -> SentryLevel.ERROR
                Telemetry.Level.FATAL -> SentryLevel.FATAL
            }

        private val Telemetry.Level.log: SentryLogLevel
            get() = when (this) {
                Telemetry.Level.DEBUG -> SentryLogLevel.DEBUG
                Telemetry.Level.INFO -> SentryLogLevel.INFO
                Telemetry.Level.WARNING -> SentryLogLevel.WARN
                Telemetry.Level.ERROR -> SentryLogLevel.ERROR
                Telemetry.Level.FATAL -> SentryLogLevel.FATAL
            }
    }
}
