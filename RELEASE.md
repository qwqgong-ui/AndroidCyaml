# AndroidCyaml v1.0.53 发布说明

## 核心日志收进诊断日志

v1.0.52 把方向搞反了:它把平台事件推进 mihomo 的日志流。那条流属于内核,而且进程一重启就
没了。本版撤销该桥,改成正确的方向——**把 mihomo 自己的日志行收进 AndroidCyaml 的诊断
日志**,也就是那份会轮转、能导出、重启后仍在的文件。

计数器只能说"发生了一场风暴";唯一能说清"在拨什么、命中哪条规则、走哪个出站"的,是内核
的日志行。此前它们只存在于面板的日志视图里,看过即忘。

- 新增 `core_log_capture.go`:有界环形缓冲,由日志泵填充,随每分钟的指标拉取一次性取走。
  不做逐行 JNI 上调——那会把一次上调放在 mihomo 的日志调用点上并阻塞内核,正是回调限流器
  存在的理由;
- `DiagnosticsSampler` 把取回的每一行单独写成一条 `core` 记录。一条 `sample` 事件塞进
  数千行既无法阅读,轮转时也会整窗丢弃而不是只丢最旧的部分;
- 环形缓冲上限 2048 行/窗口,超出多少以 `core.dropped` 明确记录,避免把被截断的窗口误读
  成安静的窗口。

## 日志开关决定保留多少

`AndroidCyamlSetLogCapture` 由日志级别驱动:

- `silent` / `error` / `warning`:只保留警告与错误;
- `info` / `debug`:额外保留每连接的行——正是这些行携带目的地与命中规则,也正是它们会在
  日常浏览中塞满缓冲。

拨号探针同样改为写入这个缓冲(标记为 `PROBE`),不再挂进 mihomo 的日志流。

## 撤销

- 移除 `AndroidCyamlLog` 导出、对应 JNI 桥与 `MihomoNative.log`;
- 移除 `DiagnosticsLog.append` 中向内核日志流的镜像;
- 随之移除 `[android] ` 前缀与分类器跳过——不再有平台行进入那条流,也就不再需要把它们从
  核心计数里排除。

# AndroidCyaml v1.0.52 发布说明

## 日志模式变成一条时间线

此前运行时的两半只能分别查看:核心的拨号失败在日志页,而 Android 侧知道的一切——网络
切换、TUN 与 protect 状态、内存与线程计数——在一个只能通过 adb 取出的独立文件里。要回答
"那场风暴是不是在路由切换时开始的",需要导出两份东西再按时间戳手工对齐。

本版打通了平台到核心日志流的桥:

- 新增 `AndroidCyamlLog` 导出与对应 JNI 桥,facade 早已提供的 `Infoln`/`Warnln` 至此才
  真正可用;
- `DiagnosticsLog.append` 是所有胶水事件的唯一汇聚点,每条事件在写入文件的同时镜像到核心
  日志流。文件仍保留完整记录供导出,日志页则是实时视图;
- 平台日志带 `[android] ` 前缀,警告/错误分类器据此跳过它们。没有这个标记,打开日志模式
  会让 Android 自己的事件计入 `coreWarnings`,使桶计数描述错误的东西。

## 拨号探针改为全量输出

探针此前把目的地编成合成的 `key=value` 指标名塞进诊断行。那条行是数值型的,每个目的地都
会变成一列永久存在的字段;而且它属于要被导出分享的文件。

现在探针改为写入核心日志,由日志级别开关控制,并且**输出整个窗口而非排名摘要**。截断过的
视图正是此前两次归因错误的成因:能解释一场风暴的地址不一定在前几名,而长尾恰恰是区分
"P2P 对等节点列表"和"单个失效 CDN"的依据。唯一的边界是 512 条容量上限,超出多少会明确
报告。安静的窗口不输出任何内容。

## 隐私

拨号探针记录目的地,只在诊断采样开启时收集,只输出到受日志级别控制的核心日志,并刻意不
进入诊断指标行——那份文件才是用来导出和分享的。

# AndroidCyaml v1.0.51 发布说明

## 修复:v1.0.50 的拨号探针没有输出

v1.0.50 引入的拨号探针在收集数据,但两处接线遗漏,导致它从不上报:

- `AndroidCyamlRuntimeMetrics` 没有调用 `platformDialProbe.counters`,统计因此进不了
  诊断采样;
- socket hook 没有调用 `observeAddress`,解析后地址一栏始终为空。

真机验证过:v1.0.50 采到 11 条样本,`dialFailDst.*` 等字段一条都没有出现。本版补上这
两处调用,探针字段方可出现在采样中。探针本身的逻辑与隐私说明不变,见 v1.0.50。

# AndroidCyaml v1.0.50 发布说明

## 拨号探针:让核心自己说出在拨谁

v1.0.49 的熔断器降低了失败的代价,但没有回答成因:**到底是什么在反复拨一个不应答的
地址**。此前只能从设备外读 `ss`,拿到的是解析后的 IP,再靠猜把 IP 对应到域名——两次
这样的推断都被证伪,因为 fake-ip 对任何域名都返回假地址,IP 数字对得上不构成因果。

mihomo 其实一直在记录所需的一切。它的拨号失败行是:

```
[TCP] dial OUTBOUND (match TYPE/PAYLOAD) SOURCE --> DEST error: REASON
```

`DEST` 是**客户端请求的名字**——域名,而不是它解析成的地址;`PAYLOAD` 是命中的规则。
`coreLogCounters.observe` 一直收到这些 payload,只是分类计数后把文本丢掉了。

本版新增有界统计,并在诊断采样中报告最忙的若干条:

- `dialFailDst.<域名:端口>` — 失败的目的地,按客户端请求的名字
- `dialFailRule.<类型/载荷>` — 命中的规则
- `dialFailOut.<出站>` — 走的出站
- `dialAddr.<IP:端口>` — socket hook 看到的解析后地址
- `dialProbeDistinct` / `dialProbeAddresses` / `dialProbeDropped` — 规模与截断情况

一条样本即可回答"哪个主机、被哪条规则匹配、走哪个出站"。统计上限 512 条,每条采样只
报告前 6 名,并在每次采样后清零,使每个窗口独立可归因——避免早期一次突发长期主导数据,
那正是之前把突发误读成稳态的原因。

## 隐私变化(请注意)

与其他桶计数不同,本探针**记录目的地**。它仅在诊断采样开启时填充(这是一个显式的用户
开关),且只输出前几名。**在开启采样的情况下导出诊断日志,即等于导出失败过的主机名。**
不需要这项归因时,关闭诊断采样即可。

## 实测状态

v1.0.49 的熔断器在真机上观测到:风暴复现(SYN 峰值 1213、CLOSE_WAIT 峰值 1511)后,
拨号量维持在 5000–6400/分钟的同时,超时从 1160/分钟降到 1/分钟。这与熔断器吸收失败的
形状一致,但相关性不等于因果——本版的探针正是为给出确证而加。

# AndroidCyaml v1.0.49 发布说明

## 拨号失败开始被记住

TUN 架构下,客户端无法判断一个目的地是死是活。本地握手由 TUN 栈立即完成——实测对一个
全球不可路由的地址 `connect()` 在 **1.2 毫秒**返回成功,五秒后才由真实拨号超时收场。
客户端因此认为连接已建立,它自己的连通性检测被完全蒙蔽,于是继续开新连接。实测抓到过
针对两个不应答地址的 **470 个并发半开 socket**,以及单进程累计 61,598 条拨号超时告警。

本版在拨号路径上引入熔断器:

- 连续 3 次**可达性失败**后,该目的地在 30 秒冷却期内直接失败,不再创建 socket;
- 冷却结束放行一次探测,成功即遗忘,失败则重新武装;
- 只对**耗满拨号预算**的失败计数(超时、host/net unreachable)。`ECONNREFUSED` 和
  `ECONNRESET` 是即时返回的廉价失败,通常意味着主机活着而服务没开,对它们熔断会掐掉
  本来能建立的连接;并发拨号中败者的 `context.Canceled` 更不是对目的地的判决——那是
  每一次成功连接都会发生的事;
- 熔断记录按 `directNetworkEnvironment` 作用域存放,切换网络时旧判决自然失效,不需要
  显式清理,也不会让某个目的地背上它从未在该网络上试过的判决。

对上述 470 socket 的场景:前 3 次仍付满超时,其余 232 条连接零成本失败,464 个 socket
不会被创建。

## DIRECT 地址竞速默认关闭

DIRECT 出站此前无条件启用地址竞速(`adapter/outbound/direct.go` 直接 append
`WithDirectDualStack()`,没有任何配置项)。该竞速对同一地址族内**所有** A 记录同时发起
连接,既没有 RFC 8305 的 Connection Attempt Delay,也没有并发上限;而赢家缓存只在成功时
写入,所以完全不应答的域名每次重试都要付全量扇出。

本版引入 `dialer.DirectRaceEnabled`,默认 `false`,在唯一调用点门控,使 DIRECT 恢复上游
逐个地址拨号的行为。竞速代码本身保留,等它学会错开候选后再打开。单元测试固定了两种行为:
关闭时一次只起 1 个连接,开启时 2 个。

注意 `tcp-concurrent` 开关管不到这条路径——关掉它反而会进入竞速分支。

## 已知仍未解决

反复拨号的**来源**尚未定位。观测到的目标是两个不应答的 CN 地址(117.185.17.235、
117.185.18.212,端口 80),但把它们归因到某个具体域名的推断经复核不成立,已撤回。本版
的熔断器缓解的是这类失败的**代价**,不是它的成因;成因仍需在风暴复现时抓取。

## 实测状态

补丁已通过完整构建与单元测试。熔断器在真机上的效果尚未验证——需要等待反复拨号复现。
改动前的基线:`coreErrTimeout` 61,598、单次抓样 SYN-SENT 峰值 4,337。

# AndroidCyaml v1.0.48 发布说明

## socket protect 回到 JNI，并给回调本身加上限

v1.0.38 把拨号热路径移出 JNI：Go 侧用 `SCM_RIGHTS` 把 socket fd 经 unix socket
交给 Java，由上限 8 个 worker 的线程池执行 `protect()`。目标是压住 OS thread 高
水位，选中的代理指标是"避免跨语言调用"。

这条路径没有准入控制。真机实测：某 CDN 域名的两条 A 记录同时被拨，`SYN-SENT` 里
一次堆出 470 个半开 socket；数百个并发拨号打爆 endpoint 的 listen backlog，Go 侧
只重试 3 次、退避 1ms+2ms（合计约 3ms）就判定传输失败，随后回退到 JNI 回调——而
**当时唯一挂着并发上限的恰恰只有那条回退路径**。诊断计数印证了这一点：

```
protectTransportErrors = protectJniFallbacks = protectJniCalls = 5456
```

三者完全相等，即整条链路被走满 5456 次。

真正需要约束的量是**同时进入 cgo 的调用数**——goroutine 阻塞在 cgo 期间独占其 OS
thread，限住并发即封顶线程数。这个机制本版本库早就有（`callbackLimiter`），只是没
用在主路径上。把上限加在回调本身之后，当初促使引入 unix socket 的问题即被解决，该
传输层随之成为多余：

- 移除 unix socket endpoint、`SocketProtectService` 与 JNI 侧的 endpoint 参数；
- `protect` 恢复为「取许可 → JNI 直调 → 计数」，`maxConcurrentPlatformCallbacks`
  由 16 收敛为 **8**，进程归属查询共享同一枚信号量；
- 净删 611 行，protect 路径的状态空间从十余个分支收敛到 3 个。

ClashMetaForAndroid 与 FlClash 采用的正是这一结构：一枚信号量加一次直接上调。

## protect 失败不再让拨号失败

原实现把 protect 失败转成拨号错误。高压下最先饱和的正是 protect，于是饱和 → 拨号
失败 → mihomo 重试 → 上层客户端重试 → 更多 protect 请求灌回已饱和的地方，形成拥塞
崩溃。这是一次慢速抖动演变成数千个半开 socket 的放大器。

本版与两个参考实现对齐：protect 的裁决只被计数，不改变拨号成败。未被 protect 的
socket 不会静默走错——它绕回 TUN 后被 loopback 检查拒绝，失败依然可见，只是表现为
一条连接失败，而不是一个正反馈环。

## 诊断字段变化

读日志的话注意字段增删：

- 移除 `protectJniCalls`、`protectJniRejections`、`protectJniFallbacks`、
  `protectTransportErrors`；
- 保留 `protectAttempts`，新增 `protectRejections`；
- 运行状态行的 `protect unix socket` / `protect JNI 回退` 统一为 `protect JNI`。

## 构建

`scripts/build_mihomo.sh` 的 NDK host 探测此前只接受 Linux 与 Darwin，Windows 上
直接退出。本版补上 `MINGW*|MSYS*|CYGWIN*` 分支，NDK 自带的 windows-x86_64 工具链
Go 可直接驱动，无需其他改动。

## 实测状态

本版的线程与内存对比数据尚未在设备上验证。改动前的基线为：`protectTransportErrors`
6456、goroutine 峰值 1748、`AndroidCyaml-pr` 线程 9 个（8 worker + 1 accept）。修复
生效后前两项应显著回落，`AndroidCyaml-pr` 线程应完全消失。

# AndroidCyaml v1.0.42 发布说明

## 系统默认切网与可观测状态

- 普通 mihomo 出站 socket 只执行 `VpnService.protect()`，不再绑定指定物理
  `Network`；Wi-Fi/移动数据双连时由 Android 系统网络评分决定新连接出口。
- 底层网络变化拆分为 route、DNS、IPv6、网络身份与 cache scope；IPv6
  变化只更新 mihomo IPv6 resolver/DNS 状态，不重建 TUN，不关闭 IPv4/代理连接。
- 切网观察、Selector 记忆和 direct cache scope 归入独立 `network` 组件；
  系统最佳出口与可用网络档案由两个被动 callback 分开观察。
- Android 进程归属查询失败后立即按未找到处理，不再落回 Linux
  procfs/inet_diag 路径。

诊断采样日志新增 `network.initial`、`network.transition`和
`network.handover`，记录 route/DNS/IPv6/identity/cache 变化位、网络类型、
IPv6 可用性和 DNS 数量，不记录 SSID、IP、DNS 地址或网络指纹。

# AndroidCyaml v1.0.41 发布说明

## 关掉采样后仍可导出

「导出诊断日志」原本只在开关打开时出现在菜单里。长时间采集的正常用法恰恰是先
关掉再把文件交出去，所以现在只要还有留存记录，无论开关状态都能导出。

# AndroidCyaml v1.0.40 发布说明

## 诊断采样：网络侧

上一版的采样只看内存。网络不稳定需要的是另一组信号，本版补齐，仍然遵守同一条
不耗电的约束：**不新增任何唤醒源**，高频信号一律做成计数器由每分钟那一行读走，
只有低频转变才各占一行。

新增的独立事件行：

- `net.available` / `net.lost`：底层网络出现与消失；
- `net.unusable`：网络还在，但已不满足做底层的条件（掉 validated、被 suspend 等）——
  这通常就是"断网"的真实时刻；
- `net.capabilities`：**只在关键位真的翻转时**才记（validated / notSuspended /
  notMetered / notRoaming / transport）。信号强度刷新一类的回调不会写进日志，
  否则弱网时一分钟几十行会把真正的转变淹掉；
- `network.refresh.failed`：切网后刷新 mihomo 失败；
- `runtime.ipv6.fallback`：IPv6 启动失败回退 IPv4。

每分钟采样行新增：

- 当前底层网络：handle、类型、IPv6 可用性、DNS 服务器数；
- 网络事件累计数：available / lost / unusable / capability / link / handover /
  refreshFailure；
- 拨号路径分层计数：`dialHookCalls`（新建出站 socket 数）、`protectAttempts`、
  `protectRejections`（VpnService 拒绝）、`protectTransportErrors`（端点坏了）、
  `protectJniCalls` / `protectJniRejections` / `protectJniFallbacks`、
  进程归属查询次数与未命中数；
- mihomo 自身告警/错误的分类计数：timeout / refused / unreachable / reset /
  dns / tls / closed / protect / other。

最后一项**只记分类计数，不抄原始日志文本**——mihomo 的错误消息里带着失败的目标
域名和地址，而这个文件是拿来导出分享的。要看原文，面板的日志页仍然是实时的。
分类器只在诊断采样开启时才订阅 core 的日志流：mihomo 每条日志无论级别都会发给
订阅者，且订阅者慢会阻塞调用点，所以默认不挂。

# AndroidCyaml v1.0.39 发布说明

## 诊断采样日志

RSS 持续增长这类问题只有长时间序列能定位，之前没有任何手段观察：mihomo 没有编进
pprof，`/memory` 端点只给瞬时值。本版加入可长期开启的采样日志。

右上角二级菜单里新增「诊断采样日志」开关，开启后菜单里出现「导出诊断日志」。

**不耗电是硬约束**：采样不使用 AlarmManager、JobScheduler 或 wake lock。
`Handler.postDelayed` 走的是 uptime 时钟，设备休眠时该时钟不推进，因此采样器
不可能唤醒 CPU——它在 doze 期间自动暂停，等设备因为别的原因醒来时才继续。

每分钟一条采样行，每行包含：

- 三个时钟：wall（墙钟）、boot（含休眠）、awake（不含休眠）。`boot - awake`
  就是设备休眠时长，所以序列中的空档可解释，不会被误读成内存跳变；
- 进程侧：`rssKb`、`swapKb`、`threads`、Java 堆已用/上限；
- `artAttachFloor`：存活线程中 `Thread-N` 的最大编号。ART 在 attach 时给线程命名，
  所以它能回答"还在不在发生 attach"，但高编号线程退出后不可见，只是下界；
- Go 运行时：`goTotal`（运行时映射总量，对应 `dumpsys meminfo` 的 `Unknown` 行）、
  堆的 objects/unused/free/released 拆分、goroutine 栈、`goGcLive`、GC 周期数、
  goroutine 数、`cgoCalls`（累计 cgo 调用数，可直接验证拨号路径是否还进 cgo）；
- mihomo：连接数、累计上下行字节。

Go 侧用 `runtime/metrics` 而不是 `runtime.ReadMemStats`，后者会 stop-the-world；
读取不加 `runtimeMu`，避免采样定时器等在启动流程后面。指标名在编译期由单元测试
校验，Go 版本改名不会变成一堆静默的空字段。

除了周期采样，以下低频事件也会各记一行：运行时状态变化、底层网络切换、内存压力
trim/kill；开启采样时还会把系统记录的历史进程退出原因（含被杀时的 PSS/RSS）
写入，这正是"被 low memory kill"的第一手证据。

日志写在 app 私有 `no_backup` 目录，两代各 2 MiB 轮转，约两周量。只记聚合计数，
不记域名、IP、SNI 或网络指纹——这个文件是拿来分享的。关闭开关后不再写入任何一行。

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
