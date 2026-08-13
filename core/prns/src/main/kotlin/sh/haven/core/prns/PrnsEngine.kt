package sh.haven.core.prns

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import rs.reticulum.prns.Bitrate
import rs.reticulum.prns.BitrateAuto
import rs.reticulum.prns.Capability
import rs.reticulum.prns.CommandFailed
import rs.reticulum.prns.CommandSettlement
import rs.reticulum.prns.CommandSucceeded
import rs.reticulum.prns.DestinationConfig
import rs.reticulum.prns.Host
import rs.reticulum.prns.HostOptions
import rs.reticulum.prns.HostRole
import rs.reticulum.prns.IdentityConfig
import rs.reticulum.prns.IdentityConfigGenerateEphemeral
import rs.reticulum.prns.SuppliedPipe
import rs.reticulum.prns.SuppliedPipeOpener

/**
 * Opens one CONNECTED file descriptor for the engine, or returns a negative
 * value / throws to decline this attempt (the engine retries after the
 * attachment's respawn delay). This is where Haven's tunnel-aware dialing —
 * and, if Haven ever hosts a VpnService, `protect()` — happens: the open runs
 * in the caller's own coroutine context, never in a native callback
 * (Prns#94's pull-driven contract).
 */
typealias PrnsFdOpener = suspend () -> Int

/**
 * Thin lifecycle owner for a Prns (Personal Reticulum, Rust) host with
 * app-supplied transports. Second Reticulum engine next to reticulum-kt
 * ([sh.haven.core.reticulum.ReticulumTransport]'s kotlin stack) — experimental,
 * not yet wired into the connection flow.
 *
 * The native capsule (libprns_host.so) is built from the `prns` submodule by
 * `:core:prns:buildPrnsNative`; JVM tests point at a host build via
 * `-Dpersonal.rns.library=`.
 */
class PrnsEngine(
    private val scope: CoroutineScope,
) : AutoCloseable {

    private var host: Host? = null
    private val pipes = mutableListOf<Pair<SuppliedPipe, Job>>()

    val isOpen: Boolean get() = host != null

    /** Whether the running host's backend supports app-supplied transports. */
    fun supportsSuppliedPipe(): Boolean =
        requireHost().backendInfo.capabilities.contains(Capability.SUPPLIED_PIPE)

    fun open(
        role: HostRole = HostRole.ENDPOINT,
        identity: IdentityConfig = IdentityConfigGenerateEphemeral,
        destinations: List<DestinationConfig> = emptyList(),
    ) {
        check(host == null) { "engine already open" }
        host = Host(
            HostOptions(
                role = role,
                identity = identity,
                destinations = destinations,
                requiredCapabilities = setOf(Capability.SUPPLIED_PIPE),
            ),
        )
    }

    /**
     * Attaches a supplied-Pipe interface whose descriptors come from [opener]
     * and starts serving its open requests on the engine scope. Returns the
     * attachment settlement (the interface is live once it succeeds).
     */
    suspend fun attachSupplied(
        name: String,
        respawnDelayMillis: Long,
        opener: PrnsFdOpener,
        bitrate: Bitrate = BitrateAuto,
    ): CommandSettlement {
        val pipe = requireHost().beginSuppliedPipe(name, respawnDelayMillis, bitrate)
        val serveJob = scope.launch {
            pipe.serve(SuppliedPipeOpener { opener() })
        }
        pipes += pipe to serveJob
        val settlement = pipe.awaitAttachment()
        if (settlement is CommandFailed) {
            serveJob.cancel()
            pipe.close()
            pipes.removeAll { it.first === pipe }
        }
        return settlement
    }

    fun requireHost(): Host = checkNotNull(host) { "engine not open" }

    override fun close() {
        pipes.forEach { (pipe, job) ->
            job.cancel()
            pipe.close()
        }
        pipes.clear()
        host?.close()
        host = null
    }
}

/** True when a settlement carries a successful outcome. */
fun CommandSettlement.succeeded(): Boolean = this is CommandSucceeded
