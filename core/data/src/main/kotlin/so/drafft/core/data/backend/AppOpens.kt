package so.drafft.core.data.backend

import java.util.Locale
import java.util.TimeZone
import so.drafft.core.data.platform.AppInfo

// Ports Drafft/Services/Backend/AppOpens.swift.

/**
 * Each time the app comes to the front, signed in: which phone, OS and app version, locale and time
 * zone (`report_app_open`). The server adds the IP and country. For the team's safety checks only (ban
 * evasion, shared devices, the last time someone opened drafft); the server keeps it 180 days to a year.
 */
class AppOpens(private val backend: Backend, private val info: AppInfo) {
    suspend fun report() {
        if (!backend.hasSession()) return
        val install = info.installID ?: return
        val device = jsonOf(
            "model" to info.model,
            "os" to info.osVersion,
            "app" to info.version,
            "build" to info.build,
            "locale" to Locale.getDefault().toString(),
            "timezone" to TimeZone.getDefault().id,
            "platform" to "android",
        )
        attempt { backend.rpc("report_app_open", jsonOf("p_install" to install, "p_device" to device)) }
    }
}
