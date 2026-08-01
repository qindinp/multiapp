# MultiApp 成熟产品审核 — 具体执行计划

- 版本：v1.1
- 日期：2026-08-01
- 依据方案：`C:\Users\20237\.workbuddy\plans\quantum-nebula-lovelace.md`
- 控制项注册表：`config/audit/control-register.json`（P0×20 / P1×8 / P2×7，当前 2 PASS / 2 FAIL / 31 BLOCKED）
- 当前裁决：**NO-GO / BLOCK**

> **决策状态（2026-08-01 项目负责人确认）**：
> - **D1 = B 已确认**：hosted/legacy 独立 flavor，hosted 为唯一发布变体 → W1-3 解锁
> - **D2 = B→A 已确认**：短期文档化降级 at-most-once，durable journal 列 P1 迭代 → P0-TXN-01 验收口径确定
> - D3–D7 待确认；W1-3 中 ABI 配置暂维持现状，不擅自排除 arm32

---

## W0：决策项（阻塞后续执行，需项目负责人拍板）

| # | 决策 | 选项 | 建议 | 影响控制项 |
|---|------|------|------|-----------|
| D1 | Legacy 处置 | A. 完全删除 `:core:stub`/loader.dex/Xposed；B. 独立 `legacy` flavor，发布 `hosted` flavor | **B**：hosted 为唯一发布变体，legacy 保留实验但物理隔离 | P0-ARC-01, P1-ARC-01/02 |
| D2 | Crash atomicity | A. 实现 durable journal/WAL；B. 短期文档化降级 at-most-once + 长期 journal | **B→A**：先降级声明解除歧义，journal 列为 P1 迭代 | P0-TXN-01, P1-CRASH-01 |
| D3 | armeabi-v7a | A. 支持（升级为 P0 全矩阵）；B. 从制品排除 | **B**：abiFilters 只留 arm64-v8a，聚焦资源 | 设备矩阵规模 |
| D4 | 设备矩阵 | 采购/借用清单见 W2 | 最低 6 台物理锚点 + CI 模拟器 | 全部真机控制项 |
| D5 | 白名单 APK 来源 | 官方渠道（官网/应用宝/Play）合法获取 12 套（6 应用 × N/N-1） | 法务确认留存许可 | P0-COMPAT-01 |
| D6 | 国内商店名单 | 华为/小米/OPPO/vivo/荣耀/应用宝 | W5 前冻结 | P2-STORE-01 |
| D7 | 测试账号 | 6 应用各 2 个（A/B 实例独立登录） | 企业实名注册，密钥入保险库 | P0-COMPAT-01 |

---

## W1：本地可执行（无外部资源依赖，预估 5–7 个工作日）

### W1-1 审计基线冻结（→ P0-ENG-01）

- **动作**：将 165 项 dirty 变更整理为语义化 commit 组：
  1. S0/S1 安全修复（StubBuilder fail-closed、路径穿越防护、SignatureBypass ThreadLocal）
  2. S2 修复（native 原子状态、scoped rule 索引、ProGuard、Detekt）
  3. QQ Reader compat 子包隔离迁移
  4. 24 槽进程合同统一（EngineProcessSlotContract）
  5. 审核资产（whitelist/control-register/evidence-schema/execution-status）
  6. 测试设施与 androidTest 用例
- **命令**：分组 `git add -p` 后逐组 `git commit`；每组附变更摘要
- **验收**：`git status --porcelain` 为空；`git fsck --full` 通过；每组 commit 可独立编译
- **注意**：禁止 `git clean -fdx`（会销毁 `.workbuddy/audit-backups/` 取证现场，该目录已在 .gitignore 排除）

### W1-2 修复 IdentityHookTest 反射测试（→ P1-TEST-01）

- **根因**：`rewritePath` 声明为 `internal`，JVM 字节码名被混淆为 `rewritePath$multiapp_core_identity`，测试按简单名反射查找返回 null → 9 个 assertEquals 全部失败
- **动作**：删除 `invokeRewritePath` 反射辅助函数，9 个测试改为直接调用 `FileSystemHook.rewritePath(...)`（同模块 internal 可见）
- **文件**：`core/identity/src/test/java/com/multiapp/core/identity/IdentityHookTest.kt:583-595`
- **验证**：`gradlew.bat :core:identity:testDebugUnitTest` 全绿
- **参照**：`FileSystemHookPathTest`（23/23 通过）已是正确写法

### W1-3 hosted/legacy flavor 隔离（→ P0-ARC-01, P1-ARC-01, P1-ARC-02）

依赖 D1=B。

- **动作**：
  1. `app/build.gradle.kts` 增加 `flavorDimensions += "runtime"`，`hosted` 与 `legacy` 两个 flavor
  2. `:core:stub`、`:core:xposed`、loader.dex 依赖改为 `legacyImplementation`
  3. `copyLoaderDex` 任务仅在 legacy 变体注册；输出改到 `build/generated/assets/<variant>` 并声明 inputs/outputs（不再写源码目录）
  4. ProGuard keep 规则按变体拆分
  5. 新增 APK 内容门禁脚本：`tools/audit/check-release-content.py`，hosted Release 出现 `StubBuilder`/`loader.dex`/`liblsplant.so`/`de.robv.android.xposed` 即 FAIL
- **验收**：`assembleHostedRelease` 产物解包无 Legacy 类与资产；门禁脚本进 CI

### W1-4 首个 Release 基线（→ 解锁阶段 7 前置）

- **动作**：串行执行（禁止并行 Gradle 进程，复用项目约定 `--no-daemon --no-build-cache --max-workers=1`）：
  ```bash
  ./gradlew.bat clean
  ./gradlew.bat :app:assembleHostedRelease
  ```
- **产物**：APK + R8 mapping + native symbols + SHA-256 清单 + 依赖树（`gradlew :app:dependencies --configuration hostedReleaseRuntimeClasspath`）
- **验收**：构建零错误；mapping/symbols 归档；hash 记录进 `build/release-baseline.json`
- **备注**：当前 `app/build/outputs/apk/release/` 为空，Release 从未成功构建过；签名可先用 debug keystore 做基线验证，正式发布签名后置到 W5

### W1-5 关键代码修复

| 修复项 | 文件 | 内容 | 解锁 |
|--------|------|------|------|
| linker namespace fail-closed | `core/apk/.../VirtualClassLoader.kt` | namespace 创建失败时抛出异常中止 guest 启动，不再 fallback 默认 namespace | P0-CMP-07 |
| WebView 数据目录隔离 | guest bootstrap（`core/loader/.../HostedRuntimeBootstrap.kt`） | 实例首启时 `WebView.setDataDirectorySuffix(instanceId)`，幂等且线程安全 | P0-CMP-08 |
| Bootstrap 槽位解析 | `app/.../EngineProcessBootstrapTransport.kt:71-79` | `substringAfterLast(":v").toIntOrNull` 改为 `EngineProcessSlotContract.isCanonicalProcessSlot(host, slot)` 严格匹配 | P1-SEC-01 |

每项附单元测试；串行构建验证。

### W1-6 合规文件（→ P0-REL-03）

- 新增 `LICENSE`（确认项目许可证，建议 Apache-2.0 与现有代码头一致后定稿）
- 新增 `NOTICE`（列第三方组件：LSPlant、Xposed API、Timber 等）
- 新增 `SECURITY.md`（漏洞披露渠道）

### W1-7 P1 安全负向测试矩阵（→ P1-SEC-02/03/04）

- **动作**：在 `core/engine/src/test/` 新增参数化测试：
  - foreign UID × 73 endpoint 拒绝矩阵（mock Binder 调用方 UID）
  - PID 复用 + 错误 processStartTicks 拒绝
  - route token 错 target instance / 错 startTicks / 错 processEpoch
  - Bootstrap alias 负例：`host:v03`、`foreign.pkg:v3`、`prefix:v1:suffix:v3`
- **验收**：全部 fail-closed 断言通过，无 mutation、无跨实例泄漏

### W1-8 全量质量门禁（→ P1-TEST-01 关闭确认）

- **命令**（严格串行）：
  ```bash
  ./gradlew.bat lintHostedDebug
  ./gradlew.bat testHostedDebugUnitTest jacocoTestReport
  ./gradlew.bat detekt
  ```
- **验收**：0 failure；skipped=0（StubBuilderManifestTest 需迁移 Robolectric 或标记 instrumentation-only 并豁免登记）；JaCoCo 报告归档

**W1 出口判据**：P0-ENG-01 PASS、P0-ARC-01 PASS、P1-TEST-01 PASS、W1-5 三项修复合入、Release 基线可复现。预计控制项状态：P0 4–5 PASS。

---

## W2：资源准备（与 W1 并行，负责人工/采购）

| 资源 | 明细 | 阻塞项 |
|------|------|--------|
| 物理设备 ×6 | Pixel (AOSP, API 35/36)、Samsung One UI、Xiaomi HyperOS、OPPO ColorOS、vivo OriginOS、Honor MagicOS；其中至少 1 台 API 37 + 16KB page size | W3 全部 |
| 白名单 APK ×12 | 微信/支付宝/淘宝/抖音/QQ阅读/Edge × (N + N-1)，记录 versionCode、base/split SHA-256、签名证书 SHA-256 | P0-COMPAT-01 |
| 测试账号 ×12 | 每应用 2 个，完成实名/二验绑定 | 同上 |
| CI 模拟器补齐 | API 29/31/32/34/36 加入 `android.yml` smoke 矩阵 | P2-TEST-02 |
| 签名密钥 | 正式发布 keystore（W5 前到位，入保险库） | P0-REL-01 |

---

## W3：真机测试（W1 出口 + 设备到位后启动，预估 2–3 周）

按方案阶段 4–5 执行，顺序固定：

1. **24 槽动态 parity**（→ P0-ARC-03, P1-CMP-01）：`v0..v23` 逐槽拉起 Activity/Service/Provider/Bootstrap，断言 process name/authority/PID/startTicks/Binder death/kill-restart；v03/foreign/多分隔符负例
2. **组件 data plane**（→ P0-CMP-01..05, 09, 10）：guest-observed fixtures，按 Package/Activity → Service death → Provider → Broadcast → Permission/AppOps → 通知 → host death recovery 依赖顺序
3. **Storage/Native/WebView**（→ P0-CMP-06/07/08）：文件/媒体隔离、linker/JNI、Cookie/localStorage/renderer death
4. **双实例隔离**（→ P0-ISO-01）：minimal fixture A/B 先行，随后微信/QQ阅读双账号实测
5. **六应用认证 WL-C01..C16**（→ P0-COMPAT-01）：按方案阶段 5 的 9 步流程 × 6 应用 × N/N-1；禁止真实资金操作
6. **事务 fault-injection**（→ P0-TXN-01 部分）：install/create/refresh/clear/delete 注入 kill/IO 异常/磁盘满/并发，验证恢复语义（配合 D2 的降级声明）

每条执行产出符合 `config/evidence/evidence-schema.json` 的 Evidence JSON + 附件 hash。

---

## W4：性能与长稳（W3 核心链路 PASS 后，预估 2 周）

- Macrobenchmark：冷启 30 次/热启 50 次，对照原生（→ P1-PERF-01；p50 ≤+20%，p95 ≤+30%）
- 24h 单 ROM 长稳 × 主要 ROM（→ P1-STAB-01；PSS 增长 ≤10% 或 100MB）
- 72h 组合 soak + 六应用并发（→ P2-TEST-01, P2-PERF-01）
- 覆盖率核对：changed-code ≥90%/80%，core:engine/loader/installer ≥85%/75%

---

## W5：合规、RC 与灰度（W4 PASS 后，预估 1–2 周）

1. 正式签名 + SBOM + 依赖漏洞扫描（→ P0-REL-01/02）
2. 敏感权限逐项论证或移除（→ P0-REL-04）
3. 渠道共同门禁 + 逐商店附录（D6 冻结后）（→ P2-STORE-01）
4. 独立 RC 复核 Evidence 与制品 hash（→ P2-RC-01）
5. 灰度演练 1%→5%→25%→50%→100% + 一次真实回滚（→ P0-OPS-01, P2-OPS-01）

**GO 判据**：P0=100% PASS、P1≥90%、P2≥75%、S0/S1=0、六应用全 PASS、制品与 Evidence hash 一致、渠道门禁全过 → M4。

---

## 风险与依赖汇总

| 风险 | 等级 | 缓解 |
|------|------|------|
| W1-3 flavor 隔离涉及构建脚本大改，可能引入新构建错误 | 中 | 先分支验证，APK 内容门禁脚本先行 |
| WebView.setDataDirectorySuffix 须在 WebView 加载前调用，时序敏感 | 高 | 在 Application onCreate 最早期设置，附 instrumentation 验证 |
| durable journal（D2 长期项）改动 engine 核心状态机 | 高 | 单独迭代评审，不阻塞 W1–W4 |
| 设备/账号采购周期不可控 | 中 | W2 立即启动，与 W1 完全并行 |
| 六应用对虚拟化环境有反检测（尤其微信/支付宝） | 高 | W3 先跑 QQ阅读/Edge 积累方法，再攻重型应用；失败如实记 FAIL |

## 立即下一步（本周）

1. 项目负责人确认 D1–D7 决策（**阻塞 W1-3/W3/W5**）
2. 执行 W1-1 审计基线冻结
3. 执行 W1-2 测试修复（最快可闭环项，约 0.5 天）
4. 启动 W2 采购/获取流程
