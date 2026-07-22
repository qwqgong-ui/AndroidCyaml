#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCE_URL="https://github.com/heiher/hev-socks5-tunnel.git"
readonly HEV_COMMIT="df11261f09ebafc37bac03f81029c9b75a4aa074"
readonly NDK_VERSION="29.0.14206865"
readonly BUILD_RECIPE_VERSION="3"
readonly SOURCE_DIR="${ROOT_DIR}/.third_party/hev-socks5-tunnel-src"
readonly BUILD_DIR="${ROOT_DIR}/.third_party/hev-socks5-tunnel-build"
readonly OUTPUT_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
readonly OUTPUT_FILE="${OUTPUT_DIR}/libhev-socks5-tunnel.so"
readonly MARKER_FILE="${ROOT_DIR}/.third_party/hev-socks5-tunnel.commit"
readonly EXPECTED_MARKER="${HEV_COMMIT}:android-arm64-ndk${NDK_VERSION}-v${BUILD_RECIPE_VERSION}"

if [[ -f "${OUTPUT_FILE}" && -f "${MARKER_FILE}" ]] \
    && [[ "$(<"${MARKER_FILE}")" == "${EXPECTED_MARKER}" ]]; then
    echo "hev-socks5-tunnel ${HEV_COMMIT:0:8} is already built."
    exit 0
fi

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${sdk_root}" && -f "${ROOT_DIR}/local.properties" ]]; then
    sdk_root="$(sed -n 's/^sdk\.dir=//p' "${ROOT_DIR}/local.properties" | tail -n 1)"
fi
if [[ -z "${sdk_root}" ]]; then
    echo "ANDROID_SDK_ROOT, ANDROID_HOME, or sdk.dir in local.properties is required" >&2
    exit 1
fi

# setup-android exports ANDROID_NDK_HOME for the runner's default NDK, which may
# not be the version this project pins. Prefer the side-by-side pinned NDK and
# only accept ANDROID_NDK_HOME when it identifies that exact revision.
ndk_root="${sdk_root}/ndk/${NDK_VERSION}"
if [[ ! -x "${ndk_root}/ndk-build" && -n "${ANDROID_NDK_HOME:-}" ]]; then
    candidate_revision=""
    if [[ -f "${ANDROID_NDK_HOME}/source.properties" ]]; then
        candidate_revision="$(sed -n 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "${ANDROID_NDK_HOME}/source.properties" | tail -n 1)"
    fi
    if [[ "${candidate_revision}" == "${NDK_VERSION}" ]]; then
        ndk_root="${ANDROID_NDK_HOME}"
    fi
fi
ndk_build="${ndk_root}/ndk-build"
if [[ ! -x "${ndk_build}" ]]; then
    echo "Android NDK ${NDK_VERSION} is required; missing ${ndk_build}" >&2
    exit 1
fi

mkdir -p "${ROOT_DIR}/.third_party" "${OUTPUT_DIR}"

if [[ ! -d "${SOURCE_DIR}/.git" ]]; then
    if [[ -e "${SOURCE_DIR}" ]]; then
        echo "Refusing to replace non-git path: ${SOURCE_DIR}" >&2
        exit 1
    fi
    git clone --filter=blob:none --no-checkout "${SOURCE_URL}" "${SOURCE_DIR}"
fi

actual_origin="$(git -C "${SOURCE_DIR}" remote get-url origin)"
if [[ "${actual_origin}" != "${SOURCE_URL}" ]]; then
    echo "Unexpected hev-socks5-tunnel origin: ${actual_origin}" >&2
    exit 1
fi

git -C "${SOURCE_DIR}" fetch --depth=1 origin "${HEV_COMMIT}"
git -C "${SOURCE_DIR}" checkout --detach --force "${HEV_COMMIT}"
git -C "${SOURCE_DIR}" submodule sync --recursive
git -C "${SOURCE_DIR}" submodule update --init --recursive --force --depth=1

rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
readonly APPLICATION_MK="${BUILD_DIR}/Application.mk"
cat > "${APPLICATION_MK}" <<'APP_MK'
APP_OPTIM := release
# NDK r29 exposes native platform 35. The APK still targets/min-requires API 36;
# a native library built with API 35 remains valid on Android 16.
APP_PLATFORM := android-35
APP_ABI := arm64-v8a
APP_MODULES := hev-socks5-tunnel
APP_CFLAGS := -O3 -DPKGNAME=io/github/qwqgong/androidcyaml -DCLSNAME=AndroidVpnService
APP_LDFLAGS := -Wl,--build-id=none
APP_SHORT_COMMANDS := true
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
APP_MK

jobs=1
if command -v nproc >/dev/null; then
    jobs="$(nproc)"
elif command -v sysctl >/dev/null; then
    jobs="$(sysctl -n hw.ncpu 2>/dev/null || echo 1)"
fi

"${ndk_build}" \
    -C "${SOURCE_DIR}" \
    NDK_PROJECT_PATH="${SOURCE_DIR}" \
    APP_BUILD_SCRIPT="${SOURCE_DIR}/Android.mk" \
    NDK_APPLICATION_MK="${APPLICATION_MK}" \
    NDK_OUT="${BUILD_DIR}/obj" \
    NDK_LIBS_OUT="${BUILD_DIR}/libs" \
    -j"${jobs}"

readonly BUILT_LIBRARY="${BUILD_DIR}/libs/arm64-v8a/libhev-socks5-tunnel.so"
if [[ ! -f "${BUILT_LIBRARY}" ]]; then
    echo "NDK build did not produce ${BUILT_LIBRARY}" >&2
    exit 1
fi

readelf_bin=""
for candidate in \
    "${ndk_root}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf \
    "$(command -v readelf 2>/dev/null || true)"; do
    if [[ -n "${candidate}" && -x "${candidate}" ]]; then
        readelf_bin="${candidate}"
        break
    fi
done
if [[ -n "${readelf_bin}" ]]; then
    "${readelf_bin}" -h "${BUILT_LIBRARY}" | grep -q 'Machine:.*AArch64' || {
        echo "Built HEV library is not AArch64" >&2
        exit 1
    }
fi

readonly TEMP_OUTPUT="${OUTPUT_FILE}.building"
cp "${BUILT_LIBRARY}" "${TEMP_OUTPUT}"
chmod 0644 "${TEMP_OUTPUT}"
mv -f "${TEMP_OUTPUT}" "${OUTPUT_FILE}"
printf '%s' "${EXPECTED_MARKER}" > "${MARKER_FILE}"
echo "Built ${OUTPUT_FILE}"
