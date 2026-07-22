#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly VERSION="v3.15.0"
readonly ARCHIVE_NAME="dist-no-fonts.zip"
readonly ARCHIVE_SHA256="403b351d3663f5fe65db053cb2f3dc980108d8f86e8c6968d56164d3452592e1"
readonly DOWNLOAD_URL="https://github.com/Zephyruso/zashboard/releases/download/${VERSION}/${ARCHIVE_NAME}"
readonly ASSETS_ROOT="${ROOT_DIR}/app/src/main/assets"
readonly DESTINATION="${ASSETS_ROOT}/zashboard"
readonly VERSION_FILE="${ASSETS_ROOT}/zashboard.version"

if [[ -f "${DESTINATION}/index.html" && -f "${VERSION_FILE}" ]] \
    && [[ "$(<"${VERSION_FILE}")" == "${VERSION}" ]]; then
    echo "zashboard ${VERSION} is already present."
    exit 0
fi

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }

readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

curl --fail --location --retry 3 --output "${TEMP_DIR}/${ARCHIVE_NAME}" "${DOWNLOAD_URL}"
echo "${ARCHIVE_SHA256}  ${TEMP_DIR}/${ARCHIVE_NAME}" | sha256sum --check --status || {
    echo "zashboard checksum mismatch" >&2
    exit 1
}

mkdir -p "${TEMP_DIR}/unpacked"
unzip -q "${TEMP_DIR}/${ARCHIVE_NAME}" -d "${TEMP_DIR}/unpacked"

source_root="${TEMP_DIR}/unpacked"
if [[ -f "${source_root}/dist/index.html" ]]; then
    source_root="${source_root}/dist"
fi
if [[ ! -f "${source_root}/index.html" ]]; then
    echo "Downloaded zashboard does not contain index.html" >&2
    exit 1
fi

readonly STAGING="${ASSETS_ROOT}/zashboard.installing"
readonly PREVIOUS="${ASSETS_ROOT}/zashboard.previous"
rm -rf "${STAGING}" "${PREVIOUS}"
mkdir -p "${STAGING}"
cp -a "${source_root}/." "${STAGING}/"

if [[ -e "${DESTINATION}" ]]; then
    mv "${DESTINATION}" "${PREVIOUS}"
fi
mv "${STAGING}" "${DESTINATION}"
rm -rf "${PREVIOUS}"
printf '%s' "${VERSION}" > "${VERSION_FILE}"
echo "Installed zashboard ${VERSION} into ${DESTINATION}"
