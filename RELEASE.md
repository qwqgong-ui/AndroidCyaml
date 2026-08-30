# AndroidCyaml v1.0.38 发布说明

## socket protect 移出 JNI 热路径

`VpnService.protect()` 没有 NDK 等价物，只有 Java API 能让 netd 设置 protect
fwmark 位，所以请求必须到达 JVM；但它不必以 JNI upcall 的形式从 Go 线程发出。
上一版只是给这条路径加了并发上限：goroutine 阻塞在 cgo 期间独占其 OS thread，
每个进入 JNI 的线程还会被 attach 到 ART，限流只是把高水位压住，没有消除成因。

本版把拨号热路径整体移出 JNI：

- Go 侧连接 app 私有目录中的 unix socket endpoint，用 `SCM_RIGHTS` 把 socket fd
  交给 Java，等待裁决的 goroutine 停在 Go netpoller 上；
- 拨号路径因此不再进入 cgo，不创建 OS thread，也不再有 attach/detach 到 ART 的
  每次拨号开销；
- Java 侧由上限 8 个 worker 的线程池执行 `protect()` 与 `Network.bindSocket()`；
- endpoint 位于 `no_backup` 私有目录而非 abstract namespace（设备上所有 app 共享
  同一 network namespace），并校验对端 uid 属于本进程；
- endpoint 创建失败或运行中失效时自动回退到原 JNI 回调，拨号不会因此失败。

回退路径会让"端点没生效"和"端点工作正常"看起来一样，因此本版把实际生效的路径显式
暴露出来：运行状态行显示 `protect unix socket`，回退时显示 `protect JNI 回退`；core
日志在启动时也会记录一行。设备侧核对方法是读 `/proc/<pid>/task/*/comm` 中 `Thread-N`
的最大编号——它是累计 ART attach 次数，新版在拨号压测中不应继续增长。上述线程/内存
对比数据尚未在本版实测。

需要说明的是，这条改动解决的是 JNI attach 与 cgo 线程占用，不是 RSS 大头：1.0.37 实测
中线程栈合计只有 572 KB，而进程 PSS 为 102 MB，其中 Go 运行时（`Unknown` 段）占 68 MB。

## JNI/cgo 线程高水位限制

进程归属查询（`getConnectionOwnerUid`）是真正的 Binder 调用，仍走 JNI，保留 16
并发的 Go 侧入口；System WebView XHTTP 的阻塞式响应头回调使用独立的 16 并发入口；
WebView 请求取消不受该上限约束，不会因限流阻塞取消路径。限流发生在进入 cgo 前，
多余连接只停在轻量 Go goroutine，不占用额外 OS thread。

## 网络切换修复

本版修正了底层网络刷新与物理网络切换的边界：

- 同一 Android `Network` 上的 DNS、路由、DHCP 或链路地址刷新不再关闭
  已建立的代理连接；
- Wi-Fi、移动数据或其他物理网络之间切换时，仍会关闭旧网络连接，
  使新建 socket 绑定到新的底层网络；
- 网络切换时保留按网络和 DNS 来源隔离的长期 direct DNS 候选，
  普通易失应答和 resolver 连接仍会刷新；
- IPv4/IPv6 有效状态不变时继续复用现有 TUN，只有协议族合约变化时
  才重建 VPN 运行时。

## 按物理网络隔离的长期 DNS 候选缓存

本版本已实现按物理网络身份隔离的 direct DNS 长期候选缓存。缓存不是只按
“Wi-Fi / 移动数据”粗略分类，而是使用隐私化的稳定网络指纹分桶：

- Wi-Fi 优先使用 SSID 识别，无法取得时回退到 BSSID；
- 移动网络使用订阅、SIM 运营商与 carrier 等稳定信息识别；
- 传入 mihomo 并写入缓存键的是 SHA-256 指纹，不会持久化 SSID、BSSID 或 SIM 原始身份。

不同物理网络下的同一域名因此不会共用 direct DNS 候选，重回同一网络时则可
继续使用该网络之前的候选记录。

### 缓存行为

- direct nameserver 的各个 DNS 来源分别保存，来源之间不会相互覆盖；
- direct DNS 来源候选的最低保留时间为 24 小时；
- DNS 缓存会写入 `cache.db`，每小时定期保存，并在 core 正常关闭时再次保存；
- core 启动时恢复缓存及原始过期时间；停机期间已过期的结果会作为 stale
  候选恢复，并由乐观缓存机制在后台刷新；
- TCP 实际连接成功的地址可作为当前优选结果，但不会改写各 DNS 来源的长期原始候选。

### 网络切换

底层网络切换时，AndroidCyaml 会更新当前网络指纹和系统 DNS，清理普通的易失
DNS 应答缓存，重置 resolver 持久连接并关闭旧连接。按网络指纹隔离的 direct DNS
来源候选不会在切网时全部删除，但也不会被新网络误用。

这能在保留长期 DNS 候选和连接竞速信息的同时，避免 Wi-Fi、移动数据或不同
Wi-Fi 之间的 DNS/CDN 结果串用。
