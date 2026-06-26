# MultiApp v2 仓内彻底重构执行方案

日期：2026-06-26

分支：`container-runtime-refactor`

定位：开源学习向 App 级虚拟化多开容器

目标样本：普通 App baseline 优先，QQ 阅读 protected baseline 作为首个加固壳兼容目标

## 1. 最终决策

MultiApp v2 采用 **当前仓库内的新内核式彻底重构**，不是另起一个全新仓库，也不是在
旧 `Stub clone APK + LoaderFactory` 路线上继续小修小补。

```text
保留当前仓库、分支、构建系统、设备验证资产和历史证据
冻结旧 Stub transitional container 的功能增长
在 canonical Gradle module 中建设 v2 container kernel
v2 新实例直接走 MultiApp-hosted container launch，不再生成新的 Stub APK
旧实现只保留为 legacy evidence collector / 对照组
```

一句话：**MultiApp v2 是当前项目的仓内内核重写，不是旧项目补丁，也不是 greenfield 项目。**

## 2. 为什么不新建项目

全新项目的优势是目录干净，但在当前阶段收益不高：

1. 现有仓库已经有 Gradle、多模块、签名、安装、Stub 创建、启动和真机日志链路。
2. QQ 阅读 baseline 已经跑出关键证据：`APPLICATION SUCCESS` 后死于
   `com.stub.StubApp.interface20()` 未注册。
3. 现有 `core/model`、`core/loader`、`core/hook` 已经有 v2 初始模型和证据 primitives。
4. 真正难点不是工程脚手架，而是 Virtual PMS/AMS/Provider/Storage、native namespace、
   ClassLoader identity 和 instance data isolation。
5. 新仓库会先消耗时间追平旧仓库能力，最终仍会回到同一组容器内核问题。

因此，当前仓库内重构更合理：保留可运行资产，把旧实现变成对照组，同时建设新内核。

## 3. 产品目标

最终产品形态是一个 App 级虚拟化多开容器，用户侧效果接近悟空分身、团团分身、比翼多开
这类产品：

```text
用户选择已安装 App 或 APK
-> 创建一个或多个虚拟实例
-> 每个实例有独立数据、图标/名称/通知/最近任务归属
-> 普通 App 稳定多开
-> 加固 App 尽量在不破壳、不改壳、不默认 Hook 的情况下正常启动
-> LSPlant/Xposed 作为可选扩展层，不是 baseline 依赖
```

对 QQ 阅读的产品验收画面：

```text
MultiApp 中创建 QQ 阅读实例
点击启动
QQ 阅读原壳完成初始化
不因 interface20 / RegisterNatives 链路失败闪退
进入正常界面
实例数据与原 App 隔离
```

## 4. 架构目标

v2 container kernel 的目标架构：

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
  - InstallRecordStore

core/instance
  - InstanceManager
  - InstanceDataRoot
  - InstanceLifecycle
  - InstanceProfile

core/virtual
  - VirtualPackageManager
  - VirtualActivityManager
  - VirtualProviderManager
  - VirtualStorageManager
  - VirtualPermissionManager

core/loader
  - RuntimeBootstrap
  - HostedRuntimeBootstrap
  - BootstrapStage
  - BootstrapResult
  - GuestContextStage
  - PackageMetadataStage
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

app/container
  - hosted container entry
  - ContainerActivity
  - container process entry

app/legacy-stub
  - evidence collector
  - compatibility comparison path
```

关键边界：

1. v2 新实例不再以独立 Stub APK 作为启动和运行模型。
2. `LoaderFactory.kt` 只能作为 legacy 对照链路逐步瘦身，不再承载 v2 容器内核。
3. `StubBuilder` 只服务旧实例和对照验证，不再参与 v2 新实例创建。
4. 虚拟安装和虚拟实例必须由 `VirtualInstallService` / `InstanceManager` 管理。
5. PMS/AMS/Provider/Storage 兼容能力进入 `core/virtual`，不能散落在启动入口。
6. protected baseline 默认关闭 LSPlant、Xposed、business native stubs、method replacement、
   no-op patches。

## 5. 分阶段执行计划

### Phase 0：冻结旧实现和建立门禁

目标：阻止旧路线继续膨胀，保证后续重构不会被专项 patch 拉回去。

任务：

1. 为 `LoaderFactory.kt` 建立 LOC、分支数、特殊符号增长检查。
2. 禁止 `com.qq.reader`、`interface20`、`libjiagu` 等特殊符号扩散到非 profile/diagnostics
   模块。
3. 把 `QQ_READER_SPECIAL` 明确标记为 manual legacy/diagnostic 对照组。
4. 给 protected baseline 增加单测，确认以下开关默认关闭：

```text
lsPlantEnabled=false
xposedModulesEnabled=false
businessNativeStubsEnabled=false
methodReplacementEnabled=false
noOpPatchesEnabled=false
```

退出条件：

```text
旧实现仍可启动 baseline
新增逻辑不能进入 LoaderFactory 巨型流程
文档和单测都能证明 protected baseline 不依赖 patch/hook
```

### Phase 1：Virtual Install DB 接管创建流程

目标：让“创建分身”先落到虚拟安装记录，而不是先生成 Stub APK。

任务：

1. 新增 `core/installer` 模块或包。
2. 实现 `VirtualInstallService`：导入 origin APK、计算 digest、读取 package metadata、生成
   `VirtualPackageRecord`。
3. 实现 `ArtifactStore`：管理 origin APK、optimized artifact、native libs artifact。
4. 实现 `InstallRecordStore`：先用文件型 JSON/Proto 存储，后续可替换为 SQLite/Room。
5. 创建流程改为：

```text
origin APK / installed package
-> VirtualInstallService.importPackage()
-> VirtualPackageRecord persisted
-> InstallArtifactManifest persisted
-> no Stub APK generated for v2 instances
```

退出条件：

```text
不用启动 App，也能完成虚拟安装记录创建、读取、删除
同一 origin APK 重复导入可复用或生成明确版本记录
artifact path、digest、package metadata 可被测试覆盖
```

### Phase 2：Virtual Instance Model 接管实例身份

目标：让多开实例由 `VirtualInstanceRecord` 决定，而不是由 stub package name 临时拼出来。

任务：

1. 新增 `core/instance` 模块或包。
2. 实现 `InstanceManager`：创建、查询、删除、清理数据、列出实例。
3. 实现 `InstanceDataRoot`：为每个实例分配独立 data/cache/files/shared prefs/storage 根目录。
4. 建立 instance identity：

```text
instanceId
originPackageName
virtualPackageName / launcherPackageName
displayName
iconPolicy
dataRoot
compatibilityMode
protectedBaselinePolicy
```

5. UI/CLI/工具脚本的创建动作改为只创建 instance record；启动交给 hosted container
   entry，不进入 Stub APK 生成。

退出条件：

```text
同一个 origin package 可创建两个实例
两个实例 dataRoot 不同
删除实例可以清理 record 和数据目录
MultiApp-hosted container 可以根据 instanceId 启动目标实例
```

### Phase 3：RuntimeBootstrap stage 化替换 LoaderFactory 巨型流程

目标：把启动流程拆成可测试、可记录、可裁决的 stage。

任务：

1. 将现有 `LoaderFactory.initializeInternal()` 的关键步骤映射为 stage：

```text
CONFIG
GUEST_CONTEXT
PACKAGE_METADATA
ORIGIN_APK
NATIVE_LIBS
CLASS_LOADER
RESOURCES
VIRTUAL_SERVICES
APPLICATION
```

2. 每个 stage 输出 `BootstrapResult`，包括：

```text
stage
status
elapsedMs
evidence
errorClass
errorMessage
reversibility
```

3. `LoaderFactory` 只负责收集入口参数、构造 `RuntimeBootstrap`、执行 stage、记录结果。
4. 对 irreversible stage 明确 rollback 语义：放弃启动、保留证据、清理可清理 artifact，不
   宣称状态恢复。

退出条件：

```text
普通 App baseline 能输出完整 BootstrapResult
QQ 阅读 baseline 能输出 CONFIG..APPLICATION 的成功/失败边界
LoaderFactory 新增代码量受控，复杂逻辑迁入 stage 类
```

### Phase 4：Hosted Container Launch MVP

目标：v2 新实例直接从 MultiApp 容器入口启动，不再生成新的独立 Stub APK。

任务：

1. 新增 hosted container entry，例如：

```text
app/container/ContainerActivity
app/container/ContainerProcess entry
core/loader/HostedRuntimeBootstrap
core/virtual/VirtualContextFactory
core/virtual/VirtualActivityController
```

2. MultiApp 内启动流程改为：

```text
LauncherScreen / AppManager
-> start ContainerActivity(instanceId)
-> InstanceManager.load(instanceId)
-> InstallRecordStore.load(installId)
-> RuntimeBootstrap.run(instance)
-> load guest dex/resources/native libs
-> create guest Application
-> launch guest launcher Activity through VirtualActivityController
```

3. `Intent extras` 只允许用于 MultiApp 启动自己的 `ContainerActivity`，作为进程内入口参数；
   不作为跨 Stub APK 的实例身份事实源。
4. 旧 `Stub clone APK + LoaderFactory` 不删除，但只作为 legacy 对照链路；v2 新实例不得再
   调用 `StubBuilder.build()` 生成独立安装包。
5. 第一轮只要求跑通 minimal test app 的主进程 launcher Activity，不要求一次覆盖所有
   Service/Receiver/Provider/secondary process。

退出条件：

```text
MultiApp 内可选择一个 VirtualInstanceRecord 启动
启动链路不生成新的 Stub APK
RuntimeBootstrapContext 携带 instanceId / installId / originPackageName
能加载 origin APK dex 和 resources
能创建 guest Application 或输出明确失败 stage
minimal test app 至少进入 launcher Activity 或失败在可解释 stage
旧 QQ 阅读 Stub APK 不受影响，继续作为 legacy 对照组
```

### Phase 5：Virtual PMS / AMS / Provider / Storage 最小闭环

目标：让 guest 看到接近真实安装态的系统服务视图。

任务：

1. `VirtualPackageManager`：提供 `PackageInfo`、`ApplicationInfo`、签名 digest、sourceDir、
   publicSourceDir、nativeLibraryDir、permissions 视图。
2. `VirtualActivityManager`：处理启动 intent、task/recent identity、service binding 基础映射。
3. `VirtualProviderManager`：处理 authority 映射、provider 初始化顺序和实例隔离。
4. `VirtualStorageManager`：处理 files/cache/external files/media 视图和路径重写。
5. 对普通 App 建立回归样本：Activity、Service、Receiver、Provider、SQLite、SharedPreferences、
   external files、notification。

退出条件：

```text
普通 App 单实例稳定启动
普通 App 双实例数据隔离
provider/storage 不串写
notification/recent task 至少能归属到正确实例或有明确限制说明
```

### Phase 6：NativeDiagnosticsProfile(register-natives-only)

目标：解释 QQ 阅读 `interface20` 崩溃，不通过补 stub 掩盖问题。

任务：

1. 建立 register-natives-only 诊断 profile，记录但不替换：

```text
nativeLoad
JNI_OnLoad
FindClass
RegisterNatives
library path
native namespace
class loader
/proc/<pid>/maps
linker message
```

2. 对 QQ 阅读建立真实安装对照和容器启动对照。
3. 输出 `interface20` 根因矩阵 verdict：

```text
JNI_OnLoad not executed
RegisterNatives not executed
RegisterNatives wrong class
FindClass wrong ClassLoader
native namespace mismatch
shell detected container and skipped registration
unknown / insufficient evidence
```

退出条件：

```text
每次 QQ 阅读 baseline 崩溃都有可读 summary
能明确判断下一步修 native namespace、ClassLoader、PMS identity 还是其他容器缺口
仍不启用 business stubs、method replacement、no-op patch 或默认 LSPlant
```

### Phase 7：Protected App hook-free 兼容推进

目标：在不破壳、不改壳、不默认 Hook 的前提下，让 QQ 阅读原壳初始化成功。

任务：

1. 根据 Phase 6 verdict 修复容器真实安装态差异。
2. 优先修：

```text
ApplicationInfo.sourceDir / publicSourceDir
nativeLibraryDir
dataDir / credential/device protected storage
package name / process name / uid-like identity view
signature / installer / permissions view
ClassLoader identity
provider authority
```

3. 每次修复后跑普通 App 回归，再跑 QQ 阅读 protected baseline。

退出条件：

```text
QQ 阅读不再死于 interface20 / RegisterNatives 缺失
至少进入正常启动页或首页
普通 App 回归不退化
```

### Phase 8：产品化实例管理

目标：让 v2 从工程验证变成可用的多开产品。

任务：

1. 应用列表：从已安装应用和 APK 文件创建实例。
2. 实例列表：启动、停止、删除、清理数据、重命名。
3. 快捷方式：每个实例可创建桌面入口。
4. 可观测性：导出启动日志、BootstrapResult、NativeDiagnostics summary。
5. 兼容模式：标准、兼容、诊断、可选 LSPlant/Xposed。

退出条件：

```text
普通用户无需理解内部 stage 也能创建和启动实例
开发者能导出完整诊断包
实例删除后无明显数据残留
```

### Phase 9：可选 LSPlant/Xposed Runtime

目标：在容器 baseline 稳定后，提供可选扩展能力。

任务：

1. LSPlant/Xposed 只对显式启用的实例生效。
2. 模块管理与容器实例绑定，不影响 protected baseline。
3. 建立模块 API stub 和兼容层测试。
4. 明确禁止用它替代 PMS/AMS/Provider/Storage/native namespace 虚拟化。

退出条件：

```text
关闭 LSPlant/Xposed 时 baseline 行为不变
启用扩展层有独立日志、开关和失败隔离
```

## 6. 代码迁移规则

1. 生产代码必须放回 canonical Gradle module，不在 `docs/container-runtime-refactor/` 中运行。
2. 旧实现可读、可对照，但 v2 新实例不能调用旧 Stub APK 生成和 LoaderFactory 运行链路。
3. 每次迁移只改一个边界：installer、instance、bootstrap、virtual service、diagnostics。
4. 每个边界必须有单测或最小真机验证脚本。
5. 不做“大爆炸式一次性替换”；采用 strangler pattern：新内核逐步接管旧入口。

## 7. 文件和模块落点

优先落点：

```text
core/model/src/main/...
core/installer/src/main/...      # 如新增模块成本过高，先落 core/model 或 core/runtime 包内
core/instance/src/main/...
core/loader/src/main/...
core/hook/src/main/...
app/src/main/...                 # MultiApp UI、hosted container entry、legacy 对照入口 glue
tools/qqreader-baseline/...      # 真机 evidence automation，暂不默认纳入提交
docs/container-runtime-refactor/ # 计划、证据、迁移日志
```

如果新增 Gradle module 会拖慢第一轮推进，可以先在现有 `core/model`、`core/loader`、`core/hook`
内按 package 切分，待 API 稳定后再拆模块。

## 8. 验证矩阵

每个阶段至少保留三层验证：

```text
JVM/Android unit tests
普通 App 真机 baseline
QQ 阅读 protected baseline diagnostics
```

最小命令集：

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:loader:testDebugUnitTest :core:hook:testDebugUnitTest "-Dkotlin.compiler.execution.strategy=in-process" --console=plain
```

真机证据包必须包含：

```text
device/model/API/ABI/page size
origin package/version/signature digest
clone or instance id
install/create time
am start output
logcat main/system/crash
dumpsys activity exit-info
/proc/<pid>/maps when available
BootstrapResult summary
NativeDiagnosticsProfile verdict when enabled
```

## 9. 风险和处理

| 风险 | 处理 |
| --- | --- |
| 旧 `LoaderFactory` 继续膨胀 | Phase 0 门禁，新增能力必须进 stage/profile/virtual service |
| 新增模块成本过高 | 先 package 切分，后续再拆 Gradle module |
| 普通 App 回归被 QQ 阅读牵着走 | 每次 protected baseline 前先跑普通 App baseline |
| `interface20` 被错误 patch 掩盖 | register-natives-only diagnostics，不默认 stub/no-op |
| 商业产品参照被误读成实现确定性 | 文档只作为能力参照，不推断内部实现或权限关系 |
| native namespace 问题难以定位 | 强制 maps/linker/nativeLibraryDir/FindClass/RegisterNatives 证据包 |

## 10. 第一轮 10 个执行任务

1. 新增 `VirtualInstallService` 接口和文件型 `InstallRecordStore`。
2. 将 `VirtualPackageRecord` 接入 origin APK import 流程。
3. 新增 `InstanceManager` 接口和文件型 `VirtualInstanceRecord` store。
4. 新增 `ContainerActivity` / hosted container entry，从 MultiApp 内按 `instanceId` 启动。
5. 新增 `HostedRuntimeBootstrap` 或等价入口，直接消费 `VirtualInstanceRecord` 和
   `InstallRecord`，不生成 Stub APK。
6. 加载 origin APK dex/resources/native libs，创建 guest `Application`，输出明确 stage 结果。
7. 给 hosted bootstrap 的每个 stage 增加 reversibility 声明。
8. 增加 `BootstrapResult` summary parser，供真机脚本生成摘要。
9. 建立一个普通 App 双实例 baseline 用例。
10. 跑 QQ 阅读 register-natives-only diagnostics，输出 `interface20` 根因矩阵 verdict。

## 11. 当前不做的事

1. 不新建独立仓库。
2. 不为 v2 新实例生成新的 Stub APK。
3. 不把 QQ 阅读专项 patch 写回默认路径。
4. 不默认启用 LSPlant/Xposed。
5. 不默认补 `interface20` native stub。
6. 不做脱壳、改壳、破坏壳。
7. 不承诺会员、支付、DRM、登录风控等业务能力兼容。

## 12. 下一步落地顺序

立即执行顺序：

```text
Step 1: 文档冻结 v2 in-repo kernel rewrite 决策
Step 2: 实现 VirtualInstallService + InstallRecordStore
Step 3: 实现 InstanceManager + VirtualInstanceRecord persistence
Step 4: Hosted Container Launch MVP，从 MultiApp 内按 instanceId 启动
Step 5: Virtual PMS / AMS / Provider / Storage 最小闭环
Step 6: 普通 App 双实例 baseline
Step 7: QQ 阅读 register-natives-only diagnostics
```

成功标准：

```text
创建实例不再以 Stub APK 为事实源头
v2 新实例启动不再生成或安装 Stub APK
启动流程不再以 LoaderFactory 巨型方法为事实源头
普通 App 多实例隔离稳定
QQ 阅读崩溃能被诊断矩阵解释并指向容器缺口
```
