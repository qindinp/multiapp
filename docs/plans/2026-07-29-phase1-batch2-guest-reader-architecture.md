# MultiApp Phase 1 Batch 2 架构设计：Guest Owner-File Reader 清除

> 日期：2026-07-29
> 作者：高见远（软件架构师）
> 状态：GO（最小批次）

---

## 0. 结论与 GO / NO-GO

**GO（有条件）**：删除 `VirtualInstrumentation.createHostedRuntime()` 中的 `Json*Store + DefaultInstanceManager` 本地 fallback，令 cache miss 路径统一走 `HostedRuntimeEngine` 权威 Binder 快照。

**NO-GO 条件**：
- 保留任何 `JsonInstanceRecordStore` / `JsonInstallRecordStore` / `DefaultInstanceManager` 在 guest/loader 生产代码中的构造
- 用跨进程文件锁替代删除非 engine reader
- 修改 AIDL 方法顺序或 owner store schema
- 碰 `JsonDirectoryLock`
- 在本批中同时重构 `AppModule` Hilt owner bindings（属 Batch 3）

**整体项目仍为 `alpha / BLOCK`。** 本批只收紧权威边界，不改变成熟度。

---

## 1. 当前路径分析

### 1.1 正确路径（`DefaultHostedRuntimeEngine`，已存在）

```
ContainerActivity / Provider / Service
  → HostedActivityRuntimeBinder / HostedProviderRuntimeBinder / HostedServiceRuntimeBinder
    → hostedRuntimeEngineFrom(context) → Hilt HostedRuntimeEngineEntryPoint
      → DefaultHostedRuntimeEngine
        → authoritativeRuntime(instanceId, processSlot, effectiveGuestProcessName)
          → EngineRuntimeIpcClients.engineQueryRuntimeState()  // Binder IPC → :engine
        → deriveHostedRuntimeView()  // 实例/代次/slot 校验
        → ReadOnlyRuntimeInstanceManager(runtime)  // 纯内存只读适配器
        → ReadOnlyRuntimeInstallRecordStore(installRecord)  // 纯内存只读适配器
        → HostedRuntimeBootstrap(...).run()
        → processRuntime.bindApplication(instanceId, fingerprint) { ... }  // VirtualProcessRuntime.global
```

特征：
- **只通过 Binder IPC 获取 `VirtualInstanceRuntime`**
- 不构造任何 `Json*Store`，不读 `filesDir/instances|installs`
- authority 不可用 → fail-closed（返回 `authorityFailureResult`）
- 使用 `HostedRuntimeBindingFingerprint` 做代次校验
- 与 VI 共享同一个 `VirtualProcessRuntime.global`（通过 `EngineHostedProcessRuntimeDefaults.loaderRuntime`）

### 1.2 违规路径（`VirtualInstrumentation.createHostedRuntime()`，需删除）

```kotlin
// VirtualInstrumentation.kt:1171-1227
private fun createHostedRuntime(instanceId: String): HostedActivityRuntime {
    val reusableResult = processRuntime.reusableResult(instanceId)
    // ... cache hit 直接返回 ...

    // ↓↓↓ cache miss — 违规路径 ↓↓↓
    val filesDir = hostApplication.filesDir
    val installRecordStore = JsonInstallRecordStore(File(filesDir, INSTALLS_DIR))     // ← 违规：直接读 owner files
    val instanceManager = DefaultInstanceManager(
        store = JsonInstanceRecordStore(File(filesDir, INSTANCES_DIR)),               // ← 违规：直接读 owner files
        dataRootBase = File(filesDir, INSTANCE_DATA_DIR),
        installRecordStore = installRecordStore
    )
    val bootstrap = HostedRuntimeBootstrap(
        instanceManager = instanceManager,                                            // ← 违规：非权威 store
        installRecordStore = installRecordStore,                                      // ← 违规：非权威 store
        hostContext = hostApplication,
        providerHookInstallEnabled = true,
        processRuntime = processRuntime,
        activityRecordManager = activityRecordManager,
        runtimePublisher = processRuntime::rememberApplication
    )
    val result = processRuntime.bindApplication(instanceId) {
        bootstrap.run(instanceId)                                                     // ← 违规：本地 bootstrap
    }
    // ...
}
```

**违规点精确位置**：

| 行号 | 违规内容 | 不变量违反 |
|------|----------|-----------|
| 22-24 | `import JsonInstanceRecordStore` / `import JsonInstallRecordStore` / `import DefaultInstanceManager` | guest 不得构造 owner store 类型 |
| 1201 | `JsonInstallRecordStore(File(filesDir, INSTALLS_DIR))` | guest 不得直接读 `filesDir/installs` |
| 1202-1206 | `DefaultInstanceManager(store = JsonInstanceRecordStore(...), ...)` | guest 不得构造 `DefaultInstanceManager` 或读 `filesDir/instances` |
| 1207-1215 | `HostedRuntimeBootstrap(instanceManager = instanceManager, installRecordStore = installRecordStore, ...)` | guest 不得使用非权威 store |
| 1216-1218 | `processRuntime.bindApplication(instanceId) { bootstrap.run(instanceId) }` | cache miss 应等待权威 bind，非本地 bootstrap |

### 1.3 调用关系：两个路径共享同一 `VirtualProcessRuntime.global`

```
VirtualProcessRuntime.global  ← 进程单例
    ↑ 共享 ↑
    |                                  |
DefaultHostedRuntimeEngine            VirtualInstrumentation
  runBootstrapLoader()                  createHostedRuntime()
  → processRuntime.bindApp()            → processRuntime.bindApp()
  (权威 fingerprint, IPC 快照)          (本地 store, 无 fingerprint)
```

**关键风险**：如果 VI 先 bind 并发布了非权威结果到 `VirtualProcessRuntime.global`，后续 `DefaultHostedRuntimeEngine` 的 fingerprint 校验会因代次不匹配而失败（`VirtualProcessRuntime` 拒绝替换已 READY 的不同 fingerprint runtime）。

---

## 2. 精确调用链

### 2.1 现有正确调用链（Engine bind）

```
EngineProcessBootstrapTransport
  → hostedRuntimeEngineFrom(hostContext)
    → DefaultHostedRuntimeEngine
      → EngineRuntimeIpcClients.engineQueryRuntimeState(instanceId, processSlot)
        → IEngineRuntimeService.engineOpenRuntimeState(instanceId, Bundle)
          → :engine 进程内 EngineServerRuntime
            → EngineRuntimeRegistry 查询
            → EngineAuthoritativeRuntimePayloadCodec 编码
            → 返回 ParcelFileDescriptor
        → EngineAuthoritativeRuntimeStream.read(descriptor)
      → VirtualInstanceRuntime.deriveHostedRuntimeView(expectedProcessSlot, effectiveGuestProcessName)
        → 实例 ID 校验
        → state 校验（!= STOPPED, != DEAD）
        → processSlot 校验
        → 组件进程名声明校验
      → ReadOnlyRuntimeInstanceManager / ReadOnlyRuntimeInstallRecordStore
      → HostedRuntimeBootstrap(...).run(instanceId, processSlot)
      → processRuntime.bindApplication(instanceId, fingerprint) { bootstrap }
```

### 2.2 现有违规调用链（VI local bootstrap）

```
VirtualInstrumentation
  → newActivity / callActivityOnCreate / execStartActivity
    → createHostedGuestActivity / remapStartActivityIntent / unmappedStartActivityBlock
      → createHostedRuntime(instanceId)
        → processRuntime.reusableResult(instanceId)  // 无 fingerprint
        → [cache miss]
        → JsonInstallRecordStore(filesDir/installs)   // 直接文件读
        → DefaultInstanceManager(JsonInstanceRecordStore(filesDir/instances), ...)
        → HostedRuntimeBootstrap(instanceManager, installRecordStore, ...)
          → instanceManager.getInstance(instanceId)   // 读 owner 文件
          → installRecordStore.load(packageName)      // 读 owner 文件
        → processRuntime.bindApplication(instanceId) { bootstrap.run(instanceId) }
          → [publishes to VirtualProcessRuntime.global WITHOUT fingerprint]
```

### 2.3 目标修复调用链

```
VirtualInstrumentation
  → createHostedRuntime(instanceId)
    → processRuntime.reusableResult(instanceId)  // 检查 engine 已发布的缓存
    → [cache miss]
    → throw ForegroundBootstrapBlockedException(instanceId)  // 或 fail-closed
```

---

## 3. 推荐方案：最小安全修改

### 3.1 核心修改：`VirtualInstrumentation.createHostedRuntime()`

**目标**：删除 cache miss 路径中的所有 owner store 构造和本地 bootstrap，替换为 fail-closed。

**具体修改**：

```kotlin
// 修改后：
private fun createHostedRuntime(instanceId: String): HostedActivityRuntime {
    val reusableResult = processRuntime.reusableResult(instanceId)
    hostedRuntimeCache[instanceId]?.let { cached ->
        if (canReuseHostedRuntimeCache(cached.result, reusableResult)) {
            return cached
        }
        hostedRuntimeCache.remove(instanceId, cached)
    }

    val hostApplication = resolveProcessHostContext()
    if (shouldBlockForegroundRuntimeBootstrap(isMainThread(), reusableResult != null)) {
        writeForegroundBootstrapBlockedEvidence(
            filesDir = hostApplication.filesDir,
            instanceId = instanceId,
            threadName = Thread.currentThread().name
        )
        throw ForegroundBootstrapBlockedException(instanceId)
    }
    if (reusableResult != null) {
        writeProtectedDiagnosticsEvidence(hostApplication.filesDir, reusableResult)
        require(reusableResult.success) {
            "Hosted bootstrap failed: " + (reusableResult.summary.failureReason ?: "unknown")
        }
        requireNotNull(reusableResult.guestClassLoader) { "Hosted bootstrap returned null guestClassLoader" }
        return HostedActivityRuntime(hostApplication, reusableResult).also {
            hostedRuntimeCache[instanceId] = it
        }
    }

    // ← 以下为原违规路径，全部删除，替换为 fail-closed
    writeRemapFailureEvidence(
        filesDir = hostApplication.filesDir,
        instanceId = instanceId,
        reason = "AUTHORITY_RUNTIME_UNAVAILABLE",
        api = "createHostedRuntime:cache-miss",
        requestCode = -1,
        error = IllegalStateException(
            "authoritative runtime unavailable for instance $instanceId; " +
                "guest local bootstrap has been removed"
        )
    )
    throw IllegalStateException(
        "authoritative runtime unavailable for instance $instanceId"
    )
}
```

### 3.2 删除的 import

```kotlin
// 删除以下 3 个 import：
import com.multiapp.core.model.instance.DefaultInstanceManager
import com.multiapp.core.model.instance.JsonInstanceRecordStore
import com.multiapp.core.model.installer.JsonInstallRecordStore
```

### 3.3 删除的常量

```kotlin
// 删除以下 3 个常量（如果不再被其他方法引用）：
private const val INSTANCES_DIR = "instances"
private const val INSTALLS_DIR = "installs"
private const val INSTANCE_DATA_DIR = "instance_data"
```

需要检查：`INSTANCE_DATA_DIR` 是否在 `buildVirtualContextConfig` 等其他地方使用。从当前代码看，`buildVirtualContextConfig` 使用 `result.dataRoot` 而非 `INSTANCE_DATA_DIR`，所以可以安全删除。

### 3.4 不修改的内容

| 不修改 | 原因 |
|--------|------|
| `HostedRuntimeEngine.kt` | 已经正确使用权威路径 |
| `EngineRuntimeInstallers.kt` | 不涉及 guest owner file 读取 |
| AIDL 方法顺序 | 不变 |
| owner store schema | 不变 |
| `JsonDirectoryLock` | 不碰 |
| `VirtualProcessRuntime` | 不变 |
| `HostedRuntimeBootstrap` | 不变（被 engine 路径正确使用） |

---

## 4. 影响分析

### 4.1 `VirtualInstrumentation` 中调用 `createHostedRuntime` 的位置

| 调用者方法 | 行号 | 场景 | 影响 |
|-----------|------|------|------|
| `remapStartActivityIntent` | 641 | Activity 启动重映射 | cache miss 时原来会本地 bootstrap → 现在抛异常 → `runCatching` 捕获 → 返回 null → 不重映射（降级为原始 intent） |
| `unmappedStartActivityBlock` | 567 | 未映射启动判断 | cache miss → `runCatching` 返回 null → 不阻塞 |
| `unmappedStartActivitiesBlock` | 589 | 批量未映射启动判断 | 同上 |

**行为变化**：
- **正常路径**（engine 已 prewarm）：无变化，cache hit 直接返回
- **异常路径**（engine 未 prewarm，race condition）：原来会读 owner 文件并本地 bootstrap → 现在 fail-closed → Activity remap 失败 → 容器可能显示原始 intent 或阻塞
- **这是正确行为**：没有权威快照时不应启动 guest runtime

### 4.2 `DefaultHostedRuntimeEngine` 对 `VirtualProcessRuntime.global` 的影响

删除 VI 的本地 bootstrap 后，`VirtualProcessRuntime.global` 中不会有非权威 fingerprint 的缓存记录。`DefaultHostedRuntimeEngine.runBootstrapLoader()` 的 `HostedRuntimeBootstrap` 调用仍会通过 `runtimePublisher` 将结果发布到 `VirtualProcessRuntime.global`（带 fingerprint）。

**结论**：无冲突，两个路径不再竞争同一个 `VirtualProcessRuntime.global` 记录。

### 4.3 对 container binder 的影响

`HostedActivityRuntimeBinder.ensureBound()` → `runtimeEngine.bindApplication()` → `DefaultHostedRuntimeEngine.bindApplication()` → 使用 fingerprint → `VirtualProcessRuntime.bindApplication(instanceId, fingerprint) { ... }`。

如果之前 VI 已经用无 fingerprint 的方式在 `VirtualProcessRuntime.global` 中注册了同一 instanceId，engine 路径的 fingerprint 校验会失败（`bindingFingerprintMismatch`）。

**删除 VI 本地 bootstrap 后**：此竞态消除。engine 路径是唯一发布者。

---

## 5. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| engine prewarm 延迟导致 guest Activity 首次启动 fail-closed | 容器 Activity 启动失败或降级 | engine bootstrap transport 已在 `EngineProcessBootstrapTransport` 中正确 prewarm；VI 只在极端 race condition 下 cache miss；容器 UI 应有重试/降级逻辑 |
| `shouldBlockForegroundRuntimeBootstrap` 主线程检测不变 | 仍会在主线程 cache miss 时抛异常 | 不变行为，删除本地 bootstrap 只是让非主线程也 fail-closed |
| VI 中仍有 `hostedRuntimeCache` 本地缓存 | 该缓存基于 engine 已发布的 `reusableResult`，不读 owner 文件 | 安全，不违反不变量 |
| 运行时 `evidence` 文件读写 | `writeRemapFailureEvidence` 等只写诊断证据文件，不读 owner store | 安全 |
| 未提交的 `JsonDirectoryLock` 工作树 | 可能与本批修改冲突 | 开始实现前先 `git stash` 或隔离工作树 |

---

## 6. 具体文件与符号修改点

### 6.1 生产文件

| 文件 | 修改内容 | 修改类型 |
|------|----------|----------|
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt` | 删除 3 个 import、删除 `createHostedRuntime()` 中 lines 1200-1226 的违规代码块、替换为 fail-closed、删除 3 个不再使用的 companion 常量 | 删除 + 替换 |

### 6.2 测试文件

| 文件 | 修改内容 |
|------|----------|
| `core/loader/src/test/java/com/multiapp/core/loader/DualInstanceBaselineTest.kt` | 检查是否依赖 VI 的本地 bootstrap 路径；如果是，更新为 mock engine authority |
| `core/loader/src/test/java/com/multiapp/core/loader/HostedRuntimeBootstrapTest.kt` | 不变（测试 `HostedRuntimeBootstrap` 本身，被 engine 路径使用） |
| `core/loader/src/test/java/com/multiapp/core/loader/VirtualInstrumentationApplicationContextTest.kt` | 检查是否触发 `createHostedRuntime` cache miss |
| `core/loader/src/test/java/com/multiapp/core/loader/VirtualInstrumentationStartActivityEvidenceTest.kt` | 检查是否依赖 VI 本地 bootstrap |
| `core/engine/src/test/java/com/multiapp/core/engine/EngineHostedProcessRuntimeTest.kt` | 不变 |
| `core/engine/src/test/java/com/multiapp/core/engine/HostedRuntimeEngineProcessViewTest.kt` | 不变 |
| `core/engine/src/test/java/com/multiapp/core/engine/EngineRuntimeInstallersTest.kt` | 不变 |
| `core/engine/src/test/java/com/multiapp/core/engine/EngineOwnerFileBoundaryTest.kt` | **新增断言**：生产 `VirtualInstrumentation.kt` 不得出现 `JsonInstanceRecordStore` / `JsonInstallRecordStore` / `DefaultInstanceManager` |

### 6.3 新增测试

| 文件 | 测试点 |
|------|--------|
| `core/loader/src/test/java/com/multiapp/core/loader/VirtualInstrumentationAuthorityBoundaryTest.kt`（新增） | 源码边界扫描：`VirtualInstrumentation.kt` 生产代码不得包含 `JsonInstanceRecordStore`、`JsonInstallRecordStore`、`DefaultInstanceManager`、`INSTANCES_DIR`、`INSTALLS_DIR` 引用 |

---

## 7. 测试点

### 7.1 必须通过的测试

| 测试类别 | 测试文件/命令 | 验证内容 |
|----------|--------------|----------|
| 源码边界 | `VirtualInstrumentationAuthorityBoundaryTest`（新增） | VI 生产代码不含 owner store 类型 |
| 源码边界 | `EngineOwnerFileBoundaryTest`（扩展） | engine owner store 仍是单点 writer |
| 现有 loader 测试 | `./gradlew :core:loader:testDebugUnitTest` | 不回归 |
| 现有 engine 测试 | `./gradlew :core:engine:testDebugUnitTest` | 不回归 |
| 编译 | `./gradlew :core:loader:compileDebugKotlin :core:engine:compileDebugKotlin` | Hilt 图无断裂 |
| 聚合 | `./gradlew :app:assembleDebug` | APK 构建通过 |

### 7.2 测试命令

```bash
export JAVA_HOME='C:/Users/20237/.cache/codex-runtimes/jdk-17'
export PATH="$JAVA_HOME/bin:$PATH"

# targeted 边界测试
./gradlew :core:loader:testDebugUnitTest --tests 'com.multiapp.core.loader.VirtualInstrumentationAuthorityBoundaryTest' --no-daemon
./gradlew :core:engine:testDebugUnitTest --tests 'com.multiapp.core.engine.EngineOwnerFileBoundaryTest' --no-daemon

# 模块全量
./gradlew :core:loader:testDebugUnitTest :core:engine:testDebugUnitTest --no-daemon

# 编译与聚合
./gradlew :core:loader:compileDebugKotlin :core:engine:compileDebugKotlin --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

### 7.3 边界复核（源码搜索）

```
VirtualInstrumentation.kt 生产代码中：
  禁止 JsonInstanceRecordStore
  禁止 JsonInstallRecordStore
  禁止 DefaultInstanceManager
  禁止 INSTANCES_DIR / INSTALLS_DIR（除非有其他非违规使用方）
```

---

## 8. 实现顺序

```
S0  隔离当前未提交 JsonDirectoryLock 工作树（git stash 或主理人决定）
    ↓
S1  修改 VirtualInstrumentation.kt
    - 删除 3 个违规 import
    - 删除 createHostedRuntime() 中 1200-1226 行的违规代码
    - 替换为 fail-closed（写 evidence + throw）
    - 删除 3 个 companion 常量（确认无其他引用）
    ↓
S2  新增 VirtualInstrumentationAuthorityBoundaryTest.kt
    - 源码边界扫描
    ↓
S3  检查并修复受影响的 loader tests
    - DualInstanceBaselineTest
    - VirtualInstrumentationApplicationContextTest
    - VirtualInstrumentationStartActivityEvidenceTest
    - 其他引用违规 import 的测试文件
    ↓
S4  运行验证
    - targeted 边界测试
    - core:loader + core:engine 全量测试
    - compileDebugKotlin
    - app:assembleDebug
```

- S2、S3 可并行
- 不修改 `HostedRuntimeEngine.kt`、`EngineRuntimeInstallers.kt`（已合规）
- 不新增 AIDL 方法

---

## 9. 验收条件

1. `VirtualInstrumentation.kt` 生产代码不引用 `JsonInstanceRecordStore`、`JsonInstallRecordStore`、`DefaultInstanceManager`
2. `createHostedRuntime()` cache miss 路径 fail-closed，不读 `filesDir/instances` 或 `filesDir/installs`
3. 无 owner JSON 文件格式变更
4. 无 AIDL transaction 变化
5. `core:loader`、`core:engine` 全量 JVM 测试通过
6. `app:assembleDebug` 通过

---

## 10. 未澄清事项

| 事项 | 影响 | 建议 |
|------|------|------|
| `DualInstanceBaselineTest` 是否直接依赖 VI 本地 bootstrap | 如果依赖，需要大量测试重写 | 读取该测试确认；可能只需 mock engine authority |
| `INSTANCES_DIR` / `INSTALLS_DIR` 是否在 VI 中有非违规使用 | companion 常量可能被 evidence 路径引用 | grep 确认后删除或保留 |
| 未提交的 `JsonDirectoryLock` 工作树状态 | 可能与本批修改冲突 | 主理人决定 stash 策略 |
| 容器 UI 对 Activity 启动失败的容错能力 | 删除本地 bootstrap 后，极端 race condition 下 Activity 启动可能失败 | 属 Batch 5（AMS + 设备证据）范畴，本批不处理 |
