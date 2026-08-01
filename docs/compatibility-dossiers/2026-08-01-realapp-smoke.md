# 真实应用兼容性冒烟基线（2026-08-01）

> 首份设备基线数据：popsicle（API 36 / HyperOS / arm64），host=com.multiapp.app hosted flavor
> 测试：`RealAppCompatibilitySmokeTest`（诊断语义，不断言成功）
> 流程：import（engine createInstance）→ launchInstance → 20s evidence 窗口 → deleteInstance 清理

## 结果总览

| 应用 | 特征 | 导入(install) | 建实例(create) | 启动(launch) | evidence | 失败原因 |
|------|------|:---:|:---:|:---:|:---:|------|
| 微信 com.tencent.mm | 加固/大型(266MB) | ✅ PASS | ✅ PASS | ❌ FAIL | 0 | `authoritative runtime state missing`（STALE fail-closed） |
| 起点读书 com.qidian.QDReader | 阅文系/加固(115MB) | ✅ PASS | ✅ PASS | ❌ FAIL | 0 | 同上 |
| WPS cn.wps.moffice_eng | 办公(188MB) | ✅ PASS | ✅ PASS | ❌ FAIL | 0 | 同上 |
| 微博 com.sina.weibo | 社交(185MB) | ✅ PASS | ✅ PASS | ❌ FAIL | 3 | `Guest Application creation failed: Permission Denial` 打开自营 `AppMonitorProvider`（not exported，UID 不匹配） |
| 酷狗 com.kugou.android.lite | 轻量(73MB) | ✅ PASS | ✅ PASS | ✅ **BOOTSTRAPPED** | 3 | — |
| 高德 com.autonavi.minimap | so 密集(165MB) | ✅ PASS | ✅ PASS | ✅ **BOOTSTRAPPED** | 3 | — |
| Minimal fixture | 对照 | ✅ PASS | ✅ PASS | ✅ **BOOTSTRAPPED** | 3 | — |

**结论：7/7 导入全通（含微信 266MB 加固 APK），3/7 启动成功。** 导入层零障碍；启动层已对常规应用可用（含 so 密集的高德）。

## 根因分类（4 个失败项 → 2 类产品缺口）

### A. `authoritative runtime state missing`（微信/起点/WPS）— 3 项

- 来源：`EngineProcessBootstrapTransport.call` PREPARE 阶段（`EngineProcessBootstrapTransport.kt:754`）
- 机制：guest 进程（:vN）bootstrap PREPARE 时 `engineQueryRuntimeState(instanceId, runtimeEpoch, engineSessionId, processSlot, attachCapability)` 返回 null → fail-closed `STALE`
- 特征：与 Pr10 双启动（跨进程 runtime 状态同步）疑似同源——engine 进程与 host 进程的 runtime 状态不同步；但同轮测试中酷狗/高德能过，说明差异在**应用侧 bootstrap 路径**（加固壳抢先改写进程/类加载，导致 PREPARE envelope 的 runtimeEpoch/sessionId 与 engine 侧不一致）
- 待验证：engine 侧 `engineQueryRuntimeState` 的 miss 分支日志（当前无日志），对比加固/非加固应用的 bootstrap envelope 参数

### B. self-provider UID 隔离冲突（微博）— 1 项

- 证据：`Permission Denial: opening provider com.sina.weibo.appmonitor.provider.AppMonitorProvider from ProcessRecord{...:com.multiapp.app:v21/u0a473} (pid=16251, uid=10473) that is not exported from UID 10328`
- 机制：微博 guest Application 创建时尝试打开**自营非导出 provider**（AppMonitorProvider），hosted 模式下 guest 进程 UID=10473（host），系统按 UID 隔离拒绝（应为微博自身 UID 10328）
- 对应控制项：P0-COMPAT 系列 "Provider data plane satisfies whitelist use"（control-register.json:121）——**Provider data plane 虚拟化的真实缺口**，guest 需以 origin UID 语义访问 self-provider
- evidence=3 说明已走到 Guest Application 创建阶段（比 A 类更深入）

## 对控制项的意义

- **P0-COMPAT-01**：从 `6/6 BLOCKED - no APK` 推进为 **有设备基线数据**：导入层 7/7 PASS 已验证；启动层 3/7 PASS；失败 4 项均为已定位的产品缺口（2 类）
- 启动失败的 A/B 两类分别对应已登记的架构缺陷（跨进程 runtime 同步、Provider data plane）

## 方法备注

- 诊断式测试：不 assert 导入/启动成功，失败本身即兼容性数据；dossier 写 `filesDir/compat_dossier/YYYY-MM-DD.txt`
- 候选应用从设备 `pm list packages -3` 实选，覆盖：加固/大型、阅文系、社交、办公、轻量、so 密集 六种特征
- 每应用 `deleteInstance` 清理，无设备残留
