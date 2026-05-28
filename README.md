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

## 技术栈

- **语言**: Kotlin (Jetpack Compose + Material 3)
- **DI**: Hilt
- **数据库**: Room
- **Hook**: LSPlant (ART method hooking)
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
