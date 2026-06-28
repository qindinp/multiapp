# MultiApp v2 开源容器方案对照与分角色整改

日期：2026-06-27

负责人：金凡

团队口径：按系统工程团队分工推进，目标不是复制某一个开源项目，而是把 VirtualApp / BlackBox / DroidPlugin / SPatch 公开路线中已经被验证过的容器骨架，映射到 MultiApp v2 hosted container 的最终态。

## 1. 为什么必须参考开源方案

之前没有直接参考开源方案继续做，主要风险不是“不能参考”，而是不能把第三方项目整包搬进当前工程：

1. VirtualApp / BlackBox / DroidPlugin 都是围绕 AMS/PMS/Instrumentation/LoadedApk/stub component 池建立的体系工程，直接合入会把现有 `VirtualInstall / VirtualInstance` 事实源、模块边界和测试体系打散。
2. 这些项目大多依赖大量 hidden API、binder proxy、反射和 native IO redirect，不同 Android 版本和厂商 ROM 上兼容成本很高，必须先做能力拆解和验收矩阵。
3. 当前 v2 已经选择 hosted container，不再生成新 Stub APK。因此应借鉴它们的架构模式，而不是恢复独立安装包/patch APK 路线。
4. SPatch/LSPatch 的价值主要在 protected-app/profile 实验，不应作为普通容器主路径；普通 APK 多开应走用户态容器闭环。

结论：**参考开源方案是正确方向，但执行方式必须是“对照能力、拆模块、逐项验收”，不是整包复制。**

## 2. 开源方案能力对照

参考来源：

- VirtualApp README：`https://github.com/asLody/VirtualApp/blob/master/README.md`
- BlackBox README：`https://github.com/FBlackBox/BlackBox/blob/master/README.md`
- DroidPlugin README：`https://github.com/DroidPluginTeam/DroidPlugin/blob/master/readme_cn.md`
- LSPatch README：`https://github.com/LSPosed/LSPatch/blob/master/README.md`

| 方案 | 可借鉴点 | v2 采用方式 | 不直接照搬原因 |
| --- | --- | --- | --- |
| VirtualApp | virtual AMS/PMS、Instrumentation hook、stub Activity/Service/Provider、Native IO redirect | 建立 `VirtualProcessRuntime`、`LoadedApkBridge`、`VirtualPackageManagerWrapper`、proxy Activity 池、后续 native IO redirect | 代码体系庞大，Android 高版本 hidden API 风险高，和现有 v2 install/instance 模型不一致 |
| BlackBox | package/component settings、process runtime、Activity 重定向、服务端式虚拟管理 | 学习 package registry、component resolver、runtime record 分层 | 不能把其包管理和进程管理直接替换现有 record store；需要先兼容 MultiApp UI 和数据模型 |
| DroidPlugin | stub component 池、未安装 APK 四大组件代理、AMS/PMS hook 思路 | 采用 proxy slot + intent remap + component resolver 的设计 | 老路线对新 Android 版本适配压力大，通知、native hook、动态 intent-filter 等限制必须单独验证 |
| SPatch/LSPatch | protected app profile、LSPlant/hook 能力、patch/stub 经验 | 仅保留为 protected diagnostics/compat profile，不进入普通容器默认路径 | patch APK/独立 Stub 与 hosted container 最终态冲突；默认 hook 会污染加固应用 baseline |

## 3. 最终技术路线

```text
MultiApp UI
  -> VirtualInstallService.importOrEnsure(origin APK)
  -> InstanceManager.createInstance(installId)
  -> VirtualProcessRuntime.bindApplication(instanceId)
  -> VirtualActivityManager.launchGuestLauncher(instanceId)
  -> VirtualIntentResolver.resolveActivity()
  -> ProxyActivityRegistry.allocate()
  -> host startActivity(ProxyActivity)
  -> ActivityThread/Instrumentation creates guest Activity
  -> LoadedApkBridge / VirtualContext / VPMS / Storage provide guest runtime view
```

硬性边界：

- v2 新实例不生成独立 Stub APK。
- `VirtualInstallRecord`、`VirtualInstanceRecord` 是唯一事实源。
- `DefaultVirtualActivityController` 手工 `new Activity()` 只能保留为 diagnostic negative evidence，不进入生产路径。
- 默认 baseline 不启用 LSPlant、native inline hook、business wrapper。
- QQ 阅读等加固应用只进入 evidence-driven protected profile，不作为普通容器基础能力验收项。

## 4. 分角色整改任务

### 4.1 系统架构组

P0：建立运行时事实源。

| 任务 | 文件/模块 | 验收 |
| --- | --- | --- |
| 新增 `VirtualProcessRuntime` | `core/loader` | 同一 `instanceId` 重复启动时 `Application.onCreate()` 只调用一次，runtime 可复用和销毁 |
| 改造 `HostedRuntimeBootstrap` 为 `bindApplication` 语义 | `core/loader` | bootstrap 不再和 launcher Activity 启动耦合；失败 stage 可追踪 |
| 新增 `VirtualActivityRecordManager` | `core/loader` 或 `core/model` | 每次 guest Activity launch 有 token、instanceId、guest/proxy component、intent、requestCode 记录 |
| 扩展 proxy Activity 池 | `app/src/main/AndroidManifest.xml` | 至少区分 `standard`、`singleTop`、`singleTask` 三类 proxy slot |
| 拆出 `LoadedApkBridge` | `core/loader` | `ActivityThread.mPackages/mResourcePackages/ClassLoader/Resources/ApplicationInfo` 接入点集中管理 |

P1：统一身份模型。

| 任务 | 文件/模块 | 验收 |
| --- | --- | --- |
| 输出 host/origin/virtual package 使用规则 | `docs/container-runtime-refactor` | `getPackageName()`、PMS 查询、Storage 路径、权限检查都有明确返回策略 |
| 收缩 `ContainerActivity` 职责 | `app/container` | 只接收 `instanceId` 并委托 `VirtualActivityManager`，不直接承担 runtime 状态 |
| 清理 `LoaderFactory.kt` 历史反射逻辑 | `core/loader` | v2 hosted 主路径只依赖命名清晰的新模块，legacy 路径显式隔离 |

### 4.2 Android Runtime 组

P0：补齐 Activity 生命周期承载。

| 任务 | 文件/模块 | 验收 |
| --- | --- | --- |
| 完整接管 `Instrumentation.execStartActivity()` 系列签名 | `VirtualInstrumentation.kt` | guest 内部显式/隐式 `startActivity` 均改写到 proxy Activity，不出现直接启动未注册 guest Activity |
| 接管 `newActivity()` 和 `callActivityOnCreate()` | `VirtualInstrumentation.kt` | proxy class 被替换为 guest Activity，`MainActivity/SecondActivity.onCreate()` 在系统 lifecycle 内执行 |
| 注入 guest context/application/resources | `HostedActivityContextInjector.kt`、`VirtualContextWrapper.kt` | guest `getPackageName/getDataDir/getClassLoader/getResources` 符合 identity 策略 |
| 新增 `VirtualResourcesManager` | `core/loader` | guest layout/string/theme/assets 来自 origin APK，不再落到 host resources |
| 新增 native library path 管理 | `core/loader` | 含 `.so` fixture 的 `System.loadLibrary()` 不出现 `UnsatisfiedLinkError` |

P1：四大组件边界。

| 任务 | 文件/模块 | 验收 |
| --- | --- | --- |
| Provider MVP | `core/provider` 或 `core/loader` | 至少 provider manifest info/authority rewrite 有定义；未支持时受控失败 |
| Service MVP | `core/service` 或 `core/loader` | `startService/bindService` 不得静默落到 host；支持或明确 unsupported |
| Broadcast MVP | `core/receiver` 或 `core/loader` | 动态 receiver 和 manifest receiver 支持边界明确 |
| Native IO redirect 方案 | `core/hook` 或独立 `core/nativeio` | 普通容器 profile 和 protected diagnostics profile 分离，默认不污染加固 baseline |

### 4.3 测试验证组

P0：建立真机 baseline。

| Case | 验收内容 | 必抓证据 |
| --- | --- | --- |
| E0 | host 启动和 instrumentation 安装成功 | `VirtualInstrInstaller`、无 host crash |
| E1 | minimal app 单实例进入 launcher | `ContainerActivity`、`HostedRuntimeBootstrap`、`GUEST_ACTIVITY_SUBSTITUTED` |
| E2 | 双实例 SP/files/db/cache 隔离 | 两个 `instanceId` 的 dataRoot 文件树、摘要和 UI 输出 |
| E3 | guest `MainActivity -> SecondActivity` 跳转成功 | `execStartActivity` remap、proxy slot、`SecondActivity` UI/log |
| E4 | Back stack 基础行为 | 从 `SecondActivity` Back 回 `MainActivity`，不崩宿主 |
| E5 | Package identity | guest PMS/context 查询不暴露错误 host 状态 |
| E6 | resources/theme/assets | guest 自身 layout/string/theme 可用 |
| E7 | native fixture | `.so` 加载路径正确 |
| E8 | Provider/Service/Broadcast unsupported/pass | 未支持时必须稳定失败并产出 stage，不允许静默误判 |

建议新增脚本：

```text
tools/hosted-baseline/run-hosted-e3-capture.ps1
tools/hosted-baseline/collect-hosted-evidence.ps1
tools/hosted-baseline/assert-e3-log.ps1
tools/hosted-baseline/pull-instance-data.ps1
tools/hosted-baseline/run-minimal-matrix.ps1
tools/hosted-baseline/make-report.ps1
```

## 5. 当前代码状态确认

本轮已经完成的本地验证：

```powershell
.\gradlew.bat :core:loader:compileDebugKotlin :core:loader:testDebugUnitTest :test-fixtures:minimal-app:assembleDebug --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

2026-06-27 运行时分层补充：

- 新增 `core/loader/src/main/java/com/multiapp/core/loader/VirtualProcessRuntime.kt`。
- `ContainerActivity` 已改为通过 `VirtualProcessRuntime.global.bindApplication(instanceId)` 绑定 hosted runtime。
- 成功 runtime 以 `instanceId` 为 key 在当前进程内复用，避免同一实例重复 bootstrap / 重复创建 guest Application。
- 失败结果或缺失 `guestClassLoader` 的结果不缓存，下一次启动允许重试。
- 新增 `VirtualProcessRuntimeTest` 固定成功复用、失败不缓存、无 classloader 不缓存、清理 runtime 四个 contract。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

2026-06-28 Activity record 管理层补充：

- 新增 `core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityRecordManager.kt`。
- `VirtualActivityManager` 的 launcher 启动和 guest 内部 Activity 启动都统一注册到 `VirtualActivityRecordManager`。
- `VirtualContextWrapper.startActivity()` 不再每次创建完全孤立的 Activity 状态，改为复用当前 wrapper 的 proxy registry，并注册到进程级 record manager。
- `ProxyActivityBase` 在 resume 时会按 token 反查 `VirtualActivityRecordManager.global`，evidence 增加 `activityRecordFound` 字段，便于真机判断 proxy launch 是否命中过容器 Activity record 表。
- 新增 `VirtualActivityRecordManagerTest`，并扩展 `VirtualActivityManagerTest` 验证 token 注册语义。
- 清理 `VirtualInstrumentation.kt` 中一个恒真判断，去除编译噪音。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

边界：当前 `VirtualActivityRecordManager` 仍是进程内 MVP。它解决 token 反查、诊断和后续 result/task 管理的落点问题，但还不是完整 task/back-stack，也没有跨进程持久恢复语义。

2026-06-28 Resources / LoadedApk MVP 补充：

- 新增 `core/loader/src/main/java/com/multiapp/core/loader/VirtualResourcesManager.kt`。
- `VirtualResourcesManager` 统一负责从 `VirtualContextConfig` 构造 guest `ApplicationInfo` 和 `Resources`，加载顺序为：

```text
PackageManager.getResourcesForApplication(ApplicationInfo)
-> AssetManager.addAssetPath(originApkPath)
-> host resources fallback
```

- `VirtualContextWrapper.getApplicationInfo()/getResources()/getAssets()/getTheme()` 已改为使用 `VirtualResourceBundle`，不再各自拼资源路径。
- 新增 `core/loader/src/main/java/com/multiapp/core/loader/LoadedApkBridge.kt`，集中 patch LoadedApk-like 对象的 `mApplicationInfo/mResources/mClassLoader/mPackageName/mAppDir/mResDir`。
- `HostedActivityContextInjector` 在注入 guest Activity context 时会尝试 patch Activity 关联的 `mLoadedApk` 或 `mPackageInfo`，evidence 增加 `loadedApkPatchedFields`。
- 新增 `VirtualResourcesManagerTest` 和 `LoadedApkBridgeTest`，固定 guest `ApplicationInfo` 字段映射和 LoadedApk-like 字段 patch contract。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

边界：当前 `LoadedApkBridge` 只是集中反射工具和 Activity 注入侧 MVP；还没有正式接入 `ActivityThread.mPackages/mResourcePackages`，也没有完成 Android 版本差异适配。完整 VirtualApp/BlackBox 级资源和 LoadedApk 接管仍需后续真机验证。

2026-06-28 ActivityThread LoadedApk alias 注册补充：

- `ActivityThreadCompat` 新增 `packageMap()` 和 `putLoadedApkReference()`，集中访问 `ActivityThread.mPackages/mResourcePackages`。
- 新增 `core/loader/src/main/java/com/multiapp/core/loader/ActivityThreadLoadedApkInstaller.kt`。
- installer 会先调用 `LoadedApkBridge.patch()` patch LoadedApk-like target，再把 origin/virtual package aliases 以 `WeakReference` 写入 `mPackages` 和 `mResourcePackages`。
- `HostedActivityContextInjector` 在 guest Activity context 注入时会尝试调用 installer，evidence 增加 `loadedApkInstalledAliasCount`。
- 新增 `ActivityThreadLoadedApkInstallerTest`，验证 fake ActivityThread 的 `mPackages/mResourcePackages` 能写入 origin/virtual aliases，且 LoadedApk-like target 字段被 patch。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

边界：当前 installer 仍是 alias 注册 MVP。它没有构造新的 Android framework `LoadedApk`，而是基于已有 LoadedApk-like target 做 patch 和 package map alias。该实现推进了 VirtualApp/BlackBox 风格的 `ActivityThread` 接入点，但还不能宣称完整 LoadedApk 隔离；下一步需要在真机上确认 host LoadedApk 复用是否污染宿主，并评估是否需要构造独立 guest LoadedApk。

2026-06-28 LoadedApk host guard 补充：

- `LoadedApkBridge` 新增 `inspect()`，可读取 LoadedApk-like target 的 `mPackageName` 和 `mApplicationInfo.packageName`。
- `ActivityThreadLoadedApkInstaller.install()` 新增 `hostPackageName` 参数。
- 当 installer 识别到 target 是 host `LoadedApk` 时，不再 patch，也不写入 `mPackages/mResourcePackages` aliases，返回 `skippedReason=HOST_LOADED_APK_GUARD:<hostPackageName>`。
- `HostedActivityContextInjector` 传入 host package 并把 `loadedApkSkippedReason` 写入 evidence。
- 新增/扩展测试，覆盖 host LoadedApk guard、`LoadedApkBridge.inspect()` 和正常 alias 注册路径。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

结论：这一步没有盲目追求“强行 patch 成功”，而是先把 host runtime 污染风险挡住。后续真机 evidence 如果出现 `loadedApkSkippedReason=HOST_LOADED_APK_GUARD:com.multiapp.app`，说明当前系统路径拿到的是 host LoadedApk，下一步必须走独立 guest LoadedApk 构造/替换，不能继续复用 host target。

2026-06-28 Provider registry MVP 补充：

- 新增 `core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderManager.kt`。
- 基于 `VirtualPackageSnapshot.providers` 建立 guest provider authority 到 host proxy authority 的确定性映射。
- `resolve()` 返回 `VirtualProviderResolution`，包含 instance、origin/virtual package、guest authority、proxy authority、provider class 和 `ProviderInfo`。
- 历史记录：早期 `openProvider()` 返回 `Unsupported(PROVIDER_LIFECYCLE_NOT_IMPLEMENTED)`，用于防止未实现 provider 生命周期被误判为成功；该语义已在第 25 节被 dispatcher/runtime 能力收口替换。
- 新增 `VirtualProviderManagerTest`，覆盖 authority 解析、proxy authority 稳定性、unknown authority、unsupported lifecycle 结果。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

边界：当前 Provider 只是 registry/authority mapping MVP，尚未声明 host `StubContentProvider`，也未接入 `ContentResolver` 调用链。下一步应参考 VirtualApp/DroidPlugin 的 stub provider 分发方式，在 `app` manifest 声明宿主 provider，并用 androidTest/真机验证 `content://guest.authority/...` 能被 rewrite 到 host proxy authority 后分发。

2026-06-28 Provider host stub 补充：

- 新增 `app/src/main/java/com/multiapp/app/container/StubContentProvider.kt`。
- `app/src/main/AndroidManifest.xml` 声明 host provider：

```xml
<provider
    android:name=".container.StubContentProvider"
    android:authorities="${applicationId}.multiapp.provider.stub"
    android:exported="false" />
```

- `VirtualProviderManager` 的 proxy authority 改为固定 host stub authority：`<hostPackage>.multiapp.provider.stub`，符合 Android manifest 必须预声明 provider authority 的约束。
- guest authority 与 instance 信息暂通过 query parameter 预留：`multiapp_instanceId`、`multiapp_guestAuthority`。
- `StubContentProvider` 当前返回受控 unsupported 结果，`call()` 返回：

```text
status=UNSUPPORTED
reason=PROVIDER_ROUTE_RESOLVED_BY_MANAGER
```

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

边界：host stub provider 已经存在，但还没有创建/attach guest `ContentProvider`，也没有把 `VirtualContextWrapper.getContentResolver()` 接到 rewrite 链路。下一步应实现 `VirtualProviderDispatcher`，负责根据 proxy Uri 中的 instance/guest authority 找到 `VirtualProviderResolution`，创建 guest provider 并调用 `attachInfo()`。

2026-06-28 Provider dispatcher 补充：

- `VirtualPackageRegistry` 新增 `global` 实例。
- `HostedRuntimeBootstrap` 创建 `VirtualPackageSnapshot` 后注册到 `VirtualPackageRegistry.global`，为 provider/service/broadcast 等运行时分发提供同进程包表。
- 新增 `core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderDispatcher.kt`。
- `VirtualProviderDispatcher` 可以根据 proxy Uri 或显式 `instanceId + guestAuthority`：

```text
instanceId -> VirtualPackageRegistry
guestAuthority -> VirtualProviderManager.resolve/openProvider
-> VirtualProviderDispatchResult
```

- `StubContentProvider.query()` 已接入 dispatcher 并记录 dispatch result。
- `StubContentProvider.call()` 支持通过 `arg` 传入 proxy Uri，返回结构化 Bundle：`UNSUPPORTED`、`INVALID_PROXY_URI`、`INSTANCE_NOT_FOUND`、`PROVIDER_NOT_FOUND`。
- 新增 `VirtualProviderDispatcherTest`，覆盖 known provider unsupported、missing instance、missing provider。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

边界：dispatcher 已经把 host stub provider 和 virtual package registry 接起来，但还没有实例化 guest `ContentProvider`。下一步应实现 `VirtualProviderRuntime`：用 guest ClassLoader 创建 provider，使用 `VirtualContextWrapper` + `ProviderInfo` 调用 `attachInfo()`，并缓存 provider 实例。

2026-06-28 Provider runtime MVP 补充：

- 新增 `core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderRuntime.kt`。
- `VirtualProviderRuntime` 负责 provider 实例缓存、guest ClassLoader 反射创建、`attachInfo()` 调用和失败诊断。
- 结果类型明确区分：

```text
Created
Cached
CreateFailed
AttachFailed
```

- 默认实现使用 `ReflectionProviderFactory` 和 `DefaultProviderAttacher`；测试可注入 factory/attacher，避免 JVM 单测直接依赖 Android provider attach 行为。
- 新增 `VirtualProviderRuntimeTest`，覆盖 provider 创建+缓存、创建失败不缓存、attach 失败不缓存。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

边界：`VirtualProviderRuntime` 还没有接入 `VirtualProviderDispatcher` 的生产路径。原因是 dispatcher 当前只持有 `instanceId + guestAuthority + snapshot`，还缺 guest `Context/ClassLoader`；下一步应让 dispatcher 或 StubContentProvider 通过 `VirtualProcessRuntime`/`HostedRuntimeBootstrap` 获取 `HostedBootstrapResult`，组装 `VirtualProviderCreateRequest`，再调用 `VirtualProviderRuntime.global.getOrCreate()`。

2026-06-28 Provider dispatcher 接入 runtime 补充：

- `VirtualProviderDispatcher` 不再把 known provider 固定返回 `PROVIDER_LIFECYCLE_NOT_IMPLEMENTED`。
- 新路径对齐 VirtualApp/DroidPlugin 的 provider stub 模式：

```text
StubContentProvider
  -> VirtualProviderDispatcher.dispatch(proxy Uri / instanceId + guestAuthority)
  -> VirtualPackageRegistry.getByInstanceId(instanceId)
  -> VirtualProviderManager.resolve(snapshot, guestAuthority)
  -> VirtualProcessRuntime.get(instanceId)
  -> VirtualContextWrapper(hostContext, VirtualContextConfig, guestClassLoader)
  -> VirtualProviderRuntime.getOrCreate()
  -> ContentProvider.attachInfo(guestContext, ProviderInfo)
```

- `VirtualProviderDispatchResult` 新增生产态结果：

```text
ProviderReady(created/cached)
RuntimeNotBound
RuntimeIncomplete
ProviderCreateFailed
ProviderAttachFailed
```

- `StubContentProvider` 已将 `query/getType/insert/delete/update` 转发到 `ProviderReady.provider`；转发前会把 proxy authority 还原为 guest authority，并移除内部 query 参数。
- `StubContentProvider.call()` 返回结构化 Bundle，便于真机 logcat/diagnostic 读取 provider 创建、缓存、失败原因。
- `VirtualProviderDispatcherTest` 新增覆盖：runtime 未绑定返回 `RuntimeNotBound`；runtime 已绑定时 provider 创建、attach、缓存只发生一次。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

仍需边界说明：这只是 Provider 生命周期托管 MVP，不等于完整 Provider 虚拟化。还缺 `VirtualContextWrapper.getContentResolver()` 的统一 Uri rewrite、跨进程 provider 恢复、provider 权限/AppOps 映射、`notifyChange/registerContentObserver` 虚拟化。

2026-06-28 Provider authority rewrite 基础件补充：

- 新增 `core/loader/src/main/java/com/multiapp/core/loader/VirtualContentResolver.kt`，但该文件当前只承载 provider rewrite 基础件，不再伪装成完整 `ContentResolver` 子类。
- 编译验证确认：现代 Android SDK 下 `ContentResolver.query/insert/update/delete/call` 在 Kotlin 视角为 final，不能通过继承覆盖。这一点与 VirtualApp/DroidPlugin 的经验一致：真正生产接入点应在 provider acquisition / framework hook / ActivityThread provider cache 层，而不是 public resolver subclass。
- 新增 `VirtualProviderUriRewriter`：根据 `VirtualPackageSnapshot` 将 guest provider authority 映射到 host stub authority。
- 新增 `VirtualProviderAuthorityMapFactory`：从 `snapshot.providers` 导出 `guestAuthority -> proxyAuthority`，可作为后续 `ContentProviderHook` 或 provider acquisition 代理的输入。
- `VirtualProviderManagerTest` 新增 authority rewrite 与 authority map factory 覆盖。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

当前生产边界：

```text
已完成：provider authority 映射、host stub provider、guest provider create/attach/cache。
未完成：guest ContentResolver 自动进入 rewrite 链路。
下一步：二选一推进。
  A. 修 HookEngine pass-through，让 ContentProviderHook 可以改参数后继续原方法。
  B. 实现 ActivityThread/provider acquisition 代理，把 authority rewrite 放到 acquire provider 层。
```

2026-06-28 Provider hook pass-through 接入补充：

- 审查确认 `HookEngine.hookMethod()` 是 skip-mode：无论 beforeCallback 返回什么，都不会可靠继续调用原方法；它不适合 `ContentResolver.query/insert/update/delete/call/acquireProvider` 这类“改参数后继续执行”的场景。
- `HookEngine.hookMethodPassThrough()` 已存在，内部通过 `hookMethodWithBackup()` + `SimpleHooker.callOriginal()` 调用 backup 原方法，适合 provider authority rewrite。
- `ContentProviderHook` 已从 `hookMethod()` 切换到 `hookMethodPassThrough()`：

```text
ContentResolver.query(Uri, ...)
ContentResolver.insert(Uri, ...)
ContentResolver.update(Uri, ...)
ContentResolver.delete(Uri, ...)
ContentResolver.call(Uri, ...)
ContentResolver.acquireProvider(String)
ContentResolver.acquireProvider(Uri)
```

- 修复 `ContentProviderHook.apply(config, hookEngine)` 忽略传入 `hookEngine` 的问题。实例 apply 现在使用调用方传入的 HookEngine；companion apply 才使用 `HookEngine.getInstance()`。
- `IdentityHookTest.ContentProviderHookTests` 增加验证：`ContentProviderHook().apply(testConfig, mockHookEngine)` 会调用 `mockHookEngine.hookMethodPassThrough(...)`，避免回退到 skip-mode。

补充验证命令：

```powershell
.\gradlew.bat :core:identity:testDebugUnitTest --tests "com.multiapp.core.identity.IdentityHookTest*ContentProviderHook*" --console=plain --no-build-cache
.\gradlew.bat :core:hook:testDebugUnitTest :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：均为 `BUILD SUCCESSFUL`。

2026-06-28 identity 回归基线修复补充：

- 清理 `:core:identity:testDebugUnitTest` 既有失败项，避免 Provider hook 接入后缺少可信模块基线。
- `ProcFsHook` 新增私有 `shouldFilterLine()` 诊断 wrapper，复用真实 `filterMapsLine()` 规则，恢复 maps 过滤规则测试入口。
- `IdentityHookTest` 的 `DeviceIdentityHook.generateFakeImsi` 反射 helper 改为访问 companion 方法；实现本身未改变。
- `IdentityHookTest` 的 `SignatureBypass.interceptPackageInfo` helper 改为访问 companion 方法；`recursionGuard` helper 按 Kotlin 字节码实际位置访问外层静态字段。
- `SignatureBypass` skip-path 测试不再调用 Android JVM stub 的 `Signature.toByteArray()`，改为验证对象引用未被替换。
- ThreadLocal 测试断言改为验证其他线程没有看到 `true`；ThreadLocal 初始值为 `null`，不能错误要求等于 `false`。

补充验证命令：

```powershell
.\gradlew.bat :core:identity:testDebugUnitTest --console=plain --no-build-cache
.\gradlew.bat :core:identity:testDebugUnitTest :core:hook:testDebugUnitTest :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：均为 `BUILD SUCCESSFUL`。

下一步 Provider 方向边界：pass-through hook 已能作为 Java 层 authority rewrite 入口，但真机仍需验证 LSPlant backup 调用在 Android 16 + 小米 ROM 上是否稳定。若真机出现 hidden/final/API 限制，应转向 ActivityThread/provider acquisition/cache 代理，不再尝试 public `ContentResolver` subclass。

2026-06-28 Provider hook installer adapter 补充：

- `ContentProviderHook` 新增轻量入口 `ProviderAuthorityHookConfig`，只包含 `instanceId`、`originalPackageName`、`authorityMap`。
- 目的：Provider authority rewrite 不再必须伪造完整 `IdentityConfig`。完整 `IdentityConfig` 仍保留给设备身份、签名、文件系统、ActivityManager 等 hook 使用。
- 新增 `core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderHookInstaller.kt`。
- `VirtualProviderHookInstaller` 消费 `VirtualProviderRoutingPlan`：

```text
enabled=false -> Skipped(reason)
primaryStrategy != CONTENT_RESOLVER_PASS_THROUGH_HOOK -> Skipped(PRIMARY_STRATEGY_NOT_PASS_THROUGH:...)
authorityMap empty -> Skipped(AUTHORITY_MAP_EMPTY)
pass-through plan -> ContentProviderHook.apply(ProviderAuthorityHookConfig, HookEngine)
exception -> Failed(error)
```

- 注意：installer 当前是显式入口，不在 `HostedRuntimeBootstrap` 默认执行。原因是 hook-free baseline 仍然必须保持，是否启用 Java pass-through hook 应由 profile/真机实验控制。
- 新增测试：

```text
IdentityHookTest.provider authority config installs hooks without full identity config
VirtualProviderHookInstallerTest.install applies content provider hook for pass-through routing plan
VirtualProviderHookInstallerTest.install skips disabled plan
VirtualProviderHookInstallerTest.install skips acquisition proxy primary plan
```

补充验证命令：

```powershell
.\gradlew.bat :core:identity:testDebugUnitTest :core:loader:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

2026-06-28 Provider hook profile-controlled bootstrap 补充：

- `HostedRuntimeBootstrap` 新增 `providerHookInstallEnabled`，默认 `false`。
- 默认 baseline 行为：不调用 `VirtualProviderHookInstaller`，但会在 `CLASS_LOADER` stage 写入安装证据：

```text
providerHookInstallStatus=SKIPPED
providerHookInstallReason=PROFILE_DISABLED
```

- 实验 profile 行为：调用方显式传入 `providerHookInstallEnabled=true`，并可注入 `VirtualProviderHookInstaller`。当 routing plan 可用时，证据为：

```text
providerHookInstallStatus=INSTALLED
providerHookInstallAuthorityMapSize=<N>
providerHookInstallReason=AUTHORITY_MAP_READY
```

- 这一步仍然不改变 MultiApp 默认启动路径；它只是把 Provider Java pass-through hook 变成 profile-controlled 能力。
- 新增测试：

```text
HostedRuntimeBootstrapTest.bootstrap records provider routing evidence when manifest declares providers
HostedRuntimeBootstrapTest.bootstrap installs provider hook when profile enables provider hook install
VirtualProviderHookInstallerTest.install result evidence records installed status
```

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

2026-06-28 Provider routing plan/evidence 补充：

- 新增 `core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderRoutingPlan.kt`。
- `VirtualProviderRoutingPlan` 不安装 hook，只描述当前实例 Provider 路由策略，避免真机验证时无法判断实际路线。
- 策略枚举：

```text
NONE
CONTENT_RESOLVER_PASS_THROUGH_HOOK
ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY
```

- `VirtualProviderRoutingPlanFactory` 根据 `VirtualPackageSnapshot.providers` 和 host package 生成：

```text
providerCount
providerAuthorityCount
authorityMap
primaryStrategy
fallbackStrategy
enabled
reason
```

- `HostedRuntimeBootstrap` 在创建 `VirtualPackageSnapshot` 后生成 provider routing plan，并把证据写入 `CLASS_LOADER` stage：

```text
providerRoutingEnabled
providerRoutingReason
providerRoutingPrimary
providerRoutingFallback
providerCount
providerAuthorityCount
providerAuthorityMapSize
providerHostPackage
```

- 这一步对齐 VirtualApp/DroidPlugin 的工程方法：先建立可观测的 provider routing decision，再在真机上验证 pass-through hook 或切换 provider acquisition/cache 代理。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

当前新增 E3 能力点：

- `VirtualInstrumentation.execStartActivity()` 开始承担 guest -> guest Activity intent remap。
- `VirtualContextWrapper.startActivity()` 增加辅助 remap 路径。
- `VirtualIntentResolver` / `VirtualActivityManager` 已有 JVM contract tests。
- minimal fixture 已新增 `SecondActivity` 和入口按钮。

尚未完成真机验收：

- 用户点击 `Launch SecondActivity` 后是否进入 guest `SecondActivity`。
- Back stack 是否保持在 guest 视角。
- context/resources/PMS 是否在 `SecondActivity` 中继续保持 virtual view。

## 6. 下一步执行顺序

1. 跑真机 E3：用户手动从 MultiApp 打开 minimal instance，再点击 `Launch SecondActivity`，我们只抓 logcat/evidence。
2. 若 E3 失败，优先修 `VirtualInstrumentation.execStartActivity` 签名覆盖和 `ProxyActivityRegistry` record 恢复，不扩展 QQ 阅读。
3. E3 通过后，补 `VirtualProcessRuntime`，避免重复 bootstrap 和重复 `Application.onCreate()`。
4. 再进入 `VirtualResourcesManager` / `LoadedApkBridge` / native lib path。
5. 最后才进入 QQ 阅读 `register-natives observe-only diagnostics`，并保持 hook-free baseline。

## 7. 对外宣称边界

当前只能宣称：

```text
v2 hosted container 正在按 VirtualApp/BlackBox/DroidPlugin 的公开容器骨架重构，
已完成 minimal Activity lifecycle 原型和部分 VPMS/Storage baseline，
正在验证 guest 内部 Activity 跳转、runtime 复用、resources、LoadedApk 和 native path。
```

当前不能宣称：

```text
已达到 VirtualApp/BlackBox 完整能力。
已支持所有普通 APK。
已支持 QQ 阅读/加固应用稳定分身。
已支持四大组件完整虚拟化。
```

## 8. 开源项目到底怎么做到

结论先定：VirtualApp / BlackBox / DroidPlugin 不是靠“给 APK 系统权限”实现分身，也不是靠每个 guest 生成一个真实安装包。它们的核心是用户态虚拟化：让 Android 系统只看到宿主声明过的组件，让 guest 在宿主进程或受控虚拟进程中运行，并通过运行时代理把系统调用改写成 guest 视角。

### 8.1 共性机制

| 能力 | 开源项目做法 | MultiApp v2 对应实现 | 当前状态 |
| --- | --- | --- | --- |
| 虚拟安装 | 维护自己的 package/settings 数据库，不依赖系统 `PackageManager` 真的安装 guest | `InstallRecordStore`、`VirtualInstallService`、`VirtualInstanceRecord` | 已有基础闭环 |
| Activity 启动 | guest Intent 先解析到虚拟组件，再映射到 host manifest 里预注册的 proxy Activity | `VirtualIntentResolver`、`VirtualActivityManager`、`ProxyActivityBase`、`VirtualInstrumentation` | minimal 样本已进入 proxy 路线，仍需 E3 真机回归 |
| Provider | guest authority 改写到 host stub authority，由 stub provider 分发到 guest provider 实例 | `StubContentProvider`、`VirtualProviderManager`、`VirtualProviderDispatcher`、`VirtualProviderRuntime` | MVP 已有，缺 observer/AppOps/跨进程恢复 |
| PMS 视图 | hook/代理 `PackageManager`，让 guest 查询到“自己已安装、其他虚拟包也已安装” | `VirtualPackageSnapshot`、`VirtualPackageRegistry`、`VirtualPackageManagerWrapper` | in-process MVP，非完整 VPMS |
| LoadedApk/Resources | 修改或补齐 `ActivityThread` 中的 package/classloader/resources 记录 | `LoadedApkBridge`、`ActivityThreadLoadedApkInstaller`、`VirtualResourcesManager` | 骨架已建，需扩大真机证据 |
| 存储隔离 | Java 层 Context 路径重写 + native IO redirect | `VirtualContextWrapper`、`VirtualContextStorage`；native IO 尚待独立 profile | Java 层普通文件/SP/db 已验证，native IO 未完成 |
| Service/Broadcast | 预注册 host stub Service/Receiver，运行时解析和转发 guest component | 待新增 `VirtualServiceManager`、`VirtualBroadcastManager` | 未完成 |
| 加固兼容 | 普通容器默认不 hook；对加固应用只做可控 profile 诊断 | `NativeHookPolicyGate`、`providerHookInstallEnabled=false` 默认 | 路线正确，QQ 阅读还没证明稳定 |

### 8.2 为什么不是直接复制 VirtualApp/BlackBox

1. Android 高版本和厂商 ROM 对 hidden API、反射、binder proxy 限制更强，直接搬历史项目会带来大量不可控崩溃。
2. BlackBox 当前公开仓库状态不适合作为长期上游，只能作为历史架构参考。
3. DroidPlugin 明确支持免安装 APK 和四大组件代理，但也在 README 中列出 native hook、特殊 intent-filter、通知资源等限制；这些正是我们必须逐项验收的风险点。
4. LSPatch 的定位是通过 patch 目标 APK 注入 Xposed/LSPosed 能力，适合 protected profile 研究，不等于普通多开容器主路径。
5. MultiApp v2 已经确定 hosted container，事实源是 `VirtualInstall / VirtualInstance`，直接换成第三方 install/settings 层会破坏当前数据模型和测试体系。

### 8.3 参考开源后的整改优先级

P0：把 Activity/Provider 做成真正运行时闭环。

- `ContainerActivity` 只负责接收 `instanceId` 和进入 runtime，不继续承载业务分支。
- `VirtualInstrumentation.newActivity()` 必须稳定把 proxy Activity 替换成 guest Activity。
- `VirtualInstrumentation.execStartActivity()` 必须覆盖 minimal app 的 `MainActivity -> SecondActivity`。
- `StubContentProvider` 继续作为 host 唯一 provider 入口，默认 hook-free；Java pass-through hook 仅 debug/profile 显式开启。

P1：补齐虚拟系统服务层。

- 新增 `VirtualServiceManager`：先支持 `startService`，再支持 `bindService`。
- 新增 `VirtualBroadcastManager`：先支持动态 receiver，再处理 manifest receiver 的受控分发。
- 扩展 `VirtualPackageSnapshot`：补 `ServiceInfo`、`ReceiverInfo`、权限、签名摘要、native library、split APK 信息。
- 将 `VirtualPackageRegistry` 从 process-local map 演进到可恢复的 runtime registry，避免进程被杀后 provider/activity record 丢失。

P2：补 native 和 protected app 诊断。

- 普通容器 profile：默认不启用 LSPlant/native inline hook，只做 Java 层虚拟化和可观测 evidence。
- Native IO profile：单独实现 per-instance 文件路径重定向，先覆盖 `open/access/stat/readlink` 等最小集合。
- QQ 阅读 profile：只做 `register-natives observe-only diagnostics`，不得默认 no-op/stub 加固逻辑。

### 8.4 下一轮验收口径

下一轮不能再用“启动了 ContainerActivity”作为完成标准，必须按开源容器能力验收：

```text
E3 Activity chain:
  guest MainActivity onCreate
  guest click Launch SecondActivity
  execStartActivity remap evidence
  proxy Activity allocation evidence
  guest SecondActivity onCreate
  Back 返回 MainActivity

E4 Provider chain:
  guest ContentResolver query original authority
  Uri rewrite to host stub authority
  StubContentProvider dispatch
  guest provider attachInfo/onCreate
  query result or明确 unsupported result

E5 Runtime reuse:
  same instanceId second launch does not recreate Application unnecessarily
  process runtime cache has deterministic destroy/rebind behavior
```

## 9. 当前负责人结论

参考开源项目后的路线不变，但判断更清楚：MultiApp v2 应该继续走 VirtualApp/DroidPlugin 式用户态容器，而不是回到 Stub APK 或 LSPatch patch APK。短期最关键的不是 QQ 阅读，而是先把普通 APK 的 Activity/Provider/Storage/PMS 做到稳定闭环；否则加固应用只会把底层容器缺口放大。

当前已对齐的开源骨架：虚拟安装库、实例库、proxy Activity、Instrumentation、stub provider、provider dispatcher、in-process VPMS、Java storage baseline。

当前缺口：Service/Broadcast、完整 Activity stack、完整 Provider 权限/observer/跨进程、native IO redirect、split APK/native lib 完整处理、QQ 阅读 protected profile 真机证据。

## 10. 2026-06-28 Provider profile 启动开关落地

为对齐 VirtualApp/DroidPlugin 式 Provider authority rewrite 验证，本轮补齐 MultiApp 入口到 `HostedRuntimeBootstrap` 的 profile 控制链路：

```text
ContainerActivity.createIntent(..., providerHookEnabled = true)
  -> Intent extra: multiapp.profile.providerHookEnabled
  -> ContainerActivity.onCreate()
  -> HostedRuntimeBootstrap(providerHookInstallEnabled = true)
  -> VirtualProviderHookInstaller.install(providerRoutingPlan)
  -> CLASS_LOADER evidence records providerHookInstallStatus
```

默认行为仍然是：

```text
providerHookEnabled=false
providerHookInstallStatus=SKIPPED
providerHookInstallReason=PROFILE_DISABLED
```

这条边界必须保持不变。普通 APK baseline、双实例 baseline、QQ 阅读 protected baseline 都不得默认启用 Provider hook。只有 E4 Provider routing/profile 验证可以显式开启该开关。

本轮代码改动：

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/multiapp/app/container/ContainerActivity.kt` | 新增 `EXTRA_ENABLE_PROVIDER_HOOK=multiapp.profile.providerHookEnabled`；`createIntent()` 增加 `providerHookEnabled` 参数；`onCreate()` 读取 extra 并传入 `HostedRuntimeBootstrap`。 |
| `app/src/test/java/com/multiapp/app/container/ContainerActivityTest.kt` | 增加 intent extra contract 测试，锁定 profile key。 |

真机验证时，E4 应抓取以下证据：

```text
ContainerActivity: providerHookEnabled=true
HostedRuntimeBootstrap CLASS_LOADER evidence:
  providerRoutingEnabled=true
  providerRoutingPrimary=CONTENT_RESOLVER_PASS_THROUGH_HOOK
  providerHookInstallStatus=INSTALLED 或 FAILED
  providerHookInstallReason=<reason>
```

如果 Android 16 / 小米 ROM 上 Java pass-through hook 失败，下一步按开源容器的 fallback 路线切到 `ACTIVITY_THREAD_PROVIDER_ACQUISITION_PROXY`，不在普通 baseline 中强行启用 hook。

## 11. 2026-06-28 E3 Activity remap 原始 Intent 保真

参考 VirtualApp/DroidPlugin 的 proxy Activity 模式，本轮修正 guest 内部 `startActivity` 的一个关键语义问题：proxy intent 不能直接作为 guest Activity 的业务 intent 使用，必须保存并恢复原始 guest intent。

新链路：

```text
guest Activity startActivity(originalGuestIntent)
  -> VirtualInstrumentation.execStartActivity()
  -> VirtualIntentResolver.resolveActivity(originalGuestIntent)
  -> VirtualActivityManager.allocateGuestActivity()
  -> createProxyIntent(record, sourceIntent = originalGuestIntent)
  -> proxy intent carries multiapp.originalGuestIntent
  -> VirtualInstrumentation.newActivity(proxyClassName, proxyIntent)
  -> buildGuestActivityIntent(originalGuestIntent + internal instance extras)
  -> base.newActivity(guestClassLoader, guestActivityClassName, guestIntent)
```

本轮代码改动：

| 文件 | 改动 |
| --- | --- |
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityManager.kt` | 新增 `EXTRA_ORIGINAL_GUEST_INTENT=multiapp.originalGuestIntent`；`createProxyIntent()` 支持携带原始 guest intent；`launchGuestActivity()` 使用 `request.sourceIntent`。 |
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt` | remap 后写入 `$instanceId.activity-remap.properties`；guest Activity 创建时恢复原始 intent，并补入 `instanceId` / `guestActivityClassName` 内部字段。 |
| `core/loader/src/test/java/com/multiapp/core/loader/VirtualActivityManagerTest.kt` | 增加 original guest intent extra key contract 测试。 |

新增真机证据文件：

```text
/data/data/<hostPackage>/files/hosted_launch_evidence/<instanceId>.activity-remap.properties

status=GUEST_ACTIVITY_REMAP
stage=ACTIVITY_START_REMAP
guestActivityClassName=<guest activity>
proxyActivityClassName=<host proxy activity>
reason=explicit|launcher
```

E3 真机验收时，应同时出现：

```text
<instanceId>.activity-remap.properties:
  status=GUEST_ACTIVITY_REMAP
  guestActivityClassName=com.test.minimal.SecondActivity

<instanceId>.properties:
  status=GUEST_ACTIVITY_CONTEXT_INJECTED 或 GUEST_ACTIVITY_SUBSTITUTED
  guestActivityClassName=com.test.minimal.SecondActivity
```

这一步仍不等于完整 Activity stack。尚未完成的部分包括 taskAffinity/launchMode、activity result、configuration change、process death record recovery、Back stack 完整策略。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

## 12. 2026-06-28 E3 Activity remap 失败证据补齐

为避免真机 E3 出现“点击后没有反应但无法定位”的问题，本轮补齐 `VirtualInstrumentation.execStartActivity()` 的 remap skipped/failed evidence。

现在只要目标 Activity 带有 `multiapp.instanceId`，就会在以下情况写出结构化证据：

| status | reason | 含义 | 下一步 |
| --- | --- | --- | --- |
| `GUEST_ACTIVITY_REMAP` | `explicit` / `launcher` | guest intent 已成功映射到 host proxy Activity | 继续看 guest Activity substitution/context evidence |
| `GUEST_ACTIVITY_REMAP_SKIPPED` | `PACKAGE_SNAPSHOT_MISSING` | runtime 存在，但没有 `VirtualPackageSnapshot` | 修 `HostedRuntimeBootstrap` / manifest resolver / package snapshot 创建 |
| `GUEST_ACTIVITY_REMAP_SKIPPED` | `INTENT_NOT_RESOLVED` | intent 进入 remap，但没有解析到 guest Activity | 修 `VirtualIntentResolver`，重点看 explicit component、relative class name、virtual/origin package 匹配 |
| `GUEST_ACTIVITY_REMAP_FAILED` | `RUNTIME_BOOTSTRAP_FAILED` | remap 前无法恢复 hosted runtime | 修 instance/install store、origin APK path 或 bootstrap stage |
| `GUEST_ACTIVITY_REMAP_FAILED` | `PROXY_INTENT_CREATE_FAILED` | 已解析 guest Activity，但 proxy 分配/intent 构造失败 | 修 `ProxyActivityRegistry` / `VirtualActivityManager` |

证据文件仍为：

```text
/data/data/<hostPackage>/files/hosted_launch_evidence/<instanceId>.activity-remap.properties
```

示例：

```text
status=GUEST_ACTIVITY_REMAP_SKIPPED
stage=ACTIVITY_START_REMAP
reason=INTENT_NOT_RESOLVED
intentAction=
intentComponent=com.test.minimal/.SecondActivity
intentData=
```

这一步对齐开源容器团队常用做法：代理组件链路必须让每个路由决策可观测，不能把未支持的情况静默交回系统，否则系统会尝试启动未注册 guest Activity 并产生误导性失败。

补充验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --console=plain --no-build-cache
```

结果：`BUILD SUCCESSFUL`。

## 13. 2026-06-28 E3 launchMode-aware proxy Activity 池

参考 VirtualApp/DroidPlugin 的 stub Activity 池，本轮把 hosted container 的 proxy Activity 从两个通用 `standard` slot 扩展为按 `launchMode` 分组的 slot。目标是让 guest Activity 的 manifest 属性先进入容器路由模型，避免所有 Activity 都被压成 `standard`。

新增 host proxy slot：

```text
standard:
  com.multiapp.app.container.ProxyActivity0
  com.multiapp.app.container.ProxyActivity1

singleTop:
  com.multiapp.app.container.ProxyActivitySingleTop0
  com.multiapp.app.container.ProxyActivitySingleTop1

singleTask:
  com.multiapp.app.container.ProxyActivitySingleTask0
  com.multiapp.app.container.ProxyActivitySingleTask1
```

当前映射策略：

| guest launchMode | host proxy launchMode | 说明 |
| --- | --- | --- |
| `standard` / 未声明 | `standard` | 默认路径 |
| `singleTop` | `singleTop` | 保留基本 top 复用语义入口 |
| `singleTask` | `singleTask` | 为后续 task/back-stack 管理预留 |
| `singleInstance` | `singleTask` | 临时降级，record 保留归一化后的 `singleTask` |
| `singleInstancePerTask` | `singleTask` | 临时降级，后续需要 Android 12+ 单独策略 |

本轮代码改动：

| 文件 | 改动 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | 新增 `ProxyActivitySingleTop0/1`、`ProxyActivitySingleTask0/1` 声明。 |
| `app/src/main/java/com/multiapp/app/container/ProxyActivityBase.kt` | 新增对应 proxy Activity 类。 |
| `core/model/src/main/java/com/multiapp/core/model/virtual/VirtualPackageResolver.kt` | `ResolvedComponent` 新增 `launchMode`。 |
| `core/model/src/main/java/com/multiapp/core/model/virtual/VirtualActivityRecord.kt` | `VirtualActivityRecord` 新增 `launchMode`。 |
| `core/model/src/main/java/com/multiapp/core/model/virtual/ProxyActivityRegistry.kt` | 支持按 `launchMode` 选择 proxy slot。 |
| `core/loader/src/main/java/com/multiapp/core/loader/ManifestVirtualPackageResolver.kt` | manifest `launchMode` 进入 `ResolvedComponent`。 |
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualIntentResolver.kt` | `VirtualActivityLaunchRequest` 保留目标 Activity `launchMode`。 |
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityManager.kt` | proxy intent/spec/record 保留 `launchMode`。 |
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt` | remap evidence 增加 `launchMode` 字段。 |
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt` | `Context.startActivity()` 辅助 remap 路径同步使用 launchMode-aware proxy 池，并携带原始 guest intent。 |
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt` | `ActivityInfo.launchMode` 从 virtual snapshot 返回。 |

这一步仍不等于完整 task/back-stack 实现。它只是把开源容器中的 proxy slot 池模型落到当前 v2 主线，并让后续真机 E3 能观察到 guest `launchMode` 是否进入路由决策。

补充验证命令：

```powershell
.\gradlew.bat :core:model:testDebugUnitTest --console=plain --no-build-cache
.\gradlew.bat :core:loader:testDebugUnitTest --console=plain --no-build-cache
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-build-cache
```

结果：全部 `BUILD SUCCESSFUL`。

## 14. 2026-06-28 多角色开源方案批判性审核结论

本轮按三个方向复核 VirtualApp / DroidPlugin / BlackBox 的已验证功能模型，并映射到当前 v2 hosted container。结论：过时代码不能直接复制，但它们验证过的控制面必须补齐。

### 14.1 Activity/AMS/Instrumentation 组

已对齐：

```text
hosted launch
proxy Activity slot
Instrumentation.newActivity proxy -> guest substitution
execStartActivity remap
guest original Intent preservation
launchMode-aware proxy slot MVP
remap/substitution evidence
```

P0 缺口：

```text
VirtualActivityStack / TaskRecord
singleTop/singleTask/CLEAR_TOP/NEW_TASK 真语义
onNewIntent / finish / result 路由
startActivities 覆盖
AMS/IActivityManager profile-controlled hook
隐式 Activity intent-filter resolver
```

执行建议：先补 `VirtualActivityStack + onNewIntent/result/finish`，不要直接上全局 AMS hook。AMS hook 必须 profile-controlled，默认 baseline 不启用。

### 14.2 Provider/Service/Broadcast 组

Provider 已对齐 MVP：

```text
host StubContentProvider
provider authority map
VirtualProviderDispatcher
VirtualProviderRuntime create/attach/cache
profile-controlled Provider hook
```

Provider 缺口：

```text
ContentObserver / notifyChange
permission / AppOps
cross-process provider recovery
ActivityThread provider acquisition fallback
```

Service 当前进度：本轮已补 host `StubService` + `VirtualServiceManager` + `VirtualContextWrapper.startService()` remap。当前只证明 guest explicit service intent 能转到 host stub 并写 evidence，不代表 guest Service 生命周期完成。

Broadcast 当前缺口：

```text
VirtualBroadcastManager
VirtualReceiverRuntime
sendBroadcast / registerReceiver remap
manifest receiver 分发
ordered/sticky broadcast 策略
```

### 14.3 VPMS/Storage/native IO 组

已对齐：

```text
InstallRecord / VirtualInstanceRecord
VirtualPackageSnapshot
VirtualPackageManagerWrapper
VirtualPackageInfoFactory
VirtualContextWrapper Java storage isolation
native IO hook primitives
```

P0 缺口：

```text
VPMS 只是 Context PackageManager wrapper，不是统一 VirtualPackageService
VirtualPackageSnapshot 字段不足：processName/taskAffinity/theme/metaData/signing/split/uid/provider grant 等
JSON install record 还不是 package settings
native IO redirect 未作为 hosted runtime stage 接入
native redirect 是进程全局状态，缺 instance owner 约束
originPackageName / virtualPackageName guest-visible 策略需要硬化
```

执行建议：先抽 `VirtualPackageService` 作为统一查询后端，再考虑 `AppGlobals/IPackageManager` profile hook；native IO 先做 `HostedStorageRedirectStage`，默认关闭，profile 显式启用。

## 15. 2026-06-28 Service proxy MVP 落地

参考 DroidPlugin/VirtualApp 的 stub Service 控制点，本轮先补 Service 代理入口，不直接宣称完整 Service lifecycle。

新链路：

```text
guest Context.startService(originalGuestServiceIntent)
  -> VirtualContextWrapper.startService()
  -> VirtualServiceManager.resolveStartService(snapshot, intent)
  -> create proxy Intent targeting host StubService
  -> host StubService.onStartCommand()
  -> write hosted_launch_evidence/<instanceId>.service-proxy.properties
  -> stopSelf(startId)
```

当前支持范围：

```text
explicit startService
explicit startForegroundService
same hosted process / same instance snapshot
host stub evidence only
```

当前不支持：

```text
guest Service 反射创建
Service.attach / onCreate / onStartCommand / onDestroy
bindService / onBind / onUnbind
implicit service intent-filter resolve
foreground service timeout handling
cross-process service recovery
```

本轮代码改动：

| 文件 | 改动 |
| --- | --- |
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceManager.kt` | 新增 Service explicit intent resolver、proxy spec、proxy intent 构造。 |
| `core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt` | 覆盖 `startService()` / `startForegroundService()`，优先 remap guest service 到 host stub。 |
| `app/src/main/java/com/multiapp/app/container/StubService.kt` | 新增 host service proxy slot，写入 `service-proxy.properties` evidence。 |
| `app/src/main/AndroidManifest.xml` | 声明 `.container.StubService`。 |
| `core/loader/src/test/java/com/multiapp/core/loader/VirtualServiceManagerTest.kt` | 覆盖 explicit service resolve、outside package skip、proxy spec。 |

新增 evidence：

```text
/data/data/<hostPackage>/files/hosted_launch_evidence/<instanceId>.service-proxy.properties

status=GUEST_SERVICE_PROXY_RECEIVED
stage=SERVICE_PROXY
originPackageName=<origin package>
guestServiceClassName=<guest service>
reason=explicit
startId=<host startId>
lifecycle=HOST_STUB_ONLY
```

验证命令：

```powershell
.\gradlew.bat :core:loader:testDebugUnitTest --console=plain --no-build-cache --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-build-cache --no-daemon
```

结果：全部 `BUILD SUCCESSFUL`。

注意：本轮曾因并行运行两个 Gradle/KSP 任务触发 `Storage for ... kspCaches ... is already registered`。处理方式是停止 daemon 后串行运行验证；这不是业务代码失败。后续不要并行跑涉及同一 module KSP cache 的 Gradle 任务。

## 16. 2026-06-28 三角色执行落地批次 1

本轮执行方式调整为：三个角色负责落地代码，负责人只做边界控制、代码审核和最终验证。写入范围已隔离，未直接复制第三方开源代码，而是把 VirtualApp / DroidPlugin / BlackBox 已验证的功能模型拆入当前 v2 hosted container。

### 16.1 Activity 组：VirtualActivityStack / TaskRecord MVP

落地文件：

```text
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualActivityRecord.kt
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualTaskRecord.kt
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualActivityStack.kt
core/model/src/test/java/com/multiapp/core/model/virtual/VirtualActivityStackTest.kt
```

能力：

```text
standard -> new record
singleTop -> top same component reuse
singleTask -> find existing same component, clear above, reuse
FLAG_ACTIVITY_CLEAR_TOP -> clear above within selected task
FLAG_ACTIVITY_NEW_TASK -> select/create task by affinity
```

边界：纯模型，不接 AMS，不接 Instrumentation lifecycle，不处理 result/document/multi-window/task reparenting。

### 16.2 Service 组：VirtualServiceRuntime MVP

落地文件：

```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceRuntime.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceRecordManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceManager.kt
app/src/main/java/com/multiapp/app/container/StubService.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualServiceRuntimeTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualServiceManagerTest.kt
```

能力：

```text
explicit startService/startForegroundService -> StubService
StubService -> VirtualServiceDispatcher / VirtualServiceRuntime
factory create guest Service
attacher attach guest Service
onCreate once
repeated start reuses service
onStartCommand called per start
structured failures: RuntimeNotBound/RuntimeIncomplete/Unsupported/CreateFailed/AttachFailed/OnCreateFailed/OnStartCommandFailed
```

边界：不支持 stopService/onDestroy，不支持 bindService，不支持 implicit service，不支持真实 foreground service timeout/notification 语义。DefaultServiceAttacher 真实反射路径尚未真机验证。

### 16.3 VPMS 组：VirtualPackageService MVP

落地文件：

```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageService.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageManagerWrapper.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualPackageServiceTest.kt
```

能力：

```text
VirtualPackageSnapshot -> VirtualPackageService -> VirtualPackageManagerWrapper
getPackageInfo/getApplicationInfo
getActivityInfo/getServiceInfo/getReceiverInfo/getProviderInfo
resolveContentProvider
queryIntentActivities/resolveActivity
queryIntentServices/resolveService
queryBroadcastReceivers/queryIntentContentProviders
getInstalledPackages/getInstalledApplications
checkPermission/isInstantApp
```

边界：单 snapshot 后端，不是跨实例全局 VPMS；IntentFilter 匹配仍是 action/category 字符串简化；没有 AppGlobals/IPackageManager hook。

### 16.4 负责人审核结果

审核结论：三组改动符合开源方案功能模型填充方向，且没有越界到高风险系统 hook。当前仍不能宣称完整 VirtualApp/BlackBox 级容器，但普通容器骨架从 Activity/Provider 继续扩展到了 Service lifecycle MVP 和 VPMS service 后端。

联合验证命令：

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache --no-daemon
```

结果：`BUILD SUCCESSFUL`。

补充检查：

```powershell
git diff --check -- <本批次相关文件>
```

结果：无 whitespace error。

## 17. 2026-06-28 三角色执行落地批次 2

本批次继续采用三角色执行、负责人审核。目标不是新增高风险 hook，而是把上一批模型接入现有 runtime，并补齐 PMS 字段模型。

### 17.1 Activity 组：VirtualActivityStack 接入 VirtualActivityManager

落地范围：

```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityRecordManager.kt
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualActivityStack.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualActivityManagerTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualActivityRecordManagerTest.kt
```

能力：

```text
VirtualActivityManager.allocateGuestActivity()
  -> VirtualActivityRecordManager.registerLaunch()
  -> VirtualActivityStack.launch()
  -> returns record with taskId/intentFlags/state/taskAffinity
```

新增可观测决策：

```text
lastLaunchResult().reused
lastLaunchResult().clearedActivities
listTasks()
```

边界：仍不触发 `onNewIntent`，不做 AMS hook，不做真实 task/back-stack 系统同步。

### 17.2 Service 组：stopService / onDestroy MVP

落地范围：

```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceRuntime.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceManager.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualServiceRuntimeTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualServiceManagerTest.kt
```

能力：

```text
VirtualServiceRuntime.stop(instanceId, guestServiceClassName)
  -> NotFound if missing
  -> service.onDestroy()
  -> remove record on success
  -> OnDestroyFailed and keep record on failure
```

新增 manager/dispatcher stop request 结构。边界：未接 `VirtualContextWrapper.stopService()`，未做 bindService，未做 implicit service。

### 17.3 VPMS 组：PMS 字段模型补齐

落地范围：

```text
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualPackageSnapshot.kt
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualPackageResolver.kt
core/loader/src/main/java/com/multiapp/core/loader/ManifestVirtualPackageResolver.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageInfoFactory.kt
core/model/src/test/java/com/multiapp/core/model/virtual/VirtualPackageResolverTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualPackageSnapshotFactoryTest.kt
```

新增字段：

```text
processName
taskAffinity
themeId
screenOrientation
configChanges
permission
metaData
```

传播路径：

```text
ManifestParser existing fields
  -> ManifestVirtualPackageResolver
  -> ResolvedPackage / ResolvedComponent
  -> VirtualPackageSnapshot
  -> VirtualPackageInfoFactory
  -> ApplicationInfo / ActivityInfo / ServiceInfo / ProviderInfo
```

边界：不硬造 parser 没有的 provider process/permission；不做 IPackageManager hook。

### 17.4 负责人集成修复

全量 `core:loader` 曾暴露 JVM local unit test 使用真实 Android `Intent.getFlags()` 的问题：

```text
Method getFlags in android.content.Intent not mocked
```

修复方式：仅修改 `VirtualActivityManagerTest`，用 mock intent stub `flags`，不改生产逻辑。

### 17.5 验证

联合验证命令：

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest :app:testDebugUnitTest --console=plain --no-build-cache --no-daemon
```

结果：`BUILD SUCCESSFUL`。

补充检查：

```powershell
git diff --check -- <本批次相关文件>
```

结果：无 whitespace error，仅有 LF/CRLF 提示。

## 18. 2026-06-28 三角色执行落地批次 3

本批按负责人审查结论继续补低风险连接器，不进入 AMS/IPackageManager 全局 hook，不改变 v2 hosted container 的 baseline 原则。

### 18.1 Activity 组：pending onNewIntent / finish / result 模型

落地范围：
```text
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualActivityRecord.kt
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualActivityStack.kt
core/model/src/test/java/com/multiapp/core/model/virtual/VirtualActivityStackTest.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityRecordManager.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualActivityRecordManagerTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualActivityManagerTest.kt
```

能力：
```text
singleTop / singleTask / FLAG_ACTIVITY_CLEAR_TOP reused launch
  -> record pendingNewIntent evidence
  -> do not call real Activity.onNewIntent yet

finish(token/activityId)
  -> mark FINISHED for diagnostics
  -> remove from task stack
  -> release proxy mapping when it still points to the finished record

setResult(token/activityId)
  -> store VirtualActivityResult on active or retained record
```

负责人修正：
```text
VirtualActivityRecordManagerTest nullable pendingNewIntent assertion
VirtualActivityManagerTest CLEAR_TOP expectation changed from removed record to FINISHED evidence record
```

边界：仍未接真实 `onNewIntent` 分发，未接 `onActivityResult` 回调，未做系统 task/back-stack 同步。

### 18.2 Service 组：VirtualContextWrapper.stopService 接线

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualContextWrapperTest.kt
```

能力：
```text
VirtualContextWrapper.stopService(explicit guest service intent)
  -> VirtualServiceManager.resolveStopService(snapshot, intent)
  -> VirtualServiceDispatcher.dispatchStop(request)
  -> VirtualServiceRuntime.stop(...)

ServiceStopped -> true
ServiceNotFound / InstanceNotFound / OnDestroyFailed / unsupported -> false
lastStopServiceDispatchResult() keeps structured evidence
```

边界：只支持 explicit guest service；不做 `bindService`、implicit service、foreground service timeout、权限校验。

### 18.3 Broadcast 组：in-process explicit receiver MVP

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualBroadcastManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualReceiverRuntime.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualBroadcastManagerTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualReceiverRuntimeTest.kt
```

能力：
```text
VirtualBroadcastManager.dispatch(snapshot, intent, virtualContext, classLoader)
  -> explicit receiver component resolve
  -> VirtualReceiverRuntime creates BroadcastReceiver through guest classLoader
  -> receiver.onReceive(virtualContext, intent)
```

结构化结果：
```text
Delivered
UnsupportedImplicit
ReceiverClassNotFound
ReceiverCreateFailed
OnReceiveFailed
```

边界：未接 `VirtualContextWrapper.sendBroadcast()`，未做 `registerReceiver`，未做 `sendOrderedBroadcast`，未做 manifest 静态 receiver 冷启动，未做权限/系统广播。

### 18.4 负责人审查结论

本批改动符合开源容器路线的“先建虚拟组件记录与代理分发，再逐步接系统入口”的顺序。当前实现补齐的是 hosted runtime 内部可观测模型，不是完整 VirtualApp/BlackBox/DroidPlugin 级四大组件虚拟化。

可以继续推进的下一批：
```text
Activity: 将 pendingNewIntent/result 连接到 ProxyActivityBase / VirtualInstrumentation 的受控回调点
Service: 增加 VirtualContextWrapper.startService/stopService evidence 聚合与 fixture 级生命周期验证
Broadcast: 将 explicit sendBroadcast 接入 VirtualContextWrapper，并保留 unsupported implicit 的明确返回/证据
VPMS: 继续改善 intent-filter matching，不做 AppGlobals/IPackageManager hook
```

禁止提前推进：
```text
默认启用 AMS hook
默认启用 IPackageManager hook
默认启用 LSPlant/Xposed/native diagnostics
把 QQ 阅读专项兼容混进普通容器 baseline
```

### 18.5 验证

命令：
```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest --console=plain --no-build-cache --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

## 19. 2026-06-28 三角色执行落地批次 4

本批继续参考 VirtualApp / BlackBox / DroidPlugin 的组件虚拟化共性路线：先把组件调用收敛到 hosted runtime 内部的记录、解析、分发和 evidence 层，再逐步接更深的系统入口。仍不默认启用 AMS/IPackageManager hook，不把 QQ 阅读专项诊断混入普通容器 baseline。

### 19.1 Activity 组：pending/result 可控消费点

落地范围：
```text
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualActivityStack.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityRecordManager.kt
core/model/src/test/java/com/multiapp/core/model/virtual/VirtualActivityStackTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualActivityRecordManagerTest.kt
```

能力：
```text
consumePendingNewIntentByToken / consumePendingNewIntentByActivityId
  -> FIFO consume oldest pending event
  -> remove consumed event from record/stack

consumeResultByToken / consumeResultByActivityId
  -> read VirtualActivityResult
  -> clear result after consume
```

固定语义：
```text
active record can consume pendingNewIntent and result
finished diagnostic record cannot consume pendingNewIntent
finished diagnostic record can consume and clear result
missing token/activityId returns null
```

边界：仍不触发真实 `onNewIntent` / `onActivityResult`，只是为后续 `ProxyActivityBase` / `VirtualInstrumentation` 接入提供安全消费点。

### 19.2 Service 组：start/stop evidence 聚合

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualContextWrapperTest.kt
```

能力：
```text
startService / startForegroundService
  -> lastStartServiceMappingResult records Remapped or Fallback
  -> explicit guest service remap evidence includes request/proxyIntent/foreground
  -> fallback evidence records reason

stopService
  -> keeps lastStopServiceDispatchResult from previous batch
```

固定语义：
```text
unsupported service fallback is not treated as virtual success
start evidence and stop evidence do not overwrite each other incorrectly
```

边界：不做 `bindService`、implicit service、foreground timeout、权限校验、多进程 service。

### 19.3 Broadcast 组：VirtualContextWrapper.sendBroadcast 接线

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualBroadcastManager.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualContextWrapperTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualBroadcastManagerTest.kt
```

能力：
```text
VirtualContextWrapper.sendBroadcast(intent)
  -> explicit guest receiver resolved from packageSnapshot
  -> VirtualBroadcastManager.dispatchExplicit(...)
  -> VirtualReceiverRuntime dispatches in-process
  -> Delivered: do not call base.sendBroadcast
  -> non-delivered: fallback to base.sendBroadcast and keep evidence
```

新增结果：
```text
NoPackageSnapshot
ReceiverNotFound
UnsupportedImplicit
Delivered
ReceiverClassNotFound / ReceiverCreateFailed / OnReceiveFailed
```

边界：不做 ordered broadcast、`registerReceiver`、manifest receiver 冷启动、系统广播、权限校验。

### 19.4 负责人审查结论

本批把上一批的“可记录”推进到“可消费/可审计/可 fallback”。这比单纯堆 hook 更接近开源容器项目的稳定路径：组件入口先进入虚拟解析和代理层，hook 只作为后续 profile-controlled 的补强点。

下一批建议：
```text
Activity: 在 ProxyActivityBase 或 VirtualInstrumentation 中消费 pendingNewIntent/result，但仍以受控 evidence 为先
Broadcast: 增加 dynamic receiver registry 设计，不急于接系统 registerReceiver
Service: 增加 Service fixture lifecycle evidence，验证 start/stop 与 dataRoot/package identity 不串
VPMS: 改善 intent-filter matching，支持 action/category/data 的更完整匹配
```

### 19.5 验证

命令：
```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest --console=plain --no-build-cache --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

补充检查：
```powershell
git diff --check -- <本批次相关文件>
```

结果：无 whitespace error，仅有 LF/CRLF 提示。

## 20. 2026-06-28 三角色执行落地批次 5

本批继续按开源容器共同路线推进：把组件解析、动态注册、proxy evidence 消费落到 hosted runtime 内部，不引入默认 AMS/IPackageManager hook。

### 20.1 VPMS 组：IntentFilter matcher 对齐

落地范围：
```text
core/model/src/main/java/com/multiapp/core/model/virtual/VirtualPackageResolver.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualPackageService.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualIntentResolver.kt
core/model/src/test/java/com/multiapp/core/model/virtual/VirtualPackageResolverTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualPackageServiceTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualIntentResolverTest.kt
```

能力：
```text
ResolvedIntentFilter(actions/categories/dataSchemes)
VirtualIntentFilterMatcher
explicit component priority
action match
category subset match
data scheme match
```

覆盖：
```text
MAIN/LAUNCHER
VIEW http scheme
custom action/category
scheme mismatch
explicit component priority
```

边界：不做 MIME/host/path 完整匹配，不做 package visibility，不做 permission/signature，不做 IPackageManager hook。

### 20.2 Broadcast 组：Dynamic Receiver Registry MVP

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualDynamicReceiverRegistry.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualBroadcastManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualDynamicReceiverRegistryTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualBroadcastManagerTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualContextWrapperTest.kt
```

能力：
```text
VirtualDynamicReceiverRegistry.register(instanceId, receiver, filter)
VirtualDynamicReceiverRegistry.unregister(receiver)
VirtualDynamicReceiverRegistry.query(instanceId, intent)

VirtualContextWrapper.registerReceiver(receiver, filter)
VirtualContextWrapper.unregisterReceiver(receiver)
VirtualContextWrapper.sendBroadcast(intent)
  -> dynamic receiver first
  -> explicit manifest receiver second
  -> fallback to base for unsupported paths
```

匹配范围：
```text
action
category subset
data scheme
instanceId isolation
```

边界：不做 ordered broadcast，不做系统广播白名单，不做跨进程，不做 permission，不做 manifest static receiver cold start。

### 20.3 Activity Runtime 组：ProxyActivity evidence 消费

落地范围：
```text
app/src/main/java/com/multiapp/app/container/ProxyActivityBase.kt
app/src/main/java/com/multiapp/app/container/ProxyActivityEvidence.kt
app/src/test/java/com/multiapp/app/container/ProxyActivityEvidenceTest.kt
```

能力：
```text
ProxyActivityBase resolves token
  -> VirtualActivityRecordManager.consumePendingNewIntent(token)
  -> VirtualActivityRecordManager.consumeResult(token)
  -> writes evidence fields
```

新增 evidence 字段：
```text
pendingNewIntentConsumed
pendingAction
pendingFlags
resultConsumed
resultCode
```

边界：不调用真实 guest `onNewIntent` / `onActivityResult`，不改 VirtualInstrumentation，不做系统 task/back-stack sync。

### 20.4 负责人审查结论

本批把上一阶段的“可记录/可消费”继续推到容器关键入口：VPMS 解析更接近开源容器的 component resolver；动态 receiver 支持本进程收发；ProxyActivity 已能消费 pending/result 并写证据。仍然属于 hosted runtime 内部能力，不应对外宣称完整四大组件虚拟化。

下一批建议：
```text
Broadcast: 增加 registerReceiver overload 和 permission 参数的明确 unsupported/evidence
Activity: 在 ProxyActivityBase 记录 consumed event 后补 lifecycle ordering evidence
Service: 增加 minimal fixture Service 真机/仪器 evidence
Provider: 补 ContentObserver/notifyChange 或 provider acquisition fallback 设计
```

### 20.5 验证

命令：
```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest --console=plain --no-build-cache --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

补充检查：
```powershell
git diff --check -- <本批次相关文件>
```

结果：无 whitespace error，仅有 LF/CRLF 提示。

## 21. 2026-06-28 三角色执行落地批次 6

本批继续补“可诊断入口”，让 hosted container 在 unsupported 路径上也能留下明确证据，而不是静默落回宿主或靠异常判断。

### 21.1 Broadcast 组：registerReceiver overload / permission evidence

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualContextWrapperTest.kt
```

能力：
```text
BroadcastReceiverRegistrationResult.Registered
BroadcastReceiverRegistrationResult.Unregistered
BroadcastReceiverRegistrationResult.Fallback
lastBroadcastReceiverRegistrationResult()
```

固定语义：
```text
registerReceiver(receiver, filter)
  -> registers dynamic receiver in VirtualDynamicReceiverRegistry
  -> records Registered evidence

unregisterReceiver(receiver)
  -> removes dynamic receiver when found
  -> records Unregistered evidence

registerReceiver(receiver, filter, broadcastPermission, scheduler)
  -> records Fallback(reason=permissionOrSchedulerUnsupported)
  -> delegates to base Context
```

边界：暂不虚拟化 permission/scheduler overload，不做 ordered broadcast，不做跨进程 receiver。

### 21.2 Service 组：Lifecycle evidence mapper

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceLifecycleEvidence.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualServiceLifecycleEvidenceTest.kt
```

能力：
```text
VirtualServiceLifecycleEvidence.from(VirtualServiceRuntimeResult)
VirtualServiceLifecycleEvidence.from(VirtualServiceRuntimeStopResult)
```

事件：
```text
CREATED_AND_STARTED
STARTED_CACHED
CREATE_FAILED
ATTACH_FAILED
ON_CREATE_FAILED
ON_START_COMMAND_FAILED
STOPPED
STOP_NOT_FOUND
ON_DESTROY_FAILED
```

字段：
```text
instanceId
guestServiceClassName
event
success
cached
startCommandResult
errorClassName
errorMessage
```

边界：本批只提供稳定 evidence mapper，不改变 Service runtime 行为，不做 bindService/foreground timeout/permission。

### 21.3 负责人审查结论

这一批没有扩大能力口径，而是补了容器工程最关键的诊断面：动态 receiver 注册、unsupported overload、Service lifecycle 成功/失败都能稳定落到结构化 evidence。开源容器项目的稳定性来自入口收敛和状态可追踪，这一步是必要基础。

下一批建议：
```text
Provider: 补 provider acquisition fallback / notifyChange evidence
Service: 把 VirtualServiceLifecycleEvidence 接入 StubService 或 dispatcher 日志
Broadcast: 增加 flags overload 的 evidence 分支
Activity: 补 ProxyActivity evidence 的 lifecycle ordering 字段
```

### 21.4 验证

命令：
```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest --console=plain --no-build-cache --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

补充检查：
```powershell
git diff --check -- <本批次相关文件>
```

结果：无 whitespace error，仅有 LF/CRLF 提示。

## 22. 2026-06-28 三角色执行落地批次 7

本批继续补 Provider 与 Service 的结构化 evidence，把“已知 unsupported”从概念说明推进到代码可查询结果，避免后续调试只能依赖 logcat 异常。

### 22.1 Provider 组：acquisition / notifyChange evidence

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderEvidence.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualProviderManagerTest.kt
```

能力：
```text
VirtualProviderEvidence.acquisition(VirtualProviderOpenResult)
VirtualProviderEvidence.notifyChange(VirtualProviderUriRewrite?, originalAuthority)
```

字段：
```text
instanceId
guestAuthority
proxyAuthority
providerClassName
operation
success
reason
```

固定语义：
```text
openProvider known authority
  -> success=true
  -> reason=null

openProvider missing authority
  -> success=false
  -> reason=PROVIDER_NOT_FOUND

notifyChange rewritten authority
  -> success=true
  -> records guest/proxy authority

notifyChange missing authority
  -> success=false
  -> reason=PROVIDER_NOT_FOUND
```

边界：本批不实现真实 provider lifecycle，不接 ContentObserver，不接系统 provider acquisition hook。

### 22.2 Service 组：dispatcher lifecycle evidence 接入

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceManager.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualServiceManagerTest.kt
```

能力：
```text
VirtualServiceDispatchResult.ServiceStarted.lifecycleEvidence
VirtualServiceDispatchResult.ServiceCreateFailed.lifecycleEvidence
VirtualServiceDispatchResult.ServiceAttachFailed.lifecycleEvidence
VirtualServiceDispatchResult.ServiceOnCreateFailed.lifecycleEvidence
VirtualServiceDispatchResult.ServiceOnStartCommandFailed.lifecycleEvidence

VirtualServiceStopDispatchResult.ServiceStopped.lifecycleEvidence
VirtualServiceStopDispatchResult.ServiceNotFound.lifecycleEvidence
VirtualServiceStopDispatchResult.ServiceOnDestroyFailed.lifecycleEvidence
```

固定语义：
```text
runtime lifecycle result
  -> dispatcher result carries VirtualServiceLifecycleEvidence

runtime not bound / runtime incomplete
  -> remains explicit dispatcher state
  -> does not fake lifecycle evidence
```

边界：不改变 Service runtime 行为，不做 bindService，不做 foreground timeout，不做 permission。

### 22.3 负责人审查结论

本批把 Provider 和 Service 的诊断面继续收敛到结构化结果。Provider 仍不宣称生命周期可用，但 acquisition / notifyChange 的 known unsupported 与 not-found 已有稳定 evidence；Service dispatcher 不再只暴露业务结果，也携带 lifecycle evidence，便于后续接入 StubService 日志或真机 baseline 报告。

下一批建议：
```text
Provider: 把 VirtualProviderEvidence 接入 VirtualProviderDispatcher / StubContentProvider evidence
Service: 把 lifecycleEvidence 输出到 StubService 或 tools baseline report
Broadcast: 补 flags overload evidence
Activity: 补 ProxyActivity lifecycle ordering evidence
```

### 22.4 验证

命令：
```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest --console=plain --no-build-cache --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

补充检查：
```powershell
git diff --check -- <本批次相关文件>
```

结果：无 whitespace error。

## 23. 2026-06-28 三角色执行落地批次 8

本批把第 22 节的两个建议项继续推进到宿主可观测路径：Provider dispatcher 结果携带结构化 evidence，Service stub 日志改为消费统一 lifecycle evidence。目标不是扩大能力声明，而是让已知 unsupported、not-found、create/attach 失败和 service lifecycle 结果可以被测试、日志和后续 baseline report 稳定读取。

### 23.1 Provider 组：dispatcher evidence 接入

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderDispatcher.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderEvidence.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualProviderDispatcherTest.kt
app/src/main/java/com/multiapp/app/container/StubContentProvider.kt
```

能力：
```text
VirtualProviderDispatchResult.ProviderReady.evidence
VirtualProviderDispatchResult.RuntimeNotBound.evidence
VirtualProviderDispatchResult.RuntimeIncomplete.evidence
VirtualProviderDispatchResult.ProviderCreateFailed.evidence
VirtualProviderDispatchResult.ProviderAttachFailed.evidence
VirtualProviderDispatchResult.ProviderNotFound.evidence
```

固定语义：
```text
ProviderReady
  -> success=true
  -> reason=null

RuntimeNotBound / RuntimeIncomplete
  -> success=false
  -> reason=runtime state reason

ProviderCreateFailed / ProviderAttachFailed
  -> success=false
  -> reason=create/attach failure

ProviderNotFound
  -> success=false
  -> reason=PROVIDER_NOT_FOUND
```

StubContentProvider 已能把 dispatcher result 转成 Bundle/status 字段，下一步可以继续把 evidence 的完整字段接入 baseline report，但本批不把 provider lifecycle 伪装成已完成能力。

边界：不实现真实 ContentProvider lifecycle 完整闭环，不实现 ContentObserver，不默认接系统 ContentResolver hook，不接 IPackageManager/AMS 全局 hook。

### 23.2 Service 组：StubService lifecycle evidence 输出

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceLifecycleEvidence.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualServiceManager.kt
app/src/main/java/com/multiapp/app/container/StubService.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualServiceLifecycleEvidenceTest.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualServiceManagerTest.kt
```

能力：
```text
StubService start path
  -> lifecycle = lifecycleEvidence.event.name

StubService stop path
  -> lifecycle = lifecycleEvidence.event.name
```

覆盖事件：
```text
CREATED_AND_STARTED
STARTED_CACHED
CREATE_FAILED
ATTACH_FAILED
ON_CREATE_FAILED
ON_START_COMMAND_FAILED
STOPPED
STOP_NOT_FOUND
ON_DESTROY_FAILED
```

固定语义：Service dispatcher 继续返回明确的业务结果，同时携带 lifecycle evidence；StubService 不再手写零散 lifecycle 字符串，避免日志、测试和后续工具报告各自定义一套状态名。

边界：不实现 bindService，不实现 foreground service timeout，不实现 permission check，不改变现有 service runtime 调度策略。

### 23.3 负责人审查结论

本批是 evidence 收口，不是能力扩张。与 VirtualApp / BlackBox 类方案相比，当前项目仍处在 hosted user-space container 的早期阶段：已经开始具备 VPMS、activity/service/provider/broadcast 的局部虚拟化模型，但还没有完整 AMS/PMS/Provider/Storage hook 闭环，也没有多进程代理池。下一批应优先补齐“宿主可观测 evidence -> baseline report”的链路，而不是提前宣称 protected app 兼容。

下一批建议：
```text
Provider: StubContentProvider Bundle 输出完整 VirtualProviderEvidence 字段
Service: StubService evidence 补 errorClassName/errorMessage/startCommandResult
Broadcast: registerReceiver flags/permission/scheduler fallback evidence 进入 baseline report
Activity: ProxyActivityBase lifecycle ordering evidence
Verification: tools/hosted-container-baseline 汇总 activity/service/provider/broadcast evidence
```

### 23.4 验证

命令：
```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest --console=plain --no-build-cache --no-daemon
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

补充检查：
```powershell
git diff --check -- <本批次相关文件>
```

结果：无 whitespace error。

## 24. 2026-06-28 三角色执行落地批次 9

本批按架构、运行时、验证三组审查结果收口“宿主可观测 evidence 出口”。不新增 Hook，不默认启用 Provider hook / LSPlant / Xposed，不扩大 protected app 能力声明；只把 Activity / Provider / Service 的运行时状态拆成组件级 evidence 文件，避免 `$instanceId.properties` 被不同阶段互相覆盖。

### 24.1 Evidence 文件模型

落地范围：
```text
app/src/main/java/com/multiapp/app/container/ContainerRuntimePaths.kt
app/src/main/java/com/multiapp/app/container/ContainerRuntimeEvidenceWriter.kt
app/src/test/java/com/multiapp/app/container/ContainerRuntimePathsTest.kt
```

新增能力：
```text
ContainerRuntimePaths.hostedRuntimeEvidenceFile(filesDir, instanceId, component)
ContainerRuntimeEvidenceWriter.write(context/filesDir, instanceId, component, fields)
```

文件命名：
```text
$instanceId.launch.properties
$instanceId.activity-proxy.properties
$instanceId.activity-context.properties
$instanceId.activity-instrumentation.properties
$instanceId.activity-remap.properties
$instanceId.provider-proxy.properties
$instanceId.service-proxy.properties
```

固定语义：组件 evidence 只描述当前组件路径的最后一次状态，不再把所有阶段写到同一个 `$instanceId.properties`。

### 24.2 Activity 组：launch/proxy/context 文件拆分

落地范围：
```text
app/src/main/java/com/multiapp/app/container/ContainerActivity.kt
app/src/main/java/com/multiapp/app/container/ProxyActivityBase.kt
app/src/main/java/com/multiapp/app/container/ProxyActivityEvidence.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
app/src/test/java/com/multiapp/app/container/ProxyActivityEvidenceTest.kt
```

调整：
```text
ContainerActivity
  -> $instanceId.launch.properties

ProxyActivityBase
  -> $instanceId.activity-proxy.properties
  -> status=PROXY_ACTIVITY_BASE_ONCREATE

VirtualInstrumentation activity context injection
  -> $instanceId.activity-context.properties

VirtualInstrumentation substitution failure
  -> $instanceId.activity-instrumentation.properties
```

边界：`PROXY_ACTIVITY_BASE_ONCREATE` 不是 guest Activity 成功替换的主证据。成功替换路径仍应以后续 `GUEST_ACTIVITY_SUBSTITUTED` / context injection / UI evidence 为准。

### 24.3 Provider 组：StubContentProvider evidence 落盘

落地范围：
```text
app/src/main/java/com/multiapp/app/container/StubContentProvider.kt
```

新增能力：
```text
query/getType/insert/delete/update/call dispatch 后写入：
$instanceId.provider-proxy.properties
```

字段：
```text
status
stage=PROVIDER_PROXY
operationName
uri
instanceId
guestAuthority
providerClassName
proxyAuthority
evidenceOperation
evidenceSuccess
reason
cached
detail
```

`call()` 返回 Bundle 也补充：
```text
evidenceOperation
evidenceSuccess
evidenceReason
proxyAuthority
```

边界：没有 `instanceId` 的非法 proxy URI 无法归档到实例 evidence 文件，只返回 Bundle/log；默认 Provider hook 仍关闭，普通 baseline 不自动改写系统 ContentResolver 路径。

### 24.4 Service 组：StubService evidence 字段补齐

落地范围：
```text
app/src/main/java/com/multiapp/app/container/StubService.kt
```

新增字段：
```text
foreground
stubStopped=true
guestRecordCached
lifecycleSuccess
startCommandResult
errorClassName
errorMessage
```

固定语义：`stubStopped=true` 只表示宿主 `StubService.stopSelf(startId)` 已执行，不表示 guest Service record 已销毁。guest lifecycle 仍以 `VirtualServiceLifecycleEvidence.event` 和 stopService dispatch 结果为准。

### 24.5 验证

命令：
```powershell
.\gradlew.bat --no-parallel :app:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
.\gradlew.bat --no-parallel :core:model:testDebugUnitTest :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

下一批建议：
```text
Broadcast: sendBroadcast/registerReceiver fallback evidence 写入 $instanceId.broadcast.properties
Provider: 收口 VirtualProviderManager.openProvider 与 dispatcher/runtime 的语义冲突
Verification: hosted-container-baseline 脚本汇总组件 evidence 文件，要求 instrumentation XML tests > 0
Activity: 补 GUEST_ACTIVITY_SUBSTITUTED 独立文件或与 activity-instrumentation 文件合并为稳定状态机
```

## 25. 2026-06-28 三角色执行落地批次 10

本批继续按“先 evidence 闭环，再扩大虚拟化面”的原则推进。目标是补齐 Broadcast 宿主可观测出口、收口 ProviderManager 与 dispatcher/runtime 的语义冲突，并让手工 baseline 脚本能抓到组件级 evidence 文件。

### 25.1 Broadcast 组：全局 recorder 与宿主文件出口

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualBroadcastManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualReceiverRuntime.kt
app/src/main/java/com/multiapp/app/container/ContainerBroadcastEvidenceRecorder.kt
app/src/main/java/com/multiapp/app/MultiAppApplication.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualBroadcastManagerTest.kt
app/src/test/java/com/multiapp/app/container/ContainerBroadcastEvidenceRecorderTest.kt
```

新增能力：
```text
VirtualBroadcastRecorders.install(recorder)
GlobalVirtualBroadcastRecorder
ContainerBroadcastEvidenceRecorder
```

文件输出：
```text
$instanceId.broadcast.properties
```

字段：
```text
status
stage=BROADCAST_RUNTIME
instanceId
receiverClassName
action
result
```

固定语义：默认 core 仍是 no-op recorder；app 启动时安装文件 recorder。显式 receiver、动态 receiver、receiver not found、no package snapshot 等带 `instanceId` 的记录可以进入 evidence 文件。没有 `instanceId` 的隐式 unsupported 广播仍只保留内存/测试结果，不强行写入某个实例文件。

边界：不新增 `StubReceiver`，不实现有序广播，不实现跨进程 receiver，不开启全局 AMS hook。

### 25.2 Provider 组：openProvider 语义收口

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualProviderEvidence.kt
core/loader/src/test/java/com/multiapp/core/loader/VirtualProviderManagerTest.kt
```

调整：
```text
known authority
  -> VirtualProviderOpenResult.Resolved
  -> VirtualProviderEvidence.success=true
  -> reason=null

missing authority
  -> VirtualProviderOpenResult.NotFound
  -> VirtualProviderEvidence.success=false
  -> reason=PROVIDER_NOT_FOUND
```

固定语义：`VirtualProviderManager` 只负责 authority resolve / rewrite / route plan，不再把已知 provider 固定标记为 `PROVIDER_LIFECYCLE_NOT_IMPLEMENTED`。真正 provider create、attach、runtime incomplete、not found 等状态由 `VirtualProviderDispatcher` / `VirtualProviderRuntime` 的 result 和 evidence 表达。

边界：这不是声明 Provider 生命周期完整可用；仍不默认启用 Provider hook，也不接系统 ContentResolver 全局 hook。

### 25.3 验证组：manual baseline 汇总组件 evidence

落地范围：
```text
tools/hosted-container-baseline/capture-manual-hosted-launch.ps1
```

调整：
```text
-InstanceId inst-001
  -> 采集 files/hosted_launch_evidence/inst-001*.properties

未传 -InstanceId
  -> 继续采集 hosted_launch_evidence 目录下全部 evidence 文件
```

目的：第 24 节已经把 `$instanceId.properties` 拆成组件文件；本批让手工真机采集脚本同步适配，否则会漏掉 launch/activity/provider/service/broadcast 关键 evidence。

### 25.4 验证

命令：
```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
.\gradlew.bat --no-parallel :app:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

下一批建议：
```text
Verification: 让 run-minimal-hosted-baseline.ps1 同步归档 hosted_launch_evidence，并检查 instrumentation XML tests > 0
Activity: 补 GUEST_ACTIVITY_SUBSTITUTED 独立 evidence 文件，避免仅靠 context injection 推断替换成功
Provider: 增加 dispatcher 状态矩阵文档和 tests，明确 Resolved != ProviderReady
Storage: 将 database/files/shared_prefs 证据纳入 baseline summary
```

## 26. 2026-06-28 三角色执行落地批次 11

本批处理验证组指出的脚本假阳性风险：`run-minimal-hosted-baseline.ps1` 过去只看 Gradle exit code，不能证明 `HostedContainerMinimalBaselineTest` 真正执行。当前仓库历史 XML 曾出现 `tests=0 failures=0 errors=0`，这类结果不能作为 baseline PASS。

### 26.1 Verification 组：instrumentation XML 门禁

落地范围：
```text
tools/hosted-container-baseline/run-minimal-hosted-baseline.ps1
```

新增流程：
```text
Run hosted container minimal baseline instrumentation
  -> Copy app/build/outputs/androidTest-results/connected/debug 到 evidenceDir/androidTest-results
  -> 查找最新 TEST-*.xml
  -> 解析 tests / failures / errors / skipped
  -> tests <= 0 直接 FAIL
  -> failures > 0 或 errors > 0 直接 FAIL
```

summary 新增字段：
```text
instrumentationXml
instrumentationTests
instrumentationFailures
instrumentationErrors
instrumentationSkipped
```

固定语义：`status=PASS` 现在至少要求 instrumentation XML 存在且 `tests > 0`，不再允许“Gradle 成功但测试 0 条”的假阳性。

### 26.2 Verification 组：hosted evidence 归档

同一脚本现在会在 instrumentation 后采集：
```text
files/hosted_launch_evidence/*
```

输出到：
```text
hosted-launch-evidence.txt
```

目的：适配第 24/25 节拆分出的组件 evidence 文件：
```text
$instanceId.launch.properties
$instanceId.activity-proxy.properties
$instanceId.activity-context.properties
$instanceId.activity-instrumentation.properties
$instanceId.activity-remap.properties
$instanceId.provider-proxy.properties
$instanceId.service-proxy.properties
$instanceId.broadcast.properties
```

失败路径也会尽量采集 logcat、hosted evidence 和 XML summary，便于定位“没有启动、启动了但无 evidence、测试没有执行、测试执行失败”四类问题。

### 26.3 本地验证

PowerShell 语法检查：
```powershell
[System.Management.Automation.Language.Parser]::ParseFile(...)
```

结果：
```text
PowerShell parse OK
```

历史 XML 读取：
```text
TEST-2509FPN0BC - 16-_app-.xml
tests=0
failures=0
errors=0
skipped=0
```

结论：该历史结果会被新门禁拦截，不能再作为 baseline PASS。

离线 XML 门禁验证：
```text
zeroTests=0 rejected
oneTests=1 accepted
```

补充检查：
```powershell
git diff --check -- tools/hosted-container-baseline/run-minimal-hosted-baseline.ps1
```

结果：无 whitespace error。

### 26.4 边界

本批没有跑真机完整 baseline，也不声明 minimal hosted baseline 已通过。它只修复验证脚本，让后续真机验证结果更可信。下一步需要在线设备执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\hosted-container-baseline\run-minimal-hosted-baseline.ps1 -Serial <serial> -OutputDir .tmp
```

验收必须看到：
```text
status=PASS
instrumentationTests > 0
instrumentationFailures=0
instrumentationErrors=0
hosted-launch-evidence.txt 包含至少 launch/activity 组件 evidence
```

## 27. 2026-06-28 三角色执行落地批次 12

本批修复 Activity evidence 文件语义错位。第 24 节定义了组件级 evidence 文件，但 `VirtualInstrumentation` 实现中存在命名交叉：`GUEST_ACTIVITY_SUBSTITUTED` 写到了 `activity-context.properties`，context injection 写到了 `activity-instrumentation.properties`，substitution failure 仍写旧 `$instanceId.properties`。这会导致 baseline report 无法可靠判断“guest Activity 是否真的被 Instrumentation 替换”。

### 27.1 Activity 组：evidence 文件命名收口

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/HostedActivityEvidenceFiles.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
core/loader/src/test/java/com/multiapp/core/loader/HostedActivityEvidenceFilesTest.kt
```

固定命名：
```text
GUEST_ACTIVITY_SUBSTITUTED
  -> $instanceId.activity-instrumentation.properties

ACTIVITY_INSTRUMENTATION failure
  -> $instanceId.activity-instrumentation.properties

GUEST_ACTIVITY_CONTEXT_INJECTED
  -> $instanceId.activity-context.properties

GUEST_ACTIVITY_REMAP / SKIPPED / FAILED
  -> $instanceId.activity-remap.properties
```

新增 `HostedActivityEvidenceFiles` 集中定义文件名，避免后续再把 context / instrumentation / remap 写反。

### 27.2 验证

命令：
```powershell
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
```

补充检查：
```powershell
rg -n "\$instanceId\.properties|activity-context\.properties|activity-instrumentation\.properties|activity-remap\.properties|HostedActivityEvidenceFiles" core/loader/src/main/java/com/multiapp/core/loader core/loader/src/test/java/com/multiapp/core/loader
```

结果：`VirtualInstrumentation` 已全部通过 `HostedActivityEvidenceFiles` 写 activity evidence 文件；未发现旧 `$instanceId.properties` 写入残留。

### 27.3 边界

本批只修复 evidence 可观测语义，不实现完整 Activity lifecycle/task/back-stack。下一批仍应继续推进：
```text
Activity: onNewIntent / activity result / finish evidence 与 VirtualActivityStack 对齐
Verification: baseline summary 读取 activity-instrumentation/context/remap 并给出 verdict
Storage: database/files/shared_prefs evidence 纳入 baseline summary
```

## 28. 2026-06-28 三角色执行落地批次 13

本批继续补验证闭环：第 27 节修正了 Activity evidence 文件语义，但 baseline 脚本仍只归档文件，不读取 verdict。现在 `run-minimal-hosted-baseline.ps1` 会解析 `hosted-launch-evidence.txt`，并对 guest Activity 是否真正替换、Context 是否注入给出明确结论。

### 28.1 Verification 组：Activity verdict

落地范围：
```text
tools/hosted-container-baseline/run-minimal-hosted-baseline.ps1
```

新增解析：
```text
Read-HostedEvidenceSections
Get-HostedEvidenceSectionBySuffix
Get-HostedActivityVerdict
```

PASS 条件：
```text
$instanceId.activity-instrumentation.properties
  status=GUEST_ACTIVITY_SUBSTITUTED

$instanceId.activity-context.properties
  status=GUEST_ACTIVITY_CONTEXT_INJECTED
  contextInjected=true
```

可选观测：
```text
$instanceId.activity-remap.properties
  status=GUEST_ACTIVITY_REMAP / GUEST_ACTIVITY_REMAP_SKIPPED / GUEST_ACTIVITY_REMAP_FAILED / MISSING
```

如果 Activity verdict 不是 `PASS`，脚本直接失败，避免出现“instrumentation 测试执行了，但 guest Activity 没有真正替换”的假阳性。

### 28.2 summary 新增字段

```text
activityVerdict
activityVerdictReason
activityInstrumentationStatus
activityContextStatus
activityContextInjected
activityRemapStatus
```

失败路径也会尽量写入这些字段，方便区分：
```text
missing GUEST_ACTIVITY_SUBSTITUTED
missing GUEST_ACTIVITY_CONTEXT_INJECTED with contextInjected=true
activity substituted and context injected
```

### 28.3 本地验证

PowerShell 语法检查：
```text
PowerShell parse OK
```

构造 evidence 样本验证：
```text
passVerdict=PASS
failVerdict=FAIL
```

补充检查：
```powershell
git diff --check -- tools/hosted-container-baseline/run-minimal-hosted-baseline.ps1
```

结果：无 whitespace error。

### 28.4 边界

本批没有跑真机，也不声明 minimal hosted baseline 已通过。它把真机 baseline 的验收门槛提高为：
```text
instrumentationTests > 0
instrumentationFailures=0
instrumentationErrors=0
activityVerdict=PASS
```

下一批建议：
```text
Baseline: 将 provider/service/broadcast/storage evidence 也汇总成 verdict
Activity: onNewIntent / activity result / finish evidence 与 VirtualActivityStack 对齐
Storage: database/files/shared_prefs evidence 纳入 baseline summary
```

## 29. 2026-06-28 三角色执行落地批次 14

本批把 baseline summary 从 Activity verdict 扩展到 provider / service / broadcast / storage。目标是让真机 baseline 报告能直接看出组件路径是否被实际触发，而不是只靠人工翻 `hosted-launch-evidence.txt` 和 logcat。

### 29.1 Verification 组：组件 verdict 汇总

落地范围：
```text
tools/hosted-container-baseline/run-minimal-hosted-baseline.ps1
```

新增解析：
```text
Get-HostedComponentVerdict
Get-StorageVerdictFromLogcat
```

provider verdict：
```text
$instanceId.provider-proxy.properties
  PASS: status=PROVIDER_CREATED / PROVIDER_CACHED
  FAIL: 其他已记录 status
  UNKNOWN: 文件缺失，说明 minimal baseline 未触发 provider path
```

service verdict：
```text
$instanceId.service-proxy.properties
  PASS: status=STARTED
  FAIL: 其他已记录 status
  UNKNOWN: 文件缺失，说明 minimal baseline 未触发 service path
```

broadcast verdict：
```text
$instanceId.broadcast.properties
  PASS: status=Delivered
  FAIL: 其他已记录 status
  UNKNOWN: 文件缺失，说明 minimal baseline 未触发 broadcast path
```

storage verdict 从 logcat 的 minimal fixture storage probe 解析：
```text
PASS:
  === storage probe ===
  prefs.launchCount:
  file.path:
  db.path:
  db.rows:
  且无 prefs failed / file failed / db failed

FAIL:
  storage probe 存在但包含 failed 或字段不完整

UNKNOWN:
  logcat 缺失或 storage probe log 缺失
```

### 29.2 summary 新增字段

```text
providerVerdict
providerVerdictReason
providerStatus
serviceVerdict
serviceVerdictReason
serviceStatus
broadcastVerdict
broadcastVerdictReason
broadcastStatus
storageVerdict
storageVerdictReason
```

失败策略：
```text
Activity verdict != PASS -> FAIL
Storage verdict == FAIL -> FAIL
Provider/Service/Broadcast UNKNOWN -> 不失败，作为未触发路径记录
Provider/Service/Broadcast FAIL -> 当前作为 summary 记录，后续在专项 baseline 中可升级为硬门禁
```

### 29.3 本地验证

PowerShell 语法检查：
```text
PowerShell parse OK
```

构造 evidence/logcat 样本验证：
```text
provider=PASS
service=PASS
broadcast=PASS
missing=UNKNOWN
storagePass=PASS
storageFail=FAIL
```

补充检查：
```powershell
git diff --check -- tools/hosted-container-baseline/run-minimal-hosted-baseline.ps1
```

结果：无 whitespace error。

### 29.4 边界

本批只增强 baseline 报告，不扩大运行时能力。Provider/Service/Broadcast 在 minimal baseline 中未触发时仍是 `UNKNOWN`，不能据此证明这些组件完整可用。下一批建议：
```text
Fixture: minimal-app 主动触发 service/broadcast/provider 路径，让 verdict 从 UNKNOWN 变成可验证 PASS/FAIL
Storage: 将 dataRoot 路径、prefs 文件、database 文件存在性归档到 hosted evidence
Activity: onNewIntent / activity result / finish evidence 与 VirtualActivityStack 对齐
```

## 30. 2026-06-28 三角色执行落地批次 15

本批把 minimal fixture 从“只启动 Activity + storage probe”升级为主动触发 Service / Broadcast / Provider 路径。这样下一次真机 baseline 不再只能得到 `provider/service/broadcast=UNKNOWN`，而是能给出真实 `PASS/FAIL`。

### 30.1 Fixture 组：新增三类组件

落地范围：
```text
test-fixtures/minimal-app/src/main/AndroidManifest.xml
test-fixtures/minimal-app/src/main/java/com/test/minimal/ProbeService.java
test-fixtures/minimal-app/src/main/java/com/test/minimal/ProbeReceiver.java
test-fixtures/minimal-app/src/main/java/com/test/minimal/ProbeProvider.java
test-fixtures/minimal-app/src/main/java/com/test/minimal/MainActivity.java
```

新增 manifest 声明：
```text
service  -> com.test.minimal.ProbeService
receiver -> com.test.minimal.ProbeReceiver
provider -> com.test.minimal.ProbeProvider, authority=com.test.minimal.probe
```

MainActivity 新增 `component probe`：
```text
startService(ComponentName(getPackageName(), ProbeService))
sendBroadcast(Intent(ACTION_PROBE_BROADCAST).setComponent(ProbeReceiver))
query host StubContentProvider URI -> guest ProbeProvider
```

Provider stub URI 依赖：
```text
multiapp.instanceId
multiapp.hostPackageName
multiapp_instanceId
multiapp_guestAuthority=com.test.minimal.probe
```

### 30.2 Runtime 组：host package extra 透传

落地范围：
```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualActivityManager.kt
core/loader/src/main/java/com/multiapp/core/loader/VirtualInstrumentation.kt
```

新增 extra：
```text
multiapp.hostPackageName
```

用途：guest fixture 运行时可以构造 host-declared provider stub authority：
```text
<hostPackageName>.multiapp.provider.stub
```

边界：这不是默认开启 Provider hook。fixture 是显式访问 host stub provider，用于验证 dispatcher/runtime 和 evidence 链路。

### 30.3 预期 evidence 变化

真机 baseline 成功后应从 `UNKNOWN` 推进到：
```text
providerVerdict=PASS
providerStatus=PROVIDER_CREATED 或 PROVIDER_CACHED

serviceVerdict=PASS
serviceStatus=STARTED

broadcastVerdict=PASS
broadcastStatus=Delivered
```

如果这些仍为 `UNKNOWN`，说明 MainActivity component probe 没有执行到对应路径，或 runtime remap/dispatch/evidence 链路没有生效。

### 30.4 验证

命令：
```powershell
.\gradlew.bat --no-parallel :test-fixtures:minimal-app:assembleDebug "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
.\gradlew.bat --no-parallel :core:loader:testDebugUnitTest :app:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
.\gradlew.bat --no-parallel :core:manifest:testDebugUnitTest :core:loader:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain --no-build-cache --no-daemon
```

结果：
```text
BUILD SUCCESSFUL
BUILD SUCCESSFUL
BUILD SUCCESSFUL
```

### 30.5 边界

本批没有跑真机 baseline，因此还不能声明 provider/service/broadcast verdict 已经 PASS。下一步需要在线设备执行：
```powershell
powershell -ExecutionPolicy Bypass -File .\tools\hosted-container-baseline\run-minimal-hosted-baseline.ps1 -Serial <serial> -OutputDir .tmp
```

验收重点：
```text
activityVerdict=PASS
storageVerdict=PASS
providerVerdict=PASS
serviceVerdict=PASS
broadcastVerdict=PASS
```

## 31. 2026-06-28 三角色执行落地批次 16

本批增强 storage baseline 证据。第 29 节的 `storageVerdict` 只解析 logcat 中的 storage probe 文本，能证明 API 调用没有抛异常，但不能证明文件确实落在容器实例 dataRoot 下。本批补充 `storageFilesVerdict`，通过 `run-as com.multiapp.app` 检查实例 dataRoot 内的实际文件。

### 31.1 Verification 组：storage 文件存在性检查

落地范围：
```text
tools/hosted-container-baseline/run-minimal-hosted-baseline.ps1
```

新增函数：
```text
Test-HostedAppFileExists
Get-StorageFileVerdictFromDevice
```

数据来源：
```text
$instanceId.activity-context.properties
  dataDir=<host files>/instance_data/<instanceId>
```

检查路径：
```text
<dataDir>/files/probe.txt
<dataDir>/shared_prefs/probe.xml
<dataDir>/databases/probe.db
```

判定：
```text
PASS    -> 三个文件都存在
FAIL    -> dataDir 存在但任一文件缺失
UNKNOWN -> activity-context evidence 缺失或 dataDir 缺失
```

### 31.2 summary 新增字段

```text
storageFilesVerdict
storageFilesVerdictReason
storageDataDir
storageProbeFileExists
storageSharedPrefsExists
storageDatabaseExists
```

失败策略：
```text
storageVerdict == FAIL -> FAIL
storageFilesVerdict == FAIL -> FAIL
storageFilesVerdict == UNKNOWN -> 记录，不单独失败
```

`UNKNOWN` 不单独失败是因为如果 Activity/context evidence 已经失败，脚本会先由 `activityVerdict` 拦截；如果真机权限或采集阶段异常，summary 中仍保留原因供定位。

### 31.3 本地验证

PowerShell 语法检查：
```text
PowerShell parse OK
```

构造 activity-context evidence 验证路径解析：
```text
dataDir=/data/user/0/com.multiapp.app/files/instance_data/inst-001
probe=/data/user/0/com.multiapp.app/files/instance_data/inst-001/files/probe.txt
prefs=/data/user/0/com.multiapp.app/files/instance_data/inst-001/shared_prefs/probe.xml
db=/data/user/0/com.multiapp.app/files/instance_data/inst-001/databases/probe.db
```

补充检查：
```powershell
git diff --check -- tools/hosted-container-baseline/run-minimal-hosted-baseline.ps1
```

结果：无 whitespace error。

### 31.4 边界

本批没有跑真机，因此没有证明上述三个文件已经真实存在。下一次真机 baseline 的 storage 验收应同时满足：
```text
storageVerdict=PASS
storageFilesVerdict=PASS
storageProbeFileExists=true
storageSharedPrefsExists=true
storageDatabaseExists=true
```
