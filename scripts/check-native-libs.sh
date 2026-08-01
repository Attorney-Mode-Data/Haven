#!/bin/bash
set -euo pipefail

# The .so files under core/wayland/src/main/jniLibs are committed binaries —
# core/wayland/build.gradle.kts only does jniLibs.srcDirs("src/main/jniLibs"),
# so whatever is checked in is what ships. They are produced by hand from the
# wayland-android submodule (whose own jniLibs/ is gitignored), which makes
# "someone rebuilt the submodule but forgot to copy the result here" a silent,
# shipping-by-default failure mode.
#
# That is exactly what #469 was: the committed liblabwc_android.so was three
# weeks behind the source and left 51 symbols undefined, so ld.so refused to
# load it and every cage/app-window feature died with
#   dlopen failed: cannot locate symbol "wlr_output_is_drm"
# The app itself was fine; only the binary was stale. Nothing failed until a
# user hit the feature on a device.
#
# wlroots is built with -Dbackends=[] and no libinput, so these symbols have no
# real implementation — gen-stubs.sh emits weak stubs for them at link time. If
# any of them is UND in a committed library, that library was linked without
# the generated stubs and will not load. There is no case where shipping one is
# correct, which is what makes this checkable rather than a heuristic.
#
# readelf reads foreign-architecture ELFs fine, so this needs no NDK and runs in
# the no-submodule `checks` job.

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$REPO_ROOT/core/wayland/src/main/jniLibs"

# Prefixes that must always be resolved in a shipped library. Mirrors the
# pattern list in wayland-android/gen-stubs.sh.
MUST_RESOLVE='^(wlr_|libinput_|xcb_ewmh_)'

if ! command -v readelf > /dev/null 2>&1; then
    echo "check-native-libs: readelf not found; skipping" >&2
    exit 0
fi

if [ ! -d "$JNI_DIR" ]; then
    echo "check-native-libs: $JNI_DIR does not exist; nothing to check" >&2
    exit 0
fi

status=0
checked=0

while IFS= read -r so; do
    checked=$((checked + 1))
    # Dynamic symbol table: columns are
    #   Num: Value Size Type Bind Vis Ndx Name
    # An undefined symbol has Ndx == UND. Take the name and drop any @VERSION.
    undefined=$(readelf --dyn-syms -W "$so" 2>/dev/null \
        | awk '$7 == "UND" { sub(/@.*/, "", $8); if ($8 != "") print $8 }' \
        | grep -E "$MUST_RESOLVE" \
        | sort -u || true)

    if [ -n "$undefined" ]; then
        count=$(printf '%s\n' "$undefined" | wc -l | tr -d ' ')
        echo "FAIL: ${so#"$REPO_ROOT"/} has $count unresolved symbol(s) that must be stubbed at link time:"
        printf '%s\n' "$undefined" | sed 's/^/    /' | head -20
        [ "$count" -gt 20 ] && echo "    … and $((count - 20)) more"
        status=1
    fi
done < <(find "$JNI_DIR" -name '*.so' -type f | sort)

if [ "$status" -ne 0 ]; then
    cat >&2 <<'MSG'

This library will fail to dlopen at runtime (#469). It was almost certainly
linked before wayland-android/gen-stubs.sh ran, or copied from a stale build.

To fix, rebuild from the submodule and copy the result in:
    cd wayland-android && ABI=arm64-v8a ./build_liblabwc_android.sh
    cp wayland-android/jniLibs/<abi>/*.so core/wayland/src/main/jniLibs/<abi>/

Verify before committing:
    ./scripts/check-native-libs.sh
MSG
    exit 1
fi

echo "✓ $checked committed native librar$([ "$checked" = 1 ] && echo y || echo ies) have no unresolved stub symbols."
