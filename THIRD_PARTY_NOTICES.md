# Third-party notices

AndroidCyaml packages and launches the following upstream works.

## mihomo

- Project: <https://github.com/qwqgong-ui/mihomo>
- Pinned commit: `6f5e165f4ad98a07d9a8284bf46617580aa05e8a`
- License: GNU General Public License v3.0
- Local license copy: [`LICENSES/mihomo-GPL-3.0.txt`](LICENSES/mihomo-GPL-3.0.txt)

The Android arm64 core is built directly from the pinned commit by
[`scripts/build_mihomo.sh`](scripts/build_mihomo.sh), using Android NDK 29, CGO, Go
`-buildmode=c-shared`, and the `with_gvisor` build tag. The generated `libmihomo.so` is packaged next
to the C++ JNI wrapper `libandroidcyaml.so` and runs in the Android VPN service process.

AndroidCyaml applies no post-checkout source patch. The pinned fork contains the exported embedded
runtime API used to validate and apply configuration, start sing-tun on an Android-provided file
descriptor, protect each real outbound socket through `VpnService.protect(fd)`, resolve Android
connection owners, and select the system, gVisor, or mixed TUN stack. The Android interface contract
uses `172.19.0.1/30`, optional `fdfe:dcba:9876::1/126`, MTU 9000, and disabled GSO so the system stack
has the adjacent addresses required by its TCP NAT listener.

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
