#!/bin/sh
# Build wayvnc 0.10.1 (+ neatvnc 1.0.1, aml 1.0.0) from pinned source in the
# guest, for distros whose packaged wayvnc is too old.
#
# Why (#473): wayvnc 0.9.1 — what Debian trixie pins, with no backports —
# SIGSEGVs on roughly one app-window launch in three. Upstream declined to
# debug it ("not going to spend time on an old version", any1/wayvnc#448),
# and 0.10.1 fixes it: 8 consecutive launches, 8 connected, 0 SIGSEGV.
#
# Why from source rather than a distro package: the fix is packaged, but only
# in Debian forky/sid and Arch. Pulling wayvnc out of forky onto a stable
# guest makes a user's package set depend on a moving testing archive — one
# glibc rebuild there turns it into a partial dist-upgrade. Building three
# pinned tarballs into /usr/local touches no distro package and cannot damage
# a guest. (The guest rootfs is downloaded at runtime, so none of this is
# inside the APK and F-Droid's from-source rule does not reach it — but the
# same reasoning about moving sources applies, so the pins are real pins.)
#
# The cost is honest: on Ubuntu noble this pulls ~119 packages of toolchain
# and dev headers and takes a few minutes. That is the price of not attaching
# a testing suite to someone's system.
#
# Idempotent: re-running with a new-enough wayvnc already present does
# nothing. Safe to call on every desktop install.

set -eu

PREFIX=/usr/local
SRC=/opt/wayvnc-build
LOG_TAG="[wayvnc-build]"

log() { echo "$LOG_TAG $*"; }

# --- Gate 1: is the wayvnc we already have new enough? -----------------------
# Accepts both the packaged form ("wayvnc: 0.9.1") and a source build
# ("wayvnc: v0.10.1-ae53f07 (HEAD)").
installed_version() {
    command -v wayvnc >/dev/null 2>&1 || return 1
    wayvnc --version 2>/dev/null | head -1 |
        sed 's/^wayvnc:[[:space:]]*//; s/^v//; s/[^0-9.].*//'
}

# wayvnc 0.10 is the first release with the fix.
version_is_new_enough() {
    _v="${1:-}"
    case "$_v" in
        ''|*[!0-9.]*) return 1 ;;
    esac
    _maj="${_v%%.*}"
    _rest="${_v#*.}"
    _min="${_rest%%.*}"
    case "$_maj" in ''|*[!0-9]*) return 1 ;; esac
    case "$_min" in ''|*[!0-9]*) _min=0 ;; esac
    [ "$_maj" -gt 0 ] && return 0
    [ "$_min" -ge 10 ] && return 0
    return 1
}

CURRENT="$(installed_version || true)"
if version_is_new_enough "$CURRENT"; then
    log "wayvnc ${CURRENT} is already >= 0.10 — nothing to build"
    exit 0
fi
log "wayvnc ${CURRENT:-none} is older than 0.10 — building from pinned source"

# --- Gate 2: we only know how to install build deps on APT -------------------
# Every other family either already ships >= 0.10 (Arch) or is not a distro
# this bug has been seen on. Skipping leaves the packaged wayvnc in place,
# which is exactly today's behaviour — so this is a no-op, not a failure.
if ! command -v apt-get >/dev/null 2>&1; then
    log "not an apt distro — leaving the packaged wayvnc in place"
    exit 0
fi

# --- Build dependencies ------------------------------------------------------
# Verified to resolve on both Debian trixie and Ubuntu noble.
DEPS="build-essential meson ninja-build pkg-config git ca-certificates
      libdrm-dev libgbm-dev libpixman-1-dev libgnutls28-dev libjansson-dev
      libturbojpeg0-dev nettle-dev libgmp-dev zlib1g-dev
      libwayland-dev wayland-protocols libxkbcommon-dev"

log "installing build dependencies"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq || log "apt-get update failed — trying the install anyway"
# shellcheck disable=SC2086
apt-get install -y --no-install-recommends $DEPS

# --- Pinned sources ----------------------------------------------------------
# Commit SHAs, not tag names. A tag is a mutable ref: it can be moved or
# re-pointed, so pinning to one proves nothing about what you fetched. Each
# checkout below is verified against its SHA and the build aborts on mismatch.
AML_REPO=https://github.com/any1/aml.git
AML_COMMIT=685035c9830aa89df02a43df89b644690bd885f5      # v1.0.0

NEATVNC_REPO=https://github.com/any1/neatvnc.git
NEATVNC_COMMIT=4d6d09b544d84b836cf1906735354b4e694a0c1c  # v1.0.1

WAYVNC_REPO=https://github.com/any1/wayvnc.git
WAYVNC_COMMIT=ae53f076a83ad1ecd0d2adcaa063674a632bfe0f   # v0.10.1

fetch_pinned() {
    _name="$1"; _repo="$2"; _commit="$3"
    _dir="$SRC/$_name"
    if [ ! -d "$_dir/.git" ]; then
        rm -rf "$_dir"
        mkdir -p "$_dir"
        git -C "$_dir" init -q
        git -C "$_dir" remote add origin "$_repo"
    fi
    log "fetching $_name at $_commit"
    git -C "$_dir" fetch -q --depth 1 origin "$_commit"
    git -C "$_dir" checkout -q --force FETCH_HEAD
    _got="$(git -C "$_dir" rev-parse HEAD)"
    if [ "$_got" != "$_commit" ]; then
        log "FATAL: $_name checked out $_got, expected $_commit"
        exit 1
    fi
    log "$_name verified at $_commit"
}

build_meson() {
    _name="$1"
    shift
    _dir="$SRC/$_name"
    log "building $_name"
    rm -rf "$_dir/build"
    meson setup "$_dir/build" "$_dir" --prefix="$PREFIX" --buildtype=release "$@"
    ninja -C "$_dir/build"
    ninja -C "$_dir/build" install
}

mkdir -p "$SRC"

# aml and neatvnc are wayvnc 0.10's raised floors — trixie has aml 0.3.0 and
# neatvnc 0.9.1, so all three are built rather than just the top one.
fetch_pinned aml "$AML_REPO" "$AML_COMMIT"
build_meson aml

# -Dh264=disabled is what makes this possible at all. The *packaged* neatvnc
# 1.0.1 needs ffmpeg 8.x, which trixie does not have, and that is why this
# looked blocked on packaging grounds. neatvnc's own meson makes h264 an
# 'auto' feature, and Haven's VNC path does not use H.264 — so dropping it
# removes the ffmpeg dependency entirely. A packaging constraint was never a
# software constraint.
fetch_pinned neatvnc "$NEATVNC_REPO" "$NEATVNC_COMMIT"
build_meson neatvnc -Dh264=disabled -Dexamples=false -Dtests=false

fetch_pinned wayvnc "$WAYVNC_REPO" "$WAYVNC_COMMIT"
build_meson wayvnc

# /usr/local/lib/<triplet> is not always on the default search path.
mkdir -p /etc/ld.so.conf.d
printf '%s/lib\n%s/lib/%s\n' "$PREFIX" "$PREFIX" "$(gcc -dumpmachine)" \
    > /etc/ld.so.conf.d/haven-wayvnc.conf
ldconfig

# --- Verify ------------------------------------------------------------------
# The launcher runs a bare `exec wayvnc`, so what matters is that the built
# binary is the one PATH resolves to, and that it actually runs. Checking the
# file exists would not catch a build that produced an unloadable binary.
NEW="$(installed_version || true)"
if ! version_is_new_enough "$NEW"; then
    log "FATAL: after building, wayvnc reports '${NEW:-nothing}' — expected >= 0.10"
    log "which wayvnc: $(command -v wayvnc || echo none)"
    exit 1
fi
log "built and active: $(wayvnc --version 2>&1 | head -1) at $(command -v wayvnc)"

# neatvnc must not have pulled ffmpeg back in — if it did, the h264 opt-out
# silently stopped working and the guest now depends on libraries trixie
# cannot satisfy.
if ldd "$PREFIX"/lib/*/libneatvnc.so.1 2>/dev/null | grep -qi 'libav\|libsw'; then
    log "WARNING: libneatvnc links ffmpeg — -Dh264=disabled did not take effect"
fi

log "done"
