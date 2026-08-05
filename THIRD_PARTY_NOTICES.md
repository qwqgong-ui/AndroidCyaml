# Third-party notices

AndroidCyaml packages and launches the following upstream works.

## mihomo

- Project: <https://github.com/qwqgong-ui/mihomo>
- Pinned clean commit: `0d91f2a2f5334109c1d9cd17f14e525fc38c60bb`
- AndroidCyaml patch: [`patches/mihomo/0001-androidcyaml-platform-hooks.patch`](patches/mihomo/0001-androidcyaml-platform-hooks.patch)
- License: GNU General Public License v3.0
- Local license copy: [`LICENSES/mihomo-GPL-3.0.txt`](LICENSES/mihomo-GPL-3.0.txt)

[`scripts/build_mihomo.sh`](scripts/build_mihomo.sh) checks out the pinned desktop-safe mihomo commit,
verifies and applies the AndroidCyaml-owned patch only inside the ignored build directory, then compiles
[`native/mihomo`](native/mihomo) with Android NDK 29, CGO, Go `-buildmode=c-shared`, and the
`with_gvisor` build tag. The generated `libmihomo.so` is packaged next to the C++ JNI wrapper
`libandroidcyaml.so` and runs in the Android VPN service process.

The patch is deliberately limited to two existing mihomo files: it exposes an endpoint-aware platform process
resolver and skips sing-tun's Android package database when package routing was already applied by
`VpnService.Builder`. The JNI API, runtime configuration mutation, fixed TUN addresses, stack selection,
function-pointer callbacks, and per-socket `VpnService.protect(fd)` implementation live entirely in
AndroidCyaml's own Go and C++ sources. No AndroidCyaml code is committed to `mihomo/Alpha`.

The Android interface contract uses `172.19.0.1/30`, optional `fdfe:dcba:9876::1/126`, MTU 9000, and
disabled GSO so the system stack has the adjacent addresses required by its TCP NAT listener.

## zashboard

- Project: <https://github.com/Zephyruso/zashboard>
- Source selection: latest stable GitHub Release, exact asset `dist-no-fonts.zip`
- Integrity: SHA-256 digest returned for that release asset by the GitHub Releases API
- License: MIT
- Local license copy: [`LICENSES/zashboard-MIT.txt`](LICENSES/zashboard-MIT.txt)

Before every build, [`scripts/fetch_zashboard.sh`](scripts/fetch_zashboard.sh) resolves the latest stable
release, selects only the no-font archive, verifies its release digest, and replaces
`app/src/main/assets/zashboard`. The resolved release tag and asset ID are recorded in
`app/src/main/assets/zashboard.version`. The unmodified files are served only from mihomo's loopback
controller.

## MetaCubeX meta-rules-dat

- Project: <https://github.com/MetaCubeX/meta-rules-dat>
- Source selection: latest GitHub Release
- Bundled assets: `geoip-lite.dat` installed as `GeoIP.dat`, and full `geosite.dat` installed as `GeoSite.dat`
- Integrity: SHA-256 digests returned for both release assets by the GitHub Releases API
- License: GPL-3.0 (the repository root [`LICENSE`](LICENSE) contains the license text)

Before every build, [`scripts/fetch_geodata.sh`](scripts/fetch_geodata.sh) resolves the latest release,
verifies both assets, and replaces `app/src/main/assets/geodata`. The release ID and exact asset IDs are
recorded in `app/src/main/assets/geodata.version` so an upgraded APK replaces the previously installed
GeoIP and GeoSite files.
