package sh.haven.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.rdp.RdpRect

/**
 * #422: the dirty region decides how much of the framebuffer gets repainted, so
 * getting it wrong is either a corrupt picture (too little) or the amplification
 * we are removing (too much). Null means "repaint everything".
 */
class ClipToFrameTest {

    private fun rect(x: Int, y: Int, w: Int, h: Int) =
        RdpRect(x.toUShort(), y.toUShort(), w.toUShort(), h.toUShort())

    @Test
    fun `a normal small rect passes through`() {
        assertEquals(
            RdpSession.ClippedRect(127, 82, 200, 100),
            RdpSession.clipToFrame(rect(127, 82, 200, 100), 1920, 1080),
        )
    }

    @Test
    fun `no rect means repaint everything`() {
        assertNull(RdpSession.clipToFrame(null, 1920, 1080))
    }

    @Test
    fun `a rect covering the whole frame repaints everything`() {
        // A tile blit of the full frame would just be a slower full copy.
        assertNull(RdpSession.clipToFrame(rect(0, 0, 1920, 1080), 1920, 1080))
    }

    @Test
    fun `an empty rect is not painted`() {
        assertNull(RdpSession.clipToFrame(rect(10, 10, 0, 50), 1920, 1080))
        assertNull(RdpSession.clipToFrame(rect(10, 10, 50, 0), 1920, 1080))
    }

    @Test
    fun `a rect running past the right edge is clipped, not dropped`() {
        assertEquals(
            RdpSession.ClippedRect(1900, 0, 20, 50),
            RdpSession.clipToFrame(rect(1900, 0, 500, 50), 1920, 1080),
        )
    }

    @Test
    fun `a rect running past the bottom edge is clipped`() {
        assertEquals(
            RdpSession.ClippedRect(0, 1000, 50, 80),
            RdpSession.clipToFrame(rect(0, 1000, 50, 500), 1920, 1080),
        )
    }

    @Test
    fun `a rect starting outside the frame is not painted`() {
        assertNull(RdpSession.clipToFrame(rect(1920, 0, 10, 10), 1920, 1080))
        assertNull(RdpSession.clipToFrame(rect(0, 1080, 10, 10), 1920, 1080))
    }

    @Test
    fun `a degenerate framebuffer repaints everything rather than indexing it`() {
        assertNull(RdpSession.clipToFrame(rect(0, 0, 10, 10), 0, 0))
    }
}
