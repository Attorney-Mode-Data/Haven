package sh.haven.core.rdp

import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #466: the AVC420 colour conversion was rewritten to reuse each chroma sample
 * across the two pixels that share it (the dominant per-frame cost at 4K). It
 * must stay bit-identical to the straightforward per-pixel form, including the
 * awkward cases: odd widths, edge replication when the requested size exceeds
 * the decoded image, and both planar (pixelStride 1) and semi-planar
 * (pixelStride 2) chroma.
 */
class Avc420YuvToRgbaTest {

    /** The original per-pixel implementation, kept verbatim as the oracle. */
    private fun reference(
        out: ByteArray,
        yBuf: ByteBuffer, uBuf: ByteBuffer, vBuf: ByteBuffer,
        yRow: Int, uRow: Int, vRow: Int,
        uPix: Int, vPix: Int,
        w: Int, h: Int, cw: Int, ch: Int,
    ) {
        fun clamp(v: Int): Byte = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
        var o = 0
        for (y in 0 until h) {
            val sy = if (y < ch) y else ch - 1
            val yLine = sy * yRow
            val cLine = (sy shr 1)
            val uLine = cLine * uRow
            val vLine = cLine * vRow
            for (x in 0 until w) {
                val sx = if (x < cw) x else cw - 1
                val yv = (yBuf.get(yLine + sx).toInt() and 0xFF) - 16
                val cx = sx shr 1
                val uv = (uBuf.get(uLine + cx * uPix).toInt() and 0xFF) - 128
                val vv = (vBuf.get(vLine + cx * vPix).toInt() and 0xFF) - 128
                val c = if (yv < 0) 0 else yv * 298
                out[o] = clamp((c + 409 * vv + 128) shr 8)
                out[o + 1] = clamp((c - 100 * uv - 208 * vv + 128) shr 8)
                out[o + 2] = clamp((c + 516 * uv + 128) shr 8)
                out[o + 3] = 0xFF.toByte()
                o += 4
            }
        }
    }

    private fun check(w: Int, h: Int, cw: Int, ch: Int, uPix: Int, seed: Int) {
        val rnd = Random(seed)
        // Row strides deliberately wider than the image, as real decoders emit.
        val yRow = cw + 7
        val cRow = cw * uPix + 5
        val yBuf = ByteBuffer.wrap(ByteArray(yRow * (ch + 2)).also { rnd.nextBytes(it) })
        val uBuf = ByteBuffer.wrap(ByteArray(cRow * (ch / 2 + 2)).also { rnd.nextBytes(it) })
        val vBuf = ByteBuffer.wrap(ByteArray(cRow * (ch / 2 + 2)).also { rnd.nextBytes(it) })

        val expected = ByteArray(w * h * 4)
        reference(
            expected, yBuf, uBuf, vBuf, yRow, cRow, cRow, uPix, uPix, w, h, cw, ch,
        )
        val actual = ByteArray(w * h * 4)
        Avc420MediaCodecDecoder().yuvToRgba(
            actual, yBuf, uBuf, vBuf, yRow, cRow, cRow, uPix, uPix, w, h, cw, ch,
        )
        assertArrayEquals("w=$w h=$h cw=$cw ch=$ch uPix=$uPix", expected, actual)
    }

    @Test fun `semi-planar NV12, exact fit`() = check(w = 64, h = 32, cw = 64, ch = 32, uPix = 2, seed = 1)

    @Test fun `planar I420, exact fit`() = check(w = 64, h = 32, cw = 64, ch = 32, uPix = 1, seed = 2)

    @Test fun `odd width leaves a tail pixel`() = check(w = 33, h = 16, cw = 33, ch = 16, uPix = 2, seed = 3)

    @Test fun `requested wider than decoded replicates the edge column`() =
        check(w = 40, h = 16, cw = 24, ch = 16, uPix = 2, seed = 4)

    @Test fun `requested taller than decoded replicates the edge row`() =
        check(w = 32, h = 24, cw = 32, ch = 12, uPix = 2, seed = 5)

    @Test fun `odd decoded width with edge replication`() =
        check(w = 32, h = 16, cw = 17, ch = 16, uPix = 2, seed = 6)

    @Test fun `single decoded column`() = check(w = 16, h = 8, cw = 1, ch = 8, uPix = 2, seed = 7)

    /**
     * #477: the frame handed to UniFFI must not be copied when the scratch
     * buffer is already the right size.
     *
     * The decoder reuses one RGBA buffer, and the old code copied all of it on
     * every frame to avoid handing out shared state — 8.29 MB per frame at
     * 1080p, ~250 MB/s of garbage at 30 fps. The copy was unnecessary because
     * the generated binding lowers the array into a RustBuffer synchronously,
     * inside the callback, before anything can overwrite it.
     *
     * Asserting *identity* here is the point: an equality check would pass just
     * as happily with the copy reinstated, and the allocation is the whole cost
     * being removed.
     */
    @Test
    fun `an exactly-sized frame is handed over without copying`() {
        val decoder = Avc420MediaCodecDecoder()
        val scratch = ByteArray(64) { it.toByte() }

        assertSame(
            "a frame already the requested size must be passed through, not reallocated",
            scratch,
            decoder.exactly(scratch, 64),
        )
    }

    /**
     * The other half of the contract: UniFFI wants exactly `need` bytes, so an
     * oversized scratch buffer — left over from a larger frame before the
     * desktop was resized down — must still be trimmed rather than passed
     * through with junk on the end.
     */
    @Test
    fun `an oversized scratch buffer is trimmed to the frame size`() {
        val decoder = Avc420MediaCodecDecoder()
        val scratch = ByteArray(128) { it.toByte() }

        val out = decoder.exactly(scratch, 64)

        assertEquals("must be trimmed to the requested length", 64, out.size)
        assertArrayEquals("and keep the leading bytes", scratch.copyOf(64), out)
    }
}
