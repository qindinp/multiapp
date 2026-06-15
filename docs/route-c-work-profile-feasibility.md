# Route C: Work Profile / 多用户方案可行性验证

日期：2026-06-15
状态：技术预研
作者：Android 系统架构师

---

## 1. 执行摘要

**结论：Route C 技术上可行，但不建议作为 MultiApp 当前阶段的主线方案。**

- Work Profile / 多用户方案确实能提供更高的兼容性（真实安装态、系统级隔离）
- 但存在严重的分发和权限障碍，不适合普通用户场景
- 建议作为"高级兼容模式"保留，与 Route A 并行发展

---

## 2. Android Work Profile API 调研

### 2.1 核心 API 概览

| API | 用途 | 权限要求 | 可用性 |
|-----|------|----------|--------|
| `DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE` | 创建 Work Profile | 需要 Device Admin 或用户交互 | Android 5.0+ |
| `DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE` | 完全托管设备 | 需要 NFC/QR/零触摸配置 | Android 6.0+ |
| `DevicePolicyManager.createAndManageUser()` | 创建并管理用户 | `MANAGE_USERS` + `CREATE_USERS` (系统 API) | Android 8.0+ |
| `UserManager.getUsers()` | 列出所有用户 | `MANAGE_USERS` 或调用者是 Device Admin | Android 4.2+ |
| `UserManager.isManagedProfile()` | 检查是否是 Work Profile | 无 | Android 5.0+ |
| `PackageManager.installExistingPackageAsUser()` | 在其他用户中安装已有包 | `INTERACT_ACROSS_USERS` 或同一包名 | Android 5.0+ |
| `UserManager.USER_TYPE_PROFILE_MANAGED` | Work Profile 类型常量 | 无 | Android 12+ |

### 2.2 Work Profile 创建流程

#### 方案 A：标准用户交互流程（推荐）

```kotlin
// 1. 启动系统配置 Intent
val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
    putExtra(
        DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
        ComponentName(context, WorkProfileAdminReceiver::class.java)
    )
    putExtra(
        DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true
    )
}
context.startActivityForResult(intent, REQUEST_PROVISION_MANAGED_PROFILE)

// 2. 用户确认后，系统创建 Work Profile
// 3. 在 WorkProfileAdminReceiver.onProfileProvisioningComplete() 中继续配置
```

**限制**：
- 需要用户手动确认
- 每个 App 只能创建一个 Work Profile（Android 限制）
- 部分 OEM 可能禁用此 Intent

#### 方案 B：系统 API 调用（需要系统权限）

```kotlin
// 需要 android.permission.MANAGE_USERS 和 android.permission.CREATE_USERS
// 这些是 signature|privileged 权限，普通 App 无法获取
val dpm = context.getSystemService(DevicePolicyManager::class.java)
val adminComponent = ComponentName(context, WorkProfileAdminReceiver::class.java)

// createAndManageUser 是 @hide API
val userInfo = dpm.createAndManageUser(
    adminComponent,
    "Work Profile",  // 用户名
    adminComponent,  // Profile Owner
    null,  // 可选的用户 icon
    0  // 用户标志
)
```

**限制**：
- 需要系统签名或 privileged 权限
- 普通 App 无法调用
- HyperOS 可能有额外限制

#### 方案 C：Shizuku / ADB 辅助

```kotlin
// 使用 Shizuku 获取 shell 权限
// 通过 pm create-user 命令创建用户
// 然后通过 pm install-existing 在新用户中安装包
```

**限制**：
- 需要用户安装 Shizuku
- 需要 ADB 激活
- 不适合普通用户

### 2.3 在 Work Profile 中安装应用

```kotlin
// 方案 1：使用 PackageManager（系统 API）
val pm = context.packageManager
pm.installExistingPackageAsUser(packageName, workProfileUserId)

// 方案 2：使用 PackageInstaller（公开 API）
// 需要在 Work Profile 中运行的 App 来执行
val installer = context.packageManager.packageInstaller
val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
val sessionId = installer.createSession(params)
val session = installer.openSession(sessionId)
// ... 写入 APK 数据 ...
session.commit(pendingIntent.intentSender)

// 方案 3：通过 ContentProvider 传递 APK
// 主用户通过 ContentProvider 将 APK 暴露给 Work Profile
// Work Profile 中的 App 读取并安装
```

### 2.4 数据隔离模型

```
主用户 (userId=0):
  /data/user/0/com.example.app/
    ├── files/
    ├── cache/
    ├── shared_prefs/
    └── databases/

Work Profile (userId=10):
  /data/user/10/com.example.app/
    ├── files/
    ├── cache/
    ├── shared_prefs/
    └── databases/
```

**优势**：
- 数据目录完全隔离
- UID 空间独立
- 文件系统天然隔离
- 无需用户态路径重定向

---

## 3. HyperOS 多用户支持调研

### 3.1 MIUI/HyperOS 多用户历史

| 版本 | 多用户支持 | 特性 |
|------|-----------|------|
| MIUI 8+ | 支持 | "手机分身"功能 |
| MIUI 12+ | 支持 | 改进的多用户切换 |
| HyperOS 1.0 | 支持 | 保留多用户功能 |
| HyperOS 2.0 | 支持 | 优化性能和切换速度 |

### 3.2 HyperOS 特有行为

#### 已知限制

1. **Work Profile 创建可能被拦截**
   - HyperOS 可能自定义了 `ACTION_PROVISION_MANAGED_PROFILE` 的处理
   - 部分机型可能禁用 Work Profile 功能
   - 需要实机测试验证

2. **多用户数量限制**
   - HyperOS 默认限制用户数量（通常 2-3 个）
   - 可能需要 root 或系统权限突破

3. **应用安装限制**
   - HyperOS 可能限制在其他用户中安装应用
   - 可能有额外的包管理检查

4. **性能优化**
   - HyperOS 可能对后台用户进行资源限制
   - 内存管理可能更激进

#### 可能的系统 API

```kotlin
// 小米可能有以下扩展 API（需要反编译验证）
// MiuiMultiUserManager
// MiuiUserManager
// com.miui.server.MultiUserManagerService

// 可能的系统属性
// persist.sys.multi_user_enabled
// ro.config.multi_user_enabled
// persist.sys.miui_optimization
```

### 3.3 HyperOS 实测要点

**需要在实机上验证的关键点**：

1. `ACTION_PROVISION_MANAGED_PROFILE` 是否能正常触发
2. 创建的 Work Profile 是否完整（能否安装应用）
3. Work Profile 中的应用能否正常运行
4. 数据隔离是否完整
5. 性能影响如何

---

## 4. 实现方案设计

### 4.1 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    MultiApp 主进程                        │
│  ┌─────────────────────────────────────────────────────┐ │
│  │              WorkProfileManager                     │ │
│  │  - 检查 Work Profile 支持                           │ │
│  │  - 创建 Work Profile                               │ │
│  │  - 管理 Profile 中的应用                            │ │
│  └─────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
           │
           │ 跨用户通信
           ▼
┌─────────────────────────────────────────────────────────┐
│                Work Profile (userId=10)                   │
│  ┌─────────────────────────────────────────────────────┐ │
│  │           ProfileManagerService                     │ │
│  │  - 接收安装请求                                      │ │
│  │  - 安装应用到 Profile                               │ │
│  │  - 管理 Profile 中的应用                            │ │
│  └─────────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────────┐ │
│  │           分身应用 (真实安装)                         │ │
│  │  - 独立数据目录                                     │ │
│  │  - 独立 UID                                         │ │
│  │  - 系统级隔离                                       │ │
│  └─────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

### 4.2 核心组件

#### WorkProfileManager

```kotlin
class WorkProfileManager(private val context: Context) {
    
    // 检查是否支持 Work Profile
    fun isWorkProfileSupported(): Boolean
    
    // 检查是否已有 Work Profile
    fun hasExistingWorkProfile(): Boolean
    
    // 创建 Work Profile
    fun createWorkProfile(): Result<WorkProfileInfo>
    
    // 在 Work Profile 中安装应用
    fun installAppInProfile(apkPath: String, packageName: String): Result<Unit>
    
    // 启动 Work Profile 中的应用
    fun launchAppInProfile(packageName: String): Result<Unit>
    
    // 删除 Work Profile
    fun removeWorkProfile(): Result<Unit>
}
```

#### WorkProfileAdminReceiver

```kotlin
class WorkProfileAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        // Work Profile 创建完成
        // 启用 Profile
        // 配置策略
    }
}
```

### 4.3 通信机制

**主用户 ↔ Work Profile 通信选项**：

| 方案 | 优点 | 缺点 |
|------|------|------|
| ContentProvider | 标准 API，跨用户支持 | 需要权限声明 |
| Bound Service (AIDL) | 实时通信 | 复杂度高 |
| Broadcast | 简单 | 不可靠，延迟高 |
| FileObserver + 共享文件 | 简单 | 需要文件权限 |

**推荐方案**：ContentProvider + AIDL Service

```kotlin
// 主用户端
class MainContentProvider : ContentProvider() {
    // 暴露安装请求给 Work Profile
}

// Work Profile 端
class ProfileContentProvider : ContentProvider() {
    // 接收安装请求并执行
}
```

---

## 5. 优缺点分析

### 5.1 优势

| 优势 | 说明 |
|------|------|
| **真实安装态** | 应用在 Work Profile 中是真正安装的，不是虚拟的 |
| **系统级隔离** | 数据、UID、文件系统天然隔离 |
| **加固 App 兼容** | 加固壳检测到的是真实安装环境，兼容性极高 |
| **系统服务支持** | 所有系统服务正常工作，无需用户态代理 |
| **权限隔离** | Work Profile 中的权限独立管理 |
| **通知隔离** | 通知天然隔离，不会混淆 |

### 5.2 劣势

| 劣势 | 说明 | 严重程度 |
|------|------|----------|
| **分发障碍** | 普通 App 无法创建 Work Profile | 🔴 严重 |
| **用户交互** | 需要用户手动确认创建 | 🟡 中等 |
| **多开限制** | 通常只能创建一个 Work Profile | 🔴 严重 |
| **产品形态** | 不是用户期望的"多开"体验 | 🟡 中等 |
| **OEM 兼容** | 不同厂商行为可能不同 | 🟡 中等 |
| **HyperOS 限制** | 小米可能有额外限制 | 🟡 待验证 |
| **维护成本** | 需要维护两个方案（Route A + Route C） | 🟡 中等 |

### 5.3 与 Route A 对比

| 维度 | Route A (Stub 虚拟化) | Route C (Work Profile) |
|------|----------------------|------------------------|
| 兼容性 | 中等（需要大量 hook） | 高（真实安装） |
| 加固 App | 差（需要专门适配） | 好（系统级环境） |
| 多开数量 | 无限制 | 受限（通常 1 个） |
| 分发 | 普通 App | 需要系统权限或用户交互 |
| 数据隔离 | 用户态实现 | 系统级实现 |
| 系统服务 | 需要代理 | 正常工作 |
| 性能 | 有 hook 开销 | 原生性能 |
| 维护成本 | 高（需要持续适配） | 中（系统 API 稳定） |

---

## 6. 工作量估算

### 6.1 最小可行产品 (MVP)

| 任务 | 工作量 | 说明 |
|------|--------|------|
| WorkProfileManager 核心实现 | 3-5 天 | 创建 Profile、安装应用、启动应用 |
| 通信机制实现 | 2-3 天 | ContentProvider + AIDL |
| UI 集成 | 2-3 天 | 添加 Work Profile 管理入口 |
| 测试和调试 | 3-5 天 | 实机测试、兼容性验证 |
| **MVP 总计** | **10-16 天** | |

### 6.2 生产就绪

| 任务 | 工作量 | 说明 |
|------|--------|------|
| 错误处理和恢复 | 3-5 天 | 各种异常情况处理 |
| HyperOS 适配 | 5-10 天 | 实机测试、特殊 API 处理 |
| 性能优化 | 2-3 天 | 启动速度、内存优化 |
| 用户体验优化 | 3-5 天 | 引导流程、状态展示 |
| 文档和测试 | 3-5 天 | |
| **生产就绪总计** | **25-35 天** | |

### 6.3 风险和不确定性

| 风险 | 可能性 | 影响 |
|------|--------|------|
| HyperOS 禁用 Work Profile | 中 | 🔴 项目失败 |
| 创建流程被 OEM 修改 | 中 | 🟡 需要额外适配 |
| 多开数量限制无法突破 | 高 | 🔴 产品形态受限 |
| 用户不愿使用 Work Profile | 高 | 🟡 产品接受度低 |

---

## 7. 建议和结论

### 7.1 结论

**Route C 技术上可行，但不适合作为当前主线方案。**

理由：
1. **分发障碍严重**：普通 App 无法创建 Work Profile，需要系统权限或用户交互
2. **产品形态受限**：通常只能创建一个 Work Profile，无法满足"无限多开"需求
3. **HyperOS 不确定性**：小米可能有额外限制，需要大量实机验证
4. **维护成本高**：需要同时维护 Route A 和 Route C

### 7.2 建议

#### 短期（1-3 个月）

- **不推进 Route C**
- 专注于 Route A + Route B（加固 App 兼容）
- 收集用户对多开数量的需求

#### 中期（3-6 个月）

- **作为"高级兼容模式"预研**
- 实现最小验证代码
- 在 HyperOS 设备上实测
- 评估用户接受度

#### 长期（6+ 个月）

- **如果 Route A 兼容性遇到瓶颈，考虑推进**
- 作为"专业版"或"高级版"功能
- 需要评估分发策略（可能需要系统签名或 root）

### 7.3 替代方案

如果目标是提高兼容性，但不想走 Work Profile 路线，可以考虑：

1. **Shizuku 增强**：使用 Shizuku 获取 shell 权限，增强 Route A 能力
2. **Root 模式**：支持 root 用户，提供系统级能力
3. **虚拟系统**：类似 VMOS，运行完整 Android 系统
4. **继续优化 Route A**：投入更多资源优化用户态虚拟化

---

## 8. 附录

### 8.1 参考项目

| 项目 | 方案 | 多开数量 | 分发方式 |
|------|------|----------|----------|
| Shelter | Work Profile | 1 | 普通 App |
| Island | Work Profile | 1 | 普通 App |
| Parallel Space | 用户态虚拟化 | 无限 | 普通 App |
| Dual Space | 用户态虚拟化 | 无限 | 普通 App |
| Samsung Knox | 企业容器 | 受限 | 系统内置 |

### 8.2 关键代码位置

- 验证代码：`core/workprofile/src/main/java/com/multiapp/core/workprofile/WorkProfileManager.kt`
- 技术文档：`docs/route-c-work-profile-feasibility.md`

### 8.3 待验证事项

- [ ] HyperOS 是否支持 `ACTION_PROVISION_MANAGED_PROFILE`
- [ ] HyperOS 对 Work Profile 数量的限制
- [ ] Work Profile 中的应用是否能正常运行加固 App
- [ ] 跨用户通信的性能和可靠性
- [ ] 用户对 Work Profile 方案的接受度
