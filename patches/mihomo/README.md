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
- `component/dialer/dialer.go`
- `component/dialer/direct_progressive.go`
- `component/dialer/direct_scope.go`
- `component/dialer/direct_scope_test.go`
- `component/dialer/tcp_concurrent_cache.go`
- `component/process/process.go`
- `component/resolver/resolver.go`
- `dns/direct_candidates.go`
- `dns/direct_candidates_test.go`
- `dns/resolver.go`
- `listener/sing_tun/server_android.go`
- `transport/xhttp/browser_transport.go`
- `transport/xhttp/browser_transport_test.go`

`0001-androidcyaml-platform-hooks.patch` only exposes the endpoint-aware
process-owner hook and avoids starting sing-tun's Android package database after
`VpnService.Builder` has already applied package policy.

`0002-androidcyaml-webview-xhttp.patch` adds the injectable XHTTP browser
transport that `native/mihomo/browser_dialer_android.go` installs through
`xhttp.SetBrowserTransportFactory`, and routes VLESS XHTTP through it.

`0003-androidcyaml-progressive-direct-network-cache.patch` makes
`direct-nameserver` query in priority order on a cold cache, refresh the first
two sources independently after expiry, and start TCP as soon as either source
returns. Ordinary answers, per-source 24-hour candidates, and TCP winners are
keyed by AndroidCyaml's privacy-preserving Wi-Fi/SIM environment identity, so
one physical network cannot reuse another network's DIRECT cache records.

All lifecycle, JNI exports, fixed `/30` and `/126` TUN addresses, TUN stack
selection, IPv6 override handling, and per-socket `VpnService.protect(fd)` logic
are maintained in AndroidCyaml's `native/mihomo` module. Expanding the patch
allow-list requires an explicit architecture review.
