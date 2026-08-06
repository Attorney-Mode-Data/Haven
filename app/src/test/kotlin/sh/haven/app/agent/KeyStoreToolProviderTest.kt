package sh.haven.app.agent

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.TotpSecret
import sh.haven.core.data.repository.AgeIdentityRepository
import sh.haven.core.data.repository.TotpSecretRepository
import sh.haven.core.security.Totp

/**
 * `generate_totp_code` is the one key-store verb that hands a live credential
 * to the caller, so the two things worth pinning are that it asks first and
 * that the digits it produces are the ones the authenticator would show.
 */
class KeyStoreToolProviderTest {

    private val totpRepo = mockk<TotpSecretRepository>(relaxed = true)

    private fun provider() = KeyStoreToolProvider(
        totpSecretRepository = totpRepo,
        ageIdentityRepository = mockk<AgeIdentityRepository>(relaxed = true),
        agentUiCommandBus = AgentUiCommandBus(),
    )

    // Through handle(), not the private body — the same entry the dispatcher uses.
    private fun call(id: String): JSONObject = runBlocking {
        provider().tools()["generate_totp_code"]!!.handle(JSONObject().put("totpSecretId", id)).structured
    }

    @Test
    fun `revealing a code asks every time`() {
        assertEquals(
            ConsentLevel.EVERY_CALL,
            provider().tools()["generate_totp_code"]!!.consentLevel,
        )
    }

    /**
     * The failure this guards against is silent: defaulting to SHA1/6/30 for a
     * secret stored as something else returns six plausible digits that are
     * simply wrong, and the user reads that as "the server rejected my code".
     *
     * SHA256 + 8 digits + a 60s period is chosen so every one of those three
     * defaults would produce a different answer.
     */
    @Test
    fun `the stored algorithm digits and period are used, not the defaults`() {
        val base32 = "JBSWY3DPEHPK3PXP"
        val entity = TotpSecret(
            label = "work",
            secret = base32,
            issuer = "Example",
            accountName = "me",
            algorithm = "SHA256",
            digits = 8,
            periodSeconds = 60,
        )
        coEvery { totpRepo.getById("t1") } returns entity
        coEvery { totpRepo.getDecryptedSecret("t1") } returns base32

        val out = call("t1")
        val code = out.getString("code")
        val remaining = out.getInt("secondsRemaining")

        // Any instant inside the same 60s window yields the same code, so
        // reconstruct one from the response rather than racing the clock.
        val windowStart = System.currentTimeMillis() - (60 - remaining) * 1000L + 1_000L
        assertEquals(
            "the code must be generated with the secret's own parameters",
            Totp.generate(base32, windowStart, Totp.Algorithm.SHA256, 8, 60),
            code,
        )
        assertEquals("an 8-digit authenticator must return 8 digits", 8, code.length)
        assertEquals(60, out.getInt("periodSeconds"))
        assertTrue("secondsRemaining must fall inside the period, got $remaining", remaining in 1..60)
    }

    /** The derived code may cross the wire. The secret it came from may not. */
    @Test
    fun `the base32 secret is never in the response`() {
        val base32 = "JBSWY3DPEHPK3PXP"
        val entity = TotpSecret(label = "work", secret = base32, algorithm = "SHA1", digits = 6, periodSeconds = 30)
        coEvery { totpRepo.getById("t1") } returns entity
        coEvery { totpRepo.getDecryptedSecret("t1") } returns base32

        assertFalse(base32 in call("t1").toString())
    }

    @Test
    fun `an unknown id is refused rather than answered`() {
        coEvery { totpRepo.getById("nope") } returns null
        val e = runCatching { call("nope") }.exceptionOrNull()
        assertTrue("expected an IllegalArgumentException, got $e", e is IllegalArgumentException)
    }
}
