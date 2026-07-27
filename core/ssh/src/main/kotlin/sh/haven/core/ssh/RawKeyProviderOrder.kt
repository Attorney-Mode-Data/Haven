package sh.haven.core.ssh

import android.util.Log
import java.security.KeyFactory
import java.security.Provider
import java.security.Security

/**
 * Keeps AndroidKeyStore from winning JCE lookups for raw key material (#451).
 *
 * sshlib resolves its X25519 primitives without naming a provider:
 *
 *     KeyPairGenerator.getInstance("X25519")
 *     KeyFactory.getInstance("X25519")
 *
 * On some builds those two resolve to *different* providers, and the KeyFactory
 * lands on AndroidKeyStore — which by design only produces keys held in the
 * keystore. Converting a peer's ephemeral public key from an X509EncodedKeySpec
 * is something it can never do, so every curve25519 key exchange dies with
 * "To generate a key pair in Android Keystore, use KeyPairGenerator initialized
 * with android.security.keystore.KeyGenParameterSpec". Reported upstream as
 * connectbot/cbssh#246.
 *
 * AndroidKeyStore is never a correct answer for an *unpinned* raw-key lookup:
 * anything that reaches it that way was already going to fail. So when we detect
 * it winning one, move it to the end of the provider list. Callers that actually
 * want the keystore ask for it by name — `KeyStore.getInstance("AndroidKeyStore")`
 * and `getInstance(alg, "AndroidKeyStore")` resolve by name, not by order, and
 * are unaffected. Haven's own keystore-backed keys keep working.
 *
 * A workaround, not a fix: the real repair is upstream naming its provider. This
 * exists because waiting on an upstream release leaves the engine unusable on
 * the affected hardware.
 */
object RawKeyProviderOrder {

    private const val TAG = "RawKeyProviderOrder"
    internal const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** Raw-key algorithms sshlib resolves without naming a provider. */
    internal val RAW_KEY_ALGORITHMS = listOf("X25519", "XDH", "Ed25519", "EC")

    /**
     * True when [providerName] must not be serving an unpinned lookup for raw
     * key material. Pure so the policy is testable without a live JCE.
     */
    internal fun shouldDemote(providerName: String?): Boolean = providerName == KEYSTORE_PROVIDER

    /**
     * Demote AndroidKeyStore if it currently wins a raw-key KeyFactory lookup.
     * Safe to call more than once; does nothing when the order is already sane,
     * which is the case on most devices.
     *
     * @param probe resolves an algorithm to the provider name that would serve
     *   it, or null when the algorithm is unavailable.
     * @param demote moves the named provider to the end of the list.
     * @return the algorithm that triggered the demotion, or null if none did.
     */
    internal fun applyWith(
        probe: (String) -> String?,
        demote: (String) -> Boolean,
    ): String? {
        for (algorithm in RAW_KEY_ALGORITHMS) {
            if (!shouldDemote(probe(algorithm))) continue
            val moved = demote(KEYSTORE_PROVIDER)
            Log.w(
                TAG,
                "$KEYSTORE_PROVIDER was first for an unpinned $algorithm KeyFactory — " +
                    if (moved) {
                        "moved to the end of the provider list (#451)"
                    } else {
                        "could not reorder providers; sshlib connections may fail (#451)"
                    },
            )
            return algorithm
        }
        return null
    }

    /** Production entry point — call once during app start. */
    fun apply(): String? = applyWith(
        probe = { algorithm ->
            runCatching { KeyFactory.getInstance(algorithm).provider.name }.getOrNull()
        },
        demote = { name ->
            runCatching {
                val provider: Provider = Security.getProvider(name) ?: return@runCatching false
                Security.removeProvider(name)
                // addProvider appends, so the provider stays available by name
                // for explicit requests while no longer winning unpinned ones.
                Security.addProvider(provider) != -1
            }.getOrDefault(false)
        },
    )
}
