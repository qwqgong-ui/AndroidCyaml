# Android runtime architecture

## Ownership

| Layer | Owns | Does not own |
| --- | --- | --- |
| `AndroidVpnService` | VPN permission, foreground lifetime, notification, Builder and TUN FD | mihomo rules and proxy semantics |
| `RuntimeCoordinator` | serialized startup, stop, config, IPv6 and handover transactions | packet processing |
| `RuntimeOverrideStore` | process matching, IPv6, log level, adaptive TCP concurrency and LAN WebUI intent | YAML mutation or effective network state |
| `Ipv6EnvironmentMonitor` | best validated non-VPN network identity, link state and IPv6 availability | user preference or core lifecycle |
| `AndroidTunManager` | fixed interface addresses, routes, DNS and application scope | socket protection or proxy routing |
| `NativePlatformCallbacks` | per-socket `protect(fd)` and Android UID/package lookup | TUN packet processing |
| `MihomoNative` | Java JNI contract and native response decoding | VPN lifecycle |
| `libandroidcyaml.so` | JNI exports, JavaVM attachment and callback dispatch | mihomo configuration semantics |
| `native/mihomo` | C ABI exports, in-memory Android config adaptation and mihomo package orchestration | Android UI and service policy |
| patched mihomo source | config parsing, system sing-tun, DNS, sniffer, rules and outbounds | Android JNI implementation |
| UI/Binder | user intent and observation | runtime ownership |

`MainActivity` runs in `:ui`; `AppControlService`、`AndroidVpnService`、两个原生库和 Go runtime 均位于默认
VPN 服务进程。

## Core isolation

AndroidCyaml 固定一个 mihomo 提交，并在自己的临时构建目录中应用 Android 平台补丁。构建不会把
JNI、VpnService 或运行时覆写代码写回 mihomo checkout。

构建应用的补丁为：

```text
patches/mihomo/0001-androidcyaml-platform-hooks.patch
├── component/process/process.go
└── listener/sing_tun/server_android.go
```

第一个修改增加 endpoint-aware 的 Android 进程解析回调；第二个修改在应用范围已由
`VpnService.Builder` 处理时跳过 sing-tun Android 包数据库。构建脚本会验证补丁路径并拒绝额外修改。

较大的 Android 行为全部位于 AndroidCyaml：

```text
native/mihomo/main.go
├── JNI-facing C ABI exports
├── 强制 system TUN 栈
├── 固定 /30 和 /126 TUN 合约
├── 自适应 IPv6 配置变换
├── 网络切换缓存与连接重置
├── find-process-mode 变换
├── TUN FD 注入
└── dialer.DefaultSocketHook → protect(fd)
```

`libmihomo.so` 使用以下关键构建条件：

```text
GOOS=android
GOARCH=arm64
CGO_ENABLED=1
-buildmode=c-shared
-tags "no_tailscale no_zerotier"
```

不启用 `with_gvisor`，因此 APK 内核不包含 gVisor/mixed TUN 栈；Tailscale 与 ZeroTier 出站也被裁剪。

## Native library relationship

```text
Java MihomoNative
  → libandroidcyaml.so
      → libmihomo.so generated from native/mihomo
          → registered C function pointers
              → NativePlatformCallbacks
```

`libandroidcyaml.so` 只按稳定 SONAME `libmihomo.so` 链接。CI 验证两个库均为 arm64、LOAD 段至少
16 KiB 对齐、不包含构建机绝对依赖路径，也不存在 Go 核心反向引用 JNI 包装层的循环依赖。

## Startup transaction

1. Android 进入前台服务模式；
2. coordinator 读取进程匹配、IPv6、日志、TCP 并发和 WebUI 覆写；
3. `MihomoNative.prepareTun` 在嵌入式 Go runtime 中解析 `config.yaml`；
4. `native/mihomo` 强制 system 栈并应用固定 Android 接口合约：
   - IPv4 `172.19.0.1/30`；
   - IPv6 `fdfe:dcba:9876::1/126`（有效时）；
   - MTU 9000；
   - GSO 关闭；
5. Go runtime 返回供 `VpnService.Builder` 使用的地址、路由、DNS 和包范围；
6. Android 建立或复用 VPN TUN；
7. Java 复制 TUN FD 并将副本交给 `MihomoNative.start`；
8. Go runtime 安装 socket protect 与进程所有者 hook，应用配置并在提供的 FD 上启动 system sing-tun；
9. loopback controller 就绪且 `/configs` 确认 TUN 有效后，状态才发布为 `RUNNING`。

启动失败时，TUN 副本由 sing-tun 或 Java 清理；服务保留原始 `ParcelFileDescriptor`，当 Builder 合约
未变化时可进行 core-only restart。

## System-only TUN contract

TUN 栈不再属于运行时覆写，也不采用 YAML 中的 `stack`。旧版本保存的 `tun_stack` 与
`tun_stack_mode` 会被清除，运行时始终使用 system。

固定 `/30` 与 `/126` 前缀保证 system 栈拥有第二个接口地址用于 local-listener NAT 回注。Android
接口保留 `.1` / `::1` 主机位；只有路由通过 `IpPrefix` 归一化。gVisor 和 mixed 配置在该内核中不可用。

## Socket protection

整个应用 UID 保持在 VPN 路由内。每个真实 mihomo 上游 socket：

1. `dialer.DefaultSocketHook` 在 connect 前获得 raw FD；
2. Go 调用已注册的 C function pointer；
3. C++ 在需要时把 Go thread attach 到 JVM；
4. `NativePlatformCallbacks.protectSocket` 调用 `AndroidVpnService.protect(fd)`；
5. protect 被拒绝时直接让拨号失败。

system 栈内部 TCP listener 不经过真实出站 dialer hook，因此仍处于 TUN 数据路径中。

## Application routing

- 没有包过滤时，AndroidCyaml 自身也留在 VPN 中；
- 使用 `include-package` 时自动加入 AndroidCyaml；
- 使用 `exclude-package` 时忽略对 AndroidCyaml 的排除；
- 未安装的用户包会记录并跳过；include 列表无有效目标时明确失败。

真实上游 socket 是唯一通过逐个 `protect()` 排除的 socket。

## Runtime overrides

运行时覆写仅包括：

- process matching；
- IPv6 用户意愿；
- log level；
- adaptive `tcp-concurrent`；
- LAN WebUI visibility。

覆写事务不会改写用户 YAML。TUN 栈已从设置模型、持久化和界面中移除。

## Process matching

启用时强制 `find-process-mode: always`。补丁将协议和原始源/目标 endpoint 通过 JNI 交给
`ConnectivityManager.getConnectionOwnerUid()`，再映射为稳定包名；关闭时强制 `off`。

## Adaptive IPv6 transaction

`Ipv6EnvironmentMonitor` 追踪最佳非 VPN Internet network。只有底层网络已验证、具备全局 IPv6 地址
和 IPv6 默认路由时才启用固定 `/126`。

- 用户关闭 IPv6：运行 IPv4-only；
- 用户开启但环境不可用：保留意愿，实际运行 IPv4-only；
- 底层网络短暂消失：保留当前协议族合约并关闭旧连接；
- 新网络改变 IPv6 可用性：重建相应 TUN 合约；
- IPv6 启动失败：停止失败实例并执行一次 IPv4-only 重试。

## Underlying-network handover

网络切换若不改变有效协议族，`RuntimeCoordinator` 不替换 TUN，而是刷新接口/DNS 缓存、重置持久
resolver transport 并关闭现有 mihomo 连接。新建且已 protect 的 socket 随 Android 新物理默认网络
出去，从而避免 Wi-Fi/移动网络切换导致不必要的 VPN 重建。

## Config transaction

候选配置由同一嵌入式核心先解析，再原子替换应用私有 `config.yaml`。运行中的新配置若无法启动，
旧文件与旧运行状态会恢复。Android 平台字段只在内存中变换，不写回用户文件。

APK 离线包含 Zashboard 和 `GeoIP.dat`；GeoSite 已停止打包。使用 GeoSite 的配置需要自行提供数据，
或改用 rule-provider/MRS。

## Removed architecture

以下组件或能力已明确移除：

- mihomo 子进程；
- 抽象 Unix platform socket；
- JSON framing 与 `SCM_RIGHTS` TUN 传递；
- whole-package VPN exclusion；
- stale subprocess reaper；
- HEV/tun2socks/SOCKS packet conversion；
- gVisor 与 mixed TUN 栈；
- 运行时 TUN 栈覆写；
- Tailscale 与 ZeroTier 出站模块；
- APK 内置 GeoSite。

TUN、DNS 映射、嗅探、进程归属和出站保护保留在同一 VPN 服务进程内，避免 TUN-to-SOCKS 桥接的
元数据损失，并保留 system 栈 TCP 回注路径。
