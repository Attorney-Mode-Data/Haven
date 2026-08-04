package sh.haven.core.rdp

import org.junit.Test
import java.nio.ByteBuffer

/**
 * #466: how long does the CPU YUV->RGBA conversion actually take at 1080p?
 *
 * A reporter's v5.86.39 logcat shows `decode` at 120-243 ms per frame against
 * KRDP at 1920x1080 while `publish` is under 3 ms. The `decode` timer spans
 * MediaCodec *plus* this conversion, and hardware H.264 on that phone
 * (c2.exynos.h264.decoder) should be well under 10 ms — so the question is
 * whether this loop accounts for the rest.
 *
 * This is a floor, not the phone's number: a desktop JVM with a warm JIT is
 * considerably faster than an Exynos core.
 */
class Avc420YuvBenchTest {

    @Test
    fun `1080p yuv to rgba conversion cost`() {
        val w = 1920
        val h = 1080
        val cw = w
        val ch = h
        val y = ByteBuffer.allocateDirect(w * h)
        val u = ByteBuffer.allocateDirect(w * h / 4)
        val v = ByteBuffer.allocateDirect(w * h / 4)
        for (i in 0 until y.capacity()) y.put(i, (i and 0xFF).toByte())
        for (i in 0 until u.capacity()) u.put(i, ((i * 3) and 0xFF).toByte())
        for (i in 0 until v.capacity()) v.put(i, ((i * 7) and 0xFF).toByte())
        val out = ByteArray(w * h * 4)

        val dec = Avc420MediaCodecDecoder()
        // warm the JIT
        repeat(3) { dec.yuvToRgba(out, y, u, v, w, w / 2, w / 2, 1, 1, w, h, cw, ch) }

        val runs = 10
        val t0 = System.nanoTime()
        repeat(runs) { dec.yuvToRgba(out, y, u, v, w, w / 2, w / 2, 1, 1, w, h, cw, ch) }
        val perFrameMs = (System.nanoTime() - t0) / 1_000_000.0 / runs

        println("YUV->RGBA 1920x1080 on this JVM: %.1f ms/frame".format(perFrameMs))
        println("  (a phone core is typically 3-8x slower => %.0f-%.0f ms)".format(perFrameMs * 3, perFrameMs * 8))
        // No assertion on absolute speed — machines differ. This exists to
        // produce the number, and to fail loudly if the function ever breaks.
        assert(out.any { it != 0.toByte() }) { "conversion produced nothing" }
    }
}
