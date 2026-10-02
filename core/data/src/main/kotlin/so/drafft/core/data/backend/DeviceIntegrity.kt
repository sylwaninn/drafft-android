package so.drafft.core.data.backend

import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.platform.DeviceIntegrityProvider

/**
 * Device attestation (Play Integrity): at each launch and sign-in, a
 * fresh token goes to the server (`device-check`), which keeps a closed account from coming back on
 * the same phone. The token says nothing to the app; only the platform can tell which device it
 * stands for. Nothing is sent when the device can't give one.
 */
class DeviceIntegrity(
    private val backend: Backend,
    private val provider: DeviceIntegrityProvider,
    private val info: AppInfo,
) {
    suspend fun report() {
        if (!provider.isSupported || !backend.hasSession()) return
        val token = attempt { provider.token() } ?: return
        // Debug builds use the development environment, like their pushes.
        val environment = if (info.isDebugBuild) "development" else "production"
        attempt {
            backend.function(
                "device-check",
                jsonOf("token" to token, "environment" to environment, "platform" to "android"),
            )
        }
    }
}
