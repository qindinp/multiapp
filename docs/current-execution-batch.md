# 当前执行批次表（基于 docs/productization-plan.md 已落盘内容）

## 批次状态
- 批次 A（文档与基线）✅ 完成
- 批次 B（第一轮 UI 改造）✅ 完成
- 批次 C（验证与收口）✅ 完成（离线可完成部分）
- 批次 D（真机验证）⬜ 需设备

## 已完成证据
- docs/productization-plan.md：完整执行方案文档
- LauncherScreen.kt：顶部汇总栏 + FAB 弹性动画（spring dampingRatio=0.9）
- AppManagerScreen.kt：顶部汇总栏 + 原有卡片结构保持不变
- SettingsScreen.kt：紧凑头部 + 身份模板说明 + 存储与缓存分组 + 高级设置占位
- assembleDebug BUILD SUCCESSFUL
- testDebugUnitTest BUILD SUCCESSFUL
- 工作树仅含 3 个 UI 文件变更 + 2 个文档，无意外修改

## 批次 C 验证详情
- 构建验证：assembleDebug 通过（33s）
- 测试验证：testDebugUnitTest 通过（4s）
- LauncherViewModelTest：覆盖 create/launch/delete/error/empty 状态
- AppManagerViewModelTest：覆盖 init/refresh/delete/error 状态
- SettingsViewModelTest：覆盖初始状态/数据正确性
- git diff --stat：仅 3 个 UI 文件 + 2 个文档变更

## 批次 D 真机门槛（需设备后闭环）
- 真实安装/启动/多实例切换/权限弹窗链路
- 冷启动、首次创建实例耗时、内存占用等性能基线
- device-proof 类能力验证（recents-device-proof / cross-process-route / external-uri-grant 等）

## 当前未闭合能力边界
- Activity recents-device-proof
- Broadcast cross-process-route
- Provider external-uri-grant
- AppOps attribution-chain
- Permission runtime-permission-dialog / auto-reset / shared-uid-permission
- Storage media-provider-isolation
- Native runtime-native-load / register-natives-verdict
- Package package-refresh-device-proof
