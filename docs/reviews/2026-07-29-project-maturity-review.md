# MultiApp 项目成熟度审查与执行计划 - 2026-07-29

## TL;DR

**当前状态**：60% 完成度，`alpha / BLOCK`，距离 VirtualApp/BlackBox 级别约 **6-8 个月**

**核心差距**：独立 `:engine` 进程未隔离 + P0 能力全部 PARTIAL（无 PASS）+ 真机证据缺失

**立即行动**：启动阶段 1（P0 闭环），本周内可并行推进 3 个方向

---

## 一、项目现状快照

### 1.1 代码规模

| 维度 | 数值 | 评估 |
|------|------|------|
| Kotlin 源文件 | 652 个 | 工程级规模 |
| core 模块 | 571 个 | 主体实现 |
| feature 模块 | 10 个 | UI 薄层 |
| app 模块 | 69 个 | 应用入口 |
| 测试用例 | 2,181+ | 覆盖良好 |
| C++ Native 代码 | 12,845 行 | hook 核心 |

### 1.2 能力矩阵（17 项关键能力）

| 领域 | 状态 | 完成度 | 阻塞点 |
|------|------|--------|--------|
| 包与实例 | PARTIAL | 65% | 同包升级原子切换/回滚 |
| Activity | PARTIAL | 55% | result/onNewIntent/Recents 真机恢复 |
| Provider | PARTIAL | 50% | URI grant/自定义进程/observer |
| Service | PARTIAL | 45% | bind/unbind/Binder death/foreground type |
| Broadcast | PARTIAL | 40% | ordered/sticky/result/跨进程 |
| Permission | PARTIAL | 45% | runtime dialog/flags/auto-reset |
| AppOps | PARTIAL | 35% | note/start/finish/attribution |
| Storage/Media | PARTIAL | 50% | external policy/MediaProvider 隔离 |
| Native | PARTIAL | 50% | syscall/linker namespace/RegisterNatives |
| Split | PARTIAL | 40% | dynamic feature/multidex/native 设备闭环 |
| Isolated Split | UNSUPPORTED | 0% | loader 明确拒绝 |
| WebView | PARTIAL | 35% | 数据目录隔离/Chromium/JNI |
| Notification | PARTIAL | 25% | channel/PendingIntent/持久化 |
| JobScheduler/Alarm | UNSUPPORTED | 0% | capability catalog 默认 not-implemented |
| 独立 :engine 进程 | - | 30% | Binder 闭环 + 设备证据 |
| 设备证据矩阵 | - | 15% | 仅模拟器，缺真机 API 28-36 + 厂商 ROM |
| UI/产品化 | - | 30% | feature 层薄，settings 静态数据 |

**结论**：17 项关键能力 **0 项 PASS**，全部 PARTIAL 或 UNSUPPORTED。

---

## 二、与开源成熟方案差距

### 2.1 对标 VirtualApp/BlackBox

| 维度 | VirtualApp/BlackBox | MultiApp | 差距 |
|------|---------------------|----------|------|
| 架构对齐度 | ✅ | ✅ (85%) | MultiApp 更严格（fail-closed） |
| 独立 server 进程 | ✅ `:p0`/`:server` | ❌ 本地共址 | **阻塞项** |
| PMS 完整语义 | ✅ | 🟡 部分 | 缺完整 PackageInfo 等 |
| AMS 完整语义 | ✅ | 🟡 部分 | 缺真机 Recents 恢复证据 |
| Provider/Service/Broadcast | ✅ | 🟡 部分 | 缺数据面完整性 |
| Native/Storage | ✅ | 🟡 部分 | 缺真机结论 |
| 设备证据矩阵 | ✅ API 28-36 + 厂商 | ❌ 仅模拟器 | **阻塞项** |
| 应用兼容性 | QQ/微信/主流 App | 诊断代码存在 | 不可声称兼容 |

### 2.2 MultiApp 的优势

1. **架构更严格**：generation-scoped capability、fail-closed 恢复、单写入者权威
2. **工程纪律更好**：Hilt/KSP/Room/JaCoCo/Detekt、CI 双门禁
3. **代码质量高**：TODO 密度极低，无 NotImplementedError
4. **测试覆盖**：2,181+ 测试用例，skipped=0 要求

---

## 三、4 阶段执行计划

### 阶段 1：P0 闭环（8-10 周）— 解除 BLOCK 基础

**目标**：建立独立 `:engine` 进程 + PMS/AMS 权威语义

| 任务 | 交付物 | 验收标准 | 优先级 |
|------|--------|----------|--------|
| 独立 `:engine` 进程隔离 | `:engine` 运行于独立进程，Binder 发布前完成 recovery | server 杀死/重连真机证据 | P0 |
| Hilt DI 跨进程隔离 | `@Singleton` 按进程分离，避免两套内存状态 | 编译通过 + 单测 | P0 |
| 文件存储竞态修复 | JsonInstanceRecordStore 等跨进程文件锁 | 并发测试通过 | P0 |
| PMS 权威语义闭环 | 完整 PackageInfo/ApplicationInfo/ProviderInfo/ResolveInfo | engine 全权负责 | P0 |
| AMS 权威语义闭环 | 任务栈/result/onNewIntent/Recents 真机恢复 | API 28-36 无黑屏 | P0 |
| 移除迁移债务 | app/container 直接 loader/hook imports 全部走 engine facade | 源边界测试通过 | P1 |

**立即可并行推进**（本周）：
1. **A：Hilt DI 隔离方案设计**
2. **B：PMS P0 字段补齐（剩余 10 项细节兼容性字段）**
3. **C：feature 层依赖倒置（listInstances() 等 facade 扩展）**

### 阶段 2：能力闭环（8-10 周）— 虚拟组件完整语义

**目标**：Provider/Service/Broadcast/Native/Permission/AppOps 达到可验证的完整语义

| 任务 | 交付物 | 验收标准 |
|------|--------|----------|
| Provider 数据面 | URI grant、observer/notify、自定义进程 | engine endpoint 完整 |
| Service 数据面 | bind/unbind、Binder death/rebind、foreground type | AOSP-shaped ConnectionRecord |
| Broadcast 数据面 | ordered、sticky、result/abort、跨进程 | manifest 路由 + 完整语义 |
| Native/Storage | syscall 族、linker namespace、RegisterNatives | split native 设备闭环 |
| Permission/AppOps | runtime dialog、flags、one-time、auto-reset | 实例级隔离验证 |

**前置**：阶段 1 完成

### 阶段 3：设备证据矩阵（6-8 周）— 真机可发布验证

**目标**：产出覆盖 API + 厂商 ROM + 架构的真机证据

| 任务 | 交付物 | 验收标准 |
|------|--------|----------|
| API 真机矩阵 | API 28/30/33/35/36 全链路 | 冷启动/首帧/交互/恢复证据 |
| 厂商 ROM 验证 | HyperOS/MIUI/EMUI/ColorOS | 任务清理/后台限制下恢复 |
| 架构验证 | arm32/arm64 真机 | native hook/linker 真机结论 |
| 关键应用兼容 | QQ/微信/QQ阅读 profile 样本 | commit + APK SHA-256 + 设备 + logcat 绑定 |
| 证据归档 | 工具链/commit/设备/logcat/APK | 可追溯、可复现 |

**前置**：阶段 1-2 完成

### 阶段 4：开源准备（4-6 周）— 可分发交付

**目标**：完成 License、UI、文档，发布首个可分发 release

| 任务 | 交付物 | 验收标准 |
|------|--------|----------|
| License 与合规 | LICENSE + NOTICE + CONTRIBUTING | 明确再分发许可 |
| UI 完善 | launcher/appmanager/settings 补全 | 测试覆盖 ≥ 60% |
| 文档 | README/架构/API/开发指南 | 完整、准确、可读 |
| 模块清理 | Legacy Stub 隔离/移除 | baseline 不依赖 LSPlant/Xposed |
| 首个 release | release tag + 发布说明 | P0 能力 PASS + 证据齐全 |

---

## 四、本周立即行动项

### 4.1 技术方案设计（本周内完成）

| 项目 | 负责 | 交付物 |
|------|------|--------|
| Hilt DI 隔离方案 | 架构师 | 技术方案文档 + POC |
| 文件存储跨进程锁方案 | 架构师 | 锁策略文档 |
| PMS 剩余 10 项字段补齐 | 工程师 | 代码变更 + 测试 |
| feature 层 facade 扩展 | 工程师 | listInstances() + 测试 |

### 4.2 代码变更预估

```
阶段 1 预估变更：
├── core:engine (30-40 文件)
├── core:loader (10-15 文件)
├── core:instance (5-10 文件)
├── feature/* (5-8 文件)
├── app (3-5 文件)
└── 新增测试 (20-30 文件)
```

---

## 五、风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 独立进程迁移暴露 Binder 序列化缺陷 | 阶段 1 延期 | 先做序列化契约测试，保留 fallback |
| AMS 真机恢复受厂商 ROM 影响 | Recents 黑屏 | HyperOS/MIUI 单独验证 + watchdog |
| Native syscall 覆盖不全 | 加固应用崩溃 | profile allow-list + 持续设备证据 |
| 真机设备获取受限 | 阶段 3 阻塞 | 优先 API 28/33/36 + HyperOS |
| 应用兼容性工作偏离主线 | 资源分散 | 兼容 profile 是样本，非 baseline |

---

## 六、结论

MultiApp **不是占位骨架**，而是架构对齐度高、工程纪律良好的真实实现。当前阻塞的根本原因：

1. **独立 `:engine` 进程隔离未完成**（结构前提）
2. **P0 能力全部 PARTIAL，无 PASS**（语义闭环）
3. **真机设备证据矩阵缺失**（可发布验证）

按 4 阶段计划推进，预计 **6-8 个月（26-34 周）** 可从 `alpha / BLOCK` 推进至首个可分发 release。

**核心纪律**（沿用 README）：
- JVM test 不证明 Android framework 真实兼容性
- skipped instrumentation test 不计为能力通过
- 应用兼容结论必须绑定 commit + APK SHA-256 + 设备 fingerprint + logcat + 首帧/交互证据
- 在 P0 能力全部 PASS 前，项目状态保持 `alpha / BLOCK`

---

*审查时间：2026-07-29 15:10 CST*
*审查依据：README.md, ARCHITECTURE.md, docs/reviews/*, docs/progress/*, 源码探索, 测试验证*
