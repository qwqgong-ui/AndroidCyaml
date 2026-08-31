# Android runtime architecture

## Ownership

| Layer | Owns | Does not own |
| --- | --- | --- |
| `AndroidVpnService` | VPN permission, foreground lifetime, notification, Builder and TUN FD | mihomo rules and proxy semantics |
| `RuntimeCoordinator` | public runtime API, single-thread submission, module assembly and state publication | lifecycle, config rollback or network debounce implementation |
| `RuntimeLifecycle` | VPN service, TUN/native resources, core restart and IPv6-to-IPv4 startup fallback | persisted settings or network monitoring |
| `RuntimeConfigTransactions` | config install, runtime override persistence and failure rollback | direct mutation of lifecycle or coordinator state |
| `network/NetworkCoordinator` | typed route/DNS/IPv6/identity transitions, cache updates and delayed selector restore | TUN/native resource ownership |
| `network/SelectorSession` | per-network selector checkpoint, restore, catalog and selection persistence | physical-network monitoring or core lifecycle |
| `RuntimeOverrideStore` | process matching, IPv6, log level, adaptive TCP concurrency and LAN WebUI intent | YAML mutation or effective network state |
| `network/UnderlyingNetworkMonitor` | Android-scored best validated non-VPN network, identity, DNS and IPv6 snapshots | user preference or core lifecycle |
| `AndroidTunManager` | fixed interface addresses, routes, DNS and application scope | socket protection or proxy routing |
| `NativePlatformCallbacks` | per-socket `protect(fd)` and Android UID/package lookup | TUN packet processing |
| `MihomoNative` | Java JNI contract and native response decoding | VPN lifecycle |
| `libandroidcyaml.so` | JNI exports, JavaVM attachment and callback dispatch | mihomo configuration semantics |
| `native/mihomo` | C ABI exports, in-memory Android config adaptation and mihomo package orchestration | Android UI and service policy |
| mihomo `androidcyaml` facade | neutral platform hooks and runtime IPv6 signal | Android JNI implementation |
| UI/Binder | user intent and observation | runtime ownership |

`MainActivity` runs in `:ui`; `AppControlService`、`AndroidVpnService`、两个原生库和 Go runtime 均位于默认
VPN 服务进程。

网络观察、变化分类、策略记忆和持久化物理集中在
`app/src/main/java/io/github/qwqgong/androidcyaml/network/`，并使用独立
`io.github.qwqgong.androidcyaml.network` package；runtime 只消费其稳定状态和事件。

## Core isolation

AndroidCyaml 构建时解析 `qwqgong-ui/mihomo:dev` 的当前提交并记录精确 SHA，不再在临时构建目录中应用
Android 源码补丁。dev 内核提供中性的 endpoint-aware 进程解析、运行时 IPv6 信号与 XHTTP transport 扩展点，并通过
`mihomo/androidcyaml` 薄 facade 供本项目注册平台实现。构建不会把 JNI、VpnService、WebView
或运行时覆写代码写回 mihomo checkout；未注册 facade 回调时，普通 mihomo 行为保持不变。

较大的 Android 行为全部位于 AndroidCyaml：

```text
native/mihomo/main.go
├── JNI-facing C ABI exports
├── 强制 system TUN 栈
├── 固定 /30 和 /126 TUN 合约
├── Android IPv6 availability 信号
├── 物理 route 切换连接重置
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

## UDP domain forwarding

下游 mihomo 为可远端解析的代理出站设置 UDP remote-DNS 能力。当 metadata 中仍有原始域名时，
`ResolveUDP` 不会强制填充 `DstIP`，UOT、XUDP 或原生 UDP 封装会继续携带 FQDN 与端口。只有不支持
域名目标的出站才退回本地解析。

因此 AndroidCyaml 从 fake-ip 映射或 sniffer 获得的 UDP 域名可以一直保留到代理服务器，由服务器侧
DNS 按代理出口位置解析。该语义来自 `qwqgong-ui/mihomo` 的下游补丁，并非上游
`MetaCubeX/mihomo` 当前默认行为；后续同步上游时必须继续重放并验证该补丁。

## Native library relationship

```text
Java MihomoNative
  → libandroidcyaml.so
      → libmihomo.so generated from native/mihomo
          → registered C function pointers
              → NativePlatformCallbacks
```

`libandroidcyaml.so` 只按稳定 SONAME `libmihomo.so` 链接。CI 验证两个库均为 arm64、LOAD 段至少
16 KiB 对齐、在 APK 内未压缩存储、不包含构建机绝对依赖路径，也不存在 Go 核心反向引用 JNI 包装层的
循环依赖。

两个库都以 NDK 原生 API 级别 35 构建。NDK `29.0.14206865` 不提供高于 35 的工具链，因此原生平台
级别低于 `minSdk = 36`；链接更低的平台版本是安全的，只是不能使用 API 36 才引入的 libc 符号。
Go 核心额外固定 `GOARM64=v8.2`，使运行时原子操作走 ARMv8.1 LSE 指令而非 ARMv8.0 独占循环。

## Startup transaction

1. Android 进入前台服务模式；
2. coordinator 读取进程匹配、IPv6、日志、TCP 并发和 WebUI 覆写；
3. `MihomoNative.prepareTun` 在嵌入式 Go runtime 中解析 `config.yaml`；
4. `native/mihomo` 强制 system 栈并应用固定 Android 接口合约：
   - IPv4 `172.19.0.1/30`；
   - IPv6 `fdfe:dcba:9876::1/126`（用户启用时）；
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
2. Go 连接 app 私有目录中的 unix socket endpoint，用 `SCM_RIGHTS` 传递该 FD；
3. `SocketProtectService` 的 worker 线程收下 FD，校验对端 uid 属于本进程；
4. `NativePlatformCallbacks.protectSocket` 调用 `AndroidVpnService.protect(fd)`；
5. socket 不绑定指定 Network，由 Android 系统默认路由选择 Wi-Fi 或移动数据；
6. worker 回写一字节裁决，protect 被拒绝时直接让拨号失败。

`VpnService.protect()` 没有 NDK 等价物，只有 Java API 能让 netd 设置 protect fwmark 位，所以请求
必须到达 JVM；但它不必作为 JNI upcall 从 Go 线程发出。goroutine 阻塞在 cgo 期间独占其 OS thread，
且进入 JNI 的线程会被 attach 到 ART——这正是弱网重拨风暴留下大量 `Thread-N` 的原因。改走 unix
socket 后，等待裁决的 goroutine 停在 Go netpoller 上，拨号热路径不再创建或 attach 任何线程；Java
侧的并发上限由固定 8 个 worker 的线程池决定。endpoint 位于 `no_backup` 私有目录而非 abstract
namespace，因为设备上所有 app 共享同一 network namespace。endpoint 创建失败或运行中失效时，Go 侧
自动回退到原 JNI 回调。

进程归属查询是真正的 Binder 调用，仍走 JNI，并保留 16 并发的 Go 侧入口；限流发生在进入 cgo 之前。
System WebView XHTTP 的阻塞式响应头回调另有独立的 16 并发上限；取消回调不受此上限约束，避免取消
与限流互相等待。

system 栈内部 TCP listener 不经过真实出站 dialer hook，因此仍处于 TUN 数据路径中。

## Application routing

- 没有包过滤时，AndroidCyaml 自身也留在 VPN 中；
- 使用 `include-package` 时自动加入 AndroidCyaml；
- 使用 `exclude-package` 时忽略对 AndroidCyaml 的排除；
- 未安装的用户包会记录并跳过；include 列表无有效目标时明确失败。

真实上游 socket 是唯一通过逐个 `protect()` 排除的 socket。

## Mihomo outbound tree

```text
Android app traffic
└── VpnService TUN fd
    └── mihomo system stack → rules/process owner
        ├── DIRECT / DNS / native proxy outbound
        │   └── dialer socket hook → VpnService.protect(fd)
        │       └── unbound socket → Android system default network scoring
        │           ├── Wi-Fi
        │           └── cellular
        └── optional System WebView XHTTP
            └── Java WebView dialer → best-matching physical Network.bindSocket()
                └── WebView HTTPS request outside the VPN
```

WebView XHTTP 是唯一例外：它需要在 Java 端显式绑定已观察到的物理 network，避免
WebView 自身的 DNS/HTTPS 请求重新进入 VPN 形成递归。这个特例不改变普通 mihomo
DIRECT、DNS 和代理出站的系统默认选网行为。

## Runtime overrides

运行时覆写仅包括：

- process matching；
- IPv6 用户意愿；
- log level；
- adaptive `tcp-concurrent`；
- LAN WebUI visibility。

覆写事务不会改写用户 YAML。TUN 栈已从设置模型、持久化和界面中移除。

## Process matching

`strict` 只在规则遍历需要进程信息时查询；`always` 提前查询；`off` 完全关闭。平台 resolver 通过
JNI 调用 `ConnectivityManager.getConnectionOwnerUid()`，其失败是权威结果，不再回退 Android
SELinux 禁止的 Linux procfs/inet_diag 路径。

## Adaptive IPv6 transaction

`UnderlyingNetworkMonitor` 追踪 Android 自身评分选出的最佳非 VPN Internet network。用户开启 IPv6
时 Android TUN 固定保留 `/126`；底层网络的全局 IPv6 地址和默认路由只控制 mihomo 的运行时 IPv6
resolver/DNS 行为。

- 用户关闭 IPv6：运行 IPv4-only；
- 用户开启但环境不可用：保留双栈 TUN，只暂停 DIRECT IPv6；
- 同一物理网络 IPv6 变化：只更新 IPv6 resolver/DNS，不关闭 IPv4/代理连接；
- 物理 route handle 改变：关闭旧路径连接，新连接使用系统默认出口；
- IPv6 启动失败：停止失败实例并执行一次 IPv4-only 重试。

## Underlying-network handover

`NetworkCoordinator` 把变化分为 route、DNS、IPv6、identity 和 cache scope。DNS 变化只更新
DNS；IPv6 变化只更新 IPv6；identity 变化只处理策略记忆；cache scope 变化只更新
direct cache key；只有最佳物理 Network handle 改变才刷新
接口状态并关闭旧路径连接。`VpnService.Builder.setUnderlyingNetworks(null)` 与 protect-only socket
让 Android 的吞吐、费用、用户偏好和网络评分决定 Wi-Fi/移动数据出口。

同一网络快照中的 `LinkProperties.getDnsServers()` 会在 core 启动前以及 handover 时经
Java → JNI → Go 传给 `dns.UpdateSystemDNS`。Android 不再从 `/etc/resolv.conf` 推断系统 DNS。

`NetworkIdentityResolver` 同时从物理 network capabilities 生成策略选择记忆键：Wi-Fi 优先使用
SSID（缺失时回退 BSSID），cellular 使用 subscription ID 与稳定的 SIM operator/carrier。原始身份仅在
内存中组合，`NetworkSelectionStore` 只持久化 SHA-256 指纹及 Selector 组/目标名。

`RuntimeCoordinator` 仅在首次进入网络和 underlying-network identity 变化时读写记忆，不定时
轮询 controller。handover 只保存旧网络第一个 Selector 的可用 `now`，等待新网络稳定后恢复。
停止 VPN、重启 core 或回应后台内存回收协议前也会同步持久化最新选择；只有写盘
成功才向回收协议报告状态已保存。
已移除/失活目标会回退到组内可用自动类型；无可用自动组或 HTTP 超时时保留当前
mihomo 选择，不因记忆失败中断 VPN。

UI 通过同 UID Binder 向 `RuntimeCoordinator` 查询网络档案和第一个 Selector 的目录。目录保留可恢复的
直接目标，同时沿 Selector/URLTest/Fallback/LoadBalance/Smart 的当前选择递归解析实际叶子节点名。
对当前网络的选择先由 controller `PUT /proxies/{group}` 生效再同步写盘；对非当前网络只更新其档案，
待 underlying identity 切换时沿既有恢复事务应用。

## Config transaction

候选配置由同一嵌入式核心先解析，再原子替换应用私有 `config.yaml`。运行中的新配置若无法启动，
旧文件与旧运行状态会恢复。Android 平台字段只在内存中变换，不写回用户文件。

APK 离线只包含 Zashboard，不打包或安装 `GeoIP.dat` 与 `GeoSite.dat`。使用 GeoIP/GeoSite 的配置
需要自行提供数据，允许 mihomo 按导入配置获取数据，或改用 rule-provider/MRS。

## Diagnostics sampling

二级菜单中的「诊断采样日志」开关持久化在 `UiPreferences`，服务进程启动时恢复——进程刚被
系统杀掉重启的那一刻正是证据最完整的时候。开关与 `AndroidVpnService` 同进程（只有 dashboard
WebView 在 `:ui`），所以切换立即生效，且不依赖运行时是否已启动。

`DiagnosticsSampler` 用一个后台 `HandlerThread` 上的 `postDelayed` 每分钟采一次。该 API 走
uptime 时钟，设备挂起期间不推进，因此采样器不会唤醒 CPU：doze 中自动暂停，设备因其他原因醒来
后继续。整个特性不使用 AlarmManager、JobScheduler 或 wake lock。

单次采样成本是一次 `/proc/self/status` 读取、一次 `/proc/self/task` 遍历，加一次进入 Go 的
`AndroidCyamlRuntimeMetrics`。后者用 `runtime/metrics` 取运行时已维护的快照，不像
`runtime.ReadMemStats` 那样 stop-the-world；它不持有 `runtimeMu`，因为该锁在启动时会跨整个
`hub.ApplyConfig` 持有，定时器绝不能等在它后面。要采的指标名在初始化时与 `metrics.All()` 求交集，
缺失项单独记录，并由 host 端单元测试断言全部可解析。

`DiagnosticsLog` 在 app 私有 `no_backup` 目录下轮转两代 2 MiB 文件。每行 `key=value`，前缀是
wall/boot/awake 三个时钟——`boot - awake` 即休眠时长，使序列中的空档可解释。关闭时 `append`
只做一次 volatile 读，因此低频事件标记（运行时状态、网络切换、内存 trim/kill、历史进程退出）
可以直接埋在原路径上。日志只记聚合计数，不写域名、IP、SNI 或网络指纹。

导出走 `ACTION_CREATE_DOCUMENT`，不需要 FileProvider；写入在后台线程完成。

网络侧信号分两种形状。底层网络的出现、消失、变得不可用是低频转变，各记一行；
`onCapabilitiesChanged` 只在 validated / notSuspended / notMetered / notRoaming / transport
真的翻转时才写，弱网下每分钟数十次的回调因此不会淹没日志。其余一律是 `NetworkDiagnostics`
里的计数器，由每分钟那一行读走。拨号路径同样分层计数：dial hook 调用数、protect 尝试/拒绝/
传输错误、JNI 回退次数、进程归属查询次数与未命中数。

`coreLogCounters` 把 mihomo 自己的 warning/error 分成 timeout、refused、unreachable、reset、
dns、tls、closed、protect、other 若干桶。只记桶计数，不抄原始消息：那些消息里带着失败的目标
地址，而诊断日志是用来导出分享的。mihomo 的每条日志无论配置级别都会发到 observable，且订阅者
阻塞会反压到调用点，因此分类器只在诊断开启时通过 `AndroidCyamlSetDiagnostics` 订阅，pump 只做
一次分类加一次原子累加。

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
