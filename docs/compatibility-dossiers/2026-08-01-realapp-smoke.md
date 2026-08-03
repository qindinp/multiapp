# 真实应用兼容性冒烟基线（2026-08-01）

> 首份设备基线数据：popsicle（API 36 / HyperOS / arm64），host=com.multiapp.app hosted flavor
> 测试：`RealAppCompatibilitySmokeTest`（诊断语义，不断言成功）
> 流程：import（engine createInstance）→ launchInstance → 20s evidence 窗口 → deleteInstance 清理

## 结果总览

| 应用 | 特征 | 导入(install) | 建实例(create) | 启动(launch) | evidence | 失败原因 |
|------|------|:---:|:---:|:---:|:---:|------|
| 微信 com.tencent.mm | 加固/大型(266MB) | ✅ PASS | ✅ PASS | ❌ FAIL | 0 | `authoritative runtime state missing`（STALE fail-closed） |
| 起点读书 com.qidian.QDReader | 阅文系/加固(115MB) | ✅ PASS | ✅ PASS | ❌ FAIL | 0 | 同上 |
| WPS cn.wps.moffice_eng | 办公(188MB) | ✅ PASS | ✅ PASS | ❌ FAIL | 0 | 同上 |
| 微博 com.sina.weibo | 社交(185MB) | ✅ PASS | ✅ PASS | ❌ FAIL | 3 | `Guest Application creation failed: Permission Denial` 打开自营 `AppMonitorProvider`（not exported，UID 不匹配） |
| 酷狗 com.kugou.android.lite | 轻量(73MB) | ✅ PASS | ✅ PASS | ✅ **BOOTSTRAPPED** | 3 | — |
| 高德 com.autonavi.minimap | so 密集(165MB) | ✅ PASS | ✅ PASS | ✅ **BOOTSTRAPPED** | 3 | — |
| Minimal fixture | 对照 | ✅ PASS | ✅ PASS | ✅ **BOOTSTRAPPED** | 3 | — |

**结论：7/7 导入全通（含微信 266MB 加固 APK），3/7 启动成功。** 导入层零障碍；启动层已对常规应用可用（含 so 密集的高德）。

## 根因分类（4 个失败项 → 2 类产品缺口）

### A. `authoritative runtime state missing`（微信/起点/WPS）— 3 项

- 来源：`EngineProcessBootstrapTransport.call` PREPARE 阶段（`EngineProcessBootstrapTransport.kt:754`）
- 机制：guest 进程（:vN）bootstrap PREPARE 时 `engineQueryRuntimeState(instanceId, runtimeEpoch, engineSessionId, processSlot, attachCapability)` 返回 null → fail-closed `STALE`
- 特征：与 Pr10 双启动（跨进程 runtime 状态同步）疑似同源——engine 进程与 host 进程的 runtime 状态不同步；但同轮测试中酷狗/高德能过，说明差异在**应用侧 bootstrap 路径**（加固壳抢先改写进程/类加载，导致 PREPARE envelope 的 runtimeEpoch/sessionId 与 engine 侧不一致）
- 待验证：engine 侧 `engineQueryRuntimeState` 的 miss 分支日志（当前无日志），对比加固/非加固应用的 bootstrap envelope 参数

### B. self-provider UID 隔离冲突（微博）— 1 项

- 证据：`Permission Denial: opening provider com.sina.weibo.appmonitor.provider.AppMonitorProvider from ProcessRecord{...:com.multiapp.app:v21/u0a473} (pid=16251, uid=10473) that is not exported from UID 10328`
- 机制：微博 guest Application 创建时尝试打开**自营非导出 provider**（AppMonitorProvider），hosted 模式下 guest 进程 UID=10473（host），系统按 UID 隔离拒绝（应为微博自身 UID 10328）
- 对应控制项：P0-COMPAT 系列 "Provider data plane satisfies whitelist use"（control-register.json:121）——**Provider data plane 虚拟化的真实缺口**，guest 需以 origin UID 语义访问 self-provider
- evidence=3 说明已走到 Guest Application 创建阶段（比 A 类更深入）

## 对控制项的意义

- **P0-COMPAT-01**：从 `6/6 BLOCKED - no APK` 推进为 **有设备基线数据**：导入层 7/7 PASS 已验证；启动层 3/7 PASS；失败 4 项均为已定位的产品缺口（2 类）
- 启动失败的 A/B 两类分别对应已登记的架构缺陷（跨进程 runtime 同步、Provider data plane）

## 方法备注

- 诊断式测试：不 assert 导入/启动成功，失败本身即兼容性数据；dossier 写 `filesDir/compat_dossier/YYYY-MM-DD.txt`
- 候选应用从设备 `pm list packages -3` 实选，覆盖：加固/大型、阅文系、社交、办公、轻量、so 密集 六种特征
- 每应用 `deleteInstance` 清理，无设备残留

---

## 2026-08-03 更新：A 类根因定位并修复（metadata 组件去重）

**A 类（`authoritative runtime state missing`）根因已定位并修复**，微信/起点/WPS 从 fail-closed STALE 推进到 Application 创建阶段。

### 根因链（多轮真机定位）

```
真实应用 manifest 同名 Activity 重复声明（微信 3 / 起点 2 / WPS 1）
  → metadata 导入时组件列表含重复项（PackageManager 与 ManifestParser 双路径均未去重）
  → EngineAuthoritativeRuntimeCodec.requireValidAuthoritativeSnapshot
    hasUniqueComponentNames 校验失败（Check failed）
  → EngineAuthoritativeRuntimeStream.open() encode 抛异常 → 返回 null
  → engineOpenRuntimeState 返回 null → IPC returned null
  → guest PREPARE "authoritative runtime state missing" → launchInstance FAIL
```

**关键事实**：系统安装时对同名组件去重保留其一（应用可正常安装运行），但 MultiApp 的 metadata 读取未做同语义去重 → snapshot 校验拒绝。**不是** Pr10 的跨进程 runtime 同步问题（已排除：engine 进程全程存活、FileBacked store、registry 单一、hook 不劫持）。

### 修复（commit 待定）

`AppModule.toComponentInfos` / `toInstallComponentInfos` / providers 路径 三处加 `distinctBy { it.name }`（与系统语义一致）。同时 Codec.kt 的 fail-closed check 加带原因 message（产品改进：snapshot 校验失败时输出具体组件重复名）。

### 修复后真机结果（popsicle API36）

| 应用 | 修复前 | 修复后 |
|------|--------|--------|
| 微信 | FAIL `authoritative runtime state missing` (evidence 0) | FAIL `bootstrap provider returned a malformed or stale response` (evidence 1) |
| 起点 | FAIL `authoritative runtime state missing` (0) | FAIL `LoadedApk.makeApplication failed: InvocationTargetException` (evidence 3) |
| WPS | FAIL `authoritative runtime state missing` (0) | FAIL `LoadedApk.makeApplication failed: InvocationTargetException` (evidence 3) |
| 微博 | FAIL provider denial | FAIL provider denial（同） |
| 酷狗/高德/minimal | BOOTSTRAPPED | BOOTSTRAPPED（同） |

**A 类已闭环**（不再是 fail-closed STALE）。微信/起点/WPS 现暴露**加固应用真实缺口**：guest Application 创建失败（`LoadedApk.makeApplication InvocationTargetException`，加固壳 Application 初始化）——这是加固兼容性的下一阶段挑战（W3+），微信的 `malformed or stale response` 疑为加固初始化超时。

### 调试方法论（可复用）

1. CompatDiag 分层日志：guest PREPARE 参数 → engine 入口 → 各 return null 分支 → 客户端 IPC 层（异常/descriptor null/校验失败）——穷举所有静默失败路径
2. 关键判别：engine 入口日志打但后续分支全不打 → 请求到达 handler 且走通校验 → 聚焦**最后一步无日志的返回**（`EngineAuthoritativeRuntimeStream.open` 的 `runCatching.getOrNull` 吞异常 + Timber 日志）
3. 教训：静默 `runCatching.getOrNull()` 是调试黑洞，fail-closed 路径应带原因 message

---

## 2026-08-03 B 类根因闭环：BASELINE lsplantEnabled=false 导致 ContentProviderHook 未安装

**B 类（self-provider UID 隔离）根因已定位**，微博的 UID 拒绝在 hook 启用后消除（验证已证明）。

### 根因链（真机多轮定位）

```
EngineProfile.BASELINE 的 lsplantEnabled=false（CompatibilityProfilePolicy.kt:41）
  → legacyProviderHookEnabled = providerRoutingEnabled(true) && lsplantEnabled(false) = false
  → HostedRuntimeBootstrap.providerHookInstallEnabled = false
  → ContentProviderHook 未安装（Skipped PROFILE_DISABLED）
  → guest 访问 origin 自身非导出 provider（AppMonitorProvider 等）不被 authority 重写
  → 系统 acquire 阶段 UID 检查：guest uid(10473) ≠ owner uid(10328) → 拒绝
```

**关键判别证据**：
- authorityMap 含 AppMonitorProvider（snapshot.providers 26 项含它，authority 解析正确）——排除 manifest 解析漏
- resolve null = 0（所有访问成功 resolve）——排除 resolve 匹配失败
- acquireProvider hook 的 beforeCallback 从未执行（ContentProviderHook 的 Java hook 未生效）——**hook 未安装**
- **BASELINE lsplant=true 验证**：微博从 `Permission Denial AppMonitorProvider` → `process slot draining previous generation`（**UID 拒绝已修复**，hook 生效）；酷狗/高德/minimal 仍 BOOTSTRAPPED（hook 对非加固应用无副作用）

### 修复方向（架构决策，待立项）

1. **BASELINE 默认启用 provider hook**（验证已证明安全，但 hook 全量启用是兼容性决策）
2. **COMPAT_HOOK profile + allowList 放行加固应用**（当前 allowList 空导致 COMPAT_HOOK 不可用，需配置入口）
3. 折中：按应用特征启用（manifest 含 not exported provider 的应用默认启用）

### 注意事项

- 微信/起点/WPS 与 B 类无关（native 壳 StubApp / RePlugin Tinker / GT 内部逻辑，是 C 类与内部逻辑）
- 微博的新问题 `process slot draining previous generation` 是 bootstrap 代际管理的独立问题（hook 安装后进程复用冲突），非 B 类核心
