# AndroidCyaml

AndroidCyaml 是一个仅面向 Android 16 的 mihomo VPN 客户端。它启动
[`qwqgong-ui/mihomo`](https://github.com/qwqgong-ui/mihomo) 的 arm64 核心，在应用内用
WebView 打开内置的 [`Zephyruso/zashboard`](https://github.com/Zephyruso/zashboard)，并允许
从 Android 文件选择器上传 `config.yaml`。原生 `VpnService` 会把系统 TUN 文件描述符交给
mihomo 的 gVisor 用户态网络栈，无需 root。

## 平台限制

- 仅 Android 16：`minSdk = targetSdk = compileSdk = 36`
- 仅 ARMv8 64 位：APK 只包含 `arm64-v8a`
- 不兼容 Android 15 及以下、`armeabi-v7a`、`x86` 或 `x86_64`

## 功能

- mihomo 固定到提交 `a563ca2194edbf560b3857801cb3cceab13d7ff9`
- zashboard 固定到 `v3.15.0` 的无字体构建，静态文件随 APK 离线分发
- 完整 GeoIP/GeoSite 固定到 `MetaCubeX/meta-rules-dat` 提交 `ab44fa37` 并随 APK 分发，
  配置首次使用 GEO 规则时无需从 GitHub 下载
- WebView 优先通过固定的 `127.0.0.1:17890` 访问面板，使 Zashboard 设置可跨重启保留；
  端口冲突时自动回退到空闲端口
- 原生 VPN 滑块申请系统授权并接管全局 IPv4/IPv6 流量
- 上传配置、重启核心、运行时覆写和“隐藏后台标签页”集中在右上角二级菜单
- 运行时覆写面板目前提供进程匹配的 `strict`、`always`、`off` 和“跟随配置”模式，
  只调用控制器 API，不会写回 YAML
- VPN 运行时通过 Android `ConnectivityManager` 读取连接 UID，再映射为包名交给 mihomo，
  Android 16 上的进程规则和连接面板可正常显示进程
- Android 16 `systemExempted` 前台服务与常驻通知，可从通知直接停止 VPN
- TUN 文件描述符通过带随机名称的本地 Unix Socket 和 `SCM_RIGHTS` 传给 mihomo 子进程
- AndroidCyaml 自身从 VPN 中排除，使 mihomo 的上游连接走底层网络并避免路由循环
- 每次安装随机生成 256 位控制器密钥，控制器只监听回环地址
- 上传 `config.yaml` 后按原字节复制为只读文件，再由正式核心解析并启动；应用不会改写
  文件选择器中的原文件或把运行参数写回 YAML
- 新配置不能启动时自动恢复上一份配置
- `external-controller`、`external-ui` 和 `secret` 由启动参数覆盖

## 使用

1. 在 Android 16 arm64 设备上安装 APK。
2. 打开 AndroidCyaml，等待状态栏显示 mihomo 已运行。
3. 打开右上角二级菜单，点击“上传 config.yaml”，从系统文件选择器选择 YAML 配置。
4. 导入成功后核心会自动重启，zashboard 随即重新连接。
5. 打开 VPN 滑块，在 Android 系统对话框中允许建立 VPN。
6. 界面显示“VPN 已连接”后，其他应用的 IPv4/IPv6 流量会进入 mihomo。

首次启动没有用户配置时，应用使用仅含 `DIRECT` 规则的默认配置。导入文件大小上限为
32 MiB。内置 GeoIP/GeoSite 只会在文件缺失时复制，不会覆盖已有数据库。未启动 VPN 时，
mihomo 仍以本地 HTTP/SOCKS 代理模式运行。

VPN 的生命周期由原生滑块管理，因此 zashboard 中的 TUN 开关被禁用。启动 VPN 时，应用只
在内存中注入这些 TUN 运行参数（不会写回 `config.yaml`）：`enable`、`file-descriptor`、虚拟地址、MTU、gVisor 栈、
`auto-route`、`auto-redirect` 和 `auto-detect-interface`；节点、规则、策略组和用户 DNS
设置仍来自上传的 `config.yaml`。如果配置没有启用 DNS，应用会启用 mihomo 已解析的默认
DNS，以便 Android 的虚拟 DNS 网关可用。

每应用路由由 `VpnService.Builder` 负责，当前固定为“除 AndroidCyaml 自身外的所有应用”。
配置中的 `tun.include-package` 与 `tun.exclude-package` 会被忽略。此版本也明确禁用系统的
“始终开启 VPN”选项；需要手动在应用中启动连接。

进程匹配依赖活动中的 `VpnService`：Android 10 起普通应用不能读取 `/proc/net`，因此仅本地
代理模式无法可靠获得其他应用的进程。VPN 模式下，AndroidCyaml 使用系统连接所有者 API
获取 UID，并借助 `QUERY_ALL_PACKAGES` 普通权限解析包名；这些结果只在设备本地传给 mihomo。

## 构建

需要：

- JDK 17
- Android SDK Platform 36 与 Build Tools 36.0.0
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

Gradle 的 `preBuild` 会自动执行 `scripts/build_mihomo.sh`，从指定 fork 取回固定提交、应用
[`patches/mihomo-android-vpn.patch`](patches/mihomo-android-vpn.patch)，并生成
`app/src/main/jniLibs/arm64-v8a/libmihomo.so`。该文件是生成物，不提交到 Git。调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

上游当前提交提前声明了尚未发布的 Go 1.27。使用 Go 1.26 时，构建脚本只在生成的临时检出中
把 `go` 指令调整为 1.26。Go 1.27 可用后脚本会直接使用原声明。

## 实现说明

Android 10 起不允许从应用的可写数据目录执行文件。因此 mihomo 可执行文件以
`libmihomo.so` 的名字进入 APK 的 `lib/arm64-v8a`，并通过旧式 native library packaging
让系统安装器把它提取到只读、可执行的 `nativeLibraryDir`；应用直接从该目录启动核心。

zashboard 在首次启动或版本变化时从 APK assets 原子复制到应用私有目录，再由 mihomo
自己的 HTTP 路由提供。WebView 禁止文件与 content 访问，只允许回环面板留在应用中；外部
HTTP(S) 导航会交给系统浏览器。

VPN 服务用 Android API 建立 TUN，然后通过抽象 Unix Socket 发送描述符。提交的 mihomo 补丁
在配置解析后注入 Android 专用 TUN 参数，并通过另一条随机命名的本地 Unix Socket 向
`VpnService` 查询连接 UID/包名，同时跳过普通应用无权读取的 `/data/system/packages.xml`。
停止 VPN 后，核心自动重启回本地代理模式。

## 上游与许可证

AndroidCyaml 采用 GPL-3.0 许可证。mihomo 为 GPL-3.0；zashboard 为 MIT。固定版本、校验值
和许可证副本见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
