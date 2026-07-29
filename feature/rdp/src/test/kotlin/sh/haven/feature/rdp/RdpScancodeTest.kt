package sh.haven.feature.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #422: arrow keys were sent as bare Set-1 scancodes. Those values are the
 * *numpad* twins of the navigation cluster — the real keys are E0-prefixed —
 * so Haven pressed the wrong key on every server.
 *
 * (The marker was NOT what made VirtualBox drop connections — VRDP rejects
 * any lone fast-path scancode PDU because it never advertises fast-path
 * input; the native layer handles that separately with a slow-path input
 * fallback. The marker is still required to press the right key.)
 *
 * The native layer detects extended keys with `code and 0xE000 == 0xE000`
 * (ironrdp `Scancode::from_u16`) and sets KBDFLAGS_EXTENDED from it, so the
 * marker is the whole contract between these constants and the wire.
 */
class RdpScancodeTest {

    private fun isExtended(code: Int) = (code and 0xE000) == 0xE000

    /** Every key that is E0-prefixed on a real keyboard must carry the marker. */
    @Test
    fun `navigation cluster and Windows key are extended`() {
        val extended = mapOf(
            "Up" to SC_UP, "Down" to SC_DOWN, "Left" to SC_LEFT, "Right" to SC_RIGHT,
            "Home" to SC_HOME, "End" to SC_END, "PgUp" to SC_PGUP, "PgDn" to SC_PGDN,
            "Insert" to SC_INSERT, "Delete" to SC_DELETE, "LeftWin" to SC_WIN_L,
        )
        extended.forEach { (name, code) ->
            assertTrue("$name (0x${code.toString(16)}) must set the 0xE000 extended marker", isExtended(code))
        }
    }

    /**
     * The other half of the contract: marking a key extended that is not
     * would press a different key just as wrongly, so the ordinary keys must
     * stay bare.
     */
    @Test
    fun `character control and function keys are not extended`() {
        val plain = mapOf(
            "Escape" to SC_ESCAPE, "Backspace" to SC_BACKSPACE, "Tab" to SC_TAB,
            "Return" to SC_RETURN, "LeftCtrl" to SC_CTRL_L, "LeftShift" to SC_SHIFT_L,
            "LeftAlt" to SC_ALT_L, "F1" to SC_F1, "F12" to SC_F12,
        )
        plain.forEach { (name, code) ->
            assertTrue("$name (0x${code.toString(16)}) must NOT be marked extended", !isExtended(code))
        }
    }

    /**
     * The low byte is what actually reaches the wire as the scancode; the
     * marker must not have disturbed it. These are the Set-1 values for the
     * navigation cluster.
     */
    @Test
    fun `the extended marker leaves the underlying scancode intact`() {
        assertEquals(0x48, SC_UP and 0xFF)
        assertEquals(0x50, SC_DOWN and 0xFF)
        assertEquals(0x4B, SC_LEFT and 0xFF)
        assertEquals(0x4D, SC_RIGHT and 0xFF)
        assertEquals(0x47, SC_HOME and 0xFF)
        assertEquals(0x4F, SC_END and 0xFF)
        assertEquals(0x49, SC_PGUP and 0xFF)
        assertEquals(0x51, SC_PGDN and 0xFF)
        assertEquals(0x52, SC_INSERT and 0xFF)
        assertEquals(0x53, SC_DELETE and 0xFF)
        assertEquals(0x5B, SC_WIN_L and 0xFF)
    }
}
