package so.drafft.core.data

import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.platform.DiagnosticsSource
import so.drafft.core.data.telemetry.Telemetry

// Android reports why past runs ended (crashes, ANRs, low memory: `ApplicationExitInfo`), through
// [DiagnosticsSource].

/**
 * What the system measures of the app on people's phones: crash, hang and exit diagnostics.
 *
 * They're written to the device log (tag `so.drafft.app`, category `metrics`) and kept as JSON files
 * in the app's files/Diagnostics (the last 30), readable with Android Studio's Device Explorer or a
 * bug report. Every summary also reaches Sentry as a log line, searchable by release: a problem through
 * `TelemetryLogHandler` (it is logged at WARNING), any other exit directly, with a breadcrumb for the
 * next report. Crashes and ANRs reach Sentry on their own,
 * so a problem is only a warning here, never a second error.
 */
class Diagnostics(
    private val source: DiagnosticsSource,
    private val info: AppInfo,
) {
    private val log = Logger.getLogger("so.drafft.app.metrics")

    /** Once, at launch. */
    fun start() {
        source.start { kind, json, summary, isProblem ->
            log.log(if (isProblem) Level.WARNING else Level.INFO, summary)
            // A problem is already a Sentry log (WARNING); the rest is one too, searchable by release.
            if (!isProblem) Telemetry.log(Telemetry.Level.INFO, summary, mapOf("logger" to "metrics"))
            keep(json, kind)
        }
    }

    private fun keep(json: String, kind: String) {
        val dir = File(info.filesDir, "Diagnostics")
        dir.mkdirs()
        runCatching { File(dir, "${System.currentTimeMillis() / 1000}-$kind.json").writeText(json) }
        // Named by time: the oldest go first.
        val files = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }
        files.dropLast(KEPT).forEach { it.delete() }
    }

    private companion object {
        const val KEPT = 30
    }
}
