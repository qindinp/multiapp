# MultiApp v2 Proxy Activity Runtime 执行方案

日期：2026-06-27

状态：替代 `DefaultVirtualActivityController` 手工 Activity 承载路线。

## 1. 背景结论

真机 baseline 已证明当前 hosted container 可以完成：

```text
VirtualInstanceRecord -> InstallRecord -> readonly origin APK -> guest ClassLoader -> guest Application.onCreate()
```

失败发生在 guest launcher Activity 承载：

```text
ClassLoader.loadClass()
-> Activity.newInstance()
-> attachBaseContext()
-> Activity.onCreate()
```

该路线先后触发：

```text
NullPointerException: ActivityInfo.parentActivityName on null mActivityInfo
IllegalStateException: No activity from FragmentController/FragmentManager
```

根因是手工创建 Activity 绕过了 `ActivityThread.performLaunchActivity()` 和系统 `Activity.attach()`。
继续补 `mActivityInfo`、`mFragments`、`mWindow`、`mToken` 不是最终态。

## 2. 最终技术路线

```text
MultiApp UI
  -> VirtualActivityManager.launchGuestLauncher(instanceId)
  -> resolve guest launcher Activity
  -> allocate ProxyActivity
  -> startActivity(proxyIntent with virtual token)
  -> system creates real ProxyActivity lifecycle
  -> VirtualInstrumentation.newActivity substitutes guest Activity class
  -> VirtualLoadedApk/Resources/PackageManager provide guest runtime view
  -> guest Activity runs inside system lifecycle
```

## 3. 第一阶段对象

| 对象 | 模块 | 职责 |
| --- | --- | --- |
| `ProxyActivity` | `app/container` | Manifest 声明的真实 Activity，占用系统 lifecycle/window/token。 |
| `ProxyActivityRegistry` | `core/model` 或 `core/loader` | 管理 proxy slot 与 guest Activity 映射。 |
| `VirtualActivityRecord` | `core/model` | 记录一次 guest Activity launch。 |
| `VirtualActivityManager` | `core/loader` | 从 instanceId 解析 launcher 并启动 proxy。 |
| `VirtualInstrumentation` | `core/loader` | hook `newActivity/callActivityOnCreate/execStartActivity`。 |
| `ActivityThreadCompat` | `core/loader` | 读写 `ActivityThread.mInstrumentation/mPackages` 等兼容层。 |
| `VirtualLoadedApk` | `core/loader` | 提供 guest classloader/resources/applicationInfo。 |
| `VirtualResourcesManager` | `core/loader` | 从 origin APK 构造 guest `Resources/Assets/Theme`。 |
| `VirtualPackageManager` | `core/loader` | 最小化响应 guest package/component 查询。 |

## 4. 实施顺序

### Phase A：停止手工 Activity 作为生产路径

1. `DefaultVirtualActivityController` 标记为 diagnostic-only。
2. `ContainerActivity` 不再把手工 controller 的成功作为 v2 完成依据。
3. baseline evidence 保留 `MANUAL_ACTIVITY_HOST_UNSUPPORTED` / root cause。

验收：

```text
真机 evidence 不再把手工 onCreate 失败解释为普通 launch failure。
文档和代码注释明确该路线不是最终态。
```

### Phase B：ProxyActivity skeleton

1. Manifest 声明固定数量 proxy：

```xml
<activity android:name=".container.ProxyActivity0" android:exported="false" />
<activity android:name=".container.ProxyActivity1" android:exported="false" />
```

2. `ProxyActivity` 读取 `virtualActivityToken`。
3. `VirtualActivityManager` 写入 `VirtualActivityRecord` 并启动 proxy。

验收：

```text
MultiApp 内点击 instance 后，系统 resume ProxyActivity。
evidence 包含 instanceId、guestActivityClassName、proxyActivityClassName。
```

当前真机证据：

```text
evidenceDir=.tmp/manual-hosted-launch-20260627-162114
device=Xiaomi 2509FPN0BC / Android 16 / HyperOS V816
status=PROXY_RESUMED
stage=ACTIVITY_PROXY
proxyActivityClassName=com.multiapp.app.container.ProxyActivity0
originPackageName=com.test.minimal
guestActivityClassName=com.test.minimal.MainActivity

logcat:
activityResumed ComponentInfo{com.multiapp.app/com.multiapp.app.container.ProxyActivity0}
wms.Input focus has changed to Window{... com.multiapp.app/com.multiapp.app.container.ProxyActivity0}
```

Phase B 判定：通过。下一步进入 Phase C。

### Phase C：Instrumentation 接管 Activity 创建

1. 安装 `VirtualInstrumentation` 到当前 `ActivityThread.mInstrumentation`。
2. `newActivity()` 遇到 proxy class 时改用 guest classloader 创建 guest Activity。
3. `callActivityOnCreate()` 前注入 guest context/resources/applicationInfo。

验收：

```text
minimal MainActivity.onCreate 在系统 Activity lifecycle 中执行。
不再出现 mActivityInfo null / No activity。
```

### Phase D：LoadedApk / Resources / PMS 最小闭环

1. `ActivityThread.mPackages[originPackageName]` 映射到 virtual LoadedApk。
2. `getResources/getAssets/getTheme/createPackageContext` 返回 guest 视图。
3. `PackageManager` 查询 launcher activity、applicationInfo、providerInfo 使用 virtual records。

验收：

```text
minimal app 可以读取自身 resources。
普通 APK 双实例 dataRoot 隔离。
```

### Phase E：Protected baseline diagnostics

1. 默认 baseline 不启用 native hooks / LSPlant / business wrappers。
2. 仅启用 register-natives observe-only profile。
3. QQ 阅读 diagnostics 输出 interface20 根因矩阵。

验收：

```text
QQ 阅读 protected baseline 不因容器主动 hook 污染。
diagnostics evidence 能区分 classloader、namespace、shell path、RegisterNatives 时机。
```

## 5. 当前禁止项

以下内容不得作为最终态继续堆补丁：

```text
Activity.newInstance()
attachBaseContext only
直接反射 Activity.onCreate()
逐个补 mActivityInfo/mFragments/mWindow/mToken
把手工 controller 单测通过当作 Step 4 完成
```

这些只能作为诊断工具，用来证明缺失哪个系统生命周期字段。

## 6. 2026-06-27 Phase C 真机结果

设备：`Xiaomi 2509FPN0BC / Android 16 / HyperOS V816`

目标实例：`00086573-633f-4365-948f-97f8b26518f7`

Origin package：`com.test.minimal`

当前 evidence 已证明 hosted launch 路径进入了真实 guest Activity 生命周期执行：

```text
status=GUEST_ACTIVITY_SUBSTITUTED
stage=ACTIVITY_INSTRUMENTATION
proxyActivityClassName=com.multiapp.app.container.ProxyActivity0
guestActivityClassName=com.test.minimal.MainActivity
```

截图证据显示 `MinimalTest launched` 已由 `com.test.minimal.MainActivity.onCreate()` 绘制。当前链路已经跨过此前的手工 Activity 承载阻塞：

```text
ProxyActivity system lifecycle
-> VirtualInstrumentation.newActivity()
-> guest ClassLoader
-> guest Activity instance
-> guest Activity.onCreate()
-> visible first screen
```

同一截图也暴露 Phase D 剩余缺口：

```text
getPackageName(): com.multiapp.app
getApplication(): com.multiapp.app.MultiAppApplication
getDataDir(): /data/user/0/com.multiapp.app
app label: MultiApp
```

结论：Phase C minimal visible launch 已在真机上成立，但当前 runtime 还不是完整用户态容器。下一步必须进入 Phase D，补齐 `VirtualContext`、`LoadedApk`、`PackageManager` 和 `Storage` 虚拟化，让 guest 代码看到 origin/virtual package 身份和隔离数据根，而不是 host 状态。

## 7. 2026-06-27 Phase D 最小 Context 注入结果

设备：`Xiaomi 2509FPN0BC / Android 16 / HyperOS V816`

ADB serial：`192.168.2.53:32835`

目标实例：`00086573-633f-4365-948f-97f8b26518f7`

本轮新增 `HostedActivityContextInjector`，在 `VirtualInstrumentation.callActivityOnCreate()` 前对 guest Activity 执行最小注入：

```text
Activity.mBase -> VirtualContextWrapper
Activity.mApplication -> guest Application
Activity.mResources -> guest context resources fallback
```

真机 evidence：

```text
status=GUEST_ACTIVITY_CONTEXT_INJECTED
stage=ACTIVITY_CONTEXT
guestActivityClassName=com.test.minimal.MainActivity
contextInjected=true
applicationInjected=true
packageName=com.multiapp.instance.00086573633f
applicationClassName=com.test.minimal.MinimalApp
dataDir=/data/user/0/com.multiapp.app/files/instance_data/00086573-633f-4365-948f-97f8b26518f7
```

guest 日志证明 guest 代码实际读到的身份已从 host 切换到虚拟实例：

```text
MinimalApp.attachBaseContext(): base.packageName=com.multiapp.instance.00086573633f
MinimalApp.onCreate(): packageName=com.multiapp.instance.00086573633f
MinimalApp.onCreate(): dataDir=/data/user/0/com.multiapp.app/files/instance_data/00086573-633f-4365-948f-97f8b26518f7
MinimalApp.onCreate(): application=com.test.minimal.MinimalApp
VirtualInstrumentation: Injected guest Activity context ... context=true, app=true
MainActivity.onCreate() complete
```

当前 Phase D 判定：最小 `Context/Application/DataDir` 注入成立，但仍不是完整 VirtualApp/BlackBox 级容器。下一步需要继续补：

```text
Virtual PackageManager: label/signature/activity/provider/applicationInfo 查询
LoadedApk/ApplicationInfo: ActivityInfo 和 system task/packageName 一致性
Resources/AssetManager: origin APK resources/theme 稳定加载
Storage: SharedPreferences/files/database/external files 双实例隔离真机验证
Runtime cache/session: 避免 ContainerActivity 与 Instrumentation 双 bootstrap
```

## 8. 2026-06-27 Phase D storage redirection result

设备：`Xiaomi 2509FPN0BC / Android 16 / HyperOS V816`

ADB serial：`192.168.2.53:32835`

本轮补齐 `VirtualContextWrapper.openOrCreateDatabase()`，并增强 `test-fixtures/minimal-app`，让 guest Activity 真实读写：

```text
SharedPreferences: getSharedPreferences("probe", MODE_PRIVATE)
Files: openFileOutput/openFileInput/getFileStreamPath("probe.txt")
Database: openOrCreateDatabase("probe.db", MODE_PRIVATE, null)
```

真机 guest log：

```text
storage probe
prefs.launchCount: 1
prefs.packageName: com.multiapp.instance.a929b385ac9d
file.path: /data/user/0/com.multiapp.app/files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/files/probe.txt
file.content: package=com.multiapp.instance.a929b385ac9d,dataDir=/data/user/0/com.multiapp.app/files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3
db.path: /data/user/0/com.multiapp.app/files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/databases/probe.db
db.rows: 1
```

设备文件系统证据：

```text
files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/files/probe.txt
files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/shared_prefs/probe.xml
files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/databases/probe.db
files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/databases/probe.db-journal
```

判定：v2 hosted container 的最小 storage redirection 已成立，覆盖 prefs/files/database 三类普通 App 高频私有存储 API。下一步需要用两个同源 `com.test.minimal` 实例同时验证互不串写，并继续补 Virtual PMS / PackageManager 查询，使 `loadLabel()`、`getPackageInfo()`、`getApplicationInfo()` 等查询不再回落到 host 或系统真实安装状态。

## 9. 2026-06-27 Phase D dual-instance storage isolation result

设备：`Xiaomi 2509FPN0BC / Android 16 / HyperOS V816`

ADB serial：`192.168.2.53:32835`

本轮使用两个稳定同源实例验证最小私有存储隔离：

```text
Origin package: com.test.minimal

Instance A: a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3
virtualPackageName: com.multiapp.instance.a929b385ac9d
dataRoot: /data/user/0/com.multiapp.app/files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3

Instance B: d0a49807-28b0-43dc-9559-c413f31944d9
virtualPackageName: com.multiapp.instance.d0a4980728b0
dataRoot: /data/user/0/com.multiapp.app/files/instance_data/d0a49807-28b0-43dc-9559-c413f31944d9
```

文件写入证据：

```text
Instance A probe.txt:
package=com.multiapp.instance.a929b385ac9d,dataDir=/data/user/0/com.multiapp.app/files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3

Instance B probe.txt:
package=com.multiapp.instance.d0a4980728b0,dataDir=/data/user/0/com.multiapp.app/files/instance_data/d0a49807-28b0-43dc-9559-c413f31944d9
```

数据库文件存在性证据：

```text
files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/databases:
- probe.db
- probe.db-journal

files/instance_data/d0a49807-28b0-43dc-9559-c413f31944d9/databases:
- probe.db
- probe.db-journal
```

设备文件系统完整证据：

```text
files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/files/probe.txt
files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/shared_prefs/probe.xml
files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/databases/probe.db
files/instance_data/a929b385-ac9d-41b9-a6d4-5f2ab84c0cb3/databases/probe.db-journal
files/instance_data/d0a49807-28b0-43dc-9559-c413f31944d9/files/probe.txt
files/instance_data/d0a49807-28b0-43dc-9559-c413f31944d9/shared_prefs/probe.xml
files/instance_data/d0a49807-28b0-43dc-9559-c413f31944d9/databases/probe.db
files/instance_data/d0a49807-28b0-43dc-9559-c413f31944d9/databases/probe.db-journal
```

判定：两个同源 `com.test.minimal` 实例已在真机上证明 prefs/files/database 私有存储分别落到各自 `files/instance_data/<instanceId>/` 根目录，`virtualPackageName` 与 `dataRoot` 均不相同，当前最小 storage isolation 成立。

剩余缺口：这只证明了普通私有存储 API 的最小隔离。要接近 VirtualApp / BlackBox 级用户态容器，下一阶段必须补 Virtual PMS / PackageManager、LoadedApk/ApplicationInfo 一致性、Resources/AssetManager、Provider/Service/Broadcast 代理和更完整的外部存储重定向。

## 10. 2026-06-27 Phase E minimal ApplicationInfo label identity fix

本轮针对 Phase D 真机截图暴露的问题继续推进：guest 代码执行

```text
getApplicationInfo().loadLabel(getPackageManager())
```

时曾回落到 host 视角，显示类似 `com.multiapp.app.MultiAppApplication` / `MultiApp` 的结果。这说明当前 hosted container 虽然已经完成 Activity 替换、Context 注入和私有存储隔离，但 PackageManager/ApplicationInfo 身份层仍未闭环。

本轮修复范围：

```text
ManifestParser.ParsedManifest.applicationLabel
ResolvedPackage.applicationLabel
HostedBootstrapResult.applicationLabel
VirtualContextConfig.applicationLabel
VirtualContextWrapper.getApplicationInfo().nonLocalizedLabel
VirtualContextWrapper.getPackageResourcePath()/getPackageCodePath()
```

修复后的最小语义：

```text
origin APK manifest android:label -> ResolvedPackage.applicationLabel
HostedRuntimeBootstrap -> HostedBootstrapResult.applicationLabel
VirtualInstrumentation -> VirtualContextConfig.applicationLabel
guest Activity/Application Context -> ApplicationInfo.nonLocalizedLabel
```

这样 `ApplicationInfo.loadLabel()` 会优先返回 origin APK manifest 中解析出的 label，不再因为 `labelRes` 或 host `PackageManager` 查询回落到 MultiApp 自身身份。

为真机验证增强了 `test-fixtures/minimal-app` 输出：

```text
applicationInfo.packageName: <guest/origin package>
app label: <origin manifest label>
```

本轮验证：

```text
core:manifest:testDebugUnitTest -> BUILD SUCCESSFUL
core:model:testDebugUnitTest    -> BUILD SUCCESSFUL
core:loader:testDebugUnitTest   -> BUILD SUCCESSFUL
app:compileDebugKotlin          -> BUILD SUCCESSFUL
test-fixtures:minimal-app:assembleDebug -> BUILD SUCCESSFUL
app:packageDebug                -> BUILD SUCCESSFUL
```

真机准备状态：

```text
ADB serial: 192.168.2.53:32835
app-debug.apk install -r -g: Success
files/artifacts/com.test.minimal-origin.apk 已覆盖为新 minimal fixture
```

判定：这是 Virtual PMS / PackageManager 方向的第一层身份修复，只关闭 `ApplicationInfo.loadLabel()` 的 host 泄漏和 source path 查询问题。它还不是完整 Virtual PMS。下一阶段必须继续实现或接入 PackageManager wrapper / IPackageManager proxy，至少覆盖：

```text
getPackageInfo(originPackageName / virtualPackageName)
getApplicationInfo(originPackageName / virtualPackageName)
getActivityInfo(ComponentName)
resolveActivity(Intent)
queryIntentActivities(Intent)
provider/service/broadcast metadata
signature/signingInfo 查询策略
```

## 11. 2026-06-27 Phase E E1/E2 minimal VPMS snapshot and PackageManager wrapper

本轮按负责人复审后的 E1/E2 执行，不再继续零散 `ContextWrapper` 补丁，而是引入 hosted runtime 的第一层虚拟包管理数据源：

```text
VirtualPackageSnapshot
VirtualPackageRegistry
VirtualPackageSnapshotFactory
VirtualPackageInfoFactory
VirtualPackageManagerWrapper
VirtualContextWrapper.getPackageManager()
```

数据流：

```text
InstallRecord + VirtualInstanceRecord + ResolvedPackage
  -> VirtualPackageSnapshotFactory
  -> HostedBootstrapResult.packageSnapshot
  -> VirtualContextConfig.packageSnapshot
  -> VirtualContextWrapper.getPackageManager()
  -> VirtualPackageManagerWrapper
```

首批覆盖的 guest self package 查询：

```text
getPackageInfo(String, int)
getPackageInfo(String, PackageInfoFlags)
getApplicationInfo(String, int)
getApplicationInfo(String, ApplicationInfoFlags)
getActivityInfo(ComponentName, int / ComponentInfoFlags)
queryIntentActivities(Intent, int / ResolveInfoFlags)
resolveActivity(Intent, int / ResolveInfoFlags)
resolveContentProvider(String, int / ComponentInfoFlags)
getInstalledPackages(int)
getInstalledApplications(int)
checkPermission(permission, packageName)
```

当前策略：

```text
originPackageName / virtualPackageName -> snapshot
host/system/unknown package -> delegate to host PackageManager
```

为真机验证增强了 `test-fixtures/minimal-app`：

```text
package manager probe:
pm.packageInfo.packageName
pm.packageInfo.versionName
pm.applicationInfo.packageName
pm.resolveActivity
```

本轮验证：

```text
core:loader:compileDebugKotlin -> BUILD SUCCESSFUL
core:loader:testDebugUnitTest  -> BUILD SUCCESSFUL
app:compileDebugKotlin         -> BUILD SUCCESSFUL
test-fixtures:minimal-app:assembleDebug -> BUILD SUCCESSFUL
```

判定：E1/E2 的最小 in-process VPMS baseline 已进入 hosted runtime，guest self `PackageManager` 查询不再必然回落到 host/system。当前仍不是完整 VirtualApp/BlackBox 级 VPMS，尚未覆盖 Service/Broadcast/Provider 生命周期、AMS 联动、签名/SigningInfo、权限 grant、package visibility、uid/appId、split APK、native library extraction 和多进程 Binder 化 VPMS。

## 12. 2026-06-27 Phase E E3 Activity intent resolver baseline

本轮按 E3 先落 Activity start remap 的前置模型，不直接宣称已完成 `execStartActivity` 接管。

新增：

```text
VirtualIntentResolver
VirtualActivityLaunchRequest
VirtualActivityManager.allocateGuestActivity(request)
VirtualActivityManager.launchGuestActivity(request)
test-fixtures/minimal-app SecondActivity
```

当前能力：

```text
explicit guest ComponentName(package=origin/virtual, class=guest Activity)
  -> VirtualActivityLaunchRequest
  -> ProxyActivityRegistry.allocate()
  -> VirtualActivityRecord(token, instanceId, originPackageName, guestActivityClassName, proxyActivityClassName)

MAIN + LAUNCHER intent
  -> snapshot.launcherActivityName
  -> VirtualActivityLaunchRequest(reason=launcher)
```

验证：

```text
core:loader:testDebugUnitTest -> BUILD SUCCESSFUL
test-fixtures:minimal-app:assembleDebug -> BUILD SUCCESSFUL
```

判定：E3 的 resolver/record/proxy allocation 前置层成立，后续 `VirtualInstrumentation.execStartActivity` 或 AMS/ATM hook 可以复用该 resolver，把 guest 内部 `startActivity()` 改写到 proxy Activity。当前仍未完成真正 Activity stack 接管，尚不能验收 guest 内部 `startActivity()` 进入 `SecondActivity`。
