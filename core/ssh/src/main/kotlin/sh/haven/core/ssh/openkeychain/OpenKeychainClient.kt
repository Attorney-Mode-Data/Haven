package sh.haven.core.ssh.openkeychain

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import org.openintents.ssh.authentication.ISshAuthenticationService
import org.openintents.ssh.authentication.SshAuthenticationApiError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "OpenKeychainClient"

/** How long to wait for the provider's service to bind. */
private const val BIND_TIMEOUT_SECONDS = 10L

/**
 * How long to wait for the user to deal with the provider's prompt — a
 * passphrase, or a PIN and a tap on a hardware token. Deliberately generous:
 * finding the token and holding it to the phone is not a fast operation, and
 * the cost of being too short is an authentication that fails after the user
 * did everything right.
 */
private const val USER_INTERACTION_TIMEOUT_SECONDS = 180L

/** The provider refused the request; [error] is its code, if it sent one. */
class OpenKeychainException(
    message: String,
    val error: Int? = null,
) : Exception(message)

/**
 * Talks to an SSH Authentication API provider (#487).
 *
 * Every call blocks, because the only caller that matters is JSch's
 * authentication, which is synchronous — the same reason
 * `sh.haven.core.fido.FidoIdentity` blocks on the hardware token.
 *
 * A request can come back asking for user interaction (unlock the key, enter
 * the PIN, tap the token). That is not a failure: the provider hands over a
 * [PendingIntent], and once the user has finished with it the original
 * request is re-sent and succeeds. [OpenKeychainPromptActivity] runs that
 * step, since a signature requested from a background thread has no activity
 * of its own to launch from.
 */
class OpenKeychainClient(
    context: Context,
    private val providerPackage: String,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var service: ISshAuthenticationService? = null
    private var connection: ServiceConnection? = null

    /**
     * Bind, blocking until the service arrives or [BIND_TIMEOUT_SECONDS]
     * passes. Idempotent — a live binding is reused.
     */
    @Synchronized
    private fun bind(): ISshAuthenticationService {
        service?.let { return it }

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = ISshAuthenticationService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        val intent = Intent(OpenKeychainApi.SERVICE_INTENT).setPackage(providerPackage)
        val started = try {
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            throw OpenKeychainException("Not allowed to reach $providerPackage: ${e.message}")
        }
        if (!started) {
            throw OpenKeychainException(
                "$providerPackage did not accept the connection — is it still installed?",
            )
        }
        connection = conn

        if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw OpenKeychainException("$providerPackage did not respond within ${BIND_TIMEOUT_SECONDS}s")
        }
        return service ?: throw OpenKeychainException("$providerPackage connected without a service")
    }

    /** Release the binding. Safe to call when never bound. */
    @Synchronized
    fun close() {
        connection?.let {
            runCatching { appContext.unbindService(it) }
                .onFailure { e -> Log.w(TAG, "unbind failed: ${e.message}") }
        }
        connection = null
        service = null
    }

    /**
     * Send [request], resolving one round of user interaction if the provider
     * asks for it.
     *
     * Only one round is resolved. A provider that asks again after the user
     * has already satisfied a prompt is not making progress, and retrying
     * indefinitely would trap the connection in a prompt loop with no way out.
     */
    private fun execute(request: Intent): Intent {
        request.putExtra(OpenKeychainApi.EXTRA_API_VERSION, OpenKeychainApi.API_VERSION)
        val first = bind().execute(request)
        return when (first.getIntExtra(OpenKeychainApi.EXTRA_RESULT_CODE, OpenKeychainApi.RESULT_CODE_ERROR)) {
            OpenKeychainApi.RESULT_CODE_SUCCESS -> first
            OpenKeychainApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                val pending = pendingIntentOf(first)
                    ?: throw OpenKeychainException("Provider asked for user interaction but sent no prompt")
                if (!OpenKeychainPromptActivity.await(appContext, pending)) {
                    throw OpenKeychainException("Cancelled")
                }
                val second = bind().execute(request)
                if (second.getIntExtra(OpenKeychainApi.EXTRA_RESULT_CODE, OpenKeychainApi.RESULT_CODE_ERROR) ==
                    OpenKeychainApi.RESULT_CODE_SUCCESS
                ) {
                    second
                } else {
                    throw errorFrom(second)
                }
            }
            else -> throw errorFrom(first)
        }
    }

    /**
     * The public key for [keyId], in OpenSSH form.
     *
     * Fetched when the key is first chosen, and stored, so that connecting
     * does not depend on the provider being reachable until there is an
     * actual signature to make.
     */
    fun fetchPublicKey(keyId: String): OpenKeychainPublicKey {
        val result = execute(
            Intent(OpenKeychainApi.ACTION_GET_SSH_PUBLIC_KEY)
                .putExtra(OpenKeychainApi.EXTRA_KEY_ID, keyId),
        )
        val line = result.getStringExtra(OpenKeychainApi.EXTRA_SSH_PUBLIC_KEY)
            ?: throw OpenKeychainException("Provider returned no public key for $keyId")
        return OpenKeychainApi.parsePublicKey(line)
            ?: throw OpenKeychainException("Provider returned an unreadable public key: $line")
    }

    /**
     * Sign [challenge] as [sshAlgorithm], returning the SSH signature blob.
     *
     * The provider encodes the blob itself — algorithm name and signature,
     * per RFC 4253 — so the result goes to JSch untouched. For RSA the hash
     * also picks the algorithm name, which is why it is derived from
     * [sshAlgorithm] rather than fixed.
     */
    fun sign(keyId: String, challenge: ByteArray, sshAlgorithm: String): ByteArray {
        val hash = OpenKeychainApi.hashAlgorithmFor(sshAlgorithm)
            ?: throw OpenKeychainException("$sshAlgorithm is not an algorithm this provider can sign with")
        val result = execute(
            Intent(OpenKeychainApi.ACTION_SIGN)
                .putExtra(OpenKeychainApi.EXTRA_KEY_ID, keyId)
                .putExtra(OpenKeychainApi.EXTRA_CHALLENGE, challenge)
                .putExtra(OpenKeychainApi.EXTRA_HASH_ALGORITHM, hash),
        )
        return result.getByteArrayExtra(OpenKeychainApi.EXTRA_SIGNATURE)
            ?: throw OpenKeychainException("Provider returned no signature")
    }

    /**
     * Show the provider's key chooser and return what the user picked, or
     * null if they backed out.
     *
     * The provider drives this entirely through the user-interaction path:
     * the first response is always the chooser prompt, and the id only comes
     * back on the retry.
     */
    fun selectKey(): OpenKeychainSelection? = try {
        val result = execute(Intent(OpenKeychainApi.ACTION_SELECT_KEY))
        val keyId = result.getStringExtra(OpenKeychainApi.EXTRA_KEY_ID)
        if (keyId == null) {
            null
        } else {
            OpenKeychainSelection(
                keyId = keyId,
                description = result.getStringExtra(OpenKeychainApi.EXTRA_KEY_DESCRIPTION).orEmpty(),
            )
        }
    } catch (e: OpenKeychainException) {
        if (e.message == "Cancelled") null else throw e
    }

    private fun errorFrom(result: Intent): OpenKeychainException {
        val error = errorExtra(result)
        val detail = error?.message?.takeIf { it.isNotBlank() }
        return OpenKeychainException(
            detail ?: "The key provider refused the request",
            error?.error,
        )
    }

    @Suppress("DEPRECATION")
    private fun errorExtra(result: Intent): SshAuthenticationApiError? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getParcelableExtra(OpenKeychainApi.EXTRA_ERROR, SshAuthenticationApiError::class.java)
        } else {
            result.getParcelableExtra(OpenKeychainApi.EXTRA_ERROR)
        }
    } catch (e: RuntimeException) {
        // A malformed parcel must not mask the failure it describes.
        Log.w(TAG, "unreadable error extra: ${e.message}")
        null
    }

    @Suppress("DEPRECATION")
    private fun pendingIntentOf(result: Intent): PendingIntent? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getParcelableExtra(OpenKeychainApi.EXTRA_PENDING_INTENT, PendingIntent::class.java)
        } else {
            result.getParcelableExtra(OpenKeychainApi.EXTRA_PENDING_INTENT)
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "unreadable pending intent: ${e.message}")
        null
    }
}

/** A key the user picked from the provider's chooser. */
data class OpenKeychainSelection(val keyId: String, val description: String)

/**
 * Runs a provider's [PendingIntent] and reports whether the user completed it.
 *
 * A signature is requested from JSch's thread, which has no activity to
 * launch a prompt from and may be running while Haven is in the background.
 * This activity is that launcher: transparent, started in its own task, alive
 * only for the round trip.
 */
class OpenKeychainPromptActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getLongExtra(EXTRA_TOKEN, -1L)
        if (savedInstanceState != null) return // already launched; waiting on the result

        @Suppress("DEPRECATION")
        val pending: PendingIntent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_PENDING, PendingIntent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_PENDING)
            }
        if (pending == null) {
            finishWith(false)
            return
        }
        try {
            startIntentSenderForResult(pending.intentSender, REQUEST_CODE, null, 0, 0, 0)
        } catch (e: android.content.IntentSender.SendIntentException) {
            Log.w(TAG, "prompt could not be shown: ${e.message}")
            finishWith(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) finishWith(resultCode == RESULT_OK)
    }

    private fun finishWith(completed: Boolean) {
        waiters.remove(token)?.complete(completed)
        finish()
    }

    private var token: Long = -1L

    companion object {
        private const val REQUEST_CODE = 0x0C4A
        private const val EXTRA_PENDING = "sh.haven.openkeychain.PENDING"
        private const val EXTRA_TOKEN = "sh.haven.openkeychain.TOKEN"

        private val nextToken = AtomicLong(1)
        private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

        /**
         * Show [pending] and block until the user is done with it.
         *
         * Returns false if they cancelled, or if the prompt could not be
         * shown at all — both mean no signature, and the caller reports it
         * the same way.
         */
        fun await(context: Context, pending: PendingIntent): Boolean {
            val token = nextToken.getAndIncrement()
            val deferred = CompletableDeferred<Boolean>()
            waiters[token] = deferred

            val launch = Intent(context, OpenKeychainPromptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PENDING, pending)
                .putExtra(EXTRA_TOKEN, token)
            context.startActivity(launch)

            return try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(
                        USER_INTERACTION_TIMEOUT_SECONDS * 1000,
                    ) { deferred.await() } ?: false
                }
            } finally {
                waiters.remove(token)
            }
        }
    }
}
