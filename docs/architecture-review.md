# MultiApp 架构审查报告

> 审查人：高见远 (Gao) · 架构师  
> 日期：2025-07-01  
> 版本：v1.0

---

## 一、项目概览

MultiApp 是一个 Android 应用多开/分身框架，通过 Stub APK + Hook 注入 + 身份伪装 的方式实现应用克隆。核心流程为：

```
StubBuilder 构建 Stub APK
    → LoaderFactory (AppComponentFactory 入口) 在 Application 创建前注入
        → LoadedApkSwapper 替换 LoadedApk 使系统使用原始 APK 的 ClassLoader
            → HookEngine 初始化 LSPlant
                → Identity Hooks 安装（包名、设备信息、文件系统、签名等）
                    → 原始 Application 正常启动
```

---

## 二、模块划分审查

### 2.1 当前模块结构

| 模块 | 职责 | 依赖 |
|------|------|------|
| `core/model` | 数据模型 (VirtualApp, DeviceProfile, ApkInfo) | 无 |
| `core/common` | 工具类 (AndroidCompat, ReflectionUtils, CrashReporter) | 无 |
| `core/apk` | APK 解析 (ApkParser, VirtualClassLoader) | model, common |
| `core/manifest` | Manifest 解析/生成 + StubConfig 定义 | model, common, apk |
| `core/hook` | Hook 引擎 (HookEngine, NativeHookBridge, AntiDetectionEngine, IdentitySpoofingEngine, DexPatcher) | model, common |
| `core/identity` | 身份 Hook 实现 (PackageIdentityHook, DeviceIdentityHook, FileSystemHook, SignatureBypass 等) | model, common, hook |
| `core/loader` | APK 加载入口 (LoaderFactory, LoadedApkSwapper, NativeLibHandler) | model, common, hook, identity, manifest |
| `core/stub` | Stub APK 构建 (StubBuilder, ApkSigningHelper) | model, common, manifest, apk |
| `core/installer` | 安装器 (ShizukuInstaller, StubInstaller) | model, common |
| `core/instance` | 实例管理 (InstanceManager, InstanceDatabase) | model, common, manifest, identity, hook, stub, installer |

### 2.2 评估：模块划分基本合理，但存在以下问题

**✅ 优点：**
- 模块职责边界清晰：model/common 是基础设施，hook/identity/loader/stub 各司其职
- model 和 common 无项目依赖，作为稳定的底层
- feature 层与 core 层分离

**❌ 问题 1：StubConfig/DeviceIdentityConfig 定义位置不当**

`StubConfig` 和 `DeviceIdentityConfig` 定义在 `core/manifest/ManifestGenerator.kt` 文件底部，但它们是跨模块共享的配置数据结构，被 `loader`、`stub`、`identity`、`instance` 多个模块引用。

**影响：**
- `manifest` 模块本应只负责 Manifest 解析/生成，却承担了配置定义职责
- 语义不清：`StubConfig` 不是 Manifest 概念，而是 Stub 构建配置

**建议：** 将 `StubConfig`、`DeviceIdentityConfig` 移入 `core/model` 模块，model 本身就是数据定义的归属地。

---

**❌ 问题 2：hook 模块职责过重**

`core/hook` 包含了 5 个不同的子系统：
- HookEngine（LSPlant 封装）
- NativeHookBridge（native 层路径重定向）
- AntiDetectionEngine + antidetection/*（反检测系统）
- IdentitySpoofingEngine（身份伪装）
- DexPatcher + dexpatch/*（DEX 补丁）

这些子系统之间耦合度低，但被强制打包在同一模块中。

**影响：**
- 编译时间增加：改一个 antidetection bypass 需要重编整个 hook 模块（含 native CMake）
- 测试隔离性差：测试 antidetection 需要加载 NativeHookBridge 的 native 库
- 职责边界模糊：IdentitySpoofingEngine 和 core/identity 中的 DeviceIdentityHook 功能高度重叠

**建议：** 将 hook 模块拆分为：
- `core/hook-engine`：HookEngine + NativeHookBridge（核心 hook 基础设施）
- `core/anti-detection`：AntiDetectionEngine + antidetection/* + DexPatcher（反检测子系统）

---

**❌ 问题 3：IdentitySpoofingEngine 与 DeviceIdentityHook 功能重叠**

两个独立的身份伪装实现：

| 特性 | IdentitySpoofingEngine (core/hook) | DeviceIdentityHook (core/identity) |
|------|-----------------------------------|------------------------------------|
| Build 字段修改 | ✅ 完整（14个字段） | ❌ 无 |
| LSPlant 方法 hook | ✅ Telephony/WiFi/Bluetooth | ✅ Telephony/WiFi/Settings |
| Android ID 伪装 | ✅ Settings cache 注入 | ❌ 无 |
| 时区伪装 | ✅ TimeZone.setDefault() | ❌ 无 |
| TimePrison | ✅ 集成 | ❌ 无 |
| 使用方式 | Hilt 注入 | 静态 companion 调用 |

**影响：**
- LoaderFactory 调用 DeviceIdentityHook，不调用 IdentitySpoofingEngine
- 如果两个都被触发，同一方法会被 hook 两次，产生竞态
- IMEI/Android ID 生成算法不同（SHA-256 vs hashCode），同 instanceId 会产生不同值

**建议：** 统一为一个身份伪装引擎，删除 DeviceIdentityHook，保留功能更完善的 IdentitySpoofingEngine。

---

## 三、依赖关系审查（循环依赖检查）

### 3.1 模块依赖图

```
                    ┌──────────┐
                    │  model   │
                    └────┬─────┘
                         │
                    ┌────▼─────┐
                    │  common  │
                    └────┬─────┘
                         │
              ┌──────────┼──────────┐
              │          │          │
         ┌────▼────┐ ┌───▼────┐ ┌──▼──────┐
         │   apk   │ │  hook  │ │installer│
         └────┬────┘ └───┬────┘ └─────────┘
              │          │
         ┌────▼────┐     │
         │manifest │◄────┘ (间接: hook 依赖 model, manifest 依赖 model+apk)
         └────┬────┘
              │
         ┌────▼────┐
         │identity │ (depends on: model, common, hook)
         └────┬────┘
              │
         ┌────▼────┐
         │ loader  │ (depends on: model, common, hook, identity, manifest)
         └────┬────┘
              │
         ┌────▼────┐
         │  stub   │ (depends on: model, common, manifest, apk)
         └────┬────┘
              │
         ┌────▼────┐
         │instance │ (depends on: model, common, manifest, identity, hook, stub, installer)
         └─────────┘
```

### 3.2 结论：无循环依赖 ✅

Gradle 依赖图是 DAG（有向无环图），模块间没有直接或间接的循环依赖。这是良好的设计基础。

### 3.3 但存在隐式耦合 ⚠️

**HookEngine 的双重单例模式：**

```kotlin
@Singleton  // Hilt 注解
class HookEngine @Inject constructor() {
    companion object {
        @Volatile private var instance: HookEngine? = null
        fun getInstance(): HookEngine { ... }  // 手动单例
    }
}
```

`identity` 模块中的所有 Hook 实现都通过 `HookEngine.getInstance()` 获取实例，而不是通过 Hilt 注入。这意味着：
- Hilt 创建的实例 ≠ 手动单例实例（除非手动同步）
- LoaderFactory 在 AppComponentFactory 阶段运行，此时 Hilt 尚未初始化
- 必须依赖手动单例，但 Hilt 的 `@Singleton` 注解产生了误导

**建议：** 移除 `@Singleton @Inject constructor()` 注解，明确 HookEngine 是手动管理的全局单例。或在 LoaderFactory 阶段也通过 Hilt 入口获取。

---

## 四、核心流程时序审查

### 4.1 当前启动时序

```
[系统] instantiateApplication(cl, className)
  │
  ├─ 1. getActivityThread()                    ← 反射获取 ActivityThread
  ├─ 2. getBoundAppInfo(activityThread)        ← 反射获取 ApplicationInfo
  ├─ 3. readConfigFromAssets(stubApkPath)      ← 从 assets 读取 JSON 配置
  ├─ 4. extractOriginApk(...)                  ← 解压原始 APK 到 dataDir/cache/origin/
  ├─ 4.5 extractPatchedDex(...)                ← 解压 patched DEX（如果有）
  ├─ 5. LoadedApkSwapper.swap(...)             ← 替换 LoadedApk，返回 guestClassLoader
  ├─ 5.5 HookEngine.initLsplant(guestClassLoader) ← 初始化 LSPlant
  ├─ 6. installIdentityHooks(config)           ← 8 个 Hook 依次安装
  │     ├─ PackageIdentityHook.apply(config)
  │     ├─ DeviceIdentityHook.apply(config)
  │     ├─ BuildFieldSpoof.apply(config)
  │     ├─ FileSystemHook.apply(config)
  │     ├─ ProcFsHook.apply(config)
  │     ├─ ContentProviderHook.apply(config)
  │     ├─ ActivityManagerHook.apply(config)
  │     └─ DlopenHook.apply(config)
  ├─ 7. installSignatureBypass(config)         ← 签名绕过
  ├─ 8. appInfo.appComponentFactory = "android.app.AppComponentFactory"
  └─ 9. super.instantiateApplication(cl, className)  ← 原始 Application 开始创建
```

### 4.2 时序问题分析

**⚠️ 问题 1：NativeHookBridge 未在启动流程中初始化**

`NativeHookBridge.initNativeHooks()` 和 `NativeHookBridge.initialize()` 都不在 LoaderFactory 的启动流程中被调用。当前的 identity hooks 只使用了 LSPlant 层面的 hook，但：
- `ProcFsHook` 的 maps 过滤声明"委托给 native 层"，但 native 层未初始化
- `FileSystemHook` 只做了 Java 层 File 路径重写，没有走 NativeHookBridge 的 PathTrie

**影响：** /proc/self/maps 过滤不生效，加固壳的 native 层检测（直接 fopen 读 /proc/self/maps）无法拦截。

**建议：** 在 `installIdentityHooks` 之前增加 `NativeHookBridge.initNativeHooks(context)` 调用。

---

**⚠️ 问题 2：AntiDetectionEngine 从未在启动流程中被调用**

`AntiDetectionEngine` 是 Hilt 注入的 `@Singleton`，但 LoaderFactory 不走 Hilt 流程。没有任何代码在启动时调用 `antiDetectionEngine.enableAntiDetection(instanceId)`。

**影响：** Root 检测绕过、模拟器检测绕过、Xposed 检测绕过等全部未生效。

**建议：** 在 LoaderFactory 的 `installIdentityHooks` 中增加反检测引擎的初始化，或创建一个统一的启动编排器。

---

**⚠️ 问题 3：Hook 安装顺序隐含依赖**

当前安装顺序是：
1. PackageIdentityHook（包名伪装）
2. DeviceIdentityHook（设备信息伪装）
3. BuildFieldSpoof（Build 字段修改）
4. FileSystemHook（文件路径重写）
5. ProcFsHook（/proc 过滤）
6. ContentProviderHook（Provider authority 重写）
7. ActivityManagerHook（进程信息伪装）
8. DlopenHook（native 库加载重定向）

这个顺序没有文档化，也没有显式的依赖声明。如果某个 Hook 失败（catch 异常后继续），后续 Hook 可能基于错误的前提运行。

**建议：** 
- 为每个 Hook 增加 `priority` 属性
- 引入 `HookChain` 或 `HookPipeline` 概念，支持前置条件检查
- 关键 Hook 失败时应中断流程（而不是静默继续）

---

## 五、可扩展性审查

### 5.1 支持新加固厂商

**当前方案：**
- `PackerDetector.detect()` 通过 native lib 名称、DEX 类名、Manifest 组件三重检测
- `PackerDetectionBypass.apply()` 根据 packerType 分发到对应的 bypass 方法
- `DetectionSignatureDatabase` 存储 DEX 层面的特征码

**评估：** 设计思路正确，但扩展性不足：

1. **PackerDetector 是硬编码的 when 分支**：新增厂商需要修改 `detectByNativeLibs()`、`detectByClasses()`、`detectByManifest()` 三个方法
2. **PackerDetectionBypass 也是硬编码的 when 分支**：新增厂商需要添加新的 `bypassXxx()` 方法
3. **无插件机制**：第三方无法通过配置添加新厂商支持

**建议重构为策略模式：**

```kotlin
interface PackerStrategy {
    val packerName: String
    fun detect(zip: ZipFile): Boolean
    fun applyBypass(hookEngine: HookEngine)
}

class PackerRegistry {
    private val strategies = mutableListOf<PackerStrategy>()
    fun register(strategy: PackerStrategy) { strategies.add(strategy) }
    fun detect(apkPath: String): PackerStrategy? { ... }
}
```

### 5.2 支持新 Hook 类型

**当前方案：**
- HookEngine 支持 6 种 HookType：STATIC_FIELD、INSTANCE_FIELD、METHOD_PROXY、LSPLANT_METHOD、NATIVE_INLINE、NATIVE_PLT
- 但只实现了 STATIC_FIELD、INSTANCE_FIELD、LSPLANT_METHOD 三种
- NATIVE_INLINE 和 NATIVE_PLT 仅声明了枚举值，未实现

**评估：** LSPlant 的 ART 方法 hook + NativeHookBridge 的 native 层 hook 已覆盖大部分场景。但如果需要 inline hook（修改函数体中间指令），当前架构不支持。

**建议：**
- NativeHookBridge 已引入 shadowhook 依赖，应利用其 inline hook 能力
- 在 HookEngine 中增加 `hookNativeInline()` 方法封装 shadowhook
- 为 NATIVE_INLINE 类型增加 unhook 支持

### 5.3 支持新的身份伪装维度

**当前身份伪装覆盖范围：**

| 维度 | 状态 | 实现位置 |
|------|------|----------|
| 包名 | ✅ | PackageIdentityHook |
| IMEI/IMSI | ✅ | DeviceIdentityHook + IdentitySpoofingEngine |
| Android ID | ✅ | 两处实现（不一致） |
| MAC 地址 | ✅ | DeviceIdentityHook |
| Build 字段 | ✅ | BuildFieldSpoof + IdentitySpoofingEngine |
| 文件路径 | ✅ | FileSystemHook |
| /proc | ⚠️ 部分 | ProcFsHook（native 层未初始化） |
| ContentProvider | ✅ | ContentProviderHook |
| 进程信息 | ✅ | ActivityManagerHook |
| 签名 | ✅ | SignatureBypass |
| WiFi BSSID | ❌ | 未实现 |
| 蓝牙地址 | ⚠️ | IdentitySpoofingEngine 有 hook 但未接入启动流程 |
| 广告 ID | ⚠️ | IdentitySpoofingEngine 有方法但未调用 |
| 剪贴板 | ❌ | 未实现 |
| 传感器 | ❌ | 未实现 |

**建议：** 创建 `IdentityProfile` 抽象，将所有伪装维度组合为可配置的 profile，通过统一接口应用。

---

## 六、性能瓶颈审查

### 6.1 启动耗时分析

**关键路径耗时预估（按顺序）：**

| 步骤 | 操作 | 预估耗时 | 瓶颈等级 |
|------|------|----------|----------|
| extractOriginApk | 解压整个原始 APK | 500ms-2s | 🔴 高 |
| extractPatchedDex | 解压 patched DEX | 50-200ms | 🟡 中 |
| LoadedApkSwapper.swap | 反射 + 创建新 LoadedApk | 10-50ms | 🟢 低 |
| HookEngine.initLsplant | LSPlant 初始化（反射 + native） | 50-200ms | 🟡 中 |
| installIdentityHooks | 8 个 Hook 安装（约 40 个方法 hook） | 100-500ms | 🟡 中 |
| installSignatureBypass | 2-3 个方法 hook | 10-30ms | 🟢 低 |

**总启动额外耗时：约 700ms - 3s**（取决于原始 APK 大小和 Hook 数量）

**瓶颈 1：APK 解压在主线程**

`extractOriginApk()` 在 `instantiateApplication()` 中同步执行，直接阻塞 Application 创建。对于大型 APK（如游戏 500MB+），这可能导致 ANR。

**建议：** 
- 首次启动：后台线程解压 + 进度通知
- 非首次启动：直接使用已解压的 APK（当前已有 `if (output.exists())` 检查，这是好的）
- 可考虑使用 mmap 或直接从 assets 读取（不解压）

**瓶颈 2：LSPlant 初始化的反射开销**

`initLsplant()` 通过 `Class.forName("io.github.lsplant.LSPlant")` + 反射调用 `init()`。LSPlant 的 `hook()` 方法也通过反射调用。

**建议：** 如果 LSPlant 是编译期依赖，直接使用编译期绑定而非反射。当前的反射方式是为了兼容 LSPlant 未加载的情况，但可以优化为：
- 编译期：直接调用 LSPlant API
- 运行时：通过 `HiddenApiBypass` 规避 hidden API 限制

**瓶颈 3：Identity Hook 的同步安装**

8 个 Hook 串行安装，每个 Hook 内部又有 3-6 个方法 hook。总共约 40 次反射 + LSPlant 调用。

**建议：** 大部分 Hook 之间无依赖关系，可以并行安装（使用协程或线程池）。但需注意 LSPlant 的线程安全性。

### 6.2 内存占用分析

**NativeHookBridge 的内存开销：**

```kotlin
private val pathRedirections = ConcurrentHashMap<String, String>()  // 路径映射
private val pathTrie = PathTrie()  // 前缀树
private val pathCache = LinkedHashMap<String, String>(256, 0.75f, true)  // LRU 缓存，max 2048
private val propertyOverrides = ConcurrentHashMap<String, String>()  // 属性覆盖
private val hiddenPaths = ConcurrentHashMap.newKeySet<String>()  // 隐藏路径
private val fakeFileContent = ConcurrentHashMap<String, ByteArray>()  // 伪造文件内容
```

- `pathCache` 最多 2048 条，每条约 200 字节（路径字符串），总计约 400KB
- `fakeFileContent` 中 `/proc/self/mounts` 的内容约 500 字节
- `hiddenPaths` 约 50 条路径，约 5KB

**总计约 500KB-1MB**，可接受。

**HookEngine 的内存开销：**

```kotlin
private val installedHooks = mutableListOf<HookInfo>()  // Hook 信息列表
private val lsplantHooks = mutableMapOf<Executable, Any>()  // LSPlant Unhook 对象
```

- 40 个 Hook × 约 200 字节 = 约 8KB

**可接受。**

**建议：** 无显著内存问题。

### 6.3 运行时性能

**PathTrie.translate() 热路径：**

```kotlin
fun translate(path: String): String? {
    var node = root; var bestReplacement: String? = null; var bestPrefixLength = 0
    for (ch in path) {
        val child = node.children[ch] ?: break
        node = child
        if (node.replacement != null && node.prefixLength > bestPrefixLength) {
            bestReplacement = node.replacement; bestPrefixLength = node.prefixLength
        }
    }
    return bestReplacement?.let { it + path.substring(bestPrefixLength) }
}
```

时间复杂度 O(n)，n 为路径长度。对于典型路径（约 50 字符），性能良好。

**但 `translatePath()` 使用 `synchronized(pathCacheLock)`：**

```kotlin
fun translatePath(originalPath: String): String {
    if (hiddenPaths.contains(originalPath)) return "/dev/null"
    synchronized(pathCacheLock) {
        pathCache[originalPath]?.let { return it }
        val result = pathTrie.translate(originalPath) ?: originalPath
        pathCache[originalPath] = result
        return result
    }
}
```

**问题：** `addPathRedirection()` 调用 `rebuildPrefixIndex()` 清空 pathTrie，但没有获取 `pathCacheLock`。如果一个线程在 `translatePath()` 中（持有 pathCacheLock），另一个线程调用 `addPathRedirection()` 清空 pathTrie，可能导致不一致。

**建议：** 将 `rebuildPrefixIndex()` 也放在 `synchronized(pathCacheLock)` 中，或使用 ReadWriteLock。

---

## 七、安全架构审查

### 7.1 多层 Hook 协同

**当前架构的 Hook 层次：**

```
┌─────────────────────────────────────────────────┐
│ 层 1: Java Method Hook (LSPlant)                │
│   - Context.getPackageName()                     │
│   - TelephonyManager.getDeviceId()               │
│   - PackageManager.getPackageInfo()              │
│   - File(String) constructor                     │
│   - ContentResolver.query/insert/update/delete   │
│   - Class.forName / ClassLoader.loadClass        │
└─────────────────────────────────────────────────┘
         │
┌─────────────────────────────────────────────────┐
│ 层 2: Java Field Modification (Reflection)      │
│   - Build.MODEL, Build.BRAND, Build.FINGERPRINT │
│   - Build.VERSION.SDK_INT                        │
│   - ApplicationInfo.packageName                  │
└─────────────────────────────────────────────────┘
         │
┌─────────────────────────────────────────────────┐
│ 层 3: Native Hook (NativeHookBridge)            │
│   - /proc/self/maps 过滤                         │
│   - /proc/self/cmdline 伪装                      │
│   - 路径重定向 (PathTrie)                         │
│   - System property 伪装                         │
│   - Runtime.nativeLoad 拦截                      │
└─────────────────────────────────────────────────┘
         │
┌─────────────────────────────────────────────────┐
│ 层 4: DEX Patch (DexPatcher)                    │
│   - 加固壳检测方法空实现化                          │
└─────────────────────────────────────────────────┘
```

**评估：分层合理，但层间协同不足**

**问题 1：层 1 和层 2 的 HookEngine 统一管理，但层 3 独立**

LSPlant 的方法 hook 和反射的字段修改都通过 HookEngine 管理（`installedHooks` 列表），支持 `unhookAll()`。但 NativeHookBridge 的 hook 是独立管理的（`nativeCleanup()`），两套清理机制不同步。

**影响：** 如果只调用 `HookEngine.unhookAll()`，native 层的路径重定向、属性伪装等不会被清理。

**建议：** 将 NativeHookBridge 的生命周期纳入 HookEngine 管理，或创建统一的 `HookManager` 协调所有层。

**问题 2：层 4 DEX Patch 的结果与层 1 可能冲突**

DexPatcher 将加固壳的检测方法替换为空实现，但这些方法可能也被 LSPlant hook 了。如果 LSPlant hook 了同一个方法，hook 回调可能期望原始行为，但实际已被 patch 为空。

**建议：** DEX Patch 和 LSPlant Hook 应有明确的优先级和互斥规则。

### 7.2 检测对抗能力评估

**对抗加固壳检测的覆盖矩阵：**

| 检测手段 | 360加固 | 腾讯乐固 | iJiami | Bangcle | 通用 |
|----------|---------|----------|--------|---------|------|
| /proc/self/maps 扫描 | ⚠️ Java层过滤 | ⚠️ Java层过滤 | ⚠️ Java层过滤 | ⚠️ Java层过滤 | ⚠️ Java层过滤 |
| Xposed 类检测 | ✅ Class.forName 拦截 | ✅ Class.forName 拦截 | ✅ Class.forName 拦截 | ✅ Class.forName 拦截 | ✅ Class.forName 拦截 |
| Root 二进制检测 | ✅ 路径隐藏 | ✅ 路径隐藏 | ✅ 路径隐藏 | ✅ 路径隐藏 | ✅ 路径隐藏 |
| 模拟器检测 | ✅ Build 属性伪装 | ✅ Build 属性伪装 | ✅ Build 属性伪装 | ✅ Build 属性伪装 | ✅ Build 属性伪装 |
| 签名验证 | ✅ SignatureBypass | ✅ SignatureBypass | ✅ SignatureBypass | ✅ SignatureBypass | ✅ SignatureBypass |
| Debuggable 检查 | ✅ 系统属性伪装 | ✅ 系统属性伪装 | ✅ 系统属性伪装 | ✅ 系统属性伪装 | ✅ 系统属性伪装 |
| SELinux 状态检查 | ⚠️ 部分 | ⚠️ 部分 | ⚠️ 部分 | ⚠️ 部分 | ⚠️ 部分 |

**⚠️ 关键缺口：/proc/self/maps native 层过滤未生效**

如前所述，NativeHookBridge 未在启动流程中初始化，导致：
- Java 层的 ProcFsHook 只 hook 了 FileInputStream（容易被绕过）
- 加固壳通常直接用 `fopen()` 或 `open()` 系统调用读取 /proc/self/maps
- Native 层的 `hooked_fopen` 未安装，maps 中的 hook 框架痕迹完全暴露

**这是当前架构最严重的安全缺陷。**

**建议：** 
1. 在 LoaderFactory 启动流程中初始化 NativeHookBridge
2. 确保 native hook 在 identity hooks 之前安装
3. 增加 /proc/self/status、/proc/self/smaps 等更多 proc 文件的过滤

### 7.3 反检测自测试

`AntiDetectionEngine.runSelfTest()` 是一个很好的设计，但由于 AntiDetectionEngine 从未被初始化，自测试也无法运行。

**建议：** 在应用启动后（Application.onCreate）自动运行一次自测试，并将结果记录到日志，便于调试。

---

## 八、代码质量问题

### 8.1 HookPoint 接口不一致

```kotlin
// 接口定义
interface HookPoint {
    fun apply(config: IdentityConfig, hookEngine: HookEngine)  // 两个参数
}

// 所有实现
class DeviceIdentityHook : HookPoint {
    override fun apply(config: IdentityConfig) { ... }  // 一个参数！
}
```

**影响：** 代码无法编译（除非有其他重载）。实际运行的可能是 companion object 的 `apply(config)` 方法，绕过了接口。

**建议：** 统一接口签名，将 HookEngine 作为构造参数传入或通过 companion 的 `getInstance()` 获取。

### 8.2 硬编码路径

```kotlin
// SignatureBypass.kt
val archivePath = "/data/app/$originalPkg-1/base.apk"  // 硬编码 -1 后缀
```

不同设备上 APK 安装路径的后缀可能是 -1、-2、-3 等。应通过 `PackageManager.getPackageInfo().applicationInfo.sourceDir` 获取。

### 8.3 异常处理过于宽泛

几乎所有 Hook 安装都使用 `try-catch(Exception)` 包裹，失败后仅 log 然后继续。这导致：
- Hook 安装失败被静默忽略
- 应用在不完整的 Hook 环境中运行，行为不可预测
- 调试困难：需要逐个检查日志才能发现哪个 Hook 失败

**建议：** 
- 关键 Hook（如 PackageIdentityHook、SignatureBypass）失败应中断启动
- 非关键 Hook（如 BuildFieldSpoof）可以降级处理
- 引入 Hook 安装结果报告机制

### 8.4 日志泄露风险

```kotlin
Timber.tag(TAG).d("Hooked TelephonyManager.getDeviceId()")  // 正常
Timber.tag(TAG).d("ANDROID_ID for instance $instanceId: $spoofedId")  // ⚠️ 泄露伪装值
Timber.tag(TAG).d("Telephony spoofed for instance $instanceId: IMEI=$imei")  // ⚠️ 泄露伪装值
```

在 release 版本中，这些日志可能被加固壳读取（通过 logcat），暴露虚拟环境的存在。

**建议：** 
- 使用 `Timber.tag(TAG).d()` 时，release 版本应禁用或脱敏
- 考虑使用 `BuildConfig.DEBUG` 控制日志级别
- 伪装值等敏感信息不应出现在日志中

---

## 九、架构改进建议总结

### 优先级 P0（必须修复）

| # | 问题 | 建议 | 影响 |
|---|------|------|------|
| 1 | NativeHookBridge 未在启动流程中初始化 | 在 LoaderFactory 中调用 initNativeHooks() | /proc/self/maps 过滤失效，加固壳检测必过 |
| 2 | AntiDetectionEngine 未接入启动流程 | 在 LoaderFactory 中启用反检测 | Root/模拟器/Xposed 检测全部暴露 |
| 3 | HookPoint 接口签名不一致 | 统一为 `fun apply(config: IdentityConfig)` | 编译错误或行为不确定 |
| 4 | HookEngine 双重单例模式 | 移除 Hilt 注解或统一为一种模式 | DI 实例不一致 |

### 优先级 P1（重要改进）

| # | 问题 | 建议 | 影响 |
|---|------|------|------|
| 5 | IdentitySpoofingEngine 与 DeviceIdentityHook 重叠 | 统一为一个实现 | 行为冲突、维护成本 |
| 6 | StubConfig 定义位置不当 | 移入 core/model | 跨模块引用不便 |
| 7 | NativeHookBridge 的 pathCacheLock 竞态 | 使用 ReadWriteLock 或扩大同步范围 | 数据不一致 |
| 8 | APK 解压阻塞主线程 | 异步化 + 缓存检查 | 大 APK 启动 ANR |
| 9 | SignatureBypass 硬编码 APK 路径 | 通过 PackageManager 获取 | 路径不匹配导致签名绕过失败 |

### 优先级 P2（长期优化）

| # | 问题 | 建议 | 影响 |
|---|------|------|------|
| 10 | Hook 模块职责过重 | 拆分为 hook-engine + anti-detection | 编译效率、测试隔离 |
| 11 | 加固厂商支持硬编码 | 重构为策略模式 | 扩展性 |
| 12 | Hook 安装无优先级和失败处理 | 引入 HookPipeline + 优先级 | 稳定性 |
| 13 | 日志泄露敏感信息 | release 版本脱敏 | 安全风险 |

---

## 十、推荐的目标架构

```
┌─────────────────────────────────────────────────────────────┐
│                    core/model                                │
│  VirtualApp, DeviceProfile, StubConfig, IdentityConfig,     │
│  ApkInfo, ProcessSlot, ...                                  │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────┼───────────────────────────────┐
│                    core/common                               │
│  AndroidCompat, ReflectionUtils, CrashReporter              │
└─────────────────────────────┬───────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
┌───────▼───────┐   ┌────────▼────────┐   ┌───────▼───────┐
│  core/apk     │   │ core/hook-engine│   │core/installer │
│  ApkParser,   │   │ HookEngine,     │   │ShizukuInstaller│
│  VClassLoader │   │ NativeHookBridge│   │StubInstaller  │
└───────┬───────┘   └────────┬────────┘   └───────────────┘
        │                    │
┌───────▼───────┐   ┌────────▼────────┐
│core/manifest  │   │core/anti-detect │
│ManifestParser,│   │AntiDetectEngine,│
│ManifestGen    │   │PackerDetector,  │
│ComponentExt   │   │DexPatcher       │
└───────┬───────┘   └────────┬────────┘
        │                    │
        └────────┬───────────┘
                 │
┌────────────────▼────────────────────────────────────────────┐
│                    core/identity                             │
│  HookPipeline (统一编排器)                                    │
│    ├─ IdentitySpoofingEngine (唯一身份伪装实现)               │
│    ├─ PackageIdentityHook                                    │
│    ├─ FileSystemHook                                         │
│    ├─ ProcFsHook                                             │
│    ├─ ContentProviderHook                                    │
│    ├─ ActivityManagerHook                                    │
│    ├─ DlopenHook                                             │
│    └─ SignatureBypass                                        │
└────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────┐
│                    core/loader                               │
│  LoaderFactory (启动编排)                                     │
│    1. NativeHookBridge.initNativeHooks()                    │
│    2. AntiDetectionEngine.enableAntiDetection()             │
│    3. LoadedApkSwapper.swap()                               │
│    4. HookEngine.initLsplant()                              │
│    5. HookPipeline.installAll(config)                       │
└─────────────────────────────────────────────────────────────┘
```

---

## 附录：完整模块依赖矩阵

```
             model common apk manifest hook identity loader stub installer instance
model          -     
common         -     -    
apk            ✗     ✗    -   
manifest       ✗     ✗    ✗    -    
hook           ✗     ✗              -   
identity       ✗     ✗         ✗     -  
loader         ✗     ✗    ✗     ✗    ✗    -   
stub           ✗     ✗    ✗     ✗         -  
installer      ✗     ✗                   -  
instance       ✗     ✗    ✗     ✗    ✗    ✗    ✗    -  
```

✗ = 有依赖，- = 自身
