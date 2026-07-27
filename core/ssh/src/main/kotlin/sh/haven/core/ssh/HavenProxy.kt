package sh.haven.core.ssh

import com.jcraft.jsch.Proxy
import org.connectbot.sshlib.transport.TransportFactory

/**
 * Haven-internal opaque handle representing the proxy / tunnel chain a
 * connection should route through.
 *
 * Constructed by `sh.haven.core.tunnel.TunnelResolver` (Tailscale, WireGuard,
 * Cloudflare Access, legacy SOCKS5/SOCKS4/HTTP) and by
 * [SshSessionManager.createProxyJump]. Consumed by [SshClient] and
 * `SshlibConnection`, which unwrap it internally, so callers in feature- and
 * app-modules pass a proxy through without importing either engine's types.
 *
 * Two shapes, because a jump host is a live connection on one engine or the
 * other:
 * - [jschProxy] — a JSch `Proxy`. Every tunnel type and a JSch jump session.
 *   The sshlib engine adapts it via `JschProxyTransportFactory`, so this shape
 *   works on both engines.
 * - [sshlibJump] — opens a direct-tcpip transport on a sshlib jump session for a
 *   given target. A lambda rather than a ready-made factory because the target's
 *   host/port are only known at dial time, which keeps `createProxyJump`'s
 *   signature (and its callers) unchanged. sshlib-only: a JSch target cannot use
 *   it, and [SshClient] says so rather than dialing direct.
 */
class HavenProxy internal constructor(
    internal val jschProxy: Proxy?,
    internal val sshlibJump: ((host: String, port: Int) -> TransportFactory)? = null,
) {
    /** The ordinary case: a JSch proxy, usable by either engine. */
    constructor(jschProxy: Proxy) : this(jschProxy, null)
}
