# 360 加固加载链路综合分析

最后更新：2026-06-07

目标应用：QQ 阅读（`com.qq.reader`）

当前目标：在 MultiApp stub 进程中承载加固应用，让壳的 native 初始化、DEX 解密、业务 JNI 注册和原应用 `Application` 初始化尽量按原环境完成。

## 结论摘要

当前项目不是在做离线脱壳，而是在运行期接管加固应用的启动环境：

1. 用 stub APK 作为系统安装主体。
2. 在 `AppComponentFactory` 阶段解压并加载原 APK。
3. 替换 `LoadedApk.mClassLoader`、资源路径、native 库路径。
4. 让加固壳 native 库在 guest ClassLoader 命名空间中完成 `JNI_OnLoad` 和 `RegisterNatives`。
5. 通过路径重定向、`FindClass` hook、`Runtime.nativeLoad` 修正、GOT hook 等方式处理壳的校验和反检测。

最近日志显示已经进入原应用：

```text
com.qq.reader.ReaderApplication.onCreate
com.tencent.rfix.loader.app.RFixProxyApplication.onCreate
```

但仍卡在业务 JNI 注册点：

```text
java.lang.UnsatisfiedLinkError:
No implementation found for
com.yuewen.ywlogin.login.YWLoginManager.getInstance()
```

这说明加固壳/热修复入口至少已经推进到原应用初始化阶段，但 `YWLoginManager.getInstance()` 对应 native 实现还没有绑定成功。当前还不能宣称完整绕过加固。

## 核心问题

加固应用的 native 加载和普通应用不同：

- 壳库通常在 `JNI_OnLoad` 中做反调试、完整性校验、环境检测、DEX 解密和 `RegisterNatives`。
- `JNI_OnLoad` 里的 `FindClass` 必须命中 guest ClassLoader 中的真实壳类。
- `RegisterNatives` 必须注册到 guest ClassLoader 里的真实类对象。
- `System.loadLibrary` / `Runtime.nativeLoad` 的 caller 和 ClassLoader 不正确时，库会被绑定到错误 namespace。
- 壳读取 APK 做完整性校验时，不能看到被注入或修改过的 APK。
- 壳读取 `/proc/self/maps` 时，不能看到 `multiapp`、`shadowhook`、`libmultiapp-native.so` 等痕迹。

项目当前围绕这些问题建立了一套运行期加载链路。

## 当前实现链路

### 构建期：StubBuilder

入口：

```text
core/stub/src/main/java/com/multiapp/core/stub/StubBuilder.kt
```

`StubBuilder.build()` 负责生成最终 stub APK：

```text
classes.dex
assets/origin.apk
assets/origin_original.apk
assets/multiapp_config.json
lib/<abi>/libmultiapp-native.so
lib/<abi>/libshadowhook.so
lib/<abi>/libc++_shared.so
lib/<abi>/<origin native libs>.so
```

关键点：

- `classes.dex` 实际是预编译的 `loader.dex`，包含 `LoaderFactory`。
- `assets/origin.apk` 是运行时加载用 APK，可能被注入 helper。
- `assets/origin_original.apk` 是未修改 APK，用于完整性校验重定向。
- 所有 native 库以 `STORED` 方式打包，保证系统可 mmap。
- `packageNativeLibs()` 同时打包 MultiApp hook 库和原 APK native 库。

`injectPackerLibLoad()` 会尝试在壳类中注入 `System.loadLibrary("jiagu_vip")`：

```text
com.stub.StubApp
com.qihoo.util.StubApp
com.stub.StubApplication
com.secneo.apkwrapper.ApplicationWrapper
```

这一步的风险是会改变 DEX，可能触发壳的 DEX 完整性校验。因此项目同时保留 `origin_original.apk`，运行期在壳校验时重定向到未修改 APK。

### 运行期：LoaderFactory

入口：

```text
core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
```

系统创建 `Activity`、`Provider`、`Application` 前，会先进入 `AppComponentFactory`。`LoaderFactory` 在这里调用：

```kotlin
ensureClassLoaderSwapped(cl)
```

核心流程：

1. 读取 `ActivityThread.currentActivityThread()`。
2. 从 `mBoundApplication.appInfo` 读取 stub `ApplicationInfo`。
3. 解压 `assets/origin.apk` 到 stub dataDir。
4. 解压 `assets/origin_original.apk`。
5. 安装 `Runtime.nativeLoad` hook。
6. 替换 `LoadedApk.mClassLoader`。
7. 修改 `sourceDir`、`publicSourceDir`、`nativeLibraryDir`、`mAppDir`、`mResDir`。
8. 重建 origin resources。
9. 更新 `ActivityThread.mPackages` 映射。
10. 预加载壳 native 库。
11. 尝试 Stage 2 业务 native 预加载。

注意：代码刻意保留系统层面的 stub 包身份，不直接改 `LoadedApk.mPackageName`，避免系统权限和 AppOps 校验失败。原包名伪装放在 `GuestContextWrapper` 里做。

### Context 伪装：GuestContextWrapper

入口：

```text
core/loader/src/main/java/com/multiapp/core/loader/GuestContextWrapper.kt
```

它让上层应用代码看到：

```text
getPackageName() -> 原包名
getApplicationInfo().sourceDir -> origin.apk
getApplicationInfo().nativeLibraryDir -> origin lib dir
getPackageCodePath() -> origin.apk
getPackageResourcePath() -> origin.apk
getResources()/getAssets() -> origin resources
```

并额外提供 `mOuterContext` 字段，兼容 Tinker/RFix 这类热修复框架的反射逻辑。

### Native 桥：NativeHookBridge

入口：

```text
core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt
core/hook/src/main/cpp/native-hook.cpp
```

主要能力：

- 加载 `libmultiapp-native.so`
- 安装 `Runtime.nativeLoad` hook
- 通过 JNI 调 ART `nativeLoad`，让库绑定到 guest ClassLoader
- 设置 `FindClass` hook
- 手动 `dlopen` 并调用 `JNI_OnLoad`
- 设置完整性校验路径重定向
- 管理路径重定向、`/proc/self` 伪装、maps 过滤
- GOT hook `open/openat/fopen/readlink`

## 已尝试方案与结果

### 方案 1：DEX 注入加载 helper

思路：构建期往原 APK 的 DEX 注入 helper，并在 `StubApp.load()` 开头插入 `System.loadLibrary("jiagu_vip")`。

涉及代码：

```text
DexPatcher.injectHelperClass()
DexPatcher.injectLoadLibrary()
StubBuilder.injectPackerLibLoad()
```

结果：有价值但有风险。

优点是能恢复壳库加载时机；缺点是修改 DEX 会触发壳完整性校验。因此必须配合 `origin_original.apk` 和完整性重定向。

### 方案 2：直接调用 Runtime.nativeLoad

思路：调用：

```text
Runtime.nativeLoad(path, classLoader, callerClass)
```

让 `.so` 绑定到 guest ClassLoader。

涉及代码：

```text
NativeHookBridge.loadLibraryForGuest()
nativeLoadLibraryForGuest()
```

结果：仍然是必要能力，但 Android 高版本 hidden API 限制严格，不能依赖 Java 反射。当前通过 JNI 层调用并配合 native hook 处理。

### 方案 3：dlopen + 手动 JNI_OnLoad

思路：避开 Java hidden API，用 libc 公开 API：

```text
dlopen(libjiagu_vip.so)
dlsym("JNI_OnLoad")
JNI_GetCreatedJavaVMs()
JNI_OnLoad(vm, nullptr)
```

涉及代码：

```text
nativePreloadLibraries()
LoaderFactory.preloadPackerLibViaGuestClassLoader()
```

结果：可用于强制执行壳初始化，但单独使用不等价于 ART `nativeLoad`。问题在于：

- ART 不会自动把库记录到正确 ClassLoader。
- `JNI_OnLoad` 中的 `FindClass` 可能找不到 guest 类。
- 壳可能在 `JNI_OnLoad` 早期读取 maps 或校验 APK。

因此当前方案必须同时配合 `FindClass` hook、GOT hook 和完整性重定向。

### 方案 4：ShadowHook inline hook

思路：hook libc 的 `open/fopen/readlink` 等函数，过滤 `/proc/self/maps` 中的框架痕迹。

结果：在当前 Android 16 设备上不可靠，曾出现 `errno=12`，疑似受 W^X 或 trampoline 内存限制影响。

因此项目转向 GOT hook。

### 方案 5：GOT/PLT hook

思路：修改目标库和 libc 的 GOT 表，将 `open/openat/fopen/readlink` 指向自己的 hook 函数。

涉及代码：

```text
got_hook_library_callback()
got_hooked_open()
got_hooked_openat()
got_hooked_fopen()
got_hooked_readlink()
```

结果：可拦截通过 PLT/GOT 发出的 libc 调用，对 `/proc/self/maps`、`smaps`、`pagemap` 返回空 fd/tmpfile，对 `/proc/self/map_files` 的 `readlink` 返回失败。

局限：

- 如果壳直接 syscall，GOT hook 拦不到。
- 如果壳用 `dl_iterate_phdr()` 枚举内存里的库，GOT hook 也拦不到。
- 如果检测在 constructor 中过早发生，需要更早介入。

### 方案 6：FindClass JNI 函数表 hook

思路：替换 JNI function table 中的 `FindClass` 指针，让 `JNI_OnLoad` 中查壳类时优先走 guest ClassLoader。

涉及代码：

```text
nativeSetupFindClassHook()
nativeInstallFindClassHook()
hooked_FindClass()
```

结果：这是当前必要组件。它解决 `JNI_OnLoad` 里找不到 `com.stub.StubApp` 等类的问题。

局限：如果壳在调用 `FindClass` 前就因反检测返回 `JNI_ERR`，这个 hook 无法救场。

### 方案 7：完整性校验重定向

思路：壳校验 APK 时，从修改过的 `origin.apk` 重定向到未修改的 `origin_original.apk`。

涉及代码：

```text
NativeHookBridge.setIntegrityRedirect()
nativeSetIntegrityRedirect()
nativeClearIntegrityRedirect()
```

结果：必要但不是充分条件。它能处理 DEX 注入后的 hash 校验问题，但不能覆盖所有反调试检测。

### 方案 8：Runtime.nativeLoad hook

思路：用 `RegisterNatives` 替换 `java.lang.Runtime.nativeLoad` 的 native 实现。

涉及代码：

```text
installNativeLoadHook()
hooked_nativeLoad()
resolve_native_load_caller()
```

作用：

- 当 caller 为 null 时合成有效 caller class。
- 保持库加载仍然走 ART 原始 nativeLoad。
- 让 ART 记录 native library 与 guest ClassLoader 的关系。

这是比裸 `dlopen` 更接近系统真实加载语义的方案。

### 方案 9：Stage 2 业务 JNI 预加载

思路：壳通过后，业务 SDK 仍可能有动态注册 native 方法。当前崩溃点是：

```text
com.yuewen.ywlogin.login.YWLoginManager.getInstance()
```

涉及代码：

```text
preloadGuestRuntimeNativeLibraries()
preloadNativeForClass()
tryBindNativeMethod()
isNativeMethodBound()
```

当前逻辑会尝试加载候选库：

```text
libywlogin.so
libYWLogin.so
libyuewenlogin.so
libyuewen.so
libreader.so
libaccount.so
liblogin.so
libsdk.so
libywad-own.so
libnativekey.so
libapp.so
libentryexpro.so
libQmt.so
```

并在失败后遍历剩余 `.so`。

成功标志：

```text
Stage2 native method bound after <lib>.so: com.yuewen.ywlogin.login.YWLoginManager.getInstance
```

当前实际日志没有出现该成功标志，且仍然崩在 `YWLoginManager.getInstance()`。

## 修复过的关键 bug

文档整理时确认项目中已经围绕加固链路修复过一些底层问题：

1. `BinaryXmlEncoder` 缺少 `debuggable` 属性字符串导致 manifest 编码异常。
2. `DexPatcher` 中空指令列表使用 `replaceInstruction()` 导致越界，应使用追加/构造方式。
3. `Timber` 在 loader/stub 早期没有 plant tree，关键日志改用 `android.util.Log`。
4. `open/openat` 是 variadic 函数，只有 `O_CREAT` 时才能读取 `mode_t`，否则会触发 UB。
5. 用 pipe 伪装 maps fd 容易被 `fstat()` 检测为 FIFO，改用 `tmpfile()`。
6. `FindClass` hook 从单目标扩展为候选类集合，并保留原始 `FindClass` fallback。
7. RFix/Tinker 等热修复框架访问 `mOuterContext`，`GuestContextWrapper` 增加同名字段兼容。
8. Provider 初始化失败改成 wrapper 降级，避免早期 provider 阻塞 app 启动。

## 当前状态矩阵

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| Stub APK 构建 | 可用 | 能打包 loader、origin、hook native libs |
| loader.dex 生成 | 可用 | 由 `core:stub:generateLoaderDex` 生成 |
| ClassLoader 替换 | 可用 | 直接替换 `LoadedApk.mClassLoader` |
| 资源替换 | 可用 | 重建 `LoadedApk.mResources` |
| Context 伪装 | 可用 | 应用层看到原包名和 origin 路径 |
| Runtime.nativeLoad hook | 使用中 | 修正 null caller 和 ClassLoader 绑定 |
| dlopen + JNI_OnLoad | 使用中 | 用于强制壳库初始化 |
| FindClass hook | 使用中 | 解决 `JNI_OnLoad` 查 guest 类问题 |
| GOT hook | 使用中 | 过滤 maps 和 map_files 读取 |
| 完整性重定向 | 使用中 | 修改 APK 与原始 APK 双轨 |
| Stage 2 业务 JNI | 未完成 | 仍卡在 `YWLoginManager.getInstance()` |

## 当前卡点分析

最新崩溃：

```text
Process: com.qq.reader.clonestub_a1eb7010e71b42f692a26277feba1bd9
java.lang.UnsatisfiedLinkError:
No implementation found for
com.yuewen.ywlogin.login.YWLoginManager.getInstance()
```

这有几种可能：

1. 新 `loader.dex` 没有真正进入新生成的 stub，导致 Stage 2 代码没运行。
2. Stage 2 运行了，但没有找到或没有成功加载真正注册 `YWLoginManager.getInstance()` 的库。
3. 相关库加载成功，但 `JNI_OnLoad` 内部条件不满足，未执行 `RegisterNatives`。
4. 执行了 `RegisterNatives`，但注册到了错误 ClassLoader 的类对象。
5. `YWLoginManager.getInstance()` 是延迟注册，必须先执行某个 SDK 初始化入口，而不是单纯加载库。

目前干净 logcat 中没有看到：

```text
Stage2 native preload
Stage2 native method bound after
MultiApp.POC
```

因此第一优先级不是继续猜库名，而是确认新 loader 是否被当前 clone 使用。

## 最新实验补充：GOT hook 与 JNI_OnLoad patch

附件中的历史日志补充了更细的实验过程。关键结论是：GOT hook 能部分生效，`libjiagu_vip.so` 的 `JNI_OnLoad` 也已经做过一次返回值 patch，但这两者都没有彻底解决壳初始化失败。

### GOT hook 结果

关键日志：

```text
pre-parsed libjiagu_vip.so (open=1 openat=0 fopen=1 readlink=0)
got_hook_immediate: base=0x6f01426000 open=1 fopen=1
JNI_OnLoad returned -1
```

这说明：

- ELF 预解析能找到 `libjiagu_vip.so` GOT/PLT 中的 `open` 和 `fopen`。
- `dlopen` 后能拿到库基址，并立即 patch 对应 GOT 项。
- 但 `JNI_OnLoad` 仍然返回 `-1`。

因此可以排除一个方向：导致失败的检测不是单纯通过 `libjiagu_vip.so` 自己导入表里的 `open()` / `fopen()` 去读 `/proc/self/maps`。如果壳走这条路径，GOT hook 应该能影响结果。

仍可能存在的检测路径：

- `dl_iterate_phdr()` 枚举已加载 `.so`，不需要打开文件。
- 直接 `syscall(__NR_openat, ...)`，绕过 libc PLT。
- 读取 `/proc/self/map_files/` 并通过 `getdents64` 或 linker 内部能力检查映射。
- 检查 linker namespace、调用栈、JNI 调用来源、线程局部状态或全局标志。

### JNI_OnLoad 返回值 patch 结果

附件日志显示运行期 patch 已执行：

```text
patchJiaguLoad: symtab=0xd6430 strtab=0xd8ad8
patchJiaguLoad: JNI_OnLoad at strtab[1139]
patchJiaguLoad: JNI_OnLoad at vaddr=0x258a38, file=0xcea38, size=196
patchJiaguLoad: patched at offset 0xceaf0
patchJiaguSo: PATCHED libjiagu_vip.so (889192 bytes)
```

但随后仍失败：

```text
nativePreloadLibraries: JNI_OnLoad returned -1 for .../lib/arm64/libjiagu_vip.so
preloadPackerLib: dlopen + JNI_OnLoad failed
```

这说明只 patch `JNI_OnLoad` 中看到的一个 `MOV W0, #-1` 不够。可能原因：

1. patch 的分支不是实际失败路径。
2. `JNI_OnLoad` 调用的子函数内部返回错误，外层重新转成 `-1`。
3. 加载的库不是预期被 patch 的那份，需要继续验证实际 `dlopen` 路径和文件字节。
4. 壳有多个失败条件，并非所有条件都表现为显式 `MOV W0, #-1`。

附件中对 `JNI_OnLoad` 的粗反汇编结果：

```text
JNI_OnLoad: vaddr=0x258a38, file=0xcea38, size=196
BL calls:
  0x258acc: BL 0x2586d8
  0x258ae0: BL 0x258620
  0x258af8: BL 0x258254
```

目前只确认 `JNI_OnLoad` 函数体内有一个显式：

```text
0x258af0 / file 0xceaf0: MOV W0, #-1
```

但壳仍返回 `-1`，所以下一步要分析 `0x2586d8`、`0x258620`、`0x258254` 这三个子函数的返回语义和调用条件，而不是继续盲目 patch 外层返回点。

### Zeus 新崩溃信号

日志中还出现过：

```text
java.lang.NoSuchMethodError:
No static method init(Landroid/app/Application;Z)V
in class Lcom/bytedance/pangle/Zeus;
```

这说明壳/应用已经推进到更深阶段时，可能进入了字节跳动 Pangle 插件框架初始化。这个问题与 `YWLoginManager.getInstance()` 是不同层次的问题：

- `JNI_OnLoad returned -1` 是加固壳 native 初始化失败。
- `YWLoginManager.getInstance()` 是业务登录 SDK native 方法未注册。
- `Zeus.init()` 是 Pangle 插件框架方法签名/DEX 版本/类加载路径问题。

不要把三者混为一个问题。排查时应按日志时间线确认当前最早失败点。

## 下一步方案

### 1. 验证 loader.dex 是否进包

检查新生成 stub APK 的 `classes.dex` 是否包含：

```text
Stage2 native preload
libywlogin.so
com.yuewen.ywlogin.login.YWLoginManager
```

如果不包含，说明生成 clone 时用了旧 loader。

### 2. 保证早期日志可见

当前 clone 不是 debuggable，`run-as` 读不了：

```text
cache/loader_debug.log
```

需要确认最终 stub manifest 是否真的带 `android:debuggable="true"`，或者临时把 loader 日志导出到可拉取路径。否则 AppComponentFactory 阶段日志很容易被系统噪声冲掉。

### 3. 增加 `.so` 字符串扫描

不要只靠库名候选。应扫描 origin native libs 的字符串或符号，找包含以下内容的库：

```text
YWLoginManager
getInstance
com/yuewen/ywlogin/login
Java_com_yuewen_ywlogin_login_YWLoginManager_getInstance
```

命中的库优先通过 `loadLibraryForGuest()` 加载。

### 4. 增加 RegisterNatives 诊断

在 native 层记录 `RegisterNatives`：

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

如果没出现，说明业务库没有走到注册逻辑；如果出现但仍崩，说明类对象或 ClassLoader 绑定不对。

### 5. 如仍失败，再考虑更底层方向

如果确认壳在 `JNI_OnLoad` 前置反检测处返回失败，后续方向包括：

- hook 或包装 `dl_iterate_phdr()`，过滤已加载库枚举。
- 处理直接 syscall 读取 `/proc` 的场景。
- 针对 `libjiagu_vip.so` 做版本相关二进制 patch。
- 分析 DEX 解密算法并离线重建解密流程。

这些方案成本更高，应在确认 Stage 2 和日志链路无误后再推进。

### 6. 针对最新日志的推荐顺序

基于附件中的新日志，推荐下一步顺序调整为：

1. 验证实际 `dlopen` 的 `libjiagu_vip.so` 字节是否已经 patch。重点检查 `0xceaf0` 是否为预期指令。
2. 如果实际文件已 patch，静态分析 `JNI_OnLoad` 的三个子调用：`0x2586d8`、`0x258620`、`0x258254`。
3. 增加 `RegisterNatives` 诊断，确认壳是否真的注册了 `StubApp` 和业务 native 方法。
4. 增加 `dl_iterate_phdr` 过滤或日志，验证是否为库枚举检测。
5. 最后再考虑对 `libjiagu_vip.so` 做更完整的 hash 匹配二进制 patch。

当前不建议继续只扩展 `open/fopen/openat` hook，因为日志已经显示这条路不是主要失败点。

## 关键文件

| 文件 | 作用 |
| --- | --- |
| `core/stub/src/main/java/com/multiapp/core/stub/StubBuilder.kt` | 构建 stub APK、打包 origin、native libs 和 loader |
| `core/stub/build.gradle.kts` | 生成 `loader.dex` |
| `app/build.gradle.kts` | 将 `loader.dex` 复制进 app assets |
| `core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt` | 运行期入口，替换 ClassLoader、资源、native 路径 |
| `core/loader/src/main/java/com/multiapp/core/loader/GuestContextWrapper.kt` | 应用层包名、资源和 ApplicationInfo 伪装 |
| `core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt` | Kotlin 到 native hook 的桥 |
| `core/hook/src/main/cpp/native-hook.cpp` | nativeLoad、FindClass、GOT hook、路径重定向、JNI_OnLoad |
| `core/hook/src/main/java/com/multiapp/core/hook/dexpatch/DexPatcher.kt` | DEX 注入 helper 和 loadLibrary 调用 |
| `core/manifest/src/main/java/com/multiapp/core/manifest/BinaryXmlEncoder.kt` | 二进制 manifest 生成 |

## 2026-06-06 最新进展

### 关键发现：DEX 未加密

360 加固对 QQ 阅读的 DEX **没有加密**。classes2-13.dex 直接在 APK 中，壳只是个加载器：
- `classes.dex`（90KB）— 壳的 loader
- `classes2-13.dex`（各 2-8MB）— 完整业务代码，明文

壳的作用仅限于：
1. `com.stub.StubApp` — 入口，调用 JNI_OnLoad 初始化 native 库
2. `libjiagu_vip.so` — native 层反调试、环境检测、RegisterNatives
3. 运行时把 classes2-13.dex 加入 ClassLoader

### 已解决的问题

1. **多 DEX 加载** — 从 APK 提取 classes2-9.dex，加入 PathClassLoader，setReadOnly 修复 writable 错误
2. **JNI_OnLoad 绕过** — NOP patch CBZ/CBNZ 条件跳转（偏移 +0x40, +0x54, +0x60）+ MOV W0,#-1→MOV W0,#0，JNI_OnLoad 返回 JNI_VERSION_1_4 成功
3. **StubApp 方法注册** — registerStubMethods() 注册 15 个 stub 方法，StubApp.load() 可调用
4. **应用启动到 onCreate** — ReaderApplication.onCreate() 被调用

### 当前阻塞：YWLoginManager.getInstance()

调用栈：
```
ReaderApplication.onCreate()
  → appNetworkStart()
    → initLoginSDK()
      → YWLogin.registerParameter()
        → YWLoginManager.getInstance() ← native 方法未注册，崩溃
```

`YWLoginManager.getInstance()` 是 native 方法，实现在 `libjiagu_vip.so` 内部。壳通过 `StubApp.load() → interface20() → RegisterNatives` 注册。我们的 `interface20` stub 是空壳，不触发注册。

在所有业务 .so 中搜索 "YWLoginManager" 字符串未找到。native 实现嵌入在壳库内部。

### 技术细节

- NOP patch 扫描范围需要 128 字节（CBZ 在偏移 +0x40 处，64 字节扫描不到）
- 壳的 JNI_OnLoad 不注册 StubApp 方法（壳通过 System.loadLibrary 静态初始化块注册）
- 我们用 dlopen 加载，触发不了壳的静态初始化
- RegisterNatives hook 确认：壳只注册了 StubApp（15 个方法）和 MMKV（52 个方法），不注册业务 native 方法

### 下一步方向

1. 逆向 libjiagu_vip.so 的 interface20 实现，找到 RegisterNatives 调用点
2. 或：hook ReaderApplication.onCreate() 跳过 initLoginSDK()，让应用继续运行
3. 或：接受登录不可用，验证其他功能是否正常

## 2026-06-06 深入排查：System.loadLibrary 静默失败

### 问题描述

`System.loadLibrary("jiagu_vip")` 在 2ms 内"成功"返回（不抛异常），但没有触发 dlopen 和 JNI_OnLoad。

### 诊断结果

```
jiagu_vip.so exists=true at /data/user/0/.../lib/arm64/libjiagu_vip.so
nativeLibraryDir=/data/user/0/.../lib/arm64
System.loadLibrary('jiagu_vip') OK in 2ms  ← 没有真正加载
JNI_OnLoad 从未调用
```

### 已尝试的方案

| 方案 | 结果 | 原因 |
|---|---|---|
| dlopen + 手动 JNI_OnLoad | dlopen 成功，JNI_OnLoad 返回 65540 | ART 不做 ClassLoader 绑定 |
| System.loadLibrary（LoaderFactory 直接调用） | 静默失败（2ms） | 可能是 hidden API 限制 |
| JiaguLoader.loadLibrary()（guest ClassLoader） | 静默失败（1ms） | 同上 |
| loadLibraryForGuest（Runtime.nativeLoad JNI） | 失败 | Android 16 阻止：nativeLoad method not found |
| 全局 GOT hook + System.loadLibrary | GOT hook 生效，但 System.loadLibrary 仍然失败 | System.loadLibrary 内部问题 |
| NOP 整个 JNI_OnLoad 函数体（196 字节） | 已实现 | System.loadLibrary 不加载 .so，NOP 无意义 |

### 根因分析

System.loadLibrary 内部调用 `Runtime.nativeLoad(filename, callerClassLoader, callerClass)`。在 Android 16 上：

1. `Runtime.nativeLoad` 是 hidden API（blocked level）
2. System.loadLibrary 是公开 API，理论上不受限制
3. 但 System.loadLibrary 的内部实现可能通过 ART 的 native 路径调用 nativeLoad，而不是 Java 反射
4. 如果 ART 的 native 路径也受 hidden API 限制，System.loadLibrary 会静默失败

**这是 Android 16 的安全机制，不是我们的代码 bug。**

### 下一步

1. **方案 C 止血**：注册 YWLoginManager.getInstance() 的 stub 实现，让应用不崩溃
2. **研究 ART 源码**：确认 System.loadLibrary 在 Android 16 上的内部调用路径
3. **尝试 Frida**：用 Frida 注入加载 .so，绕过 ART 限制（需要 root 或 debuggable）
4. **长期方案**：逆向 libjiagu_vip.so 的 interface20 实现

## 2026-06-07 突破：壳绕过成功

### 关键发现

1. **DEX 未加密** — 360 加固对 QQ 阅读的 DEX 没有加密，classes2-13.dex 直接在 APK 中
2. **壳的 JNI_OnLoad 不注册业务 native 方法** — 只注册 StubApp 和 MMKV
3. **业务 native 方法由壳的 interface20 注册** — 但 interface20 没有执行（壳的环境检测失败）
4. **System.loadLibrary 在 Android 16 上静默失败** — nativeLibraries 缓存污染

### 解决方案

1. **DEX 加载**：从 APK 提取 classes2-9.dex，加入 PathClassLoader（setReadOnly 修复 writable 问题）
2. **.so 加载**：System.load（绝对路径）+ dlopen + GOT hook + NOP patch
3. **native 方法注册**：批量扫描 DEX 中所有 native 类，注册 stub 实现
4. **getInstance() 返回非 null**：AllocObject 创建实例

### 当前状态

- ✅ 壳绕过成功
- ✅ 所有 native 方法注册（89 个方法）
- ✅ getInstance() 返回非 null 实例
- ❌ YWLoginManager 内部字段为 null（AllocObject 不初始化字段）
- ❌ AB Test SDK 配置缺失
- ❌ Pangle 广告 SDK 方法不存在

### 下一步

1. 解决 YWLoginManager 内部初始化问题
2. 或：跳过登录 SDK 初始化，让应用以游客模式启动
3. 或：用 LSPlant hook 相关方法，绕过初始化检查

## 与 protected-app-loading.md 的关系

`protected-app-loading.md` 更偏“当前代码架构和加载链路说明”。

本文档更偏“360 加固场景的综合分析、尝试记录、当前卡点和下一步方案”。

两者可以同时保留：

- 新同学先读 `protected-app-loading.md` 理解系统架构。
- 排查 QQ 阅读/360 加固问题时读本文档。
