package so.drafft.core.data.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Why the app's past runs ended (Android 11 and later): crashes, ANRs, low memory kills, as the
 * system recorded them. Each run is reported once (the last one seen is remembered).
 */
class ExitReasonDiagnostics(
    private val context: Context,
    private val defaults: KeyValueStore,
) : DiagnosticsSource {
    override fun start(onPayload: (kind: String, json: String, summary: String, isProblem: Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val seen = defaults.getString(LAST_SEEN_KEY)?.toLongOrNull() ?: 0L
        val exits = runCatching { manager.getHistoricalProcessExitReasons(null, 0, 16) }.getOrDefault(emptyList())
            .filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
        for (exit in exits) {
            val problem = exit.reason in setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
            )
            val json = buildJsonObject {
                put("timestamp", exit.timestamp)
                put("reason", exit.reason)
                put("status", exit.status)
                put("importance", exit.importance)
                put("description", exit.description ?: "")
                put("pssKb", exit.pss)
                put("rssKb", exit.rss)
            }
            val summary = "exit ${exit.timestamp}: reason ${exit.reason}, ${exit.description ?: ""}, rss ${exit.rss / 1024} MB"
            onPayload(if (problem) "diagnostics" else "metrics", json.toString(), summary, problem)
        }
        exits.lastOrNull()?.let { defaults.putString(LAST_SEEN_KEY, it.timestamp.toString()) }
    }

    private companion object {
        const val LAST_SEEN_KEY = "diagnostics.lastExitSeen"
    }
}
