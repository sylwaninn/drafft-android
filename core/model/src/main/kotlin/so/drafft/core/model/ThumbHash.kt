package so.drafft.core.model

import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * ThumbHash encoder and decoder (https://evanw.github.io/thumbhash/), following the reference
 * implementation. ~25 bytes per image, stored with each photo:
 * the app draws a blurred preview from it instantly, before a single byte of the real image has arrived.
 *
 * Pure Kotlin: pixels are RGBA bytes (unsigned, as in the reference). Drawing a bitmap into those bytes
 * (and back) is the platform's part (`so.drafft.core.data.media`).
 */
object ThumbHash {
    /** A decoded preview: at most 32 × 32 px, straight (not premultiplied) RGBA. */
    class Image(val width: Int, val height: Int, val rgba: ByteArray)

    /**
     * Follows the reference implementation's `rgbaToThumbHash`. Photos are opaque, so premultiplied
     * input is equivalent.
     */
    fun encode(width: Int, height: Int, rgba: ByteArray): ByteArray {
        val w = width
        val h = height
        require(w <= 100 && h <= 100 && rgba.size == w * h * 4)
        val count = w * h
        fun px(i: Int): Double = (rgba[i].toInt() and 0xFF).toDouble()

        // Average color.
        var avgR = 0.0
        var avgG = 0.0
        var avgB = 0.0
        var avgA = 0.0
        for (i in 0 until count) {
            val j = i * 4
            val alpha = px(j + 3) / 255
            avgR += alpha / 255 * px(j)
            avgG += alpha / 255 * px(j + 1)
            avgB += alpha / 255 * px(j + 2)
            avgA += alpha
        }
        if (avgA > 0) {
            avgR /= avgA
            avgG /= avgA
            avgB /= avgA
        }

        val hasAlpha = avgA < count.toDouble()
        val lLimit = if (hasAlpha) 5.0 else 7.0 // Fewer luminance bits when there's alpha.
        val longest = max(w, h).toDouble()
        val lx = max(1, (lLimit * w / longest).roundAway())
        val ly = max(1, (lLimit * h / longest).roundAway())

        // RGBA to LPQA, composited atop the average color.
        val l = DoubleArray(count)
        val p = DoubleArray(count)
        val q = DoubleArray(count)
        val a = DoubleArray(count)
        for (i in 0 until count) {
            val j = i * 4
            val alpha = px(j + 3) / 255
            val r = avgR * (1 - alpha) + alpha / 255 * px(j)
            val g = avgG * (1 - alpha) + alpha / 255 * px(j + 1)
            val b = avgB * (1 - alpha) + alpha / 255 * px(j + 2)
            l[i] = (r + g + b) / 3
            p[i] = (r + g) / 2 - b
            q[i] = r - g
            a[i] = alpha
        }

        // DCT: one constant (DC) term and normalized varying (AC) terms per channel.
        class Channel(val dc: Double, val ac: List<Double>, val scale: Double)

        fun encodeChannel(channel: DoubleArray, nx: Int, ny: Int): Channel {
            var dc = 0.0
            var scale = 0.0
            val ac = ArrayList<Double>()
            val fx = DoubleArray(w)
            for (cy in 0 until ny) {
                var cx = 0
                while (cx * ny < nx * (ny - cy)) {
                    var f = 0.0
                    for (x in 0 until w) fx[x] = cos(PI / w * cx * (x + 0.5))
                    for (y in 0 until h) {
                        val fy = cos(PI / h * cy * (y + 0.5))
                        for (x in 0 until w) f += channel[x + y * w] * fx[x] * fy
                    }
                    f /= count
                    if (cx > 0 || cy > 0) {
                        ac.add(f)
                        scale = max(scale, abs(f))
                    } else {
                        dc = f
                    }
                    cx += 1
                }
            }
            val normalized = if (scale > 0) ac.map { 0.5 + 0.5 / scale * it } else ac
            return Channel(dc, normalized, scale)
        }

        val lc = encodeChannel(l, max(3, lx), max(3, ly))
        val pc = encodeChannel(p, 3, 3)
        val qc = encodeChannel(q, 3, 3)
        val ac = if (hasAlpha) encodeChannel(a, 5, 5) else null

        // Header.
        val isLandscape = w > h
        val header24 = (63 * lc.dc).roundAway() or
            ((31.5 + 31.5 * pc.dc).roundAway() shl 6) or
            ((31.5 + 31.5 * qc.dc).roundAway() shl 12) or
            ((31 * lc.scale).roundAway() shl 18) or
            ((if (hasAlpha) 1 else 0) shl 23)
        val header16 = (if (isLandscape) ly else lx) or
            ((63 * pc.scale).roundAway() shl 3) or
            ((63 * qc.scale).roundAway() shl 9) or
            ((if (isLandscape) 1 else 0) shl 15)
        val hash = ArrayList<Int>()
        hash += listOf(header24 and 255, (header24 shr 8) and 255, header24 shr 16, header16 and 255, header16 shr 8)
        if (ac != null) hash += (15 * ac.dc).roundAway() or ((15 * ac.scale).roundAway() shl 4)

        // Varying factors, two per byte.
        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        val channels = if (hasAlpha) listOf(lc.ac, pc.ac, qc.ac, ac?.ac.orEmpty()) else listOf(lc.ac, pc.ac, qc.ac)
        for (channel in channels) {
            for (f in channel) {
                val byte = acStart + (acIndex shr 1)
                if (byte >= hash.size) hash += 0
                hash[byte] = hash[byte] or ((15 * f).roundAway() shl ((acIndex and 1) shl 2))
                acIndex += 1
            }
        }
        return ByteArray(hash.size) { hash[it].toByte() }
    }

    /** Base64 of [encode]. */
    fun base64(width: Int, height: Int, rgba: ByteArray): String =
        Base64.getEncoder().encodeToString(encode(width, height, rgba))

    // Decoding

    /** The preview a base64 hash stands for. Null for a hash that isn't one (too short, bad base64). */
    fun image(fromBase64: String): Image? {
        val bytes = runCatching { Base64.getDecoder().decode(fromBase64) }.getOrNull() ?: return null
        return decode(bytes)
    }

    /** Width over height, from the header alone. */
    fun approximateAspectRatio(hash: ByteArray): Double? {
        if (hash.size < 5) return null
        val header = hash.u(3)
        val hasAlpha = hash.u(2) and 0x80 != 0
        val isLandscape = hash.u(4) and 0x80 != 0
        val lx = if (isLandscape) (if (hasAlpha) 5 else 7) else header and 7
        val ly = if (isLandscape) header and 7 else (if (hasAlpha) 5 else 7)
        if (lx <= 0 || ly <= 0) return null
        return lx.toDouble() / ly
    }

    // Kept in one piece like the reference, so it can be compared line by line.
    /** Follows the reference implementation's `thumbHashToRGBA` (straight, not premultiplied, alpha). */
    fun decode(hash: ByteArray): Image? {
        if (hash.size < 5) return null
        val ratio = approximateAspectRatio(hash) ?: return null
        val header24 = hash.u(0) or (hash.u(1) shl 8) or (hash.u(2) shl 16)
        val header16 = hash.u(3) or (hash.u(4) shl 8)
        val lDC = (header24 and 63).toDouble() / 63
        val pDC = ((header24 shr 6) and 63).toDouble() / 31.5 - 1
        val qDC = ((header24 shr 12) and 63).toDouble() / 31.5 - 1
        val lScale = ((header24 shr 18) and 31).toDouble() / 31
        val hasAlpha = (header24 shr 23) != 0
        val pScale = ((header16 shr 3) and 63).toDouble() / 63
        val qScale = ((header16 shr 9) and 63).toDouble() / 63
        val isLandscape = (header16 shr 15) != 0
        val lx = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
        if (hasAlpha && hash.size < 6) return null
        val aDC = if (hasAlpha) (hash.u(5) and 15).toDouble() / 15 else 1.0
        val aScale = if (hasAlpha) (hash.u(5) shr 4).toDouble() / 15 else 0.0

        // Varying factors, two per byte (saturation boosted 1.25× against quantisation).
        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        var truncated = false
        fun decodeChannel(nx: Int, ny: Int, scale: Double): DoubleArray {
            val ac = ArrayList<Double>()
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) {
                    val byte = acStart + (acIndex shr 1)
                    if (byte >= hash.size) {
                        truncated = true
                        return ac.toDoubleArray()
                    }
                    val nibble = (hash.u(byte) shr ((acIndex and 1) shl 2)) and 15
                    ac.add((nibble / 7.5 - 1) * scale)
                    acIndex += 1
                    cx += 1
                }
            }
            return ac.toDoubleArray()
        }
        val lAC = decodeChannel(lx, ly, lScale)
        val pAC = decodeChannel(3, 3, pScale * 1.25)
        val qAC = decodeChannel(3, 3, qScale * 1.25)
        val aAC = if (hasAlpha) decodeChannel(5, 5, aScale) else DoubleArray(0)
        if (truncated) return null

        val w = (if (ratio > 1) 32.0 else 32 * ratio).roundAway()
        val h = (if (ratio > 1) 32 / ratio else 32.0).roundAway()
        if (w <= 0 || h <= 0) return null
        val rgba = ByteArray(w * h * 4)
        val nx = max(lx, if (hasAlpha) 5 else 3)
        val ny = max(ly, if (hasAlpha) 5 else 3)
        val fx = DoubleArray(nx)
        val fy = DoubleArray(ny)
        fun byte(v: Double): Byte = max(0.0, 255 * min(1.0, v)).toInt().toByte()
        var i = 0
        for (y in 0 until h) {
            for (cy in 0 until ny) fy[cy] = cos(PI / h * (y + 0.5) * cy)
            for (x in 0 until w) {
                var l = lDC
                var p = pDC
                var q = qDC
                var a = aDC
                for (cx in 0 until nx) fx[cx] = cos(PI / w * (x + 0.5) * cx)

                var j = 0
                for (cy in 0 until ly) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2
                    while (cx * ly < lx * (ly - cy)) {
                        l += lAC[j] * fx[cx] * fy2
                        cx += 1; j += 1
                    }
                }
                j = 0
                for (cy in 0 until 3) {
                    var cx = if (cy > 0) 0 else 1
                    val fy2 = fy[cy] * 2
                    while (cx < 3 - cy) {
                        val f = fx[cx] * fy2
                        p += pAC[j] * f
                        q += qAC[j] * f
                        cx += 1; j += 1
                    }
                }
                if (hasAlpha) {
                    j = 0
                    for (cy in 0 until 5) {
                        var cx = if (cy > 0) 0 else 1
                        val fy2 = fy[cy] * 2
                        while (cx < 5 - cy) {
                            a += aAC[j] * fx[cx] * fy2
                            cx += 1; j += 1
                        }
                    }
                }

                val b = l - 2.0 / 3 * p
                val r = (3 * l - b + q) / 2
                val g = r - q
                rgba[i] = byte(r); rgba[i + 1] = byte(g); rgba[i + 2] = byte(b); rgba[i + 3] = byte(a)
                i += 4
            }
        }
        return Image(w, h, rgba)
    }

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

    /** Rounds half away from zero (Kotlin's `round` is half to even). */
    private fun Double.roundAway(): Int = (if (this < 0) -Math.round(-this) else Math.round(this)).toInt()
}
