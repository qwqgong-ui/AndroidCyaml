# AndroidCyaml

AndroidCyaml 是一个仅面向 Android 16 的 mihomo VPN 客户端。它启动
[`qwqgong-ui/mihomo`](https://github.com/qwqgong-ui/mihomo) 的 arm64 核心，在应用内用
WebView 打开内置的 [`Zephyruso/zashboard`](https://github.com/Zephyruso/zashboard)，并允许
从 Android 文件选择器上传 `config.yaml`。原生 `VpnService` 建立固定的双栈 TUN，
[`heiher/hev-socks5-tunnel`](https://github.com/heiher/hev-socks5-tunnel) 再把 TUN 中的 TCP/UDP
流量转发到 mihomo 的回环 SOCKS5 或 mixed 端口，无需 root。

## 平台限制

- 仅 Android 16：`minSdk = targetSdk = compileSdk = 36`
- 仅 ARMv8 64 位：APK 只包含 `arm64-v8a`
- 不兼容 Android 15 及以下、`armeabi-v7a`、`x86` 或 `x86_64`

## 功能

- mihomo 固定到提交 `a563ca2194edbf560b3857801cb3cceab13d7ff9`
- HEV SOCKS5 tunnel 固定到提交 `df11261f09ebafc37bac03f81029c9b75a4aa074`
- zashboard 固定到 `v3.15.0` 的无字体构建，静态文件随 APK 离线分发
- 完整 GeoIP/GeoSite 固定到 `MetaCubeX/meta-rules-dat` 提交 `ab44fa37` 并随 APK 分发，
  配置首次使用 GEO 规则时无需从 GitHub 下载
- WebView 优先通过固定的 `127.0.0.1:17890` 访问面板，使 Zashboard 设置可跨重启保留；
  端口冲突时自动回退到空闲端口
- 原生 VPN 滑块申请系统授权并接管全局 IPv4/IPv6 流量
- HEV 使用 lwIP 将 TUN 流量转成 SOCKS5 TCP/UDP，不再把 Android TUN 文件描述符交给 mihomo
- 上传配置、重启核心、运行时覆写和“隐藏后台标签页”集中在右上角二级菜单
- Android 16 `systemExempted` 前台服务与常驻通知，可从通知直接停止 VPN
- AndroidCyaml 自身从 VPN 中排除，使 HEV 的回环 SOCKS 连接和 mihomo 上游连接走底层网络，
  避免路由循环
- 每次安装随机生成 256 位控制器密钥，控制器只监听回环地址
- 上传 `config.yaml` 后按原字节复制为只读文件，再由正式核心解析并启动；应用不会改写
  文件选择器中的原文件或把运行参数写回 YAML
- 新配置不能启动时自动恢复上一份配置
- `external-controller`、`external-ui` 和 `secret` 由启动参数覆盖

## 使用

1. 在 Android 16 arm64 设备上安装 APK。
2. 打开 AndroidCyaml，等待状态栏显示 mihomo 已运行。
3. 打开右上角二级菜单，点击“上传 config.yaml”，从系统文件选择器选择 YAML 配置。
4. 配置必须提供一个可由回环地址无认证访问的 `socks-port` 或 `mixed-port`。默认配置使用
   `mixed-port: 7890`。
5. 打开 VPN 滑块，在 Android 系统对话框中允许建立 VPN。
6. 界面显示“VPN 已连接”后，其他应用的 IPv4/IPv6 TCP 与 UDP 流量会经 HEV 进入 mihomo。

首次启动没有用户配置时，应用使用仅含 `DIRECT` 规则的默认配置。导入文件大小上限为
32 MiB。内置 GeoIP/GeoSite 只会在文件缺失时复制，不会覆盖已有数据库。未启动 VPN 时，
mihomo 仍以本地 HTTP/SOCKS 代理模式运行。

### Android VPN 固定参数

Android 接口完全由 `VpnService.Builder` 管理，不读取或覆写 `config.yaml` 中的 TUN MTU、地址
或 DNS 地址：

- MTU：`9000`
- IPv4：`198.18.0.1/30`
- 系统 DNS：`198.18.0.2`
- IPv6：`fdfe:dcba:9876::1/126`
- IPv4 路由：`0.0.0.0/0`
- IPv6 路由：`::/0`

`198.18.0.2` 由 HEV 的 MapDNS 在 TUN 内本地响应。它把系统 DNS 查询映射为保留地址，并在建立
SOCKS5 连接时恢复域名，因此域名仍交给 mihomo 的规则和解析器处理。HEV 当前的 MapDNS 是
IPv4 映射服务，所以 Android 只发布这一条系统 DNS；这不影响 VPN 接管 IPv6 流量或 mihomo
使用 IPv6 上游。

VPN 启动时 mihomo 始终保持本地代理模式。YAML 中的 `tun.enable`、`tun.mtu`、TUN 地址和
`dns-hijack` 不用于配置 Android VPN，也不要求设置 `tun.enable: true`。

每应用路由由 `VpnService.Builder` 负责，当前固定为“除 AndroidCyaml 自身外的所有应用”。
配置中的 `tun.include-package` 与 `tun.exclude-package` 不参与 Android 应用选择。导入配置或重启
核心时，VPN 服务会等待新的回环 SOCKS5 入站；端口发生变化时会在保留 Android TUN 的情况下
重启 HEV 转发器。此版本也明确禁用系统的“始终开启 VPN”选项；需要手动在应用中启动连接。

### 进程规则限制

HEV 将原始应用连接转换为来自 AndroidCyaml UID 的回环 SOCKS5 连接。mihomo 因此无法再看到
原始应用 UID 或包名，`PROCESS-NAME`、`PROCESS-PATH` 等进程规则以及连接面板中的原应用归属
不可靠。域名、IP、端口、网络类型和普通规则不受此限制。

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

Gradle 的 `preBuild` 会自动执行：

- `scripts/build_mihomo.sh`：从固定提交构建仅用于回环代理的 mihomo；不再编入 gVisor 栈
- `scripts/build_hev_socks5_tunnel.sh`：从固定提交和递归子模块构建 HEV JNI 共享库

生成物分别位于：

```text
app/src/main/jniLibs/arm64-v8a/libmihomo.so
app/src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so
```

调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

上游 mihomo 当前提交提前声明了尚未发布的 Go 1.27。使用 Go 1.26 时，构建脚本只在生成的
临时检出中把 `go` 指令调整为 1.26。Go 1.27 可用后脚本会直接使用原声明。

## 实现说明

Android 10 起不允许从应用的可写数据目录执行文件。因此 mihomo 可执行文件以
`libmihomo.so` 的名字进入 APK 的 `lib/arm64-v8a`，并通过旧式 native library packaging
让系统安装器把它提取到只读、可执行的 `nativeLibraryDir`；应用直接从该目录启动核心。

mihomo 在该架构中只提供回环代理、规则和出站连接，构建时不启用 `with_gvisor` 标签。

HEV 以标准 JNI 共享库 `libhev-socks5-tunnel.so` 打包。`VpnService` 建立非阻塞 TUN 后把现有
文件描述符传给 HEV；外部 FD 模式下 HEV 不创建或配置系统接口，只负责从 TUN 读写数据并连接
mihomo 的 `127.0.0.1` SOCKS5 端口。VPN 服务通过 mihomo 的回环控制器只读发现
`socks-port`/`mixed-port`，并在使用前完成无认证 SOCKS5 握手校验。停止 VPN 时先停止 HEV 工作
线程，再关闭 TUN FD，mihomo 核心保持运行，因此不会因 VPN 开关反复重启。

zashboard 在首次启动或版本变化时从 APK assets 原子复制到应用私有目录，再由 mihomo
自己的 HTTP 路由提供。WebView 禁止文件与 content 访问，只允许回环面板留在应用中；外部
HTTP(S) 导航会交给系统浏览器。

## 上游与许可证

AndroidCyaml 采用 GPL-3.0 许可证。mihomo 为 GPL-3.0；zashboard、HEV SOCKS5 tunnel 及其
多数子组件为 MIT；lwIP 为 BSD-3-Clause。固定版本、校验值和许可证副本见
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
