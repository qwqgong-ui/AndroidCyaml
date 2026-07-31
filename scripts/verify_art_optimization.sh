#!/usr/bin/env bash
set -euo pipefail

if (( $# != 2 )); then
    echo "usage: $0 <apk> <mapping>" >&2
    exit 2
fi

readonly APK="$1"
readonly MAPPING="$2"
[[ -s "${APK}" ]] || { echo "APK not found: ${APK}" >&2; exit 1; }
[[ -s "${MAPPING}" ]] || { echo "R8 mapping not found: ${MAPPING}" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }

readonly APK_ENTRIES="$(unzip -Z1 "${APK}")"

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

if grep -E '^lib/[^/]+/' <<< "${APK_ENTRIES}" \
        | grep -Ev '^lib/arm64-v8a/' >/dev/null; then
    echo "APK contains a native ABI other than arm64-v8a" >&2
    exit 1
fi

if ! grep -Eq '^.+ -> .+:$' "${MAPPING}"; then
    echo "R8 mapping does not contain class mappings" >&2
    exit 1
fi

echo "Verified R8 mapping, Startup Profile, and ART Baseline Profile in ${APK}"
