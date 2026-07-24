# mihomo patch policy

AndroidCyaml uses the desktop-safe `qwqgong-ui/mihomo` commit pinned in
`app/build.gradle.kts`. Android integration is not committed to the shared
mihomo branch.

During an Android build, `scripts/build_mihomo.sh` creates a disposable checkout
under `.third_party`, applies `0001-androidcyaml-platform-hooks.patch`, and
verifies that the patch changes exactly these files:

- `component/process/process.go`
- `listener/sing_tun/server_android.go`

The patch only exposes the endpoint-aware process-owner hook and avoids starting
sing-tun's Android package database after `VpnService.Builder` has already
applied package policy.

All lifecycle, JNI exports, fixed `/30` and `/126` TUN addresses, TUN stack
selection, IPv6 override handling, and per-socket `VpnService.protect(fd)` logic
are maintained in AndroidCyaml's `native/mihomo` module. Expanding the patch
allow-list requires an explicit architecture review.
