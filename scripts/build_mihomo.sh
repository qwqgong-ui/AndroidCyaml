#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCE_URL="https://github.com/qwqgong-ui/mihomo.git"
readonly MIHOMO_COMMIT="10579d810750b1c177aa17a0da0d6b48bd489fa3"
readonly PATCH_MBOX="${ROOT_DIR}/patches/mihomo/androidcyaml-jni-runtime.mbox"
readonly BUILD_RECIPE_VERSION="15"
readonly NDK_VERSION="29.0.14206865"
readonly NATIVE_API="35"
readonly SOURCE_DIR="${ROOT_DIR}/.third_party/mihomo-src"
readonly PATCH_SPLIT_DIR="${ROOT_DIR}/.third_party/mihomo-patches"
readonly HEADER_DIR="${ROOT_DIR}/app/src/main/cpp/generated"
readonly LIBRARY_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
readonly OUTPUT_LIBRARY="${LIBRARY_DIR}/libmihomo.so"
readonly OUTPUT_HEADER="${HEADER_DIR}/libmihomo.h"
readonly MARKER_FILE="${ROOT_DIR}/.third_party/mihomo.commit"

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
command -v go >/dev/null || { echo "Go 1.26+ is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }
[[ -f "${PATCH_MBOX}" ]] || { echo "mihomo patch set is missing: ${PATCH_MBOX}" >&2; exit 1; }

readonly PATCH_DIGEST="$(sha256sum "${PATCH_MBOX}" | awk '{ print $1 }')"
readonly EXPECTED_MARKER="${MIHOMO_COMMIT}:${PATCH_DIGEST}:android-arm64-jni-c-shared-v${BUILD_RECIPE_VERSION}"

if [[ -f "${OUTPUT_LIBRARY}" && -f "${OUTPUT_HEADER}" && -f "${MARKER_FILE}" ]] \
    && [[ "$(<"${MARKER_FILE}")" == "${EXPECTED_MARKER}" ]]; then
    echo "mihomo ${MIHOMO_COMMIT:0:8} with AndroidCyaml patch ${PATCH_DIGEST:0:8} is already built."
    exit 0
fi

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

rm -rf "${PATCH_SPLIT_DIR}"
mkdir -p "${PATCH_SPLIT_DIR}"
git mailsplit -o"${PATCH_SPLIT_DIR}" "${PATCH_MBOX}" >/dev/null
patch_count=0
while IFS= read -r patch_file; do
    [[ -n "${patch_file}" ]] || continue
    git -C "${SOURCE_DIR}" apply --check --whitespace=error-all "${patch_file}"
    git -C "${SOURCE_DIR}" apply --whitespace=error-all "${patch_file}"
    patch_count=$((patch_count + 1))
done < <(find "${PATCH_SPLIT_DIR}" -type f -print | sort)
rm -rf "${PATCH_SPLIT_DIR}"

if (( patch_count == 0 )); then
    echo "mihomo patch mailbox did not contain any patches" >&2
    exit 1
fi
[[ -f "${SOURCE_DIR}/android/jni/main.go" ]] || {
    echo "Android JNI entry point was not created by the patch set" >&2
    exit 1
}
[[ -f "${SOURCE_DIR}/component/androidplatform/embedded_android.go" ]] || {
    echo "Android embedded TUN adapter was not created by the patch set" >&2
    exit 1
}
git -C "${SOURCE_DIR}" diff --check

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
readonly VERSION="androidcyaml-${MIHOMO_COMMIT:0:8}-p${PATCH_DIGEST:0:8}"
readonly LDFLAGS="-X github.com/metacubex/mihomo/constant.Version=${VERSION} -X github.com/metacubex/mihomo/constant.BuildTime=${BUILD_TIME} -w -s -buildid="
readonly TEMP_DIR="${ROOT_DIR}/.third_party/mihomo-jni-building"
rm -rf "${TEMP_DIR}"
mkdir -p "${TEMP_DIR}"

(
    cd "${SOURCE_DIR}"
    env \
        GOTOOLCHAIN="${GO_TOOLCHAIN_MODE}" \
        CGO_ENABLED=1 \
        GOOS=android \
        GOARCH=arm64 \
        CC="${ANDROID_CC}" \
        CXX="${ANDROID_CXX}" \
        AR="${ANDROID_AR}" \
        CGO_LDFLAGS="-Wl,-soname,libmihomo.so -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
        go build \
        -buildmode=c-shared \
        -tags with_gvisor \
        -trimpath \
        -ldflags "${LDFLAGS}" \
        -o "${TEMP_DIR}/libmihomo.so" \
        ./android/jni
)

[[ -s "${TEMP_DIR}/libmihomo.so" && -s "${TEMP_DIR}/libmihomo.h" ]] || {
    echo "Go did not produce the JNI shared library and header" >&2
    exit 1
}

mv -f "${TEMP_DIR}/libmihomo.so" "${OUTPUT_LIBRARY}"
mv -f "${TEMP_DIR}/libmihomo.h" "${OUTPUT_HEADER}"
rmdir "${TEMP_DIR}"
printf '%s' "${EXPECTED_MARKER}" > "${MARKER_FILE}"
echo "Built ${OUTPUT_LIBRARY} and ${OUTPUT_HEADER} from pristine mihomo plus AndroidCyaml patches"
