package so.drafft.core.data.backend

import java.util.UUID
import kotlin.test.assertEquals
import org.junit.Test

class DeviceIntegrityTest {
    @Test
    fun theRequestHashIsTheLowercaseHexSha256OfTheAccountId() {
        val account = UUID.fromString("0B6F1F5E-8F0C-4A5E-9D3B-2F8A1C7E4D10")
        assertEquals("08b22dd96eae1a1828446fe9758a0078fb88361d33e0e27453bf4988ab788e23", DeviceIntegrity.requestHash(account))
    }
}
