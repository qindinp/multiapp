# NOTICE

MultiApp — Android 应用级多开宿主（hosted v2 架构）。

Copyright 2026 MultiApp contributors

Licensed under the Apache License, Version 2.0（详见 `LICENSE`）。
本文件列出项目使用的第三方开源组件及其许可。完整且精确的依赖许可清单由
CI 的 license 报告任务生成（见 `build/reports/license/`）；下表为主要组件的
许可归属（按 Gradle 版本目录 `gradle/libs.versions.toml`）。

## 第三方组件许可概览

### AndroidX / Jetpack（Apache-2.0）
- androidx.compose（BOM 2024.12.01）
- androidx.activity / appcompat / core-ktx / lifecycle / navigation / datastore
- androidx.room / security-crypto
- androidx.test（core/runner/rules/ext-junit）

### Kotlin 生态
- org.jetbrains.kotlin（Apache-2.0）
- kotlinx.coroutines（Apache-2.0）

### 依赖注入 / 编译
- Dagger / Hilt（Apache-2.0）
- 注解处理器：kapt / ksp（Apache-2.0）

### 网络 / 序列化
- Gson（Apache-2.0）
- OkHttp / Okio（Apache-2.0；Okio 含 MIT 贡献者致谢）

### 工具库
- Timber（Apache-2.0）
- Coil（Apache-2.0）
- 测试：JUnit5（EPL-2.0）、MockK（Apache-2.0）、Robolectric（MIT）、Turbine（Apache-2.0）

### Android 内部 / hook 相关（注意许可）
- LSPlant（`lsplant` 6.4）——GNU Lesser General Public License v2.1（LGPL-2.1）
- ShadowHook（`shadowhook` 1.1.1）——BSD-3-Clause（ByteDance）
- HiddenApiBypass（`hiddenapibypass` 6.1）——Apache-2.0
- 注意：以上 hook 组件仅存在于 `legacy` 实验变体（D1 决策）；
  `hosted` 发布变体不打包 LSPlant（`liblsplant.so` 已物理迁移至 `core:xposed`）。

### APK 解析 / 构建
- net.dongliu:apk-parser（`apkparser` 2.6.10）——Apache-2.0
- com.android.tools.build:apksig（`apksig` 9.3.0）——Apache-2.0
- org.smali:dexlib2（`dexlib2` 2.5.2）——Apache-2.0

### 其他
- 系统/平台 API 的兼容实现（android.app.ActivityThread 等反射调用）——
  属于 Android Open Source Project（AOSP），Apache-2.0

## 完整性说明

本概览为维护者人工整理，可能存在遗漏或版本漂移。以 CI license 报告任务
（`dependencyLicenseReport`）生成的机器可读清单为准。任何再分发必须附上
各组件自身的 LICENSE/NOTICE 文件。
