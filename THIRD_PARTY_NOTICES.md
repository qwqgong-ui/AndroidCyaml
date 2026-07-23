# Third-party notices

AndroidCyaml packages and launches the following upstream works.

## mihomo

- Project: <https://github.com/qwqgong-ui/mihomo>
- Pinned commit: `f8d525565d3d1488835749b9cab8450ad9248b07`
- License: GNU General Public License v3.0
- Local license copy: [`LICENSES/mihomo-GPL-3.0.txt`](LICENSES/mihomo-GPL-3.0.txt)

The Android arm64 executable is built directly from the pinned commit by
[`scripts/build_mihomo.sh`](scripts/build_mihomo.sh), using the `with_gvisor` build tag. AndroidCyaml
applies no source patch. The pinned fork contains the Android platform contract used to obtain a
`VpnService` TUN descriptor and resolve Android connection owners.

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
