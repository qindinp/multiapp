# MultiApp v2 Hosted Container 多角色审核与整改文件

日期：2026-06-27

分支：`container-runtime-refactor`

审核范围：v2 直接最终态路线，即 `VirtualInstall / VirtualInstance` 作为唯一事实源，
v2 新实例从 MultiApp hosted container 启动，不再生成独立 Stub APK。

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

### P0-2：Hosted Container 没有启动 guest launcher Activity

问题：`ContainerActivity` 只执行 `bootstrap.run(instanceId)`，成功后记录 `guestApplication` 和
`guestClassLoader`，没有解析 launcher Activity，也没有调用 `VirtualActivityController`。

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

1. 实现生产级 `VirtualActivityController`，至少支持 single launcher Activity MVP。
2. 从 `InstallRecord.activities` 或 `VirtualPackageResolver` 解析 launcher Activity。
3. Hosted bootstrap 成功后执行：

```text
resolve launcher activity
-> create/attach guest Activity or proxy host lifecycle
-> call onCreate
-> show minimal test app first screen
```

4. 不能只 `Class.forName().newInstance()`，需要处理 Activity base context、Window、token、
   Application、theme、lifecycle。

验收：

```text
MultiApp 内启动 minimal test app instance 后，minimal MainActivity 可见。
失败时输出 ACTIVITY stage failed，而不是空白页或 bootstrap success。
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
