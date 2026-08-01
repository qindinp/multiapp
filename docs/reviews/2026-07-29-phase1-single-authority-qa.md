# MultiApp Phase 1 单权威批次 QA 最终报告

## Summary

- 验收日期：2026-07-29
- QA_PASS: **NO**
- ROUTE: **QA**
- 生产源码已知 Bug：**无**
- 结论：生产实现通过静态审查及首轮测试；QA 发现源码边界测试的正向断言盲区，并仅在隔离副本中制作测试补丁。由于第 2 轮回归受 Gradle 文件锁/权限问题阻塞，未经回归的测试补丁未合入原工作区，因此本轮严格判定 `QA_PASS: NO`、`ROUTE: QA`。

## 文件一致性

以下 4 个文件在原工作区 `C:\Users\20237\Documents\multiapp` 与隔离副本 `C:\Users\20237\Documents\multiapp-phase1-build` 的 SHA-256 逐一一致：

1. `core/instance/src/main/java/com/multiapp/core/instance/CloneCreateUseCase.kt`
2. `core/instance/src/test/java/com/multiapp/core/instance/CloneCreateUseCaseTest.kt`
3. `core/instance/src/test/java/com/multiapp/core/instance/InstanceBoundaryModuleTest.kt`
4. `app/src/test/java/com/multiapp/app/EngineDependencyInjectionBoundaryTest.kt`

## 静态审查

生产源码未发现已知 Bug，已确认：

- `CloneCreateUseCase` 的实例生命周期生产逻辑仅依赖 `VirtualizationEngine`。
- authority result 为 unknown 时保留 creation request ID。
- 确定性失败不保留 creation request ID。
- 不存在本地 `delete` 或 `rollback` 补偿逻辑。
- `displayName` 建议值通过 `VirtualizationEngine.listInstances()` 计算。
- 禁止类型在生产文件中零命中。

## Round 1 测试结果

| 测试范围 | 通过 | 失败 |
|---|---:|---:|
| Targeted：`CloneCreateUseCaseTest` + `InstanceBoundaryModuleTest` | 15 | 0 |
| App：`EngineDependencyInjectionBoundaryTest` | 4 | 0 |
| `:core:instance:testDebugUnitTest` | 84 | 0 |
| `:app:testDebugUnitTest` | 155 | 0 |

两模块全量测试的 Gradle 标准输出显示 `BUILD SUCCESSFUL`；随后在更新 Gradle 本地状态文件时出现 Windows 拒绝访问，详见 Known Issues。

## APK 验证

- 路径：`C:\Users\20237\Documents\multiapp-phase1-build\app\build\outputs\apk\debug\app-debug.apk`
- 大小：`97,042,240 bytes`
- SHA-256 前缀：`b2e7eaa7`
- 结果：APK 存在且非空。

## QA 发现与隔离副本补丁

### 正向断言盲区

`InstanceBoundaryModuleTest` 与 `EngineDependencyInjectionBoundaryTest` 的正向依赖断言原本仅在生产源码全文中搜索 `VirtualizationEngine`。该名称即使只出现在 KDoc 或注释中也可使断言通过，因此存在明显假阳性路径。

QA 仅在隔离副本中增强了以下测试文件：

- `core/instance/src/test/java/com/multiapp/core/instance/InstanceBoundaryModuleTest.kt`
- `app/src/test/java/com/multiapp/app/EngineDependencyInjectionBoundaryTest.kt`

补丁将正向断言加强为同时验证：

1. 精确的 `VirtualizationEngine` import；
2. `private val virtualizationEngine: VirtualizationEngine` 构造器依赖；
3. `virtualizationEngine.listInstances(...)` 或 `virtualizationEngine.createInstance(...)` 的实际调用。

未修改任何生产源码。原工作区未合入上述未经回归验证的测试补丁。

## Known Issues

第 2 轮回归无法完成，Windows 对以下 Gradle 文件报“拒绝访问”：

- Gradle wrapper 分发锁文件：`gradle-9.6.1-bin.zip.lck`
- 项目 Gradle 状态文件：`.gradle/9.6.1/fileChanges/last-build.bin`

因此未能为隔离副本中的测试补丁生成新的有效 JUnit XML 结果。按照最多 2 轮的验收规则，本轮不再进入第 3 轮，严格结论为 `QA_PASS: NO`、`ROUTE: QA`。

## 恢复验收命令

先清理占用上述 Gradle 锁/状态文件的进程或修复 ACL，然后在隔离副本中使用 JDK 17 执行：

```powershell
$env:JAVA_HOME = 'C:\Users\20237\.cache\codex-runtimes\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$project = 'C:\Users\20237\Documents\multiapp-phase1-build'
$gradlew = "$project\gradlew.bat"
```

### A. Targeted core tests

```powershell
& $gradlew -p $project `
  :core:instance:testDebugUnitTest `
  --tests 'com.multiapp.core.instance.CloneCreateUseCaseTest' `
  --tests 'com.multiapp.core.instance.InstanceBoundaryModuleTest' `
  --no-daemon --no-build-cache --max-workers=1 --console=plain
```

### B. App boundary test

```powershell
& $gradlew -p $project `
  :app:testDebugUnitTest `
  --tests 'com.multiapp.app.EngineDependencyInjectionBoundaryTest' `
  --no-daemon --no-build-cache --max-workers=1 --console=plain
```

### C. 两模块回归

仅在 A、B 均通过后执行：

```powershell
& $gradlew -p $project `
  :core:instance:testDebugUnitTest `
  :app:testDebugUnitTest `
  --no-daemon --no-build-cache --max-workers=1 --console=plain
```

若 A、B、C 均通过，再评估将隔离副本中的两个 QA 测试补丁同步到原工作区，并重新核验文件哈希与测试结果。
