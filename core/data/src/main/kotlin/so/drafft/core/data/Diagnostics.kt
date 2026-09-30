package so.drafft.core.data

import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.platform.DiagnosticsSource

// Ports Drafft/Services/Diagnostics.swift. The iPhone reads MetricKit; Android reports why past runs
// ended (crashes, ANRs, low memory: `ApplicationExitInfo`), through [DiagnosticsSource].

/**
 * What the system measures of the app on people's phones: crash, hang and exit diagnostics.
 *
 * For now they're written to the device log (tag `so.drafft.app`, category `metrics`) and kept as
 * JSON files in the app's files/Diagnostics (the last 30), readable with Android Studio's Device
 * Explorer or a bug report. Nothing leaves the phone yet: sending them (Sentry or the backend) comes
 * with that service.
 */
class Diagnostics(
    private val source: DiagnosticsSource,
    private val info: AppInfo,
) {
    private val log = Logger.getLogger("so.drafft.app.metrics")

    /** Once, at launch. */
    fun start() {
        source.start { kind, json, summary, isProblem ->
            log.log(if (isProblem) Level.SEVERE else Level.INFO, summary)
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
