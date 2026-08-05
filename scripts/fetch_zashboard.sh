#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly RELEASE_API="https://api.github.com/repos/Zephyruso/zashboard/releases/latest"
readonly ARCHIVE_NAME="dist-no-fonts.zip"
readonly ASSETS_ROOT="${ROOT_DIR}/app/src/main/assets"
readonly DESTINATION="${ASSETS_ROOT}/zashboard"
readonly VERSION_FILE="${ASSETS_ROOT}/zashboard.version"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
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
    python3 - "${TEMP_DIR}/release.json" "${ARCHIVE_NAME}" <<'PY'
import json
import re
import sys

path, required_name = sys.argv[1:]
with open(path, "r", encoding="utf-8") as handle:
    release = json.load(handle)

if release.get("draft") or release.get("prerelease"):
    raise SystemExit("GitHub latest release unexpectedly resolved to a draft or prerelease")
tag = release.get("tag_name")
if not isinstance(tag, str) or not tag:
    raise SystemExit("latest zashboard release has no tag")

matches = [asset for asset in release.get("assets", []) if asset.get("name") == required_name]
if len(matches) != 1:
    raise SystemExit(f"latest zashboard release must contain exactly one {required_name}")
asset = matches[0]
asset_id = asset.get("id")
url = asset.get("browser_download_url")
digest = asset.get("digest") or ""
match = re.fullmatch(r"sha256:([0-9a-fA-F]{64})", digest)
if not isinstance(asset_id, int) or not isinstance(url, str) or match is None:
    raise SystemExit(f"invalid release metadata for {required_name}")

print(tag)
print(asset_id)
print(url)
print(match.group(1).lower())
PY
)

[[ "${#release_metadata[@]}" -eq 4 ]] || {
    echo "unable to resolve latest zashboard release" >&2
    exit 1
}

readonly RELEASE_TAG="${release_metadata[0]}"
readonly ASSET_ID="${release_metadata[1]}"
readonly DOWNLOAD_URL="${release_metadata[2]}"
readonly ARCHIVE_SHA256="${release_metadata[3]}"
readonly VERSION="${RELEASE_TAG}@${ASSET_ID}"

if [[ -f "${DESTINATION}/index.html" && -f "${VERSION_FILE}" ]] \
    && [[ "$(<"${VERSION_FILE}")" == "${VERSION}" ]]; then
    echo "zashboard ${RELEASE_TAG} ${ARCHIVE_NAME} is already present."
    exit 0
fi

curl --fail --silent --show-error --location --retry 3 \
    --output "${TEMP_DIR}/${ARCHIVE_NAME}" \
    "${DOWNLOAD_URL}"
echo "${ARCHIVE_SHA256}  ${TEMP_DIR}/${ARCHIVE_NAME}" | sha256sum --check --status || {
    echo "zashboard ${ARCHIVE_NAME} checksum mismatch" >&2
    exit 1
}

mkdir -p "${TEMP_DIR}/unpacked"
unzip -q "${TEMP_DIR}/${ARCHIVE_NAME}" -d "${TEMP_DIR}/unpacked"

source_root="${TEMP_DIR}/unpacked"
if [[ -f "${source_root}/dist/index.html" ]]; then
    source_root="${source_root}/dist"
fi
if [[ ! -f "${source_root}/index.html" ]]; then
    echo "downloaded zashboard ${ARCHIVE_NAME} does not contain index.html" >&2
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
if ! mv "${STAGING}" "${DESTINATION}"; then
    [[ ! -e "${DESTINATION}" && -e "${PREVIOUS}" ]] && mv "${PREVIOUS}" "${DESTINATION}"
    exit 1
fi
rm -rf "${PREVIOUS}"
printf '%s' "${VERSION}" > "${VERSION_FILE}"
echo "Installed zashboard ${RELEASE_TAG} ${ARCHIVE_NAME} into ${DESTINATION}"
