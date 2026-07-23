#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCE_URL="https://github.com/qwqgong-ui/mihomo.git"
readonly MIHOMO_COMMIT="82fa6be864f76a70a0024e9035205a2fad6cda96"
readonly BUILD_RECIPE_VERSION="8"
readonly NDK_VERSION="29.0.14206865"
readonly NATIVE_API="35"
readonly SOURCE_DIR="${ROOT_DIR}/.third_party/mihomo-src"
readonly OUTPUT_DIR="${ROOT_DIR}/app/src/main/cpp/generated"
readonly OUTPUT_ARCHIVE="${OUTPUT_DIR}/libmihomo.a"
readonly OUTPUT_HEADER="${OUTPUT_DIR}/libmihomo.h"
readonly MARKER_FILE="${ROOT_DIR}/.third_party/mihomo.commit"
readonly EXPECTED_MARKER="${MIHOMO_COMMIT}:android-arm64-jni-c-archive-v${BUILD_RECIPE_VERSION}"

if [[ -f "${OUTPUT_ARCHIVE}" && -f "${OUTPUT_HEADER}" && -f "${MARKER_FILE}" ]] \
    && [[ "$(<"${MARKER_FILE}")" == "${EXPECTED_MARKER}" ]]; then
    echo "mihomo JNI archive ${MIHOMO_COMMIT:0:8} is already built."
    exit 0
fi

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
command -v go >/dev/null || { echo "Go 1.26+ is required" >&2; exit 1; }

readonly SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
readonly NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${SDK_ROOT}/ndk/${NDK_VERSION}}}"
if [[ ! -d "${NDK_ROOT}" ]]; then
    echo "Android NDK ${NDK_VERSION} is required; expected ${NDK_ROOT}" >&2
    exit 1
fi

host_tag=""
case "$(uname -s)" in
    Linux) host_tag="linux-x86_64" ;;
    Darwin)
        for candidate in darwin-arm64 darwin-x86_64; do
            if [[ -d "${NDK_ROOT}/toolchains/llvm/prebuilt/${candidate}" ]]; then
                host_tag="${candidate}"
                break
            fi
        done
        ;;
esac
if [[ -z "${host_tag}" ]]; then
    echo "Unsupported NDK host: $(uname -s)" >&2
    exit 1
fi

readonly TOOLCHAIN="${NDK_ROOT}/toolchains/llvm/prebuilt/${host_tag}"
readonly CC="${TOOLCHAIN}/bin/aarch64-linux-android${NATIVE_API}-clang"
readonly CXX="${TOOLCHAIN}/bin/aarch64-linux-android${NATIVE_API}-clang++"
readonly AR="${TOOLCHAIN}/bin/llvm-ar"
[[ -x "${CC}" && -x "${CXX}" && -x "${AR}" ]] || {
    echo "NDK arm64 API ${NATIVE_API} toolchain is incomplete" >&2
    exit 1
}

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
    echo "Unexpected mihomo origin: ${actual_origin}" >&2
    exit 1
fi

git -C "${SOURCE_DIR}" fetch --depth=1 origin "${MIHOMO_COMMIT}"
git -C "${SOURCE_DIR}" checkout --detach --force "${MIHOMO_COMMIT}"
git -C "${SOURCE_DIR}" clean -ffdqx

readonly INSTALLED_GO_VERSION="$(GOTOOLCHAIN=local go env GOVERSION)"
case "${INSTALLED_GO_VERSION}" in
    go1.26.*|go1.27.*|go1.28.*|go1.29.*)
        readonly GO_TOOLCHAIN_MODE="local"
        ;;
    *)
        echo "Go 1.26+ is required; found ${INSTALLED_GO_VERSION}" >&2
        exit 1
        ;;
esac

readonly BUILD_TIME="$(git -C "${SOURCE_DIR}" show -s --format=%cI "${MIHOMO_COMMIT}")"
readonly VERSION="androidcyaml-${MIHOMO_COMMIT:0:8}"
readonly LDFLAGS="-X github.com/metacubex/mihomo/constant.Version=${VERSION} -X github.com/metacubex/mihomo/constant.BuildTime=${BUILD_TIME} -w -s -buildid="
readonly TEMP_DIR="${OUTPUT_DIR}/.building"
rm -rf "${TEMP_DIR}"
mkdir -p "${TEMP_DIR}"

(
    cd "${SOURCE_DIR}"
    GOTOOLCHAIN="${GO_TOOLCHAIN_MODE}" \
    CGO_ENABLED=1 \
    GOOS=android \
    GOARCH=arm64 \
    CC="${CC}" \
    CXX="${CXX}" \
    AR="${AR}" \
    CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
        go build \
        -buildmode=c-archive \
        -tags with_gvisor \
        -trimpath \
        -ldflags "${LDFLAGS}" \
        -o "${TEMP_DIR}/libmihomo.a" \
        ./android/jni
)

[[ -s "${TEMP_DIR}/libmihomo.a" && -s "${TEMP_DIR}/libmihomo.h" ]] || {
    echo "Go did not produce the JNI C archive and header" >&2
    exit 1
}

mv -f "${TEMP_DIR}/libmihomo.a" "${OUTPUT_ARCHIVE}"
mv -f "${TEMP_DIR}/libmihomo.h" "${OUTPUT_HEADER}"
rmdir "${TEMP_DIR}"
printf '%s' "${EXPECTED_MARKER}" > "${MARKER_FILE}"
rm -f "${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a/libmihomo.so"
echo "Built ${OUTPUT_ARCHIVE} and ${OUTPUT_HEADER}"
