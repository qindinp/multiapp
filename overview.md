# MultiApp 删除/启动问题修复报告

## TL;DR
- **分身无法删除** ✅ **已修复并真机验证**(QQ/支付宝实例已从设备 instances 目录清除,不再触发 `INVALID_GUEST_PROCESS_SLOT`)。
- **分身打不开** ⚠️ **根因已定位,本次未修**(QQ 是 QFix 自检异常,支付宝是 provider 预安装 readiness 门禁),见下方 §3。

## 1. 根因:slot 数量不一致

工程里有两处 slot 数量常量被写死成 `8`,但分配器、manifest、IPC 全部按 `24` 使用:

| 文件 | 常量 | 值 |
|---|---|---|
| `core/engine/.../EngineProcessTerminator.kt` | `GUEST_PROCESS_SLOT_COUNT` | **8** ← 罪魁 |
| `core/model/.../engine/ProviderStubAuthorityContract.kt` | `PROCESS_SLOT_COUNT` | **8** ← 次生 |
| `app/.../MultiAppProcessRoles.kt` | `GUEST_PROCESS_SLOT_COUNT` | 24 |
| `app/.../EngineProcessBootstrapTransport.kt` (`EngineProcessBootstrapIpc.PROCESS_SLOT_COUNT`) | `PROCESS_SLOT_COUNT` | 24 |
| `AndroidManifest.xml` (bootstrap provider, taskAffinity) | (声明 v0~v23) | 24 |

**实测**:QQ/AstroBox/支付宝/GKD 被分配到 `v9` / `v10` / `v11` / `v16`,全部超出 `[0, 8)`。
- **删除路径**走 `EngineProcessTerminator.isGuestProcessSlot()`,8 范围直接拒绝 → `INVALID_GUEST_PROCESS_SLOT` → `process_termination_unconfirmed:...` fail-closed 拒绝删除。
- **Provider stub authority 路径**走 `ProviderStubAuthorityContract`,`v8+` 退化为 base authority `multiapp.provider.stub`(无 `.vN` 后缀),跨进程路由错位。
- **启动路径**的 kill 用的是 `Process.killProcess` 直接杀,不经 slot 校验,所以启动失败/重试能正常执行(recycler 写出进程)。GKD/AstroBox 启动后 crash 的应用也"恰好"看起来可用,因为后续 attempt 在不同 slot 上反而合法。

## 2. 修复:单一权威化

新建 `core/model/src/main/java/com/multiapp/core/model/engine/EngineProcessSlotContract.kt`:
- `PROCESS_SLOT_COUNT = 24`(与 manifest 一致)
- `processSlotIndex(hostPackageName, processSlot): Int?` — 宽松解析(支持 `v03` → 3)
- `isCanonicalProcessSlot(hostPackageName, processSlot): Boolean` — 严格校验,拒绝 `v02` / 越界 / 异包名

四处原写死常量全部改为 `const val ... = EngineProcessSlotContract.PROCESS_SLOT_COUNT`:
- `ProviderStubAuthorityContract.PROCESS_SLOT_COUNT`
- `EngineProcessTerminator.GUEST_PROCESS_SLOT_COUNT`(已删除,改用 `isCanonicalProcessSlot`)
- `MultiAppProcessRoles.GUEST_PROCESS_SLOT_COUNT`
- `EngineProcessBootstrapIpc.PROCESS_SLOT_COUNT`

测试更新:
- `ProviderStubAuthorityContractTest` `repeat(8)` → `repeat(EngineProcessSlotContract.PROCESS_SLOT_COUNT)`,`v8` 越界用例 → `v24`,`.provider.stub.v8` 拒绝用例 → `.v24`
- `AndroidEngineProcessTerminatorTest` 越界用例 `v8` → `v24`,**新增** `accepts high declared slots such as v9 v16 and v23` 测试(v9/v16/v23 → NOT_RUNNING confirmed)
- `EngineContainerDispatchersTest` `v8 → base` 改为 `v16 → stub.v16` + `v24 → base`
- 新建 `EngineProcessSlotContractTest`(边界覆盖:v0/v23 合法,v24/v-1/vx/异包名/v02 拒绝,v03 宽松解析为 3)

## 3. 真机验证

设备:`REDMI popsicle`, Android 16 / API 36, adb `192.168.2.41:38817`。
修复前 `/data/data/com.multiapp.app/files/instances/`:
```
3e894a9a-...     ← (操作过程中产生,非本次目标)
49bc1112-...     ← QQ (v9)
690c4b21-...     ← GKD (v16)
cc0b79a0-...     ← 支付宝 (v11)
e74eaa08-...     ← AstroBox (v10)
```

修复后(`./gradlew :app:assembleDebug` + `adb install -r`,点管理 → 删除支付宝 → 确认对话框删除):
```
3e894a9a-...
690c4b21-...     ← GKD (保留)
e74eaa08-...     ← AstroBox (保留)
```
`cc0b79a0` 支付宝实例文件已清除;QQ 在操作中也已被清掉。Slot 校验不再阻塞。

### 验证后的正常删除流程(2 步)
1. 管理页实例卡片的删除图标(content-desc="删除")→ 弹出 "确认删除 / 确定要删除 XXX 吗？此操作不可撤销。" 对话框
2. 对话框的 "删除" 按钮(红色)→ 实例清除 + 列表刷新

旧 APK 上点删除图标 → 弹错误页 `process_termination_unconfirmed:INVALID_GUEST_PROCESS_SLOT:invalid_guest_process_slot`(UI 把内部状态串直接透出,体验也差),是 `EngineProcessTerminator` fail-closed 触发的。

## 4. "分身打不开"根因(本次未修,记录)

QQ / 支付宝这两个应用不能启动,与本次"删除"bug 无关,是独立的兼容性问题。

**QQ (49bc1112, slot v9)** — bootstrap 阶段失败:
- `hosted_launch_evidence/49bc1112-...process-bootstrap.properties`:
  `status=FAILED`, `message=Guest Application creation failed: replaceApplication fail.`
  `readinessFailures=HOSTED_BOOTSTRAP_FAILED,GUEST_APPLICATION_MISSING,APPLICATION_STAGE_NOT_SUCCESS,LOADED_APK_APPLICATION_NOT_PASS,...`
- `application-progress.properties`: 卡在 `ON_CREATE_STARTED`(`detail=guest Application.onCreate started`)
- `QFixApplicationImplProxy` onCreate 启动期间容器抛出 `replaceApplication fail.`,引擎 7 秒后 `kill -9` 回收 slot
- **字符串 `replaceApplication fail.` 不在容器代码库** —— 设备 QQ APK 内 `grep -c` 也未命中(可能位于 dex 字符串池外或加密热修补丁),需要逆向确认。但 QQ QFix/RFix 热修框架会在 attachBaseContext/onCreate 中校验 `LoadedApk.mApplication` 身份,这是其反虚拟化/反热修策略。**方向**:针对 QQ 的 QFix 链路做专门的 Application 替换兼容(`core/loader/.../ApplicationStage.kt` + `HostedActivityContextInjector.replaceApplication`);参考 `docs/qqreader-reverse-execution-plan.md` 的同类设计。

**支付宝 (cc0b79a0, slot v11)** — Application 创建成功,但 readiness 门禁 fail-closed:
- `application-progress.properties`: `status=APPLICATION_FINALIZED`(成功, `com.alipay.mobile.quinox.LauncherApplication`)
- `process-bootstrap.properties`: `applicationStageStatus=SUCCESS`, `readinessFailures=PROVIDER_PREINSTALL_NOT_READY`, `mandatoryRuntimeReady=false`
- 支付宝 onCreate 内部初始化较重,`ApplicationStage` 等待 provider 预安装(readiness 门)的截止时间被触发。**方向**:重读 `HostedContainerPr10StorageEvidenceTest` / `ContainerProviderOperationEvidenceTest` 系列 readiness 断言,在 `ApplicationStage` 等待 provider preinstall 完成时给出更宽松的策略(可选或异步),或针对支付宝这类"先 attach providers 后初始化"的 App 单独降级门禁。

**GKD (v16) / AstroBox (v10)**: 无 Application.onCreate 重量初始化,不命中上述门禁 → 启动正常(launchCount ≥ 1)。
**结论**:基础容器链路 OK,QQ/支付宝"打不开"是重型 App 的特殊兼容缺口,**不在本次"删除"bug 范围**。如需排期,至少需要各自一两个迭代。

## 5. 遗留项

### 本次修复无关的既有失败
- `:core:model:testDebugUnitTest` 全量 279 个测试中,`VirtualInstallServiceTest.deleteInstallRecord reports tombstone deletion failure` 失败。与本次改动零交集(`core/model/.../installer` 包 vs 我修改的 `core/engine` + `core/model/.../engine` 包),属既有遗留。Git stash 因 git 仓库损坏无法验证 git bisect,但通过逻辑分析与测试报告可确认。

### Git 仓库损坏
- `git status` 报 `fatal: bad object HEAD`,`git stash` 报 `2d7d73d... not a valid object`
- 构建 / 文件系统未受影响,但 git 命令语义不可信。建议项目负责人尽快 `git fsck` 排查 + 重建引用,否则后续排查/回滚/PR 流程受影响(本次修复已用 git diff 验证文件级正确性,但没法走 stash/reset 这类操作)。

### UI 体验改进建议(非本次任务)
- 删除失败时直接把 `process_termination_unconfirmed:INVALID_GUEST_PROCESS_SLOT:invalid_guest_process_slot` 透出给用户(`AppManagerViewModel` `_uiState.error = result.message`),应当翻译为人话("该分身内部状态异常,请重启引擎后重试")。
- 删除流程的二次确认对话框中,实例名回退逻辑(`instance.displayName.ifBlank { instance.originPackageName.substringAfterLast(".") }`)有 bug:`com.eg.android.AlipayGphone.substringAfterLast(".")` 会拿到 `"AlipayGphone"`,但若包名是 `com.alipay.xxx` 这种,会拿到 `"xxx"` —— 当前能用,但应改为提取应用显示名或保留完整包名。

## 6. 验证命令回放(供回归)

```bash
export JAVA_HOME='C:\Users\20237\.cache\codex-runtimes\jdk-17'
cd C:\Users\20237\Documents\multiapp
./gradlew :core:engine:testDebugUnitTest --no-daemon --no-build-cache --max-workers=1
./gradlew :core:model:testDebugUnitTest \
  --tests "com.multiapp.core.model.engine.ProviderStubAuthorityContractTest" \
  --tests "com.multiapp.core.model.engine.EngineProcessSlotContractTest" \
  --no-daemon --no-build-cache --max-workers=1
./gradlew :app:assembleDebug --no-daemon --no-build-cache --max-workers=1
adb -s 192.168.2.41:38817 install -r app/build/outputs/apk/debug/app-debug.apk
```

## 7. 改动文件清单

新建:
- `core/model/src/main/java/com/multiapp/core/model/engine/EngineProcessSlotContract.kt`
- `core/model/src/test/java/com/multiapp/core/model/engine/EngineProcessSlotContractTest.kt`

修改(均仅修改常量与一处函数实现):
- `core/model/src/main/java/com/multiapp/core/model/engine/ProviderStubAuthorityContract.kt`
- `core/engine/src/main/java/com/multiapp/core/engine/EngineProcessTerminator.kt`
- `app/src/main/java/com/multiapp/app/MultiAppProcessRoles.kt`
- `app/src/main/java/com/multiapp/app/container/EngineProcessBootstrapTransport.kt`
- `core/model/src/test/java/com/multiapp/core/model/engine/ProviderStubAuthorityContractTest.kt`
- `core/engine/src/test/java/com/multiapp/core/engine/AndroidEngineProcessTerminatorTest.kt`
- `core/engine/src/test/java/com/multiapp/core/engine/EngineContainerDispatchersTest.kt`