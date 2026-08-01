# S2问题修复回归测试执行计划

## 概述
本计划详细说明如何在CI环境中执行S2问题修复的回归测试，确保所有修复正确且不影响现有功能。

## 1. 测试环境准备

### 前置条件
- Java 17+ 已安装
- Android SDK 37 已配置
- Gradle 8.9+ 已安装
- 项目代码已更新到最新版本

### 环境变量
```bash
export JAVA_HOME=/path/to/java17
export ANDROID_HOME=/path/to/android/sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
```

## 2. 测试执行步骤

### 步骤1: 编译检查
```bash
# 检查代码编译是否通过
./gradlew assembleDebug
```

### 步骤2: 运行FileSystemHook路径测试
```bash
# 运行FileSystemHook路径测试
./gradlew :core:identity:test --tests "com.multiapp.core.identity.FileSystemHookPathTest"

# 预期结果: 22个测试全部通过
```

### 步骤3: 运行所有单元测试
```bash
# 运行所有单元测试
./gradlew test

# 预期结果: 所有测试通过，无新增失败
```

### 步骤4: Detekt静态代码分析
```bash
# 运行Detekt检查
./gradlew detekt

# 预期结果: 无新增警告，MagicNumber规则正确应用
```

### 步骤5: ProGuard规则验证
```bash
# 构建release版本
./gradlew assembleRelease

# 预期结果: 构建成功，无ClassNotFoundException
```

### 步骤6: 集成测试（可选）
```bash
# 运行Android instrumentation tests
./gradlew connectedAndroidTest

# 预期结果: 所有集成测试通过
```

## 3. 测试结果验证

### 通过标准
1. **FileSystemHook测试**: 22/22 通过
2. **单元测试**: 100% 通过
3. **Detekt**: 无新增警告
4. **Release构建**: 成功，无异常
5. **集成测试**: 100% 通过（如果执行）

### 失败处理
1. **测试失败**:
   - 检查失败测试的详细日志
   - 分析是测试代码问题还是实现代码问题
   - 根据问题类型决定修复方案

2. **Detekt警告**:
   - 检查警告详情
   - 如果是新增警告，需要修复代码
   - 如果是配置问题，更新detekt.yml

3. **构建失败**:
   - 检查错误日志
   - 验证ProGuard规则
   - 检查依赖冲突

## 4. 测试报告生成

### 自动报告
```bash
# 生成测试报告
./gradlew test jacocoTestReport

# 报告位置: build/reports/tests/test/index.html
```

### 手动报告
1. 收集所有测试结果
2. 记录失败测试和错误信息
3. 生成总结报告
4. 发送给团队成员

## 5. 回滚计划

如果发现严重问题：
1. 立即通知团队负责人
2. 回滚到上一个稳定版本
3. 分析问题原因
4. 重新评估修复方案

## 6. 时间安排

### 执行时间
- **预计执行时间**: 30-45分钟
- **报告生成时间**: 5-10分钟
- **问题分析时间**: 根据问题复杂度而定

### 执行顺序
1. 环境准备 (5分钟)
2. 编译检查 (5分钟)
3. 单元测试 (10分钟)
4. Detekt检查 (5分钟)
5. Release构建 (10分钟)
6. 报告生成 (5分钟)

## 7. 负责人

- **测试执行**: QA工程师严过关
- **问题分析**: QA工程师 + 软件工程师
- **最终确认**: 团队负责人

## 8. 附录

### 常用命令
```bash
# 查看测试结果
./gradlew :core:identity:test --tests "com.multiapp.core.identity.FileSystemHookPathTest" --info

# 查看Detekt报告
./gradlew detekt --report html

# 查看测试覆盖率
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

### 相关文件
1. 测试文件: `core/identity/src/test/java/com/multiapp/core/identity/FileSystemHookPathTest.kt`
2. 实现文件: `core/identity/src/main/java/com/multiapp/core/identity/FileSystemHook.kt`
3. Detekt配置: `config/detekt/detekt.yml`
4. ProGuard规则: `app/proguard-rules.pro`, `core/model/proguard-rules.pro`

### 联系人
- **QA工程师**: 严过关 (software-qa-engineer)
- **软件工程师**: Alex (software-engineer)
- **团队负责人**: team-lead