package sh.haven.core.ssh

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone attaching to a tmux session that a desktop is already attached to
 * ends up in a size fight. tmux defaults to `window-size latest`, so the window
 * is sized for whichever client was used most recently — when that is the
 * desktop, the phone can only render the leftmost N columns of a much wider
 * window, and tmux offers no horizontal scrolling for the rest.
 *
 * Measured on a real pair of clients (desktop 189x42, phone 72x33): forcing the
 * window to the desktop's size left the phone's emulator holding lines cut
 * dead at column 72 —
 *
 *     ∙ Resolve open GitHub issues      No resource problem — 19 GB free, hea
 *
 * with the remaining 117 columns unreachable. That reads exactly like the
 * "text is clipped and I can't scroll to it" report in #479, but it is a
 * different fault from the libvterm reflow bug fixed there: nothing is
 * destroyed, it is simply never sent to this client.
 *
 * `window-size smallest` makes the window fit the smallest attached client, so
 * the phone always sees everything (the desktop gets letterboxed instead — the
 * deliberate trade, since the small screen is the one that cannot recover).
 * Set with `-gq` alongside the mouse/passthrough options Haven already applies
 * on attach.
 */
class SessionManagerWindowSizeTest {

    @Test
    fun `tmux attach pins the window to the smallest client`() {
        val cmd = SessionManager.TMUX.command!!("work")
        assertTrue(
            "tmux attach must set window-size smallest, or a desktop client " +
                "attached to the same session sizes the window beyond what the " +
                "phone can display and the overflow is unreachable. Got: $cmd",
            cmd.contains("set -gq window-size smallest"),
        )
    }

    /** byobu is tmux underneath, so it has the same failure and the same fix. */
    @Test
    fun `byobu attach pins the window to the smallest client`() {
        val cmd = SessionManager.BYOBU.command!!("work")
        assertTrue(
            "byobu wraps tmux and inherits the same multi-client sizing fight. Got: $cmd",
            cmd.contains("set -gq window-size smallest"),
        )
    }

    /**
     * The options are passed as tmux command arguments, not shell commands, so
     * the separators must survive as literal `\;` — an unescaped `;` would end
     * the shell command and tmux would never see the option (#358 territory).
     */
    @Test
    fun `the option is chained with an escaped separator`() {
        val cmd = SessionManager.TMUX.command!!("work")
        assertTrue(
            "window-size must be chained with an escaped ';' or the shell eats it. Got: $cmd",
            cmd.contains("\\; set -gq window-size smallest"),
        )
    }
}
