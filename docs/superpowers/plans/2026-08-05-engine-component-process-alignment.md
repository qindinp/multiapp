# 组件进程对齐开源思路实施计划（slot 分配表 + 自证认领 + 进程名还原）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让微信 sandbox / WPS 组件进程在真机可达 BOOTSTRAPPED，且其余 5 个候选应用不回归。

**Architecture:** 保留现有 24 stub 进程 + `:engine` server 骨架，把「一次性票据」语义改为「slot 分配表 + 自证认领」：主进程死亡时保留组件进程状态（宽限窗口内可完成 attach），组件进程无票据时按 slot 自证认领；同时把进程名还原从微信特判扩展为通用 Java/native 还原。

**Tech Stack:** Kotlin / Android framework hook (LSPlant) / JNI native hook（已有 `/proc/self/cmdline` spoof 机制）/ Gradle Android + JVM 单测。

**Spec:** `docs/superpowers/specs/2026-08-05-engine-component-process-alignment-design.md`

**关键事实（证据）**
- Round 8 logcat：微信主进程 14:34:04.583 exit(1) → engine 14:34:04.629 清理 → sandbox v1 14:34:04.834 attach 时 `component_process_launch_capability_not_found`。
- `EngineServerRuntime.kt:567-601` 的 `generationCleanup` 在主进程死亡时同时 revoke 组件 slots/clients/launch capabilities —— 微信失败的直接原因。
- `EngineServerRuntime.kt:353-355` `validateComponentProcessClientIdentity` 对 `DEAD/STOPPED` 一律拒绝 —— 即使票据保留，死亡后 attach 也会被 `component_process_runtime_not_live` 拒绝。
- WPS `makeApplication` 失败根因是 RePlugin 依据进程名走了插件进程分支，binder 为 null；现网只有微信特判 hook，WPS 无覆盖。
- native `/proc/self/cmdline` spoof 已有（`NativeHookBridge.spoofProcSelf`），但 PackerRuntimeStage 固定用 `originPackageName`，组件进程应改用 `effectiveGuestProcessName`。

---

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `core/loader/.../JavaExitSuppressionHook.kt` | exit 拦截 + 全量退出栈诊断 | 修改 |
| `core/loader/.../PackerRuntimeStage.kt` | packer 适配阶段（exit hook 安装、native spoof、进程名 hook） | 修改 |
| `core/engine/.../EngineServerRuntime.kt` | engine 组件进程 authority（prepare/attach/validate） | 修改 |
| `core/engine/.../EngineComponentProcessSlotAllocator.kt` | slot 分配表（slot→key 反查 + 每 slot 进程 epoch） | 修改 |
| `core/engine/.../EngineComponentProcessIpc.kt` | 组件进程 IPC 操作/形状校验 | 修改 |
| `core/engine/.../EngineRuntimeIpc.kt` | IPC endpoint + client（新增 attachComponentProcessBySlot） | 修改 |
| `app/.../container/HostedServiceRuntimeBinder.kt` | 组件进程 runtime bind（无票据时按 slot 自证） | 修改 |
| `core/loader/.../GuestProcessNameCompat.kt` | 通用进程名还原（新组件） | 新建 |
| `core/loader/.../BootstrapStageContract.kt` | 阶段输入（新增 effectiveGuestProcessName） | 修改 |
| `core/loader/.../HostedRuntimeBootstrap.kt` | 组装阶段输入 | 修改 |
| `core/engine/.../EngineServerRuntimeTest.kt` | engine 死亡保留/自证 attach 测试 | 修改 |
| `core/loader/.../JavaExitSuppressionHookTest.kt` | exit 诊断测试 | 修改 |
| `core/loader/.../GuestProcessNameCompatTest.kt` | 进程名还原决策测试 | 新建 |

---

### Task 1: 全量退出栈诊断（微信看门狗自杀点定位）

**Files:**
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/JavaExitSuppressionHook.kt`
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/PackerRuntimeStage.kt`
- Test: `core/loader/src/test/java/com/multiapp/core/loader/JavaExitSuppressionHookTest.kt`

- [ ] **Step 1: 写失败测试**（期望：开启 alwaysLog 后，窗口关闭的非零 exit 也回调 stackLogger 且 callOriginal 照常执行）

在 `JavaExitSuppressionHookTest.kt` 追加：

```kotlin
@Test
fun `alwaysLogExitStacks logs unsuppressed exits while original still runs`() {
    JavaExitSuppressionHook.openWindow()
    JavaExitSuppressionHook.closeWindow()
    JavaExitSuppressionHook.enableAlwaysLogExitStacks()
    val logged = mutableListOf<Pair<Int, String>>()
    JavaExitSuppressionHook.setStackLogger { status, stack -> logged += status to stack }
    try {
        var originalRan = false
        JavaExitSuppressionHook.interceptExit(status = 1, callOriginal = { originalRan = true; Unit })
        assertTrue(originalRan, "unsuppressed exit must still call original")
        assertEquals(1, logged.size)
        assertEquals(1, logged.single().first)
        assertTrue(logged.single().second.isNotBlank(), "stack must be captured")
        assertTrue(logged.single().second.contains("JavaExitSuppressionHook"))
    } finally {
        JavaExitSuppressionHook.disableAlwaysLogExitStacks()
        JavaExitSuppressionHook.setStackLogger(null)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.JavaExitSuppressionHookTest"
```

Expected: FAIL（`enableAlwaysLogExitStacks` / `setStackLogger` 不存在 → 编译失败）。

- [ ] **Step 3: 实现**

在 `JavaExitSuppressionHook.kt`：

```kotlin
private val alwaysLogStacks = AtomicBoolean(false)
private val stackLogger = AtomicReference<((Int, String) -> Unit)?>(null)

fun enableAlwaysLogExitStacks() {
    alwaysLogStacks.set(true)
}

fun disableAlwaysLogExitStacks() {
    alwaysLogStacks.set(false)
}

fun setStackLogger(logger: ((Int, String) -> Unit)?) {
    stackLogger.set(logger)
}

private fun captureExitStack(): String =
    runCatching {
        Throwable().stackTrace.take(20).joinToString("\n    at ") { it.toString() }
    }.getOrDefault("")
```

`interceptExit` 改为：

```kotlin
fun <T> interceptExit(
    status: Int,
    callOriginal: () -> T,
    onSuppressed: (status: Int) -> Unit = { logSuppressed(it) }
): T? {
    return if (shouldSuppress(status)) {
        suppressedCount.incrementAndGet()
        onSuppressed(status)
        null
    } else {
        if (alwaysLogStacks.get()) {
            val stack = captureExitStack()
            stackLogger.get()?.invoke(status, stack)
                ?: runCatching { android.util.Log.w(TAG, "Unsuppressed exit($status). Stack:\n    at $stack") }
        }
        callOriginal()
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

同上命令，Expected: PASS。

- [ ] **Step 5: 在 PackerRuntimeStage 打开诊断**

`PackerRuntimeStage.kt` 在 `JavaExitSuppressionHook.install(...)` 之后追加：

```kotlin
// P0-1 诊断：微信/WPS 加固在窗口外也主动 System.exit，打印全量调用栈定位看门狗自杀点。
if (javaExitHookResult.anyHooked) {
    JavaExitSuppressionHook.enableAlwaysLogExitStacks()
}
```

- [ ] **Step 6: Commit**

```powershell
git add core/loader/src/main/java/com/multiapp/core/loader/JavaExitSuppressionHook.kt core/loader/src/main/java/com/multiapp/core/loader/PackerRuntimeStage.kt core/loader/src/test/java/com/multiapp/core/loader/JavaExitSuppressionHookTest.kt
git commit -m "diagnose(loader): exit 拦截全量栈诊断——定位加固看门狗自杀点"
```

---
### Task 2: 主进程死亡保留组件进程状态（微信 attach 修复）

**Files:**
- Modify: `core/engine/src/main/java/com/multiapp/core/engine/EngineServerRuntime.kt`
- Test: `core/engine/src/test/java/com/multiapp/core/engine/EngineServerRuntimeTest.kt`

- [ ] **Step 1: 写失败测试**（期望：票据在死亡前签发，死亡后 attach 仍成功）

在 `EngineServerRuntimeTest.kt` 追加（沿用现有 `providerRuntime()` / `componentProcessIdentity()` 测试辅助）：

```kotlin
@Test
fun `component process attach succeeds after runtime death when ticket issued before death`() {
    val runtimeRegistry = EngineRuntimeRegistry()
    val runtime = providerRuntime()
    runtimeRegistry.register(runtime)
    val processId = requireNotNull(runtime.processId) + 20
    val owner = EngineServerRuntime.createForTest(
        hostPackageName = runtime.hostPackageName,
        instanceManager = mockk<InstanceManager>(relaxed = true),
        virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
        activityLauncher = EngineActivityLauncher { },
        runtimeRegistry = runtimeRegistry,
        systemServer = DefaultVirtualSystemServer(runtimeRegistry),
        componentProcessIdentityProbe = EngineComponentProcessIdentityProbe { candidatePid ->
            if (candidatePid == processId) {
                EngineComponentProcessHostIdentity(
                    processName = "${runtime.hostPackageName}:v1",
                    processStartTicks = processId.toLong() * 10L
                )
            } else null
        }
    )
    val assignment = requireNotNull(owner.allocateComponentProcessSlot(runtime.instanceId, ":remote"))
    runtimeRegistry.markDeadIfCurrent(
        EngineProcessClientIdentity(
            instanceId = runtime.instanceId,
            runtimeEpoch = runtime.runtimeEpoch,
            engineSessionId = runtime.engineSessionId,
            processSlot = runtime.processSlot,
            processId = requireNotNull(runtime.processId),
            processStartTicks = 1L
        )
    )
    val token = mockk<IBinder>(relaxed = true)
    every { token.isBinderAlive } returns true
    val identity = componentProcessIdentity(runtime, assignment.processSlot, processId)
    val attached = owner.attachComponentProcessClient(identity, token, processId, identity.processSlot)
    assertTrue(attached.accepted, "death should not block in-flight component attach: ${attached.reason}")
    assertTrue(owner.componentProcessClients.isAuthoritative(identity, token))
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest --tests "com.multiapp.core.engine.EngineServerRuntimeTest"
```

Expected: FAIL（死亡后 attach 返回 `component_process_runtime_not_live`）。

- [ ] **Step 3: 实现——generationCleanup 不再 revoke 组件进程状态**

`EngineServerRuntime.kt` 的 `generationCleanup` lambda（L567-601）删除以下三行（保留 service leases/connections/provider endpoints/route tokens 的 revoke）：

```kotlin
// 删除：componentProcessSlots.revokeGeneration(...)
// 删除：componentProcessClients.revokeGeneration(...)
// 删除：componentProcessLaunchCapabilities.revokeGeneration(...)
```

`ephemeralInstanceCleanup`（L636-646）保持不变——实例删除仍全量清理。

- [ ] **Step 4: 实现——死亡状态允许孤儿组件 attach**

`validateComponentProcessClientIdentity`（L353-355）改为：

```kotlin
if (runtime.state == VirtualRuntimeState.STOPPED) {
    return "component_process_runtime_not_live"
}
// DEAD 允许：主进程死亡后，已分配的组件进程仍可在宽限窗口内完成 attach（孤儿收尾）。
```

- [ ] **Step 5: 运行测试确认通过**

同上命令，Expected: PASS（含新测试 + 既有组件进程测试不回归）。

- [ ] **Step 6: 检查并修正依赖旧语义的既有测试**

运行 `.\gradlew.bat :core:engine:testDebugUnitTest`，若既有测试断言「死亡后组件状态被清空」，按新语义更新断言（死亡保留、删除/重启才清空）。

- [ ] **Step 7: Commit**

```powershell
git add core/engine/src/main/java/com/multiapp/core/engine/EngineServerRuntime.kt core/engine/src/test/java/com/multiapp/core/engine/EngineServerRuntimeTest.kt
git commit -m "fix(engine): 主进程死亡保留组件进程状态——微信 sandbox attach 不再 not_found"
```

---

### Task 3: slot 自证认领（无票据组件进程路径）

**Files:**
- Modify: `core/engine/src/main/java/com/multiapp/core/engine/EngineComponentProcessSlotAllocator.kt`
- Modify: `core/engine/src/main/java/com/multiapp/core/engine/EngineComponentProcessIpc.kt`
- Modify: `core/engine/src/main/java/com/multiapp/core/engine/EngineRuntimeIpc.kt`
- Modify: `core/engine/src/main/java/com/multiapp/core/engine/EngineServerRuntime.kt`
- Modify: `app/src/main/java/com/multiapp/app/container/HostedServiceRuntimeBinder.kt`
- Test: `core/engine/src/test/java/com/multiapp/core/engine/EngineServerRuntimeTest.kt`、`core/engine/src/test/java/com/multiapp/core/engine/EngineComponentProcessEndpointSecurityTest.kt`

- [ ] **Step 1: allocator 增加反查与每 slot epoch**

`EngineComponentProcessSlotAllocator.kt` 增加：

```kotlin
private val processEpochsBySlot = linkedMapOf<String, Long>()

/** 为该 slot 分配下一个进程 epoch（同 slot 新进程单调递增）。 */
@Synchronized
fun nextProcessEpoch(processSlot: String): Long {
    validateComponentProcessSlotText("processSlot", processSlot)
    val next = (processEpochsBySlot[processSlot] ?: 0L) + 1L
    processEpochsBySlot[processSlot] = next
    return next
}
```

（`ownerOf(processSlot)` 已存在于 allocator，直接复用。）

- [ ] **Step 2: IPC 增加 attachComponentProcessBySlot 操作**

`EngineComponentProcessIpc.kt`：

```kotlin
internal const val COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION = "attachComponentProcessBySlot"
```

在 `EngineComponentProcessAuthority` 接口增加：

```kotlin
fun attachBySlot(
    instanceId: String,
    processSlot: String,
    clientToken: IBinder,
    callingPid: Int,
    callingProcessName: String?,
    callingProcessStartTicks: Long?
): EngineComponentProcessOperationResult
```

`COMPONENT_PROCESS_OPERATIONS` 集合加入新操作；`hasValidComponentProcessResultShape` 的 `when` 增加分支（与 ATTACH 同规则：`!alreadyRunning && launchTicket == null && processState?.live == true`）。
- [ ] **Step 3: EngineServerRuntime 实现 attachBySlot**

在 `EngineServerRuntime.kt` 的 `EngineComponentProcessAuthority` 实现处新增：

```kotlin
override fun attachBySlot(
    instanceId: String,
    processSlot: String,
    clientToken: android.os.IBinder,
    callingPid: Int,
    callingProcessName: String?,
    callingProcessStartTicks: Long?
): EngineComponentProcessOperationResult = synchronized(componentProcessAttachLock) {
    val owner = componentProcessSlots.ownerOf(processSlot)
        ?: return@attachBySlot componentProcessRejected(
            COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId,
            "component_process_slot_unallocated"
        )
    if (owner.instanceId != instanceId) {
        return@attachBySlot componentProcessRejected(
            COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId,
            "component_process_slot_instance_mismatch"
        )
    }
    val runtime = runtimeRegistry.get(instanceId)
        ?: return@attachBySlot componentProcessRejected(
            COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId,
            "component_process_runtime_not_found"
        )
    if (
        runtime.runtimeEpoch != owner.runtimeEpoch ||
        runtime.engineSessionId != owner.engineSessionId
    ) {
        return@attachBySlot componentProcessRejected(
            COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId,
            "component_process_runtime_generation_mismatch"
        )
    }
    if (runtime.state == VirtualRuntimeState.STOPPED) {
        return@attachBySlot componentProcessRejected(
            COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId,
            "component_process_runtime_not_live"
        )
    }
    if (callingProcessName != processSlot) {
        return@attachBySlot componentProcessRejected(
            COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId,
            "component_process_android_name_mismatch"
        )
    }
    val queried = componentProcessClients.queryByKey(instanceId, owner.guestProcessName)
    if (
        queried.found && queried.identity != null && queried.clientToken != null &&
        queried.identity.processId == callingPid &&
        isComponentProcessIdentityAuthoritative(queried.identity, queried.clientToken)
    ) {
        return@attachBySlot componentProcessRunning(
            identity = queried.identity,
            idempotent = true,
            reason = "component_process_already_running"
        )
    }
    val identity = EngineComponentProcessClientIdentity(
        instanceId = owner.instanceId,
        runtimeEpoch = owner.runtimeEpoch,
        engineSessionId = owner.engineSessionId,
        processEpoch = componentProcessSlots.nextProcessEpoch(processSlot),
        clientSessionId = UUID.randomUUID().toString(),
        effectiveGuestProcessName = owner.guestProcessName,
        processSlot = processSlot,
        processId = callingPid,
        processStartTicks = callingProcessStartTicks ?: 0L
    )
    val attached = attachComponentProcessClient(
        identity,
        clientToken,
        callingPid,
        callingProcessName,
        callingProcessStartTicks
    )
    if (attached.accepted && attached.identity != null) {
        componentProcessAttached(attached.identity, attached.idempotent)
    } else {
        componentProcessRejected(
            COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId,
            attached.reason
        )
    }
}
```

（`componentProcessRunning` / `componentProcessAttached` / `componentProcessRejected` 沿用现有私有辅助。）

- [ ] **Step 4: IPC endpoint + client**

`EngineRuntimeIpc.kt` 的 endpoint 增加（按现有 `attachComponentProcessClient` 模式补齐 Binder 方法声明与实现）：

```kotlin
override fun attachComponentProcessBySlot(
    instanceId: String,
    processSlot: String,
    clientToken: IBinder
): Bundle = authorizedBundle {
    if (instanceId.isBlank() || processSlot.isBlank()) {
        return@authorizedBundle componentProcessRejected(
            COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId,
            "invalid_component_process_slot_attach"
        )
    }
    val authority = componentProcessAuthority
        ?: return@authorizedBundle componentProcessRejected(
            COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION,
            instanceId,
            "component_process_authority_unavailable"
        )
    val callerPid = callingPid()
    authority.attachBySlot(
        instanceId = instanceId,
        processSlot = processSlot,
        clientToken = clientToken,
        callingPid = callerPid,
        callingProcessName = callingProcessName(callerPid),
        callingProcessStartTicks = callingProcessStartTicks(callerPid)
    ).toComponentProcessIpcBundle(ipcBundleFactory)
}
```

`EngineRuntimeIpcClients` 增加：

```kotlin
fun attachComponentProcessBySlot(
    instanceId: String,
    processSlot: String,
    clientToken: IBinder
): EngineComponentProcessOperationResult? {
    if (instanceId.isBlank() || processSlot.isBlank()) return null
    val response = runCatching {
        activeService()?.attachComponentProcessBySlot(instanceId, processSlot, clientToken)
    }.getOrNull() ?: return null
    return response.toComponentProcessOperationResultOrNull()
        ?.takeIf { result ->
            result.operation == COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION &&
                result.instanceId == instanceId &&
                (!result.accepted || result.processState?.let { state ->
                    state.instanceId == instanceId && state.processSlot == processSlot
                } == true)
        }
}
```

- [ ] **Step 5: HostedServiceRuntimeBinder 无票据自证路径**

`HostedServiceRuntimeBinder.kt` 构造函数新增注入参数：

```kotlin
private val componentBySlotAttacher: (
    String,
    String,
    IBinder
) -> EngineComponentProcessOperationResult? = EngineRuntimeIpcClients::attachComponentProcessBySlot,
```

`ensureBound` 中「`!primaryAuthority.allowed && componentAuthority == null && launchTicket == null`」分支改为先按 slot 自证：

```kotlin
if (!primaryAuthority.allowed && componentAuthority == null && launchTicket == null) {
    val bySlotAttach = componentBySlotAttacher(route.instanceId, route.processSlot.orEmpty(), processToken)
    val bySlotMatches = bySlotAttach?.accepted == true &&
        bySlotAttach.processState?.instanceId == route.instanceId &&
        bySlotAttach.processState?.processSlot == route.processSlot
    if (bySlotMatches) {
        pendingAttachDetail = "componentProcessAttachedBySlot"
        componentAuthority = bySlotAttach
    } else {
        return HostedServiceRuntimeBindResult.Failed(
            instanceId = route.instanceId,
            processSlot = route.processSlot,
            errorClassName = SecurityException::class.java.name,
            errorMessage = bySlotAttach?.reason ?: "component_process_attach_ipc_unavailable",
            detail = "componentProcessAttachFailed"
        )
    }
}
```

`providerHookEnabled` 的计算保持原逻辑（组件路径走 `effectiveGuestProcessName`）。
- [ ] **Step 6: 写/跑测试**

`EngineServerRuntimeTest.kt` 追加：

```kotlin
@Test
fun `component process attach by slot works without ticket using real process identity`() {
    val runtimeRegistry = EngineRuntimeRegistry()
    val runtime = providerRuntime()
    runtimeRegistry.register(runtime)
    val processId = requireNotNull(runtime.processId) + 30
    val owner = EngineServerRuntime.createForTest(
        hostPackageName = runtime.hostPackageName,
        instanceManager = mockk<InstanceManager>(relaxed = true),
        virtualInstallService = mockk<VirtualInstallService>(relaxed = true),
        activityLauncher = EngineActivityLauncher { },
        runtimeRegistry = runtimeRegistry,
        systemServer = DefaultVirtualSystemServer(runtimeRegistry),
        componentProcessIdentityProbe = EngineComponentProcessIdentityProbe { candidatePid ->
            if (candidatePid == processId) {
                EngineComponentProcessHostIdentity(
                    processName = "${runtime.hostPackageName}:v1",
                    processStartTicks = processId.toLong() * 10L
                )
            } else null
        }
    )
    val assignment = requireNotNull(owner.allocateComponentProcessSlot(runtime.instanceId, ":remote"))
    val token = mockk<IBinder>(relaxed = true)
    every { token.isBinderAlive } returns true
    val attached = owner.attachBySlot(
        instanceId = runtime.instanceId,
        processSlot = assignment.processSlot,
        clientToken = token,
        callingPid = processId,
        callingProcessName = assignment.processSlot,
        callingProcessStartTicks = processId.toLong() * 10L
    )
    assertTrue(attached.accepted, "by-slot self-attach must be accepted: ${attached.reason}")
    assertEquals(COMPONENT_PROCESS_ATTACH_BY_SLOT_OPERATION, attached.operation)
    val state = requireNotNull(attached.processState)
    val identity = EngineComponentProcessClientIdentity(
        instanceId = runtime.instanceId,
        runtimeEpoch = runtime.runtimeEpoch,
        engineSessionId = runtime.engineSessionId,
        processEpoch = state.processEpoch,
        clientSessionId = state.effectiveGuestProcessName,
        effectiveGuestProcessName = state.effectiveGuestProcessName,
        processSlot = state.processSlot,
        processId = state.processId,
        processStartTicks = processId.toLong() * 10L
    )
    assertTrue(owner.componentProcessClients.isAuthoritative(identity, token))
}
```

`EngineComponentProcessEndpointSecurityTest.kt` 追加拒绝矩阵（空 instanceId / 未分配 slot / 进程名不匹配）。

运行：

```powershell
.\gradlew.bat :core:engine:testDebugUnitTest
```

Expected: PASS。

- [ ] **Step 7: Commit**

```powershell
git add core/engine/src/main/java/com/multiapp/core/engine/EngineComponentProcessSlotAllocator.kt core/engine/src/main/java/com/multiapp/core/engine/EngineComponentProcessIpc.kt core/engine/src/main/java/com/multiapp/core/engine/EngineRuntimeIpc.kt core/engine/src/main/java/com/multiapp/core/engine/EngineServerRuntime.kt app/src/main/java/com/multiapp/app/container/HostedServiceRuntimeBinder.kt core/engine/src/test
git commit -m "feat(engine): slot 自证认领——无票据组件进程按 slot+身份 attach（对齐开源 initProcess 思路）"
```

---

### Task 4: 通用进程名还原（含 native cmdline）

**Files:**
- Create: `core/loader/src/main/java/com/multiapp/core/loader/GuestProcessNameCompat.kt`
- Test: `core/loader/src/test/java/com/multiapp/core/loader/GuestProcessNameCompatTest.kt`
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/BootstrapStageContract.kt`
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt`
- Modify: `core/loader/src/main/java/com/multiapp/core/loader/PackerRuntimeStage.kt`

- [ ] **Step 1: 写失败测试**

`GuestProcessNameCompatTest.kt`：

```kotlin
class GuestProcessNameCompatTest {

    @Test
    fun `resolveGuestProcessName prefers effective name over origin package`() {
        assertEquals(
            "com.tencent.mm:sandbox",
            GuestProcessNameCompat.resolveGuestProcessName(
                originPackageName = "com.tencent.mm",
                effectiveGuestProcessName = "com.tencent.mm:sandbox"
            )
        )
        assertEquals(
            "cn.wps.moffice_eng",
            GuestProcessNameCompat.resolveGuestProcessName(
                originPackageName = "cn.wps.moffice_eng",
                effectiveGuestProcessName = null
            )
        )
    }

    @Test
    fun `generic getter methods resolve from platform stubs`() {
        assertNotNull(GuestProcessNameCompat.resolveApplicationGetProcessName())
        assertNotNull(GuestProcessNameCompat.resolveProcessMyProcessName())
    }
}
```

- [ ] **Step 2: 运行确认失败**

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --tests "com.multiapp.core.loader.GuestProcessNameCompatTest"
```

Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现 GuestProcessNameCompat**

```kotlin
package com.multiapp.core.loader

import android.util.Log
import com.multiapp.core.hook.HookEngine

/**
 * 通用 guest 进程名还原（对齐 VirtualApp 运行期进程名还原思路）。
 *
 * 组件进程/主进程在 attach 拿到 effectiveGuestProcessName 后统一安装：
 *  - Application.getProcessName()
 *  - ActivityThread.currentProcessName()
 *  - Process.myProcessName()
 * 全部返回 guest 进程名（如 com.tencent.mm:sandbox / cn.wps.moffice_eng），
 * 让微信看门狗、WPS RePlugin 等按进程名分流的框架看到「自己人」的名字。
 */
object GuestProcessNameCompat {

    private const val TAG = "GuestProcessNameCompat"

    data class HookResult(
        val applicationGetProcessNameHooked: Boolean = false,
        val activityThreadCurrentProcessNameHooked: Boolean = false,
        val processMyProcessNameHooked: Boolean = false
    ) {
        val anyHooked: Boolean
            get() = applicationGetProcessNameHooked ||
                activityThreadCurrentProcessNameHooked ||
                processMyProcessNameHooked
    }

    /** 纯决策：effective 优先，否则退回 origin 包名。JVM 可测。 */
    fun resolveGuestProcessName(
        originPackageName: String,
        effectiveGuestProcessName: String?
    ): String = effectiveGuestProcessName
        ?.takeIf { it.isNotBlank() }
        ?: originPackageName

    fun resolveApplicationGetProcessName(): java.lang.reflect.Method? =
        runCatching {
            android.app.Application::class.java.getMethod("getProcessName")
        }.getOrNull()

    fun resolveProcessMyProcessName(): java.lang.reflect.Method? =
        runCatching {
            android.os.Process::class.java.getMethod("myProcessName")
        }.getOrNull()

    fun resolveActivityThreadCurrentProcessName(): java.lang.reflect.Method? =
        runCatching {
            val clazz = Class.forName("android.app.ActivityThread")
            clazz.declaredMethods.firstOrNull {
                it.name == "currentProcessName" &&
                    it.parameterCount == 0 &&
                    it.returnType == String::class.java
            }?.also { it.isAccessible = true }
        }.getOrNull()

    fun install(
        guestProcessName: String,
        hookEngine: HookEngine
    ): HookResult {
        fun installReturnHook(method: java.lang.reflect.Method?): Boolean {
            if (method == null) return false
            return runCatching {
                hookEngine.hookMethod(
                    method,
                    afterCallback = { _, _, _ ->
                        safeLog("process name getter intercepted - $guestProcessName")
                        guestProcessName
                    }
                )
            }.getOrElse { e ->
                safeLog("hook install failed on ${method.declaringClass.name}.${method.name}: " +
                    "${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
        val result = HookResult(
            applicationGetProcessNameHooked = installReturnHook(resolveApplicationGetProcessName()),
            activityThreadCurrentProcessNameHooked =
                installReturnHook(resolveActivityThreadCurrentProcessName()),
            processMyProcessNameHooked = installReturnHook(resolveProcessMyProcessName())
        )
        safeLog("Guest process-name hooks: $result")
        return result
    }

    private fun safeLog(message: String) {
        runCatching { Log.d(TAG, message) }
    }
}
```

- [ ] **Step 4: BootstrapStageInput 增加 effectiveGuestProcessName**

`BootstrapStageContract.kt` 的 `BootstrapStageInput` 增加：

```kotlin
val effectiveGuestProcessName: String? = null,
```

`HostedRuntimeBootstrap.kt` 组装 `preparedContext`/Packer 输入处传 `effectiveGuestProcessName`（该类已有该字段，`prepare` 链中补到 BootstrapStageInput）。
- [ ] **Step 5: PackerRuntimeStage 统一安装**

`PackerRuntimeStage.kt`：

native spoof 改为 effective 名：

```kotlin
val guestProcessName = input.effectiveGuestProcessName?.takeIf(String::isNotBlank)
    ?: instance.originPackageName
NativeHookBridge.getInstance().spoofProcSelf(android.os.Process.myPid(), guestProcessName)
```

进程名 hook 改为全部 guest 安装（替换原 `if (MicroMsgProcessNameCompat.isMicroMsgPackage(...))` 包裹的通用部分）：

```kotlin
val genericProcessNameHook = GuestProcessNameCompat.install(
    guestProcessName = guestProcessName,
    hookEngine = HookEngine.getInstance()
)
runCatching {
    android.util.Log.d("PackerRuntimeStage", "Guest process-name hooks: " + genericProcessNameHook)
}
if (MicroMsgProcessNameCompat.isMicroMsgPackage(instance.originPackageName)) {
    // 保留微信混淆类特判 hook（绕过通用 getter 直接读 in5.f1.a()）+ getDataDir fallback
}
```

- [ ] **Step 6: 跑测试**

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest
```

Expected: PASS（含 GuestProcessNameCompatTest + PackerRuntimeStageTest 不回归；若 PackerRuntimeStageTest 断言 MicroMsg 分支日志，按新结构更新）。

- [ ] **Step 7: Commit**

```powershell
git add core/loader/src/main/java/com/multiapp/core/loader/GuestProcessNameCompat.kt core/loader/src/test/java/com/multiapp/core/loader/GuestProcessNameCompatTest.kt core/loader/src/main/java/com/multiapp/core/loader/BootstrapStageContract.kt core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt core/loader/src/main/java/com/multiapp/core/loader/PackerRuntimeStage.kt
git commit -m "feat(loader): 通用 guest 进程名还原——Java 三入口 + native cmdline（WPS RePlugin 兼容）"
```

---

### Task 5: 全量单测回归

**Files:** 无（仅运行）

- [ ] **Step 1: 全量 JVM 单测**

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :core:engine:testDebugUnitTest :app:testDebugUnitTest
```

Expected: 全绿（记录各模块总数）。

- [ ] **Step 2: 检查 diff 是否只含计划内文件**

```powershell
git status --short
```

Expected: 仅上述任务涉及的文件（忽略 `*_probe.txt`、`deliverables/` 残留）。

- [ ] **Step 3: Commit（若有遗漏调整）**

```powershell
git add -u
git commit -m "test: 组件进程对齐回归——全量单测绿"
```

---

### Task 6: 真机复跑验证

**Files:** 无（设备验证）

- [ ] **Step 1: 前置设备状态**

```powershell
$adb='C:\Users\20237\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb shell svc power stayon true
& $adb shell input keyevent KEYCODE_WAKEUP
& $adb shell wm dismiss-keyguard
& $adb shell appops set com.multiapp.app 10021 allow
```

- [ ] **Step 2: 构建并安装**

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
& $adb install -r app/build/outputs/apk/debug/app-debug.apk
& $adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

（若产出路径与现有 `app-hosted-debug.apk` 命名不同，以实际文件为准。）

- [ ] **Step 3: 清空 logcat 并复跑**

```powershell
& $adb shell am force-stop com.multiapp.app
& $adb logcat -c
& $adb shell am instrument -w -r -e class com.multiapp.app.RealAppCompatibilitySmokeTest com.multiapp.app.test/com.multiapp.app.TestRunner
```

- [ ] **Step 4: 判定通过条件**

```powershell
& $adb shell run-as com.multiapp.app cat files/compat_dossier/2026-08-01.txt
```

Expected:
- `com.tencent.mm` → BOOTSTRAPPED
- `cn.wps.moffice_eng` → BOOTSTRAPPED
- 其余 5 个（起点/微博/酷狗/高德/Minimal）→ BOOTSTRAPPED（不回归）
- logcat 不再出现 `component_process_launch_capability_not_found`；若出现，检查 exit 全量栈定位新自杀点

- [ ] **Step 5: 汇总证据**

把 dossier + logcat 关键段落到 `docs/packed-app-compat-campaign.md` 追加 Round 9 记录，提交：

```powershell
git add docs/packed-app-compat-campaign.md
git commit -m "docs(compat): Round 9 真机结果——微信/WPS 组件进程对齐验证"
```

---

## 风险与回退

- **死亡保留的副作用**：实例删除/重启已由 `ephemeralInstanceCleanup`/generation 递增自动清理；若发现新路径（如实例置 STOPPED 后组件进程仍 attach），Task 2 Step 6 的单测会暴露。
- **by-slot 自证的信任边界**：以 host UID + `/proc/<pid>/cmdline` == slot + slot 分配表三方校验为界，与现有票据路径同信任模型；security 测试矩阵必须覆盖。
- **WPS 可能不止进程名问题**：若 Round 9 仍 FAIL，按 spec 边界「RePlugin 完整插件系统兼容」单独立项，保留本次证据。

## 验收标准（对照 spec）

| spec 要求 | 任务 |
|---|---|
| 诊断先行（全量退出栈） | Task 1 |
| registry → slot 分配表 + 自证 attach | Task 2 + 3 |
| HostedServiceRuntimeBinder 无票据自证路径 | Task 3 Step 5 |
| 进程名还原通用化（Java + native） | Task 4 |
| 单测矩阵 + 设备复跑 | Task 5 + 6 |
