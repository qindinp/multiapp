# QQ 阅读分身专项修复交接方案

日期：2026-06-12  
仓库：`C:\Users\Administrator\Desktop\1122\visual app\multiapp`  
分支：`minimal-poc`  
状态：未完成，最新 v112 APK 已生成但尚未真机验证。

## 0. 文档目的

这份文档用于在对话压缩、换 agent、ADB 暂时不可用或后续继续专项时，快速恢复 QQ 阅读分身问题的完整上下文。

文档把信息分成三类：

- `已验证事实`：来自本地 APK、源码、logcat、crash buffer、exit-info 的证据。
- `历史尝试`：来自完整会话记录和中间版本，保留原因与失败方式，但不直接当作当前结论。
- `下一步动作`：手机重新连接后应优先执行的命令和判断标准。

## 1. 最终目标

目标不是“QQ 阅读能打开前台”这么低，而是：

```text
QQ 阅读分身可以正常使用：
启动、书城、搜索、免费阅读、加书架、章节正文加载、账号/书架相关基础功能都不因 clone runtime 崩溃或网络签名异常而失败。
```

当前没有达到这个目标。

## 2. 当前总体结论

当前最重要的结论：

```text
普通 Activity 启动问题已经不是主线。
真正阻塞在 QQ 阅读加固壳 / protected native / qrencrypt-fock / 章节下载 native 的初始化链路。
```

更具体地说：

1. 早期的前台闪退、资源、toast、推送初始化、部分 Java native 缺失已经被多轮 patch 推过。
2. 后来进入主界面后出现“书城网络异常”，证据指向业务签名/加密链路被过度 stub 或未完整初始化。
3. 再往后，章节加载暴露 `OnlineChapterDownloadTask.run()` 未绑定；no-op 可以避免 crash，但不能加载正文。
4. 最新 v110/v111 证据显示，`libjiagu_vip.so` 原始 StubApp 注册链路可以跑到 `RegisterNatives`，但随后从 `libjiagu_vip.so offset=0x11cb88` 主动 `tgkill(SIGKILL)` 自杀。
5. 最新 v112 方案已经按该实测 offset 做定点 callsite patch，但 ADB 断连，尚未验证。

## 3. 当前包与工具信息

目标包名：

```text
com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
```

启动 Activity：

```text
com.qq.reader.activity.launch.DefaultAliasSplashActivity
```

ADB：

```text
C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe
```

NDK：

```text
C:\Users\Administrator\.openclaw\workspace\apk_analysis\ndk\29.0.13599879
```

推荐构建命令：

```powershell
.\gradlew.bat --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :app:assembleDebug
```

最新 APK：

```text
.tmp\qqreader-c9f8-neutralized-v112-callsite-nop-signed.apk
```

v112 APK 状态：

- 已通过 Gradle 构建。
- 已通过离线 patch 脚本重打包。
- 未完成安装/启动验证。
- 原设备 `192.168.2.125:37279` 已 offline，旧无线调试端口重连超时。

## 4. 当前技术路线

当前采用的是“离线 clone 包验证路线”，不是通过 MultiApp UI 每次重新点创建：

1. 以已有 QQ 阅读 clone APK 作为外层壳。
2. 解出外层 `assets/origin.apk`。
3. 对内部 `classes*.dex` 做最小化静态 patch。
4. 注入当前项目构建出来的 `loader.dex`。
5. 注入当前项目构建出来的 native libs。
6. 重新 zipalign/apksigner 签名。
7. 真机安装启动，抓 `logcat`、`crash`、`exit-info`、UI tree。

价值：

- 跳过 MultiApp UI 创建链路的不确定性。
- 每个版本的输入、输出、日志可复现。
- 可以判断问题到底在 clone runtime、QQ 阅读壳、业务签名、还是 MultiApp 构建链路。

离线重打包脚本：

```text
tools\qqreader-offline-patch\patch-qqreader-clone.ps1
```

典型命令：

```powershell
$extract = '.tmp\app-debug-extract-v112'
$zip = '.tmp\app-debug-extract-v112.zip'
$out = '.tmp\qqreader-c9f8-neutralized-v112-callsite-nop-signed.apk'

Remove-Item -LiteralPath $extract,$zip,$out -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination $zip -Force
Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force

.\tools\qqreader-offline-patch\patch-qqreader-clone.ps1 `
  -InputCloneApk .tmp\qqreader-c9f8-current-base.apk `
  -OutputApk $out `
  -LoaderDex "$extract\assets\loader.dex" `
  -NativeLibDir "$extract\lib" `
  -PatchFockSign
```

## 5. 历史问题链与处理状态

### 5.1 启动早期 Java/native 崩溃

历史会话中出现过这些启动期问题：

```text
ReaderApplication.initPushSDK
ReaderApplication.initLoginSDK
ShortcutManager.cihai
ZeusPlatformUtils.initZeus
Fock.sign
SplashActivity.onCreate(Bundle) native missing
MainFlutterActivity / reader_toast_layout resource path
Jiguang push packageName/AppKey mismatch
```

处理策略：

- 对非核心业务启动副作用做静态 neutralize。
- 修外层资源 / context / classloader / native lib path。
- 把粗暴业务 stub 逐步收窄，避免影响网络签名。

当前判断：

- 这些不是最新主阻塞点。
- 但不能随意回滚，因为它们压住了早期启动崩溃链。

### 5.2 书城“网络异常”

历史会话中已经证实过一个重要方向：

```text
能进主界面但书城网络异常，常见原因不是 UI，而是业务签名/加密链路被 stub 坏了。
```

危险做法：

```text
Fock.sign -> null / "" / diagnostic MD5
qrencrypt / FockEncryptBean / FockKeyPoolCache 被空实现覆盖
通用工具类 ae/qdbd/qdcg/qdeb 大面积 neutralize
```

这些做法可能让 App 不崩，但服务端请求签名无效，最终表现为：

```text
书城空白
网络异常
章节无法加载
接口返回异常
```

当前原则：

- 不把 fake `Fock.sign` 当最终方案。
- `FockRT.sn`、`Fock.sign`、qrencrypt keypool 应尽量走原始链路。
- diagnostic MD5 只能用于定位，不能作为“正常使用 QQ 阅读”的结果。

### 5.3 qrencrypt / fock 初始化链路

历史会话中定位过：

```text
com.qq.reader.qrencrypt.fock.qdaa.search(String)
com.qq.reader.qrencrypt.fock.qdaa.search(String, Map)
com.yuewen.fock.Fock.sign(String)
com.yuewen.fock.Fock.setup(String)
com.yuewen.fock.Fock.addKeys(...)
FockKeyPoolCache.getFockEncryptBean(...)
FockKeyPoolCache.updateForckEncryptBean(...)
```

关键认识：

- `qdaa.search(String)` 会直接调用 `Fock.sign(String)`。
- `qdaa.search(String, Map)` 会先 `Fock.setup` / `Fock.addKeys`。
- 如果 keypool 未真实初始化，`Fock.sign` 可能进入 native 空指针路径。
- 如果 keypool 被 fake 成空 bean，App 可能误以为 keypool 已存在，反而跳过真实拉取。

当前判断：

- 这条链解释了“主界面能进但内容异常/签名崩溃”的历史阶段。
- 最新阶段又向前卡到了 `libjiagu_vip.so` 的壳自杀点，因此下一步先验证 v112 的壳自杀 callsite patch。

### 5.4 章节加载 native 缺失

已确认错误：

```text
java.lang.UnsatisfiedLinkError:
No implementation found for void
com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask.run()
```

结论：

- `OnlineChapterDownloadTask.run()` no-op 不是最终修复。
- no-op 可以让打开书不崩，但章节会卡在 `正在加载...`。
- 正常使用必须恢复真实章节下载 native 注册，或实现等价兼容层。

优先方向：

```text
先恢复壳和业务 native 的 RegisterNatives 链路，再看 OnlineChapterDownloadTask 是否自然绑定。
```

## 6. 最新 v107-v112 证据链

### 6.1 v107 / v109

现象：

- 在保留原始 `libjiagu_vip.so` 路径时，仍会出现自杀或后续 native 缺失。
- 说明问题不是某个单纯 Java patch 没打，而是壳 native 初始化链路没有完整通过。

相关文件：

```text
.tmp\qqreader-v107-retest-logcat.txt
.tmp\qqreader-v109-no-selfpatch-logcat.txt
```

### 6.2 v110：最关键证据

v110 加入 GOT `kill/tgkill` hook 后，抓到关键事实。

日志证明 `StubApp` 原始 native 注册成功：

```text
.tmp\qqreader-v110-got-tgkill-logcat.txt:4343
RegisterNatives: class=com.stub.StubApp count=10
caller=.../libjiagu_vip.so offset=0x1116b4
```

日志证明 `interface20` 注册成功：

```text
.tmp\qqreader-v110-got-tgkill-logcat.txt:4346
RegisterNatives StubApp: captured original interface20=...
```

随后同一个 so 主动杀进程：

```text
.tmp\qqreader-v110-got-tgkill-logcat.txt:4792
GOT tgkill intercepted:
tgid=19639 tid=19639 sig=9
caller=.../libjiagu_vip.so offset=0x11cb88
```

结论：

```text
v110 证明 libjiagu_vip.so 能进入真实 StubApp RegisterNatives，
但之后从 offset=0x11cb88 发起 self SIGKILL。
```

这是目前最强的本地证据。

### 6.3 v111：全局抑制方案失败

v111 尝试在 `nativeLoad` 前打开内部抑制：

```text
nativeSetSuppressSelfSigkill: enabled=1
```

结果：

```text
.tmp\qqreader-v111-internal-sigkill-logcat.txt:5109
nativeLoadLibraryForGuest: FAILED:
JNI_ERR returned from JNI_OnLoad
```

随后 Java 崩溃：

```text
No implementation found for boolean com.stub.StubApp.interface20()
```

结论：

```text
不能在 JNI_OnLoad 前使用全局 self SIGKILL 抑制。
这个动作会改变壳执行路径，导致更早 JNI_ERR，StubApp 注册无法完成。
```

### 6.4 v112：当前待验证方案

v112 不再启用全局 self SIGKILL 抑制，而是做更窄的定点 patch。

依据：

```text
v110 caller offset = 0x11cb88
AArch64 caller 通常是返回地址
callsite offset = 0x11cb88 - 4 = 0x11cb84
```

v112 实现：

- `dlopenOnly(libjiagu_vip.so)` 后找到 so 基址。
- 保留 GOT hook，用于继续记录 `kill/tgkill`。
- 检查 `base + 0x11cb84` 的 AArch64 指令。
- 如果是 `BL` 或 `BLR`，替换成 `NOP`。

NOP 指令：

```text
0xd503201f
```

涉及源码：

```text
core/hook/src/main/cpp/native-hook.cpp
core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
```

v112 状态：

```text
.tmp\qqreader-c9f8-neutralized-v112-callsite-nop-signed.apk
```

- 已构建。
- 已重打包。
- 未真机验证。

## 7. 当前工作区改动摘要

当前工作区未提交，主要文件：

```text
core/hook/build.gradle.kts
core/hook/src/main/cpp/native-hook.cpp
core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt
core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
core/loader/src/main/java/com/multiapp/core/loader/QqReaderSignCompat.java
docs/current-repository-state.md
docs/qqreader-offline-patch.md
docs/qqreader-clone-special-plan.md
tools/qqreader-offline-patch/NeutralizeDex.java
tools/qqreader-offline-patch/DecodeT2Strings.java
tools/qqreader-offline-patch/DumpDexClassVerbose.java
```

重要说明：

- 这些改动不是一个单一原子修复。
- 里面混合了 QQ 阅读专项、native hook 日志增强、离线 patch 工具增强、Fock sign 兼容实验。
- 提交前应拆成至少三组：文档、工具、runtime 修复。

## 8. 当前本地 artifact 清单

最新 APK：

```text
.tmp\qqreader-c9f8-neutralized-v112-callsite-nop-signed.apk
```

近期 APK：

```text
.tmp\qqreader-c9f8-neutralized-v109-no-selfpatch-signed.apk
.tmp\qqreader-c9f8-neutralized-v110-got-tgkill-signed.apk
.tmp\qqreader-c9f8-neutralized-v111-internal-sigkill-signed.apk
.tmp\qqreader-c9f8-neutralized-v112-callsite-nop-signed.apk
```

近期关键日志：

```text
.tmp\qqreader-v107-retest-logcat.txt
.tmp\qqreader-v109-no-selfpatch-logcat.txt
.tmp\qqreader-v110-got-tgkill-logcat.txt
.tmp\qqreader-v110-suppress-sigkill-logcat.txt
.tmp\qqreader-v111-internal-sigkill-logcat.txt
```

尚未生成，因为 v112 未能上机验证：

```text
.tmp\qqreader-v112-callsite-nop-logcat.txt
.tmp\qqreader-v112-callsite-nop-crash.txt
.tmp\qqreader-v112-callsite-nop-exit-info.txt
.tmp\qqreader-v112-callsite-nop-ui.xml
```

## 9. 会话记录参考源

完整会话记录可用于补充历史，但不能替代当前本地日志。

已发现的本地会话附件：

```text
C:\Users\Administrator\.codex\attachments\9cad6ef0-4a43-4899-baa8-8de9a648d7c9\pasted-text.txt
C:\Users\Administrator\.codex\attachments\a724c60a-dc1d-4bdb-8652-e56322802d34\pasted-text.txt
C:\Users\Administrator\.codex\attachments\447d753a-2428-4b47-93dc-05b19c53c917\pasted-text.txt
C:\Users\Administrator\.codex\attachments\435a05e1-5bd4-4544-8264-077f53621bd7\pasted-text.txt
C:\Users\Administrator\.codex\attachments\4f8c6b26-9130-475e-9150-5989c0b45190\pasted-text.txt
```

使用原则：

1. 先用附件恢复历史决策和曾经试过的版本。
2. 再用仓库当前源码和 `.tmp` 当前日志确认是否仍成立。
3. 不把旧会话里的推测、临时方案、已回滚方案当成当前事实。

## 10. v112 验证步骤

手机重新打开无线调试后，先验证 v112，不要先切换方案。

### 10.1 连接设备

```powershell
$adb = 'C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe'
& $adb connect <IP:PORT>
& $adb devices
```

### 10.2 安装启动

```powershell
$adb = 'C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe'
$serial = '<IP:PORT>'
$pkg = 'com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8'
$activity = 'com.qq.reader.activity.launch.DefaultAliasSplashActivity'
$apk = '.tmp\qqreader-c9f8-neutralized-v112-callsite-nop-signed.apk'

& $adb -s $serial shell setprop debug.multiapp.jiagu.explicit_load 1
& $adb -s $serial shell setprop debug.multiapp.patch_jiagu 0
& $adb -s $serial shell setprop debug.multiapp.patch_jiagu_mode legacy
& $adb -s $serial shell setprop debug.multiapp.suppress_self_sigkill 0
& $adb -s $serial shell setprop debug.multiapp.online.run_fallback 0
& $adb -s $serial shell setprop debug.multiapp.online.failure_callback 1
& $adb -s $serial shell setprop debug.multiapp.stubapp.fallback 0

& $adb -s $serial install -r -d $apk
& $adb -s $serial shell am force-stop $pkg
& $adb -s $serial logcat -c
& $adb -s $serial shell am start -W -n "$pkg/$activity"
Start-Sleep -Seconds 45
```

### 10.3 抓证据

```powershell
& $adb -s $serial logcat -d -v threadtime > .tmp\qqreader-v112-callsite-nop-logcat.txt
& $adb -s $serial logcat -b crash -d -v threadtime > .tmp\qqreader-v112-callsite-nop-crash.txt
& $adb -s $serial shell dumpsys activity exit-info $pkg > .tmp\qqreader-v112-callsite-nop-exit-info.txt
& $adb -s $serial exec-out uiautomator dump /dev/tty > .tmp\qqreader-v112-callsite-nop-ui.xml
& $adb -s $serial shell pidof $pkg
```

### 10.4 必查关键词

```text
patch_jiagu_vip_self_kill_callsite
patch_arm64_instruction
RegisterNatives: class=com.stub.StubApp
RegisterNatives: class=com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask
interface20
nativeLoadLibraryForGuest: SUCCESS
JNI_ERR returned from JNI_OnLoad
GOT tgkill intercepted
OnlineChapterDownloadTask.run
Fock.sign
FockRT.sn
FockKeyPoolCache
UnsatisfiedLinkError
FATAL EXCEPTION
```

## 11. v112 结果判定

### 11.1 成功通过壳自杀点

期望看到：

```text
patch_jiagu_vip_self_kill_callsite: patched ...
RegisterNatives: class=com.stub.StubApp
RegisterNatives StubApp: captured original interface20
```

且不再看到：

```text
GOT tgkill intercepted ... sig=9 ... offset=0x11cb88
JNI_ERR returned from JNI_OnLoad
No implementation found for boolean com.stub.StubApp.interface20()
```

如果成立，继续验证业务功能：

1. 书城是否还显示网络异常。
2. 免费阅读是否闪退。
3. 加书架是否闪退。
4. 章节正文是否能加载出来。
5. 日志里是否还有 `OnlineChapterDownloadTask.run()` 未绑定。

### 11.2 仍然 self SIGKILL

如果仍然出现：

```text
GOT tgkill intercepted ... sig=9
```

处理：

1. 记录新的 caller offset。
2. 判断是否仍来自 `libjiagu_vip.so`。
3. 如果 offset 仍是 `0x11cb88`，说明 v112 patch 没打中或指令类型不匹配。
4. 如果 offset 改变，说明壳还有第二个自杀点，需要按新 offset 定点处理。

### 11.3 出现 JNI_ERR

如果出现：

```text
JNI_ERR returned from JNI_OnLoad
```

处理：

1. 查看 `patch_jiagu_vip_self_kill_callsite` 打印的 patch 前指令。
2. 如果不是 `BL` 或 `BLR`，说明 `0x11cb84` callsite 推断错误。
3. 需要反汇编 `libjiagu_vip.so` 的 `0x1116b4`、`0x11cb84`、`0x11cb88` 附近。

### 11.4 壳通过但章节仍不加载

如果启动不崩，但章节卡住：

```text
正在加载...
```

优先查：

```text
RegisterNatives: class=com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask
OnlineChapterDownloadTask.run
handleResult
startRequestTask
notifySucces
onDone
onFailed
```

此时不要把 `run()` no-op 当成功，应继续恢复真实业务 native。

### 11.5 壳通过但书城网络异常

优先查：

```text
Fock.sign
FockRT.sn
FockKeyPoolCache
qrencrypt
NativeBinding
request sign
network error
```

判断标准：

- 如果 fake sign 路径被使用，说明服务端签名仍可能无效。
- 如果真实 `FockRT.sn` / `Fock.sign` 路径崩溃，说明 native runtime 仍没初始化完整。
- 如果签名正常但接口失败，再查包名、证书、AppKey、设备环境校验。

## 12. 不建议继续的方向

这些只能作为诊断，不应作为最终方案：

```text
OnlineChapterDownloadTask.run -> no-op
Fock.sign -> null / empty string / diagnostic MD5
qrencrypt/FockKeyPoolCache 大面积空实现
debug.multiapp.online.run_fallback=1 作为常态
JNI_OnLoad 前全局 suppress self SIGKILL
没有记录 offset 和指令前后值的大范围 libjiagu patch
```

原因：

- 这些会把 crash 变成网络异常或内容加载失败。
- 用户目标是正常使用 QQ 阅读，不是只避免前台闪退。

## 13. 专业化后续工作流

后续每一轮都按这个格式记录：

```text
版本：
APK：
代码改动：
安装方式：
启动 props：
是否安装成功：
是否进主界面：
是否 crash：
crash 摘要：
关键 logcat 行：
UI 状态：
下一步判断：
```

每轮至少保留：

```text
.tmp\qqreader-vXXX-<name>-logcat.txt
.tmp\qqreader-vXXX-<name>-crash.txt
.tmp\qqreader-vXXX-<name>-exit-info.txt
.tmp\qqreader-vXXX-<name>-ui.xml
```

每轮结论必须区分：

- `已证实`
- `被排除`
- `未验证`
- `下一步`

## 14. 当前最短恢复路径

手机重新连接后，最短路径是：

1. 安装 v112。
2. 验证是否打印 `patch_jiagu_vip_self_kill_callsite`。
3. 验证是否还有 `GOT tgkill intercepted sig=9`。
4. 验证 `StubApp.interface20` 是否真实绑定。
5. 如果壳通过，再验证书城和章节。
6. 根据日志决定回到 `OnlineChapterDownloadTask` 还是 `Fock/qrencrypt`。

当前不要先清理 `.tmp`，这些日志和 APK 是继续排查的证据。

