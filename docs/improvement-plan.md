# MultiApp 项目完善计划

## 项目概览
- **架构**: Stub-Load + Identity Proxy
- **当前完成度**: ~75% (Alpha)
- **目标**: 从 Alpha 推进到 Beta，建立质量基线

---

## 阶段一：质量基线建设 (Week 1-2)

### 1.1 CI 流水线增强
**优先级**: P0 (阻塞所有后续工作)
**工作量**: 2-3h
**依赖**: 无

| 任务 | 说明 |
|------|------|
| 添加单元测试步骤 | 在 `android.yml` 中 `assembleDebug` 之前插入 `./gradlew testDebugUnitTest` |
| 添加代码覆盖率 | 集成 JaCoCo，生成报告并上传为 artifact |
| 测试失败阻断构建 | 移除 lint 的 `continue-on-error: true`，测试步骤失败时阻断 |

**验证方法**:
- PR 触发 CI 后，测试步骤必须执行
- 覆盖率报告可下载查看
- 测试失败时 CI 状态为红色

**成功标准**: CI 流水线包含 lint → test → build 三步骤，测试失败阻断合并

---

### 1.2 补充缺失的单元测试
**优先级**: P0
**工作量**: 8-12h
**依赖**: 1.1 (CI 能运行测试)

#### 1.2.1 feature:appmanager 测试 (3-4h)
| 文件 | 测试内容 |
|------|----------|
| `AppManagerViewModelTest.kt` | 初始加载、删除实例、撤销删除、展开/折叠、错误处理 |
| Mock `InstanceManager` | 使用 MockK 或接口抽象 |

#### 1.2.2 feature:settings 测试 (1-2h)
| 文件 | 测试内容 |
|------|----------|
| `SettingsViewModelTest.kt` | 初始状态验证 (当前为静态数据，先写基础测试) |

#### 1.2.3 core:apk 测试 (2-3h)
| 文件 | 测试内容 |
|------|----------|
| `VirtualClassLoaderTest.kt` | ClassLoader 创建、命名空间隔离、资源加载 |
| `ApkExtractorTest.kt` | APK 解析、DEX 提取、Native 库提取 |

#### 1.2.4 core:installer 测试 (2-3h)
| 文件 | 测试内容 |
|------|----------|
| `InstallerTest.kt` | 安装流程、签名验证、错误处理 |

**验证方法**:
```bash
./gradlew testDebugUnitTest --continue
# 检查各模块测试报告
```

**成功标准**: 所有模块测试通过，新增模块覆盖率 ≥ 60%

---

### 1.3 删除废弃代码
**优先级**: P1
**工作量**: 1h
**依赖**: 无

| 任务 | 文件 | 说明 |
|------|------|------|
| 删除 LoadedApkSwapper | `core/loader/.../LoadedApkSwapper.kt` | 已内联到 LoaderFactory，空对象 |
| 检查引用 | 全局搜索 `LoadedApkSwapper` | 确认无其他模块引用 |

**验证方法**:
```bash
./gradlew assembleDebug  # 编译通过
grep -r "LoadedApkSwapper" --include="*.kt"  # 无引用
```

**成功标准**: 文件删除后项目编译通过，无残留引用

---

## 阶段二：核心功能补全 (Week 3-4)

### 2.1 FileSystemHook.rewritePackagePath() 实现
**优先级**: P0 (功能完整性)
**工作量**: 4-6h
**依赖**: 无

**当前状态**: `PackageIdentityHook.kt:215-218` 中 `rewritePackagePath()` 直接返回原值，未做路径重写。

**实现方案**:
```kotlin
private fun rewritePackagePath(result: Any?, originalPkg: String): Any? {
    if (result !is String) return result
    // 将 stub 包名路径段替换为原始包名
    // 例: /data/data/com.multiapp.stub1/ → /data/data/com.original.app/
    val stubPkg = getCurrentStubPackage() ?: return result
    return result.replace(stubPkg, originalPkg)
}
```

**需要处理的路径类型**:
- `/data/data/{pkg}/`
- `/data/user/0/{pkg}/`
- `/data/user_de/0/{pkg}/`
- `/storage/emulated/0/Android/data/{pkg}/`

**验证方法**:
- 单元测试覆盖各种路径格式
- 集成测试验证实际文件访问路径正确

**成功标准**: 文件系统路径重写正确，应用数据目录访问正常

---

### 2.2 BinaryXmlEncoder Provider meta-data 编码修复
**优先级**: P1
**工作量**: 3-4h
**依赖**: 无

**当前状态**: `BinaryXmlEncoder.kt:271-272` 中 provider meta-data 编码被注释掉，原因是 MIUI 上安装失败。

**解决方案**:
1. **调研 MIUI 兼容性问题** (1h)
   - 确认是 meta-data 编码格式问题还是 MIUI 特有限制
   - 检查是否可以通过条件编码绕过

2. **实现条件编码** (2-3h)
   - 方案 A: 检测 MIUI 系统，跳过 meta-data 编码
   - 方案 B: 修复编码格式，确保 MIUI 兼容
   - 方案 C: 使用 ManifestRewriter 路径保留原始 meta-data (当前注释说明的方案)

**验证方法**:
- 在 MIUI 设备上测试安装
- 在原生 Android 设备上测试安装
- 验证 provider 功能正常

**成功标准**: 所有目标设备上安装成功，provider meta-data 正确保留

---

### 2.3 SettingsViewModel 功能实现
**优先级**: P2
**工作量**: 4-6h
**依赖**: 无

**当前状态**: `SettingsViewModel.kt` 只有静态数据 (appVersion, packageName, buildType)

**需要实现的功能**:

| 功能 | 说明 | 工作量 |
|------|------|--------|
| 主题切换 | 支持浅色/深色/跟随系统 | 1h |
| 语言设置 | 多语言支持 | 1h |
| 存储管理 | 清理缓存、查看数据大小 | 1h |
| 高级选项 | 调试模式、日志级别 | 1h |
| 关于页面 | 版本信息、开源许可 | 1h |

**实现步骤**:
1. 定义 `SettingsRepository` 接口
2. 使用 DataStore 存储设置
3. 实现各设置项的读写逻辑
4. 更新 `SettingsScreen.kt` UI

**验证方法**:
- 单元测试覆盖 ViewModel 逻辑
- 手动测试各设置项功能

**成功标准**: 所有设置项可正常切换并持久化

---

## 阶段三：代码质量提升 (Week 5-6)

### 3.1 LoaderFactory.kt 拆分
**优先级**: P1
**工作量**: 8-12h
**依赖**: 2.1 (rewritePackagePath 实现后)

**当前状态**: 3720 行，职责过多

**拆分方案**:

| 新模块 | 职责 | 预估行数 |
|--------|------|----------|
| `LoaderFactory.kt` | 入口，AppComponentFactory 实现 | ~200 |
| `ClassLoaderSwapper.kt` | ClassLoader 替换逻辑 | ~400 |
| `ResourceLoader.kt` | 资源加载、AssetPath 管理 | ~300 |
| `HookInitializer.kt` | Hook 引擎初始化 | ~200 |
| `IdentityApplier.kt` | Identity 配置应用 | ~300 |
| `NativeLibraryLoader.kt` | Native 库加载 | ~200 |
| `SecurityManager.kt` | 签名验证、安全检查 | ~200 |
| `Logger.kt` | 日志管理 | ~100 |

**拆分步骤**:
1. 识别 LoaderFactory 中的职责边界
2. 创建新类，逐步迁移方法
3. 保持 LoaderFactory 作为门面，委托给新类
4. 确保所有现有测试通过

**验证方法**:
```bash
./gradlew testDebugUnitTest  # 所有测试通过
./gradlew assembleDebug      # 编译通过
```

**成功标准**: LoaderFactory ≤ 300 行，各模块职责单一，测试通过

---

### 3.2 签名伪装升级 (TODO B)
**优先级**: P2
**工作量**: 6-8h
**依赖**: 无

**当前状态**: `LoaderFactory.kt:3411` 标注 `TODO(B): 签名伪装升级位`

**实现方案**:
1. **设计签名伪装接口** (1h)
   ```kotlin
   interface SignatureDisguise {
       fun disguise(originalSignature: ByteArray): ByteArray
       fun validateDisguisedSignature(disguised: ByteArray): Boolean
   }
   ```

2. **实现基础签名替换** (3-4h)
   - 支持用户提供自有签名文件
   - 运行时替换 APK 签名
   - 处理 V1/V2/V3 签名方案

3. **集成到 LoaderFactory** (2-3h)
   - 在初始化阶段应用签名伪装
   - 处理签名验证绕过

**验证方法**:
- 单元测试覆盖签名替换逻辑
- 集成测试验证应用功能正常

**成功标准**: 应用可通过签名验证，功能不受影响

---

### 3.3 ProGuard 规则审查
**优先级**: P2
**工作量**: 2-3h
**依赖**: 无

**审查内容**:

| 模块 | 检查项 |
|------|--------|
| core:hook | 反射调用的类/方法是否 keep |
| core:identity | Hook 点是否正确 keep |
| core:loader | AppComponentFactory 是否 keep |
| core:stub | Stub 相关类是否 keep |
| app | 最终合并规则是否完整 |

**验证方法**:
```bash
./gradlew assembleRelease  # Release 构建通过
# 运行 Release APK 验证功能
```

**成功标准**: Release 构建通过，运行时无 ClassNotFoundException

---

## 阶段四：集成测试与文档 (Week 7-8)

### 4.1 仪器化测试 (androidTest)
**优先级**: P1
**工作量**: 8-12h
**依赖**: 阶段二完成

**测试场景**:

| 场景 | 测试内容 | 工作量 |
|------|----------|--------|
| 应用多开流程 | 安装 → 创建实例 → 启动 → 验证隔离 | 3h |
| Identity 代理 | 包名伪装 → 签名伪装 → 资源隔离 | 2h |
| 数据隔离 | 不同实例数据目录独立 | 2h |
| 卸载清理 | 删除实例 → 验证数据清理 | 1h |
| 边界情况 | 重复安装、磁盘空间不足等 | 2h |

**实现步骤**:
1. 配置 androidTest 环境 (Hilt 测试、TestRunner)
2. 创建测试用 APK (使用 test-fixtures:minimal-app)
3. 编写测试用例
4. 集成到 CI

**验证方法**:
```bash
./gradlew connectedDebugAndroidTest  # 本地运行
# CI 中使用 emulator 运行
```

**成功标准**: 核心流程测试通过，覆盖率 ≥ 70%

---

### 4.2 文档更新
**优先级**: P2
**工作量**: 4-6h
**依赖**: 所有功能完成

**文档内容**:

| 文档 | 内容 |
|------|------|
| README.md | 项目介绍、架构说明、快速开始 |
| ARCHITECTURE.md | 详细架构设计、模块职责 |
| CONTRIBUTING.md | 贡献指南、代码规范 |
| API.md | 核心 API 文档 |

**验证方法**: 文档完整性检查

**成功标准**: 文档完整、准确、易读

---

## 依赖关系图

```
阶段一 (Week 1-2)
├── 1.1 CI 流水线增强 ─────────────────────┐
├── 1.2 补充单元测试 ─────────────────────┤
└── 1.3 删除废弃代码 ─────────────────────┤
                                           ↓
阶段二 (Week 3-4)                          │
├── 2.1 rewritePackagePath 实现 ──────────┤
├── 2.2 BinaryXmlEncoder 修复 ────────────┤
└── 2.3 SettingsViewModel 功能 ───────────┤
                                           ↓
阶段三 (Week 5-6)                          │
├── 3.1 LoaderFactory 拆分 ←──────────────┘
├── 3.2 签名伪装升级
└── 3.3 ProGuard 审查
                                           ↓
阶段四 (Week 7-8)
├── 4.1 仪器化测试
└── 4.2 文档更新
```

---

## 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| MIUI 兼容性问题 | 2.2 可能延期 | 准备降级方案 (跳过 meta-data) |
| LoaderFactory 拆分复杂 | 3.1 可能超时 | 分步拆分，保持向后兼容 |
| 签名方案多样性 | 3.2 实现困难 | 先支持 V1 签名，逐步扩展 |
| 测试环境搭建 | 4.1 可能受阻 | 使用 Robolectric 替代部分场景 |

---

## 成功指标

| 指标 | 当前 | 目标 |
|------|------|------|
| 单元测试覆盖率 | ~40% | ≥ 70% |
| 仪器化测试覆盖 | 0% | ≥ 50% |
| CI 测试步骤 | 无 | 完整流水线 |
| 代码行数 (LoaderFactory) | 3720 | ≤ 300 |
| 废弃代码 | 存在 | 清除 |
| 功能完整度 | 75% | ≥ 90% |

---

## 时间线总结

| 阶段 | 时间 | 主要交付物 |
|------|------|-----------|
| 阶段一 | Week 1-2 | CI 增强、测试基线、代码清理 |
| 阶段二 | Week 3-4 | 核心功能补全、Settings 实现 |
| 阶段三 | Week 5-6 | 代码重构、签名升级、ProGuard |
| 阶段四 | Week 7-8 | 集成测试、文档完善 |

**总计**: 8 周，从 Alpha 推进到 Beta

---

## 新增特性：LSPatch/LSPosed 模块兼容

### 需求背景

用户希望 MultiApp 能够兼容 LSPosed 模块，将 LSPatch 的功能集成进来。

### 能力差距分析

| 层次 | LSPatch | MultiApp | 差距 |
|------|---------|----------|------|
| ART Hook 引擎 | LSPlant | LSPlant 6.4 + ShadowHook | ✅ 已对齐 |
| Hidden API 绕过 | HiddenApiBypass | 同一库 (6.1) | ✅ 已对齐 |
| **Xposed API 实现** | XposedBridge + XC_MethodHook | **完全没有** | ❌ 核心缺失 |
| **模块加载机制** | ModuleLoader + xposed_init | **没有** | ❌ 核心缺失 |
| 应用虚拟化 | 无 | StubBuilder + LoaderFactory | ✅ MultiApp 独有 |

### 三阶段实现方案

**阶段一：最小可用（嵌入式模块）**
- 新增 `core/xposed/` 模块
- 实现 XposedBridge、XC_MethodHook、XC_LoadPackage、XposedHelpers
- 实现 ModuleLoader（读取 xposed_init，InMemoryDexClassLoader 加载）
- 扩展 StubBuilder 支持嵌入模块 APK
- 工作量：16h

**阶段二：完善 API 兼容**
- findAndHookMethod() 等便利方法
- invokeOriginalMethod() 桥接
- 模块 SharedPreferences 隔离
- 工作量：11h

**阶段三：Manager 集成（可选）**
- ILSPApplicationService 接口实现
- 远程模块加载
- 工作量：11h

### 架构设计

```
Xposed API Layer (XposedBridge / XC_MethodHook)
        ↓ 桥接
MultiApp HookEngine (hookMethodPassThrough → LSPlant)
        ↓
NativeHookBridge (LSPlant + ShadowHook + GOT Hook)
```

### 时间线

| 阶段 | 时间 | 交付物 |
|------|------|--------|
| Xposed 阶段一 | Week 9-10 | core/xposed 模块、嵌入式模块加载 |
| Xposed 阶段二 | Week 11-12 | API 兼容完善 |
| Xposed 阶段三 | Week 13-14 | Manager 集成（可选） |

### 优先级

建议先完成阶段一，验证核心架构后再扩展。
