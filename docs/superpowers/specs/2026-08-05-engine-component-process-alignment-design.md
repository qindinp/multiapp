# Engine Component Process Alignment Design

## 背景与问题

当前分身系统的组件进程（guest 自启子进程，如微信 sandbox、WPS RePlugin 插件进程）在真机上启动失败。失败模式已通过 Round 8 logcat 与 dossier 定性：

### 微信（com.tencent.mm）

- 主进程 v15 在 14:34:04.583 调用 `System.exit(1)`；engine 在 14:34:04.629 返回 `validatedResponse: response=null, slot=v15`；v15 主进程随后死亡。
- sandbox 子进程（`com.multiapp.app:v1`，StubServiceV1）在 14:34:04.834 才收到 `ExceptionMonitorService` 启动请求，attach 时注册表已空，报 `component_process_launch_capability_not_found`。
- engine 进程全程存活，未重启；注册表为单例共享（EngineBinderProvider 传入同一实例），排除「engine 被杀导致状态丢失」。

根因：一次性票据模型（`issue` → intent 携带 → 新进程 `consume`）依赖「调度方先于组件进程存活并持有票据」。guest 自启子进程由 AMS 直接拉起，不经过调度方，必然拿不到票据；主进程自杀又会触发 engine 回收该实例全部组件进程状态，形成第二次破坏。

### WPS（cn.wps.moffice_eng）

- `makeApplication` 失败：`InvocationTargetException`，根因是 `com.qihoo360.loader2.PluginProcessMain.l` 在 `linkToDeath` 上 NPE（binder 为 null）。
- WPS 使用 Tinker + RePlugin 双框架；RePlugin 依据进程名判断当前进程是否为 UI 进程。我们的进程名是 `com.multiapp.app:v13`，RePlugin 走插件进程分支，向 UI 进程要 PluginManager binder，binder 为空。

根因：组件进程未还原为 guest 原进程名。现有进程名 hook（MicroMsgProcessNameCompat）仅针对微信，WPS 无任何覆盖。

## 目标

- 微信 sandbox、WPS 组件进程在真机可达 BOOTSTRAPPED（`RealAppCompatibilitySmokeTest` 的 `outcome` 列为 BOOTSTRAPPED）。
- 其余 5 个候选应用（起点、微博、酷狗、高德、Minimal）不回归。
- 对齐开源 VirtualApp/BlackBox 的思路（server 统一下发凭证、进程自证、运行期进程名还原），但保持自研实现，不引入 GPL 代码。

非目标：
- 不整体重写组件进程路由（A 方案，渐进式）。
- 不实现 slot 分配表落盘持久化（列为边界外后续项）。
- 不实现 seccomp-bpf 兼容层（当前失败与 seccomp 无关，后续单独立项）。

## 架构设计

### 机制 1：凭证模型——从「一次性票据」改为「slot 分配表 + 自证认领」

现状：
- `EngineComponentProcessLaunchCapabilityRegistry` 维护 `issue`/`consume` 一次性票据，TTL 30 秒。
- 调度方 `prepareComponentProcess` 签发票据，塞入 `EXTRA_ENGINE_COMPONENT_PROCESS_LAUNCH_TICKET` intent extra。
- 新进程启动后 `query/consume` 认领。

改为：
- engine 维护每实例的 **slot 分配表**（slot → instance + effectiveGuestProcessName），这是长期状态，不是一次性消费品。
- 组件进程启动后**自证身份**：向 engine 声明「我在 slot vN，属于 instance X」，engine 校验三点：
  1. caller uid 是 host；
  2. `/proc/<pid>/cmdline` 进程名等于声明的 slot；
  3. slot 分配表中该 slot 属于该 instance。
- 通过即 attach（幂等，可重试）。

配套调整：
- `prepare` 保留但语义变为「分配并登记 slot」，不再签发一次性密钥；intent 里的 ticket 降级为可选加固（有则校验，无则走 slot 自证）。
- 主进程死亡时**不再立即清空**组件进程状态；已 attach 的组件进程继续存活，正在 attach 的在宽限窗口内仍可完成认领，孤儿进程由 engine 死亡监视统一回收。
- engine 重启后 slot 分配表会丢——先做「engine 存活期间幂等自证 + 宽限重试」，落盘持久化列为边界外后续项。

### 机制 2：进程名还原通用化

现状：
- 仅微信特判 hook（`MicroMsgProcessNameCompat`，hook 混淆类 `in5.f1.a()` 返回 `com.tencent.mm`）。
- WPS RePlugin 拿到 `com.multiapp.app:v13`，走错误分支。

改为：
- 组件进程 attach 拿到 `effectiveGuestProcessName` 后统一执行还原：
  - Java 层：hook 通用入口（`Application.getProcessName()`、`ActivityThread.currentProcessName()`、`Process.myProcessName()`、`ApplicationInfo.processName`），组件进程返回 guest 原名（如 `com.tencent.mm.sandbox.monitor`），主进程返回包名。
  - native 层：hook 读取 `/proc/self/cmdline` 的路径，返回 guest 原名（WPS RePlugin 若走 native 判断就有兜底）。
- 微信特判 hook 保留为兼容兜底（它绕过通用 getter 直接读混淆类）。

## 数据流

```text
host main 进程
  | startService(guest service)
  v
engine server (EngineBinderProvider/EngineServerRuntime)
  | prepare: 分配 slot 并登记 slot 分配表
  v
AMS 启动 StubServiceVn（进程 com.multiapp.app:vN）
  |
  +- 常规路径：intent 携带 ticket（可选）
  +- guest 自启路径：AMS 直接拉起，无 ticket
  v
组件进程 self-attach
  | 声明 (slot=vN, instance=X)
  v
engine 校验 uid + /proc/self/cmdline + slot 分配表
  +- 通过 -> attach（幂等）
  |        -> 进程名还原（Java + native）
  |        -> guest Application/Service 启动
  +- 失败 -> 拒绝并留证据
```

## 实施顺序

1. 诊断先行：给现有 exit 拦截加「总是打印调用栈」日志，复跑微信抓到看门狗自杀点，确认它盯的是进程名还是别的信号。
2. engine 侧：改造 registry → slot 分配表 + 自证 attach。
3. 组件进程侧：`HostedServiceRuntimeBinder` 支持无票据自证路径。
4. 进程名还原通用化（loader 新组件，替换/扩展微信特判）。
5. native cmdline 伪装。
6. 单测矩阵 + 设备复跑（微信/WPS 目标 BOOTSTRAPPED，其余 5 个不回归）。

## 测试策略

### 单元测试

- slot 自证的拒绝矩阵：uid/pid/cmdline/slot/instance 任一不匹配 -> 拒绝。
- revoke 语义变更：主进程死亡不再清空组件进程状态。
- 通用进程名还原各入口。

### 设备测试

- `RealAppCompatibilitySmokeTest` 全量 7 应用：
  - 微信、WPS 目标 BOOTSTRAPPED；
  - 起点、微博、酷狗、高德、Minimal 不回归（保持 BOOTSTRAPPED）。

## 边界与后续项

- slot 分配表落盘持久化（engine 重启后恢复）——后续项。
- seccomp-bpf 兼容层——后续独立立项。
- RePlugin 完整插件系统兼容（可能不止进程名问题）——以真机复跑结果为准，逐步暴露。
