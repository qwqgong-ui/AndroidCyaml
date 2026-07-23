# Android runtime architecture

## Ownership

| Layer | Owns | Does not own |
| --- | --- | --- |
| `AndroidVpnService` | VPN permission, foreground lifetime, notification | mihomo configuration semantics |
| `RuntimeCoordinator` | serialized state machine, config and override transactions | packet processing |
| `RuntimeOverrideStore` | persisted shell-only override selection | YAML mutation or core parsing |
| `AndroidPlatformBridge` | Android `VpnService.Builder`, TUN FD, UID lookup | DNS, rules, proxy selection |
| `MihomoRuntime` | subprocess, controller, immutable runtime files and launch arguments | Android routing APIs |
| mihomo | config parsing, TUN stack, DNS, sniffer, rules, outbounds | Android service lifecycle |
| UI/Binder | user intent and observation | runtime ownership |

## Startup transaction

1. Android enters foreground-service mode.
2. The coordinator opens one abstract Unix platform socket.
3. The core parses `config.yaml`.
4. The core applies the validated shell override. Currently only `gvisor` can replace `tun.stack`;
   `system` is rejected and an empty override follows the parsed configuration.
5. The core sends the parsed TUN platform options through `open_tun`.
6. Android establishes `VpnService.Builder` and returns the FD with `SCM_RIGHTS`.
7. mihomo starts sing-tun on that FD, then exposes its loopback controller.
8. The coordinator publishes `RUNNING` only after `/configs` reports an enabled TUN with a valid FD.

Any failure closes the core, platform socket and Android TUN as one unit.

## Override transaction

1. The UI selects an override through `RuntimeOverridesDialog`.
2. `AppControlService` forwards the value over the same-UID Binder interface.
3. `RuntimeCoordinator` validates and persists it through `RuntimeOverrideStore`.
4. If VPN is active, the coordinator restarts mihomo on the existing platform bridge.
5. If the new override cannot establish TUN, the previous value is restored and the previous runtime is
   started again.

The selection is never written into `config.yaml`.

## Why the HEV path was removed

A TUN-to-SOCKS adapter terminates the original IP flow and creates a new loopback SOCKS flow. The
proxy core then observes the adapter connection rather than the Android application's original flow,
which degrades process attribution and can reduce domain metadata to destination IPs. Direct mihomo
TUN processing keeps DNS mapping, sniffing and connection metadata in one core.
