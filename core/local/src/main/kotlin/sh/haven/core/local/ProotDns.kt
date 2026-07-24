package sh.haven.core.local

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File

/**
 * Where a local guest's `/etc/resolv.conf` gets its nameservers (#446).
 *
 * Android has no `/etc/resolv.conf`, so Haven writes one into every rootfs. That
 * used to be hardcoded to public resolvers, which fails *silently* on networks
 * that block outbound port 53 to anything but their own resolver — the symptom
 * being package installs that hang with no visible cause.
 */
enum class ProotDnsMode(val id: String) {
    /** Whatever the device's active network provides (DHCP, VPN, carrier). */
    SYSTEM("system"),

    /** Public resolvers — Google 8.8.8.8 + Cloudflare 1.1.1.1, the historical default. */
    PUBLIC("public"),

    /** Explicit servers supplied by the user. */
    CUSTOM("custom"),
    ;

    companion object {
        /**
         * The network's own resolver. Default because it is the only choice that
         * works everywhere: a network may block public DNS, but it always routes
         * to the resolver it handed out.
         */
        val DEFAULT = SYSTEM

        fun fromId(id: String?): ProotDnsMode =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/** Resolves and writes the guest resolver configuration. */
object ProotDns {

    private const val TAG = "ProotDns"

    /** The historical hardcoded pair, and the fallback whenever a source yields nothing. */
    val PUBLIC_SERVERS = listOf("8.8.8.8", "1.1.1.1")

    /**
     * Nameservers for [mode]. [systemProvider] supplies the device's resolvers for
     * [ProotDnsMode.SYSTEM] (injected so the decision logic stays testable off-device).
     *
     * Every branch falls back to [PUBLIC_SERVERS] rather than yielding an empty list:
     * a resolv.conf with no nameserver resolves nothing at all, which would be a worse
     * failure than the one this setting exists to fix.
     */
    fun nameservers(
        mode: ProotDnsMode,
        customServers: String?,
        systemProvider: () -> List<String>,
    ): List<String> = when (mode) {
        ProotDnsMode.PUBLIC -> PUBLIC_SERVERS
        ProotDnsMode.CUSTOM -> parseServers(customServers).ifEmpty { PUBLIC_SERVERS }
        ProotDnsMode.SYSTEM -> systemProvider().ifEmpty { PUBLIC_SERVERS }
    }

    /** Split a user-entered list on commas/whitespace and keep only IP literals. */
    fun parseServers(text: String?): List<String> =
        text.orEmpty()
            .split(',', ' ', '\n', '\t', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && isIpLiteral(it) }
            .distinct()

    /**
     * Accept only IP literals. A hostname in resolv.conf cannot be resolved (there is
     * nothing to resolve it *with*), so letting one through would produce a config that
     * silently never works — exactly the failure mode #446 is about.
     */
    fun isIpLiteral(value: String): Boolean {
        if (value.isEmpty()) return false
        if (':' in value) {
            // Loose IPv6 check: hex groups and colons only, at least one colon pair.
            return value.all { it == ':' || it.isDigit() || it.lowercaseChar() in 'a'..'f' } &&
                value.count { it == ':' } in 2..8
        }
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                (part.toIntOrNull() ?: return@all false) in 0..255
        }
    }

    /** The file body: one `nameserver` line per entry. */
    fun resolvConfText(nameservers: List<String>): String =
        nameservers.joinToString(separator = "") { "nameserver $it\n" }

    /** The device's current resolvers, newest-network-first; empty when unavailable. */
    fun systemNameservers(context: Context): List<String> = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val properties = network?.let { manager.getLinkProperties(it) }
        properties?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.filter { it.isNotBlank() }
    } catch (e: Exception) {
        // Missing permission, no active network, OEM quirk — treat as "unknown" and
        // let the caller fall back rather than failing the whole guest launch.
        Log.w(TAG, "Could not read system DNS servers: ${e.message}")
        emptyList()
    }

    /**
     * Write `etc/resolv.conf` under [rootfsDir] for the given settings.
     *
     * Written on every guest launch so that changing the setting actually takes effect —
     * the file is Haven-managed (Android supplies none), so there is no user content to
     * preserve; a user who wants specific servers sets [ProotDnsMode.CUSTOM].
     */
    fun write(
        rootfsDir: File,
        context: Context,
        mode: ProotDnsMode,
        customServers: String?,
    ): List<String> {
        val servers = nameservers(mode, customServers) { systemNameservers(context) }
        return try {
            val target = File(rootfsDir, "etc/resolv.conf")
            target.parentFile?.mkdirs()
            target.writeText(resolvConfText(servers))
            Log.d(TAG, "resolv.conf mode=${mode.id} servers=$servers")
            servers
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write resolv.conf: ${e.message}")
            servers
        }
    }
}
