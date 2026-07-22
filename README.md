# AndroidCyaml

AndroidCyaml 是一个极简的 Android 16 mihomo 容器。它启动
[`qwqgong-ui/mihomo`](https://github.com/qwqgong-ui/mihomo) 的 arm64 核心，在应用内用
WebView 打开内置的 [`Zephyruso/zashboard`](https://github.com/Zephyruso/zashboard)，并允许
从 Android 文件选择器上传 `config.yaml`。

## 平台限制

- 仅 Android 16：`minSdk = targetSdk = compileSdk = 36`
- 仅 ARMv8 64 位：APK 只包含 `arm64-v8a`
- 不兼容 Android 15 及以下、`armeabi-v7a`、`x86` 或 `x86_64`

## 功能

- mihomo 固定到提交 `a563ca2194edbf560b3857801cb3cceab13d7ff9`
- zashboard 固定到 `v3.15.0` 的无字体构建，静态文件随 APK 离线分发
- WebView 访问 mihomo 在 `127.0.0.1:9090/ui/` 提供的面板
- 每次安装随机生成 256 位控制器密钥，控制器只监听回环地址
- “上传 config.yaml”会先运行 `mihomo -t`；只有校验成功才替换当前配置
- 新配置不能启动时自动恢复上一份配置
- `external-controller`、`external-ui` 和 `secret` 由启动参数覆盖，用户配置不会断开内置面板

## 使用

1. 在 Android 16 arm64 设备上安装 APK。
2. 打开 AndroidCyaml，等待状态栏显示 mihomo 已运行。
3. 点击“上传 config.yaml”，从系统文件选择器选择 YAML 配置。
4. 校验成功后核心会自动重启，zashboard 随即重新连接。

首次启动没有用户配置时，应用使用仅含 `DIRECT` 规则的默认配置。导入文件大小上限为
32 MiB。

> AndroidCyaml 当前提供本地 mihomo 核心、HTTP/SOCKS 入站和控制面板，不实现 Android
> `VpnService`。因此不会自动接管全系统流量；需要由系统或其他应用显式使用配置中的代理端口。
> 含 `tun.enable: true` 的配置通常需要额外的 VPN/TUN 权限和文件描述符集成。

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
./gradlew :app:assembleDebug :app:lintDebug
```

Gradle 的 `preBuild` 会自动执行 `scripts/build_mihomo.sh`，从指定 fork 取回固定提交并生成
`app/src/main/jniLibs/arm64-v8a/libmihomo.so`。该文件是生成物，不提交到 Git。调试 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

上游当前提交提前声明了尚未发布的 Go 1.27。使用 Go 1.26 时，构建脚本只在生成的临时检出中
把 `go` 指令调整为 1.26；Go 源码与固定提交保持一致。Go 1.27 可用后脚本会直接使用原声明。

## 实现说明

Android 10 起不允许从应用的可写数据目录执行文件。因此 mihomo 可执行文件以
`libmihomo.so` 的名字进入 APK 的 `lib/arm64-v8a`，并通过旧式 native library packaging
让系统安装器把它提取到只读、可执行的 `nativeLibraryDir`；应用直接从该目录启动核心。

zashboard 在首次启动或版本变化时从 APK assets 原子复制到应用私有目录，再由 mihomo
自己的 HTTP 路由提供。WebView 禁止文件与 content 访问，只允许回环面板留在应用中；外部
HTTP(S) 导航会交给系统浏览器。

## 上游与许可证

AndroidCyaml 采用 GPL-3.0 许可证。mihomo 为 GPL-3.0；zashboard 为 MIT。固定版本、校验值
和许可证副本见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
