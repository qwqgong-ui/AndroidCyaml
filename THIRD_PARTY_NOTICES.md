# Third-party notices

AndroidCyaml packages and launches the following upstream works.

## mihomo

- Project: <https://github.com/qwqgong-ui/mihomo>
- Pinned commit: `a563ca2194edbf560b3857801cb3cceab13d7ff9`
- License: GNU General Public License v3.0
- Local license copy: [`LICENSES/mihomo-GPL-3.0.txt`](LICENSES/mihomo-GPL-3.0.txt)

The Android arm64 executable is built by [`scripts/build_mihomo.sh`](scripts/build_mihomo.sh).
The build applies [`patches/mihomo-android-vpn.patch`](patches/mihomo-android-vpn.patch). The patch
contains Android descriptor/process compatibility hooks retained from the previous direct-TUN data
path and, for the HEV path, the `-android-disable-tun` guard that prevents a user-supplied
`tun.enable` from opening `/dev/net/tun` while mihomo is running as a local proxy. The current build
does not enable the `with_gvisor` tag because HEV owns the VPN data plane. Until Go 1.27 is
published, the script changes the generated checkout's `go` directive from 1.27 to 1.26. The exact
corresponding source is the pinned upstream commit plus the committed patch.

## HEV SOCKS5 tunnel

- Project: <https://github.com/heiher/hev-socks5-tunnel>
- Pinned commit: `df11261f09ebafc37bac03f81029c9b75a4aa074`
- License: MIT
- Local license copy: [`LICENSES/hev-socks5-tunnel-MIT.txt`](LICENSES/hev-socks5-tunnel-MIT.txt)

The Android arm64 JNI shared library is built from the pinned recursive checkout by
[`scripts/build_hev_socks5_tunnel.sh`](scripts/build_hev_socks5_tunnel.sh). The binary statically
includes the following pinned git submodules selected by the HEV superproject:

- `heiher/hev-socks5-core` — MIT
- `heiher/hev-task-system` — MIT
- `heiher/yaml` — MIT
- `heiher/lwip` — BSD-3-Clause; local license copy:
  [`LICENSES/lwip-BSD-3-Clause.txt`](LICENSES/lwip-BSD-3-Clause.txt)

## zashboard

- Project: <https://github.com/Zephyruso/zashboard>
- Release: `v3.15.0`, asset `dist-no-fonts.zip`
- Release archive SHA-256: `403b351d3663f5fe65db053cb2f3dc980108d8f86e8c6968d56164d3452592e1`
- License: MIT
- Local license copy: [`LICENSES/zashboard-MIT.txt`](LICENSES/zashboard-MIT.txt)

The unmodified release files are stored under `app/src/main/assets/zashboard` and served only
from mihomo's loopback controller.

## MetaCubeX meta-rules-dat

- Project: <https://github.com/MetaCubeX/meta-rules-dat>
- Pinned data commit: `ab44fa37df7a2939806042c20af3a0bfd07152ea`
- Bundled files: `GeoIP.dat`, `GeoSite.dat`
- License: GPL-3.0 (the repository root [`LICENSE`](LICENSE) contains the license text)
