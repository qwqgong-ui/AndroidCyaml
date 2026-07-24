#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
    echo "usage: $0 <apk> <aab>" >&2
    exit 2
fi

readonly APK="$1"
readonly AAB="$2"
[[ -s "${APK}" ]] || { echo "APK not found: ${APK}" >&2; exit 1; }
[[ -s "${AAB}" ]] || { echo "AAB not found: ${AAB}" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }

readonly APK_ENTRIES="$(unzip -Z1 "${APK}")"
readonly AAB_ENTRIES="$(unzip -Z1 "${AAB}")"

for profile in assets/dexopt/baseline.prof assets/dexopt/baseline.profm; do
    grep -Fxq "${profile}" <<< "${APK_ENTRIES}" || {
        echo "APK is missing ${profile}" >&2
        exit 1
    }
    (( $(unzip -p "${APK}" "${profile}" | wc -c) > 0 )) || {
        echo "APK contains an empty ${profile}" >&2
        exit 1
    }
done

for profile in \
    BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof \
    BUNDLE-METADATA/com.android.tools.build.profiles/baseline.profm; do
    grep -Fxq "${profile}" <<< "${AAB_ENTRIES}" || {
        echo "AAB is missing ${profile}" >&2
        exit 1
    }
done

if grep -E '^lib/[^/]+/' <<< "${APK_ENTRIES}" \
        | grep -Ev '^lib/arm64-v8a/' >/dev/null; then
    echo "APK contains a native ABI other than arm64-v8a" >&2
    exit 1
fi

readonly R8_METADATA="$(
    unzip -p "${AAB}" BUNDLE-METADATA/com.android.tools/r8.json
)"
for expected in \
    '"isObfuscationEnabled":true' \
    '"isOptimizationsEnabled":true' \
    '"isShrinkingEnabled":true' \
    '"startup":true' \
    '"isDexLayoutOptimizationEnabled":true' \
    '"isProfileGuidedOptimizationEnabled":true'; do
    grep -Fq "${expected}" <<< "${R8_METADATA}" || {
        echo "R8 metadata does not contain ${expected}" >&2
        exit 1
    }
done

echo "Verified R8, Startup Profile, and ART Baseline Profile in ${APK} and ${AAB}"
