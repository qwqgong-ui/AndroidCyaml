# AndroidCyaml

AndroidCyaml 是一个最低支持 Android 16（API 36）、面向 Android 17（API 37）构建、仅提供
`arm64-v8a` 架构的 mihomo VPN 外壳。
从 0.6.133 起，mihomo 不再以子进程运行，也不再通过抽象 Unix Socket 请求 TUN；Go 核心以
`c-shared` 形式随 APK 打包，通过 JNI 直接运行在 `AndroidVpnService` 所在进程中。

```text
Android 应用流量
  → Android VpnService TUN
  → JNI 包装库 libandroidcyaml.so
  → AndroidCyaml Go 包装模块生成的 libmihomo.so
  → mihomo sing-tun（system / gVisor / mixed）
  → DNS、嗅探、规则、代理组和节点
  → 每个真实出站 socket 调用 VpnService.protect(fd)
  → 底层网络
```

整个应用 UID 不再被排除出 VPN。只有 mihomo 真正建立的上游 socket 会被逐个保护，因此 system 栈
内部的 TCP listener、TCP NAT 回注和 TUN 数据路径仍留在 VPN 内，同时代理出站不会重新进入 TUN。

## 核心隔离与补丁边界

桌面使用的 `qwqgong-ui/mihomo:Alpha` 不承载 AndroidCyaml 的 JNI、VpnService 或运行时覆写代码。
AndroidCyaml 的构建过程固定执行以下步骤：

1. 检出 `app/build.gradle.kts` 中指定的干净 mihomo 提交；
2. 在被 `.gitignore` 排除的 `.third_party/mihomo-src` 中校验并应用
   `patches/mihomo/0001-androidcyaml-platform-hooks.patch`；
3. 确认补丁只修改：
   - `component/process/process.go`
   - `listener/sing_tun/server_android.go`
4. 使用 AndroidCyaml 自己维护的 `native/mihomo` Go 模块生成 `libmihomo.so`。

最小补丁只增加 Android 连接所有者回调入口，并在应用范围已经由 `VpnService.Builder` 处理时跳过
sing-tun 的 Android 包数据库。JNI 导出、固定 TUN 地址、栈选择、IPv6 处理、逐 socket `protect()`
和运行时配置变换全部位于 AndroidCyaml 仓库中。构建脚本会拒绝触碰约定之外的 mihomo 文件。

## 运行时结构

- `AndroidVpnService`：拥有 VPN 授权、前台服务、通知、`VpnService.Builder` 和 TUN 文件描述符。
- `MihomoNative`：JNI 的 Java 入口，负责校验、准备 TUN、启动、停止和内存回收。
- `libandroidcyaml.so`：C++ JNI 包装层，连接 Java 回调和 Go 导出函数。
- `native/mihomo`：AndroidCyaml 自有 Go 包装模块，导出 JNI 所需的 C ABI 并调用 mihomo 包。
- `libmihomo.so`：由上述包装模块和固定 mihomo 源码共同构建的 Go `c-shared` 库。
- `NativePlatformCallbacks`：把每个出站 FD 交给 `VpnService.protect()`，并提供连接 UID/包名查询。
- `AndroidTunManager`：应用固定接口地址、路由、DNS 和应用范围；不会把 AndroidCyaml 自身排除。
- `RuntimeCoordinator`：串行化启动、停止、配置事务、栈切换、IPv6 环境变化和底层网络切换。
- `MainActivity`：运行在独立 `:ui` 进程，只通过同 UID、非导出的 Binder 服务控制 VPN 进程。

使用 `tun.include-package` 白名单时，外壳会自动把 AndroidCyaml 自身加入允许列表；使用
`tun.exclude-package` 时会忽略对自身包的排除。这样核心始终位于 VPN 数据路径内，真实出站再通过
逐 socket `protect()` 离开 VPN。

## TUN 栈

覆写面板可直接选择：

- **system 全栈（默认）**：TCP、UDP 和 ICMP 均使用 sing-tun system 实现。
- **gVisor 全栈**：TCP、UDP 和 ICMP 均由用户态 gVisor 网络栈处理。
- **mixed**：mihomo 官方语义，TCP 使用 system，UDP 使用 gVisor。

system 栈需要接口前缀中存在可用的“下一个地址”。AndroidCyaml 不采用用户 YAML 中的接口地址，
而是固定使用：

- IPv4：`172.19.0.1/30`
- IPv6：`fdfe:dcba:9876::1/126`
- MTU：`9000`
- GSO：关闭

因此 system TCP listener 可以使用 `.2` / `::2` 完成 NAT 回注，不会再遇到 `/32`、`/128` 无下一
地址的问题。接口地址会保留前缀中的主机位；只在添加路由时才把前缀归一为网段，避免 Android
`IpPrefix` 把 `.1` / `::1` 错误转换为 `.0` / `::`。

## 逐 socket protect

AndroidCyaml 的 Go 包装模块为 mihomo 的真实拨号安装 `dialer.DefaultSocketHook`。每个 TCP、UDP 等
出站 socket 在连接前通过 JNI 回调：

```text
Go RawConn FD → NativePlatformCallbacks.protectSocket(fd)
              → AndroidVpnService.protect(fd)
```

失败会中止该次拨号，而不是静默形成 VPN 路由循环。system 栈内部 listener 不属于真实代理出站，
不会被该 hook 排除。

## 进程匹配

覆写面板中的“进程匹配”与 TUN 栈独立：

- 开启：强制 `find-process-mode: always`；mihomo 按协议和原始四元组调用 Android
  `ConnectivityManager.getConnectionOwnerUid()`，再把 UID 映射为包名。
- 关闭：强制 `find-process-mode: off`。

核心、JNI 和 VPN 服务在同一进程中，进程查询不再经过 JSON/Unix Socket 往返。

## IPv6

IPv6 开关表示用户意愿。应用监控系统选择的最佳非 VPN 互联网网络，不会在 VPN 建立后把自身虚拟
网络误认为底层网络；实际启用还要求该网络同时具备：

- Android 已验证的互联网能力；
- 全局 IPv6 地址；
- IPv6 默认路由。

启动时环境不满足会保留 IPv6 开关状态并使用 IPv4-only；运行中只有在新的可用底层网络确认不支持
IPv6 时才重建为 IPv4-only。Wi‑Fi 与移动数据切换时若协议族不变，应用保留现有 TUN，只关闭旧连接
并清理接口、DNS 缓存与持久 DNS 连接，让新请求立即走新网络。短暂无网络不会触发 IPv6/IPv4 来回
重建。即使环境检测通过，只要 IPv6 模式启动失败，也会关闭失败实例并用 IPv4-only 重试一次。

## 配置边界

上传的 `config.yaml` 按原字节保存，不会被重写。Android JNI 运行时在内存中接管以下平台字段：

- 强制启用 TUN，并设置设备名 `AndroidCyaml`；
- 使用覆写面板选择的 `system`、`gvisor` 或 `mixed`；
- 使用固定 `/30`、`/126` 地址、MTU 9000，并关闭 GSO；
- 根据 IPv6 有效状态移除 IPv6 地址和路由；
- 将路由、排除路由、DNS 和包范围交给 `VpnService.Builder`；
- 把 Android TUN FD 交给 sing-tun，并关闭核心侧重复的系统路由操作。

节点、代理组、规则、DNS 行为、fake-ip、sniffer、DNS 劫持、NAT 和代理选择仍由 mihomo 处理。
动态 `route-address-set` 不能直接转换为 Android `VpnService.Builder` 路由，因此会明确报错。

域名展示仍依赖 fake-ip DNS 映射或 sniffer；应用自行使用加密 DNS、关闭映射与嗅探，或目标本身只有
IP 时，连接面板显示 IP 属于正常结果。

## 配置导入事务

1. 从 Android Storage Access Framework 读取候选文件，最大 32 MiB。
2. 使用同一份嵌入式 mihomo 解析候选配置。
3. 校验成功后原子替换应用私有的 `config.yaml`，并将权限设为仅应用自身可读写的 `0600`。
4. VPN 运行时，在同一个服务进程内停止旧核心并重建 TUN。
5. 新配置无法启动时恢复上一份配置和运行状态。

首次安装使用内置 DIRECT 默认配置；GeoIP、GeoSite 与 Zashboard 均随 APK 离线分发。

## 系统 VPN

- 支持系统“始终开启 VPN”和锁定模式。
- 普通模式可从通知停止 VPN。
- 系统托管时，应用内停止入口会提示前往系统 VPN 设置。
- UI 或 WebView 被回收不会停止默认进程中的 VPN 与 mihomo。

## Android 17 内存限制

AndroidCyaml 按 [Android 17 应用内存限制](https://developer.android.com/about/versions/17/behavior-changes-all?hl=zh-cn)
和 [OPPO 公平运行内存适配](https://open.oppomobile.com/documentation/page/info?id=13825)
处理内存压力：

- VPN、TUN 和 mihomo 留在默认前台服务进程，WebView 仅存在于可回收的 `:ui` 进程。
- UI 进入后台后解除绑定、销毁 WebView 并结束独立 `:ui` 进程；再次打开时冷启动并重新加载
  Dashboard，UI 回收不影响 VPN。
- 默认进程接收 OPPO `itgsa.intent.action.TRIM`/`KILL` 广播，在后台线程释放可重建缓存、保存已
  落盘状态，并通过 Binder 返回处理结果。PSS 只在后台低频采样。
- 空闲服务进程收到内存回调时不会因此加载 `libmihomo.so`。核心运行时会清理 DNS、接口和
  geodata 解析缓存，并由 Go 运行时把空闲页归还 Android。
- 启动时读取 `ApplicationExitInfo`；`REASON_OTHER` 且描述包含
  `MemoryLimiter:AnonSwap` 的退出记录会写入应用私有 `noBackupFilesDir/tombstones`，最多保留
  16 条。

Android 17 设备可用以下命令建立 UI 可见、UI 退后台和 VPN-only 三种状态的 PSS/RSS 基线，并测试
指定进程的限制。手动限制会影响当前进程，测试后务必恢复：

```bash
adb shell am memory-limiter status
adb shell dumpsys meminfo io.github.qwqgong.androidcyaml
adb shell pidof io.github.qwqgong.androidcyaml
adb shell pidof io.github.qwqgong.androidcyaml:ui

adb shell am memory-limiter manual <pid> 256MB
# 执行启动、配置导入、前后台切换和 VPN 连通性测试
adb shell am memory-limiter manual <pid> none
```

Android 17 不提供应用侧查询实际限制的运行时 API；以设备的 `am memory-limiter status`、进程
PSS/RSS、退出记录和 VPN 连通性为验收事实，不能仅以 Java 堆大小代替整进程内存。

## 构建

需要：

- JDK 17
- Android SDK Platform 37 与 Build Tools 37.0.0
- Android NDK `29.0.14206865` 与 CMake 3.22.1
- Go 1.26 或更高版本
- Git、bash、unzip、readelf、sha256sum

```properties
# local.properties
sdk.dir=/absolute/path/to/Android/Sdk
```

```bash
./scripts/fetch_zashboard.sh
./scripts/fetch_geodata.sh
./gradlew :app:assembleDebug :app:lintDebug
bash scripts/verify_native_runtime.sh app/build/outputs/apk/debug/app-debug.apk
```

### ART、启动与 Release 优化

Release 已启用 R8 代码压缩、优化和混淆以及资源压缩。Java/DEX 启动路径由两份静态规则覆盖，不需要
连接手机采集：

- `app/src/main/baseline-prof.txt` 会编译为 APK 中的
  `assets/dexopt/baseline.prof`/`baseline.profm`，供 ART 对启动代码做 Profile-guided AOT；
- `app/src/main/baselineProfiles/startup-prof.txt` 供 R8 调整 DEX 布局，把启动类放入启动 DEX；
- `androidx.profileinstaller` 负责侧载安装后的 Profile 安装兼容。

可用调试签名编译与 Release 相同优化配置的独立测试包：

```bash
./gradlew :app:assembleOptimized :app:bundleOptimized :app:lintOptimized
bash scripts/verify_art_optimization.sh \
  app/build/outputs/apk/optimized/app-optimized.apk \
  app/build/outputs/bundle/optimized/app-optimized.aab
```

`libmihomo.so` 和 `libandroidcyaml.so` 已经是原生机器码，不经过 ART。发行包会剥离原生调试/静态
符号；用于崩溃符号化的符号表单独生成在
`app/build/outputs/native-debug-symbols/<variant>/native-debug-symbols.zip`，R8 映射位于
`app/build/outputs/mapping/<variant>/mapping.txt`。

`scripts/build_mihomo.sh` 会从干净 mihomo 提交开始，在临时目录应用 AndroidCyaml 自有补丁，然后以
Android arm64、CGO、`with_gvisor` 和 `-buildmode=c-shared` 构建 `native/mihomo`。CMake 再生成 JNI
包装库 `libandroidcyaml.so`。验证脚本检查：

- 两个库均为 AArch64；
- JNI 与 Go 导出符号完整；
- `libandroidcyaml.so` 仅以 `libmihomo.so` 为依赖名，不含构建机绝对路径；
- Go 核心不存在反向引用 JNI 包装层的循环未定义符号；
- 两个 ELF 的所有 LOAD 段均至少 16 KiB 对齐。

正式发行需要设置：

- `ANDROID_SIGNING_STORE_FILE`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

然后执行：

```bash
./gradlew :app:assembleRelease :app:bundleRelease :app:lintRelease
bash scripts/verify_native_runtime.sh app/build/outputs/apk/release/app-release.apk
bash scripts/verify_art_optimization.sh \
  app/build/outputs/apk/release/app-release.apk \
  app/build/outputs/bundle/release/app-release.aab
```

## 固定依赖

- 干净 mihomo 提交：见 `app/build.gradle.kts` 中的 `mihomoCommit`
- AndroidCyaml 补丁：`patches/mihomo/0001-androidcyaml-platform-hooks.patch`
- AndroidCyaml Go 包装模块：`native/mihomo`
- Zashboard：`v3.15.0` 无字体构建
- MetaCubeX meta-rules-dat：见 `geodataCommit`

许可证与固定版本说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
