# Android runtime architecture

## Ownership

| Layer | Owns | Does not own |
| --- | --- | --- |
| `AndroidVpnService` | VPN permission, foreground lifetime, notification | mihomo configuration semantics |
| `RuntimeCoordinator` | serialized state machine, config, override and network-change transactions | packet processing |
| `RuntimeOverrideStore` | desired process-matching and IPv6 switches | YAML mutation or effective network state |
| `Ipv6EnvironmentMonitor` | validated underlying IPv6 availability | user preference or mihomo lifecycle |
| `AndroidPlatformBridge` | Android `VpnService.Builder`, TUN FD, UID lookup | DNS, rules, proxy selection |
| `MihomoRuntime` | subprocess, controller, immutable runtime files and launch arguments | Android routing APIs |
| mihomo | config parsing, gVisor TUN, DNS, sniffer, rules, outbounds | Android service lifecycle |
| UI/Binder | user intent and observation | runtime ownership |

## Startup transaction

1. Android enters foreground-service mode.
2. The coordinator opens one abstract Unix platform socket.
3. The core parses `config.yaml`.
4. Android runtime overrides force gVisor, set process matching to `always` or `off`, and remove IPv6
   configuration when the effective IPv6 state is disabled.
5. The core sends the resulting TUN platform options through `open_tun`.
6. Android establishes `VpnService.Builder` and returns the FD with `SCM_RIGHTS`.
7. mihomo starts sing-tun on that FD, then exposes its loopback controller.
8. The coordinator publishes `RUNNING` only after `/configs` reports an enabled TUN with a valid FD.

Any failure closes the core and its TUN attempt as one transaction. When IPv6 was requested, the coordinator
retries once with IPv6 disabled before declaring startup failure.

## Override transaction

1. The UI opens `RuntimeOverridesDialog`.
2. gVisor is displayed as fixed and cannot be changed; process matching and IPv6 remain independent switches.
3. `AppControlService` forwards both desired values over the same-UID Binder interface.
4. `RuntimeCoordinator` persists them atomically through `RuntimeOverrideStore`.
5. If VPN is active, the coordinator restarts mihomo on the existing platform bridge.
6. If the new state cannot establish TUN, the previous settings are restored and the previous runtime is started
   again.

The selection is never written into `config.yaml`.

## Adaptive IPv6 transaction

`Ipv6EnvironmentMonitor` considers IPv6 usable only when the app's underlying default network is validated and
has both a global IPv6 address and an IPv6 default route. ULA, link-local, loopback and VPN virtual addresses do
not qualify.

- Desired IPv6 off: runtime remains IPv4-only regardless of the network.
- Desired IPv6 on, environment unavailable: the preference remains on, but the effective runtime is IPv4-only.
- Environment becomes unavailable while running: the coordinator serially restarts mihomo and rebuilds TUN
  without IPv6.
- Environment recovers: the coordinator restarts again with IPv6.
- IPv6 startup itself fails: the failed core is closed and one IPv4-only retry is attempted.

## Why the HEV path was removed

A TUN-to-SOCKS adapter terminates the original IP flow and creates a new loopback SOCKS flow. The
proxy core then observes the adapter connection rather than the Android application's original flow,
which degrades process attribution and can reduce domain metadata to destination IPs. Direct mihomo
TUN processing keeps DNS mapping, sniffing and connection metadata in one core.
