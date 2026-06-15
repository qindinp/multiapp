# QQ 阅读逆向执行方案

日期：2026-06-13

## 目标

目标不是只让 QQ 阅读分身进程活着，而是让离线 clone 路线下的 QQ 阅读可以正常使用：

```text
启动、书城、搜索、免费阅读、加书架、章节正文加载、账号/书架基础功能
```

当前主阻塞不是普通前台启动，而是 `libjiagu_vip.so` 加固壳注册链和章节下载 native 绑定没有完整恢复。

## 当前证据

最强 native 证据：

```text
RegisterNatives: class=com.stub.StubApp count=10
caller=.../libjiagu_vip.so offset=0x1116b4
RegisterNatives StubApp: captured original interface11=...
RegisterNatives StubApp: captured original interface20=...
```

随后壳主动自杀：

```text
GOT tgkill intercepted ... sig=9
caller=.../libjiagu_vip.so offset=0x11cb88
```

v131 已证明 callsite 计算成立：

```text
return address = 0x11cb88
callsite = 0x11cb84
prev=0x52800120
insn=0x97ffb823
next=0x140000e8
patched direct BL
```

所以第一阶段要恢复原始 `libjiagu_vip.so` 注册路径，并只处理这个已验证 self-kill 点。

## 不再重复的方向

以下只能作为诊断，不能作为最终修复：

```text
debug.multiapp.jiagu.prehook_dlopen=1
提前 dlopenOnly(libjiagu_vip.so)
提前 NativeLibLoader.loadLibrary("jiagu_vip")
JNI_OnLoad 前全局 suppress SIGKILL
OnlineChapterDownloadTask.run no-op
复制 *_ALL_o 到 .eqct
fake Fock.sign / diagnostic MD5 常态化
ChapBatAuthWithPD direct fetch 当最终方案
```

这些路线已经把 crash 转成过网络异常、空章节文件或 `chapter data load failed`。

## Gate 1：壳 native 边界

通过标准：

```text
RegisterNatives: class=com.stub.StubApp count=10
caller=.../libjiagu_vip.so offset=0x1116b4
captured original interface11
captured original interface20
```

失败标准：

```text
只有 RegisterNatives ... count=4 ... libmultiapp-native.so
JNI_ERR returned from JNI_OnLoad
JNI_OnLoad failed on a previous attempt
```

Gate 1 不通过时，不测章节加载。

## Gate 2：self-kill callsite

通过标准满足其一即可：

```text
GOT tgkill intercepted ... sig=9 ... offset=0x11cb88
patch_jiagu_self_kill_from_return_address: patched caller-4
stub_interface20 forwarding original=...
stub_interface20 original result=1
```

或：

```text
不再出现来自 libjiagu_vip.so offset=0x11cb88 的 tgkill
StubApp/interface20 后续继续执行
```

失败标准：

```text
tgkill 仍进入真实 syscall
caller 不属于 libjiagu_vip.so
caller-4 不在 executable mapping
caller-4 不是 BL/BLR
```

现场 patch 必须满足全部安全条件：

```text
sig == SIGKILL
tgid == getpid()
tid == current thread 或 getpid()
caller 属于 libjiagu_vip.so
caller offset == 0x11cb88
caller-4 位于 executable mapping
caller-4 指令匹配 BL 或 BLR
```

不满足这些条件时放行，不吞信号。

## Gate 3：章节 native 绑定

运行属性：

```text
debug.multiapp.online.state_fallback=1
debug.multiapp.online.run_fallback=0
```

通过标准：

```text
不再出现 OnlineChapterDownloadTask.run 的 UnsatisfiedLinkError
免费章节正文实际可读
```

失败标准：

```text
No implementation found for void
com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask.run()
```

`state_fallback=1` 只是调度支架；`run_fallback=1` 只能诊断，不能作为成功标准。

## Gate 4：网络与签名链

通过标准：

```text
FockRT.sn / Fock.sign 走真实 native 路径
书城、搜索、免费阅读没有网络签名异常
```

失败标准：

```text
使用 diagnostic MD5 fallback
fileUrl/resourceUrl 为空
*_s size=0
chapter data load failed
书城网络异常
```

## 执行顺序

1. 固定 `explicit_load=0`、`prehook_dlopen=0`。
2. 让原始 `StubApp.load()` 成为第一次 `libjiagu_vip.so` 加载入口。
3. 在 `tgkill` 现场命中 `libjiagu_vip.so offset=0x11cb88` 时，校验 `caller - 4` 并 patch `BL/BLR -> NOP`，只抑制这一次 self SIGKILL。
4. 打印映射诊断：

```text
dladdr base
r-xp mapping start/end
caller address
callsite address
caller/callsite 所在 maps 行
patch 前后 3 条指令
```

5. Gate 1/2 通过后，再验证 `OnlineChapterDownloadTask.run()` 是否真实注册。
6. 只有 Gate 1/2 多轮失败后，才启用 Java/Kotlin 等价 worker 备选方案。该 worker 必须完整处理：

```text
真实 task 字段
真实 Fock/qrencrypt 签名链
ReadOnline.search(...)
有效章节文件落盘
qdaf.getBookSucces(...)
qdaf.getBookFailed(...)
qdaf.getBookNeedVIPOrPay(...)
```

不能只回调 success 或伪造正文文件。

## 验证命令

代码变更后构建：

```powershell
.\tools\qqreader-offline-patch\build-qqreader-offline.ps1 `
  -VersionTag v147-tgkill-live-callsite-patch `
  -Build `
  -ForceExtract `
  -ForceRepack
```

安装并抓日志：

```powershell
.\tools\qqreader-offline-patch\test-qqreader-offline.ps1 `
  -Connect <IP:PORT> `
  -VersionTag v147-tgkill-live-callsite-patch `
  -WaitSeconds 45
```

关键 grep：

```powershell
rg -n "RegisterNatives: class=com.stub.StubApp|captured original interface11|captured original interface20|tgkill|patch_jiagu_self_kill_from_return_address|patch_jiagu_vip_self_kill_callsite|stub_interface20|OnlineChapterDownloadTask.run|No implementation found|JNI_ERR|previous attempt" .tmp\qqreader-v147-tgkill-live-callsite-patch-*.txt
```
