# Third-party notices

AndroidCyaml packages and launches the following upstream works.

## mihomo

- Project: <https://github.com/qwqgong-ui/mihomo>
- Source branch: `qwqgong-ui/mihomo:dev`
- Revision: latest `dev` commit resolved at build time; the fetched commit is recorded in `.third_party/mihomo.commit`
- License: GNU General Public License v3.0
- Local license copy: [`LICENSES/mihomo-GPL-3.0.txt`](LICENSES/mihomo-GPL-3.0.txt)

[`scripts/build_mihomo.sh`](scripts/build_mihomo.sh) resolves and checks out the latest mihomo dev commit without applying
an AndroidCyaml source patch, verifies the dev dependency patch chain, and then compiles
[`native/mihomo`](native/mihomo) with Android NDK 29, CGO, Go `-buildmode=c-shared`, and the
AndroidCyaml build tags. The generated `libmihomo.so` is packaged next to the C++ JNI wrapper
`libandroidcyaml.so` and runs in the Android VPN service process.

The dev kernel exposes neutral endpoint-resolution and XHTTP transport interfaces plus a thin
`androidcyaml` facade. The JNI API, runtime configuration mutation, fixed TUN addresses, stack selection,
function-pointer callbacks, WebView implementation, and per-socket `VpnService.protect(fd)` implementation
live entirely in AndroidCyaml's own Go and C++ sources. Ordinary mihomo behavior is unchanged when the
facade callbacks are not registered.

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
