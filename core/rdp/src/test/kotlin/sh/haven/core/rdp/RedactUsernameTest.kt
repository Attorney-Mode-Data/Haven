package sh.haven.core.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * #477: a reporter deleted three log attachments after noticing his Windows
 * account name was in every one, which stopped a diagnosis dead.
 *
 * The rule these pin is simple and easy to erode: whatever we print, none of
 * the name's characters may appear in it. The shape is kept deliberately —
 * #461 was sspi truncating `me@example.com` at the `@` under NLA, and "does
 * this name contain an @" was the question that found it.
 */
class RedactUsernameTest {

    /**
     * The property that matters: the output tells you nothing about the
     * *content*. Two different names of the same length and qualification
     * style must redact to the same string.
     *
     * My first attempt asserted "no character of the name appears in the
     * output", which is a different and wrong property — the word "chars"
     * contains c, h, a, r and s, so any username using those letters failed a
     * correct implementation. Worth leaving noted: the test was the thing
     * that was broken.
     */
    @Test
    fun `names of the same shape are indistinguishable after redaction`() {
        assertEquals(redactUsername("alice"), redactUsername("bobby"))
        assertEquals(redactUsername("Quickemu"), redactUsername("zzzzzzzz"))
        assertEquals(redactUsername("me@aa.com"), redactUsername("yo@bb.org"))
        assertEquals(redactUsername("CORP\\bob"), redactUsername("ACME\\joe"))
    }

    /** And the name itself never appears. */
    @Test
    fun `the username is never quoted back`() {
        listOf(
            "Quickemu",
            "alice",
            "CORP\\bob",
            "me@example.com",
            "MicrosoftAccount\\someone@outlook.com",
            "Ian Williams",
        ).forEach { name ->
            assertFalse(
                "redaction of '$name' quoted it back",
                redactUsername(name).contains(name),
            )
        }
    }

    @Test
    fun `the qualification style is kept, because it is what found 461`() {
        assertEquals("<14 chars, upn>", redactUsername("me@example.com"))
        assertEquals("<8 chars, domain\\user>", redactUsername("CORP\\bob"))
        assertEquals("<5 chars>", redactUsername("alice"))
    }

    @Test
    fun `an empty username says so rather than printing nothing`() {
        assertEquals("<none>", redactUsername(""))
    }

    /**
     * Length is a weak identifier on its own but a strong one combined with a
     * guess, so it is worth being deliberate that this is the trade: the
     * length is kept because "the server rejected an 18-character UPN" is a
     * usable report and "the server rejected a username" is not.
     */
    @Test
    fun `length is reported`() {
        assertEquals("<3 chars>", redactUsername("abc"))
        assertEquals("<30 chars>", redactUsername("a".repeat(30)))
    }
}
