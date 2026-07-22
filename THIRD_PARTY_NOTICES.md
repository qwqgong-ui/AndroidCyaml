# Third-party notices

AndroidCyaml packages and launches the following upstream works.

## mihomo

- Project: <https://github.com/qwqgong-ui/mihomo>
- Pinned commit: `a563ca2194edbf560b3857801cb3cceab13d7ff9`
- License: GNU General Public License v3.0
- Local license copy: [`LICENSES/mihomo-GPL-3.0.txt`](LICENSES/mihomo-GPL-3.0.txt)

The Android arm64 executable is built by [`scripts/build_mihomo.sh`](scripts/build_mihomo.sh).
Until Go 1.27 is published, the script changes only the generated checkout's `go` directive from
1.27 to 1.26; it does not change any Go source file. The exact corresponding source can be obtained
by checking out the pinned commit from the project URL above.

## zashboard

- Project: <https://github.com/Zephyruso/zashboard>
- Release: `v3.15.0`, asset `dist-no-fonts.zip`
- Release archive SHA-256: `403b351d3663f5fe65db053cb2f3dc980108d8f86e8c6968d56164d3452592e1`
- License: MIT
- Local license copy: [`LICENSES/zashboard-MIT.txt`](LICENSES/zashboard-MIT.txt)

The unmodified release files are stored under `app/src/main/assets/zashboard` and served only
from mihomo's loopback controller.
