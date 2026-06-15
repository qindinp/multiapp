# MultiApp 目标设计方案

日期：2026-06-12  
适用仓库：`C:\Users\Administrator\Desktop\1122\visual app\multiapp`  
状态：设计建议，尚未全部实现。

## 1. 设计目标

MultiApp 的目标不应只是“把 APK 拉起来”，而应分层支持：

1. 普通 App 分身：多账号、数据隔离、快捷启动、通知与组件正常工作。
2. 中等复杂 App：资源、Theme、Provider、Service、Receiver、隐式 Intent、PackageManager 查询基本一致。
3. 加固/壳 App：在 clone/stub 包内尽量还原原应用的 ClassLoader、sourceDir、nativeLibraryDir、签名、dataDir、`JNI_OnLoad`、`RegisterNatives` 环境。
4. 可诊断工程：每轮构建、安装、启动、crash、UI 状态、关键日志都能落盘复现。

当前 QQ 阅读专项说明：只靠逐个 DEX patch 崩溃点无法达到“正常使用”。项目需要从“补丁集合”升级为“可验证的虚拟运行时”。

## 2. 参考来源

### 2.1 本地仓库依据

本地文档已经明确当前方向：

- `docs/protected-app-loading.md`：当前目标是在同一进程构造接近原应用的运行环境，让壳的 `JNI_OnLoad`、`RegisterNatives`、DEX 解密和业务 native 初始化继续执行。
- `docs/clone-runtime-general-fix.md`：核心是系统服务看 stub 包名，应用内部看 original 包名，避免每个业务崩溃点都靠 DEX patch。
- `docs/architecture-review.md`：当前架构是 `StubBuilder -> LoaderFactory -> LoadedApkSwapper -> HookEngine/NativeHookBridge -> Identity Hooks -> 原始 Application`，但缺统一 HookPipeline、Hook 生命周期管理、模块边界收敛。
- `docs/qqreader-clone-special-plan.md`：QQ 阅读证明阻塞点已经从普通 Activity 启动转向加固壳、qrencrypt/fock、章节下载 native 注册链路。

当前源码还暴露一个需要修正的认知：运行期核心已经集中到 `LoaderFactory`，旧 `LoadedApkSwapper` 基本是保留编译兼容的空对象。正式设计不能继续把 `LoadedApkSwapper` 当主实现，应把 `LoaderFactory` 拆成可测试的 `RuntimeBootstrap` 分阶段组件。

### 2.2 开源项目参考

| 项目 | 可借鉴点 | 对本项目的限制 |
|---|---|---|
| VirtualApp | 用户态虚拟 Android framework、虚拟 Package/Activity/Service、文件重定向、系统服务代理。适合作为 MultiApp 当前 Stub/虚拟化方向的主参考。 | 强加固 App 会检测真实安装态、签名、native path、maps、ClassLoader，不能只靠 Java framework 代理。 |
| VirtualXposed | 在 VirtualApp 容器上叠加 hook 层。说明“虚拟容器”和“hook/兼容补丁”应分层。 | 非 root 容器无法覆盖系统进程真实行为；资源 hook、native 壳能力有限。 |
| DroidPlugin | Proxy Hook 夹在插件和 Android framework 中，改写 AMS/PMS 等调用参数；公开资料也承认 native 层支持不足。 | QQ 阅读这类问题正卡在 native 壳和业务 native，DroidPlugin 式组件代理不够。 |
| RePlugin / Shadow | 强调插件包管理、四大组件、资源/Theme、So 加载、动态 loader，工程稳定性强。 | 更适合可配合改造的插件，不适合直接运行未知强加固 APK；但工程分层值得借鉴。 |
| BlackBox / BlackDex | VirtualApp 类虚拟引擎和 Dex dump 诊断能力。 | dump/脱壳不能等价于运行兼容；检测和合法性风险需要控制。 |
| Xposed / LSPosed / LSPatch | hook 层适合作诊断和小范围行为兼容。 | root/Magisk 或重签 APK 不适合作为普通用户态分身底座。 |
| Shelter / Island / Android Work Profile | 基于系统 Work Profile/multi-user 做真实隔离，兼容性和安全边界更清晰。 | 需要系统/设备策略能力，普通 App 难以无限多开。 |
| Samsung Knox / 企业容器 | 系统级隔离、企业管理、应用和数据边界清晰。 | 依赖厂商/MDM 能力，不是纯用户态方案。 |
| Parallel Space / VMOS | 成熟产品层面证明“虚拟空间/多账号/隔离”有用户需求；VMOS 更接近虚拟 Android 系统。 | 高兼容来自长期适配和系统能力；无法简单复制。 |

## 3. 推荐总体架构

建议将项目明确分成三条产品/技术路线，而不是把所有兼容逻辑堆进 `LoaderFactory`：

```text
Route A: 用户态 Stub 虚拟化
  当前主线，适合普通 App 和部分未强加固 App。

Route B: 加固 App 兼容运行时
  在 Route A 基础上增加壳策略、native 注册诊断、反检测、so 加载与完整性重定向。

Route C: 系统 Profile / 多用户 / 企业容器
  长期高兼容路线，依赖系统权限或设备管理能力，减少用户态伪造面。
```

当前仓库应优先完善 Route A + Route B。Route C 作为长期高兼容方案预研，不影响当前 QQ 阅读专项。

## 4. 目标模块分层

### 4.1 App/UI 层

职责：

- 管理分身实例。
- 选择原 APK。
- 触发构建、安装、启动。
- 展示兼容性状态和诊断报告。

涉及模块：

```text
app
feature/launcher
feature/appmanager
feature/settings
core/instance
core/installer
```

设计要求：

- UI 不直接处理壳兼容细节。
- 每个实例有明确 profile：普通模式、加固兼容模式、诊断模式。
- 每次构建输出 build id、loader version、origin hash、patch profile。
- `feature/launcher` 负责分身入口与启动状态。
- `feature/appmanager` 负责 APK 选择、构建、安装、实例管理。
- `feature/settings` 负责诊断开关、兼容模式、日志导出。

### 4.2 构建层：StubBuilder / Manifest / APK 工具

职责：

- 解析 origin APK。
- 生成 stub manifest。
- 映射 Activity/Service/Receiver/Provider。
- 打包 `origin.apk`、`origin_original.apk`、`loader.dex`、native libs。
- 注入必要 helper class。

涉及模块：

```text
core/apk
core/manifest
core/stub
tools/qqreader-offline-patch
```

正式设计：

1. `StubConfig`、`DeviceIdentityConfig` 等跨模块配置应移到 `core/model`。
2. 构建产物必须写入 manifest/meta：
   - origin package
   - stub package
   - origin version
   - origin cert digest
   - loader build id
   - patch profile id
   - abi list
3. 离线 patch 工具不应是 QQ 阅读专属脚本，而应升级为：

```text
PatchProfile
  - neutralize methods
  - inject helper classes
  - preserve methods
  - native lib policy
  - verification probes
```

### 4.3 Runtime 启动编排层

当前核心入口：

```text
core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
```

本地源码现状：

- `LoaderFactory` 已承担运行期几乎所有关键动作：解压 origin、安装 native hooks、创建 guest `PathClassLoader`、替换 `LoadedApk`、处理资源、预加载壳库、安装部分 identity hook、处理 QQ 阅读专项。
- `LoadedApkSwapper` 已不应作为真实架构中心看待。
- `HookEngine` 目前不是完整 pass-through hook 框架，能力边界要在设计中写清楚。

建议把 `LoaderFactory` 从“巨型实现”改成启动编排器：

```text
LoaderFactory
  -> RuntimeBootstrap
      -> OriginExtractor
      -> LoadedApkInstaller
      -> ResourceInstaller
      -> NativeRuntimeInstaller
      -> HookPipeline
      -> PackerRuntime
      -> GuestApplicationLauncher
```

关键原则：

- `LoaderFactory` 只负责系统入口和异常兜底。
- 每个阶段返回结构化结果，不再只靠 logcat。
- 关键阶段失败要中止或进入诊断模式，不能静默继续。

### 4.4 双身份层：Original vs Stub

这是分身项目的核心设计。

系统服务看到：

```text
stubPkg
```

guest app 代码看到：

```text
originalPkg
```

要统一建模为：

```text
PackageIdentity {
  originalPackageName
  stubPackageName
  visibleToGuest
  visibleToSystem
  uid
  userId
  sourceDirPolicy
  dataDirPolicy
  signaturePolicy
}
```

需要覆盖：

- `Context.getPackageName()`
- `ApplicationInfo`
- `PackageInfo`
- `PackageManager`
- `ActivityManager`
- `NotificationManager`
- `ContentResolver`
- `AppOps`
- `Intent.component`
- `Intent.package`
- Provider authority
- file/data/source/resource paths

当前 `GuestContextWrapper`、`IntentRemappingInstrumentation`、`LoadedApk` 替换只是起点，后续应补完整虚拟系统服务层。

### 4.5 虚拟系统服务层

借鉴 VirtualApp / DroidPlugin，正式引入：

```text
VirtualPackageManager
VirtualActivityManager
VirtualServiceManager
VirtualContentResolver
VirtualNotificationManager
VirtualAppOpsManager
```

职责：

- 统一处理 original/stub 包名映射。
- 统一处理 UID/package 校验场景。
- 统一管理组件生命周期。
- 统一处理隐式 Intent resolve。
- 支持多实例数据隔离。

优先级：

1. `PackageManager`：签名、sourceDir、nativeLibraryDir、version、requested permissions。
2. `ActivityTaskManager/Instrumentation`：显式/隐式 Activity 启动、返回栈。
3. `ContentProvider`：authority 重写、初始化顺序、跨包查询。
4. `NotificationManager`：package/uid 校验、channel、PendingIntent。
5. `Service/Receiver`：后台启动限制、广播动态注册。

实现矩阵：

| 服务层 | 主要拦截点 | 当前基础 | 目标实现 | 失败表现 |
|---|---|---|---|---|
| PackageManager | `getPackageInfo`、`getApplicationInfo`、`queryIntent*`、签名读取 | `GuestContextWrapper` 和部分 identity hook | 建立 `VirtualPackageManager`，统一返回 original/stub 双身份视图 | 签名校验失败、AppKey/包名不匹配、native path 错误 |
| ActivityTaskManager | `Instrumentation.execStartActivity*`、显式/隐式 `Intent`、selector | `IntentRemappingInstrumentation` | 建立 Activity intent mapper 和 resolve cache | `not exported`、跨包启动失败、返回栈异常 |
| ContentProvider | authority、`installProvider`、`ContentResolver` 查询 | `SafeProviderWrapper`、authority 文档设计 | 建立 provider authority registry，区分系统 provider 与 guest provider | provider 初始化崩溃、数据查询空、账号/配置读取失败 |
| NotificationManager | `notify`、channel、`PendingIntent` 包名 | 待补 | 包名参数按系统视角改 stub，显示内容按 guest 视角保留 original | `cannot post for pkg`、通知点击无法回到分身 |
| Service/Receiver | `startService`、`bindService`、动态广播注册 | Manifest 组件映射 | 组件代理 + Intent remap + 后台限制适配 | 后台任务失败、push/下载/登录回调失效 |
| AppOps/Permission | UID/package 校验、权限查询 | 零散 identity hook | `VirtualAppOpsManager`，系统校验传 stub，guest 查询传 original | 权限被拒、定位/存储/通知异常 |
| Filesystem | files/db/cache/shared_prefs/native libs | path redirect 和 origin lib 提取 | per-instance data root + path policy | 串号、数据污染、壳读错路径 |

实现优先级应按“失败是否阻断启动和核心功能”排序：`PackageManager`、`ActivityTaskManager`、`Filesystem`、`ContentProvider`、`NotificationManager`、`Service/Receiver`、`AppOps`。

### 4.6 数据隔离模型

数据隔离是分身产品的基础能力，不能只依赖包名伪装。正式模型应为每个实例建立独立数据根：

```text
InstanceDataRoot {
  instanceId
  originalPackageName
  stubPackageName
  filesDir
  cacheDir
  databasesDir
  sharedPrefsDir
  webViewDir
  nativeLibDir
  externalFilesDir
  mediaPolicy
}
```

必须覆盖：

| 数据面 | 目标策略 | 风险 |
|---|---|---|
| `files/`、`cache/`、`code_cache/` | 每实例独立目录，Java 和 native 文件 API 同步重定向 | 多实例数据串号、缓存污染 |
| `shared_prefs/` | 每实例独立，保留 guest 文件名语义 | 登录态串号、配置互相覆盖 |
| `databases/` | 每实例独立，SQLite 路径重定向 | 书架/账号/阅读进度污染 |
| WebView/Cookies | 每实例独立 WebView data suffix 或目录策略 | H5 登录态串号 |
| native libs | origin libs 和 MultiApp libs 分层存放，只读 marker 管理 | 壳完整性校验失败、库错绑 |
| external storage | 默认隔离到实例目录，必要时提供共享策略 | 下载文件混乱、隐私泄漏 |
| Keystore/AccountManager | 普通用户态难以完整虚拟化，需标记能力边界 | 登录 SDK 或支付 SDK 失败 |
| MediaStore/DownloadProvider | 通过路径和 display name 策略控制 | 下载、导入、分享功能异常 |

数据隔离验收：

```text
同一 App 创建两个分身 -> 分别登录/写入配置/下载文件 -> 重启后互不影响。
卸载一个分身 -> 不删除另一个分身数据。
升级 loader -> 不破坏已有实例数据。
```

### 4.7 HookPipeline

当前问题：

- `core/hook` 过重。
- `NativeHookBridge` 生命周期和 `HookEngine` 生命周期分离。
- `IdentitySpoofingEngine` 与 `DeviceIdentityHook` 重叠。
- 关键 hook 失败后仍继续启动，导致后续行为不可预测。

目标设计：

```text
HookPipeline
  - ordered stages
  - dependencies
  - critical / optional hook
  - result report
  - rollback / cleanup
```

建议阶段：

```text
Stage 0: runtime config and origin files
Stage 1: native base hooks
Stage 2: LoadedApk/ClassLoader/resources install
Stage 3: guest-bound native hooks
Stage 4: package identity hooks
Stage 5: system service proxies
Stage 6: packer-specific runtime hooks
Stage 7: guest business native probes
```

关键原则：

- native hook 要分成两类，不能混在一个“越早越好”的阶段里。
- `native base hooks` 可以在 guest ClassLoader 创建前安装，例如基础 path redirect、`open/fopen/readlink`、`kill/tgkill` 诊断。
- `guest-bound native hooks` 必须在 guest ClassLoader / resources / native path 准备后、壳 `JNI_OnLoad` 前安装，例如 `FindClass` hook、`Runtime.nativeLoad` caller 修复、`RegisterNatives` 归属诊断。
- DEX patch、LSPlant hook、native hook 要有互斥规则。
- release 版本日志要脱敏，诊断版本可以详细输出。

阶段失败策略：

| 阶段 | 失败处理 | 原因 |
|---|---|---|
| Stage 0 | 中止启动 | 配置或 origin 文件错误时继续运行没有意义。 |
| Stage 1 | 进入诊断模式或中止加固 App | 加固壳可能直接读 `/proc/self/maps` 或路径。 |
| Stage 2 | 中止启动 | guest ClassLoader/resources 错误会导致后续全部错绑。 |
| Stage 3 | 中止加固 App，普通 App 可降级 | `FindClass/RegisterNatives` 绑定依赖这个阶段。 |
| Stage 4 | 普通 App 可降级，加固 App 视 profile 决定 | 身份不一致可能表现为网络异常或权限失败。 |
| Stage 5 | 普通 App 可降级，组件型 App 视失败点决定 | 系统服务代理失败会影响跳转、通知、Provider。 |
| Stage 6 | 按 `PackerRuntime` 决策 | 不同壳失败影响不同。 |
| Stage 7 | 记录 probe 结果，不默认 patch | 业务 native 失败应先定位，避免误伤。 |

### 4.8 加固壳兼容层

不要把 QQ 阅读逻辑散落在 `LoaderFactory` 和 `native-hook.cpp` 中。应建立策略层：

```text
PackerRuntime
  - JiaguRuntime
  - SecNeoRuntime
  - TencentLeguRuntime
  - BangcleRuntime
  - GenericNativeProtectedRuntime
```

每个 runtime 定义：

```text
detect()
prepareFiles()
installNativeHooks()
loadPackerLibrary()
callOriginalEntry()
verifyRegisterNatives()
fallbackPolicy()
```

对 Jiagu / QQ 阅读当前最需要：

- `libjiagu_vip.so` 的加载时机控制。
- guest ClassLoader 下 `JNI_OnLoad`。
- `FindClass` hook。
- `RegisterNatives` 诊断。
- `origin.apk -> origin_original.apk` 完整性读取重定向。
- `/proc/self/maps` 隐藏 MultiApp/LSPlant/shadowhook 痕迹。
- `kill/tgkill/exit/abort` 诊断，不默认吞掉。
- 定点 callsite patch 必须记录 offset、原指令、替换指令和验证结果。

### 4.9 Native runtime 层

`NativeHookBridge` 应成为正式 runtime 基础设施，而不是临时调试工具。

能力清单：

- `Runtime.nativeLoad` hook。
- `FindClass` hook。
- `RegisterNatives` logger / wrapper。
- GOT hook：`open/openat/fopen/readlink/kill/tgkill/exit/abort`。
- path redirect。
- `/proc/self/maps` filter。
- native library namespace / ClassLoader binding probe。
- per-library patch registry。

设计要求：

- 每个 hook 有启用条件。
- 每个 hook 有结构化日志。
- 每个 hook 有最小影响范围。
- 对 `kill/tgkill` 默认只记录，不默认抑制。
- 对 so 指令 patch 必须走白名单 patch profile。

### 4.10 业务兼容层

对 QQ 阅读这类 App，业务层不能再靠“见崩就 no-op”。

建立：

```text
AppCompatProfile
  - packageName
  - known packer
  - required native libs
  - startup neutralize list
  - forbidden neutralize list
  - business probes
  - acceptance flows
```

QQ 阅读 profile 应明确：

允许谨慎处理：

```text
initPushSDK
initLoginSDK
ShortcutManager.cihai
ZeusPlatformUtils.initZeus
部分 toast/resource 崩溃点
```

禁止作为最终方案：

```text
Fock.sign -> fake/null/MD5
FockRT.sn -> fake/null/MD5
OnlineChapterDownloadTask.run -> no-op
qrencrypt/FockKeyPoolCache 大面积空实现
```

验收流程：

```text
启动
进入书城
打开免费阅读
加书架
打开章节正文
翻页
退出重进
```

## 5. 长期方案取舍

### 5.1 用户态虚拟化

代表：

```text
VirtualApp / Parallel Space / DroidPlugin / BlackBox
```

优点：

- 不需要系统权限。
- 可分发性较强。
- 适合多账号和普通 App。

缺点：

- 对加固 App 需要大量定制。
- ROM 差异成本高。
- 系统服务和 native 层很难完全伪造。

适合本项目短中期主线。

### 5.2 重签 + Hook

代表：

```text
LSPatch / 太极非 root 模式
```

优点：

- hook 能力强。
- 单 App 定向兼容快。

缺点：

- 重签容易触发签名/完整性校验。
- 重签后容易破坏原签名链和壳完整性假设。
- 不适合作为通用用户态分身底座。

本项目只建议用于内部诊断，不作为产品主线。

### 5.3 系统 Profile / 多用户 / 企业容器

代表：

```text
Android Work Profile
Shelter / Island
Samsung Knox
VMOS / 虚拟系统类产品
```

优点：

- 更接近真实安装。
- UID/userId/dataDir 隔离天然成立。
- 加固 App 兼容性通常优于用户态伪造。

缺点：

- 权限、设备策略、厂商限制明显。
- 多开数量和产品形态受限。
- 实现和分发门槛高。

建议作为长期高兼容路线预研：如果目标是尽量提高兼容上限，最终应评估 Work Profile / 多用户 / 虚拟系统，而不是无限堆用户态 hook。

## 6. 针对 QQ 阅读的设计结论

QQ 阅读专项不是个别崩溃点问题，而是对当前架构的压力测试。

已暴露架构缺口：

1. 加固壳原始初始化没有稳定恢复。
2. native library 必须绑定到 guest ClassLoader。
3. `RegisterNatives` 需要成为一等诊断能力。
4. 业务签名/加密不能 fake。
5. 章节下载 native 不能 no-op。
6. `LoaderFactory` 承载过多临时逻辑，需要策略化。
7. 缺少每版 APK 的结构化测试报告。

当前 QQ 阅读下一步仍是：

```text
验证 v112 是否成功绕过 libjiagu_vip.so offset=0x11cb88 self SIGKILL。
```

如果 v112 过了壳自杀点，再回到：

```text
OnlineChapterDownloadTask.run RegisterNatives
Fock/FockRT/qrencrypt 真实签名链路
```

## 7. 分阶段落地路线

### Phase 0：收敛现有专项

目标：

- 不再扩大临时 DEX patch。
- 保留 v112 验证链。
- 每轮都生成结构化报告。

产出：

```text
docs/qqreader-clone-special-plan.md
.tmp/qqreader-vXXX-*.txt
```

### Phase 1：RuntimeBootstrap 拆分

从 `LoaderFactory` 拆出：

```text
OriginExtractor
RuntimeConfigLoader
LoadedApkInstaller
ResourceInstaller
NativeRuntimeInstaller
PackerRuntimeDispatcher
HookPipeline
```

验收：

- 普通 App 启动路径不回退。
- QQ 阅读离线包仍能打出。
- log 中能看到每个阶段的结构化 result。

### Phase 2：Virtual System Service

补齐：

```text
PackageManager
ActivityTaskManager / Instrumentation
ContentProvider authority
NotificationManager
Service/Receiver
AppOps
```

验收：

- 不再靠散落 hook 修包名。
- original/stub identity 有统一决策。
- 常见普通 App 正常启动、跳转、通知、Provider。

### Phase 3：PackerRuntime 策略层

实现：

```text
JiaguRuntime
GenericProtectedRuntime
RegisterNativesProbe
NativeLibraryBindingProbe
SoPatchRegistry
```

验收：

- `libjiagu_vip.so` 加载、`StubApp.interface20`、self-kill、业务 native 注册都有结构化诊断。
- QQ 阅读不再靠猜 offset，patch 都来自记录的 probe。

### Phase 4：AppCompatProfile

实现：

```text
QqReaderCompatProfile
GenericCompatProfile
ProfileVerifier
```

验收：

- 每个 profile 有允许 patch、禁止 patch、验收流程。
- QQ 阅读 profile 明确禁止 fake `Fock.sign` 和 no-op `OnlineChapterDownloadTask.run` 作为最终方案。

### Phase 5：长期高兼容路线评估

评估：

```text
用户态虚拟化继续增强
Shizuku/Root 增强模式
Work Profile / 多用户
虚拟 Android 系统
```

验收：

- 明确普通用户版和高级兼容版边界。
- 明确权限、分发、设备支持和维护成本。
- 明确哪些能力不能承诺。

## 8. 近期优先级

按优先级排序：

1. 手机恢复后验证 v112。
2. 把 `LoaderFactory` 当前启动阶段日志结构化。
3. 抽出 `PackerRuntime` 接口，但先只实现 Jiagu/QQ 阅读需要的路径。
4. 把 `RegisterNatives` 诊断做成稳定能力。
5. 建立 `QqReaderCompatProfile`，收敛所有 QQ 阅读 patch 决策。
6. 再补 VirtualPackageManager / VirtualNotificationManager 等系统服务层。

## 9. 不建议的方向

不建议继续：

- 每遇到一个 crash 就扩大 neutralize 范围。
- 把 `Fock.sign` fake 成能返回的值然后认为网络修好了。
- 用 no-op 让章节线程不崩然后认为阅读修好了。
- 在 `LoaderFactory` 里继续堆所有 App 专项逻辑。
- 不记录版本、APK、log、props 就做判断。
- 大范围 patch so，不记录 offset、原指令、替换指令。

## 10. 参考矩阵

本项目为个人学习参考项目。下表用于记录可借鉴的公开设计思路，不表示这些项目可以直接复制或完整解决当前问题。

| 参考 | 链接 | 主要用途 | 对本项目的参考价值 | 适用性判断 |
|---|---|---|---|---|
| VirtualApp | `https://github.com/asLody/VirtualApp` | 用户态虚拟化、虚拟 Package/Activity/Service、文件重定向 | Route A 主参考，帮助设计虚拟系统服务层 | 架构参考价值高，但强加固兼容仍需额外 native/runtime 层 |
| VirtualXposed | `https://github.com/android-hacker/VirtualXposed` | VirtualApp 容器 + Xposed 模块兼容层 | 证明容器层和 hook 层应分开 | 可借鉴分层，不直接照搬 |
| DroidPlugin | `https://github.com/DroidPluginTeam/DroidPlugin` | 插件/免安装、AMS/PMS proxy hook | 参考组件代理和 Intent/Package 参数改写 | native 层短板明显，不能解决 QQ 阅读核心问题 |
| RePlugin | `https://github.com/Qihoo360/RePlugin` | 插件管理、组件、资源、So 加载 | 参考工程化、版本管理、插件元数据 | 更适合可改造插件，不适合未知加固 APK |
| Shadow | `https://github.com/Tencent/Shadow` | 动态插件框架、loader/manager 分离 | 参考 RuntimeBootstrap 和动态 loader 分层 | 架构参考，不是任意 APK 虚拟化方案 |
| BlackBox | `https://github.com/FBlackBox/BlackBox` | VirtualApp 类 virtual engine | 参考同类实现的包管理/运行容器设计 | 需要结合源码实测，不能假设兼容强壳 |
| BlackDex | `https://github.com/CodingGay/BlackDex` | Dex dump / 诊断 | 可辅助定位壳释放 dex 和类加载时机 | 诊断工具，不是运行时修复方案 |
| LSPosed | `https://github.com/LSPosed/LSPosed` | 系统级 hook 框架 | 参考 hook 能力、模块化诊断思路 | 依赖 root/Magisk，不作为普通主线 |
| LSPatch | `https://github.com/LSPosed/LSPatch` | rootless patch + hook | 参考重签注入式诊断 | 重签会影响签名校验，仅适合实验 |
| Shelter | `https://github.com/PeterCxy/Shelter` | Work Profile 隔离 | 参考系统 profile 的数据隔离模型 | 适合长期 Route C 预研 |
| Island | `https://github.com/oasisfeng/island` | Work Profile / app island | 参考用户侧 profile 管理体验 | 适合长期 Route C 预研 |
| Android Work Profile | `https://www.android.com/enterprise/work-profile/` | 系统级工作资料隔离 | 参考真实安装态和 user/profile 隔离 | 兼容性强，但权限和产品形态受限 |
| Samsung Knox Separated Apps | `https://docs.samsungknox.com/admin/knox-platform-for-enterprise/` | 企业级隔离容器 | 参考企业容器边界和管理模型 | 依赖厂商能力 |
| Parallel Space | `https://play.google.com/store/apps/details?id=com.lbe.parallel.intl` | 多账号/虚拟空间产品 | 参考产品形态和用户功能 | 技术细节不可见，只能作产品参考 |
