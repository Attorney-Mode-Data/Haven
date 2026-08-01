package sh.haven.core.data.preferences

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Companion to the core:ssh test of the same name — this enum drives the local
 * (proot) session wrapper, the SSH one drives remote attaches. They are
 * separate declarations of the same attach command and have drifted before, so
 * both are pinned.
 *
 * See the core:ssh test for the measured failure this prevents: a desktop
 * client attached to the same tmux session sizes the window wider than the
 * phone can render, and tmux gives the phone no way to reach the overflow.
 */
class SessionManagerWindowSizeTest {

    @Test
    fun `tmux attach pins the window to the smallest client`() {
        val cmd = UserPreferencesRepository.SessionManager.TMUX.command!!("work")
        assertTrue(
            "tmux attach must set window-size smallest so a larger second client " +
                "cannot push content out of reach of the phone. Got: $cmd",
            cmd.contains("set -gq window-size smallest"),
        )
    }

    @Test
    fun `byobu attach pins the window to the smallest client`() {
        val cmd = UserPreferencesRepository.SessionManager.BYOBU.command!!("work")
        assertTrue(
            "byobu wraps tmux and inherits the same sizing fight. Got: $cmd",
            cmd.contains("set -gq window-size smallest"),
        )
    }
}
