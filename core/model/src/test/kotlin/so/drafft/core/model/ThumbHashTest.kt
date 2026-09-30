package so.drafft.core.model

import java.util.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of DrafftTests/ThumbHashTests.swift. */
class ThumbHashTest {
    /** A 100 × 60 landscape: red to blue left to right, brighter at the top. */
    private fun gradient(w: Int = 100, h: Int = 60): ByteArray {
        val rgba = ByteArray(w * h * 4) { 255.toByte() }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                val t = x.toDouble() / (w - 1)
                val light = 1 - 0.5 * y / (h - 1)
                rgba[i] = (255 * (1 - t) * light).toInt().toByte()
                rgba[i + 1] = (60 * light).toInt().toByte()
                rgba[i + 2] = (255 * t * light).toInt().toByte()
            }
        }
        return rgba
    }

    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

    private fun average(rgba: ByteArray, channel: Int): Double =
        (channel until rgba.size step 4).sumOf { rgba.u(it).toDouble() } / (rgba.size / 4)

    @Test
    fun roundTripKeepsShapeAndColours() {
        val source = gradient()
        val hash = ThumbHash.encode(100, 60, source)
        assertTrue(hash.size <= 25)
        val decoded = assertNotNull(ThumbHash.decode(hash))
        // Landscape, width capped at 32, height from the approximate ratio.
        assertEquals(32, decoded.width)
        assertEquals(decoded.width * decoded.height * 4, decoded.rgba.size)
        assertTrue(abs(decoded.width.toDouble() / decoded.height - 100.0 / 60) <= 0.35)
        for (channel in 0 until 3) {
            assertTrue(abs(average(decoded.rgba, channel) - average(source, channel)) <= 12, "channel $channel")
        }
        // The left edge stays redder than the right one.
        val row = decoded.height / 2
        val left = row * decoded.width * 4
        val right = left + (decoded.width - 1) * 4
        assertTrue(decoded.rgba.u(left) > decoded.rgba.u(right))
        assertTrue(decoded.rgba.u(left + 2) < decoded.rgba.u(right + 2))
        assertTrue((3 until decoded.rgba.size step 4).all { decoded.rgba.u(it) == 255 })
    }

    @Test
    fun portraitAndBase64Image() {
        val portrait = ByteArray(40 * 90 * 4) { 255.toByte() }
        for (i in portrait.indices step 4) {
            portrait[i] = 30; portrait[i + 1] = 140.toByte(); portrait[i + 2] = 90
        }
        val base64 = Base64.getEncoder().encodeToString(ThumbHash.encode(40, 90, portrait))
        val image = assertNotNull(ThumbHash.image(fromBase64 = base64))
        assertEquals(32, image.height)
        assertTrue(image.width < image.height)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(ThumbHash.decode(byteArrayOf()))
        assertNull(ThumbHash.decode(byteArrayOf(1, 2, 3)))
        assertNull(ThumbHash.decode(byteArrayOf(0x1f, 0x2e, 0x3d, 0x07, 0x00)))
        assertNull(ThumbHash.image(fromBase64 = "not base64"))
    }

    /** Decoding a preview: what a photo view pays once (then cached by key). */
    @Test
    fun decodeSpeed() {
        val hash = ThumbHash.encode(100, 60, gradient())
        val start = System.nanoTime()
        repeat(100) { ThumbHash.decode(hash) }
        println("100 ThumbHash decodes: ${(System.nanoTime() - start) / 1_000_000} ms")
    }
}
