package sh.haven.feature.rdp

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
     * The constants above are only half the story: the bug was in the
     * *mapping*, which sent both Alt keys to the same constant. This is the
     * test that fails on the shipped code.
     */
    @Test
    fun `each right-hand modifier maps to its own scancode`() {
        assertEquals(SC_ALT_R, androidKeyToScancode(Key.AltRight))
        assertEquals(SC_CTRL_R, androidKeyToScancode(Key.CtrlRight))
        assertEquals(SC_SHIFT_R, androidKeyToScancode(Key.ShiftRight))
        assertEquals(SC_WIN_R, androidKeyToScancode(Key.MetaRight))

        assertEquals(SC_ALT_L, androidKeyToScancode(Key.AltLeft))
        assertEquals(SC_CTRL_L, androidKeyToScancode(Key.CtrlLeft))
        assertEquals(SC_SHIFT_L, androidKeyToScancode(Key.ShiftLeft))
        assertEquals(SC_WIN_L, androidKeyToScancode(Key.MetaLeft))
    }

    /**
     * #504: right-hand modifiers are separate keys, not aliases for the left
     * ones.
     *
     * A reporter ran `showkey` on his guest's console and reported AltGr
     * arriving as scancode 56 — that is 0x38, *left* Alt. Both Alt keys were
     * mapped to the same constant. On a Polish layout AltGr+o is ó, and the
     * guest was being told he had pressed a modifier that composes nothing, so
     * the character simply never appeared.
     *
     * The measurement is what made this findable: "56, six times" is a fact
     * about the wire, not a description of a symptom.
     */
    @Test
    fun `right-hand modifiers differ from their left-hand twins`() {
        assertNotEquals("AltGr must not be sent as left Alt", SC_ALT_L, SC_ALT_R)
        assertNotEquals("right Ctrl must not be sent as left Ctrl", SC_CTRL_L, SC_CTRL_R)
        assertNotEquals("right Shift must not be sent as left Shift", SC_SHIFT_L, SC_SHIFT_R)
        assertNotEquals("right Win must not be sent as left Win", SC_WIN_L, SC_WIN_R)
    }

    /**
     * Right Ctrl, Alt and Win are E0-prefixed. Right **Shift** is not — it is
     * its own base code, 0x36, and marking it extended would press something
     * else. That asymmetry is the easy thing to get wrong here.
     */
    @Test
    fun `right Ctrl Alt and Win are extended, right Shift is not`() {
        assertTrue("AltGr (right Alt) is E0-prefixed", isExtended(SC_ALT_R))
        assertTrue("right Ctrl is E0-prefixed", isExtended(SC_CTRL_R))
        assertTrue("right Win is E0-prefixed", isExtended(SC_WIN_R))
        assertFalse("right Shift is a base scancode, not an extended one", isExtended(SC_SHIFT_R))
        assertEquals("right Shift is 0x36", 0x36, SC_SHIFT_R)
    }

    /**
     * The low byte is what reaches the guest — `Scancode::from_u16` truncates
     * to `scancode as u8` and carries the extended bit separately. So AltGr
     * must be 0x38 *with* the marker, which is a different key from 0x38
     * without it.
     */
    @Test
    fun `the extended modifiers keep the right base code`() {
        assertEquals("AltGr base code", 0x38, SC_ALT_R and 0xFF)
        assertEquals("right Ctrl base code", 0x1D, SC_CTRL_R and 0xFF)
        assertEquals("right Win base code", 0x5C, SC_WIN_R and 0xFF)
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
