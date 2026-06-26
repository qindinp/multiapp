# 自研 MultiApp 容器优先 + 可选 LSPlant 路线计划

> 2026-06-26 v2 更新：本节为当前权威技术路线。后续章节中关于
> `QQ_READER_SPECIAL`、专项实验、Stub 过渡实现、Native patch、LSPlant
> 的内容，除非被本节明确保留，否则只作为历史背景和参考，不再作为默认
> 实施路线。

## 0. v2 当前权威技术路线

### 0.1 一句话结论

MultiApp 的最终路线是 **自研用户态 App 级虚拟安装容器**：

```text
Virtual Install DB
+ Virtual Instance Model
+ Virtual PMS / AMS / Provider / Storage
+ staged RuntimeBootstrap
+ profile-controlled native diagnostics
+ optional LSPlant/Xposed runtime
```

当前已经能跑的 `Stub clone APK + LoaderFactory` 不是最终架构，而是
**Stub-based transitional container**，只承担三件事：

1. 临时启动载体。
2. 证据采集器。
3. 与未来完整容器内核的兼容对照组。

不得继续把过渡实现扩展成 QQ 阅读专项 patch 集合，也不得把 LSPlant、Xposed、
business native stubs、method replacement、no-op patch 作为加固 App baseline
的默认依赖。

### 0.2 当前现实

当前代码真实运行路线是：

```text
origin APK
  -> StubBuilder 生成 clone/stub APK
  -> 系统安装 stub package
  -> AppComponentFactory / LoaderFactory 早期接管
  -> 解压 origin.apk / origin_original.apk
  -> 修正 LoadedApk / ApplicationInfo / Resources / nativeLibraryDir
  -> StealthClassLoader 加载 guest dex
  -> 局部 PMS/Intent/Provider/Storage 补偿
  -> NativeHookPolicy 控制 baseline / diagnostic / compatibility
```

这个过渡路线的价值是快速验证和产出证据；问题是它仍然有重打包、stub 包名、
签名、路径、native namespace、ClassLoader identity 等天然偏差。对普通 App
可能够用，对 360 加固这类 App 不应作为长期中心路线。

### 0.3 目标架构

目标架构必须拆成下列边界，不能继续堆在 `LoaderFactory.kt`：

```text
core/model
  - VirtualPackageRecord
  - VirtualInstanceRecord
  - InstallArtifactManifest
  - CompatibilityMode

core/installer
  - VirtualInstallService
  - OriginApkImporter
  - ArtifactStore

core/instance
  - InstanceManager
  - InstanceDataRoot
  - Virtual user/profile records

core/virtual or core/identity
  - VirtualPackageManager
  - VirtualActivityManager
  - VirtualProviderManager
  - VirtualStorageManager
  - VirtualPermissionManager

core/loader
  - RuntimeBootstrap
  - BootstrapStage
  - GuestContextStage
  - PackageMetadataStage
  - OriginApkStage
  - NativeLibrariesStage
  - ClassLoaderStage
  - ResourcesStage
  - VirtualServicesStage
  - ApplicationStage

core/hook
  - NativeHookPolicy
  - NativeDiagnosticsProfile
  - Jiagu360Profile
  - optional LSPlant/Xposed runtime
```

容器目标不是破坏、修改或替换加固壳，而是兼容壳对真实安装环境的预期，让 guest
视角尽量接近真实安装态：

```text
PackageInfo / ApplicationInfo
sourceDir / publicSourceDir / nativeLibraryDir / dataDir
package identity / signature digest / permissions
provider authority / activity-service-receiver mapping
storage path / app ops / notification identity
ClassLoader identity / native library namespace
JNI_OnLoad / FindClass / RegisterNatives evidence
```

### 0.4 硬约束

1. `com.qq.reader` 默认必须走 `CloneProfile.NORMAL` + protected baseline，不能自动
   进入 `QQ_READER_SPECIAL`。
2. `QQ_READER_SPECIAL` 只允许作为手动启用的 legacy/diagnostic 对照组，不允许作为
   普通创建入口或 baseline 默认路径。
3. Protected baseline 必须满足：

```text
lsPlantEnabled=false
xposedModulesEnabled=false
businessNativeStubsEnabled=false
methodReplacementEnabled=false
noOpPatchesEnabled=false
```

4. Native diagnostics 只能观察和记录：`nativeLoad`、`JNI_OnLoad`、`FindClass`、
   `RegisterNatives`、library path、namespace、class loader。它不是 business stub，
   不是 no-op patch，也不是 method replacement。
5. LSPlant/Xposed 是可选扩展层，只能在容器 baseline 和诊断证据足够清楚之后接入；
   它不能承担 PMS/AMS/Provider/Storage/native namespace 虚拟化职责。
6. `LoaderFactory.kt` 从现在开始冻结功能增长：不再新增 QQ 阅读业务专项逻辑，新增
   能力必须进入明确 stage、profile 或 virtual service 模块。

### 0.5 迁移顺序

当前工程顺序调整为：

```text
Phase A: 冻结 Stub transitional container 的功能增长
Phase B: VirtualInstall / VirtualInstance 数据模型接入真实创建流程
Phase C: RuntimeBootstrap stage 化，替换 LoaderFactory 巨型流程
Phase D: Virtual PMS / AMS / Provider / Storage 边界落地
Phase E: NativeDiagnosticsProfile，只开诊断，不开 patch
Phase F: LSPlant/Xposed optional runtime
Phase G: 兼容矩阵、产品体验和回归测试
```

近期执行优先级：

1. 把 `VirtualPackageRecord`、`VirtualInstanceRecord`、`InstallArtifactManifest` 从模型
   推进到 `InstanceManager` / install boundary。
2. 把 `LoaderFactory.initializeInternal()` 拆成可测试的 `BootstrapStage`。
3. 建立 `NativeDiagnosticsProfile(register-natives-only)`，用于观察 QQ 阅读 360 壳
   `com.stub.StubApp.interface20` 为什么未被原壳注册。
4. 在诊断证据确认前，不允许恢复默认 `QQ_READER_SPECIAL`、business stubs、LSPlant
   或 no-op patch。

### 0.6 QQ 阅读 baseline 最新证据

真机：`2509FPN0BC`，包：
`com.qq.reader.clonestub_762c99e31198466f8bad4ed3d82358a0`，时间：
`2026-06-26 17:48:49`。

证据文件：

```text
.tmp\qqreader-baseline-20260626-174957-summary.txt
.tmp\qqreader-baseline-20260626-174957-logcat.txt
.tmp\qqreader-baseline-20260626-174957-crash.txt
.tmp\qqreader-baseline-20260626-174957-exit-info.txt
```

已确认：

```text
cloneProfile=NORMAL
policyMode=BASELINE
lsPlantEnabled=false
xposedModulesEnabled=false
businessNativeStubsEnabled=false
methodReplacementEnabled=false
noOpPatchesEnabled=false
```

Bootstrap 已推进到：

```text
CONFIG SUCCESS
GUEST_CONTEXT SUCCESS
PACKAGE_METADATA SUCCESS
ORIGIN_APK SUCCESS
NATIVE_LIBS SUCCESS
CLASS_LOADER SUCCESS
APPLICATION SUCCESS
```

当前失败点：

```text
java.lang.UnsatisfiedLinkError:
No implementation found for boolean com.stub.StubApp.interface20()
```

工程判断：这不是“补一个 native stub”的问题，而是 360 壳原始
`RegisterNatives` 链路没有在当前容器环境里完整完成。下一步必须进入
`NativeDiagnosticsProfile(register-natives-only)`，观察 `libjiagu_vip.so` 的
`JNI_OnLoad`、`FindClass`、`RegisterNatives` 目标 class、class loader 和 namespace。

### 0.7 本文档后续内容的阅读规则

后续章节仍保留原始讨论、开源方案取舍、阶段草案和 sprint 草案，但以本 v2 节为准：

- 与 v2 冲突的 QQ 阅读专项默认行为视为废弃。
- 与 v2 冲突的默认 native patch / business stub / no-op patch 视为废弃。
- 与 v2 冲突的“LSPlant 作为加固 App 默认依赖”视为废弃。
- 仍然有效的是：用户态容器优先、hook-free baseline 优先、证据优先、LSPlant 可选。

### 0.8 开源学习定位、壳兼容边界与商业参照

本项目定位为开源学习和容器技术研究，目标是做 **兼容壳的 App 级虚拟化容器**，不是
破坏壳、脱壳、改壳或替换壳。对 QQ 阅读、360 加固壳等样本的验证，应理解为：容器
需要尽量提供接近真实安装态的 PMS/AMS/Provider/Storage/ClassLoader/native namespace
环境，让原壳自己的初始化链路可以正常完成。

市面上存在悟空分身、团团分身、比翼多开等同类商业多开/分身方案，这说明“容器化兼容
加固 App”是现实存在的工程方向。本项目可以把这些产品作为外部能力参照，但不能直接
推断其内部实现、授权关系、系统权限、OEM 合作或风控处理方式。

工程边界：

1. 允许研究和实现容器环境兼容：包身份、签名视图、路径、provider、storage、native
   library namespace、ClassLoader identity、`JNI_OnLoad` / `FindClass` / `RegisterNatives`
   等。
2. 允许通过 diagnostics 观察壳初始化为什么失败，但默认不修改壳代码、不替换壳逻辑、
   不把 native stub 当作成功路径。
3. 不把 LSPlant/Xposed 作为 protected baseline 的默认依赖；它们只能是可选扩展层或
   诊断层，不能代替容器内核。
4. 不把会员、支付、DRM、登录风控、服务端策略等业务能力作为兼容目标；这些不是容器
   运行环境兼容问题。

因此，QQ 阅读的目标应表述为“容器兼容其原壳初始化和正常启动”，而不是“绕过壳”。
如果后续做公开发布或商业化，需要补齐项目声明、测试样本来源、问题复现范围和不可支持
场景，但这不改变当前开源学习项目的技术主线。

### 0.9 普通 App 回归前置与产品体验基线

加固 App 不是第一验收门槛。容器内核每推进一层，必须先通过普通 App 回归，再进入
protected baseline：

```text
普通 App 安装/创建实例
-> 普通 App 首次启动/二次启动
-> Activity/Service/Receiver/Provider 基础行为
-> 文件、SharedPreferences、SQLite、media/storage 隔离
-> 通知、桌面图标、recent task、卸载清理
-> protected baseline 只观察，不 patch
```

产品体验基线必须形成可量化指标，至少包括：

| 指标 | 初始口径 |
| --- | --- |
| clone 创建耗时 | 从选择 origin APK 到实例可启动 |
| cold launch latency | `am start` 到首个 Activity resumed 或崩溃 |
| warm launch latency | 已有实例数据下二次启动 |
| instance identity | 图标、label、recent task、通知归属可区分 |
| data isolation | 两个实例的数据目录、provider、外部文件互不串写 |
| cleanup | 删除实例后数据、artifact、通知、快捷方式可清理 |

只有普通 App 回归稳定后，QQ 阅读 baseline 的失败才有判断价值；否则无法区分是
容器基础能力不足，还是 360 壳对当前环境不兼容。

### 0.10 阶段编号映射与当前落点

本文档保留了三套历史编号。为避免执行混乱，后续以 v2 Phase A-G 为权威编号，旧编号
只作为索引：

| v2 阶段 | 对应历史章节 | Sprint 对应 | 当前状态 |
| --- | --- | --- | --- |
| Phase A 冻结 Stub transitional container | Phase 0 | Sprint 1.1 | 进行中，禁止继续加专项业务逻辑 |
| Phase B VirtualInstall/VirtualInstance | Phase 1 | Sprint 1.2 | 下一主线，接入真实创建流程 |
| Phase C staged RuntimeBootstrap | Phase 2 | Sprint 1.3 | 已有 evidence primitives，需替换巨型流程 |
| Phase D Virtual PMS/AMS/Provider/Storage | Phase 1/2/8 | 后续拆分 | 未完成，是最终容器内核 |
| Phase E NativeDiagnosticsProfile | Phase 3/4/5 | Sprint 1.4/1.5 | QQ 阅读下一步，只诊断不 patch |
| Phase F optional LSPlant/Xposed runtime | Phase 6 | 后续拆分 | 延后，不能承担容器职责 |
| Phase G 兼容矩阵和产品回归 | Phase 7/8 | 后续拆分 | 贯穿所有阶段，先普通 App 后 protected App |

当前落点：Phase A + Phase C 的证据层已经开始，Phase B/D 才是路线升级的核心；
QQ 阅读 `interface20` 属于 Phase E 诊断问题，不得反向拉回专项 patch 路线。

### 0.11 Stub 过渡实现退役条件

`Stub clone APK + LoaderFactory` 只允许继续承担 launcher、evidence collector 和对照组
职责。满足下列条件后，必须开始退役，而不是继续扩展：

| 条件 | 退役动作 |
| --- | --- |
| VirtualInstall DB 可持久化 origin APK、artifact、instance records | 创建流程改走 VirtualInstallService |
| VirtualInstanceModel 能提供 package identity、data root、profile | Stub 参数传递降级为兼容入口 |
| RuntimeBootstrap stage 可独立执行和记录 | LoaderFactory 只保留 thin entrypoint |
| Virtual PMS/AMS/Provider/Storage 可覆盖普通 App 回归 | 停止在 LoaderFactory 中新增补偿逻辑 |
| NativeDiagnosticsProfile 可输出 register-natives-only 证据 | 停止新增 business native stubs |

硬性 kill criteria：

1. `LoaderFactory.kt` 新增分支不得再以 `com.qq.reader`、`StubApp`、`interface20`、
   `libjiagu` 等特殊符号作为业务成功路径。
2. 新增兼容能力必须落到 stage、profile、virtual service 或 diagnostics 模块。
3. 一旦 VirtualInstall + RuntimeBootstrap 可以覆盖普通 App baseline，Stub 生成器只能
   作为 legacy launcher，不再作为主容器内核。

### 0.12 `interface20` 根因矩阵

QQ 阅读当前失败点必须闭环到可证伪根因，不能直接用补 stub 判定成功：

| 假设 | 需要的证据 | 判定 |
| --- | --- | --- |
| `JNI_OnLoad` 没有执行 | logcat/linker/maps 中没有目标 so 的 load/JNI_OnLoad 记录 | native load 或 namespace 问题 |
| `JNI_OnLoad` 执行但 `RegisterNatives` 未执行 | 有 JNI_OnLoad，无 `RegisterNatives: class=com.stub.StubApp` | 壳初始化路径中断 |
| `RegisterNatives` 绑定到错误 class | 有 RegisterNatives，但 class loader / jclass 不匹配 guest `StubApp` | ClassLoader identity 问题 |
| `FindClass` 使用错误 ClassLoader | `FindClass(com/stub/StubApp)` 失败或落到宿主/错误 loader | thread context loader / JNI lookup 问题 |
| native namespace 或 library search path 不一致 | `/proc/<pid>/maps`、`nativeLibraryDir`、linker error 与真实安装不同 | Virtual native libs 边界问题 |
| 壳检测到容器后跳过注册 | 前面链路完整但关键注册被条件跳过，且真实安装对照正常 | 进入“baseline permanent failure”评审 |

`NativeDiagnosticsProfile(register-natives-only)` 的验收不是“让 App 不崩”，而是输出足够
证据把上述假设排除或确认。若确认是容器特征触发的主动拒绝，必须进入 Plan B 评审。

### 0.13 Stage 可逆性与 rollback 语义

RuntimeBootstrap 每个 stage 必须声明可逆性，避免把“回滚”写成无法兑现的承诺：

| 类型 | 含义 | rollback 语义 |
| --- | --- | --- |
| reversible | 只改内存对象或可恢复引用 | 恢复原引用并继续或中止 |
| precheck-only | 只读取和校验，不改变状态 | 记录失败并中止 |
| irreversible | 已触发 native load、Application attach、provider 初始化等外部副作用 | 放弃本次启动、保留证据、清理可清理 artifact，不宣称状态恢复 |

对 protected baseline，遇到 irreversible stage 失败时，默认动作是停止启动并保存证据；
不能在同一轮启动里继续尝试 business stub、no-op patch 或 method replacement。

### 0.14 设备矩阵、ADB 模板与 CI 门禁

真机验证必须固定最小矩阵，避免单设备结论被误读：

| 维度 | 最小记录 |
| --- | --- |
| device | model、Android version、API level、ABI、root 状态 |
| memory/page | 4KB/16KB page size、`getprop ro.product.cpu.abi` |
| package | origin package/version/signature digest、clone package、install time |
| runtime | `nativeLibraryDir`、`sourceDir`、`publicSourceDir`、dataDir |
| evidence | logcat、crash、exit-info、maps、tombstone、BootstrapResult |

ADB 模板必须覆盖：

```text
precheck: adb devices / getprop / pm path / dumpsys package
launch: am start + polling pid/activity state
log: background logcat with timestamped file
native: /proc/<pid>/maps, nativeLibraryDir comparison, linker messages
crash: logcat crash buffer, tombstone pull, dumpsys activity exit-info
summary: BootstrapResult parser + NativeDiagnosticsProfile verdict
```

CI 门禁从文档门禁开始，逐步转成脚本：

1. `LoaderFactory.kt` LOC、分支数和特殊符号增长检查。
2. 禁止 `com.qq.reader`、`interface20`、`libjiagu` 等符号扩散到非 profile/diagnostics 模块。
3. `BootstrapResult` parser 必须能判定所有 stage 的 SUCCESS/FAILED/SKIPPED。
4. protected baseline 单测必须确认 LSPlant、Xposed、business stubs、method replacement、
   no-op patches 全部关闭。
5. 普通 App baseline 回归失败时，不允许推进 protected App 兼容结论。

### 0.15 Baseline permanent failure 与 Plan B

如果 `NativeDiagnosticsProfile(register-natives-only)` 证明 QQ 阅读/360 壳在真实安装态可完成
注册，但在容器环境中失败，不能直接判定“此 App 不支持”。先要判断失败差异是否属于
容器应补齐的真实安装态能力，例如 Virtual PMS/AMS/Provider/Storage、native namespace、
ClassLoader identity、签名视图、路径视图或进程/包身份一致性。

只有在这些容器职责已经补齐，且证据仍显示原壳主动拒绝当前容器环境时，才进入
baseline permanent failure 评审。

Plan B 分层：

1. 继续补容器内核：优先修 Virtual PMS/AMS/Provider/Storage/native namespace 等真实
   安装态差异，这是对标同类商业分身方案的主路线。
2. 扩充 diagnostics：记录 `JNI_OnLoad`、`FindClass`、`RegisterNatives`、maps、linker、
   class loader、package identity 的差异，不改变壳逻辑。
3. 评估系统级或更高权限路线：Profile/Work Profile、系统插件、OEM/MDM 类能力可作为
   远期参照，但不影响当前用户态容器主线。
4. 对单个版本临时标记“不支持/待兼容”，保留证据和复现脚本，避免用错误 patch 掩盖
   容器缺口。

Plan B 不包括默认补 `interface20`、默认替换壳方法、默认 no-op patch、默认破坏壳或默认
启用 LSPlant。兼容壳的方向是让原壳在容器内正常完成自己的初始化，而不是接管壳。

### 0.16 v2 执行形态：仓内内核重写

MultiApp v2 不另起全新仓库，也不继续沿旧 `Stub clone APK + LoaderFactory` 路线小修小补。
当前执行形态是 **in-repo kernel rewrite**：保留当前仓库、分支、构建系统、设备验证资产
和历史证据，在 canonical Gradle module 中建设新的 container kernel。

执行文档：

```text
docs/container-runtime-refactor/v2-in-repo-kernel-rewrite-plan.md
```

该文档是后续拆分任务、模块落点、阶段退出条件、验证矩阵和第一轮实现任务的执行依据。

日期：2026-06-25  
状态：总方案与实施步骤  
适用仓库：`C:\Users\Administrator\Desktop\1122\visual app\multiapp`

## 1. 结论

正确主线不是继续把 `LSPatch` 魔改成容器，也不是继续在 QQ 阅读上堆单点补丁，而是先做真正的 App 级容器。对加固 App，默认成功路径应是 **hook-free 容器兼容**：目标 APK 不重打包，目标进程在容器内看到的安装态、包名、签名、路径、ClassLoader、native lib 环境足够接近真实系统安装。

```text
自研 App 级虚拟化容器
+ 加固 App hook-free 兼容基线
+ 可选 Native/ART 诊断层
+ 可选 LSPlant Hook Runtime
+ 可选最小 Xposed API 兼容层
```

设计目标：

1. 目标 APK 不重打包、不重签名，尽量保留原始 APK 文件形态。
2. 容器内模拟真实安装环境：`PackageManager`、`ActivityManager`、`ContentProvider`、路径、签名、`sourceDir`、`nativeLibraryDir`、数据目录。
3. 加固 App 的第一成功标准是不启用 LSPlant、不启用业务 patch 也能完成壳初始化。
4. LSPlant 只作为可选 Java/ART hook、模块加载、诊断和小范围兼容补丁能力，不承担容器、文件系统、UID、系统服务虚拟化。
5. 加固兼容层先只证明 360/`libjiagu_vip.so` 链路，第一验证对象是 QQ 阅读。
6. 成功标准必须来自真实运行证据，不能把业务 native stub、fake sign、no-op patch 当成最终成功。

非目标：

- 不做付费、DRM、会员、登录风控绕过。
- 不承诺完整兼容所有加固壳。
- 不把 LSPlant 作为加固 App 正常运行的默认依赖。
- 不照搬 `VirtualApp`、`BlackBox`、`VirtualXposed`、`DroidPlugin` 的老代码作为长期基线。
- 不把 `LSPatch` 的重打包注入路线作为最终产品主架构。

## 2. 开源方案取舍

| 方案 | 借鉴点 | 不采用点 | 结论 |
|---|---|---|---|
| `VirtualApp` | 用户态虚拟安装、虚拟 PM/AM、Provider、IO redirect、进程模型 | 公开代码老，高版本 Android 维护成本高 | 容器架构第一参考 |
| `BlackBox/FBlackBox` | `Bcore` 式核心模块、虚拟用户、Xposed 模块管理形态 | 官方主体代码状态不稳定，fork 质量参差 | 参考 API 边界，不直接依赖 |
| `VirtualXposed` | 目标 App 与 Xposed 模块同处虚拟环境的生命周期 | `epic` 偏老，不适合 Android 16 主后端 | 参考模块加载流程 |
| `DroidPlugin` | Activity/Service/Receiver/Provider 占坑和 Intent 改写 | native 层弱，无法解决强加固核心问题 | 只作组件代理历史参考 |
| `SPatch-Update / SlimVXposed` | 商业容器的 launcher/API 形态、`MetaActivityManager.launchApp(...)`、No-Xposed 版本、Android 16/17 release 节奏、四 ABI 打包 | 核心 SDK 明确非开源，仓库主要是 UI/sample，不能验证内核实现 | 产品/API 和工程配置参考，不作为源码基线 |
| `LSPlant` | 现代 ART method hook、backup、unhook、deopt | 不解决容器和系统服务虚拟化 | Hook 后端主选 |
| `LSPatch` | `AppComponentFactory` 早期入口、loader/module 组织 | 重打包/重签名天然触发加固检测 | 参考，不作主线 |

架构决策：

```text
容器底座：VirtualApp/BlackBox 思路，自研实现
加固兼容：默认走 hook-free 容器环境一致性
Native 诊断：按 profile 可选启用 MultiApp 现有 native hook 经验
Hook 后端：LSPlant 作为可选层
Xposed API：自研最小兼容层，默认不参与加固 App 基线
```

### 2.1 SPatch-Update 补充结论

`Katana-Official/SPatch-Update` 对本项目有参考价值，但参考点不是“拿代码合并”。该仓库 README 明确说明 Metaverse/SlimVXposed SDK **NOT OPEN SOURCE**，项目更像 launcher UI/sample，真正容器能力在商业 SDK 中。

可借鉴点：

1. 产品形态：以 launcher/UI 承载容器入口，核心 API 类似 `MetaActivityManager.launchApp(packageName)` / `launchApp(userPartitionName, packageName)`，说明对外 API 应尽量收敛成“虚拟分区 + 包名启动”。
2. 路线判断：release 同时提供普通版和 `no-xposed` APK，且 changelog 包含 Android 16、Android 16 QPR1、Android 17 支持声明。这支持本方案的判断：**容器基线应独立于 Xposed/LSPlant，Hook 能力是可选层**。
3. 工程配置：`compileSdk 37`、`targetSdk 37`、`minSdk 21`、`armeabi-v7a/x86/arm64-v8a/x86_64`、`universalApk`、`useLegacyPackaging = true` 都值得纳入 Android 16/17 兼容矩阵评估。
4. 技术栈信号：credits 同时列出 `LSPlant`、`ShadowHook`、`ByteHook` 等，说明现代商业容器通常不是单一 hook 技术，而是容器、native hook、ART hook 分层组合。

不可直接采用点：

1. 核心 SDK 非开源，无法审计系统服务代理、native loader、加固兼容策略。
2. UI/sample 代码不能证明 QQ 阅读这类加固 App 的具体兼容路径。
3. 商业 SDK 的许可、支持和合规边界不能自动继承到 MultiApp。

## 3. Android 16 设计原则

Android 16/API 36 方向下，容器方案必须把高版本限制作为主设计输入：

1. **Hidden API 不做关键路径**
   - 不依赖不可控反射访问作为唯一实现。
   - 所有私有字段/方法访问必须通过 `AndroidCompat` 版本表管理，并有失败降级。

2. **动态代码加载必须只读**
   - 落盘的 dex/apk/so 在加载前必须 chmod/read-only。
   - 当前 `ensureReadOnlyTree` 思路应产品化为 `FilePermissionPolicy`。

3. **包可见性显式建模**
   - 容器内部的 guest 查询走 `VirtualPackageManager`。
   - 宿主对系统查询只做必要范围，不依赖全局枚举。

4. **后台启动和前台服务限制**
   - Activity 启动必须经过用户可见入口或明确授权的代理 Activity。
   - Service/FGS 要声明类型并记录启动来源。

5. **Scoped Storage 与数据目录隔离**
   - guest 看到原包名路径，系统看到 stub 包路径。
   - 外部存储重定向统一走 `VirtualStorageManager`。

6. **Native/ART 不稳定性**
   - 支持 16KB page size、linker namespace、ART symbol 变化、ABI 差异。
   - native hook 和 LSPlant 都必须有设备/版本探针，且默认不作为容器基线依赖。

7. **显式尊重安全环境限制**
   - 如果目标 App/Intent 明确要求安全环境，例如 `REQUIRE_SECURE_ENV`，容器应返回“不支持/需要授权环境”，不做绕过目标。

## 4. 目标架构

```text
MultiApp Manager/UI
  |
  v
Virtual Install & Instance DB
  |
  v
Virtual Process Runtime
  |
  +--> Virtual PackageManager
  +--> Virtual ActivityManager / Instrumentation
  +--> Virtual ContentProvider / ContentResolver
  +--> Virtual Storage / Path Redirect
  +--> Virtual Permission / AppOps / Notification
  |
  v
Guest Runtime Bootstrap
  |
  +--> ClassLoader / Resources / LoadedApk
  +--> sourceDir / nativeLibraryDir / dataDir
  +--> Native Env Layer
  |     +--> /proc/self spoof
  |     +--> maps filtering
  |     +--> open/openat/fopen/readlink redirect
  |     +--> FindClass/RegisterNatives diagnostics
  |
  +--> Optional LSPlant Runtime
        +--> HookEngine
        +--> Minimal Xposed API
        +--> ModuleLoader
        +--> PackerCompatProfile
```

### 4.1 Manager/UI

职责：

- APK 导入、实例创建、启动、停止、删除。
- 展示兼容模式、诊断状态、日志导出。
- 不直接处理加固壳细节。

仓库落点：

```text
app
feature/launcher
feature/appmanager
feature/settings
core/instance
core/installer
```

### 4.2 Virtual Install & Instance DB

职责：

- 保存 origin APK、证书摘要、版本、ABI、组件表、权限表。
- 为每个分身实例保存虚拟用户、数据目录、外部存储目录、profile 配置。

需要新增/重构：

```text
VirtualPackageRecord
VirtualUserRecord
VirtualInstanceRecord
InstallArtifactManifest
```

仓库落点：

```text
core/model
core/apk
core/instance
core/installer
```

### 4.3 Virtual Framework Layer

职责：

- `VirtualPackageManager`：返回 guest 视角的 `PackageInfo`、`ApplicationInfo`、签名、组件、权限。
- `VirtualActivityManager`：启动 Activity/Service，维护 task/process/component 映射。
- `VirtualProviderManager`：Provider authority 映射、`ContentResolver` 参数改写。
- `VirtualStorageManager`：内部/外部路径重定向。
- `VirtualPermissionManager`：宿主权限与 guest 权限映射。

设计要求：

- 系统看到 stub package，guest 内部看到 original package。
- 所有代理必须可记录 trace：原始调用、改写结果、调用线程、guest package、instance id。
- 不允许业务专项逻辑散落在通用代理层。

仓库落点：

```text
core/identity
core/loader
core/common
```

### 4.4 Guest Runtime Bootstrap

职责：

- 在 guest `Application.attachBaseContext()` 前完成：
  - `LoadedApk` 切换
  - `ApplicationInfo` 修正
  - `PathClassLoader` 构造
- `Resources`/theme/asset 路由
- origin native libs 提取和只读化
- native hooks 初始化
- 可选 Hook Runtime 初始化（默认关闭，不作为加固 App 基线依赖）

当前问题：

- `LoaderFactory.kt` 承担过多职责。
- QQ 阅读专项、通用容器逻辑、native 诊断、fallback patch 混在一起。

目标拆分：

```text
RuntimeBootstrap
  - prepareArtifacts()
  - prepareVirtualAppInfo()
  - prepareClassLoader()
  - prepareResources()
  - prepareNativeLibraries()
  - installVirtualServices()
  - installNativeEnvironment()
  - installHookRuntime()
  - attachOriginalApplication()

BootstrapResult
  - phase
  - success/failure
  - evidence
  - rollback action
```

仓库落点：

```text
core/loader
core/hook
core/manifest
```

### 4.5 Native Env Layer

职责：

- 路径重定向：`open`、`openat`、`fopen`、`readlink`。
- `/proc/self` 伪装：`cmdline`、`status`、`maps`。
- native library 加载诊断：路径、ABI、maps、linker result。
- JNI 诊断：`FindClass`、`RegisterNatives`、`JNI_OnLoad`。

设计要求：

- 所有 hook 按 profile 开关启用。
- 所有 native patch 必须记录来源和适用范围。
- 禁止默认启用业务 native stub。
- `RegisterNatives` logger 必须能区分：
  - 原壳真实注册
  - MultiApp fallback 注册
  - 诊断 stub 注册

仓库落点：

```text
core/hook/src/main/cpp/native-hook.cpp
core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt
core/hook/src/main/java/com/multiapp/core/hook/PackerRuntime.kt
core/hook/src/main/java/com/multiapp/core/hook/JiaguRuntime.kt
```

### 4.6 LSPlant / Xposed Layer

职责：

- LSPlant 初始化、method hook、backup 调用、unhook。
- 最小 Xposed API 兼容。
- 模块 APK 加载、隔离、启用/禁用。

最小 API：

```text
IXposedHookLoadPackage
XC_LoadPackage.LoadPackageParam
XposedBridge.hookMethod
XposedBridge.invokeOriginalMethod
XposedHelpers.findClass
XposedHelpers.findAndHookMethod
XC_MethodHook
XC_MethodReplacement
```

暂缓：

- resource hook
- system_server hook
- 完整 LSPosed scope 管理
- 复杂模块市场

仓库落点：

```text
core/hook
core/xposed
```

### 4.7 Packer Compatibility Profiles

职责：

- 把加固壳专项逻辑从 `LoaderFactory` 和 `native-hook.cpp` 中收敛到 profile。
- 第一阶段只实现 `Jiagu360Profile`。

接口建议：

```kotlin
interface PackerCompatProfile {
    val id: String
    fun detect(context: PackerDetectContext): DetectResult
    fun plan(context: RuntimePlanContext): List<RuntimeAction>
    fun verify(context: RuntimeVerifyContext): VerifyResult
}
```

`Jiagu360Profile` 第一阶段只关心：

- `libjiagu_vip.so` 是否存在。
- `StubApp.interface20` 是否由原壳真实注册。
- `RegisterNatives: class=com.stub.StubApp count=10` 是否出现。
- self-kill 是否来自已验证壳路径。
- `sourceDir`、`nativeLibraryDir`、maps、ClassLoader 是否一致。

## 5. 团队分工

| 团队 | 负责人职责 | 交付物 |
|---|---|---|
| 容器内核组 | 虚拟安装、实例模型、PM/AM/Provider、进程模型 | `VirtualPackageManager`、`VirtualActivityManager`、`VirtualProviderManager` |
| Runtime Bootstrap 组 | 拆分 `LoaderFactory`，定义阶段结果和回滚 | `RuntimeBootstrap`、`BootstrapResult`、阶段日志 |
| Native Runtime 组 | `/proc`、路径、native lib、JNI 诊断 | profile-controlled native hook、RegisterNatives evidence |
| Hook/Xposed 组 | 可选 LSPlant 初始化、backup、最小 Xposed API | `HookEngine` 稳定化、`core/xposed` 最小可用，且可完全关闭 |
| Android 16 兼容组 | API 34-36 行为、hidden API 替代、DCL、FGS、包可见性 | `AndroidCompat` 规则表、兼容门禁 |
| QA/设备组 | 真机矩阵、ADB 自动化、exit-info、UI 状态 | 验证脚本、日志裁剪器、报告模板 |
| 安全合规组 | 授权测试边界、风险词清理、不可支持场景 | 合规边界文档、profile allowlist |

## 6. 实施步骤

### Phase 0：冻结现场和建立证据基线

目标：

- 不扩大当前混合实验。
- 记录当前分支、改动、QQ 阅读现状、可复现命令。

步骤：

1. 固定当前 `git status --short --branch` 快照。
2. 标记当前 QQ 阅读 APK、clone APK、loader build id、native lib hash。
3. 建立统一日志标签和裁剪规则：
   - `AndroidRuntime:E`
   - `MultiApp.POC:D`
   - `RegisterNatives`
   - `FindClass`
   - `libjiagu_vip.so`
4. 每轮真机验证都保存：
   - logcat
   - `dumpsys activity exit-info`
   - installed package path
   - `run-as` 文件检查结果

验收：

- 任何人可以从文档恢复当前现场。
- 不需要读完整 `.tmp` 也能知道要验证哪个 APK。

### Phase 1：普通 App 容器闭环

目标：

让未加固 APK 在容器内稳定完成：

```text
安装 -> 启动 Activity -> 访问资源 -> 读写数据 -> 停止/重启
```

步骤：

1. 抽出 `VirtualPackageRecord` 和 `VirtualInstanceRecord`。
2. 建立 `VirtualPackageManager` 最小实现：
   - `getPackageInfo`
   - `getApplicationInfo`
   - `queryIntentActivities`
   - signatures/signingInfo
3. 建立 Activity 启动代理：
   - stub Activity
   - original component 映射
   - task 记录
4. 建立数据目录：
   - internal data
   - external data
   - cache
5. 建立普通 App 测试样本：
   - minimal app
   - 含 Provider 的 app
   - 含 Service/Receiver 的 app

验收：

- 普通 App 不依赖 QQ 阅读专项代码也能启动。
- `LoaderFactory` 不再是唯一巨型入口，至少形成 `RuntimeBootstrap` 雏形。

### Phase 2：RuntimeBootstrap 拆分

目标：

把当前 `LoaderFactory` 拆成阶段化、可测、可回滚的启动流水线。

步骤：

1. 定义 `RuntimeStage`：
   - artifact
   - appInfo
   - classLoader
   - resources
   - nativeLib
   - virtualServices
   - nativeEnv
   - hookRuntime
   - application
2. 每个阶段输出 `BootstrapResult`。
3. 每个阶段记录 evidence：
   - 输入
   - 输出
   - 是否修改全局状态
   - 失败时 rollback。
4. 先迁移日志和结果模型，再迁移行为，避免一次性大改。

验收：

- 普通 App 和 QQ 阅读离线包的启动日志都能按阶段定位。
- 阶段失败时能清楚知道失败点，而不是只看到最终 crash。

### Phase 3：加固 App hook-free 基线

目标：

在不启用 LSPlant、不启用业务 native stub、不做业务 no-op patch 的情况下，验证容器环境本身能否承载加固 App。QQ 阅读第一轮只验证壳初始化链路，不验证登录、支付、会员、DRM。

步骤：

1. 增加 `ProtectedAppBaselineMode`：
   - LSPlant disabled
   - Xposed modules disabled
   - business native stubs disabled
   - only container identity/path/package/service virtualization enabled
2. 验证 QQ 阅读启动时的原生环境一致性：
   - `sourceDir`
   - `nativeLibraryDir`
   - `dataDir`
   - `PackageManager` signatures/signingInfo
   - origin native libs real filesystem path
3. 只记录必要 evidence，不主动 patch 壳行为：
   - `libjiagu_vip.so` 是否加载
   - `StubApp.load()` 是否进入
   - 是否仍出现 `UnsatisfiedLinkError: com.stub.StubApp.interface20`
4. 将失败归因到容器缺口：
   - 安装态不一致
   - native lib 路径不一致
   - ClassLoader 不一致
   - `/proc`/maps 暴露容器痕迹
   - 系统服务返回值不一致

验收：

- 普通 App 启动路径不受影响。
- QQ 阅读在 baseline mode 下有明确阶段证据。
- 如果 baseline mode 成功完成原壳 `RegisterNatives`，后续不允许再把 LSPlant 设为必要路径。

### Phase 4：Native Env 诊断工程化

目标：

当 hook-free baseline 证明容器环境仍有缺口时，再把 native hook 从“实验集合”改成 profile-controlled diagnostic/runtime。Native Env 首先用于观察和环境一致性修正，不用于业务绕过。

步骤：

1. 建立 `NativeHookPolicy`：
   - maps filter
   - cmdline spoof
   - status TracerPid
   - path redirect
   - RegisterNatives logger
   - FindClass hook
2. 所有 native hook 必须通过 policy 启用。
3. 增加 runtime report：
   - hook install result
   - original pointer
   - target library
   - ABI
   - maps evidence
4. 将 fallback stub 默认关闭，只允许诊断模式启用。

验收：

- 启动日志能说明每个 native hook 是否安装成功。
- 禁用所有 native hook 时普通 App 仍能运行。
- 启用 maps/cmdline/status 时能看到可验证的读回结果。

### Phase 5：Jiagu360 / QQ 阅读验证闭环

目标：

优先在 hook-free baseline 下让 QQ 阅读不再因壳初始化失败直接启动闪退；只有 baseline 失败并且证据指向具体环境缺口时，才启用 Native Env 诊断或最小环境修正。LSPlant 不作为本阶段必要条件。

步骤：

1. 实现 `Jiagu360Profile.detect()`：
   - 检测 `libjiagu_vip.so`
   - 检测 `com.stub.StubApp`
   - 记录 ABI/native lib 路径
2. 实现 `Jiagu360Profile.plan()`：
   - origin native libs 提取
   - read-only enforcement
   - `/proc/self` 伪装
   - maps filter
   - FindClass/RegisterNatives diagnostics
3. 实现 `Jiagu360Profile.verify()`：
   - `libjiagu_vip.so` loaded
   - `RegisterNatives: class=com.stub.StubApp`
   - `interface20` bound by original shell path
   - no `UnsatisfiedLinkError: com.stub.StubApp.interface20`
4. 对比三组：
   - 原始 QQ 阅读
   - 当前 MultiApp clone
   - 新容器 runtime

验收：

- 看到原壳真实 `RegisterNatives` 证据。
- 启动不再死于 `StubApp.interface20`。
- 如果仍崩溃，必须进入更后面的业务/资源/网络阶段。

失败判定：

- 只有 MultiApp fallback `RegisterNatives`，没有原壳注册。
- 通过 fake business native 或 no-op 绕过 crash。
- 无法证明 `libjiagu_vip.so` 从预期路径加载。

### Phase 6：LSPlant 最小 Hook Runtime（可选层）

目标：

在容器基线和加固壳初始化链路清楚之后，再接入 LSPlant。它用于诊断、模块能力和小范围兼容补丁，不作为加固 App 正常运行的默认依赖。

步骤：

1. 固定 LSPlant 初始化时机：native library `JNI_OnLoad` 或 guest Application 前，但必须受 profile 开关控制。
2. 稳定 `nativeHookMethodWithBackup`：
   - backup 生命周期
   - 异常传播
   - unhook
3. 实现最小 Xposed API：
   - `IXposedHookLoadPackage`
   - `XposedBridge.hookMethod`
   - `XposedHelpers.findAndHookMethod`
4. 建立测试模块：
   - hook 一个普通 Java 方法
   - replacement 一个普通 Java 方法
   - 调用 original method

验收：

- Android 10-16 至少一台设备各自能跑最小 hook 测试。
- LSPlant 初始化失败时不会影响普通 App 容器启动。
- 加固 App baseline mode 可以明确禁用 LSPlant 并独立验证。

### Phase 7：基础使用验证

目标：

验证授权测试范围内的基础使用，不扩大到登录/支付/DRM 绕过。

步骤：

1. 验证启动、首页、书架、公开/合法可访问阅读内容。
2. 记录 Provider、File、Network、WebView、Intent 跳转。
3. 登录、支付、会员、DRM 只做行为观察，不作为修复目标。
4. 形成兼容报告：
   - 可用功能
   - 不支持功能
   - 风险功能
   - 需要授权/系统能力的功能

验收：

- 不以“能打开前台”为唯一成功。
- 不以“绕过业务校验”为成功。
- 每个可用结论都有日志或 UI 证据。

### Phase 8：产品化前置

目标：

将研究 PoC 收敛为可维护框架。

步骤：

1. 清理 QQ 阅读专项硬编码。
2. 建立 profile registry：
   - `GenericProfile`
   - `Jiagu360Profile`
   - future `TencentProfile`
   - future `BangBangProfile`
3. 建立 CI/设备矩阵：
   - Android 10/12/14/15/16
   - arm64
   - HyperOS/MIUI
   - Pixel/AOSP
4. 建立兼容报告生成器。
5. 建立风险开关：
   - diagnostic mode
   - compatibility mode
   - strict authorized mode

验收：

- 通用容器不依赖 QQ 阅读专项。
- QQ 阅读 profile 能独立启用/禁用。
- 每个 profile 有明确支持范围。

## 7. 第一轮 Sprint 任务

第一轮不要继续扩展 QQ 阅读业务 patch。先做框架边界。

### Sprint 1.1：文档和基线

交付：

- 本文档。
- 当前 dirty worktree 快照。
- 当前 QQ 阅读验证基线。

负责人：

- 技术负责人
- QA/诊断工程师

### Sprint 1.2：模型层

任务：

- 增加 `VirtualPackageRecord`。
- 增加 `VirtualInstanceRecord`。
- 增加 `InstallArtifactManifest`。
- 明确 origin APK、stub APK、loader、native lib hash。

验收：

- 单元测试覆盖序列化/反序列化。
- 构建产物能输出 manifest。

### Sprint 1.3：RuntimeBootstrap 结果模型

任务：

- 增加 `RuntimeStage`。
- 增加 `BootstrapResult`。
- 在现有 `LoaderFactory` 外围先记录阶段，不立即迁移全部行为。

验收：

- 启动日志按阶段输出。
- 失败时能定位阶段。

### Sprint 1.4：ProtectedAppBaselineMode

任务：

- 增加加固 App 无 Hook 基线模式。
- 在该模式下明确关闭：
  - LSPlant
  - Xposed modules
  - business native stubs
  - method no-op/replacement patch
- 只保留容器化必需的安装态、包名、签名、路径、系统服务虚拟化。

验收：

- QQ 阅读 baseline mode 日志能证明是否进入原壳加载链路。
- baseline mode 失败时能明确指向容器缺口，而不是直接启用 hook 绕过。

### Sprint 1.5：NativeHookPolicy

任务：

- 把现有 native hook 开关收敛成 policy。
- `RegisterNatives logger` 和 `FindClass hook` 默认只在诊断/profile 下启用。

验收：

- 普通 App 模式不启用加固诊断。
- QQ 阅读 baseline mode 不依赖 native hook；诊断模式输出原壳注册证据或明确失败原因。

## 8. 验证命令模板

ADB 路径：

```powershell
$adb = 'C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe'
```

通用验证：

```powershell
& $adb logcat -c
& $adb shell am force-stop <stub.package>
& $adb shell monkey -p <stub.package> 1
Start-Sleep -Seconds 10
& $adb logcat -d -v time AndroidRuntime:E MultiApp.POC:D MultiApp.Native:D '*:S'
& $adb shell dumpsys activity exit-info <stub.package>
```

QQ 阅读关键日志筛选：

```powershell
rg -n "RegisterNatives: class=com.stub.StubApp|interface20|libjiagu_vip.so|FindClass|UnsatisfiedLinkError|tgkill|JNI_OnLoad" .tmp\*.txt
```

通过证据：

```text
RegisterNatives: class=com.stub.StubApp count=10
captured original interface20
RegisterNatives: result=0 class=com.stub.StubApp
no UnsatisfiedLinkError: com.stub.StubApp.interface20
```

## 9. 风险和负责人把关规则

1. `LoaderFactory` 继续膨胀是最大工程风险。
2. native hook 必须 profile-controlled，不能全局默认打开。
3. QQ 阅读首先必须验证 hook-free 容器基线；不能把 LSPlant 作为默认前置条件。
4. QQ 阅读能打开前台不等于正常使用。
5. 业务 native stub 只能用于诊断对照，不能作为产品修复。
6. 每一轮变更必须可回滚、可关闭、可定位。
7. 没有真机日志、exit-info、maps/nativeLibraryDir 证据的结论，不进入主线。

## 10. 下一步执行顺序

推荐立即按以下顺序开工：

1. `docs/index.md` 挂本文档入口。
2. 新建模型层：`VirtualPackageRecord`、`VirtualInstanceRecord`、`InstallArtifactManifest`。
3. 在 `LoaderFactory` 外围加 `RuntimeStage`/`BootstrapResult` 日志，不改变行为。
4. 新建 `ProtectedAppBaselineMode`，明确关闭 LSPlant/Xposed/business stubs。
5. 跑普通 App 样本，证明框架拆分未破坏现有基础启动。
6. 跑 QQ 阅读 baseline 样本，只判断容器环境本身能否推进到原壳加载链路。
7. 新建 `NativeHookPolicy`，先只包现有开关，不移动底层 native 实现。
8. 新建 `Jiagu360Profile` 壳接口骨架，先做 detect/verify 日志，不做 patch。
9. 只有 baseline 失败且证据明确时，再启用 profile-controlled native diagnostics 或可选 LSPlant。

这套顺序的原则是：先把边界、证据和开关补齐，再迁移行为；先保证普通 App 不坏，再用无 LSPlant 的 baseline 处理加固 App；先证明容器环境是否足够，再证明原壳真实初始化，最后才谈可选 hook 和基础使用。
