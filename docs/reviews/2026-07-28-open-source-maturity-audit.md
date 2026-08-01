# MultiApp 开源成熟度审查 - 2026-07-28

## 执行摘要

本次审查基于 `README.md`、`ARCHITECTURE.md`、`docs/reviews/2026-07-09-open-source-engine-comparison.md`、
`docs/progress/2026-07-07-commercial-engine-progress.md`、`docs/current-repository-state.md`、
`docs/improvement-plan.md`，以及对 `core/`、`app/`、`feature/` 源码的探索。

**总体结论**：MultiApp 当前完成度约 **60%**，处于 `alpha / BLOCK` 状态。架构层已对齐
VirtualApp/BlackBox 风格（且在 generation-scoped capability、fail-closed、server/client 边界上
更严格），核心代码为工程级实现（engine/loader/hook 合计 80k+ 行 Kotlin + 12.8k 行 C++，测试
200+ 用例），但所有 P0 release-critical 能力仍为 `PARTIAL`，无 `PASS`，且缺少独立 `:engine` 进程
隔离与真机设备证据矩阵。距离开源成熟方案（VirtualApp/BlackBox 可发布级别）约 **6-8 个月
（26-34 周）**。

---

## 一、项目现状评估

### 1.1 代码规模与质量（强项）

| 模块 | 主源文件 | 测试文件 | 评估 |
|---|---|---|---|
| core:engine | 74 .kt (~36k 行) | 81 .kt | 已实现，控制面核心齐全 |
| core:loader | 120 .kt (~35k 行) | ~90 .kt | 已实现，bootstrap pipeline 完整 |
| core:hook | 41 .kt + 13 cpp/h | 14 .kt | 已实现，native-hook.cpp 12,845 行真实代码 |
| core:identity | 14 .kt | 2 .kt | 部分实现，深度依赖 hook |
| app/androidTest | - | 10 .kt | 5 个 HostedContainer instrumentation 测试 |
| feature (3 模块) | 10 .kt (~3.3k 行) | 0 | UI 薄层，无测试 |

- **TODO 密度极低**：全 core 源码仅 2 条真实 TODO（loader 的 ElfPatcher/ApkExtractor）。
- **无 NotImplementedError / notImplemented()**，`UnsupportedOperationException` 均为合法防御性抛出。
- `VirtualSystemServer` 中大量 "UNSUPPORTED" 是 `EngineResultStatus` 枚举与能力裁定集合，非占位符。
- 工程纪律良好：Hilt/KSP/Room/JaCoCo/Detekt 齐备，CI 双门禁（JVM + 矩阵 instrumentation）。

### 1.2 能力状态（弱项）

按 README 的 `PASS / PARTIAL / UNSUPPORTED / FAIL` 模型，当前 **17 个关键领域均未达 PASS**：

| 领域 | 状态 | 估算完成度 | 主要缺口 |
|---|---|---|---|
| 包与实例 | PARTIAL | 65% | 同包升级原子切换/回滚、包级回收未闭环 |
| Activity | PARTIAL | 55% | result/finish-result、onNewIntent、Recents 真机恢复 |
| Provider | PARTIAL | 50% | external URI grant、自定义进程、observer/FD |
| Service | PARTIAL | 45% | 跨进程、Binder death/rebind、foreground type、sticky |
| Broadcast | PARTIAL | 40% | ordered、sticky、result/abort、权限/AppOps、跨进程 |
| Permission | PARTIAL | 45% | runtime dialog、flags、one-time、auto-reset、shared UID |
| AppOps | PARTIAL | 35% | note/start/finish operation、attribution chain |
| Storage/Media | PARTIAL | 50% | external storage policy、MediaProvider 隔离 |
| Native | PARTIAL | 50% | 完整 syscall、linker namespace、RegisterNatives 真机 |
| Split(非隔离) | PARTIAL | 40% | dynamic feature、multidex、split native 设备闭环 |
| Isolated Split | UNSUPPORTED | 0% | loader 明确拒绝 |
| WebView | PARTIAL | 35% | 数据目录隔离、Chromium renderer/JNI、首帧证据 |
| Notification | PARTIAL | 25% | 实例 ID/channel/PendingIntent 映射、持久化、清理 |
| JobScheduler/Alarm/PendingIntent | UNSUPPORTED | 0% | capability catalog 默认 not-implemented |
| 独立 :engine 进程 | - | 30% | 本地 Binder 闭环，缺设备证据 + 完整语义 |
| 设备证据矩阵 | - | 15% | 仅模拟器 x86_64，缺真机 API 28-36 + 厂商 ROM |
| UI/产品化层 | - | 30% | feature 层薄，settings 仅静态数据 |

---

## 二、与开源成熟方案的差距分析

参考标杆：VirtualApp (`asLody/VirtualApp`)、BlackBox (`FBlackBox/BlackBox`)、DroidPlugin、
RePlugin。MultiApp 已在 `docs/reviews/2026-07-09-open-source-engine-comparison.md` 完成
pinned-source 对照。

### 2.1 架构对齐度（高，~85%）

MultiApp 的 server/client 边界、generation-scoped capability、fail-closed 恢复、单写入者权威
等设计 **比参考实现更严格**。已对齐的开源关键决策：

- 包/进程/任务/stub 分配真相归属虚拟 system server，client 不可重建第二权威。
- capability 绑定 instance + PID + processSlot + runtime epoch + engine session，一次性。
- 进程死亡/Recents 恢复采用同步 fail-closed，不复制 VA/BlackBox 的 message requeue。
- 删除走单 engine 命令，确认 PID 消失后才清理，保留 sibling 实例 artifact。

### 2.2 关键差距（核心阻塞项）

1. **独立 `:engine` 进程隔离未完成**
   - 当前 `:engine` 与宿主同进程或 Binder 共址；参考实现（VA `BinderProvider`、BlackBox
     `SystemCallProvider`）均在独立 `:p0/:server` 进程。
   - 缺：server 死亡/重连真机证据、generation-aware DeathRecipient 真机验证、server 重启后
     live guest state 失效验证。

2. **PMS 完整语义未闭环**
   - 缺完整 `PackageInfo`/`ApplicationInfo`/`ProviderInfo`/`ResolveInfo`/signing/permissions/
     disabled component state，以及 Android-grade `IntentFilter.match()`。
   - VA/BlackBox 通过中央 `IntentResolver` 而非独立组件侧字符串检查；MultiApp 已有 Android-free
     matcher 但未全链路验证。

3. **AMS 完整语义未闭环**
   - 缺 back stack、result、`onNewIntent`、Recents 真机恢复、launchMode fidelity、进程死亡任务重建。
   - 本地代码路径已闭合（`VirtualActivityLaunchRecovery` 等），但 API 28-36 + HyperOS 真机证据缺失。

4. **Provider/Service/Broadcast 数据面未闭环**
   - Provider 缺 URI grant、observer/notify、自定义进程、read/write 权限。
   - Service 缺 bind/unbind、Binder death/rebind、foreground service type、sticky restart。
   - Broadcast 缺 ordered、sticky、result/abort、权限/AppOps、跨进程路由。

5. **Native/Storage 真机结论缺失**
   - 缺完整 syscall 族覆盖、linker namespace、runtime nativeLoad、RegisterNatives 真机结论。
   - split native libs、protected-app diagnostics 未完成设备闭环。

6. **设备证据矩阵缺失**
   - 仅模拟器 x86_64（API 28/30/33/35/37）+ `:app:connectedDebugAndroidTest`。
   - 缺 arm32/arm64 真机、缺厂商 ROM（尤其 HyperOS）、缺关键应用兼容证据。

7. **应用兼容性结论不可声称**
   - 仓库含 360 加固/QQ/QQ阅读/QQ阅读兼容诊断代码，但这不等于已兼容。
   - 必须按 commit + APK SHA-256 + 设备 fingerprint + logcat + 首帧/交互证据绑定。

---

## 三、4 阶段执行计划

### 阶段 1 · P0 闭环（8-10 周）— 解除 BLOCK 基础

**目标**：建立独立 `:engine` 进程 + PMS/AMS 权威语义，使 release-critical 能力具备达 PASS 的
结构前提。

| 任务 | 交付物 | 验收标准 |
|---|---|---|
| 独立 `:engine` 进程隔离 | `:engine` 运行于 `${applicationId}:engine`，Binder 发布前完成 recovery | server 杀死/重连真机证据；generation-aware DeathRecipient；server 重启后 live guest state 失效 |
| PMS 权威语义闭环 | 完整 PackageInfo/ApplicationInfo/ProviderInfo/ResolveInfo/signing/permissions/disabled state；Android-free IntentFilter.match 全链路 | Activity/Provider/Service/Broadcast 派发均来自 engine；两实例同包不同 durable state |
| AMS 权威语义闭环 | 任务栈/result/onNewIntent/launchMode fidelity；进程死亡 Recents 重建 | fail-closed 恢复；API 28-36 真机无黑屏、最终 RUNNING 确认；owner-thread bootstrap watchdog |
| 移除迁移债务 | app/container 直接 loader/hook imports 全部走 engine facade | 源边界测试通过；无直接 owner-file 读取 |

**关键风险**：独立进程迁移可能暴露跨进程 Binder 序列化缺陷；AMS 真机恢复受厂商 ROM 任务清理策略影响。

### 阶段 2 · 能力闭环（8-10 周）— 虚拟组件完整语义

**目标**：Provider/Service/Broadcast/Native/Permission/AppOps 达到可验证的完整语义。

| 任务 | 交付物 | 验收标准 |
|---|---|---|
| Provider 数据面 | URI grant、observer/notify、自定义进程 Provider、read/write 权限 | custom-process Provider 报告 unsupported 直到 engine endpoint 存在；真实组件进程槽后发布 endpoint |
| Service 数据面 | bind/unbind、Binder death/rebind、foreground service type、sticky restart | AOSP-shaped IBinder→ConnectionRecord 索引 + death 清理；lease 绑定 generation/session |
| Broadcast 数据面 | ordered、sticky、result/abort、权限/AppOps、asUser、options、跨进程 | 显式/隐式 manifest 路由 + 完整语义；跨进程 Binder 路由验证 |
| Native/Storage | 完整 syscall 族、linker namespace、runtime nativeLoad、RegisterNatives 真机结论 | split native libs 设备闭环；protected-app diagnostics 不误报 |
| Permission/AppOps | runtime dialog、flags、one-time、auto-reset、shared UID；note/start/finish、attribution | 实例级隔离验证；guest fixture 断言关键字段 |

**前置**：阶段 1 完成（engine 进程 + PMS/AMS）。

### 阶段 3 · 设备证据矩阵（6-8 周）— 真机可发布验证

**目标**：在能力闭环基础上，产出覆盖 API + 厂商 ROM + 架构的真机证据，使能力可标记 PASS。

| 任务 | 交付物 | 验收标准 |
|---|---|---|
| API 真机矩阵 | API 28/30/33/35/36 hosted container 全链路 | 冷启动、首帧、交互、恢复证据；skipped=0 |
| 厂商 ROM 验证 | HyperOS/MIUI/EMUI/ColorOS | 厂商任务清理/后台限制下的恢复行为 |
| 架构验证 | arm32/arm64 真机（模拟器 x86_64 不可替代） | native hook、linker namespace 真机结论 |
| 关键应用兼容 profile | QQ/微信/QQ阅读作为 **profile 样本**（非 baseline 成功定义） | commit + APK SHA-256 + 设备 fingerprint + logcat + 首帧/交互绑定 |
| 证据归档 | 工具链版本、commit、设备属性、logcat、APK SHA-256 | 可追溯、可复现 |

**前置**：阶段 1-2 能力闭环。模拟器证据不可替代真机。

### 阶段 4 · 开源准备（4-6 周）— 可分发交付

**目标**：完成 License、UI、文档，发布首个可分发 release。

| 任务 | 交付物 | 验收标准 |
|---|---|---|
| License 与合规 | LICENSE（建议 Apache-2.0）+ NOTICE + CONTRIBUTING + 安全披露流程 | 明确再分发许可；第三方依赖声明完整 |
| UI 完善 | launcher/appmanager/settings 功能补全 + 单测覆盖 | feature 层测试覆盖 ≥ 60% |
| 文档 | README/架构/API/开发指南/兼容性矩阵 + 诊断开关收敛 | 文档完整、准确、可读 |
| 模块清理 | Legacy Stub/实验模块隔离或移除；诊断开关收敛 | baseline 不依赖 LSPlant/Xposed/proc spoof |
| 首个 release | release tag + 发布说明 + 兼容性矩阵 | P0 能力达 PASS；证据齐全 |

---

## 四、关键风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 独立进程迁移暴露 Binder 序列化缺陷 | 阶段 1 延期 | 先做序列化契约测试，再迁移；保留 fallback 共址模式用于调试 |
| AMS 真机恢复受厂商 ROM 任务清理影响 | Recents 黑屏/无法恢复 | 针对 HyperOS/MIUI 单独验证；watchdog + 回收路径 |
| Native syscall 覆盖不全导致加固应用崩溃 | 兼容性回归 | 按 profile allow-list 启用，baseline 不依赖；持续设备证据 |
| 真机设备获取受限 | 阶段 3 阻塞 | 优先 API 28/33/36 + HyperOS；arm64 优先于 arm32 |
| 应用兼容性工作偏离主线 | 资源分散 | 严守"兼容 profile 是样本，非 baseline 成功定义"原则 |
| QQ阅读等专项工作占用主线精力 | 阶段 1-2 延期 | 将专项兼容工作限制在 COMPAT_HOOK profile，baseline 不受影响 |

---

## 五、结论

MultiApp **不是占位骨架**，而是一个架构对齐度高、工程纪律良好的真实实现。当前阻塞商业/开源
成熟的根本原因不是代码缺失，而是：

1. 独立 `:engine` 进程隔离未完成（结构前提）。
2. P0 release-critical 能力全部 PARTIAL，无 PASS（语义闭环）。
3. 真机设备证据矩阵缺失（可发布验证）。

按 4 阶段计划推进，预计 **6-8 个月（26-34 周）** 可从 `alpha / BLOCK` 推进至首个可分发
release。阶段 1 是解除 BLOCK 的结构性前提，必须优先；阶段 3（真机证据）是 PASS 标记的硬门禁，
不可被模拟器或 JVM 测试替代。

**核心纪律**（沿用 README）：
- JVM test 不证明 Android framework 真实兼容性。
- skipped instrumentation test 不计为能力通过。
- 应用兼容结论必须绑定 commit + APK SHA-256 + 设备 fingerprint + logcat + 首帧/交互证据。
- 在 P0 release-critical 能力全部 PASS 前，项目状态保持 `alpha / BLOCK`。
