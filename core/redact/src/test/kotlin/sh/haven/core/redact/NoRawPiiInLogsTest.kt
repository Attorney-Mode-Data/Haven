package sh.haven.core.redact

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #518 — a source scan asserting that hostnames, ports, usernames and
 * user-chosen labels do not reach `Log.*` unredacted.
 *
 * The fix for #518 was ~50 call sites across 15 modules. Fixing them once is
 * easy; keeping them fixed is not, because the natural way to write the next log
 * line is `Log.d(TAG, "connecting to $host")` and nothing would object. This is
 * the thing that objects.
 *
 * It reads the repository's Kotlin sources rather than inspecting behaviour,
 * which is unusual for a unit test and deliberate: the property being protected
 * is a property of the source, and there is no runtime moment at which "someone
 * wrote a leaky log statement" can be observed.
 *
 * Scope is honest about what it can catch. It matches the interpolation forms
 * that leaked in #518 — `$host`, `${profile.label}`, `${config.host}:${port}` —
 * and will not catch a value laundered through an intermediate variable with an
 * innocuous name. It is a ratchet against the common case, not a proof.
 */
class NoRawPiiInLogsTest {

    /** Names that hold a hostname, address, port pair, username or user-chosen label. */
    private val sensitiveBare = listOf("host", "hostname", "ip", "newHost", "username")
    private val sensitiveProps = listOf("host", "hostname", "label", "username", "user")

    /**
     * Values that are NOT user data despite matching the shape:
     *   de.label / spec.label — a desktop environment ("XFCE") or a built-in
     *   guest service, both from Haven's own catalogue.
     */
    private val allowedReceivers = setOf("de", "spec")

    private val logCall = Regex("""\bLog\.[dviwe]\(""")

    private fun sourceRoots(): List<File> {
        // Walk up to the repository root, then take the module source trees.
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return listOf("app", "core", "feature").map { File(dir, it) }.filter { it.isDirectory }
    }

    @Test
    fun `no raw hostname username or label reaches a log call`() {
        val offenders = mutableListOf<String>()

        for (root in sourceRoots()) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.path.contains("/build/") }
                .filterNot { it.path.contains("/test/") || it.path.contains("/androidTest/") }
                // `src/debug` is a build-type source set: AGP compiles it into
                // debug builds only, so none of it reaches a release APK or a
                // user's logs. DebugReceiver exists precisely to dump state on a
                // developer's own device, and redacting it would defeat the
                // point. Excluded deliberately, not overlooked — this is the one
                // carve-out, and it is justified by the code never shipping.
                .filterNot { it.path.contains("/src/debug/") }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        if (!logCall.containsMatchIn(line)) return@forEachIndexed
                        if (line.contains("LogRedact.")) {
                            // Partially redacted lines still need checking for a
                            // second, unredacted value on the same line.
                        }
                        for (name in sensitiveBare) {
                            // `$host` but not `$hostname`, and not `${LogRedact.of(host)}`.
                            val bare = Regex("""\$$name\b(?!\w)""")
                            if (bare.containsMatchIn(line.replace(Regex("""LogRedact\.\w+\([^)]*\)"""), ""))) {
                                offenders += "${file.path}:${index + 1}: \$$name — ${line.trim()}"
                            }
                        }
                        for (prop in sensitiveProps) {
                            val propRe = Regex("""\$\{(\w+)\.$prop\}""")
                            for (m in propRe.findAll(
                                line.replace(Regex("""LogRedact\.\w+\([^)]*\)"""), ""),
                            )) {
                                val receiver = m.groupValues[1]
                                if (receiver !in allowedReceivers) {
                                    offenders += "${file.path}:${index + 1}: \${$receiver.$prop} — ${line.trim()}"
                                }
                            }
                        }
                    }
                }
        }

        assertTrue(
            "Sensitive values reach Log.* unredacted (#518). Wrap them in " +
                "LogRedact.of(...) / LogRedact.host(host, port), or if the value is " +
                "genuinely not user data add its receiver to allowedReceivers with a " +
                "reason:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** The scan is worthless if it is looking at nothing — prove it found sources. */
    @Test
    fun `the scan actually reads the repository sources`() {
        val kotlinFiles = sourceRoots().sumOf { root ->
            root.walkTopDown().count {
                it.isFile && it.extension == "kt" && !it.path.contains("/build/")
            }
        }
        assertTrue(
            "found only $kotlinFiles Kotlin files — the scan is not looking where it thinks",
            kotlinFiles > 200,
        )
    }
}
