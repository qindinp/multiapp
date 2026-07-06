# v2 Container 成熟化执行蓝图

Date: 2026-06-29
Owner: hosted container runtime
Status: execution blueprint / context-preserving roadmap

## 0. 决策摘要

本项目后续成熟化路线是：参考 VirtualApp / BlackBox / DroidPlugin 等开源虚拟化与插件化项目的架构经验，对标商业多开产品的能力边界，但继续坚持当前仓内 v2 hosted user-space container 方向。

```text
目标：把 MultiApp 从 hosted-container MVP 推进到成熟的 App 级虚拟安装容器。

必须先完成：
1. 普通 App 多实例稳定运行。
2. ActivityThread / LoadedApk / PMS / AMS / Provider / Storage 的可证据化闭环。
3. Java 与 native storage/native namespace 的诊断或重定向证据。
4. protected App 只观察、不默认 patch 的 register-natives-only diagnostics。

不得宣称：
1. v2 container 已完成，除非七个 kernel gate 都有直接 evidence。
2. QQ Reader/protected apps 已兼容，除非有不依赖默认 LSPlant/Xposed/no-op/business wrappers 的 specific verdict。
```

本文件补充预先存在的七大 gate 源文档 [v2-seven-kernel-gap-execution-2026-06-29.md](v2-seven-kernel-gap-execution-2026-06-29.md)，用途是把“参考开源/商业方案，让本项目完善成熟”的目标拆成可执行 PR/阶段计划，并提供上下文恢复机制，防止后续 session 丢失决策背景。本文件不要求在 PR-1 中修改七大 gate 源文档。

## 1. 上下文恢复入口

新 session 或新 agent 接手时，按下列顺序恢复上下文，不要从零重新判断路线：

```text
0. 读项目 memory index：C:/Users/Administrator/.claude/projects/c--Users-Administrator-Desktop-1122-visual-app-multiapp/memory/MEMORY.md
   - 重点确认 ADB 路径、当前 blocker、代码审查先于 build 的项目约束。
   - memory 是背景线索；涉及文件/命令/状态的内容仍需在当前仓库中验证。
1. 读本文件：docs/container-runtime-refactor/v2-container-maturity-execution-blueprint-2026-06-29.md
2. 读七大 gate：docs/container-runtime-refactor/v2-seven-kernel-gap-execution-2026-06-29.md
3. 读权威路线：docs/multiapp-container-lsplant-roadmap.md 的 v2 当前权威技术路线
4. 读最新审查/整改：docs/container-runtime-refactor/v2-hosted-container-audit-remediation-2026-06-27.md
5. 读当前关键代码：
   - core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt
   - core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
   - core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityManager.kt
   - core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt
   - core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderDispatcher.kt
   - app/src/main/java/com/multiapp/app/container/ContainerActivity.kt
   - app/src/main/AndroidManifest.xml
```

### 1.1 冷启动任务简报模板

每个新 PR / 新 session 开始时复制以下 brief，并填写具体任务：

```text
Context:
- Branch: container-runtime-refactor
- Direction: v2 hosted user-space container, not legacy Stub clone APK as final runtime.
- Completion rule: class existence/JVM tests do not equal complete; gate completion requires unit tests + app build + device evidence/logcat/run-as evidence as applicable.
- Non-goals: no default LSPlant/Xposed, no business native stubs/wrappers, no no-op patches, no shell breaking/bypass.
- Current seven gates are PARTIAL unless this task adds direct evidence.

Task:
- Gate targeted:
- Files allowed/expected:
- Evidence fields to add or preserve:
- Tests to run:
- Device evidence required:
- Explicit out of scope:

Exit criteria:
- Code/doc updated.
- Tests/build listed with result.
- Evidence written or gap explicitly documented.
- No completion claim beyond evidence.
```

### 1.2 每个 PR 必须更新的上下文信息

每个执行 PR 必须在对应文档或 PR body 里留下以下信息：

```text
1. What changed
2. Which kernel gate it moves
3. Evidence added
4. Evidence still missing
5. Verification commands and results
6. Device/ADB status if applicable
7. Whether protected runtime remained hook-free by default
8. Follow-up task if still PARTIAL
```

如果一次变更没有新增直接 evidence，只能写“准备工作 / PARTIAL remains”，不能写“completed”。

## 2. 参考对象与采纳边界

### 2.1 开源参考对象

| Reference | 主要学习点 | 本项目采纳方式 | 禁止事项 |
| --- | --- | --- | --- |
| `asLody/VirtualApp` | VActivityManagerService、VPackageManagerService、stub components、Binder/server/client 分层、系统服务代理 | 学习 Virtual PMS/AMS/Provider/Storage 的分层与 evidence gate | 不直接拷贝 license 风险代码；不把旧实现照搬进 LoaderFactory |
| `FBlackBox/BlackBox` / `nnjun/BlackBox` | 免安装运行、虚拟进程、组件代理、可选 Xposed 扩展 | 学习产品化虚拟引擎的模块边界与兼容矩阵 | 不把 Xposed 当默认容器内核 |
| `DroidPluginTeam/DroidPlugin` | 插件化四大组件、AMS/PMS hook、stub Activity/Service/Provider 池 | 学习 Activity/Service/Provider 代理路径和兼容策略 | 不回退到“手工 new Activity + onCreate”路线 |
| `tiann/understand-plugin-framework` / VirtualApp docs | 原理解释与学习材料 | 用于解释复杂 Framework 适配点 | 不作为生产实现来源 |

### 2.2 商业方案对标能力

商业多开/分身产品只作为能力对标，不推断其内部实现。商业 benchmark 的含义只能是：成熟多开产品的用户会期待这些**可观测结果**；MultiApp 是否成熟仍必须由本项目自己的 evidence 证明。

| Product maturity area | Observable commercial expectation | MultiApp measurable criterion | Evidence required before claiming mature |
| --- | --- | --- | --- |
| Instance identity | 用户能区分每个分身实例。 | `instanceId`、origin package、virtual package、label/icon source、data root 都能在 UI/evidence/export 中定位。 | UI/evidence/export 同时包含 instance identity；双实例不会混淆。 |
| Create/clone latency | 创建分身耗时可接受，失败不会留下坏实例。 | 记录 `createLatencyMs`，从用户操作到 durable instance record + launcher entry。 | install/instance record 存在；失败路径包含 stage 和 cleanup status。 |
| Cold launch latency | 首次启动有可量化指标。 | 记录 `coldLaunchLatencyMs`，从点击到 guest first resume/visible marker。 | evidence 包含 activity、device/API/ABI、LoadedApk/context 字段。 |
| Warm launch latency | 二次启动复用 runtime，不重复初始化。 | 记录 `warmLaunchLatencyMs`、runtime reuse marker、Application create count。 | warm launch evidence 证明未无意义重复创建 guest Application。 |
| Crash-free launch rate | 稳定性以 app category 矩阵表达。 | 每个 compatibility row 记录 attempts/successes/crashes。 | crash-free rate 有样本、设备和 evidence 链接。 |
| Data isolation | 同源两个实例数据不串。 | files/prefs/db/provider/external/native paths 分别记录 isolation verdict。 | 双实例 write/read probe 证明互不串，或明确 UNSUPPORTED。 |
| Provider/storage isolation | Provider 和 storage 都按 instance routing。 | Provider method + path evidence 包含 original/routed URI/path 和 instanceId。 | query/insert/update/delete/call/openFile 与 storage evidence 均可追踪。 |
| Delete/cleanup correctness | 删除实例安全、完整、只影响目标实例。 | 删除前后记录 dataRoot、records、runtime cache、sibling instance 状态。 | 被删实例 dataRoot 不存在；兄弟实例仍可启动并保留数据。 |
| Diagnostics export | 用户/支持能导出诊断信息。 | export 包含 app/instance/device/stage/failure/evidence/protected-profile flags。 | 有导出文件路径，且不泄露无关隐私/secret。 |
| User-facing failures | 启动失败有可理解错误。 | 每个失败有 `failureCode`、`userMessage`、`technicalReason`、failed stage。 | UI/log evidence 不再只有 silent exit/generic crash。 |
| Compatibility matrix | 成熟度按矩阵，不按单个目标 App。 | minimal、ordinary、native、provider、service/broadcast、WebView、protected sample 分类别。 | 每行有 PASS/PARTIAL/FAIL/UNSUPPORTED 和 evidence 链接。 |

标准 evidence 字段建议：

```text
instanceId
originPackageName
virtualPackageName
instanceLabel
instanceIconSource
dataRoot
createLatencyMs
coldLaunchLatencyMs
warmLaunchLatencyMs
launchAttemptId
launchVerdict=<PASS|PARTIAL|FAIL|UNSUPPORTED>
failureCode
userMessage
technicalReason
failedStage
crashFreeLaunchRate
dataIsolationVerdict
cleanupVerdict
diagnosticsExportPath
deviceModel
androidApi
abi
pageSize
protectedProfileEnabled=false
lsplantEnabled=false
xposedEnabled=false
businessNativeStubsEnabled=false
businessNativeWrappersEnabled=false
noOpPatchesEnabled=false
```

商业方案能证明“容器化兼容加固 App 是现实工程方向”，但不能作为绕壳、破壳、默认 patch 的理由。

## 3. 成熟化原则

### 3.1 普通 App 先于 protected App

执行顺序必须是：

```text
minimal test app
-> ordinary real apps
-> ordinary apps with Provider/Service/Broadcast
-> ordinary native-lib apps
-> protected app observe-only diagnostics
-> QQ Reader/360 shell specific verdict
```

如果普通 App 的 Activity/PMS/Storage/native namespace 没闭环，QQ Reader failure 不能可靠归因。

### 3.2 容器内核先于 Hook 扩展

LSPlant/Xposed 只能作为 optional extension，不得承担下列容器内核职责：

```text
PMS virtualization
AMS/component dispatch
Provider routing
Storage redirection
nativeLibraryDir/native namespace correctness
LoadedApk/ActivityThread restoration
```

### 3.3 Evidence-first 完成口径

```text
DONE      = production path + deterministic tests + device/e2e evidence meet the gate.
PARTIAL+  = substantial implementation + unit/build verification, but device/e2e or full integration missing.
PARTIAL   = skeleton/MVP/local/JVM-only/fake-evidence, or docs explicitly say incomplete.
NOT PROVEN = goal exists in docs but current code/evidence cannot prove it.
```

## 4. 执行阶段

本节的 `Phase 0-10` 是 PR/workstream 切片，用于执行调度；它不替代 [docs/multiapp-container-lsplant-roadmap.md](../multiapp-container-lsplant-roadmap.md) 中的权威 v2 Phase A-G 编号。执行、汇报和 PR body 应同时引用两套编号：`Blueprint Phase N` 用于工作切片，`Roadmap Phase A-G` 用于路线归属。

| Blueprint phase | Roadmap phase | Meaning |
| --- | --- | --- |
| Phase 0 Reference architecture mapping | Phase A/G support | 冻结方向、建立对照和回归视角 |
| Phase 1 Freeze legacy Stub route | Phase A | 冻结 Stub transitional container 功能增长 |
| Phase 2 VirtualInstall / VirtualInstance source of truth | Phase B | 接入真实创建与实例事实源 |
| Phase 3 RuntimeBootstrap stage pipeline | Phase C | RuntimeBootstrap stage 化 |
| Phase 4 ActivityThread + LoadedApk sandbox closure | Phase D | ActivityThread/LoadedApk 运行时内核 |
| Phase 5 Virtual PMS global proxy | Phase D | Virtual PMS |
| Phase 6 Virtual AMS component dispatcher | Phase D | Virtual AMS/component dispatch |
| Phase 7 Provider dispatcher completion | Phase D | Virtual Provider |
| Phase 8 Java + native storage redirect | Phase D | Virtual Storage/native namespace evidence |
| Phase 9 Protected app register-natives-only diagnostics | Phase E | NativeDiagnosticsProfile，只诊断不 patch |
| Phase 10 Product maturity baseline | Phase G | 兼容矩阵、产品体验和回归测试 |

### Phase 0: Reference architecture mapping

目标：建立 VirtualApp / BlackBox / DroidPlugin 到本项目模块的映射，避免后续 agent 重新搜索和误判。

Tasks:

```text
1. 新建/更新 reference architecture mapping 文档。
2. 映射以下能力：
   - VPackageManagerService -> VirtualPackageService / VirtualPackageManagerWrapper
   - VActivityManagerService -> VirtualActivityManager / VirtualInstrumentation / future VirtualAmsDispatcher
   - ContentProvider proxy -> StubContentProvider / VirtualProviderDispatcher
   - Storage redirect -> VirtualContextStorage / future native redirect
   - LoadedApk/AppThread -> ActivityThreadLoadedApkInstaller / LoadedApkBridge
   - Process runtime -> VirtualProcessRuntime
   - Stub component pool -> ProxyActivityRegistry / StubService / StubContentProvider
3. 标注 license/直接复制风险。
```

Exit criteria:

```text
- 新 agent 能从映射文档知道该借鉴什么、不借鉴什么。
- 不改变 runtime 行为。
```

### Phase 1: Freeze legacy Stub route

目标：把 Stub clone APK + LoaderFactory 固定为 legacy evidence collector / 对照组，不再扩展为 v2 内核。

Tasks:

```text
1. 标注 legacy stub path: TRANSITIONAL_STUB_CONTAINER / NOT_V2_DEFAULT。
2. 确认 v2 新实例不调用 StubBuilder.build()。
3. QQ Reader legacy hooks 只能作为 manual diagnostic / legacy comparison。
4. 更新过时注释，例如 provider/service manifest 注释。
```

Exit criteria:

```text
- 默认 v2 hosted launch 不生成 stub APK。
- 默认 v2 hosted launch 不启用 QQReader special hook。
- 文档与 manifest/comment 不再冲突。
```

### PR-2 comment-only boundary

PR-2 may document or comment the expected freeze state, but should not implement or verify runtime routing changes unless deliberately expanded beyond comment cleanup.

For docs/comment-only PR-2, exit means:

```text
- legacy stub path is clearly labeled TRANSITIONAL_STUB_CONTAINER / NOT_V2_DEFAULT;
- QQ Reader legacy hooks are described only as manual diagnostics / legacy comparison;
- stale provider/service/manifest comments no longer contradict current v2 hosted-container direction;
- no runtime behavior, manifest semantics, Gradle config, hook defaults, or evidence-gate statuses changed.
```

Owner scope note: [v2-pr2-legacy-freeze-comment-cleanup-2026-06-29.md](v2-pr2-legacy-freeze-comment-cleanup-2026-06-29.md) records that comment cleanup must be split from existing runtime diffs before PR-2 is approved as clean comment-only work.

### Phase 2: VirtualInstall / VirtualInstance as source of truth

目标：创建、启动、删除实例都以 install/instance record 为唯一事实源。

Tasks:

```text
1. 补 VirtualInstallService 端到端测试。
2. 创建 instance 时确保 InstallRecord 已存在。
3. 记录并验证 originApkPath、sha256、cert、version、activities/services/receivers/providers、native libs。
4. 删除 instance 时清理 dataRoot、runtime cache、activity/service/provider/broadcast records。
```

Exit criteria:

```text
UI select installed app
-> ensure InstallRecord
-> create VirtualInstanceRecord
-> launch by instanceId
-> delete cleans instance dataRoot
```

Owner evidence note: [v2-pr3-install-instance-jvm-evidence-2026-06-29.md](v2-pr3-install-instance-jvm-evidence-2026-06-29.md) records deterministic JVM coverage for the InstallRecord -> InstanceRecord -> HostedRuntimeBootstrap fact-source chain. Device/androidTest evidence remains pending and must not be claimed from PR-3 alone.

### Phase 3: RuntimeBootstrap stage pipeline

目标：把 `HostedRuntimeBootstrap.run()` 从大函数推进到可测试 stage pipeline。

Stages:

```text
ConfigStage
InstallRecordStage
OriginApkStage
NativeLibrariesStage
PackageSnapshotStage
ClassLoaderStage
ResourcesStage
ProviderRoutingStage
ApplicationStage
LauncherActivityStage
DiagnosticsStage
```

Exit criteria:

```text
- 每个 stage 有 deterministic unit tests。
- irreversible stage 失败后不假装 rollback 完整成功。
- evidence 能显示 stage input/output/failure reason。
```

Owner plan note: [v2-pr4-runtimebootstrap-stage-pipeline-plan-2026-06-29.md](v2-pr4-runtimebootstrap-stage-pipeline-plan-2026-06-29.md) defines the PR-4 role split, extraction order, stage contract target, review frequency rule, and JVM-only verification path.

### Phase 4: ActivityThread + LoadedApk sandbox closure

目标：跑通成熟容器的关键 Activity gate。

Required launch path:

```text
guest startActivity
-> resolve guest ActivityInfo
-> allocate ProxyActivity
-> system launches ProxyActivity
-> Instrumentation.newActivity substitutes guest Activity
-> ActivityThread record patched
-> LoadedApk sandbox installed
-> guest context/application/resources/classloader injected before onCreate
```

Minimum device evidence:

```text
activityRecordPatchedFields=activityInfo,intent,packageInfo
loadedApkSource=GUEST_SANDBOX
loadedApkInstalledAliasCount>=2
contextInjected=true
activityInfo.packageName=<virtualPackageName>
applicationInfo.packageName=<virtualPackageName>
dataDir=<instance dataRoot>
```

Exit criteria:

```text
- minimal app first screen visible.
- Activity A -> B -> Back works.
- onNewIntent evidence exists.
- Activity result baseline exists or unsupported reason is explicit.
```

### Phase 5: Virtual PMS global proxy

目标：从 Context wrapper 级 PMS 推进到全局 PMS proxy。

Tasks:

```text
1. VirtualPackageService 成为 snapshot-backed package truth。
2. Cover PackageManager APIs:
   - getPackageInfo
   - getApplicationInfo
   - getActivityInfo
   - getServiceInfo
   - getProviderInfo
   - queryIntentActivities
   - queryIntentServices
   - resolveActivity
   - resolveService
   - checkPermission
   - getPackagesForUid / getNameForUid minimum behavior
3. Hook/patch:
   - AppGlobals/IPackageManager
   - ActivityThread.sPackageManager
   - ApplicationPackageManager.mPM
```

Exit criteria:

```text
guest self package queries return virtual snapshot data from both Context PM and global PM paths.
```

### Phase 6: Virtual AMS component dispatcher

目标：统一 Activity/Service/Broadcast 调度，不再散落在局部 manager。

Tasks:

```text
1. 新增 VirtualAmsDispatcher contract。
2. 统一 startActivity/startService/stopService/sendBroadcast/registerReceiver evidence。
3. 接管 Context 和 Instrumentation 入口。
4. 后续再评估 IActivityTaskManager/IActivityManager proxy。
```

Exit criteria:

```text
Activity, Service, Broadcast dispatch share one evidence contract.
Fallback must be explicit; no silent fallback.
```

### Phase 7: Provider dispatcher completion

目标：从 Provider MVP 到 provider method coverage。

Tasks:

```text
1. Add openFile/openAssetFile/bulkInsert coverage or explicit unsupported evidence。
2. Authority rewrite goes through VirtualContentResolver。
3. Add provider permission/exported/grant URI minimum policy。
4. Instance-scoped provider routing; no ordinary baseline process-wide hook。
```

Exit criteria:

```text
query/insert/update/delete/call/openFile all produce dispatcher evidence.
```

### Phase 8: Java + native storage redirect

目标：Java Context storage 与 native path 行为都能归入 instance dataRoot 或输出明确 unsupported evidence。

Tasks:

```text
1. Java absolute path diagnostics/rewrite for:
   - /data/data/<originPackage>
   - /data/user/0/<originPackage>
   - /sdcard/Android/data/<originPackage>
   - /storage/emulated/0/Android/data/<originPackage>
2. Native IO diagnostics for:
   - open/openat/stat/access/fopen/realpath
3. Record originalPath, redirectedPath, instanceId, caller if available。
```

Exit criteria:

```text
ordinary native file demo does not cross-write between instances, or unsupported gap is explicit.
```

### Phase 9: Protected app register-natives-only diagnostics

目标：让 QQ Reader/360 shell failure 输出 specific verdict，不默认 patch。

Required gates:

```text
lsplantEnabled=false
xposedEnabled=false
businessNativeStubsEnabled=false
businessNativeWrappersEnabled=false
noOpPatchesEnabled=false
```

Verdicts:

```text
nativeLoadVerdict
jniOnLoadVerdict
findClassVerdict
registerNativesVerdict
interface20Verdict
namespaceVerdict
classLoaderVerdict
```

Exit criteria:

```text
QQ Reader hosted diagnostics produces interface20Verdict=<specific verdict>, not generic crash.
```

### Phase 10: Product maturity baseline

目标：把技术容器变成可维护产品。

Tasks:

```text
1. Compatibility matrix:
   - minimal app
   - ordinary Java/Kotlin app
   - native-lib app
   - provider app
   - service/broadcast app
   - WebView app
   - protected sample
2. Product experience:
   - instance icon/label
   - notification/recent task identity
   - shortcut if supported
   - delete cleanup
   - user-facing launch failure UI
   - diagnostics export
3. Metrics:
   - create latency
   - cold/warm launch latency
   - crash-free launch rate
   - data isolation pass rate
```

Minimum product metric rows:

| Benchmark dimension | Minimum MultiApp metric | Mature target wording |
| --- | --- | --- |
| Instance identity | `instanceId`, origin package, virtual package, label/icon source, data root appear in UI/evidence/export. | User can distinguish and support can diagnose each clone without guessing which instance ran. |
| Create latency | `createLatencyMs` from user action to durable instance record and launcher entry. | Instance creation is bounded, measured, and failure-safe. |
| Cold launch latency | `coldLaunchLatencyMs` from tap to guest first resume/visible marker. | First launch performance is tracked by app category/device, not anecdotal. |
| Warm launch latency | `warmLaunchLatencyMs` plus runtime reuse/application-create-count evidence. | Relaunch avoids unnecessary duplicate guest runtime creation. |
| Crash-free launch rate | `successfulLaunches / attemptedLaunches` per compatibility-matrix row. | Ordinary app stability is expressed as a reproducible matrix. |
| Data isolation pass rate | `isolationPasses / isolationChecks` across files, prefs, DB/provider, external paths, native paths where supported. | Two instances cannot cross-read/write app data unless explicitly allowed. |
| Cleanup correctness | Delete evidence verifies selected instance records/data/cache removed and sibling instance preserved. | Deleting a clone is safe, complete, and scoped. |
| Diagnostics export | Export includes instance/app/device/stage/failure/evidence/protected-profile flags. | Support can triage without relying only on adb/logcat. |
| User-facing failure quality | Every failed launch has stable `failureCode`, user message, technical reason, and failed stage. | Failures are actionable, not silent or generic. |

Exit criteria:

```text
ordinary app matrix is reproducible; protected app diagnostics are evidence-driven.
```

## 5. Recommended PR graph

```text
Batch 1: docs/source-of-truth
  PR-1 reference architecture mapping
  PR-2 legacy stub freeze + stale comment cleanup
  PR-3 install/instance E2E tests (JVM evidence documented; device evidence pending)

Batch 2: core runtime
  PR-4 bootstrap stage refactor (planned in v2-pr4-runtimebootstrap-stage-pipeline-plan-2026-06-29.md)
  PR-5 LoadedApk sandbox device evidence
  PR-6 Activity lifecycle baseline

Batch 3: virtual services
  PR-7 Virtual PMS global proxy
  PR-8 Virtual AMS dispatcher
  PR-9 Provider method coverage
  PR-10 Storage + native IO diagnostics

Batch 4: diagnostics/product
  PR-11 register-natives-only diagnostics
  PR-12 ordinary app compatibility matrix
  PR-13 product experience baseline
```

Dependency notes:

```text
PR-1 can start immediately.
PR-2 can run in parallel with PR-1.
PR-3 should happen before deeper runtime claims.
PR-4 should precede PR-5/6 for cleaner evidence.
PR-5 and PR-6 are tightly coupled and should be reviewed together.
PR-7/8/9/10 can be split after PR-4, but device evidence should be owner-gated.
PR-11 must wait until ordinary app baseline is stable enough to make protected failure meaningful.
```

## 6. Fixed verification commands

Use Windows-friendly commands from repo root:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

When app integration changes:

```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

Device evidence checklist:

```text
install latest app-debug.apk
launch hosted ordinary instance from MultiApp
capture 15s logcat
collect hosted_launch_evidence
run-as inspect dataRoot
record package/version/device/API/ABI/page-size if relevant
```

QQ Reader diagnostics checklist:

```powershell
.\tools\qqreader-baseline\run-hosted-diagnostics-capture.ps1 -Device <serial> -InstanceId <instance-id>
```

This command is valid only after the user manually creates and launches a hosted QQ Reader instance.

## 7. Anti-overclaim rules

Allowed wording:

```text
implemented MVP
added evidence path
unit-tested bridge behavior
device evidence pending
PARTIAL remains
ordinary-app baseline passed on <device>
protected diagnostics produced <specific verdict>
```

Disallowed wording unless every evidence gate is satisfied:

```text
container complete
QQ Reader fixed
protected apps compatible
Virtual PMS complete
Virtual AMS complete
native redirect complete
LoadedApk sandbox complete
```

## 8. Current next action

Recommended immediate next action:

```text
PR-1: Reference Architecture Mapping + Current-State Refresh
```

Scope:

```text
1. Add reference architecture mapping doc.
2. Update docs index references.
3. Merge 2026-06-27 resolved items with 2026-06-29 still-PARTIAL gates.
4. Identify stale manifest/source comments, but defer comment cleanup to PR-2 unless it can be isolated from runtime/app manifest attribute changes.
5. No runtime behavior change.
```

Why first:

```text
It prevents context loss, reduces repeated research, and gives later code PRs a stable evidence contract.
```
