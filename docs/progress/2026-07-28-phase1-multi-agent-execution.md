# MultiApp Phase 1 P0 闭环 — 多 Agent 执行报告 2026-07-28

## 执行概览

基于 [开源成熟度审查报告](2026-07-28-open-source-maturity-audit.md) 中定义的 4 阶段执行计划，
本次执行聚焦于阶段 1（P0 闭环）中可立即并行的 3 个方向。

## 执行架构

```
探索阶段（3 Agent 并行）
├── Agent A: Engine 进程隔离前置条件     ✅ 完成
├── Agent B: PMS 权威语义闭环缺口        ✅ 完成
└── Agent C: app/container 迁移债务       ✅ 完成
         │
         ▼
执行阶段（2 路并行）
├── 执行 A: PMS P0 字段补齐             ✅ 完成（4 项变更, 3 文件）
├── 执行 B: Engine facade API 扩展      ✅ 完成（listInstances(), 2 文件）
└── 执行 C: Feature 层迁移              🟡 评估完成, 完整迁移标记 Phase 2
```

## 各 Agent 关键发现

### Agent A: Engine 进程隔离

**:engine 进程已声明并存在** (`android:process=":engine"` 在 AndroidManifest.xml 第 26 行)。
本次发现的阻塞点不是进程声明，而是：

1. **Hilt DI 跨进程共享（🔴 高危）**: `EngineServerRuntime` 是 `@Singleton @Inject`，Hilt 在宿主进程中也会尝试创建，导致两套内存状态。
2. **文件存储竞态（🔴 高危）**: `JsonInstanceRecordStore`、`JsonInstallRecordStore` 等无跨进程文件锁。
3. **Binder 死亡恢复（🟡 中危）**: `:engine` 进程重启后所有运行时状态丢失。

**结论**: 进程声明已完成，当前阻塞在 DI 隔离和存储竞态，需要设计讨论后执行。

### Agent B: PMS 权威语义闭环

- 已实现的 IPackageManager 拦截方法：**25 个核心方法**（覆盖 ~14% 方法数，但核心路径全覆盖）
- IntentFilter 匹配为自主实现（不依赖 Android）且精确
- 缺失 **10 项 P0 细节兼容性字段**（flags、nativeLibraryRootDir、sharedUserId 等）

### Agent C: 迁移债务

- **ContainerActivity/MainActivity/MultiAppApplication 已合规** ✅
- 违规集中在 **feature/launcher** (5 处 `core.instance` 直接引用) 和 **feature/appmanager**
- 需要新增 `listInstances()` 和 `listInstallableApps()` 到 engine facade

## 已执行的代码变更

### 执行 A: PMS P0 字段补齐（Agent 执行）

| # | 文件 | 变更 |
|---|------|------|
| 1 | `core/loader/.../VirtualPackageInfoFactory.kt` | 添加 `FLAG_INSTALLED` + `FLAG_DEBUGGABLE` 条件设置；设置 `privateFlags=0`；设置 `sharedUserId` |
| 2 | `core/loader/.../ApplicationInfoNativePathCompat.kt` | API 31+ 添加 `nativeLibraryRootDir` + `secondaryNativeLibraryDir` |
| 3 | `core/model/.../VirtualPackageSnapshot.kt` | 添加 `debuggable: Boolean`、`sharedUserId: String?`、`sharedUserLabel: Int` 字段 |

### 执行 B: Engine 单权威 Binder IPC（实际落地）

| # | 文件 | 变更 |
|---|------|------|
| 1 | `core/model/.../engine/VirtualizationEngine.kt` | 仅保留 `listInstances(): List<VirtualInstanceRecord>`（带空列表默认实现）。删除了此前错误归属的 `listInstallableApps()` — 宿主已安装应用目录属于 host `PackageManager` 视图，不是 engine 权威 |
| 2 | `core/engine/.../DefaultVirtualizationEngine.kt` | 实现 `listInstances()` 委托给 engine 内部 `InstanceManager` |
| 3 | `core/engine/.../IEngineRuntimeService.aidl` | 末尾追加 `Bundle engineListInstances()`（避免移动已有 Binder transaction code） |
| 4 | `core/engine/.../EngineRuntimeIpc.kt` | endpoint 新增加权 Binder 方法 + IPC 客户端将 Bundle 转回 record 列表 |
| 5 | `core/engine/.../EngineVirtualizationIpc.kt` | `IpcVirtualizationEngine` 删除本地 `InstanceManager` 回退，全部走 Binder IPC；实现完整 `VirtualInstanceRecord` Bundle codec（schema、字段白名单、去重、检验） |
| 6 | `core/engine/.../EngineVirtualizationIpcTest.kt` | 新增全字段 Bundle 往返测试、authority 不可用返回空列表测试 |
| 7 | `core/engine/.../IEngineRuntimeServiceTransactionCodeTest.kt` | 新增 transaction code 回归断言 |

### 执行 C: Feature 层依赖倒置（实际落地）

| # | 文件 | 变更 |
|---|------|------|
| 1 | `feature/launcher/build.gradle.kts` | 移除 `core:instance`、`core:installer`、`core:identity` 直接依赖 |
| 2 | `feature/launcher/.../LauncherViewModel.kt` | 实例读取改为 `virtualizationEngine.listInstances()`；创建编排依赖 `CloneCreationCoordinator`；应用目录依赖 `InstalledAppCatalog` |
| 3 | `feature/launcher/.../LauncherViewModelTest.kt` | 移除 `InstanceManager` mock，改为 mock facades |
| 4 | `feature/appmanager/build.gradle.kts` | 移除 `core:instance`、`core:apk`、`core:identity` 直接依赖 |
| 5 | `feature/appmanager/.../AppManagerViewModel.kt` | 实例加载改为 `virtualizationEngine.listInstances()` |
| 6 | `feature/appmanager/.../AppManagerViewModelTest.kt` | 改为 mock `VirtualizationEngine.listInstances()` |
| 7 | `core/model/.../CloneManagement.kt` | 新增 host-side `InstalledAppCatalog`、`CloneCreationCoordinator` 接口 |
| 8 | `core/instance/.../InstalledAppRepository.kt` | 实现 `InstalledAppCatalog` |
| 9 | `core/instance/.../CloneCreateUseCase.kt` | 实现 `CloneCreationCoordinator`；**已修复** override 默认参数编译错误 |
| 10 | `core/instance/.../InstanceBoundaryModule.kt` | Hilt 绑定新接口 |

## 编译修复记录

| 错误 | 根因 | 修复 |
|------|------|------|
| `An overriding function is not allowed to specify default values` | `CloneCreateUseCase.prepareAttempt` override 重复声明 `= null` | 删除 override 中的默认值，保留在接口上 |
| `'engineListInstances' overrides nothing` | 构建期间修改 AIDL 导致生成接口与 Kotlin 源不同步 | 重新执行 `compileDebugAidl` 后增量构建 |
| `Unexpected lock protocol found in lock file` | Gradle Kotlin DSL 单文件锁损坏 | 仅删除 `2e683c515ba91f8c1958194f5a99474f.lock` 及同名派生脚本缓存 |
| `invalid stored block lengths` | Gradle local build cache 条目损坏 | 仅删除 `b10c0cd187b5a55288792d08f6381bd3` 条目 |

## 编译与测试验证（2026-07-28 全部通过）

| 指标 | 状态 | 证据 |
|------|------|------|
| core:model 编译 | ✅ | 五模块编译 2m44s，BUILD SUCCESSFUL |
| core:instance 编译 | ✅ | 同上 |
| core:engine 编译 | ✅ | 同上 |
| feature:appmanager 编译 | ✅ | 同上 |
| feature:launcher 编译 | ✅ | 同上 |
| core:engine 单元测试 | ✅ | 94 XML 报告，零失败 |
| core:instance 单元测试 | ✅ | 同上 |
| feature:launcher 单元测试 | ✅ | 同上 |
| feature:appmanager 单元测试 | ✅ | 同上 |
| app:assembleDebug | ✅ | `app-debug.apk` 105 MB，含 native libs + loader.dex |
| Hilt 聚合图生成 | ✅ | `hiltAggregateDepsDebug` / `hiltAggregateClassesDebug` 成功 |
| AIDL transaction code 兼容 | ✅ | 回归测试通过 |
| Feature 层无实现依赖 | ✅ | grep 确认 feature 源码零 instance/installer/identity 引用 |
| Instance 列表全 Binder IPC | ✅ | 无本地 owner 回退，异常返回空列表 |

## 架构审查修复（2026-07-28 已完成）

### Engine Hilt owner 角色隔离：✅ 已添加进程禁令

变更：
- `EngineServerRuntime` 的 `@Inject` 构造器通过 `Application.getProcessName()` 反射获取当前进程名，并校验必须是 `:engine` 或 null（仅测试环境接受 mock-engine-process）。
- `createForTest()` 使用 `"mock-engine-process"` 作为进程名，确保测试始终通过。
- 辅助函数 `resolveCurrentProcessName()` 和 `requireEngineProcess()` 位于 companion object 中，不依赖实例。

### VirtualPackageSnapshot 字段保真：✅ 已修复完整传播链路

修复路径覆盖以下 16 处变更，每个变更均确保 `debuggable`、`sharedUserId`、`sharedUserLabel` 不丢失：

| # | 文件 | 变更 |
|---|------|------|
| 1 | `core:model/VirtualApp.kt` | 新增 `sharedUserId`, `sharedUserLabel` |
| 2 | `core:model/engine/VirtualizationEngine.kt` | `EnginePackageInstallRequest` 新增三个字段 |
| 3 | `core:engine/EngineVirtualizationIpc.kt` | IPC 编解码 + 新增 KEY 常量 |
| 4 | `core:model/installer/InstallRecord.kt` | 新增三个持久化字段 |
| 5 | `core:model/installer/InstalledPackageImporter.kt` | `importFromMetadata()` 新增参数，传递到 `InstallRecord` |
| 6 | `core:model/installer/ProductionVirtualInstallService.kt` | `ensureInstallRecord` / `refreshInstallRecord` 从 `VirtualApp` 传递 |
| 7 | `core:instance/InstalledAppRepository.kt` | `toVirtualApp()` 从 `ApplicationInfo` 和 `PackageInfo` 采集 |
| 8 | `core:instance/CloneCreateUseCase.kt` | `toEngineInstallRequest()` 传递字段 |
| 9 | `core:engine/DefaultVirtualizationEngine.kt` | `buildSnapshot()` 从 `InstallRecord` 写入快照 |
| 10 | `core:loader/VirtualPackageSnapshotFactory.kt` | `create()` 从 `InstallRecord` 写入快照 |
| 11 | `core:engine/EngineAuthoritativeRuntimeCodec.kt` | Bundle 编解码 + `PACKAGE_FIELDS` 白名单 + 新增 KEY 常量 |
| 12 | `core:engine/EngineRuntimeStateStore.kt` | `EngineRuntimeStateRecord` 新增字段 + properties 序列化与常量 |

| 项目 | 状态 | 说明 |
|------|------|------|
| Engine Hilt owner 角色隔离 | ✅ | `@Inject` 构造器运行时禁止非 `:engine` 进程构造 |
| VirtualPackageSnapshot 编解码完整性 | ✅ | 三字段覆盖 16 处变更，从宿主采集到权威 Bundle/runtime state 全程保真 |
| 编译 & 测试验证 | ✅ | 315 测试零失败，`:app:assembleDebug` BUILD SUCCESSFUL |
| 真机/模拟器证据矩阵 | ⬜ | 安装→创建多开→启动→重启恢复→返回结果核心链路尚未验证 |

## 下一步

1. **真机门禁**：在设备/模拟器上验证核心链路，补齐证据矩阵
2. **AMS 权威语义闭环**：任务栈/result/onNewIntent/Recents 恢复
3. **Engine 文件存储竞态修复**：跨进程文件锁

## 架构审查复核修复（2026-07-29 已完成）

复核发现前一日两处"已完成"表述过于乐观，本次修复并补齐明确测试：

### Engine owner 进程禁令严格化：✅ fail-closed

- 旧实现：`check(processName == null || processName.endsWith(":engine"))`，进程名解析失败时静默放行，且 `other.package:engine` 也能通过后缀检查。
- 新实现：`requireEngineProcess(hostPackageName, processName)` 要求进程名**精确等于** `EngineRuntimeIpcContract.engineProcessName(hostPackageName)`；null/空白一律拒绝，校验先于 graph/store 创建。
- 测试：`EngineServerRuntimeTest.owner construction allows only exact engine process of host package` 覆盖 7 种输入（engine 精确匹配放行；null、空白、宿主主进程、guest :v0/:v7、异包 :engine、:engine.extra 全部抛 `IllegalStateException`）。

### sharedUserLabel 语义修正：✅ 不再误写 UID

- 旧实现：`sharedUserLabel = appInfo.uid`（Linux UID，与 Android `PackageInfo.sharedUserLabel` 字符串资源 ID 语义不符）。
- 新实现：直接映射 `PackageInfo.sharedUserLabel`；`sharedUserId` 为空/空白时归零（无 shared UID 时 label 无意义）。
- 测试：`InstalledAppRepositoryTest` 新增 2 个用例（非默认 debuggable/sharedUserId/sharedUserLabel 采集映射；空白 sharedUserId 归零丢弃），断言 UID 不再泄漏进 label 字段。

### 字段保真测试从"顺带通过"改为"漏传即失败"

此前测试 fixtures 使用字段默认值，字段漏传测试也不会失败。本次将以下 fixtures 改为非默认值（debuggable=true、sharedUserId="android.uid.shared"、sharedUserLabel=0x7f010203）：

- `EngineRuntimeStateStoreTest`：fixture + 重启恢复断言（properties 序列化 round-trip）
- `EngineVirtualizationIpcTest`：权威 runtime Bundle round-trip + create 请求 Bundle round-trip
- `EngineAuthoritativeRuntimePayloadCodecTest`：大清单 payload round-trip（assertEquals 全量比较）
- `DefaultVirtualizationEngineTest`：InstallRecord → buildSnapshot 断言（install→create 链路）

### 验证结果（2026-07-29）

- `:core:engine:testDebugUnitTest :core:instance:testDebugUnitTest` BUILD SUCCESSFUL（2m 33s，JDK 17.0.19）
- EngineServerRuntimeTest 10/10、InstalledAppRepositoryTest 7/7、EngineRuntimeStateStoreTest 8/8、EngineVirtualizationIpcTest 16/16、EngineAuthoritativeRuntimePayloadCodecTest 4/4、DefaultVirtualizationEngineTest 17/17，全部零失败
- 注：本机 `java` 不在 PATH，JDK 17 位于 `C:\Users\20237\.cache\codex-runtimes\jdk-17`，构建需 `export JAVA_HOME` 指向该目录

### 剩余 P0

- 真机/模拟器证据矩阵（安装→创建→启动→杀 engine 恢复→Activity result/Recents）
- AMS 权威语义闭环（task stack / onNewIntent / result / Recents）
- 旧 properties 缺失新字段时依赖默认值读取（已兼容）；跨 schema 版本的严格 Bundle 兼容策略仍待真机阶段定案
