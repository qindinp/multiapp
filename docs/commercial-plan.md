# 加固 App 兼容层商业化方案

## 项目定位

**不是"破解"工具，而是"兼容层"** — 让加固 App 能在虚拟/多开环境中正常运行。

目标用户：企业多开、测试环境、自动化测试、应用隔离。

---

## 一、技术架构

### 1.1 整体架构

```
┌─────────────────────────────────────────┐
│ LSPatch UI / 命令行                      │
├─────────────────────────────────────────┤
│ Xposed 模块注入层                        │
├─────────────────────────────────────────┤
│ 壳兼容层 (新增核心层)                     │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│ │ 360加固   │ │ 腾讯加固  │ │ 梆梆加固  │ │
│ │ 适配器    │ │ 适配器    │ │ 适配器    │ │
│ └──────────┘ └──────────┘ └──────────┘ │
├─────────────────────────────────────────┤
│ 运行时引擎 (MultiApp native)             │
│ • GOT hook (open/fopen/kill/tgkill)     │
│ • /proc/self 伪装 (cmdline/maps/status) │
│ • RegisterNatives 拦截                   │
│ • self-kill NOP patch                    │
│ • FindClass hook (guest ClassLoader)     │
│ • native 方法补注册                       │
├─────────────────────────────────────────┤
│ ART 层 (LSPlant + ShadowHook)            │
│ • LSPlant: ART method hooking            │
│ • ShadowHook: inline hooking backend     │
│ • GetMethodShorty fallback               │
└─────────────────────────────────────────┘
```

### 1.2 核心设计原则

**不完全逆向壳，而是绕过壳的 RegisterNatives 门控**

壳有几十个条件分支（SDK_INT、品牌、payload、qiniu、materialize 等），全部通过才能注册 native 方法。与其逐个满足，不如：
1. 让壳尽量通过环境检测（伪装 + hook）
2. 壳注册失败后，直接补注册缺失的 native 方法

### 1.3 壳适配器接口

```kotlin
interface ShellAdapter {
    fun detect(apk: ApkInfo): Boolean
    fun prepare(env: RuntimeEnv)
    fun load(context: LoadContext): LoadResult
    fun postLoad(context: LoadContext, result: LoadResult)
    fun registerMissingMethods(context: LoadContext)
}
```

---

## 二、技术实现

### 2.1 环境伪装层（核心竞争力）

| 伪装项 | 实现方式 | 状态 |
|--------|----------|------|
| `/proc/self/cmdline` | `spoofProcSelf(originalPackageName)` | ✅ 已实现 |
| `/proc/self/maps` | GOT hook on libc open/openat/fopen | ✅ 已实现 |
| `/proc/self/status` | TracerPid=0 伪装 | ✅ 已实现 |
| `/proc/self/exe` | 返回 `/system/bin/app_process64` | ✅ 已实现 |
| APK 完整性 | 重定向到原始 APK | ✅ 已实现 |
| 签名验证 | PM proxy 伪装签名 | ✅ 已实现 |

### 2.2 壳绕过层

| 技术点 | 实现方式 | 状态 |
|--------|----------|------|
| self-kill 拦截 | NOP patch + GOT hook on kill/tgkill | ✅ 已实现 |
| FindClass hook | JNI 函数表替换 | ✅ 已实现 |
| RegisterNatives 拦截 | JNI 函数表 hook | ✅ 已实现 |
| native 方法补注册 | `registerBusinessStubs` / `registerAllMissingNativeMethods` | ✅ 已实现 |
| LSPlant 初始化 | GetMethodShorty fallback + ELF resolver | ✅ 已实现 |
| pass-through hook | `hookMethodPassThrough` 异常传播 | ✅ 已实现 |

### 2.3 360 加固适配器

```kotlin
class Jiagu360Adapter : ShellAdapter {
    override fun prepare(env: RuntimeEnv) {
        // 关键：壳读 /proc/self/cmdline 检测进程名
        env.spoofProcSelf(originalPackageName)
        // GOT hook libc，过滤 maps 读取
        env.installGotHooks("libc.so")
        // FindClass hook，让壳的 JNI_OnLoad 能找到 guest 类
        env.installFindClassHook(guestClassLoader)
        // 完整性校验重定向
        env.setIntegrityRedirect(modifiedApk, originalApk)
    }

    override fun postLoad(context: LoadContext, result: LoadResult) {
        // 壳注册完成后，检查 YWLogin 是否已注册
        if (!result.ywLoginBound) {
            // 补注册：不依赖壳的 interface11
            context.bridge.registerBusinessStubs(guestCl)
            context.bridge.registerAllMissingNativeMethods(guestCl)
        }
    }
}
```

### 2.4 关键代码文件

| 文件 | 作用 |
|------|------|
| `native-hook.cpp` | 所有 native hooks、GOT hook、RegisterNatives 拦截 |
| `JiaguRuntime.kt` | 壳加载逻辑、环境伪装 |
| `NativeHookBridge.kt` | Java 到 native 的桥 |
| `LoaderFactory.kt` | ClassLoader 替换、壳加载调度 |
| `HookEngine.kt` | LSPlant 初始化、method hook |
| `SimpleHooker.kt` | hook 回调异常处理 |

---

## 三、竞品分析

### 3.1 现有方案对比

| 方案 | 原理 | 加固 App 支持 | 需要 Root |
|------|------|--------------|-----------|
| **VirtualXposed** | 运行时 ActivityThread hook | ❌ 有限 | ❌ |
| **LSPatch** | APK 重打包 + LSPlant 注入 | ⚠️ 部分 | ❌ |
| **NEXTVM** | Binder 代理 + ActivityThread mH + Native GOT | ✅ 好 | ❌ |
| **MultiApp** | AppComponentFactory + LSPlant + GOT hook + 9维身份伪装 | ✅ 最好 | ❌ |
| **TaiChi** | Magisk/Zygisk 模块 | ✅ 好 | ✅ |
| **Parallel Space** | VirtualApp 方案 | ⚠️ 部分 | ❌ |

### 3.2 我们的差异化优势

1. **壳兼容层**：专门处理加固 App 的环境检测，其他方案没有
2. **native 方法补注册**：不依赖壳的内部逻辑，直接补齐缺失方法
3. **9 维身份伪装**：包名、签名、路径、进程名、设备信息等
4. **运行时 dump 能力**：可以分析壳的内部逻辑
5. **模块化适配器**：支持多种壳类型，可扩展

---

## 四、商业化路径

### 4.1 阶段 1：技术验证（1-2 周）

**目标**：在 LSPatch 上集成 MultiApp 的壳兼容层，验证 360 加固 app 能稳定运行。

**任务**：
1. 将 `native-hook.cpp` 的 GOT hook + 环境伪装移植到 LSPatch 的 `patch-loader`
2. 将 `JiaguRuntime` 的壳加载逻辑集成到 LSPatch 的 loader
3. 验证 QQ 阅读（360 加固）能稳定启动和运行
4. 修复 BSS 段 dump，导入 Ghidra 离线分析

**交付物**：
- 可运行的 PoC
- 技术验证报告
- 已知问题列表

### 4.2 阶段 2：MVP（2-4 周）

**目标**：支持 360 加固 app 的多开，免费章节阅读可用。

**任务**：
1. 支持 360 加固 app 的多开
2. 免费章节阅读 + 基础功能
3. 登录问题：WebView 登录 或 注入 Frida Gadget 分析
4. UI 集成（LSPatch 风格）

**交付物**：
- MVP 版本
- 用户文档
- 测试报告

### 4.3 阶段 3：产品化（1-2 月）

**目标**：支持更多壳类型，企业级功能。

**任务**：
1. 支持更多壳类型（腾讯加固、梆梆加固）
2. 企业级功能（MDM 集成、批量部署）
3. 性能优化
4. 稳定性测试

**交付物**：
- 产品版本
- 企业文档
- 部署指南

### 4.4 阶段 4：商业化（3-6 月）

**目标**：企业授权模式，技术支持和维护。

**任务**：
1. 企业授权模式
2. 技术支持和维护
3. 法律合规审查
4. 市场推广

**交付物**：
- 商业版本
- 授权系统
- 技术支持体系

---

## 五、法律合规

### 5.1 定位

- **企业级 Android 应用兼容层**
- **用途**：企业多开、测试环境、自动化测试、应用隔离
- **不破解**：不修改业务逻辑，不绕过付费墙
- **合规**：参考 Parallel Space、Dual Space 等商业产品的法律模式

### 5.2 法律风险

| 风险 | 应对 |
|------|------|
| 壳更新后检测绕过 | 模块化适配器，快速迭代 |
| Android 版本兼容 | LSPlant + ShadowHook 双后端 |
| 性能影响 | 按需加载，不全量 hook |
| 法律风险 | 明确定位为兼容层，不破解 |

### 5.3 商业模式

- **企业授权**：按设备数/企业授权
- **技术支持**：按需付费
- **定制开发**：按项目收费

---

## 六、技术风险

| 风险 | 概率 | 影响 | 应对 |
|------|------|------|------|
| 壳更新后检测绕过 | 中 | 高 | 模块化适配器，快速迭代 |
| Android 版本兼容 | 中 | 中 | LSPlant + ShadowHook 双后端 |
| 性能影响 | 低 | 中 | 按需加载，不全量 hook |
| 法律风险 | 低 | 高 | 明确定位为兼容层，不破解 |

---

## 七、下一步行动

### 立即执行
1. **技术验证**：在 LSPatch 上集成 MultiApp 的壳兼容层
2. **验证 360 加固 app**：QQ 阅读能稳定启动和运行
3. **修复 BSS 段 dump**：导入 Ghidra 离线分析

### 短期（1-2 周）
1. **MVP 版本**：支持 360 加固 app 的多开
2. **UI 集成**：LSPatch 风格的用户界面
3. **测试报告**：稳定性、兼容性测试

### 中期（1-2 月）
1. **产品化**：支持更多壳类型
2. **企业级功能**：MDM 集成、批量部署
3. **性能优化**：启动速度、内存占用

### 长期（3-6 月）
1. **商业化**：企业授权模式
2. **技术支持**：技术团队建设
3. **市场推广**：目标用户群体
