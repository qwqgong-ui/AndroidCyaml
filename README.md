# AndroidCyaml

AndroidCyaml 是一个面向 Android 16（API 36）、仅提供 `arm64-v8a` 构建的 mihomo VPN 外壳。
从 0.6.133 起，mihomo 不再以子进程运行，也不再通过抽象 Unix Socket 请求 TUN；Go 核心以
`c-shared` 形式随 APK 打包，通过 JNI 直接运行在 `AndroidVpnService` 所在进程中。

```text
Android 应用流量
  → Android VpnService TUN
  → JNI 包装库 libandroidcyaml.so
  → Go c-shared 核心 libmihomo.so
  → mihomo sing-tun（system / gVisor / mixed）
  → DNS、嗅探、规则、代理组和节点
  → 每个真实出站 socket 调用 VpnService.protect(fd)
  → 底层网络
```

整个应用 UID 不再被排除出 VPN。只有 mihomo 真正建立的上游 socket 会被逐个保护，因此 system 栈
内部的 TCP listener、TCP NAT 回注和 TUN 数据路径仍留在 VPN 内，同时代理出站不会重新进入 TUN。

## 核心与补丁边界

`qwqgong-ui/mihomo/Alpha` 同时用于电脑端构建，因此不承载 AndroidCyaml 专用代码。AndroidCyaml
固定一个干净的 mihomo 提交，并在临时检出目录中按 [`patches/mihomo/series`](patches/mihomo/series)
应用本仓库维护的补丁。

构建脚本会执行以下约束：

1. 强制检出并清理固定的干净 mihomo 提交；
2. 依次执行每个补丁的 `git apply --check`；
3. 校验补丁最终只能新增 `android/jni/main.go`、`android/jni/config.go` 和
   `android/jni/platform.go`；
4. 任何补丁修改共享核心现有文件时立即终止构建。

因此，JNI 生命周期、`VpnService.protect()`、Android UID 查询、固定 TUN 地址和运行时栈覆写只存在于
AndroidCyaml 的构建产物，不会污染电脑端 mihomo 历史。

## 运行时结构

- `AndroidVpnService`：拥有 VPN 授权、前台服务、通知、`VpnService.Builder` 和 TUN 文件描述符。
- `MihomoNative`：JNI 的 Java 入口，负责校验、准备 TUN、启动、停止和内存回收。
- `libandroidcyaml.so`：C++ JNI 包装层，连接 Java 回调和 Go 导出函数。
- `libmihomo.so`：由干净 mihomo 提交和 AndroidCyaml 本地补丁生成的 Go `c-shared` 核心。
- `NativePlatformCallbacks`：把每个出站 FD 交给 `VpnService.protect()`，并提供连接 UID/包名查询。
- `AndroidTunManager`：应用固定接口地址、路由、DNS 和应用范围；不会把 AndroidCyaml 自身排除。
- `RuntimeCoordinator`：串行化启动、停止、配置事务、栈切换和 IPv6 环境切换。
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
地址的问题。

## 逐 socket protect

Go 核心为 mihomo 的真实拨号安装 `dialer.DefaultSocketHook`。每个 TCP、UDP 等出站 socket 在连接
前通过 JNI 回调：

```text
Go RawConn FD → NativePlatformCallbacks.protectSocket(fd)
              → AndroidVpnService.protect(fd)
```

失败会中止该次拨号，而不是静默形成 VPN 路由循环。system 栈内部 listener 不属于真实代理出站，
不会被该 hook 排除。

## 进程匹配

覆写面板中的“进程匹配”与 TUN 栈独立：

- 开启：强制 `find-process-mode: always`；mihomo 通过 `DefaultPackageNameResolver` 按协议和原始四元组
  调用 Android `ConnectivityManager.getConnectionOwnerUid()`，再把 UID 映射为包名。
- 关闭：强制 `find-process-mode: off`。

核心、JNI 和 VPN 服务在同一进程中，进程查询不再经过 JSON/Unix Socket 往返。

## IPv6

IPv6 开关表示用户意愿，实际启用还要求底层默认网络同时具备：

- Android 已验证的互联网能力；
- 全局 IPv6 地址；
- IPv6 默认路由。

环境不满足时，应用保留 IPv6 开关状态，但自动重建为 IPv4-only；环境恢复后重新启用 IPv6。即使
环境检测通过，只要 IPv6 模式启动失败，也会关闭失败实例并用 IPv4-only 重试一次。

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
3. 校验成功后原子替换应用私有的 `config.yaml`，并设为只读。
4. VPN 运行时，在同一个服务进程内停止旧核心并重建 TUN。
5. 新配置无法启动时恢复上一份配置和运行状态。

首次安装使用内置 DIRECT 默认配置；GeoIP、GeoSite 与 Zashboard 均随 APK 离线分发。

## 系统 VPN

- 支持系统“始终开启 VPN”和锁定模式。
- 普通模式可从通知停止 VPN。
- 系统托管时，应用内停止入口会提示前往系统 VPN 设置。
- UI 或 WebView 被回收不会停止默认进程中的 VPN 与 mihomo。

## 构建

需要：

- JDK 17
- Android SDK Platform 36 与 Build Tools 36.0.0
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

`scripts/build_mihomo.sh` 检出 `app/build.gradle.kts` 固定的干净 mihomo 提交，应用本仓库
`patches/mihomo/series` 中的 Android 专用补丁，再使用 Android arm64 CGO、`with_gvisor,cmfa` 标签和
Go `-buildmode=c-shared` 生成 `libmihomo.so`。CMake 生成 JNI 包装库 `libandroidcyaml.so`。

验证脚本检查：

- 两个库均为 AArch64；
- JNI 与 Go 导出符号完整；
- `libandroidcyaml.so` 仅以 `libmihomo.so` 为依赖名，不含构建机绝对路径；
- 两个 ELF 的所有 LOAD 段均至少 16 KiB 对齐；
- Go 核心不包含反向引用 JNI 包装库的未解析回调符号。

正式发行需要设置：

- `ANDROID_SIGNING_STORE_FILE`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

然后执行：

```bash
./gradlew :app:assembleRelease :app:lintRelease
bash scripts/verify_native_runtime.sh app/build/outputs/apk/release/app-release.apk
```

## 固定依赖

- mihomo 干净提交：见 `app/build.gradle.kts` 中的 `mihomoCommit`
- mihomo Android 补丁：见 `patches/mihomo/series`
- Zashboard：`v3.15.0` 无字体构建
- MetaCubeX meta-rules-dat：见 `geodataCommit`

许可证与固定版本说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
