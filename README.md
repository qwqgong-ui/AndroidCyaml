# AndroidCyaml

AndroidCyaml 是一个最低支持 Android 16（API 36）、面向 Android 17（API 37）构建、仅提供
`arm64-v8a` 的 mihomo VPN 外壳。mihomo 以 Go `c-shared` 库随 APK 打包，通过 JNI 直接运行在
`AndroidVpnService` 所在进程中，不使用 mihomo 子进程、抽象 Unix Socket 或 tun2socks 桥接。

```text
Android 应用流量
  → Android VpnService TUN
  → libandroidcyaml.so（JNI 包装）
  → libmihomo.so（AndroidCyaml Go 包装模块 + 固定 mihomo 源码）
  → mihomo sing-tun system 栈
  → DNS、嗅探、规则、代理组和节点
  → 每个真实出站 socket 调用 VpnService.protect(fd)
  → 底层网络
```

AndroidCyaml 当前只编译和使用 **system TUN 栈**。gVisor 与 mixed 不在 APK 内核中，运行时覆写
面板也不再提供 TUN 栈切换。构建 mihomo 时同时启用 `no_tailscale` 和 `no_zerotier`，以移除未使用
的 Tailscale、ZeroTier 与 gVisor 依赖。

## 核心隔离与构建边界

AndroidCyaml 固定使用 `scripts/build_mihomo.sh` 中的 mihomo `dev` 提交。构建流程：

1. 在 `.third_party/mihomo-src` 检出指定提交；
2. 验证 dev 内核及其依赖补丁链；
3. 使用 `native/mihomo` 包装模块，通过 mihomo 的 `androidcyaml` 专用 facade 注册平台回调；
4. 以 Android arm64、CGO、裁剪 build tags 和
   `-buildmode=c-shared` 生成 `libmihomo.so`；
5. CMake 构建 JNI 包装库 `libandroidcyaml.so`。

AndroidCyaml 不再对检出的 mihomo 源码应用二次补丁。dev 只保留中性的进程解析和 XHTTP transport
扩展点，以及薄的 `androidcyaml` facade；JNI 导出、固定 TUN 合约、IPv6 处理、逐 socket
`protect()`、WebView 实现和运行时配置变换仍由 AndroidCyaml 仓库维护。

AndroidCyaml 固定使用 `qwqgong-ui/mihomo:dev` 的已测试提交，而不是未经修改的
`MetaCubeX/mihomo:Alpha`。Mihomo 下游修改已直接展开在 `dev` 源码中，包括构建裁剪、
UDP 域名转发等定制；AndroidCyaml 构建时不再重复应用源码补丁。JNI 集成由
`native/mihomo` 包装模块完成，不会修改 mihomo checkout。

### UDP 域名原样转发

当 TUN、fake-ip 或嗅探恢复出 UDP 目标域名时，下游内核会对支持域名寻址或远端解析的代理保留
`metadata.Host`，并将域名与端口原样封装给远端，而不是在客户端统一先解析为 IP。该行为覆盖支持
远端 DNS 的 UDP、UOT/XUDP 等出站；不支持域名目标的出站仍会按原有逻辑在本地解析。

这意味着例如 QUIC 的 `www.gstatic.com:443` 可以作为 FQDN 交给代理服务器，由服务器侧 DNS 决定
最终地址，避免客户端 DNS 位置与代理出口位置不同导致命中错误的 CDN。此功能来自
`qwqgong-ui/mihomo` 的下游补丁，不属于当前上游 `MetaCubeX/mihomo` 的默认行为。

## 运行时结构

- `AndroidVpnService`：VPN 授权、前台服务、通知、`VpnService.Builder` 和 TUN 文件描述符。
- `MihomoNative`：JNI Java 入口，负责校验、准备 TUN、启动、停止和内存回收。
- `libandroidcyaml.so`：C++ JNI 包装层，连接 Java 回调与 Go 导出函数。
- `native/mihomo`：AndroidCyaml 自有 Go 包装模块，导出 C ABI 并调用 mihomo 包。
- `libmihomo.so`：Go `c-shared` 核心库，只包含 system TUN 栈。
- `NativePlatformCallbacks`：执行逐 socket `VpnService.protect(fd)`，并提供 UID/包名查询。
- `AndroidTunManager`：应用固定接口地址、路由、DNS 和应用范围。
- `RuntimeCoordinator`：串行化启动、停止、配置事务、IPv6 环境变化和底层网络切换。
- `MainActivity`：运行在独立 `:ui` 进程，通过同 UID、非导出的 Binder 服务控制 VPN 进程。
- `PredictiveBackAnimator`：面板返回手势期间跟随手指缩放、位移并圆角化面板，提供预测性返回动画。
- `VpnTileService`：快捷设置磁贴，绑定同一个控制服务显示运行时状态并切换 VPN。
- `QuickActionActivity`：桌面快捷方式与磁贴的无界面入口，只负责发起一次启停并结束。
- `VpnQuickActions`：磁贴、快捷方式和主界面共用的启停路径。

面板 WebView 存在页内历史时，`MainActivity` 才向 `OnBackInvokedDispatcher` 注册
`OnBackAnimationCallback` 并接管返回；没有历史时不注册回调，退出应用交由系统自身的预测性返回动画处理。

整个 AndroidCyaml UID 保持在 VPN 数据路径内。使用 `tun.include-package` 时，外壳会自动把自身加入
允许列表；使用 `tun.exclude-package` 时会忽略对自身包的排除。只有 mihomo 真正建立的上游 socket
通过 `protect(fd)` 离开 VPN，system 栈内部 TCP listener 与 NAT 回注仍留在 TUN 内。

## 快捷入口

快捷设置磁贴与桌面长按快捷方式都不在前台，无法承载系统 VPN 授权对话框，因此二者共用
`VpnQuickActions`：

- 已授权时直接 `startForegroundService`；磁贴不声明前台启动，避免服务误请求 location 前台类型。
- 需要授权或后台启动被拒时，改为拉起 `QuickActionActivity`，由它以 Activity 身份完成授权再启动。
- 系统「始终开启 VPN」生效时，磁贴跳转到 VPN 系统设置，快捷方式给出提示，均不假装能关闭。

磁贴通过 `AppControlService` 订阅运行时快照显示状态，不依赖自己的点击记录；「更多操作 → 添加快捷设置磁贴」
调用 `StatusBarManager.requestAddTileService` 一键添加。

## 固定 TUN 合约

用户 YAML 中的 TUN 栈、接口地址、MTU 和 GSO 设置不会直接用于 Android VPN 接口。运行时固定为：

- 栈：`system`
- 设备名：`AndroidCyaml`
- IPv4：`172.19.0.1/30`
- IPv6：`fdfe:dcba:9876::1/126`（环境有效且用户启用时）
- MTU：`9000`
- GSO：关闭

`/30` 与 `/126` 为 system 栈提供 `.2` / `::2` 回注地址，避免 `/32`、`/128` 无下一地址的问题。
接口地址保留主机位；只有添加路由时才归一为网段。

## 逐 socket protect

AndroidCyaml 为 mihomo 的真实拨号安装 `dialer.DefaultSocketHook`：

```text
Go RawConn FD → NativePlatformCallbacks.protectSocket(fd)
              → AndroidVpnService.protect(fd)
              → underlying Network.bindSocket(fd)
```

保护失败会终止该次拨号，而不是允许出站重新进入 VPN 形成路由循环。system 栈内部 listener 不属于
真实代理出站，不会被该 hook 排除。

`Ipv6EnvironmentMonitor` 同时从当前最佳非 VPN `LinkProperties` 取得 DNS，通过 JNI
更新 mihomo 非 CMFA Android 路径的 `UpdateSystemDNS`。因此配置中的 `system://` 始终表示
当前 Wi-Fi/移动网络提供的 DNS，查询 socket 自身经 protect 并绑定该底层 Network。

### 按物理网络隔离的长期 DNS 候选缓存

direct DNS 的长期来源候选按物理网络身份分桶，不只按“Wi-Fi / 移动数据”
粗略分类。Wi-Fi 优先使用 SSID，无法取得时回退到 BSSID；蜂窝网络使用订阅、
SIM 运营商与 carrier 等稳定信息。原始身份只在内存中组合，mihomo 缓存键中使用的
是 SHA-256 指纹，不会持久化 SSID、BSSID 或 SIM 原始身份。

每个 direct nameserver 的候选分开保存，最低保留 24 小时，不会被其他 DNS 来源覆盖。
缓存写入 `cache.db`，每小时及 core 正常关闭时保存，启动时按原始过期时间恢复；
已过期结果仅作为 stale 候选，由乐观缓存机制在后台刷新。TCP 实际连接胜出的地址
可作为当前优选，但不会改写各 DNS 来源的长期原始候选。

切换底层网络时，AndroidCyaml 更新网络指纹和系统 DNS，清理普通易失应答缓存，
重置 resolver 连接并关闭旧连接。按网络指纹隔离的 direct DNS 来源候选会保留，
但不会被新网络误用；重回原网络时可继续使用该网络之前的候选。

## 运行时覆写

覆写面板当前提供：

- 进程匹配；
- IPv6 用户意愿；
- 日志级别：`silent`、`error`、`warning`、`info`、`debug`；
- 自适应 `tcp-concurrent`；
- XHTTP System WebView 传输；
- 向局域网公开 WebUI。

TUN 栈不再可覆写，旧版本保存的 `tun_stack` / `tun_stack_mode` 会被清理，运行时始终使用 system。
覆写只作用于内存中的 mihomo 配置，不会改写导入的 `config.yaml`。

### 进程匹配

- 开启：强制 `find-process-mode: always`；mihomo 按协议和原始四元组调用 Android
  `ConnectivityManager.getConnectionOwnerUid()`，再将 UID 映射为包名。
- 关闭：强制 `find-process-mode: off`。

核心、JNI 和 VPN 服务位于同一进程，查询不经过 JSON 或 Unix Socket 往返。

### XHTTP System WebView

开启 XHTTP WebView 后，TLS、HTTP/2 请求头、连接复用和网络侧浏览器特征由默认 VPN 进程中的隐藏
System WebView 产生，mihomo 继续负责 XHTTP 帧、session 和模式选择。启动时会实际检测
`ReadableStream` 请求体与 Fetch `duplex: "half"`；支持时保留显式 `stream-up`，通过独立 download
GET 与流式 upload 请求运行。上传正文使用异步 pull/回调桥接，等待 Go 流数据时不会阻塞 WebView 的
JavaScript 执行环境。`stream-one` 仍降级为 `packet-up`，显式 `stream-up` 在能力检测失败时也
自动降级，避免把完整隧道正文缓存在内存中。

当前 WebView 路径仍不支持 `download-settings`、cookie placement、自定义 `Cookie`、Reality 或强制
HTTP/3。关闭该开关时，上述限制不影响 mihomo 原生 XHTTP 传输。

### IPv6

IPv6 开关表示用户意愿。实际启用还要求当前最佳非 VPN 网络同时具备：

- Android 已验证的互联网能力；
- 全局 IPv6 地址；
- IPv6 默认路由。

环境不满足时保留用户开关，但运行 IPv4-only。Wi-Fi 与移动网络切换且协议族不变时，应用复用现有
TUN，只关闭旧连接并清理接口、普通易失 DNS 缓存和持久解析连接；按网络指纹隔离的
direct DNS 长期来源候选会保留。若 IPv6 模式启动失败，会停止失败实例并
以 IPv4-only 重试一次。

### 按网络记忆策略组

AndroidCyaml 会按物理网络身份只记忆 config.yaml 中第一个 `Selector` 策略组的最后可用选择，
不使用 DHCP 地址、蜂窝出口 IP 或 IPv6 前缀作为身份。Wi-Fi 优先使用 SSID，
只在 SSID 不可用时才使用 BSSID，避免 Mesh/多 AP 漫游被误认为新网络；蜂窝网络使用
订阅与稳定运营商信息，不因 4G/5G 制式变化更换身份。持久化前只保存网络身份的 SHA-256
指纹。

记忆是事件驱动的：第一次识别某个网络时保存初始选择，离开该网络、停止 VPN、
重启核心或回应后台内存回收协议前同步保存当前选择；稳定驻留期间不轮询、不重复写盘。
进入已记忆的网络后只恢复这个主 Selector，QUIC、UDP、拒绝等后续策略组保持 mihomo 自身状态。
目标已从组中移除或明确失活时，优先选择组内可用的 URLTest/Fallback/LoadBalance/Smart
自动组；若无自动组或控制器请求超时，保留 mihomo 当前选择。

右上角“按网络选择节点”会列出当前可识别以及最近已记忆的 Wi-Fi/移动数据网络。每个网络只设置
第一个 Selector；当前网络立即切换，其他网络在下次进入时恢复。界面同时展示
Selector 的直接目标和递归解析后的实际出口节点，例如 `🌐：🇯🇵⚡ → x03-jp`，避免把自动策略组名
误报为节点名。未曾连接的 Wi-Fi 不会由应用扫描或预建档案。

Wi-Fi SSID/BSSID 受 Android 位置权限保护。从前台界面正常启动 VPN 且已授予位置权限时，
VPN 前台服务会保持识别 Wi-Fi 所需的 location 服务类型，以便退到后台后仍能恢复该网络的节点。
用户拒绝位置权限，或 always-on/后台冷启动不允许 location 服务权限时，VPN 仍以
system-exempted 类型正常运行，但不会对无法稳定识别的 Wi-Fi 保存或恢复选择；蜂窝网络
识别不再要求电话状态权限。

### 日志与局域网 WebUI

默认日志级别为 `warning`。WebUI 默认监听 `127.0.0.1`；开启局域网公开后改为 `0.0.0.0`，优先使用
端口 `17890`，冲突时选择可用临时端口。其他设备可访问：

```text
http://<手机局域网 IP>:<端口>/ui/
```

Android 17 会在启用公开访问时请求 `ACCESS_LOCAL_NETWORK`。公开模式要求用户配置中的 `secret`
非空；本机模式保留 mihomo 对空 `secret` 的原始行为。

## 配置边界

上传的 `config.yaml` 按原字节保存。运行时在内存中接管以下平台字段：

- 强制启用 TUN，并设置设备名 `AndroidCyaml`；
- 强制使用 system 栈；
- 应用覆写面板选择的日志级别和自适应 `tcp-concurrent`；
- WebUI 在 `127.0.0.1` 与 `0.0.0.0` 之间切换，并保留 YAML 中的 `secret`；
- 使用固定 `/30`、`/126`、MTU 9000，并关闭 GSO；
- 根据 IPv6 有效状态移除 IPv6 地址和路由；
- 将路由、排除路由、DNS 和包范围交给 `VpnService.Builder`；
- 把 Android TUN FD 交给 sing-tun，并关闭核心侧重复的系统路由操作。

节点、代理组、规则、DNS、fake-ip、sniffer、DNS 劫持、NAT 和代理选择仍由 mihomo 处理。
动态 `route-address-set` 无法直接转换为 Android `VpnService.Builder` 路由，会明确报错。

域名展示依赖 fake-ip DNS 映射或 sniffer。关闭映射与嗅探、使用应用自有加密 DNS，或目标本身只有
IP 时，连接面板显示 IP 属于正常结果。

## 配置导入事务

1. 通过 Android Storage Access Framework 读取候选文件，最大 32 MiB；
2. 使用同一份嵌入式 mihomo 解析候选配置；
3. 校验成功后原子替换应用私有 `config.yaml`，权限为 `0600`；
4. VPN 运行时停止旧核心并重建必要的运行时状态；
5. 新配置无法启动时恢复上一份配置和运行状态。

首次安装使用内置 DIRECT 默认配置。APK 离线包含无字体构建的 Zashboard，但不再打包或安装
`GeoIP.dat` 与 `GeoSite.dat`。使用 GeoIP/GeoSite 规则的配置需要自行提供对应数据，允许 mihomo
按导入配置获取数据，或改用 rule-provider/MRS。

## 系统 VPN

- 支持系统“始终开启 VPN”和锁定模式；
- 普通模式可从通知停止 VPN；
- 系统托管时，应用内停止入口会提示前往系统 VPN 设置；
- UI 或 WebView 被回收不会停止默认进程中的 VPN 与 mihomo。

## Android 17 内存限制

VPN、TUN 和 mihomo 保留在默认前台服务进程。可见的 Dashboard WebView 位于可回收的 `:ui` 进程；
UI 进入后台后解除绑定、销毁该 WebView 并结束独立 UI 进程，再次打开时冷启动 Dashboard，不影响
VPN。开启 XHTTP WebView 时，默认 VPN 进程还会持有按精确端点隔离的隐藏传输 WebView，空闲时最多
保留四个；它们跟随 `AndroidVpnService` / mihomo 生命周期，并使用进程级 `ProxyController`
override，不会影响 `:ui` 进程中的 Dashboard WebView。关闭 XHTTP WebView 时不会创建这套隐藏
WebView。

应用会响应 Android/厂商内存压力回调，释放可重建缓存并记录相关退出信息。验收应以设备的
`am memory-limiter status`、进程 PSS/RSS、退出记录和 VPN 连通性为准，而不是只观察 Java 堆。

常用检查命令：

```bash
adb shell am memory-limiter status
adb shell dumpsys meminfo io.github.qwqgong.androidcyaml
adb shell pidof io.github.qwqgong.androidcyaml
adb shell pidof io.github.qwqgong.androidcyaml:ui
```

## 构建

需要：

- JDK 17
- Android SDK Platform 37 与 Build Tools 37.0.0
- Android NDK `29.0.14206865` 与 CMake 3.22.1
- Go 1.26 或更高版本
- Git、bash、unzip、readelf、sha256sum

原生 ABI 基线为 **ARMv8.2**：`minSdk = 36` 意味着所有目标设备都不低于 ARMv8.2，因此 Go 核心以
`GOARM64=v8.2` 交叉编译，用 ARMv8.1 LSE 原子指令（`CAS`、`LDADD`）替代 ARMv8.0 的
`LDXR`/`STXR` 独占重试循环。这对 mihomo 这种高并发 Go 负载在骁龙 8 Elite 等多核大芯片上意义最大。
`scripts/build_mihomo.sh` 在构建后读取 `go version -m` 记录的 build setting 断言该基线确实生效。

原生库以未压缩方式打包（`useLegacyPackaging = false`），由 linker 直接从 APK 内 mmap，安装后不再
额外解压一份到 `/data/app/.../lib/`。代价是 APK 下载体积等于原生库的未压缩大小。

```properties
# local.properties
sdk.dir=/absolute/path/to/Android/Sdk
```

```bash
./scripts/fetch_zashboard.sh
./gradlew :app:assembleDebug :app:lintDebug
bash scripts/verify_native_runtime.sh app/build/outputs/apk/debug/app-debug.apk
```

### Release 与 ART 优化

Release 启用 R8 代码压缩、优化、混淆和资源压缩。静态 baseline/startup profile 用于 ART AOT 与
DEX 布局优化；`androidx.profileinstaller` 负责侧载安装后的 Profile 安装兼容。

```bash
./gradlew :app:assembleOptimized :app:bundleOptimized :app:lintOptimized
bash scripts/verify_art_optimization.sh \
  app/build/outputs/apk/optimized/app-optimized.apk \
  app/build/outputs/mapping/optimized/mapping.txt
```

验证脚本检查两个原生库的架构、符号、SONAME/依赖关系、循环引用、至少 16 KiB 的 LOAD 对齐，
以及两个库在 APK 内以 `Stored` 未压缩方式打包。
正式发行在 GitHub Actions 中先构建未签名 Release APK，再从四个
`ANDROID_RELEASE_*` secrets 恢复密钥并独立执行 zipalign、签名和验证；Gradle 配置缓存因此
不会序列化签名密码。

## 上游依赖

- mihomo 来源：构建时实时解析 `qwqgong-ui/mihomo:dev` 的最新提交，不固定 SHA
- mihomo 实际构建提交：记录在 `.third_party/mihomo.commit` 和原生版本字符串中
- AndroidCyaml 核心 facade：`qwqgong-ui/mihomo/androidcyaml`
- AndroidCyaml Go 包装模块：`native/mihomo`
- Zashboard：由 `scripts/fetch_zashboard.sh` 获取固定 release 资产

许可证与第三方版本说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
