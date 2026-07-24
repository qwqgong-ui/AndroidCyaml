# Third-party notices

AndroidCyaml packages and launches the following upstream works.

## mihomo

- Project: <https://github.com/qwqgong-ui/mihomo>
- Clean pinned commit: `c3a7b207ffd2bd974b53103df2d67a276e561418`
- AndroidCyaml patch series: [`patches/mihomo/series`](patches/mihomo/series)
- License: GNU General Public License v3.0
- Local license copy: [`LICENSES/mihomo-GPL-3.0.txt`](LICENSES/mihomo-GPL-3.0.txt)

[`scripts/build_mihomo.sh`](scripts/build_mihomo.sh) checks out the clean pinned commit into a temporary
build directory, resets and cleans that checkout, and then applies the AndroidCyaml-owned patch series.
The script rejects a patch set that changes anything outside these three Android-only entry files:

- `android/jni/main.go`
- `android/jni/config.go`
- `android/jni/platform.go`

The shared `qwqgong-ui/mihomo/Alpha` branch therefore remains suitable for desktop builds and does not
contain AndroidCyaml's JNI lifecycle, VpnService contract, or runtime overrides.

The patched Android arm64 core is compiled with Android NDK 29, CGO, Go `-buildmode=c-shared`, and the
`with_gvisor,cmfa` build tags. The generated `libmihomo.so` is packaged next to the C++ JNI wrapper
`libandroidcyaml.so` and runs in the Android VPN service process. The local patch set supplies the
embedded lifecycle API, calls `VpnService.protect(fd)` for every real outbound socket, resolves Android
connection owners through a JNI callback, and permits the system, gVisor, and mixed TUN stacks.

The Android TUN contract uses `172.19.0.1/30`, optional `fdfe:dcba:9876::1/126`, MTU 9000, and disabled
GSO so the system stack has adjacent addresses available for its TCP NAT listener.

## zashboard

- Project: <https://github.com/Zephyruso/zashboard>
- Release: `v3.15.0`, asset `dist-no-fonts.zip`
- Release archive SHA-256: `403b351d3663f5fe65db053cb2f3dc980108d8f86e8c6968d56164d3452592e1`
- License: MIT
- Local license copy: [`LICENSES/zashboard-MIT.txt`](LICENSES/zashboard-MIT.txt)

The unmodified release files are stored under `app/src/main/assets/zashboard` and served only from
mihomo's loopback controller.

## MetaCubeX meta-rules-dat

- Project: <https://github.com/MetaCubeX/meta-rules-dat>
- Pinned data commit: `ab44fa37df7a2939806042c20af3a0bfd07152ea`
- Bundled files: `GeoIP.dat`, `GeoSite.dat`
- License: GPL-3.0 (the repository root [`LICENSE`](LICENSE) contains the license text)
