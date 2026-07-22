#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCE_URL="https://github.com/qwqgong-ui/mihomo.git"
readonly MIHOMO_COMMIT="a563ca2194edbf560b3857801cb3cceab13d7ff9"
readonly BUILD_RECIPE_VERSION="2"
readonly PATCH_FILE="${ROOT_DIR}/patches/mihomo-android-vpn.patch"
readonly SOURCE_DIR="${ROOT_DIR}/.third_party/mihomo-src"
readonly OUTPUT_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
readonly OUTPUT_FILE="${OUTPUT_DIR}/libmihomo.so"
readonly MARKER_FILE="${ROOT_DIR}/.third_party/mihomo.commit"
readonly PATCH_SHA256="$(sha256sum "${PATCH_FILE}" | awk '{print $1}')"
readonly EXPECTED_MARKER="${MIHOMO_COMMIT}:android-arm64-v${BUILD_RECIPE_VERSION}:${PATCH_SHA256}"

if [[ -f "${OUTPUT_FILE}" && -f "${MARKER_FILE}" ]] \
    && [[ "$(<"${MARKER_FILE}")" == "${EXPECTED_MARKER}" ]]; then
    echo "mihomo ${MIHOMO_COMMIT:0:8} is already built."
    exit 0
fi

command -v git >/dev/null || { echo "git is required" >&2; exit 1; }
command -v go >/dev/null || { echo "Go 1.26.5+ is required" >&2; exit 1; }

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
git -C "${SOURCE_DIR}" apply --unidiff-zero --check "${PATCH_FILE}"
git -C "${SOURCE_DIR}" apply --unidiff-zero "${PATCH_FILE}"

# The pinned Alpha commit declares the not-yet-published Go 1.27 toolchain, but
# currently compiles cleanly with Go 1.26.5. The generated checkout's
# language-version declaration is adjusted alongside the committed Android
# VpnService integration patch.
readonly INSTALLED_GO_VERSION="$(GOTOOLCHAIN=local go env GOVERSION)"
case "${INSTALLED_GO_VERSION}" in
    go1.26.*)
        (
            cd "${SOURCE_DIR}"
            GOTOOLCHAIN=local go mod edit -go=1.26
        )
        readonly GO_TOOLCHAIN_MODE="local"
        ;;
    go1.27.*|go1.28.*|go1.29.*)
        readonly GO_TOOLCHAIN_MODE="local"
        ;;
    *)
        echo "Go 1.26.5+ is required; found ${INSTALLED_GO_VERSION}" >&2
        exit 1
        ;;
esac

readonly BUILD_TIME="$(git -C "${SOURCE_DIR}" show -s --format=%cI "${MIHOMO_COMMIT}")"
readonly VERSION="androidcyaml-${MIHOMO_COMMIT:0:8}"
readonly TEMP_OUTPUT="${OUTPUT_FILE}.building"
readonly LDFLAGS="-X github.com/metacubex/mihomo/constant.Version=${VERSION} -X github.com/metacubex/mihomo/constant.BuildTime=${BUILD_TIME} -w -s -buildid="

(
    cd "${SOURCE_DIR}"
    GOTOOLCHAIN="${GO_TOOLCHAIN_MODE}" \
    CGO_ENABLED=0 \
    GOOS=android \
    GOARCH=arm64 \
        go build \
        -tags with_gvisor \
        -trimpath \
        -ldflags "${LDFLAGS}" \
        -o "${TEMP_OUTPUT}" \
        .
)

if command -v readelf >/dev/null; then
    readelf -h "${TEMP_OUTPUT}" | grep -q 'Machine:.*AArch64' || {
        echo "Built core is not AArch64" >&2
        exit 1
    }
fi

chmod 0755 "${TEMP_OUTPUT}"
mv -f "${TEMP_OUTPUT}" "${OUTPUT_FILE}"
printf '%s' "${EXPECTED_MARKER}" > "${MARKER_FILE}"
echo "Built ${OUTPUT_FILE}"
