# MultiApp 架构文档

## 项目架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        app (主应用)                              │
│  Compose UI · Hilt · Navigation · 实例管理入口                   │
└───────────────────────────┬─────────────────────────────────────┘
                            │
┌───────────────────────────┼─────────────────────────────────────┐
│                     feature 层                                   │
│  launcher │ appmanager │ settings                                │
└───────────────────────────┬─────────────────────────────────────┘
                            │
┌───────────────────────────┼─────────────────────────────────────┐
│                      core 层                                     │
│                                                                   │
│  ┌─────────┐  ┌─────────┐  ┌──────────┐  ┌──────────────┐      │
│  │  model  │  │ common  │  │designsys │  │    apk       │      │
│  └────┬────┘  └────┬────┘  └──────────┘  └──────┬───────┘      │
│       │            │                             │               │
│  ┌────┴────────────┴─────────────────────────────┴──────┐       │
│  │                    hook                               │       │
│  │  HookEngine · NativeHookBridge · LSPlant · ShadowHook │       │
│  └────┬──────────────────────────────────────────────────┘       │
│       │                                                           │
│  ┌────┴────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐      │
│  │identity │  │  loader  │  │   stub   │  │   xposed     │      │
│  └─────────┘  └────┬─────┘  └──────────┘  └──────────────┘      │
│                    │                                              │
│  ┌─────────┐  ┌────┴─────┐  ┌──────────┐                        │
│  │manifest │  │instance  │  │installer │                        │
│  └─────────┘  └──────────┘  └──────────┘                        │
└──────────────────────────────────────────────────────────────────┘
```

## 模块职责

| 模块 | 职责 | 核心类 |
|------|------|--------|
| `model` | 数据模型定义 | `VirtualApp`, `DeviceProfile`, `ApkInfo`, `StubConfig` |
| `common` | 基础工具 | `ReflectionUtils`, `CrashReporter`, `AndroidCompat` |
| `designsystem` | Material 3 主题 | 主题色、组件样式 |
| `apk` | APK 解析 | `ApkParser`, `VirtualClassLoader` |
| `hook` | Hook 引擎 | `HookEngine`, `NativeHookBridge`, LSPlant/ShadowHook 封装 |
| `manifest` | 二进制 XML 解析 | `ManifestParser`, `ManifestGenerator`, `BinaryXmlEncoder` |
| `identity` | 身份代理层 | `PackageIdentityHook`, `DeviceIdentityHook`, `FileSystemHook` |
| `loader` | 运行时加载入口 | `LoaderFactory` (AppComponentFactory), `GuestContextWrapper` |
| `stub` | Stub APK 构建 | `StubBuilder`, `ApkSigningHelper` |
| `instance` | 实例管理 | `InstanceManager`, Room 数据库 |
| `installer` | 安装器 | `PackageInstaller`, `ShizukuInstaller` |
| `xposed` | Xposed 兼容层 | `XposedBridge`, `XC_MethodHook`, `ModuleLoader` |
| `workprofile` | 工作配置文件 | 企业/工作配置文件方案 (实验) |

## 核心流程

### 1. Stub APK 构建期

```
StubBuilder.build()
  ├─ 解析原始 APK (manifest, 组件, 签名)
  ├─ 生成 stub AndroidManifest.xml
  ├─ 注入加固壳加载点 (System.loadLibrary)
  ├─ 打包 loader.dex (LoaderFactory 入口)
  ├─ 打包原始 APK → assets/origin.apk
  ├─ 打包 native 库 (libmultiapp-native.so + 原始 .so)
  └─ zipalign + 签名
```

### 2. 运行时注入 (LoaderFactory)

```
系统启动 Stub 进程
  │
  ▼
AppComponentFactory.instantiateApplication()
  │
  ├─ 1. 获取 ActivityThread / ApplicationInfo
  ├─ 2. 解压 assets/origin.apk → dataDir
  ├─ 3. 安装 Runtime.nativeLoad hook (GOT)
  ├─ 4. 替换 LoadedApk (ClassLoader + Resources)
  ├─ 5. 初始化 LSPlant (JNI_OnLoad 时机)
  ├─ 6. 安装身份 Hook (9 维度, 40+ 点)
  ├─ 7. 安装签名绕过 Hook
  ├─ 8. 加固壳预加载 (libjiagu_vip.so)
  ├─ 9. 业务 native 预加载
  ├─ 10. 加载 Xposed 模块 (如果有)
  ├─ 11. 重置 appComponentFactory
  └─ 12. 原始 Application 正常创建
```

### 3. Native Hook 层次

```
┌─────────────────────────────────────────────┐
│ 层 1: LSPlant ART Hook                      │
│   Java 方法拦截 (签名、设备信息、文件路径)    │
├─────────────────────────────────────────────┤
│ 层 2: ShadowHook inline hook                │
│   ART 内部函数 hook (Runtime.nativeLoad)     │
├─────────────────────────────────────────────┤
│ 层 3: GOT hook                              │
│   libc.open/fopen/readlink 拦截             │
│   /proc/self/maps 过滤                      │
│   APK 完整性校验重定向                       │
├─────────────────────────────────────────────┤
│ 层 4: FindClass hook                        │
│   JNI FindClass 重定向到 guest ClassLoader  │
└─────────────────────────────────────────────┘
```

### 4. 身份代理 (9 维度)

| 维度 | Hook 点数 | 说明 |
|------|-----------|------|
| 包名身份 | 5 | Context/PackageManager/ApplicationInfo |
| 签名伪装 | 2 | PackageInfo 签名替换 |
| 设备标识 | 6 | IMEI/AndroidID/MAC/Serial |
| 系统属性 | 10+ | Build.MODEL/FINGERPRINT/VERSION |
| 文件系统 | 6 | File 构造函数/路径重写 |
| /proc 文件 | 2 | maps/cmdline 读取拦截 |
| ContentProvider | 6 | authority 重写 |
| ActivityManager | 3 | 进程信息伪装 |
| Native 库 | 1 | dlopen 路径重定向 |

### 5. QQ 阅读兼容链路 (v174+)

```
用户点击章节
  │
  ▼
OnlineChapterDownloadTask.run() (native fallback)
  │
  ├─ ReaderProtocolTask → ChapBatAuthWithPD → 元数据
  ├─ wxmini 免费章节接口 → 明文正文
  ├─ 写入 .eqct 文件
  │
  ▼
QqReaderEqctPlaintextCompat hook
  │
  ├─ 检测 .mini_<cid>.txt 标记
  ├─ 绕过原解密流程
  └─ 直接返回 .eqct 明文字节
  │
  ▼
阅读引擎正常渲染
```

## 关键设计决策

| 决策 | 理由 |
|------|------|
| AppComponentFactory 注入 | 早于 Application 创建，壳无法感知 |
| 替换 LoadedApk 而非 DexClassLoader | 保持系统级 PackageManager 状态一致 |
| LSPlant 在 JNI_OnLoad 初始化 | 对齐 LSPatch 时机，避免 hidden API 限制 |
| GOT hook 而非 inline hook | 兼容性更好，不需要修改代码段 |
| 保留 stub 包名 | 避免系统权限/AppOps 检查失败 |
| 身份伪装在 instantiateApplication | 早于壳初始化，壳看到的全是伪装数据 |

## 构建与验证

```bash
# Debug 构建
./gradlew assembleDebug

# 安装
adb install app/build/outputs/apk/debug/app-debug.apk

# 特定模块构建
./gradlew :core:hook:assembleDebug

# 生成 loader.dex
./gradlew :core:stub:generateLoaderDex
```
