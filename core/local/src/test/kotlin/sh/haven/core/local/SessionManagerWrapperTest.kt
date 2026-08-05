package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-manager launch wrapper (#294): tmux/zellij/etc that fail to
 * start must drop the user into a login shell instead of killing the tab.
 */
class SessionManagerWrapperTest {

    private val tmuxCmd =
        "tmux new-session -A -s haven-local \\; set -gq allow-passthrough on \\; set -gq mouse on"

    @Test
    fun `missing binary falls back to a login shell`() {
        val w = sessionManagerWrapper("tmux", tmuxCmd)
        assertTrue("guards on command -v", w.contains("command -v tmux"))
        assertTrue("else-branch execs a login shell", w.contains("else $GUEST_LOGIN_SHELL; fi"))
    }

    @Test
    fun `installed-but-failing session manager degrades to a shell instead of exiting`() {
        val w = sessionManagerWrapper("tmux", tmuxCmd)
        // The command is run (not exec'd) so a non-zero exit can trip the ||.
        assertTrue("does not exec the session manager", !w.contains("then exec $tmuxCmd"))
        assertTrue("runs the command", w.contains("then $tmuxCmd ||"))
        assertTrue("|| falls back to a login shell", w.contains("$GUEST_LOGIN_SHELL; }"))
        assertTrue("surfaces the failure", w.contains("exited unexpectedly"))
    }

    @Test
    fun `tmux command separators are preserved verbatim`() {
        val w = sessionManagerWrapper("tmux", tmuxCmd)
        // The escaped ';' must reach tmux as literal args, not shell separators.
        assertTrue(w.contains("new-session -A -s haven-local \\; set -gq allow-passthrough on"))
    }

    /**
     * The wrapper is shell source we assemble by concatenation, so the only
     * assertion that really covers it is whether a shell can parse it. Added
     * when the login shell became a substituted constant (#501) — splicing a
     * multi-line `if … fi` into the `|| { … }` branch produces something that
     * reads fine and is not valid shell, and no `contains` check would notice.
     */
    @Test
    fun `every wrapper we emit is syntactically valid shell`() {
        listOf(
            sessionManagerWrapper("tmux", tmuxCmd),
            sessionManagerWrapper("zellij", "zellij attach haven-local --create"),
            sessionManagerWrapper("screen", "screen -xRR haven-local"),
            GUEST_LOGIN_SHELL,
        ).forEach { script ->
            val exit = ProcessBuilder("sh", "-n", "-c", script)
                .redirectErrorStream(true).start().waitFor()
            assertEquals("sh -n rejected: $script", 0, exit)
        }
    }

    @Test
    fun `the guest login shell prefers bash and falls back to sh`() {
        assertTrue("asks the guest, not the app", GUEST_LOGIN_SHELL.contains("command -v bash"))
        assertTrue("falls back to the previous behaviour", GUEST_LOGIN_SHELL.contains("/bin/sh"))
        assertTrue("replaces the shell rather than nesting one", GUEST_LOGIN_SHELL.startsWith("exec "))
        assertTrue("a login shell, so profile files still run", GUEST_LOGIN_SHELL.endsWith(" -l"))
    }

    @Test
    fun `zellij is wrapped the same way`() {
        val w = sessionManagerWrapper("zellij", "zellij attach haven-local --create")
        assertTrue(w.contains("command -v zellij"))
        assertTrue(w.contains("zellij attach haven-local --create ||"))
    }
}
