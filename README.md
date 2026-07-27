# MultiApp

MultiApp 是一个面向 Android 9+（API 28+）的无 Root、用户态 hosted 应用容器实验项目。项目目标是由宿主进程提供虚拟 PMS/AMS/Provider/Service/Broadcast/Storage/Native 等能力，让多个实例在同一个宿主 APK 内隔离运行。

> **成熟度：`alpha / BLOCK`**
> `BLOCK` 表示当前代码尚未达到商业发布条件，并不表示项目无法编译。JVM 测试、APK 构建或 evidence 文件存在，都不能替代真实 Android 设备上的端到端证据。

当前主线采用 VirtualApp/BlackBox 风格的用户态虚拟系统，不是“每个实例重新打包并安装一个 Stub APK”的旧方案，也不是 RePlugin 风格的合作式插件框架。

## 架构

```text
:app / :feature:*
        │
        │ VirtualizationEngine API
        ▼
┌──────────────────────────────────────────────────────────────┐
│ :core:engine / Android :engine process                       │
│                                                              │
│ package + runtime + process generation + component routing   │
│ permission/AppOps + storage/native policy + evidence         │
│                                                              │
│ 唯一权威：状态、授权、路由和持久化决策                         │
└──────────────────────┬───────────────────────────────────────┘
                       │ Binder / generation-scoped capability
                       ▼
┌──────────────────────────────────────────────────────────────┐
│ Android :vN guest process pool                               │
│                                                              │
│ Container/Proxy Activity + Stub Service/Provider             │
│ HostedRuntimeBootstrap + loader/framework adapters           │
│ guest ClassLoader / Resources / Application / components     │
└──────────────────────────────────────────────────────────────┘
```

基本边界如下：

| 层 | 当前职责 |
|---|---|
| `:app`、`:feature:*` | UI、Android 组件载体和 engine API 调用；不得自行维护虚拟运行时事实 |
| `:core:model` | `VirtualizationEngine`、请求/结果、profile 和 evidence 公共契约 |
| `:core:engine` | 虚拟包、进程、Activity、Provider、Service、Broadcast、Permission、AppOps、Storage、Native 的权威控制面 |
| `:core:loader` | 在 guest 进程执行 engine 下发的加载与 Android framework 适配计划 |
| `:core:identity`、`:core:hook` | 基础拦截设施和显式兼容 profile；不是虚拟系统事实源 |
| `:core:stub` | 仅保留 Legacy Stub 构建、旧实例和证据对照；hosted v2 实例不应走此路径 |
| `:core:xposed` | 实验性 Xposed API 兼容代码；默认 baseline 不启用 |

hosted 启动以 `InstallRecord` 和 `VirtualInstanceRecord` 为输入。`:engine` 校验实例、包代际、进程槽和 capability 后，`:vN` 进程才执行 guest APK、ClassLoader、Resources、Application 和组件绑定。实例或 generation 不一致时必须 fail-closed，不能把 guest 内部调用静默回落给宿主系统。

## 当前能力状态

项目使用 `PASS / PARTIAL / UNSUPPORTED / FAIL` 描述能力。release-critical 能力只有在当前代码、指定设备矩阵和可追溯证据都满足时才能标记为 `PASS`。

| 领域 | 状态 | 当前边界 |
|---|---|---|
| 包与实例 | `PARTIAL` | 已有导入、实例记录、generation 和删除控制面；同包升级的完整原子切换/回滚与最终包级回收仍需闭环 |
| Activity | `PARTIAL` | 已有 proxy/slot、任务记录和启动恢复基础；result/finish-result、完整 `onNewIntent`、Recents 真机恢复仍未完成验证 |
| Provider | `PARTIAL` | 已有 authority/route-token、同进程预安装和常用操作路由；external URI grant、完整 custom-process Provider、observer/FD 等仍缺闭环 |
| Service | `PARTIAL` | 已有 manifest 路由及部分 start/stop/bind 状态；跨进程、Binder death/rebind、foreground service type、sticky restart 未完成 |
| Broadcast/Receiver | `PARTIAL` | 已有显式/隐式 manifest 路由基础；ordered、sticky、result/abort、权限/AppOps、`asUser`、options、跨进程语义未完成 |
| Permission | `PARTIAL` | 已有实例级 grant 记录与 check/set/revoke；runtime permission dialog、flags、one-time、auto-reset、shared UID 未完成 |
| AppOps | `PARTIAL` | 已有 check 与实例 mode；`note/start/finish` operation 和 attribution chain 未完成 |
| Storage/Media | `PARTIAL` | Java private path、process-slot native binding 和 containment 已有基础；external storage policy、MediaProvider 隔离未完成 |
| Native | `PARTIAL` | 已有 private path redirect 和进程槽绑定；完整 syscall、linker namespace、runtime native load、RegisterNatives 真机结论未完成 |
| Split（非 isolated） | `PARTIAL` | 基础 APK 与 split 加载存在；dynamic feature、multidex 和 split native 尚未完成设备闭环 |
| Isolated split | `UNSUPPORTED` | 当前 loader 明确拒绝 isolated split，不能计入已支持能力 |
| WebView | `PARTIAL` | 有外部 renderer passthrough 基础；数据目录隔离、Chromium renderer/JNI 和首帧尚无完整设备证据 |
| Notification | `PARTIAL` | 只有 caller package remap 基础；实例 ID/channel/PendingIntent 映射、持久化和 delete 清理未完成 |
| JobScheduler/Alarm/PendingIntent/MediaProvider | `UNSUPPORTED` | engine capability catalog 默认标记为 `not-implemented`，尚无可发布的数据面 |

更细的当前状态和开源方案对照见：

- [开源引擎对照与路线结论](docs/reviews/2026-07-09-open-source-engine-comparison.md)
- [商业引擎进展](docs/progress/2026-07-07-commercial-engine-progress.md)
- [容器运行时缺陷审查](docs/reviews/2026-07-07-container-runtime-bug-audit.md)
- [hosted container 当前状态](docs/container-runtime-refactor/v2-current-state-refresh-2026-06-29.md)

## Legacy、兼容层与不支持声明

- **Legacy Stub**：`:core:stub` 和 `StubInstaller` 仍在仓库中，用于旧实例、实验与证据对照；它们不是 hosted 主线架构。
- **Shizuku**：`ShizukuInstaller` 当前明确返回 `Shizuku installation is not yet supported`。不要把它描述为已支持的静默安装方案。
- **LSPlant / Xposed**：只允许在显式 package+instance allow-list 的兼容 profile 中启用。`BASELINE` 不依赖 LSPlant、Xposed、proc spoof、业务 native stub 或 no-op patch。
- **加固应用**：仓库包含诊断和 profile 化兼容代码，但这不等于 360 加固、QQ、微信、QQ 阅读、Edge/Chromium 等已经兼容。必须分别提供当前 APK、设备和运行路径的冷启动、首帧、交互、恢复证据。
- **身份与签名**：Hook 安装日志或宿主侧字段变化，不等于 guest 实际观察值已正确隔离；必须由 guest fixture 或真实应用验证。
- **Service/Receiver/Native**：当前只有部分生命周期、路由或重定向能力，不能表述为“所有进程正常启动”或“native/加固完整支持”。
- **Work Profile**：`:core:workprofile` 是独立实验模块，不属于 hosted 商业主线。

## 技术栈与工具链

- Kotlin、Jetpack Compose、Material 3
- Hilt、Room、KSP
- LSPlant、shadowhook（仅作为 Hook/兼容基础设施）
- Gradle Wrapper `9.6.1`
- Android Gradle Plugin `9.3.0`
- JDK `17`
- `compileSdk 37`、`targetSdk 37`、`minSdk 28`（Android 9）
- Android Build Tools `37.0.0`
- Android NDK `29.0.14206865`
- CMake `4.1.2`

应使用仓库内的 Gradle Wrapper，不需要另行安装系统 Gradle。Android SDK 组件可通过 `sdkmanager` 安装：

```text
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "ndk;29.0.14206865" "cmake;4.1.2"
```

## 构建与测试

Windows PowerShell：

```powershell
.\gradlew.bat lintDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Linux/macOS：

```bash
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

连接 Android 设备或已启动模拟器后，可运行 instrumentation tests：

```powershell
.\gradlew.bat :test-fixtures:minimal-app:assembleDebug
adb install -r .\test-fixtures\minimal-app\build\outputs\apk\debug\minimal-app-debug.apk
.\gradlew.bat :app:connectedDebugAndroidTest
```

`com.test.minimal` fixture 是 hosted container 关键 instrumentation 的强制前置条件；缺失 fixture、零测试或任何 skipped test 都必须判定为门禁失败。

CI 分为两类硬门禁：

1. JDK 17 + 固定 Android/Native 工具链上的 lint、全量 JVM test、JaCoCo、Debug/Release APK；任何步骤失败都会使 job 失败。
2. API 28、30、33、35 使用 `google_apis` x86_64，API 37 使用 `android-37.0 / google_apis_ps16k` x86_64，执行 `:app:connectedDebugAndroidTest`；矩阵不 fail-fast，但任何一个 API 失败都会使工作流失败。

CI 会解析 JUnit XML，强制关键 hosted tests 已执行且 `skipped=0`，并上传工具链版本、commit、设备属性、logcat，以及从设备实际安装路径拉取 APK 后计算的 SHA-256。模拟器测试仍不能替代 arm32/arm64 与厂商真机（尤其 HyperOS）的发布证据。

## 证据规则

- JVM test 证明 resolver、状态机和契约行为，不证明 Android framework 的真实兼容性。
- skipped instrumentation test 不计为能力通过。
- evidence 文本存在不等于语义正确；关键字段必须由测试断言或人工审查。
- 应用兼容结论必须绑定 commit、APK SHA-256、设备 fingerprint、测试结果、logcat 和首帧/交互证据。
- 在 P0 release-critical 能力全部为 `PASS` 前，项目状态保持 `alpha / BLOCK`。

## 许可证

当前仓库未包含独立的 `LICENSE` 文件。在补充明确许可证前，不应仅根据旧 README 的文字假定代码可按 MIT 或其他许可证再分发。
