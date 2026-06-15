# MultiApp 团队执行方案

日期：2026-06-12  
状态：待用户审核批准。未批准前只做文档和只读审核，不执行源码重构、不提交、不清理 `.tmp`。

## 1. 团队角色与审核结论

本轮启动了 5 个只读审核角色：

| 角色 | 关注范围 | 核心结论 |
|---|---|---|
| Android Runtime 架构师 | `LoaderFactory`、双身份、虚拟系统服务、HookPipeline | 当前最大问题是 `LoaderFactory` 巨石化，缺少可验证 runtime contract；Phase 1 应先做阶段结果和边界拆分，不急着加更多 hook。 |
| Native/NDK 与加固壳工程师 | `native-hook.cpp`、`NativeHookBridge`、`RegisterNatives`、`FindClass`、GOT hook | native probe 和 patch 混在一起，`FindClass/RegisterNatives/GOT` 作用域过大；正式方案必须 scoped、profile-controlled。 |
| 构建/打包/工具链工程师 | `StubBuilder`、manifest、offline patch、loader.dex | 缺少不可变 build manifest、PatchProfile、loader/native/origin hash 追溯；`originalSignatures` 语义错误。 |
| QA/诊断自动化工程师 | v107-v112 日志、ADB 验证、结构化判定 | v112 第一轮只验证 `0x11cb88 self SIGKILL` 是否消除；不能一上来点书城/章节。每个 PASS/FAIL 必须有日志文件和行号。 |
| 技术负责人/交付经理 | 执行顺序、工作区状态、决策门 | 当前工作区改动混杂，必须先冻结现场、验证 v112、再拆分提交与架构化落地。 |

## 2. 当前现场状态

当前分支：

```text
minimal-poc
```

当前工作区是脏的，且混合了文档、工具、runtime、native、QQ 阅读专项实验：

```text
M  core/hook/build.gradle.kts
M  core/hook/src/main/cpp/native-hook.cpp
M  core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt
M  core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
M  core/loader/src/main/java/com/multiapp/core/loader/QqReaderSignCompat.java
M  docs/current-repository-state.md
M  docs/qqreader-offline-patch.md
M  tools/qqreader-offline-patch/NeutralizeDex.java
?? docs/multiapp-target-design.md
?? docs/qqreader-clone-special-plan.md
?? docs/multiapp-team-execution-plan.md
?? tools/qqreader-offline-patch/DecodeT2Strings.java
?? tools/qqreader-offline-patch/DumpDexClassVerbose.java
```

当前最新 QQ 阅读 APK：

```text
.tmp\qqreader-c9f8-neutralized-v112-callsite-nop-signed.apk
```

当前阻塞：

```text
手机 ADB 暂时不可连接，v112 尚未真机验证。
```

## 3. 执行总原则

1. 未经用户确认，不执行源码重构、不提交、不清理 `.tmp`。
2. 先验证 v112，不切换方案，不扩大 patch。
3. 所有 fallback stub 默认视为诊断能力，不作为“功能修复成功”。
4. `Fock.sign/FockRT.sn` fake、`OnlineChapterDownloadTask.run` no-op 不能作为最终方案。
5. 每一轮判断必须来自 logcat/crash/exit-info/UI tree 证据。
6. 架构化落地先做边界和结果模型，再迁移逻辑，避免行为大改。

## 4. 决策门

| 决策门 | 触发时机 | 通过条件 | 不通过时动作 |
|---|---|---|---|
| D0：批准执行方案 | 当前 | 用户明确同意 | 只保留文档，不执行 |
| D1：v112 壳自杀验证 | 手机恢复连接后 | `0x11cb88 sig=9` 消失，且 `StubApp.interface20` 注册成功 | 回到 native offset / 指令 / so hash 诊断 |
| D2：拆分当前工作区 | v112 证据明确后 | 用户同意按文档/工具/runtime 分批 | 不提交、不重构 |
| D3：RuntimeBootstrap 拆分 | 当前行为基线可复现后 | 普通 App 与 QQReader 离线包构建路径不丢 | 先补结构化日志，不拆大类 |
| D4：PackerRuntime/JiaguRuntime | Runtime 阶段结果稳定后 | native probe/patch 已能结构化记录 | 继续限制在 QQ 阅读专项实验 |
| D5：Route C 预研 | 当前主线稳定后 | 用户明确要求长期高兼容路线 | 不混入当前 v112 修复节奏 |

## 5. Phase 0：冻结现场与准备验证

目标：

- 保留当前证据和产物。
- 不继续扩大修改。
- 准备 v112 验证脚本。

交付物：

```text
docs/multiapp-target-design.md
docs/qqreader-clone-special-plan.md
docs/multiapp-team-execution-plan.md
.tmp\qqreader-c9f8-neutralized-v112-callsite-nop-signed.apk
当前 git status 快照
```

验收：

- 不删除 `.tmp`。
- 不清理 APK/log。
- 不提交混合改动。

## 6. Phase 1：v112 真机验证

目标：

只回答一个问题：

```text
libjiagu_vip.so offset=0x11cb88 self SIGKILL 是否被 v112 定点 patch 消除？
```

禁止：

- 第一轮不要点书城。
- 第一轮不要测章节。
- 第一轮不要切换 props。
- 第一轮不要扩大 patch。

验证脚本：

```powershell
$ErrorActionPreference = 'Stop'

$adb = 'C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe'
$serial = '<IP:PORT>'
$pkg = 'com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8'
$activity = 'com.qq.reader.activity.launch.DefaultAliasSplashActivity'
$apk = '.tmp\qqreader-c9f8-neutralized-v112-callsite-nop-signed.apk'
$run = '.tmp\qqreader-v112-callsite-nop'

if (!(Test-Path $apk)) { throw "APK not found: $apk" }

& $adb connect $serial
& $adb -s $serial wait-for-device
& $adb -s $serial shell getprop ro.product.model
& $adb -s $serial shell getprop ro.build.version.release

& $adb -s $serial shell setprop debug.multiapp.jiagu.explicit_load 1
& $adb -s $serial shell setprop debug.multiapp.patch_jiagu 0
& $adb -s $serial shell setprop debug.multiapp.patch_jiagu_mode legacy
& $adb -s $serial shell setprop debug.multiapp.suppress_self_sigkill 0
& $adb -s $serial shell setprop debug.multiapp.online.run_fallback 0
& $adb -s $serial shell setprop debug.multiapp.online.failure_callback 1
& $adb -s $serial shell setprop debug.multiapp.stubapp.fallback 0

& $adb -s $serial install -r -d $apk
& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial shell pm clear $pkg
& $adb -s $serial logcat -c
& $adb -s $serial shell am start -W -n "$pkg/$activity"

Start-Sleep -Seconds 45

& $adb -s $serial logcat -d -v threadtime > "$run-logcat.txt"
& $adb -s $serial logcat -b crash -d -v threadtime > "$run-crash.txt"
& $adb -s $serial shell dumpsys activity exit-info $pkg > "$run-exit-info.txt"
& $adb -s $serial shell dumpsys window | Select-String -Pattern 'mCurrentFocus|mFocusedApp' > "$run-window.txt"
& $adb -s $serial exec-out uiautomator dump /dev/tty > "$run-ui.xml"
& $adb -s $serial shell pidof $pkg > "$run-pid.txt"

rg -n "patch_jiagu_vip_self_kill_callsite|patch_arm64_instruction|RegisterNatives: class=com.stub.StubApp|captured original interface20|nativeLoadLibraryForGuest: SUCCESS|JNI_ERR returned from JNI_OnLoad|GOT tgkill intercepted|offset=0x11cb88|No implementation found|UnsatisfiedLinkError|FATAL EXCEPTION|OnlineChapterDownloadTask|Fock.sign|FockRT.sn|qrencrypt|network error" "$run-logcat.txt" "$run-crash.txt" "$run-exit-info.txt" "$run-window.txt"
```

Phase 1 交付物：

```text
.tmp\qqreader-v112-callsite-nop-logcat.txt
.tmp\qqreader-v112-callsite-nop-crash.txt
.tmp\qqreader-v112-callsite-nop-exit-info.txt
.tmp\qqreader-v112-callsite-nop-ui.xml
.tmp\qqreader-v112-callsite-nop-window.txt
.tmp\qqreader-v112-callsite-nop-pid.txt
```

通过标准：

- 出现 `patch_jiagu_vip_self_kill_callsite`。
- 出现 `RegisterNatives: class=com.stub.StubApp`。
- 捕获 `interface20` 原始注册。
- 不出现 `GOT tgkill intercepted ... sig=9 ... offset=0x11cb88`。
- 不出现 `JNI_ERR returned from JNI_OnLoad`。
- 不出现 `No implementation found for boolean com.stub.StubApp.interface20()`。

失败分支：

| 失败现象 | 下一步 |
|---|---|
| 仍是 `0x11cb88 sig=9` | 检查 patch 是否没命中、指令类型是否不匹配、so 版本/hash 是否变化 |
| 出现新 self-kill offset | 记录新 offset，加入诊断，不立即 patch |
| 出现 `JNI_ERR` | 反汇编 `0x11cb84/0x11cb88` 附近，确认 callsite 推断 |
| UI 是无关 app | 本轮无效，重抓窗口/UI tree |

## 7. Phase 2：拆分当前工作区交付单元

目标：

把当前混合改动拆成可审查、可回滚的批次。

建议批次：

| 批次 | 文件 | 目的 |
|---|---|---|
| 文档批 | `docs/multiapp-target-design.md`、`docs/qqreader-clone-special-plan.md`、`docs/multiapp-team-execution-plan.md` | 固化设计、专项状态、执行方案 |
| 工具批 | `tools/qqreader-offline-patch/*` | 离线 patch 工具增强 |
| runtime/native 批 | `native-hook.cpp`、`NativeHookBridge.kt`、`LoaderFactory.kt`、`QqReaderSignCompat.java` | v112 运行时和 native 诊断/patch |
| 状态文档批 | `docs/current-repository-state.md`、`docs/qqreader-offline-patch.md` | 同步当前事实 |

验收：

- 每批都能说明目的、风险、验证结果。
- 不把实验性 fake sign/no-op 作为成功结论。
- 不混入无关清理。

## 8. Phase 3：BuildManifest 与 PatchProfile

目标：

让每个 APK 可追溯。

新增概念：

```text
BuildManifest / ArtifactManifest
PatchProfile
SoPatchRegistry
```

最小字段：

```text
buildId
gitHead
originPackage
stubPackage
originVersion
originApkSha256
originCertDigests
patchedOriginSha256
loaderDexSha256
nativeLibsSha256
profileId
patchRules
forbiddenPatches
acceptanceChecks
```

必须修正：

```text
StubConfig.originalSignatures 当前被当作 origin APK 路径使用，语义错误。
```

验收：

- 输出 APK 内包含 `assets/multiapp_build_manifest.json`。
- 离线 patch 输出同名 `.build.json`。
- `origin_original.apk` hash 等于输入原包。
- so patch 记录 ABI、lib、offset、原 bytes、新 bytes、profile rule id。

## 9. Phase 4：RuntimeBootstrap 最小落地

目标：

先建立阶段结果，不急着改行为。

建议类/接口：

```text
RuntimeBootstrap
BootstrapStage
BootstrapStageResult
RuntimeConfigLoader
OriginApkExtractor
OriginNativeLibraryExtractor
GuestClassLoaderFactory
LoadedApkInstaller
ResourceInstaller
ApplicationInfoInstaller
NativeHookStageInstaller
PackageIdentityPolicy
IdentityProxyInstaller
SystemServiceProxyInstaller
DiagnosticFallbackPolicy
```

原则：

- Phase 4 先机械拆分，保持调用顺序。
- 每阶段输出 `success/fatal/degraded/diagnosticFallback`。
- 关键阶段失败不能静默继续。

验收：

- 普通 App clone 启动不回退。
- QQ 阅读离线包仍能构建。
- log 中能看出每个阶段结果。

## 10. Phase 5：PackerRuntime / JiaguRuntime

目标：

把加固壳逻辑从 `LoaderFactory` / `native-hook.cpp` 的散落临时代码中抽出来。

接口草案：

```text
PackerRuntime
- detect(input): PackerDetection
- prepareFiles(context): PrepareResult
- installBaseHooks(scope): HookResult
- installGuestBoundHooks(guestClassLoader, callerClass): HookResult
- loadPackerLibrary(policy): LoadResult
- verifyRegisterNatives(expectations): RegisterNativesReport
- applyPatchProfile(profile): PatchReport
- cleanup(scope): CleanupResult
- fallbackPolicy(result): Decision
```

`JiaguRuntime` 额外能力：

```text
findStubAppClass()
setupFindClassScope()
setupIntegrityRedirect(origin.apk -> origin_original.apk)
dlopenForGotProbe() 仅诊断/实验
loadViaRuntimeNativeLoad()
verifyStubAppInterface20()
verifySelfKillOffset()
verifyBusinessNativeBindings()
```

必须隔离：

- `FindClass` 全局 hook 改为 scoped hook。
- `RegisterNatives` 分成 probe 与 patch。
- `libjiagu_vip.so offset=0x11cb84 NOP` 放入 `SoPatchRegistry`，只允许 hash/profile 匹配时启用。
- `setSuppressSelfSigkill()` 只能诊断。
- `OnlineChapterDownloadTask.run` fallback 默认关闭。
- `Fock.sign/FockRT.sn` fake 默认禁止。

## 11. Phase 6：Virtual System Service 与数据隔离

目标：

补齐通用分身能力，不再靠散落 hook 修包名。

优先级：

```text
PackageManager
ActivityTaskManager / Instrumentation
Filesystem / Data isolation
ContentProvider authority
NotificationManager
Service / Receiver
AppOps
```

验收：

- original/stub identity 由 `PackageIdentityPolicy` 决策。
- 两个分身的数据互不污染。
- 通知、跳转、Provider、后台任务有回归用例。
- QQ 阅读专项保护：不能引入 fake `Fock.sign` 或 no-op `OnlineChapterDownloadTask.run` 作为通过依据。

## 12. 团队执行分工

| 角色 | 负责人职责 | Phase |
|---|---|---|
| 技术负责人 | 控制决策门、拆分批次、决定是否进入下一阶段 | 全程 |
| Android Runtime 工程师 | `RuntimeBootstrap`、`PackageIdentityPolicy`、系统服务代理 | Phase 4/6 |
| Native/NDK 工程师 | scoped native hook、`RegisterNativesProbe`、`JiaguRuntime`、SoPatchRegistry | Phase 1/5 |
| 构建工具链工程师 | `BuildManifest`、`PatchProfile`、离线 patch 报告 | Phase 3 |
| QA/诊断工程师 | v112 验证、测试矩阵、结构化报告、回归脚本 | Phase 1/4/6 |

## 13. 用户批准后第一步

如果用户批准本执行方案，团队第一步只做：

```text
等待/恢复手机 ADB 连接 -> 安装 v112 -> 抓证据 -> 输出 Phase 1 结构化验证报告。
```

不会立即：

- 重构 `LoaderFactory`。
- 提交代码。
- 清理 `.tmp`。
- 扩大 DEX patch。
- fake `Fock.sign`。
- no-op `OnlineChapterDownloadTask.run`。

