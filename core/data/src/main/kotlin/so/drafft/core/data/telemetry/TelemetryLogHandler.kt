package so.drafft.core.data.telemetry

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * The app's own log lines (`java.util.logging`, the iPhone's `os.Logger`) also go to Sentry: every
 * line from INFO up as a breadcrumb, WARNING up as a Sentry log, SEVERE as an error event (with its
 * exception when it has one). Scrubbed on the way ([PrivacyGuard]). Only the app's loggers: libraries
 * report through their own channels.
 */
class TelemetryLogHandler : Handler() {
    override fun publish(record: LogRecord?) {
        record ?: return
        if (!isLoggable(record)) return
        val name = record.loggerName ?: return
        if (!isOurs(name)) return
        val text = record.message ?: return
        val level = level(record.level)
        val area = area(name)
        Telemetry.breadcrumb("log", text, level, mapOf("logger" to area))
        if (record.level.intValue() >= Level.WARNING.intValue()) {
            Telemetry.log(level, text, mapOf("logger" to area))
        }
        if (record.level.intValue() >= Level.SEVERE.intValue()) {
            val thrown = record.thrown
            if (thrown != null) Telemetry.unexpected(thrown, area, "log") else Telemetry.problem(text, area, Telemetry.Level.ERROR)
        }
    }

    override fun flush() = Unit
    override fun close() = Unit

    companion object {
        /** Once, at launch: on the root logger, so every app logger reaches it. */
        fun install() {
            val root = Logger.getLogger("")
            if (root.handlers.any { it is TelemetryLogHandler }) return
            root.addHandler(TelemetryLogHandler().apply { level = Level.INFO })
        }

        fun isOurs(logger: String): Boolean = logger.startsWith("so.drafft") || logger in legacyNames

        /** Loggers named before the `so.drafft.` convention. */
        private val legacyNames = setOf("safety")

        /** `so.drafft.chat-media` → `chat-media`, as a slug for the `logger` attribute. */
        fun area(logger: String): String = logger.removePrefix("so.drafft.").removePrefix("app.").lowercase()
            .replace(Regex("[^a-z0-9_.:-]"), "_").ifEmpty { "app" }

        fun level(level: Level): Telemetry.Level = when {
            level.intValue() >= Level.SEVERE.intValue() -> Telemetry.Level.ERROR
            level.intValue() >= Level.WARNING.intValue() -> Telemetry.Level.WARNING
            level.intValue() >= Level.INFO.intValue() -> Telemetry.Level.INFO
            else -> Telemetry.Level.DEBUG
        }
    }
}
