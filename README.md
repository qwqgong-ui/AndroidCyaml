# AndroidCyaml

AndroidCyaml 是一个面向 Android 16（API 36）、仅提供 `arm64-v8a` 构建的 mihomo VPN 外壳。
自 0.6.130 起，项目废弃 HEV/tun2socks/SOCKS 中转路径，系统流量由 mihomo 自身的 TUN 栈直接处理：

```text
Android 应用
  → Android VpnService TUN
  → mihomo sing-tun（system / gVisor / mixed）
  → 规则、DNS、嗅探、代理组和节点
  → 底层网络
```

应用不再把 IP 包转换成内部 SOCKS5 连接。因此，mihomo 可以继续维护 TUN 入口的原始流量元数据、
DNS fake-ip 映射和嗅探结果；Android 连接所有者查询也可以把 UID 映射回包名。

## 设计边界

架构采用“核心拥有配置语义，Android 只实现平台能力”的分层：

- `AndroidVpnService` 是唯一的运行时生命周期所有者，并持有系统 VPN 授权。
- `MihomoRuntime` 只管理一个固定版本的 mihomo 子进程、控制器和只读配置文件。
- `AndroidPlatformBridge` 通过仅应用进程可访问的抽象 Unix Socket 接收核心请求：
  - `open_tun`：核心先解析完整配置，再把 MTU、接口地址、路由、DNS 和应用范围交给
    `VpnService.Builder`；Android 返回 TUN 文件描述符。
  - `find_process`：核心按协议和四元组请求 Android 查询连接 UID，再映射为包名。
- `RuntimeCoordinator` 串行化启动、停止、重启、配置导入和运行时覆写，防止 UI、系统始终开启 VPN
  和通知操作并发创建多套核心或 TUN。
- `RuntimeOverrideStore` 只持久化外壳覆写，不修改或重新生成用户 YAML。
- `MainActivity` 运行在独立 `:ui` 进程，只通过非导出的 Binder 控制服务观察和操作运行时。

mihomo 上游连接与控制器均属于 AndroidCyaml 自身 UID。该 UID 始终从 VPN 接管范围中排除，避免
上游再次进入 TUN 形成路由循环。

## 配置

上传的文件按原字节复制到应用私有目录。外壳不会重写节点、代理组、规则、DNS、sniffer 或 TUN
字段，也不会把 Android 运行时参数写回 YAML。

用于 VPN 的配置必须正常启用 mihomo TUN，例如：

```yaml
tun:
  enable: true
  stack: mixed
  dns-hijack:
    - any:53
    - tcp://any:53
  auto-route: true
  auto-detect-interface: true
```

Android 平台会消费以下操作系统级字段：

- `tun.mtu`
- 解析后的 IPv4/IPv6 TUN 地址
- `tun.auto-route`、路由与排除路由
- `tun.include-package` / `tun.exclude-package`
- DNS 模块启用时的虚拟 DNS 地址

TUN 栈、DNS 劫持、fake-ip、NAT、嗅探、规则和代理选择仍由 mihomo 处理。

### 运行时覆写

右上角菜单中的“覆写面板”当前只管理 TUN 栈：

- **跟随 config.yaml**：不传入栈覆写，使用配置解析出的 `tun.stack`。
- **强制 gVisor**：在 mihomo 完成配置解析后、创建 TUN 监听前，把本次运行的栈改为 gVisor。
- **system（当前不可用）**：在面板中显示但禁用，Binder 和 mihomo 命令行也会拒绝该值。

覆写保存在应用独立的运行时设置中，不写回 `config.yaml`。VPN 已连接时，切换会通过
`RuntimeCoordinator` 串行重启 mihomo；如果新覆写无法建立 TUN，会恢复上一项覆写并重新启动。

### 连接面板中的域名与进程

域名展示依赖 mihomo 能获得域名语义：

- `dns.enhanced-mode: fake-ip` 配合 TUN DNS 劫持；或
- 启用 `sniffer`，允许对 HTTP/TLS/QUIC 流量提取域名。

应用自行使用加密 DNS、配置关闭 DNS 映射与嗅探，或目标本来就是纯 IP 时，面板显示 IP 属于正常
结果。进程/包名展示还取决于 `find-process-mode`；设为 `off` 时核心不会执行 Android UID 查询。

## 配置导入事务

1. 从 Android Storage Access Framework 读取候选文件，最大 32 MiB。
2. 使用同一份内置 mihomo 执行 `-t` 完整解析校验。
3. 校验成功后原子替换应用私有的 `config.yaml`，并设为只读。
4. VPN 正在运行时，停止旧核心并在同一个平台桥上启动新核心。
5. 新配置无法建立 TUN 时，自动恢复上一份配置并重新启动。

首次安装使用内置的 DIRECT 默认配置。GeoIP、GeoSite 与 Zashboard 均随 APK 离线分发。

## 系统 VPN

- 支持系统“始终开启 VPN”和锁定模式。
- 普通模式可从通知停止 VPN。
- 系统托管时，应用内停止入口会提示前往系统 VPN 设置。
- UI 退出或 WebView 被回收不会停止默认进程中的 VPN 与 mihomo。

## 构建

需要：

- JDK 17
- Android SDK Platform 36 与 Build Tools 36.0.0
- Go 1.26 或更高版本
- Git、bash、sha256sum

```properties
# local.properties
sdk.dir=/absolute/path/to/Android/Sdk
```

```bash
./scripts/fetch_zashboard.sh
./scripts/fetch_geodata.sh
./gradlew :app:assembleDebug :app:lintDebug
```

`scripts/build_mihomo.sh` 直接检出 `qwqgong-ui/mihomo` 的固定提交，以
`GOOS=android GOARCH=arm64 CGO_ENABLED=0` 和 `with_gvisor` 标签构建。AndroidCyaml 仓库不再应用
mihomo 补丁，也不再构建或打包 HEV/NDK 组件。

正式发行包需要通过以下环境变量提供长期签名密钥：

- `ANDROID_SIGNING_STORE_FILE`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

然后执行：

```bash
./gradlew :app:assembleRelease :app:lintRelease
```

## 固定依赖

- mihomo：见 `app/build.gradle.kts` 中的 `mihomoCommit`
- Zashboard：`v3.15.0` 无字体构建
- MetaCubeX meta-rules-dat：见 `geodataCommit`

许可证与固定版本说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
