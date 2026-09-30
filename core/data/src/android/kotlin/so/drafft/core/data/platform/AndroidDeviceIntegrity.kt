package so.drafft.core.data.platform

/**
 * Device attestation on Android would be Play Integrity (`com.google.android.play:integrity`,
 * a standard integrity token requested with the backend's cloud project number). Not wired yet: the
 * backend's `device-check` function only verifies Apple DeviceCheck tokens, and the Play Integrity
 * library isn't in the build. Until both exist, nothing is reported (like an iPhone simulator).
 */
object UnsupportedDeviceIntegrity : DeviceIntegrityProvider {
    override val isSupported: Boolean = false
    override suspend fun token(): String? = null
}
