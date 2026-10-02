package so.drafft.core.data.backend

import java.security.MessageDigest
import java.util.UUID
import so.drafft.core.data.platform.DeviceIntegrityProvider

/**
 * Device attestation (Play Integrity on Android): each time the app opens signed in, and at sign-in, a new
 * token goes to the server (`device-check`). The server checks it with Google and uses the device's Device
 * recall bits, so that an account closed or on hold on this phone sends another account that shows up on it
 * to review. The token says nothing to the app; only the platform can tell which device it stands for.
 * Nothing is sent when the build or the device can't give one.
 */
class DeviceIntegrity(
    private val backend: Backend,
    private val provider: DeviceIntegrityProvider,
) {
    suspend fun report() {
        // hasSession() waits for the saved session to load; the account's id is read right after it.
        if (!backend.hasSession()) return
        val account = backend.userID ?: return
        // Quiet here: the provider reports the errors that need a fix (a phone without Play services isn't one).
        val token = attempt { provider.token(requestHash(account)) } ?: return
        // The server ignores `environment` for Android (it stores "production"): there is one Play app.
        attempt(report = true, area = "integrity", action = "device_check") {
            backend.function(
                "device-check",
                jsonOf("token" to token, "environment" to "production", "platform" to "android"),
            )
        }
    }

    companion object {
        /**
         * What the token is bound to: the SHA-256 of the account's id (lowercase text, hyphens included),
         * as lowercase hex. The server computes the same hash from the Auth user and compares.
         */
        fun requestHash(account: UUID): String =
            MessageDigest.getInstance("SHA-256").digest(account.toString().lowercase().toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
