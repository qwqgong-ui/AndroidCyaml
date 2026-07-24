# Android runtime architecture

## Ownership

| Layer | Owns | Does not own |
| --- | --- | --- |
| `AndroidVpnService` | VPN permission, foreground lifetime, notification, Builder and TUN FD | mihomo rules and proxy semantics |
| `RuntimeCoordinator` | serialized startup, stop, config, stack, IPv6 and handover transactions | packet processing |
| `RuntimeOverrideStore` | desired TUN stack, process matching and IPv6 settings | YAML mutation or effective network state |
| `Ipv6EnvironmentMonitor` | best validated non-VPN network identity, link state and IPv6 availability | user preference or core lifecycle |
| `AndroidTunManager` | fixed interface addresses, routes, DNS and application scope | socket protection or proxy routing |
| `NativePlatformCallbacks` | per-socket `protect(fd)` and Android UID/package lookup | TUN packet processing |
| `MihomoNative` | Java JNI contract and native response decoding | VPN lifecycle |
| `libandroidcyaml.so` | JNI exports, JavaVM attachment and callback dispatch | mihomo configuration semantics |
| `native/mihomo` | C ABI exports, in-memory Android config adaptation and mihomo package orchestration | Android UI and service policy |
| patched mihomo source | config parsing, sing-tun stacks, DNS, sniffer, rules and outbounds | Android JNI implementation |
| UI/Binder | user intent and observation | runtime ownership |

`MainActivity` runs in `:ui`; `AppControlService`, `AndroidVpnService`, both native libraries and the Go runtime
remain in the default VPN service process.

## Core isolation

`qwqgong-ui/mihomo:Alpha` is a desktop-safe branch and contains no AndroidCyaml JNI, VpnService or runtime
override commits. AndroidCyaml pins one clean mihomo commit and owns all Android-specific integration locally.

The build applies exactly one patch:

```text
patches/mihomo/0001-androidcyaml-platform-hooks.patch
├── component/process/process.go
└── listener/sing_tun/server_android.go
```

The first hunk adds an endpoint-aware platform process resolver callback. The second avoids starting sing-tun's
Android package database when package selection was already completed by `VpnService.Builder`. The build script
checks the patch before applying it and rejects any unexpected changed path.

All larger Android behavior lives outside the core checkout:

```text
native/mihomo/main.go
├── JNI-facing C ABI exports
├── system / gVisor / mixed selection
├── fixed /30 and /126 TUN contract
├── adaptive IPv6 mutation
├── network-handover cache and connection reset
├── find-process-mode mutation
├── TUN FD injection
└── dialer.DefaultSocketHook → protect(fd)
```

The patch is applied only to `.third_party/mihomo-src`, which is ignored by Git. The mihomo repository and its
`Alpha` branch are not mutated by an AndroidCyaml build.

## Native library relationship

```text
Java MihomoNative
  → libandroidcyaml.so
      → libmihomo.so generated from native/mihomo
          → registered C function pointers
              → NativePlatformCallbacks
```

`libmihomo.so` is built with Go `-buildmode=c-shared`. `libandroidcyaml.so` links against the stable SONAME
`libmihomo.so`; CI rejects absolute `DT_NEEDED` paths and circular reverse references. Both libraries are arm64
and use at least 16 KiB LOAD alignment.

## Startup transaction

1. Android enters foreground-service mode.
2. The coordinator reads the persisted stack/process/IPv6 settings.
3. `MihomoNative.prepareTun` parses `config.yaml` inside the embedded Go runtime.
4. `native/mihomo` applies the selected stack and fixed Android interface contract:
   - IPv4 `172.19.0.1/30`;
   - IPv6 `fdfe:dcba:9876::1/126` when effective;
   - MTU 9000;
   - GSO disabled.
5. The Go runtime returns JSON containing the exact `VpnService.Builder` addresses, routes, DNS and package scope.
6. Android establishes or reuses the VPN TUN.
7. Java duplicates the TUN descriptor and transfers that duplicate to `MihomoNative.start`.
8. The Go runtime installs per-socket protect and process-owner hooks, applies the parsed configuration and starts
   sing-tun on the supplied FD.
9. The coordinator publishes `RUNNING` only after the loopback controller is ready and `/configs` confirms an
   enabled TUN with a valid file descriptor.

A failed native start closes the duplicate FD through sing-tun or Java ownership cleanup. The service retains its
original `ParcelFileDescriptor` so a core-only restart can reuse the established VPN interface when Builder options
have not changed.

## Socket protection

The whole application UID is intentionally kept inside VPN routing. For every real mihomo outbound socket:

1. `dialer.DefaultSocketHook` receives the raw FD before connect.
2. Go invokes the registered C function pointer.
3. C++ attaches the current Go thread to the JVM when necessary.
4. `NativePlatformCallbacks.protectSocket` calls `AndroidVpnService.protect(fd)`.
5. A rejected protect operation fails the dial instead of allowing a routing loop.

System-stack internal TCP listeners do not use mihomo's outbound dialer hook, so they remain in the TUN path.

## Application routing

- Without package filters, every eligible application, including AndroidCyaml, remains in the VPN.
- With `include-package`, AndroidCyaml is automatically added to the allowlist.
- With `exclude-package`, attempts to exclude AndroidCyaml are ignored.
- User-requested packages that are not installed are logged and skipped; an include list with no usable target
  package fails rather than silently capturing only the core.

This invariant is required for system TCP NAT to loop packets back to its local listener. The core's true upstream
sockets are the only sockets excluded, individually, through `protect()`.

## TUN stacks

The selection is independent of YAML and never written back:

- `system`: TCP, UDP and ICMP use the sing-tun system implementation.
- `gvisor`: all supported protocols use gVisor.
- `mixed`: official mihomo behavior—TCP system, UDP gVisor.

The fixed `/30` and `/126` prefixes guarantee the system stack has a second interface address for local-listener
NAT. Stack changes restart the embedded core transactionally; the Android TUN is reused when its Builder contract
is unchanged. Android interface setup preserves the host address from each prefix (`.1` / `::1`); network
normalization through `IpPrefix` is used only for routes.

## Process matching

When enabled, the AndroidCyaml wrapper forces `find-process-mode: always`. The patched process entry sends protocol
and original source/destination endpoints through JNI to `ConnectivityManager.getConnectionOwnerUid()`, then maps
the UID to a stable package name. When disabled, the mode is forced to `off`.

## Adaptive IPv6 transaction

`Ipv6EnvironmentMonitor` follows Android's best matching non-VPN Internet network. This avoids observing the
application's own VPN after `establish()`. IPv6 is usable only when that underlying network is validated and has a
global IPv6 address plus an IPv6 default route. ULA, link-local, loopback and VPN virtual addresses do not qualify.

- Desired IPv6 off: runtime is IPv4-only.
- Desired IPv6 on, environment unavailable: preference remains on, effective runtime is IPv4-only.
- A running network becomes temporarily unavailable: the current IP-family contract is preserved while stale
  connections are closed.
- A new available network changes IPv6 usability: coordinator rebuilds with or without the fixed `/126` prefix.
- IPv6 startup itself fails: the runtime is stopped and one IPv4-only retry is attempted.

## Underlying-network handover

The monitor compares both the Android network handle and a stable signature of interface addresses, routes and DNS.
When Wi-Fi/mobile handover keeps the same effective IP-family contract, `RuntimeCoordinator` does not replace the
TUN. Instead, the native runtime flushes the interface and DNS caches, resets persistent resolver transports and
closes tracked mihomo connections. Protected replacement sockets then follow Android's new physical default
network. This preserves the VPN network and avoids a transient no-network callback causing IPv6/IPv4 rebuild
oscillation.

## Config and override transactions

Config validation uses the same embedded core. A candidate file is parsed before atomic installation. If an active
runtime cannot start with the new config, the old file and old runtime settings are restored.

Runtime stack/process/IPv6 changes are persisted atomically. If the requested combination cannot establish a
usable TUN, the previous settings are restored and restarted. No transaction rewrites user YAML.

## Removed architecture

The following components are intentionally gone:

- mihomo child process;
- abstract Unix platform socket;
- JSON request framing and `SCM_RIGHTS` transfer;
- whole-package VPN exclusion;
- stale subprocess reaper;
- HEV/tun2socks/SOCKS packet conversion;
- Android-specific commits on the shared mihomo `Alpha` branch.

Keeping TUN processing, DNS mapping, sniffing, process attribution and outbound protection in one VPN service
process avoids the metadata loss of a TUN-to-SOCKS bridge and restores the system stack's TCP feedback path.
