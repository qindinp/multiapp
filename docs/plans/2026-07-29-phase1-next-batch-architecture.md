# MultiApp Phase 1 下一批架构审查与实施方案（2026-07-29）

## 0. 结论与 GO / NO-GO

- **本轮选择：GO（有条件）——移除 host clone 创建编排对 owner store / generation journal 的直接访问。**
- **不选择：NO-GO——直接合入当前工作树中的 `JsonDirectoryLock` 跨进程锁补丁。** 当前核心问题不是“两个合法 writer 需要互斥”，而是非 `:engine` writer 违反唯一权威；加锁会掩盖边界错误。工作树已有的 `core/model/.../persistence/JsonDirectoryLock.kt` 及四个 store/test 修改不属于本方案，应先隔离/撤回后再实现本批次。
- **不选择：NO-GO——本轮继续扩 PMS 字段或 AMS task stack。** PMS 已补的 3 字段缺少专门投影断言，但更高风险的是 owner graph 在多进程 Hilt 中被解析，以及 host/guest 仍直接打开 owner 文件；AMS 真机闭环无法只靠 JVM 测试宣称完成。
- **整体项目仍是 `alpha / BLOCK`。** 本批次只收紧权威边界，不改变成熟度；真机矩阵和 AMS task stack / `onNewIntent` / result / Recents 仍是 P0。

## 1. 审查基线

审查对象：

- `docs/reviews/2026-07-29-project-maturity-review.md`
- `docs/progress/2026-07-28-phase1-multi-agent-execution.md`
- 当前 `HEAD`：`ad76bcc feat: Phase 1 P0 closure - engine isolation, field fidelity, AMS semantics, external storage sandbox`
- 当前生产源码、边界测试和未提交工作树

### 1.1 已完成 / 计划中过时的事项

| 旧描述 | 当前源码事实 | 判定 |
|---|---|---|
| “独立 `:engine` 进程未隔离 / 本地共址” | `app/src/main/AndroidManifest.xml` 已将 `EngineBinderProvider` 和 recovery provider 放在 `:engine`；`EngineServerRuntime.requireEngineProcess()` 要求精确 `${hostPackage}:engine` | **过时**；Manifest 和 runtime owner 精确校验已完成，但完整 DI graph 隔离尚未完成 |
| “新增 Hilt owner 后仍允许 null / 任意 `:engine` 后缀” | `EngineServerRuntime.kt` 已 fail-closed，测试覆盖 null、空白、host、guest、异包、`:engine.extra` | **已完成** |
| “PMS 剩余 10 字段：debuggable/sharedUserId/sharedUserLabel/native root 等” | `VirtualPackageSnapshot`、install request/record、runtime codec/state 已传播 `debuggable/sharedUserId/sharedUserLabel`；`VirtualPackageInfoFactory` 设置 `FLAG_INSTALLED/FLAG_DEBUGGABLE/privateFlags`；native path compat 已覆盖 root/secondary path | **大部分过时**；这些字段链路已完成，但工厂投影专门测试不足，时间字段/permission flags 等仍缺 |
| “feature launcher/appmanager 直接依赖 core:instance 等” | 两个 feature build 已移除实现依赖，读取改 `VirtualizationEngine.listInstances()`；源码未发现 `core.instance/installer/identity` 引用 | **已完成** |
| “需要新增 `listInstances()`” | facade、AIDL 末尾追加、endpoint/client codec、transaction code 测试均已落地 | **已完成** |
| “Activity result 全部未实现” | `ActivityFinishResultHookInstaller`、`ActivityResultFrameworkBridge`、launch commit/IPC 已落地大量基础语义 | **旧描述过宽**；仍不能替代 `onNewIntent`、完整 task stack/Recents 与真机 result 证据 |
| “Json stores 应加跨进程锁” | 当前仍有 host 和 guest 不合规访问；合法目标应是 `:engine` 单 writer + Binder/不可变 snapshot reader | **方案方向过时**；先删除非 engine writer/readers，非以锁合理化共享写 |

## 2. A：Hilt / 多进程 DI 审查

### 2.1 当前事实

`EngineServerRuntime` 本体已正确 fail-closed，但**校验发生得仍太晚**：

1. `MultiAppApplication` 在每个 Android 进程启动 Hilt `SingletonComponent`。
2. `app/src/main/java/com/multiapp/app/AppModule.kt` 在该全进程 component 中无条件提供：
   - `JsonInstanceRecordStore`
   - `JsonInstallRecordStore`
   - `ProductionVirtualInstallService`
   - `DefaultInstanceManager`
   - `FileBackedEngineRuntimeSlotStore`
3. Hilt 构造 `EngineServerRuntime` 前必须先解析上述构造器依赖，因此 `requireEngineProcess()` 不能证明“owner graph 从未在非 engine 构造”。
4. `EngineBinderProvider` 从 Hilt entry point 获取单个 `EngineServerRuntime` 并以其组装 endpoint，这一点正确且已有 `EngineDependencyInjectionBoundaryTest`。

### 2.2 是否仍有第二权威

**是，存在可构造的第二套 durable owner graph，而不只是理论风险。**

- Host 的 `CloneCreateUseCase` 通过 `InstanceManager.listInstances()` 读取 owner store。
- Host 的 `PackageGenerationJournal.begin()/complete()` 写入/删除 `filesDir/package_generation_journal`。
- Guest 的 `VirtualInstrumentation.createHostedRuntime()` cache miss 路径直接打开 `filesDir/installs`、`filesDir/instances` 并本地 bootstrap。
- AppModule 使这些对象也能在 host / guest 的 Hilt component 中出现。

因此“`EngineServerRuntime` 构造器精确校验完成”不能等价于“Hilt 多进程隔离完成”。

### 2.3 目标设计

后续独立批次应引入**进程角色先验 factory**：先只依赖 `Context` 解析并精确校验进程，再在 factory 内创建 owner stores / managers / server graph；不要让 Hilt 在校验前解析 durable owner dependencies。目标形态：

```text
Hilt EntryPoint -> EngineServerRuntimeProvider(Context only)
                -> require exact host:engine
                -> create EngineOwnerGraph (stores/managers/system server)
                -> EngineServerRuntime
```

这不是本轮首批改动，因为会同时触及 `AppModule`、metadata resolver、slot store、server runtime 构造和 Hilt 测试，范围大于“安全 JVM 批次”。本轮先消除已确认的 host writer，为后续 factory 收口创造条件。

## 3. B：Json / Properties owner store 审查

### 3.1 真实 writer / reader

| 持久化区域 | 当前生产路径 | 进程角色 | 结论 |
|---|---|---|---|
| `instances/*.json` | engine 的 `DefaultVirtualizationEngine -> InstanceManager` | engine | 合法 writer |
| `instances/*.json` | `CloneCreateUseCase -> InstanceManager.listInstances()` | host | 非法 reader |
| `instances/*.json` | `VirtualInstrumentation.createHostedRuntime()` 构造 `JsonInstanceRecordStore` | guest | 非法 reader；`DefaultInstanceManager` 还暴露写 API |
| `installs/*.json` | engine `VirtualInstallService` | engine | 合法 writer |
| `installs/*.json` | guest `VirtualInstrumentation.createHostedRuntime()` | guest | 非法 reader |
| `package_generation_journal/*.journal` | host `CloneCreateUseCase -> PackageGenerationJournal` | host | 非法 writer/deleter |
| generation recovery files | `PackageGenerationRecoveryProvider`，Manifest `:engine`，且 initOrder 1100 早于 Binder provider 1000 | engine | 合法 recovery owner |
| engine runtime/task/package-enabled/properties stores | `EngineRuntimeInstallers.fileBackedSystemServer()` | engine | 已有 `EngineOwnerFileBoundaryTest` 约束 |
| engine slot properties | AppModule 的 `FileBackedEngineRuntimeSlotStore` | 所有 Hilt graph 可构造，实际 server 使用 | 仍需随 owner factory 收口 |

### 3.2 文件锁决策

- **不要把跨进程锁作为 P0 权威修复。** 对 owner store 的正确不变量是：只有 `:engine` 可以写，host/guest 通过 IPC 或 engine 下发 snapshot 读取。
- 同一 engine 进程若有并发调用，应在业务 transaction / store 内用进程内同步和原子替换；是否需要 advisory file lock，应仅用于 engine recovery 与 engine 正常写可能并发的窄窗口，并共享同一个锁协议。
- 当前未提交 `JsonDirectoryLock` 对 load/save/delete 全部加 directory exclusive lock，但 recovery reconciler 使用另一个 journal `.reconcile.lock`，两套协议不能证明互斥；且 JVM 两线程测试不证明 Android 跨进程锁语义。
- 先删除 host/guest writer/readers，再复审是否还存在真实跨进程并发。若只剩 engine writer，则删除通用跨进程锁方案。

## 4. C：PMS P0 字段 / 语义真实剩余缺口

### 4.1 已落地

- `ApplicationInfo.flags`：`FLAG_INSTALLED`，按 snapshot 加 `FLAG_DEBUGGABLE`
- `privateFlags = 0`
- `ApplicationInfoNativePathCompat`：nativeLibraryDir/root/secondary 兼容
- `PackageInfo.sharedUserId`
- `debuggable/sharedUserId/sharedUserLabel` 从 host package metadata，经 `VirtualApp -> EnginePackageInstallRequest -> InstallRecord -> VirtualPackageSnapshot -> runtime Bundle/properties` 全链路保真
- Provider authorities/read/write permission/grant/path permissions/URI patterns
- component process/taskAffinity/theme/launchMode/permission/metadata/alias target
- signatures/signingInfo 的 flag-gated 投影

### 4.2 仍缺或尚未闭环

1. **明确代码缺口**：`VirtualPackageInfoFactory.packageInfo()` 未设置 `sharedUserLabel`。
2. **已托管组件 enabled-state P0 未闭环（优先级高于纯字段补齐）**：manifest Activity/Service/Receiver 的 `enabled` 仅局部采集，未完整经 resolver/install record/snapshot/runtime codec 保真；application 和 Provider manifest enabled 尚未采集。已有 `EnginePackageEnabledStateStore` 的 per-instance、generation-scoped override 未被 `VirtualPackageService`、intent resolver 或各类 dispatch manager 消费；factory 又将 application 和四类 component `enabled` 固定为 true，且无 `MATCH_DISABLED_COMPONENTS` 语义。
3. **disabled 组件可回落泄漏**：virtual backend 对 disabled hosted component 过滤为空后，`VirtualPackageManagerWrapper` 可能回落 base PM，hidden invocation handler 也可将 empty/null 视为 `NotHandled` 并调用原 PMS；因此宿主 PMS 会重新返回该 hosted component。必须区分 unowned 与 owned-no-result（handled-empty/handled-null），已识别 hosted ownership 时禁止 fallback。
4. **权限字段语义**：GET_PERMISSIONS 只设置 `requestedPermissions`，未生成 `requestedPermissionsFlags`（granted/never-for-location 等实例级结果）。这需要 Permission authority，不宜只填固定值；permission definition/group 等完整语义亦未闭环。
5. **时间字段待独立复核**：本架构审查曾观察到 `InstallRecord.installTimeMs/updatedAtMs` 未在当前 snapshot 投影中出现，但 PMS 专项审查未覆盖或确认这一点。因此不得以本节作为该缺口的唯一证据；若纳入应先补针对 `VirtualPackageSnapshot`、`HostedRuntimeEngine.toInstallRecordOrNull()` 与 `PackageInfo.firstInstallTime/lastUpdateTime` 的源码和 fixture 审计。
6. **测试缺口**：当前 loader tests 未直接断言 `FLAG_INSTALLED`、`FLAG_DEBUGGABLE`、`privateFlags`、native root/secondary、`sharedUserId/sharedUserLabel`；也缺 disabled hosted query 不回退宿主 PMS 的覆盖。
7. **ResolveInfo 细节**：priority/match/isDefault/resolvePackageName/filter 等仍是默认值，真实 intent resolver 排序与 Android framework 一致性尚未形成 release evidence。
8. **设备语义**：即使 JVM factory 测试通过，也不能把 hidden-field 写入、Binder proxy 或真实 App 观察值标 PASS。

建议在 owner 边界收口后，PMS 先做 enabled-state 查询语义批次：先以 Activity runtime override 为最小范围，支持 application/Activity effective evaluator、`MATCH_DISABLED_COMPONENTS`、hosted owned-no-result 的 no-fallback；必要时紧随其后接入 `VirtualIntentResolver` 覆盖 explicit/implicit/launcher dispatch。`sharedUserLabel` 和已有字段的非默认 fixture 断言可同批低风险补齐。manifest enabled 全链路保真、permission flags 和时间字段审计属于后续独立子项。

## 5. D：app / feature / container 越界审查

### 5.1 已合规

- `feature/launcher` 和 `feature/appmanager` 的实例列表已走 `VirtualizationEngine.listInstances()`。
- launch/delete/create 的最终 mutation 走 engine facade/Binder，不存在本地 mutation fallback。
- `EngineBinderProvider` endpoint 的核心服务均取自同一 `EngineServerRuntime owner`。

### 5.2 仍越界

1. `core/instance/CloneCreateUseCase.kt`
   - `suggestedDisplayName()` 直接调用 `InstanceManager.listInstances()`。
   - host 写/删 `PackageGenerationJournal`；这是跨进程 owner file writer。
2. `core/loader/VirtualInstrumentation.kt`
   - cache miss 时直接打开 instances/installs 并本地 `HostedRuntimeBootstrap`。
   - 该路径绕过 `DefaultHostedRuntimeEngine.authoritativeRuntime()` 的 Binder snapshot/fail-closed 设计。
3. `app/AppModule.kt`
   - owner stores/managers/services 在所有进程的 SingletonComponent 暴露。
4. `app/container` 多处直接依赖 `core:engine` 的 guest-side transport/facade 类型是现有模块边界债务；更严重的是直接构造 dispatchers/router 的路径，应在 owner factory 与 facade 专项中逐个约束。当前未发现 container 直接读取 owner JSON/properties；evidence 文件读写不属于权威事实源。

## 6. 本轮最高优先级安全批次

### 6.1 批次目标

**将 host clone 创建协调器改为只依赖 `VirtualizationEngine`，删除 host `InstanceManager` owner-file reader 和 `PackageGenerationJournal` writer。**

选择理由：

- P0 单权威直接受益；范围集中；无需改 AIDL（已有 `listInstances` 与 `createInstance(request)`）。
- 可以用纯 JVM 单测验证依赖和调用行为。
- 不触碰正在推进的 AMS 真机路径，也不修改 owner store 格式。
- 为随后删除 AppModule owner bindings、guest local bootstrap 和 owner factory 重构降低依赖。

### 6.2 精确文件与修改点

#### 1. `core/instance/src/main/java/com/multiapp/core/instance/CloneCreateUseCase.kt`

- 删除 import：`PackageGenerationJournal`、`PackageGenerationTransaction`、`PackageGenerationTransactionJournal`、`InstanceManager`。
- 主构造器删除 `instanceManager`、`packageGenerationJournal`；`@Inject` 构造器只注入 `VirtualizationEngine`。
- `suggestedDisplayName(app)` 改用 `virtualizationEngine.listInstances()` 计算编号；facade 不可用时的当前契约是空列表，禁止回退读取 JSON。
- `create()` 删除 journal begin/complete/abandon 全流程；只保持：构造稳定 `CreateInstanceRequest` -> 调用 engine -> 解析 authoritative result -> 返回 UI result。
- `ENGINE_AUTHORITY_UNKNOWN_RESULT` 时仍令 `shouldRetainCreationRequestId=true`，幂等性由 engine 已持久化的 `creationRequestId + creationRequestFingerprint` 保证；确定性失败不保留。
- `cleanupStatus` 继续为 `engine_owned`，禁止本地 delete/rollback。

#### 2. `core/instance/src/test/java/com/multiapp/core/instance/CloneCreateUseCaseTest.kt`

- 删除 `InstanceManager` 和 generation journal mocks，以及“journal before engine / complete / abandon”测试。
- 编号名称 fixture 改 mock `virtualizationEngine.listInstances()`。
- 保留并强化：
  - `listInstances()` 仅用于命名；
  - create 只调用 facade；
  - authority unknown 复用 request id；
  - 确定失败不复用；
  - transport exception 不做本地 mutation；
  - debuggable/sharedUserId/sharedUserLabel 进入 request 的非默认断言。
- 新增源码边界断言（可放本文件或下述专门测试）：生产 `CloneCreateUseCase` 不得出现 `InstanceManager`、`PackageGenerationJournal`、`PackageGenerationTransactionJournal`。

#### 3. `core/instance/src/main/java/com/multiapp/core/instance/InstanceBoundaryModule.kt`

- 继续把 `CloneCreateUseCase` 绑定为 `CloneCreationCoordinator`；无需 API 改动。
- 添加注释或 package-visible invariant：实现仅能依赖 `core:model` facade，不得注入 durable store/manager。
- `InstalledAppCatalog` 仍是 host PackageManager catalog，不属于 engine owner；保留。

#### 4. `core/instance/src/test/java/com/multiapp/core/instance/InstanceBoundaryModuleTest.kt`（新增）

- 源码边界测试扫描 `CloneCreateUseCase.kt`：禁止 owner store/manager/journal 类型。
- 断言 `InstanceBoundaryModule` 的 `CloneCreationCoordinator` 绑定仍唯一，防止未来引入第二实现。

#### 5. `app/src/test/java/com/multiapp/app/EngineDependencyInjectionBoundaryTest.kt`

- 扩展跨模块边界断言：host-facing `CloneCreationCoordinator` 实现不得依赖 `InstanceManager` / generation journal。
- 暂不删除 AppModule store bindings（EngineServerRuntime 本批仍需要）；把其列为下一批 failing target，而不是在本批制造大范围 Hilt 变更。

### 6.3 依赖关系与实现顺序

```text
S0 隔离当前未提交 JsonDirectoryLock 工作树（前置，避免混批）
  -> S1 修改 CloneCreateUseCase（只依赖 VirtualizationEngine）
     -> S2 改写 CloneCreateUseCaseTest
     -> S3 增加 InstanceBoundaryModuleTest / app 边界断言
        -> S4 运行 core:instance 与 app JVM tests
           -> S5 compileDebugKotlin + assembleDebug（验证 Hilt 图）
```

- S2、S3 均依赖 S1，可并行。
- 不修改 `IEngineRuntimeService.aidl`；若后续确需新增 API，只能追加末尾并更新 transaction code test。

### 6.4 验收条件

- `CloneCreateUseCase` 生产源码不引用 `InstanceManager` 或 package generation journal。
- host create/name suggestion 只通过 `VirtualizationEngine`。
- engine authority unknown 时稳定 request id 行为不变。
- 无 owner JSON/properties 文件格式变更、无 AIDL transaction 变化。
- core:instance/app 相关 JVM tests、编译和 Hilt 聚合通过。

## 7. 后续批次（不与本轮混合）

### Batch 2：guest owner-file reader 清除

文件：

- `core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt`
- `core/engine/src/main/java/com/multiapp/core/engine/HostedRuntimeEngine.kt`
- `core/engine/src/main/java/com/multiapp/core/engine/EngineRuntimeInstallers.kt`
- 对应 loader/engine JVM tests

修改：删除 `createHostedRuntime()` 的 `Json*Store + DefaultInstanceManager` fallback；cache miss 必须调用/等待 authoritative runtime bootstrap 或 fail-closed。该批需谨慎处理 instrumentation 与 `DefaultHostedRuntimeEngine` 共用的 `VirtualProcessRuntime.global`，避免重复绑定 Application。

### Batch 3：owner graph 进程角色 factory

文件：

- `app/src/main/java/com/multiapp/app/AppModule.kt`
- `app/src/main/java/com/multiapp/app/container/EngineBinderProvider.kt`
- `core/engine/src/main/java/com/multiapp/core/engine/EngineServerRuntime.kt`
- 新增 `EngineOwnerGraphFactory` 及 Hilt/process tests

修改：Hilt 仅注入 context/factory；factory 在任何 store 构造前精确验证 `${hostPackage}:engine`。移除全进程直接 provider 暴露，host 只保留 `IpcVirtualizationEngine` / guest runtime facade。必要时拆 host metadata resolver 与 engine owner import service。

### Batch 4：PMS enabled-state 查询语义

先做 Activity runtime enabled override，不与 manifest-enabled schema 改造混批：为 application/Activity 建立 effective enabled evaluator，authority unavailable、invalid 或目标缺失时 fail-closed；`VirtualPackageService.getActivityInfo()`、`queryIntentActivities()`、`resolveActivity()` 消费该 evaluator，支持 `MATCH_DISABLED_COMPONENTS` 且令 `ActivityInfo.enabled` 反映 effective state。

同时修正 adapter fallback：对已识别 hosted ownership 的 disabled/no-result，hidden `IPackageManager` handler 必须返回 handled-empty/handled-null，public wrapper 不得回退 base PM；非 hosted 请求继续走系统 fallback。若同批要覆盖真实 dispatch，接入 `VirtualIntentResolver` 并覆盖 explicit/implicit/launcher，否则紧随其后单列 dispatch 批次。补 `PackageInfo.sharedUserLabel` 和 `FLAG_INSTALLED`、conditional `FLAG_DEBUGGABLE`、`privateFlags`、native paths 的非默认 fixture 回归断言。

注意：未来把 manifest `enabled` 加入 `InstallRecord` 时，`InstallRecordStore` 是 Gson schema v1，旧 JSON 缺 primitive Boolean 可能反序列化为 false，必须经 `normalized()`/显式迁移保持默认 true；runtime codec 的 exact-key schema 还需同步 keys、bundle encode/decode、field whitelist 与版本策略。permission flags、完整 permission 定义/group 以及 install/update time 需独立权威/投影审计，不能用固定值伪造。

### Batch 5：AMS + 设备证据

完成 task stack / `onNewIntent` / result / Recents，并按 API 28/30/33/35/37、arm32/arm64、厂商 ROM 形成 commit + APK SHA-256 + fingerprint + logcat + 首帧/交互/恢复证据。

## 8. 测试命令

本机 JDK 17 路径（当前 `java` 不在 PATH）：

### Git Bash

```bash
export JAVA_HOME='C:/Users/20237/.cache/codex-runtimes/jdk-17'
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :core:instance:testDebugUnitTest :app:testDebugUnitTest --no-daemon
./gradlew :core:instance:compileDebugKotlin :app:compileDebugKotlin --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

建议 targeted 先跑：

```bash
./gradlew :core:instance:testDebugUnitTest --tests 'com.multiapp.core.instance.CloneCreateUseCaseTest' --no-daemon
./gradlew :app:testDebugUnitTest --tests 'com.multiapp.app.EngineDependencyInjectionBoundaryTest' --no-daemon
```

边界复核（使用仓库搜索工具或 CI 等价检查）：

```text
CloneCreateUseCase.kt 中禁止 InstanceManager、PackageGenerationJournal、PackageGenerationTransactionJournal
feature/* 中禁止 core.instance / core.installer / core.identity 实现引用
```

本批 JVM 命令只证明编排/边界，不证明 Android 多进程、文件锁、PMS hidden field 或 AMS 真机兼容。

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 删除 host journal 后 engine call 在 Binder 返回前完成但客户端看到 unknown | UI 重试 | 保留同一 `creationRequestId`；engine 已有 durable instance request id/fingerprint 去重；专项测试覆盖 |
| `listInstances()` authority 不可用返回空列表，建议名称可能暂时重复 | UX/请求冲突 | displayName 不作为权威 identity；engine request id/fingerprint 决定幂等；不允许 JSON fallback |
| Hilt graph 仍可在非 engine 构造 owner dependencies | 仍有第二 graph 风险 | 明确为 Batch 3 P0；本批只消除实际 host reader/writer，不宣称 DI 完成 |
| guest fallback 删除后暴露启动时序问题 | guest 启动 fail-closed 增多 | 单独 Batch 2，先完善 authoritative bind 等待/错误证据，禁止本批混改 |
| 当前未提交 JsonDirectoryLock 与本批冲突 | 测试/审查污染 | 开始实现前先隔离该工作树；不得覆盖他人修改，交由主理人决定 stash/独立批次 |
| PMS disabled hosted component 回落宿主 PMS | 已禁用虚拟 Activity 仍可被 query/resolve 返回或启动 | Batch 4 引入 owned-no-result tri-state，已识别 hosted ownership 时禁止 wrapper/handler fallback，并以 explicit/implicit/launcher fixture 覆盖 |
| PMS 已实现字段缺专测 | 回归 | Batch 4 使用非默认 fixture；不在本批捎带模型/AIDL 改动 |

## 10. 最终 GO / NO-GO 门禁

### GO 条件

1. 当前 JsonDirectoryLock 未提交变更已隔离，不与本批混合。
2. 只修改第 6.2 节文件；不改 AIDL、不改 store schema。
3. targeted + 模块 JVM tests + Kotlin compile + app assemble 全通过。
4. 边界测试证明 host clone coordinator 无 owner file 依赖。

### NO-GO 条件

- 为保留旧行为重新引入 JSON/Properties fallback；
- 用跨进程文件锁替代删除非 engine writer；
- 在 host/guest 构造 `DefaultVirtualizationEngine` 或 owner stores；
- 修改 AIDL 既有方法顺序；
- 用 JVM 测试结论将 PMS/AMS/设备能力标为 PASS；
- owner factory 未落地前声称“Hilt 多进程隔离已完成”。
