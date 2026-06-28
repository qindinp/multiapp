# MultiApp v2 Hosted Container 多角色审核与整改文件

日期：2026-06-27

分支：`container-runtime-refactor`

审核范围：v2 直接最终态路线，即 `VirtualInstall / VirtualInstance` 作为唯一事实源，
v2 新实例从 MultiApp hosted container 启动，不再生成独立 Stub APK。

关联整改文件：

- `docs/container-runtime-refactor/v2-open-source-alignment-remediation-2026-06-27.md`：对照 VirtualApp / BlackBox / DroidPlugin / SPatch 的公开路线，按系统架构组、Android Runtime 组、测试验证组拆解后续整改任务。

## 1. 最终审核结论

当前不能判定为 “v2 Hosted Container 已完成”。更准确的状态是：

```text
已完成：VirtualInstall / VirtualInstance 基础模型、部分持久化、ContainerActivity 入口、
        HostedRuntimeBootstrap 的 instance -> install -> apk -> classloader -> Application 尝试链路、
        部分 JVM contract tests。

未完成：从 MultiApp 内真实启动 minimal test app 首屏、guest Activity 承载、guest resources/assets、
        virtual PackageManager、nativeLibraryDir、真实双实例真机 baseline、QQ 阅读 register-natives-only
        真机诊断闭环、protected baseline hook-free 证明。
```

因此本轮审核结论为：**No-Go，需要整改后再声明 Step 4-7 完成**。

### 1.1 2026-06-27 真机补充结论：P0-2 路线必须改判

设备：`Xiaomi 2509FPN0BC / Android 16 / HyperOS V816`

真实 MultiApp 入口验证已经证明，当前 v2 链路可以推进到：

```text
MultiApp 内启动 ContainerActivity
-> 读取 VirtualInstanceRecord
-> 读取 InstallRecord
-> 加载只读 origin APK artifact
-> 创建 guest ClassLoader
-> 创建并调用 guest Application.onCreate()
-> 解析 guest launcher Activity: com.test.minimal.MainActivity
```

但在 `DefaultVirtualActivityController` 的手工 Activity 承载阶段失败：

```text
evidenceDir=.tmp/manual-hosted-launch-20260627-155354
status=FAIL
stage=LAUNCHER_ACTIVITY
detail=guest launch failed

Caused by:
NullPointerException: ActivityInfo.parentActivityName on null mActivityInfo
```

临时补 `mActivityInfo` 后，失败继续前进到：

```text
evidenceDir=.tmp/manual-hosted-launch-20260627-160101
status=FAIL
stage=LAUNCHER_ACTIVITY
detail=No activity

IllegalStateException: No activity
  at android.app.FragmentManagerImpl.moveToState
  at android.app.Activity.onCreate
  at com.test.minimal.MainActivity.onCreate
```

这证明 **`ClassLoader.loadClass() -> Activity.newInstance() -> attachBaseContext() -> onCreate()`
不是可继续作为最终态的技术路线**。Android Activity 不能作为普通 Java 对象承载；系统正常
`ActivityThread.performLaunchActivity()` 会注入 `ActivityInfo`、`Instrumentation`、`IBinder token`、
`ActivityThread`、`PhoneWindow`、`WindowManager`、`LoadedApk`、`Resources`、`FragmentController host`、
`Configuration`、`Intent` 等运行时状态。逐个反射补字段会形成无底洞，并且 Android 版本兼容性不可控。

因此，P0-2 的整改方向从“生产级 `VirtualActivityController` 手工承载 Activity”改判为：

```text
Proxy Activity + Instrumentation/ActivityThread/AMS/PMS 虚拟化
```

`DefaultVirtualActivityController` 后续只能作为 diagnostic/negative evidence 工具保留，不能作为 v2
最终运行时内核。

## 2. 多角色审核输入

| 角色 | 结论 |
| --- | --- |
| 架构审查 | 创建链路没有真正接入 `VirtualInstallService`；Hosted 启动没有进入 guest launcher Activity。 |
| Android Runtime / 容器审查 | 当前只到 guest `Application` 实例化尝试；缺 Activity 承载、Resources、PackageManager、native libs。 |
| 测试验证审查 | 当前测试多为 JVM contract / fake evidence；缺 `ContainerActivity` 真机/仪器化启动、真实 APK 双实例、QQ 阅读真实 diagnostics。 |
| 安全 / 加固兼容审查 | protected baseline 仍可能被 native hook 初始化污染；register-natives diagnostics 含 business wrapper 风险。 |

## 3. P0 必须整改

### P0-1：v2 创建链路没有创建或导入 `InstallRecord`

问题：UI 创建路径只创建 `VirtualInstanceRecord`，但 `HostedRuntimeBootstrap` 启动时必须读取
`InstallRecord`。这会导致真实 UI 路径创建出来的实例不可启动。

证据：

```text
feature/launcher/src/main/java/com/multiapp/feature/launcher/LauncherViewModel.kt:68
  instanceManager.createInstance(...)

core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt:106
  Stage 2: Load install record

core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt:113-120
  Install record missing -> PACKAGE_METADATA failed

core/model/src/main/java/com/multiapp/core/model/installer/InstalledPackageImporter.kt:18
  importer exists, but production creation path does not call it
```

整改：

1. 增加生产级 `VirtualInstallService`，封装 `InstalledPackageImporter`、artifact path、
   `InstallRecordStore`。
2. 创建实例入口改成：

```text
ensure/import InstallRecord
-> create VirtualInstanceRecord
-> launch by instanceId
```

3. `InstanceManager.createInstance()` 至少校验对应 `InstallRecord` 存在，避免生成不可启动实例。
4. 增加端到端测试：从已安装 app metadata 创建实例后，`HostedRuntimeBootstrap.run(instanceId)`
   不应停在 `PACKAGE_METADATA`。

验收：

```text
Launcher/AppManager 创建的新实例在 filesDir/installs 下存在对应 InstallRecord。
HostedRuntimeBootstrap 能读取 install record 并进入 ORIGIN_APK stage。
```

### P0-2：Hosted Container 没有通过系统生命周期启动 guest launcher Activity

问题：早期 `ContainerActivity` 只执行 `bootstrap.run(instanceId)`，成功后记录 `guestApplication` 和
`guestClassLoader`，没有解析 launcher Activity。后续增加的 `DefaultVirtualActivityController` 虽然能
解析 launcher 并尝试反射 `onCreate()`，但真机证明手工承载 Activity 不成立：它绕过了
`ActivityThread.performLaunchActivity()` 和系统 `attach()`，导致 Activity 内部状态不完整。

证据：

```text
app/src/main/java/com/multiapp/app/container/ContainerActivity.kt:75-80
  creates HostedRuntimeBootstrap and runs it

app/src/main/java/com/multiapp/app/container/ContainerActivity.kt:93-99
  logs guest Application / launch complete only

core/model/src/main/java/com/multiapp/core/model/virtual/VirtualActivityController.kt:18-23
  skeleton interface only

test-fixtures/minimal-app/src/main/AndroidManifest.xml:10
  minimal app launcher is .MainActivity

test-fixtures/minimal-app/src/main/java/com/test/minimal/MainActivity.java:17
  visible UI is created in MainActivity.onCreate()
```

整改：

1. 废弃“生产级手工 `VirtualActivityController`”作为最终路线；它只能保留为 diagnostic/negative
   evidence，证明哪些系统字段缺失。
2. 新增 `ProxyActivity` / `ProxyActivityRegistry`。所有 guest Activity 启动都先映射到宿主
   Manifest 中真实声明的 Proxy Activity，由系统正常创建宿主 Activity 记录、Window、token 和生命周期。
3. 新增 `VirtualActivityManager` 保存：

```text
instanceId
originPackageName
guestActivityClassName
proxyActivityClassName
launchIntent
task/process slot
```

4. 新增 `VirtualInstrumentation` / `ActivityThreadCompat`：

```text
hook Instrumentation.execStartActivity / newActivity / callActivityOnCreate
hook ActivityThread.mInstrumentation
hook ActivityThread.mPackages / LoadedApk / ClassLoader / Resources
```

5. 新增 `VirtualPackageManager` / `VirtualLoadedApk` / `VirtualResourcesManager`，保证 guest Activity
   在系统生命周期中看到 guest package、resources、classloader、applicationInfo、nativeLibraryDir。
6. `ContainerActivity` 只负责根据 `instanceId` 调度 `VirtualActivityManager.launchGuestLauncher()`，
   不再直接 `newInstance()` guest Activity。

验收：

```text
MultiApp 内启动 minimal test app instance 后，系统实际 resume 的是 ProxyActivity，
但 guest MainActivity 代码在系统 Activity 生命周期中执行并显示首屏。
失败时输出明确 ACTIVITY_PROXY / INSTRUMENTATION / LOADED_APK stage failed，
而不是空白页或 bootstrap success。
```

### P0-3：guest `Application.onCreate()` 没有调用，且失败仍可能被判成功

问题：`HostedRuntimeBootstrap` 反射调用 `attachBaseContext` 后返回成功，没有调用
`Application.onCreate()`；如果 Application stage 失败，最终仍 `success=true`。

证据：

```text
core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt:206-230
  constructs Application and invokes attachBaseContext

core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt:244-255
  returns success=true after attach only

core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt:256-286
  catches Application failure, adds failed stage, then returns success=true

test-fixtures/minimal-app/src/main/java/com/test/minimal/MinimalApp.java:13
  minimal app records initialization in Application.onCreate()
```

整改：

1. `attachBaseContext` 成功后调用 `Instrumentation.callApplicationOnCreate()` 或等价流程。
2. Application stage 失败默认应 `success=false`。
3. 如需诊断模式继续执行，必须有显式 mode，不得在默认 hosted launch 中吞失败。

验收：

```text
Application.onCreate 被调用一次。
Application failure -> HostedBootstrapResult.success=false。
ContainerActivity 失败时 finish 或显示明确错误 UI。
```

### P0-4：guest Resources / Assets / Theme 未虚拟化

问题：`VirtualContextWrapper` 没有覆盖 `getResources()`、`getAssets()`、`getTheme()`、
`createPackageContext()`；真实 APK 使用 `R.layout`、`R.string`、主题或 asset 时会失败。

证据：

```text
core/loader/src/main/java/com/multiapp/core/loader/VirtualContextWrapper.kt:23-36
  only overrides packageName, ApplicationInfo, ClassLoader

core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt:219
  sourceDir is set, but no Resources/AssetManager is created from the APK
```

整改：

1. 为 guest APK 创建独立 `AssetManager` / `Resources` / `Theme`。
2. `VirtualContextFactory` 必须接管 Context 创建，不再在 bootstrap 中直接 new
   `VirtualContextWrapper`。
3. Activity 与 Application 使用同一份 guest resources。

验收：

```text
minimal test app 可以读取自身 string/layout/resource。
真实 APK 的 getResources()/getAssets() 不返回 MultiApp host resources。
```

### P0-5：protected baseline 仍被 native hook 初始化污染

问题：默认 legacy loader 路径存在无条件 `initNativeHooks()`；native 层会安装 inline hooks。
这与 protected baseline hook-free 目标冲突。

证据：

```text
core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt:789
  NativeHookBridge.getInstance().initNativeHooks()

core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt:843
  initNativeHooks() -> nativeInit()

core/hook/src/main/cpp/native-hook.cpp:780-797
  nativeInit installs inline hooks / shadowhook hooks

core/model/src/main/java/com/multiapp/core/model/CompatibilityMode.kt:14
  requiresHookFreeRuntime = true
```

整改：

1. `initNativeHooks()` 必须进入 `NativeHookPolicy` gate。
2. baseline / protected baseline 下不得安装 shadowhook、LSPlant、method replacement、business stubs。
3. 如果需要 `/proc/maps` 或 native diagnostics，拆为显式 diagnostic capability。

验收：

```text
NORMAL/protected baseline logs show native base hooks skipped by policy。
单测确认 baseline 不调用 initNativeHooks/nativeInitLsplant/register business wrappers。
```

## 4. P1 重要整改

### P1-1：register-natives-only diagnostics 会修改业务 native 方法

问题：`REGISTER_NATIVES_LOGGING` 安装的 native hook 不只是记录，还会替换 `YWLoginManager`、
`Fock` 等业务 native 方法指针。

证据：

```text
core/hook/src/main/java/com/multiapp/core/hook/NativeHookPolicy.kt:104
  diagnostic() enables registerNativesLogger

core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt:963
  installs register natives logger by gate

core/hook/src/main/cpp/native-hook.cpp:2623
  hooked_RegisterNatives

core/hook/src/main/cpp/native-hook.cpp:2670-2711
  replaces fnPtr with wrapped_ywlogin_* / wrapped_fock_*
```

整改：

1. 拆分能力：

```text
REGISTER_NATIVES_OBSERVE_ONLY
BUSINESS_NATIVE_WRAPPERS
```

2. diagnostics 只复制并记录 `JNINativeMethod` 元数据，不修改 `methodsToRegister`。
3. 业务 wrapper 必须挂到 `BUSINESS_NATIVE_STUBS` 或单独 compatibility profile。

### P1-2：NativeDiagnosticsProfile verdict 不闭环

问题：`NativeDiagnosticsProfile` 声明了 `FIND_CLASS_WRONG_CLASSLOADER`、
`NATIVE_NAMESPACE_MISMATCH`、`SHELL_DETECTED_CONTAINER` 等 verdict，但没有可达判断；
Hosted bootstrap 生成的 evidence key 也大多不被 profile 消费。

证据：

```text
core/hook/src/main/java/com/multiapp/core/hook/NativeDiagnosticsProfile.kt:48-55
  verdict enum includes several unreachable states

core/hook/src/main/java/com/multiapp/core/hook/NativeDiagnosticsProfile.kt:90
  consumes jni_onload_executed/register_natives_executed/register_natives_class/original_shell_path/fallback_registered

core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt:340-390
  emits classloader_created/application_created/interface20_error/origin_apk_path
```

整改：

1. 接入真实 native report：`JNI_OnLoad`、`FindClass` classloader、`RegisterNatives` class、
   nativeLoad、library path、namespace、`/proc/<pid>/maps`。
2. 缺少必需观测点时返回 `INSUFFICIENT_EVIDENCE`，不能把“没采集到”判成“没执行”。
3. 为每个 verdict 补可达分支和测试。

### P1-3：native library 目录未接入 hosted bootstrap

问题：`HostedRuntimeBootstrap` 创建 ClassLoader 时传入 `nativeLibDir=null`，`VirtualContextWrapper`
也设置 `nativeLibraryDir=null`。带 JNI 的真实 APK 会失败。

证据：

```text
core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt:172
  classLoaderFactory(originApkPath, null)

core/loader/src/main/java/com/multiapp/core/loader/HostedRuntimeBootstrap.kt:220
  nativeLibraryDir = null

core/model/src/main/java/com/multiapp/core/model/installer/InstallRecord.kt:22
  InstallRecord has nativeLibraries / abiList
```

整改：

1. 安装导入阶段按 ABI 解压 `lib/<abi>/*.so` 到 artifact 或 instance lib dir。
2. `PathClassLoader` / `DexClassLoader` 使用 resolved native lib dir。
3. `ApplicationInfo.nativeLibraryDir` 指向该目录。

### P1-4：创建阶段和运行阶段依赖/目录不一致

问题：`AppModule` 注入的 `DefaultInstanceManager` 使用 `filesDir/instance_data`，
`ContainerActivity` 手工 new 的 manager 使用 `filesDir/data`。依赖构造分散，后续数据隔离和删除行为会漂移。

证据：

```text
app/src/main/java/com/multiapp/app/AppModule.kt:49-51
  dataRootBase = filesDir/instance_data

app/src/main/java/com/multiapp/app/container/ContainerActivity.kt:72
  DefaultInstanceManager(instanceStore, getDataRootDir())

app/src/main/java/com/multiapp/app/container/ContainerActivity.kt:113
  getDataRootDir() = filesDir/data
```

整改：

1. 增加 `ContainerRuntimeDependencies` 或复用 DI provider。
2. 数据根目录常量只保留一份。
3. 禁止 `ContainerActivity` 自己手工拼 store/manager。

### P1-5：测试覆盖存在假阳性

问题：Step 4-7 的测试主要是 JVM fake / interface contract / synthetic evidence，不是真实启动。

证据：

```text
app/src/test/java/com/multiapp/app/container/ContainerActivityTest.kt:8-13
  Pure JVM tests; only tests constants

core/loader/src/test/java/com/multiapp/core/loader/DualInstanceBaselineTest.kt:25
  Pure JVM tests

core/loader/src/test/java/com/multiapp/core/loader/DualInstanceBaselineTest.kt:52
  fake apk content

core/loader/src/test/java/com/multiapp/core/loader/HostedRuntimeBootstrapTest.kt:484
  NativeDiagnosticsProfile integration section uses synthetic stage evidence
```

整改：

1. 将现有测试重命名/标注为 `contract`、`pure JVM`、`synthetic evidence`。
2. 新增 `androidTest`：`ContainerActivity` 真实启动、minimal APK 真实 bootstrap、双实例隔离。
3. 新增真机 evidence 包：设备/API/ABI、命令、logcat、BootstrapResult、diagnostics verdict。

## 5. P2 清理项

1. `VirtualPackageResolver` 仍是接口，没有运行时实现接入。
2. `VirtualContextFactory` 仍是接口，bootstrap 直接 new `VirtualContextWrapper`。
3. `ContainerActivity` 启动调用点硬编码 component 字符串，应统一走 app 层 launch gateway。
4. 默认 app 构建仍复制 `core:stub` 的 `loader.dex` 并依赖 `core:stub`，legacy 与 v2 边界不够硬。
5. `ApplicationInfo.packageName` 与 `Context.getPackageName()` 语义不一致，需要统一 origin/virtual 包名策略。
6. `getSharedPreferences()` 仍落到宿主 pref namespace，不是 `dataRoot/shared_prefs`。

## 6. 整改执行顺序

### R1：把 v2 创建链路接上 `VirtualInstallService`

```text
VirtualInstallService.ensureInstalled(packageName/sourceApk)
-> InstallRecord persisted
-> InstanceManager.createInstance(installId/packageName)
-> ContainerActivity launched by instanceId
```

验收：新实例不缺 install record，`HostedRuntimeBootstrap` 不停在 `PACKAGE_METADATA`。

### R2：打通 minimal test app 可见首屏

```text
Application attach + onCreate
-> guest resources/assets/theme
-> launcher activity resolve
-> VirtualActivityController launch/host MainActivity
```

验收：真机或 instrumentation 中 minimal app `MainActivity` 可见。

### R3：补 Virtual PMS / Storage / native libs 最小闭环

```text
VirtualPackageManager
VirtualContextFactory
Resources/AssetManager
nativeLibraryDir
shared_prefs/files/cache/database/dataDir
```

验收：minimal app 能读取 label/resources，双实例文件和 prefs 不串写。

### R4：清理 native diagnostics 与 hook gate

```text
REGISTER_NATIVES_OBSERVE_ONLY
BUSINESS_NATIVE_WRAPPERS
baseline hook-free gate tests
NativeDiagnosticsProfile verdict matrix
```

验收：protected baseline 不加载/安装 LSPlant、shadowhook、business wrappers；QQ 阅读诊断输出真实 evidence 或 `INSUFFICIENT_EVIDENCE`。

### R5：真机 evidence 和测试命名收敛

```text
ContainerActivity androidTest
minimal APK real fixture test
dual-instance device baseline
QQ Reader register-natives-only capture
test names distinguish contract vs acceptance
```

## 7. 验证记录

本轮执行过：

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest :core:hook:testDebugUnitTest :app:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain
```

观察结果：输出中出现 `BUILD SUCCESSFUL in 1m 20s`，但工具外层在 120s 超时返回 `Exit code: 124`。
因此只能记录为：**测试输出显示成功，但需要复跑获得干净 0 退出码**。

尚缺：

```text
app androidTest / instrumentation
minimal test app 真机启动截图或日志
双实例真机 baseline
QQ 阅读 register-natives-only 真机 evidence
```

## 8. 修正后的项目状态

| 项目 | 状态 |
| --- | --- |
| Step 1 文档冻结 | 完成 |
| Step 2 VirtualInstall / InstallRecordStore 基础 | 部分完成，缺生产级 `VirtualInstallService` 接入 |
| Step 3 InstanceManager / record persistence | 部分完成，需和 install record 事务打通 |
| Step 4 Hosted Container Launch MVP | 未完成，当前只到 Application 尝试，未启动 guest Activity |
| Step 5 Virtual PMS / Storage 最小闭环 | 未完成，接口/局部 wrapper 多，闭环不足 |
| Step 6 普通 App 双实例 baseline | 未完成，当前是 JVM fake baseline，不是真机/真实 APK |
| Step 7 QQ 阅读 register-natives-only diagnostics | 未完成，缺真实 native evidence，且 diagnostics hook 有业务 wrapper 污染风险 |

最终裁决：**v2 方向正确，但执行结果不能标记完成；必须按 R1-R5 整改后再进入 QQ 阅读兼容结论。**

## 9. 2026-06-27 负责人复审版：开源方案对标与团队分工整改

本节覆盖前文较早状态判断。后续真机 evidence 已经证明，v2 hosted container 不再停留在
`Application` 尝试阶段，而是已经跑通：

```text
ContainerActivity
-> ProxyActivity0/1
-> VirtualInstrumentation.newActivity()
-> guest ClassLoader 创建 guest Activity
-> HostedActivityContextInjector 注入 guest Context/Application/DataDir
-> com.test.minimal.MainActivity.onCreate() 可见首屏
-> prefs/files/database 双实例私有目录隔离
```

但三角色复审结论一致：当前仍不能称为 VirtualApp / BlackBox 级用户态容器。当前更准确状态是：

```text
已完成：hosted Activity prototype + Context/storage baseline
未完成：Virtual PMS / AMS / Provider / LoadedApk / native IO redirect / complete Activity stack
```

### 9.1 开源方案对标结论

本项目定位仍是开源学习与兼容壳研究，不能直接照搬第三方项目代码作为长期基线，但必须借鉴成熟方案的分层。

| 参考方案 | 可借鉴点 | 对 MultiApp 的结论 |
| --- | --- | --- |
| VirtualApp / VirtualXposed 系 | `VActivityManager`、`VPackageManager`、stub/proxy Activity、虚拟安装态、IO redirect、ActivityThread/LoadedApk 接入 | 普通 APK 主线必须向“虚拟系统服务层”收敛，不能继续只补 `ContextWrapper` |
| BlackBox 系 | `BPackageManagerService`、component resolver、package settings、intent resolution、持久化虚拟安装模型 | 需要建立 `VirtualPackageRegistry / VirtualPackageSnapshot`，作为 PMS/AMS/Provider 的唯一事实源 |
| DroidPlugin | AMS/PMS hook、四大组件代理、插件被 host/其他插件视为已安装 | 说明免安装容器的核心不是启动一个 Activity，而是让 framework 查询和组件调度都认为 guest 已安装 |
| SPatch / LSPatch 类 | stub APK 工程化、manifest 保真重写、loader dex、profile 化 hook | 适合作为 protected-app 兼容/诊断线，不应污染普通 APK hosted runtime 默认路径 |

公开资料对应到工程要求：

```text
VirtualApp/BlackBox: 虚拟 PM/AM/service registry 是核心，不是可选增强。
DroidPlugin: 四大组件无需在 host manifest 中逐个注册，依赖 proxy + framework hook。
Android PackageManager: getPackageInfo / getApplicationInfo / resolveActivity / resolveContentProvider 等查询是 App 判断安装态和组件可用性的基础面。
```

因此，继续只做 `VirtualContextWrapper.getApplicationInfo()`、`getPackageName()`、`filesDir` 这类局部补丁，会形成“看起来能跑 demo，复杂 App 立即露出 host/system 身份”的错误路线。

### 9.2 三角色团队复审结论

#### Framework / AMS 角色

结论：当前 Activity 代理还是 prototype。

已做到：

```text
MultiAppApplication 安装 VirtualInstrumentation
ProxyActivity0/1 被系统正常创建
VirtualInstrumentation.newActivity() 替换为 guest Activity
callActivityOnCreate() 前注入 Context/Application/Resources
```

关键差距：

```text
没有 execStartActivity 系列拦截
没有 IActivityManager / IActivityTaskManager 代理
没有完整 ActivityRecord / task / launchMode / result / PendingIntent 模型
没有 LoadedApk / ActivityThread.mPackages / ResourcesManager 成体系接入
ContainerActivity 与 VirtualInstrumentation 存在重复 bootstrap / Application 重建风险
```

整改方向：

```text
VirtualInstrumentation -> 增加 startActivity remap 能力
VirtualActivityManager -> 升级为 ActivityRecord/task/proxy slot 管理器
HostedRuntimeBootstrap -> 变成 per virtual process bindApplication 模型
新增 LoadedApkBridge -> 负责 ActivityThread/LoadedApk/ResourcesManager 一致性
```

#### PMS / PackageManager 角色

结论：当前不是 Virtual PMS，只是 manifest resolver + 局部 Context identity。

已做到：

```text
ManifestVirtualPackageResolver 可解析 package/application/launcher/components/permissions/label
VirtualContextWrapper 覆盖 getApplicationInfo / sourceDir / dataDir / label fallback
```

关键差距：

```text
没有 VirtualPackageRegistry
没有 PackageInfo/ApplicationInfo/ActivityInfo/ServiceInfo/ProviderInfo 快照
没有 getPackageManager() wrapper
没有 API 33+ PackageInfoFlags / ApplicationInfoFlags overload
没有 queryIntentActivities / resolveActivity / resolveContentProvider / checkPermission
Provider authorities / meta-data / process / launchMode / theme 等已解析信息未进入统一包模型
```

整改方向：

```text
VirtualPackageSnapshot = InstallRecord + VirtualInstanceRecord + ResolvedPackage + source/data/native/signature
VirtualPackageRegistry 按 instanceId 缓存 snapshot
VirtualPackageManagerWrapper 覆盖 guest self package 查询，host/system 查询 delegate
VirtualContextWrapper.getPackageManager() 返回 wrapper
```

#### Runtime / Storage / Protected-App 角色

结论：普通 APK baseline 可继续推进，但加固应用仍是 profile 化专项 PoC，不是通用兼容层。

已做到：

```text
Context API 级 files/shared_prefs/database/external_files 分实例隔离
minimal App 双实例真机目录已分离
QQ 阅读专项资料和 native diagnostics 已沉淀在 profile/docs/tools 中
```

关键差距：

```text
Storage isolation 仅覆盖 Context API，不覆盖 Java 绝对路径和 native open/openat
Hosted runtime 默认没有 per-instance native IO policy
PathClassLoader 默认 nativeLibraryDir/split/sourceDir 模型不足
Provider/ContentResolver/MediaStore/DownloadManager/SAF 未虚拟化
QQ 阅读等加固应用需要恢复壳期望环境，不是 patch 到不崩
```

整改方向：

```text
普通 APK: hosted virtualization 主线，补 package/storage/activity/provider/service/broadcast
Protected App: profile 化兼容线，SPatch/stub/native diagnostics 作为可选实验能力
QQ Reader hook / no-op / callsite patch 不得进入普通 runtime 默认路径
```

### 9.3 修正后的主线架构

```text
MultiApp UI
  -> VirtualInstallService
  -> VirtualInstanceManager
  -> GuestRuntimeBuilder
       -> VirtualPackageRegistry / Snapshot
       -> Guest ClassLoader / nativeLibraryDir / split paths
       -> Resources / AssetManager
       -> LoadedApkBridge / ActivityThread state
       -> VirtualPackageManagerWrapper
       -> VirtualActivityManager / proxy slot pool
       -> VirtualProviderManager / ContentResolver routing
       -> VirtualStoragePolicy / Java + native IO redirect
  -> ProxyActivity / VirtualInstrumentation
```

Legacy / protected app 线：

```text
SPatch/LSPatch/stub profile
  -> manifest rewrite / loader dex / origin apk payload
  -> native diagnostics profile
  -> optional LSPlant/Xposed hooks
  -> app-specific compatibility profile, disabled by default
```

### 9.4 立即执行顺序（替代旧 R1-R5）

#### E1：VirtualPackageSnapshot / Registry

产出文件建议：

```text
core/model/.../virtual/VirtualPackageSnapshot.kt
core/loader/.../VirtualPackageRegistry.kt
core/loader/.../VirtualPackageSnapshotFactory.kt
```

验收：同一个 `instanceId` 能生成稳定 snapshot，包含：

```text
PackageInfo
ApplicationInfo
launcher ActivityInfo
providers authorities
sourceDir/publicSourceDir/dataDir/nativeLibraryDir
originPackageName + virtualPackageName identity policy
```

#### E2：VirtualPackageManagerWrapper 接入 Context

产出文件建议：

```text
core/loader/.../VirtualPackageManagerWrapper.kt
core/loader/.../VirtualPackageInfoFactory.kt
```

首批必须覆盖：

```text
getPackageInfo(String, int)
getPackageInfo(String, PackageInfoFlags)
getApplicationInfo(String, int)
getApplicationInfo(String, ApplicationInfoFlags)
getActivityInfo(ComponentName, int / ComponentInfoFlags)
queryIntentActivities(Intent, int / ResolveInfoFlags)
resolveActivity(Intent, int / ResolveInfoFlags)
resolveContentProvider(String, int / ComponentInfoFlags)
getInstalledPackages / getInstalledApplications 至少返回 self + delegate system
```

验收：minimal App 在 `Application.onCreate()` 和 `Activity.onCreate()` 内查询 self package，均不回落 host。

#### E3：Activity start remap 与 proxy slot 池

产出文件建议：

```text
core/loader/.../VirtualActivityManager.kt 扩展
core/loader/.../VirtualIntentResolver.kt
core/loader/.../ProxyActivityAllocator.kt
app AndroidManifest proxy slot matrix
```

验收：minimal fixture 增加 `SecondActivity`，guest 内部 `startActivity()` 能进入第二个 guest Activity，task/result 基线成立。

#### E4：Provider 最小闭环

产出文件建议：

```text
core/loader/.../VirtualProviderManager.kt
core/loader/.../ContentResolverProxy.kt
```

验收：minimal fixture 增加 `ContentProvider`，guest 内部 query/insert/update/delete 能走 virtual authority，双实例 provider 数据隔离。

#### E5：Storage / native IO policy

产出文件建议：

```text
core/loader/.../VirtualStoragePolicy.kt
core/hook 或 native runtime 中的 per-instance IO redirect 接入 hosted path
```

验收：Java direct `File("/data/data/<origin>")`、native `open()`、Context API 三类路径均落到 instance root 或给出明确不可支持证据。

#### E6：Protected App profile 线

产出文件建议：

```text
profiles/com.qq.reader.json
tools/qqreader-baseline/*
core/hook profile gate
```

验收：QQ 阅读 baseline 只收集 register-natives/self-kill/native-load evidence；不默认启用 no-op / business wrapper / LSPlant。

### 9.5 当前负责人裁决

1. 普通 APK 产品主线：继续走 hosted user-space virtualization，但必须补虚拟服务层。
2. `ContextWrapper` 局部补丁只作为短期 baseline，不再作为架构完成标准。
3. QQ 阅读/加固应用目标仍保留，但不得用专项 hook 污染普通 APK baseline。
4. 下一步代码实现从 E1/E2 开始：先做 `VirtualPackageSnapshot + VirtualPackageManagerWrapper`，再做 Activity remap。
5. 只有当普通 APK 完成 PMS/AMS/Provider/Storage 最小闭环后，才进入 QQ 阅读兼容结论阶段。

### 9.6 E1/E2 执行记录

本轮已按 9.4 的 E1/E2 先落第一层 in-process VPMS baseline：

```text
core/model/.../virtual/VirtualPackageSnapshot.kt
core/loader/.../VirtualPackageRegistry.kt
core/loader/.../VirtualPackageSnapshotFactory.kt
core/loader/.../VirtualPackageInfoFactory.kt
core/loader/.../VirtualPackageManagerWrapper.kt
VirtualContextConfig.packageSnapshot
HostedBootstrapResult.packageSnapshot
VirtualContextWrapper.getPackageManager()
```

已完成的最小能力：

```text
InstallRecord + VirtualInstanceRecord + ResolvedPackage -> VirtualPackageSnapshot
snapshot -> PackageInfo / ApplicationInfo / ActivityInfo / ProviderInfo / ResolveInfo
guest self package getPackageInfo/getApplicationInfo/resolveActivity/resolveContentProvider
originPackageName 与 virtualPackageName 均可命中同一 snapshot
host/system package 查询继续 delegate 到 host PackageManager
```

验证记录：

```text
core:loader:compileDebugKotlin -> BUILD SUCCESSFUL
core:loader:testDebugUnitTest  -> BUILD SUCCESSFUL
app:compileDebugKotlin         -> BUILD SUCCESSFUL
test-fixtures:minimal-app:assembleDebug -> BUILD SUCCESSFUL
```

负责人判定：E1/E2 已具备最小闭环，可以进入真机 evidence；但它仍是 in-process VPMS baseline，不是完整 VPMS service。下一阶段继续 E3 Activity start remap 与 E4 Provider lifecycle，不得把当前结果标记为完整容器完成。

### 9.7 E3 Activity resolver 前置执行记录

本轮已落 Activity start remap 的前置层：

```text
VirtualIntentResolver
VirtualActivityLaunchRequest
VirtualActivityManager.allocateGuestActivity(request)
VirtualActivityManager.launchGuestActivity(request)
test-fixtures/minimal-app SecondActivity
```

该层负责把 guest intent 解析成稳定的 proxy allocation 输入：

```text
explicit origin/virtual package component -> guest Activity class
MAIN/LAUNCHER -> snapshot.launcherActivityName
guest Activity request -> VirtualActivityRecord / proxy Activity slot
```

验证记录：

```text
core:loader:testDebugUnitTest -> BUILD SUCCESSFUL
test-fixtures:minimal-app:assembleDebug -> BUILD SUCCESSFUL
```

负责人判定：E3 只完成 resolver/record 前置，不代表 `startActivity()` 已被接管。下一步必须在 `VirtualInstrumentation` 或 AMS/ATM hook 层拦截 guest `startActivity`，调用 `VirtualIntentResolver` 后改写为 proxy Intent，才能验收 guest 内部 `SecondActivity` 生命周期。
