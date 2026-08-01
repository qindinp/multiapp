# FileSystemHook路径测试文档

## 概述
本文档详细说明FileSystemHook路径测试用例的设计、执行和维护，用于S2问题修复的回归测试。

## 1. 测试目标

### 主要目标
1. 验证FileSystemHook路径重写功能正确性
2. 确保S2-7 (FileSystemHook路径覆盖扩展) 修复有效
3. 验证路径安全性，防止误修改无关路径
4. 为未来维护提供测试参考

### 测试范围
- Android数据目录路径重写
- 多用户路径支持
- 设备加密存储路径支持
- 外部存储路径支持
- 边界条件和异常情况

## 2. 测试用例设计

### 2.1 核心路径测试 (12个测试)

| 测试用例 | 输入路径 | 预期结果 | 验证点 |
|---------|---------|---------|--------|
| data_user_0 | `/data/user/0/{pkg}/files/test.txt` | 替换为stub包名 | 基本路径重写 |
| data_user_10 | `/data/user/10/{pkg}/cache/data.bin` | 替换为stub包名 | 多用户支持 |
| data_user_de_0 | `/data/user_de/0/{pkg}/files/test.txt` | 替换为stub包名 | 设备加密存储 |
| data_user_de_10 | `/data/user_de/10/{pkg}/cache/data.bin` | 替换为stub包名 | 多用户设备加密 |
| data_data | `/data/data/{pkg}/shared_prefs/config.xml` | 替换为stub包名 | 传统路径支持 |
| storage_emulated_0 | `/storage/emulated/0/Android/data/{pkg}/files/image.jpg` | 替换为stub包名 | 外部存储 |
| storage_emulated_0_obb | `/storage/emulated/0/Android/obb/{pkg}/main.obb` | 替换为stub包名 | OBB路径 |
| storage_emulated_0_media | `/storage/emulated/0/Android/media/{pkg}/video.mp4` | 替换为stub包名 | 媒体路径 |
| sdcard_data | `/sdcard/Android/data/{pkg}/files/document.pdf` | 替换为stub包名 | SD卡数据 |
| sdcard_obb | `/sdcard/Android/obb/{pkg}/patch.obb` | 替换为stub包名 | SD卡OBB |
| sdcard_media | `/sdcard/Android/media/{pkg}/audio.mp3` | 替换为stub包名 | SD卡媒体 |
| mnt_sdcard_data | `/mnt/sdcard/Android/data/{pkg}/files/download.zip` | 替换为stub包名 | 挂载点路径 |

### 2.2 边界条件测试 (10个测试)

| 测试用例 | 输入路径 | 预期结果 | 验证点 |
|---------|---------|---------|--------|
| 无原始包名 | `/data/user/0/com.other.app/files/test.txt` | 不修改 | 安全性验证 |
| 相似包名 | `/data/user/0/{pkg}.extra/files/test.txt` | 不修改 | 精确匹配 |
| 多处包名 | `/data/user/0/{pkg}/files/{pkg}/data.bin` | 全部替换 | 多重替换 |
| 空路径 | `""` | 不修改 | 空值处理 |
| 仅包名 | `{pkg}` | 不修改 | 特殊格式 |
| 非标准位置 | `/some/path/{pkg}/other/path` | 不修改 | 模式匹配 |
| Windows路径 | `C:\Users\test\{pkg}\files\test.txt` | 不修改 | 跨平台兼容 |
| 相对路径 | `./{pkg}/files/test.txt` | 不修改 | 相对路径 |
| 特殊字符 | `/data/user/0/{pkg}/files/test file (1).txt` | 替换为stub包名 | 特殊字符处理 |
| Unicode字符 | `/data/user/0/{pkg}/files/测试文件.txt` | 替换为stub包名 | 国际化支持 |
| 超长路径 | `/data/user/0/{pkg}/{1000个字符}/test.txt` | 替换为stub包名 | 性能边界 |

## 3. 测试执行指南

### 3.1 环境准备
```bash
# 检查Java版本
java -version

# 检查Android SDK
echo $ANDROID_HOME

# 进入项目目录
cd /path/to/multiapp
```

### 3.2 执行测试
```bash
# 运行FileSystemHook路径测试
./gradlew :core:identity:test --tests "com.multiapp.core.identity.FileSystemHookPathTest"

# 查看详细测试结果
./gradlew :core:identity:test --tests "com.multiapp.core.identity.FileSystemHookPathTest" --info
```

### 3.3 查看测试报告
```bash
# 打开测试报告
open core/identity/build/reports/tests/test/index.html

# 或者查看XML格式报告
cat core/identity/build/test-results/test/*.xml
```

## 4. 测试结果分析

### 4.1 成功标准
- 所有22个测试用例通过
- 无测试失败或跳过
- 测试执行时间在合理范围内 (< 30秒)

### 4.2 失败分析
如果测试失败，按以下步骤分析：

1. **查看失败测试详情**
   ```bash
   ./gradlew :core:identity:test --tests "com.multiapp.core.identity.FileSystemHookPathTest" --info
   ```

2. **检查失败原因**
   - 测试代码错误
   - 实现代码错误
   - 环境问题

3. **修复方案**
   - 测试代码错误：修改测试用例
   - 实现代码错误：通知软件工程师
   - 环境问题：检查环境配置

## 5. 维护指南

### 5.1 添加新测试用例
当需要添加新的路径模式时：

1. **在FileSystemHook.kt中添加路径处理**
2. **在FileSystemHookPathTest.kt中添加对应测试**
3. **更新本文档**

### 5.2 修改现有测试
如果实现逻辑变化：

1. **更新测试用例以匹配新逻辑**
2. **确保所有相关测试通过**
3. **更新文档说明**

### 5.3 测试数据管理
- 测试数据使用变量 `{pkg}` 代表包名
- 保持测试数据一致性
- 避免硬编码测试数据

## 6. 常见问题解答

### Q1: 测试失败怎么办？
A1: 首先查看失败详情，确定是测试代码问题还是实现代码问题。如果是实现代码问题，通知软件工程师修复。

### Q2: 如何添加新的路径模式？
A2: 在FileSystemHook.kt的rewritePath方法中添加新的路径处理，然后在测试文件中添加对应测试用例。

### Q3: 测试覆盖不全怎么办？
A3: 分析缺失的测试场景，添加相应的测试用例。参考本文档的测试用例设计部分。

### Q4: 测试环境有问题怎么办？
A4: 检查Java版本、Android SDK配置、Gradle版本等环境变量。参考测试执行指南部分。

## 7. 相关文件

### 测试文件
- `core/identity/src/test/java/com/multiapp/core/identity/FileSystemHookPathTest.kt`
- `core/identity/src/main/java/com/multiapp/core/identity/FileSystemHook.kt`

### 配置文件
- `config/detekt/detekt.yml`
- `app/proguard-rules.pro`
- `core/model/proguard-rules.pro`

### 文档文件
- `test-execution-plan.md`
- `test-documentation.md` (本文档)

## 8. 联系方式

- **QA工程师**: 严过关 (software-qa-engineer)
- **软件工程师**: Alex (software-engineer)
- **团队负责人**: team-lead

## 9. 版本历史

| 版本 | 日期 | 修改内容 | 修改人 |
|-----|------|---------|--------|
| 1.0 | 2025-07-31 | 初始版本 | 严过关 |

## 10. 附录

### 10.1 测试用例代码示例
```kotlin
@Test
fun `rewritePath replaces data_user_0 path correctly`() {
    val inputPath = "/data/user/0/$originalPkg/files/test.txt"
    val expectedPath = "/data/user/0/$stubPkg/files/test.txt"
    
    val result = FileSystemHook.rewritePath(inputPath, originalPkg, stubPkg)
    
    assertEquals(expectedPath, result)
}
```

### 10.2 常用命令
```bash
# 运行单个测试
./gradlew :core:identity:test --tests "com.multiapp.core.identity.FileSystemHookPathTest.rewritePath replaces data_user_0 path correctly"

# 运行所有测试
./gradlew :core:identity:test

# 生成测试报告
./gradlew :core:identity:test jacocoTestReport
```

### 10.3 测试数据模板
```kotlin
// 测试数据
private val originalPkg = "com.example.original.app"
private val stubPkg = "com.example.stub.app"

// 测试路径模板
val testPaths = mapOf(
    "data_user_0" to "/data/user/0/{pkg}/files/test.txt",
    "data_user_10" to "/data/user/10/{pkg}/cache/data.bin",
    // ... 更多路径
)
```