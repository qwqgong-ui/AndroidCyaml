# mihomo patch policy

AndroidCyaml uses the tested `qwqgong-ui/mihomo` `dev` commit pinned in
`app/build.gradle.kts` and `scripts/build_mihomo.sh`. Android integration is not committed to the shared
mihomo branch.

During an Android build, `scripts/build_mihomo.sh` creates a disposable checkout
under `.third_party` and first runs mihomo's own
`patches/apply-dependency-patches.sh`, which patches copies of sing-tun,
quic-go, sing-quic and sing-mux and wires them in through a generated modfile.
Those replacements are copied into the `native/mihomo` wrapper module as well,
because Go honours `replace` only in the main module. Skipping that step builds
a kernel that cannot compile against any released sing-tun.

It then applies every patch in this directory and verifies that they change
exactly these files:

- `adapter/outbound/vless.go`
- `component/process/process.go`
- `listener/sing_tun/server_android.go`
- `transport/xhttp/browser_transport.go`
- `transport/xhttp/browser_transport_test.go`

`0001-androidcyaml-platform-hooks.patch` only exposes the endpoint-aware
process-owner hook and avoids starting sing-tun's Android package database after
`VpnService.Builder` has already applied package policy.

`0002-androidcyaml-webview-xhttp.patch` adds the injectable XHTTP browser
transport that `native/mihomo/browser_dialer_android.go` installs through
`xhttp.SetBrowserTransportFactory`, and routes VLESS XHTTP through it.

All lifecycle, JNI exports, fixed `/30` and `/126` TUN addresses, TUN stack
selection, IPv6 override handling, and per-socket `VpnService.protect(fd)` logic
are maintained in AndroidCyaml's `native/mihomo` module. Expanding the patch
allow-list requires an explicit architecture review.
