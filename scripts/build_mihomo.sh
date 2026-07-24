#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCE_URL="https://github.com/qwqgong-ui/mihomo.git"
readonly MIHOMO_COMMIT="0d91f2a2f5334109c1d9cd17f14e525fc38c60bb"
readonly PATCH_FILE="${ROOT_DIR}/patches/mihomo/0001-androidcyaml-platform-hooks.patch"
readonly WRAPPER_SOURCE_DIR="${ROOT_DIR}/native/mihomo"
readonly BUILD_RECIPE_VERSION="17"
readonly NDK_VERSION="29.0.14206865"
readonly NATIVE_API="35"
readonly SOURCE_DIR="${ROOT_DIR}/.third_party/mihomo-src"
readonly MODULE_DIR="${ROOT_DIR}/.third_party/androidcyaml-mihomo-module"
readonly TEMP_DIR="${ROOT_DIR}/.third_party/mihomo-jni-building"
readonly HEADER_DIR="${ROOT_DIR}/app/src/main/cpp/generated"
readonly LIBRARY_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
readonly OUTPUT_LIBRARY="${LIBRARY_DIR}/libmihomo.so"
readonly OUTPUT_HEADER="${HEADER_DIR}/libmihomo.h"
readonly MARKER_FILE="${ROOT_DIR}/.third_party/mihomo.commit"

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
command -v go >/dev/null || { echo "Go 1.26+ is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
[[ -f "${PATCH_FILE}" ]] || { echo "mihomo patch is missing: ${PATCH_FILE}" >&2; exit 1; }
[[ -f "${WRAPPER_SOURCE_DIR}/go.mod" && -f "${WRAPPER_SOURCE_DIR}/main.go" ]] || {
    echo "AndroidCyaml Go wrapper sources are incomplete" >&2
    exit 1
}

readonly PATCH_DIGEST="$(sha256sum "${PATCH_FILE}" | awk '{ print $1 }')"
readonly WRAPPER_DIGEST="$(cat "${WRAPPER_SOURCE_DIR}/go.mod" "${WRAPPER_SOURCE_DIR}/main.go" | sha256sum | awk '{ print $1 }')"
readonly EXPECTED_MARKER="${MIHOMO_COMMIT}:${PATCH_DIGEST}:${WRAPPER_DIGEST}:android-arm64-jni-c-shared-v${BUILD_RECIPE_VERSION}"

if [[ -f "${OUTPUT_LIBRARY}" && -f "${OUTPUT_HEADER}" && -f "${MARKER_FILE}" ]] \
    && [[ "$(<"${MARKER_FILE}")" == "${EXPECTED_MARKER}" ]]; then
    echo "mihomo ${MIHOMO_COMMIT:0:8} with AndroidCyaml patch ${PATCH_DIGEST:0:8} is already built."
    exit 0
fi

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${sdk_root}" && -f "${ROOT_DIR}/local.properties" ]]; then
    sdk_root="$(sed -n 's/^sdk.dir=//p' "${ROOT_DIR}/local.properties" | tail -n 1)"
fi
readonly SDK_ROOT="${sdk_root}"
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
readonly ANDROID_CC="${TOOLCHAIN}/bin/aarch64-linux-android${NATIVE_API}-clang"
readonly ANDROID_CXX="${TOOLCHAIN}/bin/aarch64-linux-android${NATIVE_API}-clang++"
readonly ANDROID_AR="${TOOLCHAIN}/bin/llvm-ar"
[[ -x "${ANDROID_CC}" && -x "${ANDROID_CXX}" && -x "${ANDROID_AR}" ]] || {
    echo "NDK arm64 API ${NATIVE_API} toolchain is incomplete" >&2
    exit 1
}

mkdir -p "${ROOT_DIR}/.third_party" "${HEADER_DIR}" "${LIBRARY_DIR}"

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

# AndroidCyaml owns this patch. The mihomo checkout remains pinned to a clean,
# desktop-safe commit and is modified only inside the ignored build directory.
git -C "${SOURCE_DIR}" apply --check --whitespace=error-all "${PATCH_FILE}"
git -C "${SOURCE_DIR}" apply --whitespace=error-all "${PATCH_FILE}"
git -C "${SOURCE_DIR}" diff --check

readonly EXPECTED_PATCH_PATHS=$'component/process/process.go\nlistener/sing_tun/server_android.go'
readonly ACTUAL_PATCH_PATHS="$(git -C "${SOURCE_DIR}" diff --name-only | sort)"
if [[ "${ACTUAL_PATCH_PATHS}" != "${EXPECTED_PATCH_PATHS}" ]]; then
    echo "AndroidCyaml patch touched unexpected mihomo files:" >&2
    printf '%s\n' "${ACTUAL_PATCH_PATHS}" >&2
    exit 1
fi

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

rm -rf "${MODULE_DIR}" "${TEMP_DIR}"
mkdir -p "${MODULE_DIR}" "${TEMP_DIR}"
cp "${WRAPPER_SOURCE_DIR}/go.mod" "${WRAPPER_SOURCE_DIR}/main.go" "${MODULE_DIR}/"
printf '\nreplace github.com/metacubex/mihomo => ../mihomo-src\n' >> "${MODULE_DIR}/go.mod"

readonly BUILD_TIME="$(git -C "${SOURCE_DIR}" show -s --format=%cI "${MIHOMO_COMMIT}")"
readonly VERSION="androidcyaml-${MIHOMO_COMMIT:0:8}-p${PATCH_DIGEST:0:8}"
readonly LDFLAGS="-X github.com/metacubex/mihomo/constant.Version=${VERSION} -X github.com/metacubex/mihomo/constant.BuildTime=${BUILD_TIME} -w -buildid="

(
    cd "${MODULE_DIR}"
    env \
        GOWORK=off \
        GOTOOLCHAIN="${GO_TOOLCHAIN_MODE}" \
        CGO_ENABLED=1 \
        GOOS=android \
        GOARCH=arm64 \
        CC="${ANDROID_CC}" \
        CXX="${ANDROID_CXX}" \
        AR="${ANDROID_AR}" \
        CGO_LDFLAGS="-Wl,-soname,libmihomo.so -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
        go build \
        -mod=mod \
        -buildmode=c-shared \
        -tags with_gvisor \
        -trimpath \
        -ldflags "${LDFLAGS}" \
        -o "${TEMP_DIR}/libmihomo.so" \
        .
)

[[ -s "${TEMP_DIR}/libmihomo.so" && -s "${TEMP_DIR}/libmihomo.h" ]] || {
    echo "Go did not produce the JNI shared library and header" >&2
    exit 1
}

mv -f "${TEMP_DIR}/libmihomo.so" "${OUTPUT_LIBRARY}"
mv -f "${TEMP_DIR}/libmihomo.h" "${OUTPUT_HEADER}"
rmdir "${TEMP_DIR}"
printf '%s' "${EXPECTED_MARKER}" > "${MARKER_FILE}"
echo "Built ${OUTPUT_LIBRARY} and ${OUTPUT_HEADER} from clean mihomo plus the AndroidCyaml-owned patch"
