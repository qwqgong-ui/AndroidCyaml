#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
    echo "usage: $0 <apk>" >&2
    exit 2
fi

readonly APK="$1"
[[ -f "${APK}" ]] || { echo "APK not found: ${APK}" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
command -v readelf >/dev/null || { echo "readelf is required" >&2; exit 1; }
command -v strings >/dev/null || { echo "strings is required" >&2; exit 1; }

readonly WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

unzip -q "${APK}" \
    'classes*.dex' \
    'lib/arm64-v8a/libandroidcyaml.so' \
    'lib/arm64-v8a/libmihomo.so' \
    -d "${WORK_DIR}"

readonly WRAPPER="${WORK_DIR}/lib/arm64-v8a/libandroidcyaml.so"
readonly CORE="${WORK_DIR}/lib/arm64-v8a/libmihomo.so"
readonly WRAPPER_SYMBOLS="${WORK_DIR}/wrapper-symbols.txt"
readonly CORE_SYMBOLS="${WORK_DIR}/core-symbols.txt"
readonly CORE_UNDEFINED="${WORK_DIR}/core-undefined.txt"
readonly DEX_STRINGS="${WORK_DIR}/dex-strings.txt"
[[ -s "${WRAPPER}" && -s "${CORE}" ]] || {
    echo "APK does not contain both embedded runtime libraries" >&2
    exit 1
}
mapfile -d '' dex_files < <(find "${WORK_DIR}" -maxdepth 1 -type f -name 'classes*.dex' -print0 | sort -z)
(( ${#dex_files[@]} > 0 )) || {
    echo "APK does not contain classes.dex" >&2
    exit 1
}
for dex in "${dex_files[@]}"; do
    strings "${dex}"
done > "${DEX_STRINGS}"

readelf -Ws "${WRAPPER}" > "${WRAPPER_SYMBOLS}"
readelf -Ws "${CORE}" > "${CORE_SYMBOLS}"

verify_aarch64() {
    local library="$1"
    readelf -h "${library}" | grep -q 'Class:.*ELF64'
    readelf -h "${library}" | grep -q 'Machine:.*AArch64'
}

verify_page_alignment() {
    local library="$1"
    local found=0
    while read -r alignment; do
        found=1
        if (( alignment < 0x4000 )); then
            echo "${library} has LOAD alignment below 16 KiB: ${alignment}" >&2
            exit 1
        fi
    done < <(readelf -lW "${library}" | awk '$1 == "LOAD" { print $NF }')
    (( found == 1 )) || { echo "${library} has no LOAD segments" >&2; exit 1; }
}

verify_packaged_symbols_are_stripped() {
    local library="$1"
    if readelf -SW "${library}" | grep -Eq '\.(debug_|symtab)'; then
        echo "${library} still contains debug or static symbol sections" >&2
        exit 1
    fi
}

# 16 KiB page devices map the packaged libraries straight out of the APK, which
# only works when they are stored uncompressed and page aligned.
verify_uncompressed_libraries() {
    local found=0
    local method entry
    while read -r method entry; do
        found=1
        if [[ "${method}" != "Stored" ]]; then
            echo "${entry} is compressed in the APK: ${method}" >&2
            exit 1
        fi
    done < <(
        unzip -v "${APK}" 'lib/arm64-v8a/*.so' \
            | awk '$NF ~ /\.so$/ { print $2, $NF }'
    )
    (( found > 0 )) || { echo "APK contains no arm64-v8a libraries" >&2; exit 1; }
}

verify_uncompressed_libraries
verify_aarch64 "${WRAPPER}"
verify_aarch64 "${CORE}"
verify_page_alignment "${WRAPPER}"
verify_page_alignment "${CORE}"
verify_packaged_symbols_are_stripped "${WRAPPER}"
verify_packaged_symbols_are_stripped "${CORE}"

readelf -d "${CORE}" | grep -Fq 'Library soname: [libmihomo.so]'
readelf -d "${WRAPPER}" | grep -Fq 'Shared library: [libmihomo.so]'
if readelf -d "${WRAPPER}" | grep 'Shared library:' | grep -q '/'; then
    echo "JNI wrapper contains an absolute DT_NEEDED path" >&2
    readelf -d "${WRAPPER}" | grep 'Shared library:' >&2
    exit 1
fi

# These names are consumed by C++ GetMethodID and do not otherwise appear in
# Java string constants. Exact DEX-string matches therefore catch R8 removal or
# obfuscation without confusing embedded JavaScript source with method entries.
for method in \
    protectSocket \
    resolveProcessOwner \
    startBrowserRequest \
    awaitBrowserResponse \
    readBrowserResponse \
    closeBrowserRequest; do
    grep -Fxq "${method}" "${DEX_STRINGS}" || {
        echo "R8 removed or renamed JNI callback method ${method}" >&2
        exit 1
    }
done

for symbol in \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeValidate \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativePrepareTun \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeSetWebViewXhttpEnabled \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeReadBrowserRequestBody \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeCloseBrowserRequestBody \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativePushBrowserResponseChunk \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeFinishBrowserResponse \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeStart \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeSetTcpConcurrent \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeStop \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeNotifyNetworkChanged \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeIsRunning \
    Java_io_github_qwqgong_androidcyaml_MihomoNative_nativeTrimMemory; do
    grep -q "GLOBAL.*${symbol}$" "${WRAPPER_SYMBOLS}" || {
        echo "JNI wrapper is missing exported symbol ${symbol}" >&2
        exit 1
    }
done

for symbol in \
    AndroidCyamlInstallCallbacks \
    AndroidCyamlInstallBrowserCallbacks \
    AndroidCyamlReadBrowserRequestBody \
    AndroidCyamlCloseBrowserRequestBody \
    AndroidCyamlPushBrowserResponseChunk \
    AndroidCyamlFinishBrowserResponse \
    AndroidCyamlValidate \
    AndroidCyamlPrepareTun \
    AndroidCyamlSetBrowserDialerEnabled \
    AndroidCyamlStart \
    AndroidCyamlSetTcpConcurrent \
    AndroidCyamlStop \
    AndroidCyamlNotifyNetworkChanged \
    AndroidCyamlUpdateSystemDNS \
    AndroidCyamlIsRunning \
    AndroidCyamlTrimMemory; do
    grep -q "GLOBAL.*${symbol}$" "${CORE_SYMBOLS}" || {
        echo "Go core is missing exported symbol ${symbol}" >&2
        exit 1
    }
done

awk '$7 == "UND" { print $8 }' "${CORE_SYMBOLS}" > "${CORE_UNDEFINED}"
if grep -Eq '^androidcyaml_(protect_socket|resolve_process)$' "${CORE_UNDEFINED}"; then
    echo "Go core contains circular undefined JNI callback symbols" >&2
    exit 1
fi

echo "Verified embedded mihomo JNI runtime and callback ABI in ${APK}"
