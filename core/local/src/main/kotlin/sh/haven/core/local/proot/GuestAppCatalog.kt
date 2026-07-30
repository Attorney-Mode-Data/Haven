package sh.haven.core.local.proot

/**
 * Curated one-tap installer packs for cage/app-window guest apps (#470).
 *
 * A pack collates everything a working app-window setup needs — the guest
 * packages per family, config drops that fix silent-failure defaults, the
 * audio-bridge requirement, the app-window def to register, and optional
 * pinned assets. The installer sequence itself lives with the MCP tools
 * (`install_app_pack`); this file is pure data, cloned from the
 * [DistroCatalog] / DesktopEnvironment pattern so the same entries can back
 * an "Add from catalog" UI later.
 *
 * Trust model (phase 1): the catalog is compiled into the release — packs
 * execute shell in the guest, so trust rides on the APK signature, not on
 * anything fetched at runtime. Assets are pinned by sha256.
 */

/**
 * One append-once config drop applied inside the guest after package
 * install. Idempotent: the append is guarded by an exact-match grep of the
 * stanza's first line, so re-installing a pack never duplicates it.
 */
data class PackConfigWrite(
    /** Absolute guest path, e.g. `/root/.qmmp/qmmprc`. */
    val path: String,
    /** Stanza appended once (may span lines). */
    val stanza: String,
)

/** A pinned optional asset fetched into the guest (skins, sample content). */
data class PackAsset(
    val url: String,
    /** Required — asset fetch is remote code-adjacent content; pin it. */
    val sha256: String,
    /** Absolute guest directory the file lands in (created if missing). */
    val destDir: String,
)

data class GuestAppPack(
    val id: String,
    val label: String,
    val description: String,
    /**
     * Per-family package lists. A family absent here means the pack is
     * incompatible with (or unchecked on) that family — same convention as
     * DesktopEnvironment's per-family packages.
     */
    val packages: Map<PackageFamily, List<String>>,
    /** Rootfs-relative binary probed to confirm the install took, e.g. `usr/bin/qmmp`. */
    val verifyBinary: String,
    val configWrites: List<PackConfigWrite> = emptyList(),
    /** Start the #257 PulseAudio output bridge as part of the install. */
    val needsAudioBridge: Boolean = false,
    /** App-window def registered on success (label + command + fullscreen). */
    val appLabel: String,
    val appCommand: String,
    val fullscreen: Boolean = true,
    /** Float the app's windows instead of kiosk-fullscreening them — needed
     *  by apps that open several toplevels (see AppWindowDef.multiWindow). */
    val multiWindow: Boolean = false,
    /** Per-title sway placement rules for [multiWindow] apps — sway centers
     *  every floating window, so without these a deck maps stacked. */
    val swayRules: List<String> = emptyList(),
    /** Cage output size: null = the global default ("auto" fits the phone
     *  screen aspect — the safe choice: a pinned "WxH" whose aspect differs
     *  from the screen gets CROPPED by the viewer's cover-fill). */
    val resolution: String? = null,
    /** Cage output scale (wlroots HiDPI): shrinks the LOGICAL canvas so a
     *  small fixed-size app (a 275px-wide skinned deck) fills the screen
     *  without pinning the output aspect. null = global default (1.0). */
    val scale: Float? = null,
    val assets: List<PackAsset> = emptyList(),
    /**
     * Families where this pack has been verified end-to-end on a real
     * device (install → window up → audio out). Families listed in
     * [packages] but not here are offered as unverified.
     */
    val verifiedFamilies: Set<PackageFamily> = emptySet(),
)

/** Single-quote [s] for POSIX sh. */
private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

/**
 * The guest-side half of a pack install as one `set -e` script: package
 * update+install, idempotent config drops (append guarded by an exact-match
 * grep of the stanza's first line), then sha256-pinned asset fetches.
 * App-side steps (verify-binary check, app-window def registration, audio
 * bridge) are the caller's completion block, not part of the script.
 */
fun buildPackInstallScript(
    pack: GuestAppPack,
    pkgs: List<String>,
    family: PackageFamily,
    includeAssets: Boolean,
): String {
    val ops = PackageOps.forFamily(family)
    val sb = StringBuilder("set -e\n")
    sb.append(ops.updateCmd()).append('\n')
    sb.append(ops.installCmd(pkgs)).append('\n')
    for (w in pack.configWrites) {
        val guard = w.stanza.lineSequence().first()
        sb.append("mkdir -p ${shq(w.path.substringBeforeLast('/'))}\n")
        sb.append(
            "grep -qxF ${shq(guard)} ${shq(w.path)} 2>/dev/null || " +
                "printf '%s\\n' ${shq(w.stanza)} >> ${shq(w.path)}\n",
        )
    }
    if (includeAssets) {
        for (a in pack.assets) {
            val dest = "${a.destDir}/${a.url.substringAfterLast('/')}"
            sb.append("mkdir -p ${shq(a.destDir)}\n")
            sb.append("curl -fsSL --retry 2 -o ${shq(dest)} ${shq(a.url)}\n")
            sb.append("printf '%s  %s\\n' ${shq(a.sha256)} ${shq(dest)} | sha256sum -c -\n")
        }
    }
    return sb.toString()
}

object GuestAppCatalog {

    /**
     * The reference pack — every field device-verified 2026-07-30 on
     * Ubuntu Noble (see #470): qmmp is absent from Alpine entirely, its
     * ALSA output default fails silently in proot (hence the pulse config
     * drop), and its Winamp-skin UI needs the app-window compositor's
     * Xwayland. The skin asset seeds `~/.qmmp/skins/`, which qmmp scans
     * for `.wsz`/`.zip` archives.
     */
    val QMMP = GuestAppPack(
        id = "qmmp",
        label = "Qmmp (Winamp-style music player)",
        description = "Skinned audio player: FLAC/MP3/Opus and more, Winamp 2 .wsz skins, playlists, 10-band EQ.",
        packages = mapOf(
            // ponytail: APT only — qmmp isn't packaged in Alpine, and the
            // PACMAN/XBPS names are unchecked; add them when verified.
            PackageFamily.APT to listOf("qmmp"),
        ),
        verifyBinary = "usr/bin/qmmp",
        configWrites = listOf(
            PackConfigWrite(
                path = "/root/.qmmp/qmmprc",
                stanza = "[Output]\ncurrent_plugin=pulse",
            ),
            // Open the whole deck on first run (qmmp defaults the playlist
            // and EQ closed). The append-once guard matches the `[Skinned]`
            // header, so once qmmp has written its own section — i.e. the
            // user has run it and made choices — this never overrides them.
            PackConfigWrite(
                path = "/root/.qmmp/qmmprc",
                stanza = "[Skinned]\npl_visible=true\neq_visible=true",
            ),
        ),
        needsAudioBridge = true,
        appLabel = "Qmmp",
        // xcb, not native Wayland: qmmp's skinned (Winamp) UI on the wayland
        // platform takes the app-window compositor down with it ("The Wayland
        // connection broke") — via Xwayland both UIs work. Device-verified.
        appCommand = "env QT_QPA_PLATFORM=xcb qmmp",
        fullscreen = true,
        // The skinned UI is a three-window deck (main/EQ/playlist); under the
        // kiosk's forced fullscreen they stack and only one is visible.
        multiWindow = true,
        // Winamp-classic stack, top-left: main deck, playlist, EQ. Each
        // window is 275x116 at scale 1; qmmp can't place its own windows
        // under sway (client position requests are ignored at map), and its
        // position save doesn't survive the overlay's force-kill teardown.
        swayRules = listOf(
            """for_window [title="^Qmmp$"] move position 20 20""",
            """for_window [title="^Playlist$"] move position 20 136""",
            """for_window [title="^Equalizer$"] move position 20 252""",
        ),
        // "auto" output (screen aspect — a pinned 320x400 got cover-fill
        // CROPPED in fullscreen) + scale 2.25 → 320 logical px across, so
        // the 275px deck + 20px margin fills ~93% of the width, uncropped.
        scale = 2.25f,
        assets = listOf(
            PackAsset(
                url = "https://archive.org/download/winampskin_Pika_Amp/Pika_Amp.wsz",
                sha256 = "95d23dc793aa506ed5237c16b04787bf2491b76a56cfec0b2d4cc6973232c7e5",
                destDir = "/root/.qmmp/skins",
            ),
        ),
        verifiedFamilies = setOf(PackageFamily.APT),
    )

    /**
     * The maintained XMMS descendant — has a Winamp-classic-skin mode and
     * defaults to PulseAudio output on the packaged builds, so no config
     * drop is needed. Packaged everywhere qmmp isn't. NOT yet
     * device-verified on any family.
     */
    val AUDACIOUS = GuestAppPack(
        id = "audacious",
        label = "Audacious (music player)",
        description = "Music player with a Winamp Classic skin mode. Plays FLAC/MP3/Opus and more.",
        packages = mapOf(
            PackageFamily.APT to listOf("audacious"),
            PackageFamily.APK to listOf("audacious"),
            PackageFamily.PACMAN to listOf("audacious"),
            PackageFamily.XBPS to listOf("audacious"),
        ),
        verifyBinary = "usr/bin/audacious",
        needsAudioBridge = true,
        appLabel = "Audacious",
        appCommand = "audacious",
        fullscreen = true,
    )

    val PACKS: List<GuestAppPack> = listOf(QMMP, AUDACIOUS)

    fun byId(id: String): GuestAppPack? = PACKS.find { it.id == id }
}
