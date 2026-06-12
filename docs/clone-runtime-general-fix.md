# Clone Runtime 通用修复方案

## 目标

让分身 APK 在运行时保持两套身份：

- 应用内部看到原包名，例如 `com.qq.reader`。
- Android 系统服务看到分身包名，例如 `com.qq.reader.clonestub_xxx`。

这样避免每个业务崩溃点都靠 DEX patch 单独中和。

## 根因

分身进程 UID 属于 stub 包，但 guest 代码仍按原包名调用系统接口。典型失败包括：

- `NotificationManager` 校验 UID 和 package 不匹配。
- `startActivity` 指向原包未导出组件，被系统按跨包启动拒绝。
- `Resources` 使用 stub 资源路径解析 guest 的 `0x7f...` ID。
- 大面积中和混淆方法会误伤单例 getter 或状态机方法。

## 通用修复层

1. Manifest 组件映射
   - stub manifest 必须声明 origin APK 的 Activity/Service/Receiver/Provider。
   - launcher activity 可强制 exported，内部 activity 保留原 exported 属性。

2. Intent 组件重写
   - 在 `Instrumentation.execStartActivity*` 层拦截 `Intent`。
   - 如果 `Intent.component.packageName == originalPkg`，改为 `stubPkg`，className 保持不变。
   - 如果 `Intent.package == originalPkg`，改为 `stubPkg`。
   - 同步处理 selector intent。

3. 资源路径固定
   - `Application` / `Activity` context 的 resources/assets/codePath/resourcePath 指向 origin APK。
   - `LoadedApk.mResources` 和 Activity theme 使用 origin APK manifest 的资源 ID。

4. 系统服务包名双轨
   - 对应用层查询返回 originalPkg。
   - 对需要 UID/package 校验的系统服务传 stubPkg。
   - 后续重点补 `NotificationManager`、`PackageManager`、`ActivityTaskManager` 的参数重写。

5. DEX patch 收敛
   - 只中和确定不兼容的初始化或 native 注册点。
   - 不中和构造函数、静态初始化、返回自身类型的方法。
   - 不再因为单个崩溃无限扩大混淆方法中和范围。

## 当前执行阶段

Phase 1:

- 安装 `IntentRemappingInstrumentation`。
- 修复 `origin.apk` 缓存 marker，覆盖安装时刷新旧缓存。
- 保留 self-returning 方法，避免单例 getter 被 patch 成 null。

Phase 2:

- 补 `NotificationManager` package 参数重写。
- 补隐式 intent resolve 到 stub 组件。
- 增加自动化验证脚本：安装、清缓存、启动、抓 `crash` buffer 和 `exit-info`。

## 验证标准

- 启动后不再出现 `not exported from uid`。
- 启动后不再出现 `cannot post for pkg originalPkg`。
- `dumpsys activity exit-info <stubPkg>` 没有新的 `APP CRASH(EXCEPTION)`。
- `pidof <stubPkg>` 在启动后 30 秒仍能返回主进程 PID。
