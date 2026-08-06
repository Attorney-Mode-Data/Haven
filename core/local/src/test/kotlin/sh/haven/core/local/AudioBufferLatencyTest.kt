package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #442: a reporter proposed replacing the loopback TCP hop with PulseAudio's
 * shared memory to cut audio latency. Before doing that it is worth knowing
 * what the render buffer alone costs, because that is a floor no change of
 * transport can get under.
 *
 * These pin the conversion the log line uses to say so.
 */
class AudioBufferLatencyTest {

    @Test
    fun `the bridge's 64 KB floor is a third of a second of audio`() {
        // 48 kHz, 2 channels, 2 bytes per sample = 192000 B/s.
        assertEquals(341L, pcmBufferMillis(64 * 1024, 48000))
    }

    @Test
    fun `halving the buffer halves the latency`() {
        assertEquals(170L, pcmBufferMillis(32 * 1024, 48000))
        assertEquals(85L, pcmBufferMillis(16 * 1024, 48000))
    }

    /** Mono or 8-bit would double it; the format is not assumed silently. */
    @Test
    fun `channel count and sample width are part of the answer`() {
        assertEquals(682L, pcmBufferMillis(64 * 1024, 48000, channels = 1))
        assertEquals(682L, pcmBufferMillis(64 * 1024, 48000, bytesPerSample = 1))
    }

    /** A zero rate is a misconfiguration, not a divide-by-zero crash. */
    @Test
    fun `a nonsense sample rate reports zero rather than throwing`() {
        assertEquals(0L, pcmBufferMillis(64 * 1024, 0))
    }
}
