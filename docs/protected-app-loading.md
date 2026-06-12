# Protected App Loading Notes

本文档说明当前项目中用于承载加固应用的加载链路、关键代码位置、运行原理和已知问题。这里的“绕过加固”不是脱壳或还原 APK，而是在同一进程内构造一个尽量接近原应用的运行环境，让壳的 `JNI_OnLoad`、`RegisterNatives`、DEX 解密和后续业务 native 库初始化可以在 clone/stub 包中继续执行。

## 目标

MultiApp 生成一个 stub APK。系统实际安装和启动的是 stub 包名，但进程启动后会：

1. 从 stub APK assets 中取出原始 APK。
2. 用原始 APK 创建 guest `PathClassLoader`。
3. 替换当前 `LoadedApk.mClassLoader`、资源路径和 native library 路径。
4. 让加固壳 native 库在 guest ClassLoader 命名空间完成 `JNI_OnLoad` 和 `RegisterNatives`。
5. 在必要时主动加载业务 SDK native 库，补齐动态注册方法。
6. 上层 Java 代码尽量看到原始包名、原始资源、原始 `ApplicationInfo`。

核心难点是 native 库必须绑定到正确的 ClassLoader。普通 `System.load()` 或从 loader/stub 上下文加载，容易让 `FindClass` 找不到 guest 类，或让 `RegisterNatives` 注册到错误类对象，最终表现为 `UnsatisfiedLinkError`。

## 构建期流程

入口代码：`core/stub/src/main/java/com/multiapp/core/stub/StubBuilder.kt`

### 1. 解析原 APK

`StubBuilder.build()` 读取 `config.originalSignatures.firstOrNull()` 中的原 APK 路径，解析 manifest、launcher activity、theme、组件和 icon。

### 2. 生成 stub manifest

当前 `rewriteManifest()` 主动抛错，走 fallback generator，避免保留原 APK 的 `<permission>` 声明导致 `INSTALL_FAILED_DUPLICATE_PERMISSION`。生成的 manifest 会把目标组件映射到 stub 包中，并设置 loader 使用的 `AppComponentFactory`。

相关代码：

- `ManifestGenerator`
- `BinaryXmlEncoder`
- `StubBuilder.rewriteManifest()`

### 3. 打包 loader.dex

`core/stub/build.gradle.kts` 的 `generateLoaderDex` 任务会把 `core:loader` 及依赖编译成 `core/stub/src/main/assets/loader.dex`。

`app/build.gradle.kts` 的 `copyLoaderDex` 会依赖 `generateLoaderDex`，避免 app 打包时复制旧 loader。

最终 stub APK 内：

```text
classes.dex              -> loader.dex 内容，系统首先加载 LoaderFactory
assets/origin.apk        -> 可能被注入/修改过的原 APK
assets/origin_original.apk -> 未修改原 APK，用于完整性校验重定向
assets/multiapp_config.json
lib/<abi>/libmultiapp-native.so
lib/<abi>/libshadowhook.so
lib/<abi>/libc++_shared.so
lib/<abi>/<origin libs>.so
```

### 4. 注入加固壳加载点

`StubBuilder.injectPackerLibLoad()` 会尝试修改 origin APK 的 DEX：

- 注入 helper class，用于从 guest ClassLoader 上下文加载 native 库。
- 尝试在这些壳类的 `load()` 中注入 `System.loadLibrary("jiagu_vip")`：

```text
com.stub.StubApp
com.qihoo.util.StubApp
com.stub.StubApplication
com.secneo.apkwrapper.ApplicationWrapper
```

目的：部分壳的 `StubApp.load()` 会直接调用 `interface20()` 等 native 方法，但没有先加载 `libjiagu_vip.so`。注入后尽量恢复原应用中的加载时机和 ClassLoader 语义。

### 5. 打包 native 库

`StubBuilder.packageNativeLibs()` 会：

- 先打包 MultiApp 自己的 hook native 库：`libmultiapp-native.so`、`libshadowhook.so`、`libc++_shared.so`。
- 再从 origin APK 提取所有 `lib/**/*.so` 到 stub APK。
- native libs 使用 `STORED` 方式，便于 Android 直接 mmap。

## 运行期 Java 加载流程

入口代码：`core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt`

`LoaderFactory` 是 stub 的 `AppComponentFactory`。系统创建 Activity、Provider、Application 前会进入这里。

### 1. 初始化入口

`instantiateActivity()`、`instantiateProvider()`、`instantiateApplication()` 都会调用：

```kotlin
ensureClassLoaderSwapped(cl)
```

该方法只执行一次，内部进入 `initializeInternal()`。

### 2. 读取系统启动上下文

`initializeInternal()` 做以下事情：

1. 尝试 hidden API bypass。
2. 获取 `ActivityThread.currentActivityThread()`。
3. 从 `mBoundApplication.appInfo` 读取 stub `ApplicationInfo`。
4. 读取 stub APK 路径、dataDir、packageName。
5. 从 `assets/multiapp_config.json` 读取原包名与 stub 包名。

### 3. 解压 APK

`extractOriginApk()` 解压：

```text
assets/origin.apk -> /data/user/0/<stubPkg>/origin.apk
```

`extractOriginalApk()` 解压：

```text
assets/origin_original.apk -> /data/user/0/<stubPkg>/origin_original.apk
```

`origin.apk` 是运行用 APK；`origin_original.apk` 用于壳做完整性校验时重定向。

### 4. 安装 Runtime.nativeLoad hook

代码：

```kotlin
NativeHookBridge.markNativeLibLoaded()
installNativeLoadHookIfAvailable()
```

`installNativeLoadHookIfAvailable()` 调用：

```kotlin
NativeHookBridge.getInstance().hookRuntimeNativeLoad(candidates)
```

候选 fallback caller 包括：

```text
com.stub.StubApp
com.qihoo.util.StubApp
com.stub.StubApplication
originApplicationClass
```

目的：修复某些壳通过 JNI 反射调用 `System.loadLibrary` 时传给 ART `Runtime.nativeLoad` 的 caller 为 null，导致 ART 内部崩溃或库没有绑定到正确 ClassLoader。

### 5. 替换 LoadedApk / ApplicationInfo

`swapClassLoader()` 是核心方法。它不会重新创建 `LoadedApk`，而是直接改当前进程中已经存在的对象：

- `appInfo.sourceDir = originApk.absolutePath`
- `appInfo.publicSourceDir = originApk.absolutePath`
- `appInfo.name = originApplicationClass`
- `appInfo.nativeLibraryDir = origin extracted lib dir`
- `LoadedApk.mClassLoader = StealthClassLoader(realGuestClassLoader)`
- `LoadedApk.mAppDir = originApk.absolutePath`
- `LoadedApk.mResDir = originApk.absolutePath`
- `LoadedApk.mResources = origin Resources`
- `ActivityThread.mPackages[stubPkg] = loadedApk`
- `ActivityThread.mPackages[originalPkg] = loadedApk`

特别注意：代码保留 stub 身份，不改 `ApplicationInfo.packageName` 和 `LoadedApk.mPackageName`，避免系统权限/AppOps 检查失败。应用层看到的原包名通过 `GuestContextWrapper` 提供。

### 6. native 搜索路径

运行期会从 `origin.apk` 中提取当前 ABI 的 `.so`：

```kotlin
extractOriginNativeLibs(originApk)
```

然后构造 `PathClassLoader` 的 native library path：

```text
<origin extracted lib dir>
<stub nativeLibraryDir>
<stub secondaryNativeLibraryDir>
<stub APK>!/lib/<abi>
```

这样既能找到原应用库，也能找到 `libmultiapp-native.so` 等 hook 库。

### 7. 加固壳预加载

`preloadPackerLibViaGuestClassLoader()` 专门处理 `libjiagu_vip.so`。

步骤：

1. 在 guest ClassLoader 中找壳类，如 `com.stub.StubApp`。
2. 初始化 native hooks：`bridge.initNativeHooks()`。
3. 设置 `FindClass` hook，使 `JNI_OnLoad` 里的 `FindClass("com/stub/StubApp")` 能通过 guest ClassLoader 找到。
4. 设置完整性校验重定向：`origin.apk -> origin_original.apk`。
5. 调用 `preloadNativeLibraries(listOf(libjiagu_vip.so))`，底层 `dlopen` 后手动调用 `JNI_OnLoad`。
6. 清理完整性重定向。
7. 调用 `StubApp.load()`，让壳继续解密 DEX 或初始化。
8. 兜底注册部分 StubApp native 方法。

这里的核心思路是：壳的 `JNI_OnLoad` 必须在能看到 guest 类、看不到 MultiApp hook 痕迹、且读取到期望 APK 内容的环境中运行。

### 8. Stage 2 业务 native 预加载

`preloadGuestRuntimeNativeLibraries()` 当前针对已知崩溃点：

```text
com.yuewen.ywlogin.login.YWLoginManager.getInstance()
```

逻辑：

1. 在 guest ClassLoader 中加载 `YWLoginManager` 类。
2. 找出 origin native lib 目录下所有 `.so`。
3. 优先尝试包含 `yw`、`login`、`yuewen`、`reader`、`account`、`sdk` 等关键字的库。
4. 使用 `NativeHookBridge.loadLibraryForGuest()` 调 ART `Runtime.nativeLoad`，显式指定 guest ClassLoader 和 callerClass。
5. 每加载一个库后调用 `isNativeMethodBound()` probe `getInstance()` 是否已绑定。
6. 若候选库未绑定，按优先级遍历剩余 `.so`。

成功标志：

```text
Stage2 native method bound after <lib>.so: com.yuewen.ywlogin.login.YWLoginManager.getInstance
```

当前日志显示还没越过该点，且最近复现没有抓到 Stage2 日志。因此后续优先要确认新 loader 是否确实进入 clone，再考虑做 `.so` 字符串扫描或 `RegisterNatives` 诊断。

### 9. Context 包装

代码：`core/loader/src/main/java/com/multiapp/core/loader/GuestContextWrapper.kt`

该包装器让应用代码看到：

- `getPackageName()` 返回原包名。
- `getApplicationInfo()` 返回 sourceDir/publicSourceDir/nativeLibraryDir/metaData 被替换后的信息。
- `getPackageCodePath()`、`getPackageResourcePath()` 指向 origin APK。
- `getResources()`、`getAssets()` 返回 origin resources。

它还提供 `mOuterContext` 字段，用于兼容 Tinker/RFix 等热修复框架通过反射访问 `ContextImpl.mOuterContext` 的逻辑。

## Native 层实现

主要代码：

- `core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt`
- `core/hook/src/main/cpp/native-hook.cpp`

### 1. NativeHookBridge

`NativeHookBridge` 是 Java/Kotlin 到 native 的桥：

- 加载 `libmultiapp-native.so`
- 安装 `Runtime.nativeLoad` hook
- 调用 `nativePreloadLibraries()`
- 调用 `nativeLoadLibraryForGuest()`
- 设置 `FindClass` hook
- 设置完整性重定向
- 管理路径重定向、伪装 `/proc/self`、隐藏 maps 中的 hook 痕迹

### 2. Runtime.nativeLoad hook

C++ 中 `installNativeLoadHook()`：

1. 找到 `java/lang/Runtime`。
2. 从 `libart.so` 里找 ART 原始 `Runtime_nativeLoad` 符号。
3. 用 `RegisterNatives` 把 `Runtime.nativeLoad` 替换成 `hooked_nativeLoad()`。

`hooked_nativeLoad()` 会：

- 记录加载路径。
- 如果 caller 为 null，则从 classLoader 中按 fallback caller 列表加载一个 class。
- 调回 ART 原始 nativeLoad。

这解决两个问题：

- 防止 ART 因 caller/protection domain 异常崩溃。
- 保证 native 库记录在 guest ClassLoader 下，`FindClass/RegisterNatives` 的类归属正确。

### 3. loadLibraryForGuest

`NativeHookBridge.loadLibraryForGuest()` 对应 native：

```cpp
nativeLoadLibraryForGuest(libPath, classLoader, callerClass)
```

它直接调用：

```text
Runtime.getRuntime().nativeLoad(libPath, classLoader, callerClass)
```

绕过 Java 层 hidden API 限制，同时保持 ART 的 native library bookkeeping。

### 4. FindClass hook

壳的 `JNI_OnLoad` 常见写法：

```cpp
env->FindClass("com/stub/StubApp")
env->RegisterNatives(...)
```

如果从 loader/stub 语境直接 `dlopen`，`FindClass` 可能走 boot/stub ClassLoader，找不到 guest 中的壳类。

当前方案：

1. `nativeSetupFindClassHook(guestCl, targetClassNames)` 保存 guest ClassLoader 和目标类。
2. `nativeInstallFindClassHook()` 修改 JNI function table 中的 `FindClass` 指针。
3. `hooked_FindClass()` 优先用 guest ClassLoader 加载目标类，找不到再走原始 `FindClass`。

### 5. dlopen + 手动 JNI_OnLoad

`nativePreloadLibraries()` 用于直接加载壳库：

1. `dlopen()` 指定 `.so`。
2. 找 `JNI_OnLoad`。
3. 安装/应用 GOT hook，过滤 `/proc/self/maps` 等读取。
4. 手动调用 `JNI_OnLoad(JavaVM*, nullptr)`。

这用于绕过 Java hidden API，也用于在壳调用 native 方法前强制完成注册。

### 6. 完整性校验重定向

壳在 `JNI_OnLoad` 中可能读取 APK 校验 DEX 完整性。由于 `assets/origin.apk` 可能被注入了 helper 或 `System.loadLibrary`，校验会失败。

构建期同时打包：

```text
assets/origin.apk
assets/origin_original.apk
```

运行期调用：

```kotlin
bridge.setIntegrityRedirect(modifiedApkPath, originalApkPath)
```

native 层 hook `open/openat/fopen/readlink` 时，如果壳读取 modified origin，则重定向到 original origin。

### 7. GOT hook 与反检测

部分 Android 版本上 inline hook 受限，代码实现了 GOT hook：

- 枚举目标库和 `libc.so`
- 修改 GOT 中的 `open`、`openat`、`fopen`、`readlink`
- 对 `/proc/self/maps`、`smaps`、`pagemap` 返回空 fd 或 tmpfile
- 对 `/proc/self/map_files` 的 `readlink` 返回失败

目标是避免壳通过 maps 发现：

```text
multiapp
libmultiapp-native.so
shadowhook
hook framework
```

同时 `NativeHookBridge.filterProcMaps()` 也提供 Java 层 maps 过滤。

## Provider 降级

`LoaderFactory.instantiateProvider()` 会优先用 guest ClassLoader 创建 provider，并用 `SafeProviderWrapper` 包装。

如果 provider 的 `attachInfo()`、`onCreate()` 或 CRUD 调用抛错，wrapper 会标记降级，避免 provider 初始化失败阻塞整个 app 启动。

这是为了处理 FileProvider、广告 SDK provider、热修复 provider 等在资源或 meta-data 不完整时的崩溃。

## 当前验证状态

最近设备日志显示 clone 仍崩在：

```text
java.lang.UnsatisfiedLinkError:
No implementation found for
com.yuewen.ywlogin.login.YWLoginManager.getInstance()
```

调用链：

```text
YWLoginManager.getInstance
YWLogin.registerParameter
ReaderApplication.initLoginSDK
ReaderApplication.appNetworkStart
ReaderApplication.onCreate
RFixProxyApplication.onCreate
```

这说明：

- 已经进入原应用 `ReaderApplication.onCreate()`。
- 壳/热修复入口至少已经让 Java Application 跑起来。
- 当前未完成业务登录 SDK native 方法绑定。

最近干净复现没有看到 `Stage2 native preload` / `MultiApp.POC` 关键日志，因此下一步首先确认新 `loader.dex` 是否确实进入新生成的 stub。

## 下一步建议

### 1. 验证 loader.dex 是否更新

在生成的 stub APK 中检查 `classes.dex` 或内嵌 loader 是否包含以下字符串：

```text
Stage2 native preload
libywlogin.so
com.yuewen.ywlogin.login.YWLoginManager
```

如果没有，说明新 loader 没进 stub。

### 2. 增加 so 字符串扫描

不要只靠库名猜测。应扫描 origin `.so` 字符串，定位包含以下内容的库：

```text
YWLoginManager
getInstance
com/yuewen/ywlogin/login
Java_com_yuewen_ywlogin_login_YWLoginManager_getInstance
```

命中的库应优先通过 `loadLibraryForGuest()` 加载。

### 3. 增加 RegisterNatives 诊断

在 native 层 hook 或包装 `RegisterNatives`，打印：

```text
class name
method name
signature
fnPtr
library
```

需要确认是否出现：

```text
com/yuewen/ywlogin/login/YWLoginManager.getInstance
```

如果没出现，说明相关业务库没有走到注册逻辑；如果出现但仍崩，说明注册类对象或 ClassLoader 仍不一致。

### 4. 运行期日志必须可读

当前 clone 不是 debuggable，`run-as` 读不了 `loader_debug.log`。建议确认最终 stub manifest 是否带 debug 标志，或临时增加外部可读日志导出，便于定位早期 AppComponentFactory 阶段问题。

## 关键文件索引

- `core/stub/src/main/java/com/multiapp/core/stub/StubBuilder.kt`
  构建 stub APK、打包 origin、打包 native、注入壳加载 helper。

- `core/stub/build.gradle.kts`
  生成 `loader.dex`。

- `app/build.gradle.kts`
  将 `loader.dex` 复制进 app assets，确保主 app 构建时使用新 loader。

- `core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt`
  AppComponentFactory 入口，替换 ClassLoader、资源、ApplicationInfo，执行壳库和业务 native 预加载。

- `core/loader/src/main/java/com/multiapp/core/loader/GuestContextWrapper.kt`
  给 guest Java 代码提供原包名、原 APK 路径、原资源和 meta-data。

- `core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt`
  Java/Kotlin 到 native hook 能力的桥。

- `core/hook/src/main/cpp/native-hook.cpp`
  Runtime.nativeLoad hook、FindClass hook、GOT hook、路径重定向、手动 JNI_OnLoad。

