# MultiApp 执行路线图 - 2026-07-29

## 📊 项目成熟度评估

```
┌─────────────────────────────────────────────────────────────────┐
│                    MultiApp 成熟度仪表盘                         │
├─────────────────────────────────────────────────────────────────┤
│  代码规模        ████████████████████░░░░░░░░  652 Kotlin 文件   │
│  架构对齐度      ████████████████████████████░  85%              │
│  能力完成度      ████████████░░░░░░░░░░░░░░░░░  60%              │
│  测试覆盖        ████████████████████░░░░░░░░░  2,181+ 用例      │
│  设备证据        ████░░░░░░░░░░░░░░░░░░░░░░░░░  15%              │
│  产品化/UI       ████████░░░░░░░░░░░░░░░░░░░░░  30%              │
├─────────────────────────────────────────────────────────────────┤
│  总体状态：alpha / BLOCK                                         │
│  距 VirtualApp/BlackBox 级别：6-8 个月（26-34 周）               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🎯 核心差距（必须解决才能解除 BLOCK）

### 差距 1：独立 `:engine` 进程未隔离（🔴 阻塞）

| 维度 | VirtualApp/BlackBox | MultiApp | 差距 |
|------|---------------------|----------|------|
| 进程模型 | 独立 `:p0`/`:server` 进程 | `:engine` 声明存在但共址 | 🔴 |
| DI 隔离 | 主进程/server 分离 | Hilt @Singleton 两套内存 | 🔴 |
| 文件锁 | 服务器端序列化访问 | 无跨进程锁 | 🔴 |
| Binder 死亡恢复 | 成熟的重连机制 | 本地闭环，缺设备证据 | 🟡 |

**立即行动**：
- [ ] Hilt DI 隔离方案设计（本周）
- [ ] 文件存储跨进程锁方案设计（本周）
- [ ] Binder 死亡恢复真机验证（阶段 1）

### 差距 2：P0 能力全部 PARTIAL，无 PASS（🔴 阻塞）

| 能力 | 完成度 | 主要缺口 |
|------|--------|----------|
| 包与实例 | 65% | 同包升级原子切换/回滚 |
| Activity | 55% | result/onNewIntent/Recents 真机恢复 |
| Provider | 50% | URI grant/自定义进程/observer |
| Service | 45% | bind/unbind/Binder death/foreground type |
| Broadcast | 40% | ordered/sticky/result/跨进程 |
| Permission | 45% | runtime dialog/flags/auto-reset |
| AppOps | 35% | note/start/finish/attribution |
| Storage/Media | 50% | external policy/MediaProvider 隔离 |
| Native | 50% | syscall/linker namespace/RegisterNatives |

**立即行动**：
- [ ] PMS 剩余 10 项 P0 字段补齐（本周）
- [ ] AMS 任务栈/result/onNewIntent 闭环（阶段 1）

### 差距 3：真机设备证据矩阵缺失（🔴 阻塞）

| 维度 | 当前状态 | 目标状态 |
|------|----------|----------|
| API 覆盖 | 仅模拟器 x86_64 | API 28/30/33/35/36 真机 |
| 厂商 ROM | 无 | HyperOS/MIUI/EMUI/ColorOS |
| 架构 | 仅 x86_64 | arm32/arm64 真机 |
| 关键应用 | 诊断代码存在 | QQ/微信/QQ阅读 profile 样本 |

**前置**：阶段 1-2 能力闭环后执行

---

## 🚀 4 阶段执行计划

```
阶段 1: P0 闭环 (8-10 周)
├── 独立 :engine 进程隔离
├── Hilt DI 跨进程隔离
├── 文件存储竞态修复
├── PMS 权威语义闭环
├── AMS 权威语义闭环
└── 移除迁移债务
    │
    ▼
阶段 2: 能力闭环 (8-10 周)
├── Provider 数据面
├── Service 数据面
├── Broadcast 数据面
├── Native/Storage
└── Permission/AppOps
    │
    ▼
阶段 3: 设备证据矩阵 (6-8 周)
├── API 真机矩阵
├── 厂商 ROM 验证
├── 架构验证 (arm32/arm64)
├── 关键应用兼容 profile
└── 证据归档
    │
    ▼
阶段 4: 开源准备 (4-6 周)
├── License 与合规
├── UI 完善
├── 文档
├── 模块清理
└── 首个 release
```

---

## 📅 本周立即行动项

### 技术方案设计（并行推进）

| 任务 | 负责 | 交付物 | 状态 |
|------|------|--------|------|
| Hilt DI 隔离方案 | 架构师 | 技术方案 + POC | 🔴 待启动 |
| 文件锁方案 | 架构师 | 锁策略文档 | 🔴 待启动 |
| PMS 字段补齐 | 工程师 | 代码 + 测试 | 🟡 部分完成 |
| feature facade 扩展 | 工程师 | listInstances() | 🟡 部分完成 |

### 可复用的已有工作

| 已完成工作 | 状态 | 可复用性 |
|------------|------|----------|
| PMS P0 字段补齐（debuggable/sharedUserId/sharedUserLabel） | ✅ | 直接复用 |
| Engine listInstances() IPC | ✅ | 直接复用 |
| feature:launcher 依赖倒置 | ✅ | 直接复用 |
| feature:appmanager 依赖倒置 | ✅ | 直接复用 |
| Engine owner 进程禁令严格化 | ✅ | 直接复用 |
| 字段保真测试强化 | ✅ | 直接复用 |

---

## 📈 验收标准

### 阶段 1 完成标准

- [ ] `:engine` 运行于 `${applicationId}:engine` 独立进程
- [ ] server 杀死/重连真机证据
- [ ] PMS 完整 PackageInfo/ApplicationInfo/ProviderInfo/ResolveInfo
- [ ] AMS 任务栈/result/onNewIntent/Recents 真机恢复
- [ ] feature 层零直接 loader/hook imports
- [ ] 全部测试通过 + `:app:assembleDebug` 成功

### 阶段 2 完成标准

- [ ] Provider URI grant/observer/自定义进程
- [ ] Service bind/unbind/Binder death/foreground type
- [ ] Broadcast ordered/sticky/result/跨进程
- [ ] Native syscall 族/linker namespace/RegisterNatives
- [ ] Permission runtime dialog/flags/auto-reset
- [ ] AppOps note/start/finish/attribution

### 阶段 3 完成标准

- [ ] API 28/30/33/35/36 真机全链路证据
- [ ] HyperOS/MIUI/EMUI/ColorOS 验证
- [ ] arm32/arm64 真机 native hook 结论
- [ ] QQ/微信/QQ阅读 profile 样本证据
- [ ] 证据归档（commit + APK SHA-256 + 设备 + logcat）

### 阶段 4 完成标准

- [ ] LICENSE + NOTICE + CONTRIBUTING
- [ ] feature 层测试覆盖 ≥ 60%
- [ ] 文档完整（README/架构/API/开发指南）
- [ ] Legacy Stub 隔离/移除
- [ ] 首个 release tag + 发布说明

---

## ⚠️ 关键风险

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 独立进程迁移暴露 Binder 序列化缺陷 | 阶段 1 延期 | 先做序列化契约测试，保留 fallback |
| AMS 真机恢复受厂商 ROM 影响 | Recents 黑屏 | HyperOS/MIUI 单独验证 + watchdog |
| Native syscall 覆盖不全 | 加固应用崩溃 | profile allow-list + 持续设备证据 |
| 真机设备获取受限 | 阶段 3 阻塞 | 优先 API 28/33/36 + HyperOS |
| 应用兼容性工作偏离主线 | 资源分散 | 兼容 profile 是样本，非 baseline |

---

## 📚 参考文档

- [项目架构文档](../ARCHITECTURE.md)
- [README](../README.md)
- [开源引擎对照](2026-07-09-open-source-engine-comparison.md)
- [阶段 1 执行报告](../progress/2026-07-28-phase1-multi-agent-execution.md)
- [成熟度审查报告](2026-07-28-open-source-maturity-audit.md)
- [本次完整审查](2026-07-29-project-maturity-review.md)

---

*生成时间：2026-07-29 15:10 CST*
*维护者：齐活林（Qi）· 交付总监*
