package sh.haven.rclone.bridge

import sh.haven.rclone.binding.rcbridge.Rcbridge

/**
 * Thin Kotlin wrapper around the gomobile-generated Java bindings for the
 * Go rcbridge module.  All rclone functionality is accessed via the [rpc]
 * method which calls rclone's RC (Remote Control) API.
 */
object RcloneBridge {

    data class RpcResult(
        val status: Int,
        val output: String,
    ) {
        val isOk: Boolean get() = status == 200
    }

    private var initialized = false

    /**
     * Whether this build actually carries rclone's native code.
     *
     * Haven's terminal flavour (#510) links a `libgojni.so` built without the
     * rcbridge package — 8 MB against 28 MB, keeping tailscale, WireGuard and
     * the mail bridge. The library still loads, so everything else in it works;
     * only rclone's JNI methods are absent.
     *
     * The generated [Rcbridge] class registers those methods from a static
     * initialiser, so merely *touching* the class throws where they are
     * missing — an ExceptionInInitializerError the first time and
     * NoClassDefFoundError after that, neither of which is an Exception.
     * Hence [Throwable], and hence `touch()`, which gomobile emits as an empty
     * method for exactly this purpose: it forces class initialisation without
     * calling into rclone.
     *
     * Probed once and cached. Every entry point below checks it, so a caller
     * that forgets gets a no-op rather than taking the process down.
     */
    val available: Boolean by lazy {
        try {
            Rcbridge.touch()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Initialise the rclone library.  Must be called once before any [rpc]
     * calls.  Safe to call multiple times (subsequent calls are no-ops).
     *
     * @param configPath absolute path to the rclone config file
     *                   (e.g. `/data/data/sh.haven.app/files/rclone/rclone.conf`).
     *                   Pass empty string to use rclone's default.
     */
    fun initialize(configPath: String) {
        // #510: no-op rather than crash when this build has no rclone.
        if (!available) return
        if (initialized) return
        Rcbridge.rbInitialize(configPath)
        initialized = true
    }

    /** Shut down the rclone library. Call once at app shutdown. */
    fun shutdown() {
        // #510: no-op rather than crash when this build has no rclone.
        if (!available) return
        if (!initialized) return
        Rcbridge.rbFinalize()
        initialized = false
    }

    /**
     * Set HTTPS_PROXY / HTTP_PROXY for outgoing rclone HTTP traffic.
     * Routes the embedded rclone process through the given proxy URL —
     * typically `socks5://127.0.0.1:<port>` for one of Haven's per-tunnel
     * SOCKS5 listeners (#149).
     *
     * Pass null or an empty string to clear both vars and route direct.
     *
     * Caveat: rclone caches HTTP clients per-fs, so changing the proxy
     * mid-session may not take effect until a fresh fs is created. For
     * the per-profile use case the caller sets this before the first
     * RPC for the profile, which is fresh.
     */
    fun setProxy(proxyUrl: String?) {
        // #510: no-op rather than crash when this build has no rclone.
        if (!available) return
        Rcbridge.rbSetProxy(proxyUrl.orEmpty())
    }

    /**
     * Call an rclone RC method.
     *
     * @param method RC method name, e.g. "operations/list", "config/create"
     * @param input  JSON string of method parameters, e.g. `{"fs":"remote:","remote":"/"}`
     * @return [RpcResult] with HTTP-style status code and JSON output
     */
    fun rpc(method: String, input: String = "{}"): RpcResult {
        // #510: no-op rather than crash when this build has no rclone.
        if (!available) return RpcResult(status = 501, output = """{"error":"rclone is not included in this build"}""")
        check(initialized) { "RcloneBridge.initialize() must be called first" }
        val result = Rcbridge.rbRPC(method, input)
        return RpcResult(
            status = result.status.toInt(),
            output = result.output,
        )
    }

    /**
     * Start a local HTTP server that streams files from the given rclone
     * remote via VFS.  Binds to 127.0.0.1 with an auto-assigned port.
     *
     * @param remoteName rclone remote name without trailing colon, e.g. "gdrive"
     * @return [RpcResult] with JSON `{"port": N}` on success
     */
    fun startMediaServer(remoteName: String, preferredPort: Long = 0): RpcResult {
        // #510: no-op rather than crash when this build has no rclone.
        if (!available) return RpcResult(status = 501, output = """{"error":"rclone is not included in this build"}""")
        check(initialized) { "RcloneBridge.initialize() must be called first" }
        val result = Rcbridge.rbStartMediaServer(remoteName, preferredPort)
        return RpcResult(
            status = result.status.toInt(),
            output = result.output,
        )
    }

    /** Query the current media server state. Returns JSON with "port" and optional "remote". */
    fun mediaServerStatus(): RpcResult {
        // #510: no-op rather than crash when this build has no rclone.
        if (!available) return RpcResult(status = 501, output = """{"error":"rclone is not included in this build"}""")
        check(initialized) { "RcloneBridge.initialize() must be called first" }
        val result = Rcbridge.rbMediaServerStatus()
        return RpcResult(
            status = result.status.toInt(),
            output = result.output,
        )
    }

    /** Stop the media streaming HTTP server if running. */
    fun stopMediaServer(): RpcResult {
        // #510: no-op rather than crash when this build has no rclone.
        if (!available) return RpcResult(status = 501, output = """{"error":"rclone is not included in this build"}""")
        check(initialized) { "RcloneBridge.initialize() must be called first" }
        val result = Rcbridge.rbStopMediaServer()
        return RpcResult(
            status = result.status.toInt(),
            output = result.output,
        )
    }
}
