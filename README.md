# AndroidCyaml

AndroidCyaml 是一个面向 Android 16、并提前适配 Android 17 内存限制的 mihomo VPN 客户端。它启动
[`qwqgong-ui/mihomo`](https://github.com/qwqgong-ui/mihomo) 的 arm64 核心，在应用内用
WebView 打开内置的 [`Zephyruso/zashboard`](https://github.com/Zephyruso/zashboard)，并允许
从 Android 文件选择器上传 `config.yaml`。

系统 VPN 不再把 TUN 文件描述符直接交给 mihomo 的 gVisor 栈，而是使用原生
`VpnService` 建立固定双栈 TUN，再由
[`heiher/hev-socks5-tunnel`](https://github.com/heiher/hev-socks5-tunnel) 将 IP 包转发到
mihomo 的应用内部 SOCKS5 入口：

```text
其他应用 → Android VpnService TUN → hev-socks5-tunnel
        → 仅回环的内部 SOCKS5 → mihomo 规则/节点 → 底层网络
```

整个路径无需 root。

## 平台限制

- Android 16 及以上：`minSdk = targetSdk = compileSdk = 36`；Android 17（API 37）行为已提前适配，
  待正式 SDK 可用后再单独提升编译与目标版本
- 仅 ARMv8 64 位：APK 只包含 `arm64-v8a`
- 不兼容 Android 15 及以下、`armeabi-v7a`、`x86` 或 `x86_64`

## 功能

- mihomo 固定到提交 `a563ca2194edbf560b3857801cb3cceab13d7ff9`
- hev-socks5-tunnel 固定到提交 `df11261f09ebafc37bac03f81029c9b75a4aa074`，连同其 gitlink
  固定的子模块从源码构建
- zashboard 固定到 `v3.15.0` 的无字体构建，静态文件随 APK 离线分发
- 完整 GeoIP/GeoSite 固定到 `MetaCubeX/meta-rules-dat` 提交 `ab44fa37` 并随 APK 分发，
  配置首次使用 GEO 规则时无需从 GitHub 下载
- WebView 优先通过固定的 `127.0.0.1:17890` 访问面板，使 Zashboard 设置可跨重启保留；
  端口冲突时自动回退到空闲端口
- MainActivity 与 WebView 固定运行在独立 `:ui` 进程；界面进入后台时保存轻量导航状态并主动
  销毁 WebView，普通退后台后结束 UI 任务与独立进程，回到前台再冷重建；UI 暂停、回收或 WebView 异常不会直接结束原应用进程中的
  VPN Service 与 HEV，跨进程控制使用仅限同 UID 的非导出 Binder 服务
- 每次进程启动从系统历史退出记录中缓存本应用 native crash tombstone；缓存位于不参与备份的
  应用私有目录，单份最多 4 MiB、最多保留 8 份且总量不超过 16 MiB
- 接入金标联盟“公平运行内存适配”的 `TRIM`/`KILL` 通知，在独立工作线程释放可回收缓存并通过
  Binder 单向事务回执；通知处理不停止 VPN、不重启 mihomo，也不阻塞 UI 线程
- 识别 Android 17 `MemoryLimiter:AnonSwap` 退出原因并缓存结构化诊断元数据，便于区分系统内存
  红线终止与普通崩溃
- 原生 VPN 滑块申请系统授权并接管其他应用的全局 IPv4/IPv6 流量
- 上传配置、重启核心、运行时覆写和“隐藏后台标签页”集中在右上角二级菜单
- Android 16 `systemExempted` 前台服务与常驻通知；普通模式可从通知直接停止 VPN
- 支持系统“始终开启 VPN”与“屏蔽未使用 VPN 的所有连接”；系统托管连接时应用内停止入口
  会被禁用，避免与系统自动重连冲突
- 二级菜单可开启“打开应用时自动开启 VPN”；每次打开只尝试一次，未授权时进入系统授权流程，
  系统始终开启模式下不重复发起启动
- AndroidCyaml 自身从 VPN 中排除，使 HEV、mihomo 上游连接和回环 SOCKS 通道走底层网络，
  避免路由循环
- 监听已验证的底层 Wi-Fi 链路；连续 5 秒没有全局 IPv6 地址或 IPv6 默认路由时，通过控制器
  将 mihomo 运行时 `ipv6` 切为 `false`，IPv6 恢复或离开 Wi-Fi 后还原用户配置值
- 每次安装随机生成 256 位控制器密钥；控制器与内部 SOCKS5 均只监听回环地址
- 内部 SOCKS5 端口由控制器密钥派生，并使用该密钥进行用户名/密码认证，不占用或暴露用户
  配置中的 `socks-port`、`mixed-port` 与认证设置
- 上传 `config.yaml` 后按原字节复制为只读文件，再由正式核心解析并启动；应用不会改写
  文件选择器中的原文件或把运行参数写回 YAML
- 新配置不能启动时自动恢复上一份配置
- `external-controller`、`external-ui` 和 `secret` 由启动参数覆盖

## 使用

1. 在 Android 16 或以上的 arm64 设备上安装 APK。
2. 打开 AndroidCyaml，等待状态栏显示 mihomo 已运行。
3. 打开右上角二级菜单，点击“上传 config.yaml”，从系统文件选择器选择 YAML 配置。
4. 导入成功后核心会自动重启，zashboard 随即重新连接。
5. 打开 VPN 滑块，在 Android 系统对话框中允许建立 VPN。
6. 界面显示“VPN 已连接 · HEV SOCKS · IPv4/IPv6”后，其他应用的流量会通过 mihomo。
7. 如需系统托管，打开右上角菜单中的“VPN 系统设置”，进入 AndroidCyaml 的 VPN 选项，
   依次开启“始终开启 VPN”和“屏蔽未使用 VPN 的所有连接”。不同系统的选项名称可能略有差异。
8. 不使用系统始终开启时，可在二级菜单勾选“打开应用时自动开启 VPN”。

首次启动没有用户配置时，应用使用仅含 `DIRECT` 规则的默认配置。导入文件大小上限为
32 MiB。内置 GeoIP/GeoSite 只会在文件缺失时复制，不会覆盖已有数据库。未启动 VPN 时，
mihomo 仍以用户配置中的本地 HTTP/SOCKS 入站模式运行。

## Android VPN 与配置边界

Android VPN 网络参数完全由 `VpnService.Builder` 和 HEV 运行时配置持有，不从上传的 YAML
读取，也不会写回 YAML：

| 项目 | 固定值 |
| --- | --- |
| MTU | `9000` |
| IPv4 接口 | `198.18.0.1/30` |
| IPv4 默认路由 | `0.0.0.0/0` |
| IPv6 接口 | `fdfe:dcba:9876::1/126` |
| IPv6 默认路由 | `::/0` |
| Android DNS（IPv4） | `8.8.8.8` |
| Android DNS（IPv6） | `2001:4860:4860::8888` |

HEV 的运行时 YAML 声明固定 IPv4/IPv6 地址，并使用 `icmp: reply` 接管 ICMP Echo。
Android 传入现成的单个 `VpnService` TUN 文件描述符，因此接口地址和路由仍以
`VpnService.Builder` 创建的接口为准。该模式只有一个现成 TUN 文件描述符，运行时配置
不再启用无效的 `multi-queue`。

上传的配置不需要 `tun.enable: true`。AndroidCyaml 启动 mihomo 时关闭 mihomo 自身的 TUN
监听，由 Android `VpnService` 和 HEV 单独负责接管系统流量。用户配置中的节点、代理组、规则、
规则集、嗅探、DNS 策略及普通入站仍由 mihomo 解析；其中 `tun.mtu`、`tun.*-address`、
`tun.dns-hijack`、`tun.auto-route` 等字段不会成为 Android VPN 接口参数。

远程映射 DNS 已关闭。Android 直接把 IPv4/IPv6 DNS 地址加入 `VpnService.Builder`，
DNS 数据包经 HEV 和 mihomo 转发到解析器并原样返回 A、AAAA 等真实记录。HEV 运行时配置
不再包含只支持 A 记录的 `mapdns` 段，因此应用能够获取真实 AAAA 记录；相应地，DNS 目标
在 mihomo 中表现为解析器 IP，而不是原始查询域名。

Wi-Fi IPv6 检测只观察同时具备 `INTERNET`、`VALIDATED` 和 `NOT_VPN` 能力的底层 Wi-Fi，
不会把 AndroidCyaml 自己创建的 VPN 网络误判为 Wi-Fi。链路必须同时具有 `2000::/3` 全局
单播地址和 IPv6 默认路由才视为 IPv6 可用；缺失状态持续 5 秒后，应用通过本地控制器把 mihomo
运行时 `ipv6` 设为 `false`。这个覆写不会修改只读的 `config.yaml`，也不会改变 Android VPN
固定的双栈 TUN；IPv6 恢复或离开 Wi-Fi 时会还原核心启动后读取到的用户配置值。

每应用路由由 `VpnService.Builder` 负责，当前固定为“除 AndroidCyaml 自身外的所有应用”。
配置中的 `tun.include-package` 与 `tun.exclude-package` 不控制 Android 应用接管范围。开启系统
“始终开启 VPN”后，Android 会在开机、应用升级或服务退出后重新启动 VPN；同时开启锁定模式后，
系统会阻断其他应用未经过 VPN 的流量。Android 始终豁免当前 VPN 提供者自身 UID，因此
AndroidCyaml 内的 mihomo、HEV 上游连接仍可使用底层网络，不会形成路由循环。

### 进程规则限制

HEV 的 SOCKS5 转发不会携带原始 Android UID/包名。进入 mihomo 的连接表现为内部
`ANDROID-SOCKS` 入站，因此 `PROCESS-NAME`、`PROCESS-PATH` 等规则不能可靠区分最初发起
流量的应用。域名、IP、端口、网络类型和其他 mihomo 规则仍正常工作。

## 构建

需要：

- JDK 17
- Android SDK Platform 36 与 Build Tools 36.0.0
- Android NDK `29.0.14206865`
- Go 1.26.5 或更高版本
- Git、curl、unzip、sha256sum

设置 SDK 路径：

```properties
# local.properties
sdk.dir=/absolute/path/to/Android/Sdk
```

然后运行：

```bash
./scripts/fetch_zashboard.sh
./scripts/fetch_geodata.sh
./gradlew :app:assembleDebug :app:lintDebug
```

正式发行包必须使用项目长期签名密钥。通过以下环境变量提供签名材料后运行
`./gradlew :app:assembleRelease :app:lintRelease`：

- `ANDROID_SIGNING_STORE_FILE`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

缺少任一变量时 `packageRelease` 会直接失败，避免误把临时调试签名当作发行包。GitHub 的
`Signed Android Release` 工作流从仓库 Secrets 恢复同一密钥，并输出可持续覆盖升级的签名 APK。

Gradle 的 `preBuild` 会自动完成两项固定源码构建：

1. `scripts/build_mihomo.sh` 从固定提交取回 mihomo、应用
   [`patches/mihomo-android-vpn.patch`](patches/mihomo-android-vpn.patch)，并生成
   `app/src/main/jniLibs/arm64-v8a/libmihomo.so`。
2. `scripts/build_hev_socks5_tunnel.sh` 从固定提交取回 HEV 及其递归子模块，用固定 NDK 构建
   `app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so`。

这些上游检出位于被忽略的 `.third_party/`，生成的 native 文件不会提交到 Git。调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

上游 mihomo 固定提交提前声明了尚未发布的 Go 1.27。使用 Go 1.26 时，构建脚本只在生成的
临时检出中把 `go` 指令调整为 1.26。Go 1.27 可用后脚本会直接使用原声明。

## 实现说明

Android 10 起不允许从应用的可写数据目录执行文件。因此 mihomo 可执行文件以
`libmihomo.so` 的名字进入 APK 的 `lib/arm64-v8a`，并通过旧式 native library packaging
让系统安装器把它提取到只读、可执行的 `nativeLibraryDir`；应用直接从该目录启动核心。

hev-socks5-tunnel 作为普通 JNI 共享库进入同一 ABI 目录。`VpnService` 建立非阻塞 TUN 后将
其文件描述符直接传给 HEV JNI；停止 VPN 时先停止 HEV 工作线程，再关闭 TUN 文件描述符。

MainActivity/WebView 运行在独立的 `:ui` 进程，VPN Service、HEV JNI 与核心监管器仍留在默认
应用进程，mihomo 仍由默认进程作为独立子进程启动。UI 在停止状态会解除 Binder 绑定，因此
系统可以单独回收 UI；同时应用会主动销毁不可见的 WebView，并在普通退后台后先结束 UI 任务、
再结束独立 UI 进程，避免部分 OEM 为维护最近任务快照而反复重建被终止的 Activity，并避免
避免 Chromium 映射仍被计入高优先级进程总 PSS。文件选择器和首次 VPN 授权等等待 Activity
结果的系统流程会暂时保留 UI。重新进入前台后会重建 WebView、恢复导航状态并取得完整状态快照，
不会启动第二份核心。

系统为本包保留的 `REASON_CRASH_NATIVE` 历史退出 trace 会在下次任一进程启动时复制到
`noBackupFilesDir/tombstones`。多个进程通过文件锁去重，trace 与元数据使用临时文件同步后
原子替换。Android 17 因应用内存限制退出时，`REASON_OTHER` 且描述包含
`MemoryLimiter:AnonSwap` 的记录会在同一目录保存为 `.memory-limit.json`（最多 16 份）；普通
Java 异常、ANR 和其他应用的退出记录不会进入该缓存。

### Android 17 与公平运行内存

依据 [Android 17 行为变更](https://developer.android.com/about/versions/17/behavior-changes-all)
和 OPPO 开放平台的[公平运行内存适配](https://open.oppomobile.com/documentation/page/info?id=13825)，
Android 17 的应用内存限制由系统按设备 RAM 动态决定，本项目不硬编码单一“红线”。默认进程
动态接收金标联盟规定的 `itgsa.intent.action.TRIM` 与 `itgsa.intent.action.KILL`：

| 通知/场景 | AndroidCyaml 的处理 |
| --- | --- |
| UI 进入后台 | 销毁不可见 WebView；750 ms 回前台宽限期后结束 UI 任务，再退出独立进程；等待系统 Activity 结果时除外 |
| `TRIM` | 清理默认进程的短期诊断日志缓存，低频采集当前 PSS/Java heap 并回执 |
| `KILL` | 再次清理可回收缓存；配置、密钥、运行时覆写与 UI 偏好本来就已落盘，然后回执 |
| Android 系统 `onTrimMemory`/`onLowMemory` | 执行相同的本地缓存释放，不影响 VPN 数据面 |
| Android 17 内存限制退出 | 下次启动缓存 `ApplicationExitInfo` 元数据用于诊断 |

厂商回调只在默认进程注册，避免多进程对同一个 `notifyId` 重复响应；接收、PSS 采样和 Binder
回执都在专用 `HandlerThread` 中执行。PSS 最短采样间隔为 30 秒，避免高频调用
`Debug.getPss()` 本身造成额外开销。回执使用 `IBinder.FIRST_CALL_TRANSACTION` 与
`FLAG_ONEWAY`，正常路径远低于文档要求的 3 秒。

zashboard 在首次启动或版本变化时从 APK assets 原子复制到应用私有目录，再由 mihomo
自己的 HTTP 路由提供。WebView 禁止文件与 content 访问，只允许回环面板留在应用中；外部
HTTP(S) 导航会交给系统浏览器。

## 上游与许可证

AndroidCyaml 采用 GPL-3.0 许可证。mihomo 为 GPL-3.0；hev-socks5-tunnel 及其 HEV 组件为
MIT；lwIP 为 BSD-3-Clause；zashboard 为 MIT。固定版本、构建方式、校验值与许可证副本见
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
