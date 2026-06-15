# MultiApp

**Stub-Load + Identity Proxy** — 无需 root 的 Android 应用多开方案。

支持加固应用、签名认证、每实例独立设备身份。目标平台 Android 10+ (API 28+)。

## 架构概览

```
┌─────────────────────────────────────────────────┐
│              MultiApp 主进程                      │
│   Compose UI + 实例管理 + Manifest 引擎          │
└──────────────────┬──────────────────────────────┘
                   │ PackageInstaller.Session API
                   ▼
┌─────────────────────────────────────────────────┐
│            Stub APK (每个实例一个)                 │
│  真实 Android 应用 — 真实 UID / 数据目录 / 进程    │
│  android:appComponentFactory = "LoaderFactory"   │
│  assets/origin.apk = 原始 APK 完整副本            │
│  AndroidManifest.xml = 原始 app 全部组件声明       │
└──────────────────┬──────────────────────────────┘
                   │ appComponentFactory 自动注入
                   ▼
┌─────────────────────────────────────────────────┐
│            LoaderFactory (借鉴 LSPatch)           │
│  1. instantiateApplication() 回调                │
│  2. 解压 assets/origin.apk                       │
│  3. 替换 LoadedApk (ClassLoader + Resources)     │
│  4. 安装身份 Hook (LSPlant)                      │
│  5. 安装签名绕过 Hook                             │
│  6. 重置 appComponentFactory                     │
│  7. 原始 Application 正常创建                     │
└──────────────────┬──────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────┐
│         原始 APK 代码 (正常运行)                   │
│  原始 ClassLoader / Resources / Application      │
│  Service/Receiver 在所有进程正常启动               │
│  加固壳正常解包 / 签名验证通过                     │
│  每个实例看到独立的设备身份                        │
└─────────────────────────────────────────────────┘
```

### 核心机制

| 机制 | 说明 |
|------|------|
| **注入点** | `AppComponentFactory.instantiateApplication()` — 在 `Application.attachBaseContext()` 之前执行 |
| **APK 加载** | 替换 `ActivityThread.mPackages` 中的 `LoadedApk`，而非 `DexClassLoader` |
| **资源加载** | `LoadedApk` 自动包含 `Resources`，无需手动 `AssetManager` Hook |
| **多进程** | `appComponentFactory` 在每个进程的类加载时自动执行，无需 `BIND_APPLICATION` Hook |
| **原始 app** | 嵌入 Stub 的 `assets/`，运行时解压，独立运行，不依赖原始 app 安装 |
| **签名绕过** | Hook `PackageParser.generatePackageInfo` + 签名块复制双重保障 |

## 模块结构

```
multiapp/
├── app/                         # 主应用入口
├── core/
│   ├── model/                   # 数据模型 (VirtualApp, ApkInfo, DeviceProfile)
│   ├── common/                  # 工具类 (ReflectionUtils, CrashReporter)
│   ├── designsystem/            # Material 3 主题
│   ├── apk/                     # APK 解析 (ApkParser, VirtualClassLoader)
│   ├── hook/                    # LSPlant ART Hook 引擎 + DexPatcher
│   ├── manifest/                # 二进制 XML 解析与组件提取
│   ├── identity/                # 身份代理层 (9 个 Hook 维度)
│   ├── loader/                  # LoaderFactory + LoadedApk 替换
│   ├── stub/                    # Stub APK 构建器
│   ├── instance/                # 实例管理 (Room 数据库)
│   └── installer/               # PackageInstaller / Shizuku 安装
└── feature/
    ├── launcher/                # 主界面 — 实例列表 + 应用选择器
    ├── appmanager/              # 应用管理
    └── settings/                # 设置
```

## 身份代理 (9 维度, 40+ Hook 点)

| 维度 | Hook 点 | 说明 |
|------|---------|------|
| 包名身份 | 5 | `Context.getPackageName()`, `ApplicationInfo.packageName` 等 |
| 签名伪装 | 2 | `PackageManager.getPackageInfo(GET_SIGNATURES)` |
| 设备标识 | 6 | IMEI, Android ID, MAC, Serial 等 |
| 系统属性 | 10+ | `Build.MODEL`, `FINGERPRINT`, `VERSION` 等 (含 native) |
| 文件系统 | 6 | `File` 构造函数, `getFilesDir()`, `getDatabasePath()` 等 |
| /proc 文件 | 2 | `/proc/self/cmdline`, `/proc/self/maps` 读取拦截 |
| ContentProvider | 6 | `ContentResolver` 全方法 Hook + authority 重写 |
| ActivityManager | 3 | `getRunningAppProcesses()` 返回值伪装 |
| Native 库 | 1 | `dlopen` 路径重定向 |

## 加固应用绕过原理 (以 360 加固为例)

整个过程**不需要 root、不需要脱壳、不需要虚拟机**。壳和 MultiApp 的代码跑在同一个进程、同一个地址空间里，改的是自己进程的内存，不涉及跨进程注入。

```
系统启动 Stub 进程
        │
        ▼
AppComponentFactory.instantiateApplication()
  ← 此时 Application 尚未创建，ApplicationInfo 已设置
        │
        ▼
┌─ Step 1: Native 层拦截 (shadowhook) ─────────────┐
│  PLT/GOT Hook 拦截 libc 函数:                      │
│  • open() / fopen() — APK 路径重定向到原始 APK     │
│  • read() — 过滤 /proc/self/maps 中 hook 框架痕迹  │
│  • ptrace() — 返回"未被调试"                        │
│  目标: 让壳的 libsec.so 所有环境检测全部通过         │
└───────────────────────────────────────────────────┘
        │
        ▼
┌─ Step 2: 替换 ClassLoader + LoadedApk ────────────┐
│  修改 ActivityThread.mPackages                     │
│  ClassLoader → 原始 APK 的 ClassLoader             │
│  Resources   → 原始 APK 的 Resources               │
│  系统认为进程就是目标 app                           │
└───────────────────────────────────────────────────┘
        │
        ▼
┌─ Step 3: Java 层身份伪装 (LSPlant) ───────────────┐
│  • Build.MODEL / FINGERPRINT / MANUFACTURER        │
│  • Settings.Secure.ANDROID_ID                      │
│  • TelephonyManager.getDeviceId() / getImei()      │
│  • WifiInfo.getMacAddress() / Build.getSerial()    │
│  • Binder 拦截 → 返回原始 APK 签名                  │
└───────────────────────────────────────────────────┘
        │
        ▼
重置 appComponentFactory 为默认值 (防壳检测异常)
        │
        ▼
系统继续创建 Application → 360 壳正常执行
        │
        ├─ System.loadLibrary("sec") → 加载 native 库 ✓
        ├─ JNI 解密 DEX → 壳解密流程正常 ✓
        ├─ 环境检测:
        │    ptrace() → "未被调试" ✓
        │    /proc/self/maps → 无 hook 痕迹 ✓
        │    APK 路径 → 指向原始 APK ✓
        │    Build.MODEL → 真实设备信息 ✓
        │    签名验证 → 原始签名 ✓
        │    全部通过 ✓
        │
        ▼
壳解密完成 → 原始 app 代码正常运行
```

**关键点**: 所有拦截在 `instantiateApplication()` 中一步完成，时序上**早于**壳的任何初始化代码。壳运行时，拦截层已经就位，壳拿到的全是伪装后的数据。

## LSPatch 兼容

MultiApp 借鉴 LSPatch 的 `appComponentFactory` 注入方案，同时在以下方面做了扩展：

| 特性 | LSPatch | MultiApp |
|------|---------|----------|
| 注入方式 | `AppComponentFactory` | 同 |
| ART Hook | LSPlant | LSPlant + ShadowHook |
| Hidden API 绕过 | HiddenApiBypass | 同 (内嵌 loader.dex) |
| Native Hook | 无 | GOT hook (libc 拦截) |
| 身份伪装 | 无 | 9 维度 40+ Hook 点 |
| 应用虚拟化 | 无 | Stub APK 独立实例 |
| Xposed API | 无 | 内置兼容层 |

LSPlant 初始化已对齐 LSPatch 时机：在 `JNI_OnLoad` 中完成，早于壳的任何初始化代码。

## Xposed 模块使用

MultiApp 内置 Xposed API 兼容层，支持在分身进程中加载 Xposed 模块。

### 支持的 API

| 类 | 说明 |
|----|------|
| `XposedBridge` | 方法 hook/unhook、原始方法调用 |
| `XC_MethodHook` | before/after 回调、优先级排序 |
| `XC_LoadPackage` | 包加载参数 (packageName, classLoader, appInfo) |
| `XposedHelpers` | findAndHookMethod 等便利方法 |
| `IXposedHookLoadPackage` | 标准模块入口接口 |

### 模块加载流程

```text
1. ModuleLoader 读取模块 APK 的 assets/xposed_init
2. InMemoryDexClassLoader 加载模块 DEX
3. 实例化 IXposedHookLoadPackage / XC_LoadPackage
4. 分身进程启动时 dispatchLoadPackage() 通知所有模块
```

### 代码示例

```kotlin
// 在分身进程中 hook 方法
XposedBridge.hookMethod(
    SomeClass::class.java.getDeclaredMethod("someMethod"),
    object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // 修改参数或阻止原方法执行
        }
        override fun afterHookedMethod(param: MethodHookParam) {
            // 修改返回值
        }
    }
)
```

## 技术栈

- **语言**: Kotlin (Jetpack Compose + Material 3)
- **DI**: Hilt
- **数据库**: Room
- **Native Hook**: shadowhook (PLT/GOT 拦截)
- **Java Hook**: LSPlant (ART method hooking)
- **DEX 操作**: dexlib2 2.5.2
- **构建**: Gradle Kotlin DSL, KSP
- **最低版本**: Android 10 (API 28)

## 构建

```bash
# Debug 构建
./gradlew assembleDebug

# 安装 (需 ADB)
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 已知限制

- `android:sharedUserId` — 分身无法与其他共享 UID 的 app 通信
- PackageInstaller 安装需用户确认 (Shizuku 可选静默安装)
- Stub APK 体积包含原始 APK (通常 100-400MB)
- 部分加固壳可能检测 `/proc/self/cmdline` (已通过 ProcFsHook 处理)

## 致谢

核心架构借鉴 [LSPatch](https://github.com/LSPosed/LSPatch) 的 `appComponentFactory` 注入和 `LoadedApk` 替换方案。

## License

MIT
