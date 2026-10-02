package so.drafft.core.data.telemetry

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * The app's own log lines (`java.util.logging`) also go to Sentry: INFO as a breadcrumb (it comes with the next error
 * report), WARNING also as a Sentry log (searchable, never an issue: the code that caught the error decides that,
 * [Telemetry.unexpected]), SEVERE as a Sentry issue grouped by its words. Scrubbed on the way ([PrivacyGuard]). Only
 * the app's loggers: libraries report through their own channels.
 */
class TelemetryLogHandler : Handler() {
    override fun publish(record: LogRecord?) = safely {
        record ?: return@safely
        if (!isLoggable(record)) return@safely
        val name = record.loggerName ?: return@safely
        if (!isOurs(name)) return@safely
        val text = record.message ?: return@safely
        val area = area(name)
        val attributes = mapOf<String, Any>("logger" to area)
        when {
            record.level.intValue() >= Level.SEVERE.intValue() -> {
                Telemetry.log(Telemetry.Level.ERROR, text, attributes)
                Telemetry.problem(text, area, Telemetry.Level.ERROR, record.thrown?.let(::thrownExtra).orEmpty())
            }
            record.level.intValue() >= Level.WARNING.intValue() -> {
                Telemetry.breadcrumb("log", text, Telemetry.Level.WARNING, attributes)
                Telemetry.log(Telemetry.Level.WARNING, text, attributes)
            }
            else -> Telemetry.breadcrumb("log", text, Telemetry.Level.INFO, attributes)
        }
    }

    /** What an exception tells without its message (which may hold what people typed): its type and kind. */
    private fun thrownExtra(thrown: Throwable): Map<String, Any> = mapOf(
        "exception" to thrown.javaClass.simpleName.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase(),
        "kind" to ErrorKind.of(thrown).id,
    )

    override fun flush() = Unit
    override fun close() = Unit

    companion object {
        /** Once, at launch: on the root logger, so every app logger reaches it. */
        fun install() {
            val root = Logger.getLogger("")
            if (root.handlers.any { it is TelemetryLogHandler }) return
            root.addHandler(TelemetryLogHandler().apply { level = Level.INFO })
        }

        fun isOurs(logger: String): Boolean = logger.startsWith("so.drafft")

        /** `so.drafft.chat-media` → `chat-media`, as a slug for the `logger` attribute. */
        fun area(logger: String): String = logger.removePrefix("so.drafft.").removePrefix("app.").lowercase()
            .replace(Regex("[^a-z0-9_.:-]"), "_").ifEmpty { "app" }
    }
}
