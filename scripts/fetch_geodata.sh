#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCE_COMMIT="ab44fa37df7a2939806042c20af3a0bfd07152ea"
readonly BASE_URL="https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/${SOURCE_COMMIT}"
readonly GEOIP_SHA256="af332ab88eb4bb15e3cd10f03f5542e90655ee4bd5bf0e23949cfbd1e46bc20f"
readonly GEOSITE_SHA256="258441c3cb7d25a29d532f70b7120652e42a2e1bca7c6824a913e357e869da8e"
readonly ASSETS_ROOT="${ROOT_DIR}/app/src/main/assets"
readonly DESTINATION="${ASSETS_ROOT}/geodata"
readonly VERSION_FILE="${ASSETS_ROOT}/geodata.version"

verify_file() {
    local expected_sha="$1"
    local path="$2"
    [[ -f "${path}" ]] && echo "${expected_sha}  ${path}" | sha256sum --check --status
}

if verify_file "${GEOIP_SHA256}" "${DESTINATION}/GeoIP.dat" \
    && verify_file "${GEOSITE_SHA256}" "${DESTINATION}/GeoSite.dat" \
    && [[ -f "${VERSION_FILE}" ]] \
    && [[ "$(<"${VERSION_FILE}")" == "${SOURCE_COMMIT}" ]]; then
    echo "MetaCubeX geodata ${SOURCE_COMMIT:0:8} is already present."
    exit 0
fi

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }

readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

curl --fail --location --retry 3 --output "${TEMP_DIR}/GeoIP.dat" "${BASE_URL}/geoip.dat"
curl --fail --location --retry 3 --output "${TEMP_DIR}/GeoSite.dat" "${BASE_URL}/geosite.dat"

verify_file "${GEOIP_SHA256}" "${TEMP_DIR}/GeoIP.dat" || {
    echo "GeoIP.dat checksum mismatch" >&2
    exit 1
}
verify_file "${GEOSITE_SHA256}" "${TEMP_DIR}/GeoSite.dat" || {
    echo "GeoSite.dat checksum mismatch" >&2
    exit 1
}

readonly STAGING="${ASSETS_ROOT}/geodata.installing"
readonly PREVIOUS="${ASSETS_ROOT}/geodata.previous"
rm -rf "${STAGING}" "${PREVIOUS}"
mkdir -p "${STAGING}"
install -m 0644 "${TEMP_DIR}/GeoIP.dat" "${STAGING}/GeoIP.dat"
install -m 0644 "${TEMP_DIR}/GeoSite.dat" "${STAGING}/GeoSite.dat"

if [[ -e "${DESTINATION}" ]]; then
    mv "${DESTINATION}" "${PREVIOUS}"
fi
mv "${STAGING}" "${DESTINATION}"
rm -rf "${PREVIOUS}"
printf '%s' "${SOURCE_COMMIT}" > "${VERSION_FILE}"
echo "Installed MetaCubeX geodata ${SOURCE_COMMIT:0:8} into ${DESTINATION}"
