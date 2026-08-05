#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly RELEASE_API="https://api.github.com/repos/MetaCubeX/meta-rules-dat/releases/latest"
readonly GEOIP_ASSET="geoip-lite.dat"
readonly GEOSITE_ASSET="geosite.dat"
readonly ASSETS_ROOT="${ROOT_DIR}/app/src/main/assets"
readonly DESTINATION="${ASSETS_ROOT}/geodata"
readonly VERSION_FILE="${ASSETS_ROOT}/geodata.version"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
command -v sha256sum >/dev/null || { echo "sha256sum is required" >&2; exit 1; }

readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

api_headers=(
    -H "Accept: application/vnd.github+json"
    -H "X-GitHub-Api-Version: 2022-11-28"
    -H "User-Agent: AndroidCyaml-upstream-fetch"
)
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    api_headers+=( -H "Authorization: Bearer ${GITHUB_TOKEN}" )
fi

curl --fail --silent --show-error --location --retry 3 \
    "${api_headers[@]}" \
    --output "${TEMP_DIR}/release.json" \
    "${RELEASE_API}"

mapfile -t release_metadata < <(
    python3 - "${TEMP_DIR}/release.json" "${GEOIP_ASSET}" "${GEOSITE_ASSET}" <<'PY'
import json
import re
import sys

path, *required_names = sys.argv[1:]
with open(path, "r", encoding="utf-8") as handle:
    release = json.load(handle)

assets = {asset.get("name"): asset for asset in release.get("assets", [])}
release_id = release.get("id")
if not isinstance(release_id, int):
    raise SystemExit("latest MetaCubeX release has no numeric id")
print(release_id)

for name in required_names:
    asset = assets.get(name)
    if asset is None:
        raise SystemExit(f"latest MetaCubeX release is missing {name}")
    asset_id = asset.get("id")
    url = asset.get("browser_download_url")
    digest = asset.get("digest") or ""
    if not isinstance(asset_id, int) or not isinstance(url, str):
        raise SystemExit(f"invalid release metadata for {name}")
    match = re.fullmatch(r"sha256:([0-9a-fA-F]{64})", digest)
    if match is None:
        raise SystemExit(f"latest MetaCubeX release has no SHA-256 digest for {name}")
    print(f"{asset_id}\t{url}\t{match.group(1).lower()}")
PY
)

[[ "${#release_metadata[@]}" -eq 3 ]] || {
    echo "unable to resolve latest MetaCubeX geodata release" >&2
    exit 1
}

readonly RELEASE_ID="${release_metadata[0]}"
IFS=$'\t' read -r GEOIP_ASSET_ID GEOIP_URL GEOIP_SHA256 <<< "${release_metadata[1]}"
IFS=$'\t' read -r GEOSITE_ASSET_ID GEOSITE_URL GEOSITE_SHA256 <<< "${release_metadata[2]}"
readonly VERSION="${RELEASE_ID}@${GEOIP_ASSET_ID},${GEOSITE_ASSET_ID}"

verify_file() {
    local expected_sha="$1"
    local path="$2"
    [[ -f "${path}" ]] && echo "${expected_sha}  ${path}" | sha256sum --check --status
}

if verify_file "${GEOIP_SHA256}" "${DESTINATION}/GeoIP.dat" \
    && verify_file "${GEOSITE_SHA256}" "${DESTINATION}/GeoSite.dat" \
    && [[ -f "${VERSION_FILE}" ]] \
    && [[ "$(<"${VERSION_FILE}")" == "${VERSION}" ]]; then
    echo "MetaCubeX latest geodata release ${RELEASE_ID} is already present."
    exit 0
fi

curl --fail --silent --show-error --location --retry 3 \
    --output "${TEMP_DIR}/${GEOIP_ASSET}" \
    "${GEOIP_URL}"
curl --fail --silent --show-error --location --retry 3 \
    --output "${TEMP_DIR}/${GEOSITE_ASSET}" \
    "${GEOSITE_URL}"

verify_file "${GEOIP_SHA256}" "${TEMP_DIR}/${GEOIP_ASSET}" || {
    echo "${GEOIP_ASSET} checksum mismatch" >&2
    exit 1
}
verify_file "${GEOSITE_SHA256}" "${TEMP_DIR}/${GEOSITE_ASSET}" || {
    echo "${GEOSITE_ASSET} checksum mismatch" >&2
    exit 1
}

readonly STAGING="${ASSETS_ROOT}/geodata.installing"
readonly PREVIOUS="${ASSETS_ROOT}/geodata.previous"
rm -rf "${STAGING}" "${PREVIOUS}"
mkdir -p "${STAGING}"
install -m 0644 "${TEMP_DIR}/${GEOIP_ASSET}" "${STAGING}/GeoIP.dat"
install -m 0644 "${TEMP_DIR}/${GEOSITE_ASSET}" "${STAGING}/GeoSite.dat"

if [[ -e "${DESTINATION}" ]]; then
    mv "${DESTINATION}" "${PREVIOUS}"
fi
if ! mv "${STAGING}" "${DESTINATION}"; then
    [[ ! -e "${DESTINATION}" && -e "${PREVIOUS}" ]] && mv "${PREVIOUS}" "${DESTINATION}"
    exit 1
fi
rm -rf "${PREVIOUS}"
printf '%s' "${VERSION}" > "${VERSION_FILE}"
echo "Installed MetaCubeX release ${RELEASE_ID}: ${GEOIP_ASSET} as GeoIP.dat and ${GEOSITE_ASSET} as GeoSite.dat"
