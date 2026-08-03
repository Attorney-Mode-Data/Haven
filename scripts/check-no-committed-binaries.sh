#!/usr/bin/env bash
# Fail if any tracked file is a compiled binary.
#
# Why this gate exists
# -------------------
# A committed binary is unauditable: nobody reviewing a diff can tell what is
# inside it, it does not correspond to any reviewable source change, and it
# ships straight into the APK. Three separate failures came out of that:
#
#   #469  a committed liblabwc_android.so went three versions stale because
#         nothing rebuilt it — the cage crashed and the source looked correct.
#   rdp   armeabi-v7a was missing from the cargo target list, so armv7 users
#         ran whatever binary happened to be checked in.
#   wayland  F-Droid `scandelete`d core/wayland/src/main/jniLibs and rebuilt
#         only liblabwc_android.so, so their APK silently shipped without the
#         GPU renderer, the XWayland wrapper and the GLES benchmark.
#
# Every native artefact must therefore be produced by a build step from source
# in the tree. Detection is by content (ELF/Mach-O/PE/dex/zip magic), not by
# file extension, so renaming a blob does not get it past this.
#
# Usage:
#   scripts/check-no-committed-binaries.sh            # check tracked files
#   scripts/check-no-committed-binaries.sh --staged   # check the index (hooks)

set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

# The only permitted binary. Gradle's wrapper jar cannot bootstrap itself, is
# an accepted prebuilt on F-Droid's trusted-source list, and its integrity is
# checked separately by gradle/wrapper-validation in CI.
ALLOWLIST=(
    "gradle/wrapper/gradle-wrapper.jar"
)

# Binaries that predate this gate and are still being migrated to a build step.
# This list may only ever SHRINK — the check below fails if an entry no longer
# exists, so a completed migration forces its removal here and the list cannot
# quietly become a second allowlist. New binaries are never added.
#
# core/ffmpeg: DONE (#493) — built from pinned source by core/ffmpeg's
# buildFfmpegNatives task from preBuild, and gitignored. The "~an hour per
# ABI" that had kept them committed was a guess; measured, a clean build of
# the whole chain is 2m42s on a 12-core desktop.
# core/wayland: DONE (#493) — all five are now built from source by
# core/wayland's buildWaylandNatives task, which runs from preBuild in both our
# CI and release workflows, and they are gitignored so they cannot come back.
# rclone-android/build/rcbridge-bindings.jar: untracking this broke main.
# It is consumed as `api(files("build/rcbridge-bindings.jar"))`, and a file
# dependency is resolved when a CONSUMER resolves its runtime classpath —
# :app:checkArm64DebugDuplicateClasses demanded the jar before any task had
# produced it. compileKotlin's dependsOn is too late, and attaching builtBy to
# the file collection did not propagate through the artifact transform either
# (tried; same failure). The real fix is for rclone-android to publish it as a
# proper project artifact rather than a raw file path. Tracked in #493.
GRANDFATHERED=(
    "rclone-android/build/rcbridge-bindings.jar"
)

is_allowed() {
    local f="$1"
    for a in "${ALLOWLIST[@]}"; do [ "$f" = "$a" ] && return 0; done
    for g in "${GRANDFATHERED[@]}"; do [ "$f" = "$g" ] && return 0; done
    return 1
}

if [ "${1:-}" = "--staged" ]; then
    mapfile -t FILES < <(git diff --cached --name-only --diff-filter=ACM)
    READ_FROM_INDEX=1
else
    mapfile -t FILES < <(git ls-files)
    READ_FROM_INDEX=0
fi

violations=()
for f in "${FILES[@]}"; do
    [ -n "$f" ] || continue
    is_allowed "$f" && continue
    if [ "$READ_FROM_INDEX" = "1" ]; then
        magic=$(git show ":$f" 2>/dev/null | head -c 4 | od -An -tx1 -v 2>/dev/null | tr -d ' \n')
    else
        [ -f "$f" ] || continue
        magic=$(head -c 4 "$f" 2>/dev/null | od -An -tx1 -v 2>/dev/null | tr -d ' \n')
    fi
    case "$magic" in
        7f454c46)  violations+=("$f  (ELF)") ;;          # .so / executable
        feedface|feedfacf|cffaedfe|cafebabe) violations+=("$f  (Mach-O or Java class)") ;;
        4d5a*)     violations+=("$f  (PE/DLL)") ;;
        6465780a)  violations+=("$f  (Android dex)") ;;
        504b0304)  # zip container — only flag the ones that carry code
                   case "$f" in
                       *.jar|*.aar|*.apk|*.aab|*.war) violations+=("$f  (Java/Android archive)") ;;
                   esac ;;
    esac
done

# A grandfathered entry that no longer exists means its migration finished —
# force it out of the list so the list can only shrink. Without this the list
# rots into a permanent second allowlist, which is how the original problem
# ("it was already like that") persisted in the first place.
stale=()
for g in "${GRANDFATHERED[@]}"; do
    git ls-files --error-unmatch "$g" >/dev/null 2>&1 || stale+=("$g")
done
if [ ${#stale[@]} -gt 0 ]; then
    echo "✗ GRANDFATHERED lists ${#stale[@]} file(s) that are no longer tracked:" >&2
    printf '    %s\n' "${stale[@]}" >&2
    echo "  Migration done — delete these from GRANDFATHERED in $0" >&2
    exit 1
fi

if [ ${#violations[@]} -gt 0 ]; then
    echo "✗ ${#violations[@]} committed binary file(s) found:" >&2
    printf '    %s\n' "${violations[@]}" >&2
    cat >&2 <<'EOF'

Binaries must not be committed — they are unauditable in review and go stale
silently (#469). Build them from source instead:

    core/local/src/main/jniLibs/   build-proot/build.sh
    core/ffmpeg/src/main/jniLibs/  build-ffmpeg/build.sh
    core/wayland/src/main/jniLibs/ wayland-android/build_liblabwc_android.sh
                                   wayland-android/build-native-helpers.sh
    rdp-kotlin/jniLibs/            cargo-ndk (rdp-kotlin:buildRdpNative)
    rclone-android/build/          gomobile (buildRcloneNative)

then add the output path to .gitignore. If a binary genuinely must be tracked,
add it to ALLOWLIST in this script with a comment saying why.
EOF
    exit 1
fi

echo "✓ no new committed binaries (${#FILES[@]} tracked files checked, ${#GRANDFATHERED[@]} grandfathered)"
