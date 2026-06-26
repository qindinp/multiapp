# libjiagu_vip.so 逆向分析报告

## 分析日期
2026-06-22

## 分析目标
找出 `ywguid`/`ykkey` 的生成逻辑，解决 QQ 阅读分身登录问题。

## 分析工具
- radare2 6.1.6 (D:\360Downloads\radare2-6.1.6-w32)
- xxd (十六进制查看)
- grep (字符串提取)
- 自定义 dump 代码（native-hook.cpp）

## 文件信息
- **路径**: `.tmp/jiagu-extract/lib/arm64-v8a/libjiagu_vip.so`
- **类型**: ELF 64-bit LSB shared object, ARM aarch64
- **大小**: 869KB (889192 bytes)
- **状态**: stripped, 加密/混淆

## 分析方法

### Phase 1: 静态分析

#### 1.1 字符串提取
```bash
grep -aoE '[\x20-\x7e]{4,}' libjiagu_vip.so | sort -u
```

**结果** (691 个字符串):
- `/proc/self/maps` — 壳读取 maps 做环境检测
- `android/app/ActivityThread` — 壳访问 ActivityThread
- `android/content/pm/PackageInfo` — 壳检查包信息
- `android/content/pm/Signature` — 壳检查签名
- `java/security/MessageDigest` — 壳用 MessageDigest 做哈希/签名
- `currentApplication` — 壳获取当前 Application
- `getSystemContext` — 壳获取系统 Context
- `mBoundApplication` — 壳访问 mBoundApplication 字段
- `JIAGU_ENCRYPTED_DEX_NAME` — DEX 加密相关
- `RMUTGF_KEY` — 某个密钥（可能用于加密）
- `libijmDataEncryption` — 数据加密库
- `getPackageName` — 壳获取包名
- `getProperty` — 壳获取系统属性
- `JNI_OnLoad` — JNI 入口点
- `JNI_OoLoad` — 诱饵函数
- `DynCryptor` — 动态解密器类

#### 1.2 导入函数分析
```bash
r2 -q -c "ii" libjiagu_vip.so
```

**安全相关导入**:
| 函数 | 用途 |
|------|------|
| `kill`, `syscall`, `prctl` | 进程控制 |
| `mmap`, `munmap` | 内存管理 |
| `open`, `read`, `write`, `fopen` | 文件 I/O |
| `dlopen`, `dlsym`, `dl_iterate_phdr` | 动态链接 |
| `getpid`, `getenv` | 进程信息 |
| `pthread_create`, `pthread_mutex_*` | 线程 |
| `abort`, `exit` | 进程终止 |

#### 1.3 导出函数分析
```bash
r2 -q -c "iE" libjiagu_vip.so
```

**关键导出函数**:
| 函数 | 地址 | 用途 |
|------|------|------|
| `JNI_OnLoad` | 0x258a38 | JNI 入口（152 字节 wrapper） |
| `JNI_OoLoad` | 0x8fe0 | 诱饵函数 |
| `DynCryptor::__arm_c_0()` | 0x6f60 | 动态解密器 |
| `__arm_a_1(JavaVM*, JNIEnv*, void*, int&)` | 0x9f6c | JNI 相关 |
| `__arm_a_20()` | 0x7730 | 内部函数 |
| `__arm_a_21()` | 0x7674 | 内部函数 |

#### 1.4 ELF 结构分析
```bash
r2 -q -c "iSS" libjiagu_vip.so
```

**内存布局**:
| 段 | 地址 | 大小 | 权限 | 说明 |
|---|------|------|------|------|
| LOAD0 | 0x0000-0x1c910 | 114KB | r-x | 代码段 |
| LOAD1 | 0x2d790-0x257000 | 2.2MB | rw- | 数据/BSS（0xae10a 文件 + 0x17b056 BSS） |
| LOAD2 | 0x257000-0x25f4f8 | 33KB | r-x | 附加代码 |
| LOAD3 | 0x26fc20-0x270000 | 1.1KB | rw- | 数据 |
| LOAD4 | 0x2710a0-0x274038 | 12KB | rw- | 数据 |

**关键发现**:
- BSS 段巨大（0x17b056 字节）— 壳在运行时解密代码到这个区域
- `JNI_OnLoad` 很小（152 字节）— 只是 wrapper，真实初始化在 0x25861c
- `interface11` 地址 0x11fe2c 在文件中全为零 — 属于 BSS/解密区

### Phase 2: 运行时分析

#### 2.1 运行时 dump 方法

在 `native-hook.cpp` 中添加 dump 函数：

```cpp
static void dump_decrypted_jiagu_code() {
    dl_iterate_phdr([](struct dl_phdr_info* info, size_t size, void* data) -> int {
        if (info->dlpi_name == nullptr || strstr(info->dlpi_name, "libjiagu_vip.so") == nullptr) {
            return 0;
        }
        uintptr_t base = info->dlpi_addr;
        LOGI("dump_decrypted: libjiagu_vip.so base=%p", (void*)base);

        // 关键函数地址
        struct { uintptr_t offset; const char* name; } targets[] = {
            {0x11cb84, "self-kill-callsite"},
            {0x1116b4, "RegisterNatives-caller"},
            {0x11fe2c, "interface11"},
            {0x10d3f4, "interface20"},
            {0x112820, "interface5"},
            {0x114bd4, "interface21"},
            {0x258a38, "JNI_OnLoad"},
            {0x25861c, "init-function"},
        };

        for (int i = 0; i < 8; i++) {
            uintptr_t addr = base + targets[i].offset;
            uint32_t insns[4];
            memcpy(insns, (void*)addr, sizeof(insns));
            LOGI("dump_decrypted: %s offset=0x%lx insn=[0x%08x, 0x%08x, 0x%08x, 0x%08x]",
                 targets[i].name, (unsigned long)targets[i].offset,
                 insns[0], insns[1], insns[2], insns[3]);
        }

        // dump interface11 函数体（前 256 字节）
        uintptr_t i11_addr = base + 0x11fe2c;
        LOGI("dump_decrypted: interface11 body start");
        for (int off = 0; off < 256; off += 16) {
            uint32_t* p = (uint32_t*)(i11_addr + off);
            LOGI("dump_decrypted: interface11+0x%02x: %08x %08x %08x %08x",
                 off, p[0], p[1], p[2], p[3]);
        }

        return 1;
    }, nullptr);
}
```

**调用点**:
1. `hooked_nativeLoad()` — 壳的 JNI_OnLoad 执行后
2. `nativeGotHookLibrary()` — GOT hook 安装后
3. `got_hook_immediate()` — 立即 hook 后

#### 2.2 成功 dump 结果

**设备**: 192.168.2.69:34309
**APK**: v227-dump-v3
**运行次数**: 第 3 次成功（~33% 成功率）

**解密后的关键地址**:
```
base=0x74acea2000

self-kill-callsite (0x11cb84): [0xd503201f, 0x140000e8, 0xf9400660, 0x940177e6]
  → 0xd503201f = NOP (已 patch)
  → 0x140000e8 = B +0x3A0 (无条件跳转)

RegisterNatives-caller (0x1116b4): [0x37f80100, 0x320003f5, 0x1400000b, 0xf9404508]
  → 0x37f80100 = TBNZ W0, #0, ... (条件分支)

interface11 (0x11fe2c): [0xa9ba6ffc, 0xa90167fa, 0xa9025ff8, 0xa90357f6]
  → 函数序言 (保存寄存器)

interface20 (0x10d3f4): [0xf81e0ff4, 0xa9017bf3, 0xaa0003f4, 0x321c03e0]
  → 函数序言

interface5 (0x112820): [0xd10483ff, 0xa90c6ffc, 0xa90d67fa, 0xa90e5ff8]
  → 函数序言

interface21 (0x114bd4): [0xd10383ff, 0xf9004bfa, 0xa90a63f9, 0xa90b5bf7]
  → 函数序言

JNI_OnLoad (0x258a38): [0xd10103ff, 0x52800082, 0x72a00022, 0xa90053f3]
  → SUB SP, SP, #0x40; MOV W2, #4; MOVK W2, #1, LSL #16; STP X19, X20, [SP]

init-function (0x25861c): [0xd10103ff, 0x52800024, 0xa9015bf5, 0x900000d6]
  → SUB SP, SP, #0x40; MOV W4, #1; STP X21, X22, [SP, #16]; ADRP X22, ...
```

### Phase 3: interface11 反汇编分析

#### 3.1 完整反汇编

```asm
; interface11 函数序言
0x00: STP X28, X27, [SP, #-96]!    ; 保存寄存器
0x04: STP X26, X25, [SP, #16]
0x08: STP X24, X23, [SP, #32]
0x0c: STP X22, X21, [SP, #48]
0x10: STP X20, X19, [SP, #64]
0x14: STP X29, X30, [SP, #80]
0x18: ADD X29, SP, #0x50           ; 帧指针
0x1c: SUB SP, SP, #0x90            ; 栈分配

; 保存参数（int value = 59494）
0x20: STUR W2, [X29, #-0x68]      ; 保存参数值

; 线程本地存储访问
0x24: MRS X8, TPIDR_EL0           ; 获取线程 ID
0x28: STR X8, [X29, #-0xd0]
0x2c: LDR X8, [X8, #0x28]        ; 加载线程本地值

; 间接跳转（分发机制）
0x34: ADRP X8, #0x1290000        ; 加载地址
0x38: LDR X8, [X8, #0x520]      ; 从 GOT/数据加载函数指针
0x40: BR X8                      ; 跳转到加载的地址 ← 关键分发点！

; 函数调用
0x44: BL #0x59a28                ; 调用函数（实际实现）
0x4c: BL #0x59ab0                ; 调用另一个函数
```

#### 3.2 关键发现

1. **`interface11` 使用间接跳转分发** — `BR X8` 跳转到从 GOT 加载的函数指针
2. **参数值 59494 决定分发目标** — 壳根据 token 值选择不同的处理函数
3. **实际实现在 `0x179898`** — `BL #0x59a28` 调用的函数
4. **`self-kill-callsite` 已被 NOP** — `0xd503201f` = NOP 指令

#### 3.3 调用目标计算

```
interface11 地址: 0x11fe2c
BL 指令偏移: 0x44
BL 目标偏移: 0x59a28

目标地址 = (0x11fe2c + 0x44) + (0x59a28 << 2)
         = 0x11fe70 + 0x1668A0
         = 0x286710
```

**注意**: BL 指令的偏移是相对于 PC 的，需要左移 2 位（乘以 4）。

### Phase 4: 壳的环境检测

#### 4.1 已知检测项

| 检测项 | 我们的处理 | 状态 |
|--------|-----------|------|
| `/proc/self/cmdline` | `spoofProcSelf` | ✅ 已伪装 |
| `/proc/self/maps` | GOT hook 过滤 | ✅ 已过滤 |
| APK 完整性 | 路径重定向 | ✅ 已重定向 |
| `/proc/self/exe` | 返回 `/system/bin/app_process64` | ✅ 已伪装 |
| `/proc/self/status` | TracerPid=0 | ✅ 已伪装 |

#### 4.2 竞态条件分析

**成功运行 (pid 6848)**:
```
self-kill-callsite: prev=0x52800120 insn=0xd503201f
  → 代码已解密，NOP 已应用
RegisterNatives: class=com.stub.StubApp count=10
  → 壳成功注册 10 个方法
```

**失败运行 (pid 28887)**:
```
self-kill-callsite: prev=0x00000000 insn=0x00000000
  → 代码未解密
RegisterNatives: class=com.stub.StubApp count=4 (fallback)
  → 壳未注册，使用 fallback
```

**结论**: 竞态条件导致 ~33% 成功率。壳的解密过程有时间依赖或随机因素。

### Phase 5: 壳的 RegisterNatives 行为

#### 5.1 壳注册的方法（成功时）

```
RegisterNatives: class=com.stub.StubApp count=10
  [0] interface14 (I)Ljava/lang/String;
  [1] mark ()V
  [2] interface5 (Landroid/app/Application;)V
  [3] interface11 (I)V
  [4] interface20 ()Z
  [5] interface21 (Landroid/app/Application;)V
  [6] interface7 (Landroid/app/Application;Landroid/content/Context;)Z
  [7] interface8 (Landroid/app/Application;Landroid/content/Context;)Z
  [8] interface22 (I[Ljava/lang/String;[I)V
  [9] interface24 (Landroid/app/Activity;[Ljava/lang/String;I)V
```

#### 5.2 壳不注册的方法

壳**不注册** `YWLoginManager.pwdLogin/sendPhoneCode/qrCodeV2`。这些方法需要通过 `interface11(59494)` 触发注册。

#### 5.3 interface11(59494) 行为

调用 `interface11(59494)` 后：
- 壳的 native 代码执行
- 但**不注册 YWLogin 方法**
- 原因：壳的实现中可能有额外的条件检查

### Phase 6: 与 QQ 阅读登录的关系

#### 6.1 登录流程

```
YWLoginManager.<clinit>
  → StubApp.interface11(59494)
  → 壳内部加密表查找
  → RegisterNatives 注册 pwdLogin/sendPhoneCode/qrCodeV2
```

#### 6.2 当前状态

- ✅ 免费章节阅读可用
- ❌ 登录不可用（`ywguid`/`ykkey` 缺失）
- ❌ `interface11(59494)` 不注册 YWLogin
- ❌ 服务端返回 `apiCode=3`

#### 6.3 已尝试的方案

| 方案 | 结果 |
|------|------|
| 手动调用 `interface11(59494)` | 执行但不注册 |
| `Class.forName` 触发 `<clinit>` | 执行但不注册 |
| `qdad.b().d()` 获取 ywguid | 返回空 |
| Java fallback 发送 `/sdk/staticlogin` | `apiCode=3` |

### Phase 7: 下一步方向

#### 7.1 短期（可立即执行）

1. **继续分析解密后的代码** — dump `0x179898` 处的函数（`interface11` 的实际实现）
2. **分析 `BR X8` 的目标** — 找出分发表和 token 59494 对应的处理函数
3. **对比成功/失败运行** — 找出竞态条件的具体原因

#### 7.2 中期（需要更多工作）

1. **WebView 登录** — 不依赖壳的 native 代码
2. **修复竞态条件** — 提高 `JNI_OnLoad` 成功率
3. **分析 `RMUTGF_KEY`** — 可能是 `ywguid`/`ykkey` 的生成密钥

#### 7.3 长期（深度逆向）

1. **完整反编译壳的代码** — 使用 radare2 反汇编所有解密后的函数
2. **分析 `DynCryptor` 类** — 了解解密机制
3. **分析 `libijmDataEncryption`** — 了解数据加密逻辑

## 关键文件

| 文件 | 用途 |
|------|------|
| `core/hook/src/main/cpp/native-hook.cpp` | dump 函数、GOT hook、RegisterNatives 拦截 |
| `core/hook/src/main/java/.../JiaguRuntime.kt` | 壳加载逻辑、spoofProcSelf |
| `core/hook/src/main/java/.../NativeHookBridge.kt` | Java 到 native 的桥 |
| `docs/qqreader-offline-patch.md` | 完整版本历史和证据 |
| `.tmp/dump-v227-success-3.txt` | 成功的 dump 数据 |

## 命令参考

### 反编译 QDReaderHook
```bash
"D:/360Downloads/jadx-1.5.5/bin/jadx" -d ".tmp/qdreaderhook-decompiled" "tmp_apks/cn.xihan.qdds/QDReaderHook_3.3.6.apk"
```

### T2 解码
```bash
cd /tmp/t2classes && java -cp ".;<dexlib2.jar>;<guava.jar>" -Dfile.encoding=UTF-8 DecodeT2Strings "<dex-path>" "<filter>"
```

### radare2 分析
```bash
"D:/360Downloads/radare2-6.1.6-w32/radare2-6.1.6-w32/bin/radare2.exe" -q -c "s 0x00258a38; pd 50" libjiagu_vip.so
```

### 运行时 dump
```bash
# 安装 APK
adb install -r -d qqreader-c9f8-neutralized-v227-dump-v3-signed.apk

# 启动并抓取 dump
adb shell am force-stop com.qq.reader.clonestub_...
adb logcat -c
adb shell am start -W -n "com.qq.reader.clonestub_.../com.qq.reader.activity.launch.DefaultAliasSplashActivity"
sleep 10
adb logcat -d | grep "dump_decrypted" > dump.txt
```

## 总结

### 已完成
- ✅ 静态分析：字符串、导入函数、导出函数、ELF 结构
- ✅ 运行时 dump：成功获取解密后的代码
- ✅ interface11 反汇编：发现间接跳转分发机制
- ✅ 环境检测分析：确认竞态条件导致 ~33% 成功率
- ✅ 初始化流程分析：完整追踪 JNI_OnLoad → init → 环境检查 → 解密 → 注册

### 未完成
- ❌ 找出 `ywguid`/`ykkey` 的生成逻辑
- ❌ 修复竞态条件（`dl_iterate_phdr` hook 无效，~30% 成功率）
- ❌ 实现 WebView 登录

### 关键发现
1. 壳的代码在运行时解密，文件中全为零
2. `interface11` 使用间接跳转分发，参数值决定处理函数
3. 壳的 `JNI_OnLoad` 有竞态条件，~33% 成功率
4. 壳不注册 YWLogin 方法，需要通过 `interface11(59494)` 触发
5. `interface11(59494)` 执行但不注册，原因待查
6. 初始化流程：JNI_OnLoad → GetEnv → init-function → 环境检查 → mprotect → 解密 → RegisterNatives
7. 环境检查函数 (0x25bde4) 通过 JNI 调用检查环境，失败返回 JNI_ERR
8. 环境检查内容：ActivityThread、Build.VERSION.SDK_INT、mBoundApplication、currentPackageName、getSystemContext
9. 解密函数 (0x25a5dc) 使用内存分配和数据复制解密代码
10. 壳检查 SDK_INT > 17 (Android 4.2) 走不同路径
11. `interface11` 通过 `BR X8` 间接跳转到 GOT 函数指针（地址 0x1290520），该指针在 BSS/解密区
12. `interface11` 的 BL 调用目标在 0x286710 和 0x286938，也在 BSS/解密区
13. 壳的 `interface11` 不使用 `FindClass`，而是通过内部加密表分发
14. 注册函数 (0x25a7ac) 使用 base64 类解码和查表机制
15. `__arm_a_20` (0x7730) 和 `__arm_a_21` (0x7674) 是内部工具函数，使用栈保护
16. `DynCryptor::__arm_c_0()` (0x6f60) 是动态解密器，使用 XOR 和查表解密
 
## 2026-06-22 v245 runtime range dump 对比

### 目标

不再做启动成功率统计，直接对比一次成功条件和一次失败条件，并 dump 运行时内存。

### 产物

- 成功日志：`.tmp/qqreader-v243-loader-dlopen-ext-multirun-run8-logcat.txt`
- 成功关键行：`.tmp/qqreader-v243-success-run8-keylines.txt`
- 失败日志：`.tmp/qqreader-v245-external-rangedump-run1-start-logcat.txt`
- 失败 runtime dump：`.tmp/v245-run1-dump/`
- 失败 BSS dump：`.tmp/v245-run1-dump/jiagu-runtime-11-rel_000dc000-00257000-rw-p.bin`
- 失败 maps：`.tmp/v245-run1-dump/jiagu-runtime-maps.txt`

### 成功条件

成功 run8 在 `libjiagu_vip.so` load 后约 9ms 触发原壳注册：

```text
RegisterNatives: class=com.stub.StubApp count=10 caller=...libjiagu_vip.so offset=0x1116b4
RegisterNatives StubApp DIAG result=0 calls=1 jiaguCalls=1 jiaguComplete=1 multiappCalls=0
loadPackerLibrary: StubApp binding after load: interface5=bound interface11=bound interface20=bound interface21=bound originalJiaguComplete=1
```

成功时解密区已有真实 AArch64 指令，并且壳自杀调用点处在可执行匿名 BSS：

```text
self-kill-callsite offset=0x11cb84 insn=[0xd503201f, 0x140000e8, 0xf9400660, 0x940177e6]
RegisterNatives-caller offset=0x1116b4 insn=[0x37f80100, 0x320003f5, 0x1400000b, 0xf9404508]
interface11 offset=0x11fe2c insn=[0xa9ba6ffc, 0xa90167fa, 0xa9025ff8, 0xa90357f6]
interface20 offset=0x10d3f4 insn=[0xf81e0ff4, 0xa9017bf3, 0xaa0003f4, 0x321c03e0]
caller_map line=... r-xp ... [anon:.bss]
```

### 失败条件

失败 run1 中 `libjiagu_vip.so` 也加载成功，`StubApp.load()` 也返回 OK，但原壳没有注册：

```text
loadPackerLibrary: StubApp binding after load: interface5=missing interface11=missing interface20=missing interface21=missing
stubRegCalls=0 jiaguRegCalls=0 jiaguCompleteCalls=0 originalJiaguComplete=0
```

失败时 fallback 只注册了 multiapp 的 4 个 stub，不是原壳注册：

```text
RegisterNatives: class=com.stub.StubApp count=4 caller=...libmultiapp-native.so
RegisterNatives StubApp DIAG result=0 calls=1 jiaguCalls=0 jiaguComplete=0 multiappCalls=1
```

### runtime dump 结论

失败 dump 的 maps 显示关键偏移全部落在同一个匿名 BSS range：

```text
base=0x74b18de000
target interface20 offset=0x10d3f4 mapped=1 perms=rw-p line=... [anon:.bss]
target RegisterNatives-caller offset=0x1116b4 mapped=1 perms=rw-p line=... [anon:.bss]
target self-kill-callsite offset=0x11cb84 mapped=1 perms=rw-p line=... [anon:.bss]
target interface11 offset=0x11fe2c mapped=1 perms=rw-p line=... [anon:.bss]
```

该 range 对应文件：

```text
jiagu-runtime-11-rel_000dc000-00257000-rw-p.bin
```

按相对基址 `0xdc000` 换算后，失败态关键位置全为 0：

```text
interface20 offset=0x10d3f4 fileOff=0x313f4 bytes=00 ... 00
RegisterNatives offset=0x1116b4 fileOff=0x356b4 bytes=00 ... 00
selfkill offset=0x11cb84 fileOff=0x40b84 bytes=00 ... 00
interface11 offset=0x11fe2c fileOff=0x43e2c bytes=00 ... 00
```

radare2 反汇编这些地址均为 invalid，因为 4-byte instruction 全是 `0x00000000`。

### 原因判断

失败不是因为 `libjiagu_vip.so` 没加载，也不是因为 `RegisterNatives` logger 没装上。失败时静态段仍可读且正常：

```text
JNI_OnLoad offset=0x258a38
env-check offset=0x25bde4
decrypt-func offset=0x25a5dc
register-func offset=0x25a7ac
```

真正分界是：壳是否完成从静态初始化路径进入 BSS 解密/权限切换/原始 `RegisterNatives` 这一步。

- 成功：`[anon:.bss]` 被填入代码，权限变为 `r-xp`，随后 `0x1116b4` 调用 `RegisterNatives count=10`。
- 失败：同一逻辑区域保持 `rw-p` 且全 0，`0x1116b4/interface11/interface20/self-kill` 都没有代码，因此不会触发原壳注册。

下一步应聚焦 `0x25bde4 env-check` 及其调用链（`0x25b508`、`0x25ba74`、`0x258bac`），记录它在失败时为什么没有继续驱动 `0x25a5dc decrypt-func` 填充 BSS。优先做有条件 JNI 诊断或直接离线 patch env-check 返回成功，验证是否能让 BSS 解密区稳定填充。
## 2026-06-22 v246/v247 Jiagu JNI 诊断

### 新增诊断

`v246-jiagu-jni-diag` 在 `libjiagu_vip.so` 初始化窗口安装了低噪声 JNI 表 hook，限定 caller 来自这些 offset 附近：

```text
0x25b508 env-check-a
0x25ba74 env-check-b
0x25bde4 env-check
0x258bac onload-dispatch
0x25a5dc decrypt-func
0x25a7ac register-func
```

已验证安装成功：

```text
installJiaguJniDiagHooks: installed=1
PackerRuntime.Jiagu: prepareFiles: Jiagu JNI diag hooks installed: true
```

### v246 失败路径新证据

`v246` 仍然没有出现原壳注册：

```text
loadPackerLibrary: StubApp binding after load:
interface5=missing interface11=missing interface20=missing interface21=missing
stubRegCalls=0 jiaguRegCalls=0 jiaguCompleteCalls=0 originalJiaguComplete=0

RegisterNatives: class=com.stub.StubApp count=4 caller=...libmultiapp-native.so
```

但 `env-check` 的 JNI 访问链已经明确：

```text
FindClass android/app/ActivityThread
GetStaticMethodID ActivityThread.currentActivityThread()
FindClass android/os/Build$VERSION
GetStaticFieldID Build$VERSION.SDK_INT
GetStaticMethodID ActivityThread.currentPackageName()
GetMethodID ActivityThread.getSystemContext()
GetMethodID ContextImpl.getPackageManager()
GetMethodID ApplicationPackageManager.getPackageInfo(Ljava/lang/String;I)
GetFieldID PackageInfo.signatures
GetMethodID Signature.toByteArray()
```

这些 JNI lookup/ExceptionCheck 全部 `exception=0`，所以失败不是类/方法找不到，而是后续 Java 返回值或签名字节比较没有通过。

### 静态分支对应关系

`0x25bde4 env-check` 的本地反汇编显示：

```text
0x25be08 BL 0x25b508
0x25be1c BL 0x25ba74
0x25bea0 BL 0x258bac
0x25beb0 BL 0x258bac
```

其中 `0x25b508` 负责 ActivityThread/SDK/packageName/systemContext 相关读取，`0x25ba74` 负责 PackageManager/PackageInfo/signatures/Signature.toByteArray 相关读取。失败时 `0x25bde4` 最终走返回 `1` 的失败出口，BSS 解密区继续保持 `rw-p` 且关键 offset 全 0。

### v247 状态

`v247-jiagu-jni-callav` 已补 `Call*MethodA/V` 诊断并构建/打包成功：

```text
.tmp/qqreader-c9f8-neutralized-v247-jiagu-jni-callav-signed.apk
```

设备切到 `192.168.2.95:37631` 后，`v247` run1 命中成功路径，日志：

```text
.tmp/qqreader-v247-jiagu-jni-callav-run1-start-logcat.txt
```

关键 JNI 返回值已确认：

```text
JiaguJNI CallStaticObjectMethodV ... ActivityThread.currentPackageName()
  static result="com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8" exception=0

JiaguJNI CallObjectMethodV ... ApplicationPackageManager.getPackageInfo(Ljava/lang/String;I)
  result=0x782a44f059 exception=0

JiaguJNI CallObjectMethodV ... Signature.toByteArray()[B
  result=0x782a44f06d exception=0
```

随后原壳完成注册：

```text
RegisterNatives: class=com.stub.StubApp count=10 caller=...libjiagu_vip.so offset=0x1116b4
RegisterNatives StubApp DIAG result=0 calls=1 jiaguCalls=1 jiaguComplete=1 multiappCalls=0

loadPackerLibrary: StubApp binding after load:
interface5=bound interface11=bound interface20=bound interface21=bound
originalJiaguComplete=1
```

自杀点也命中成功态的解密 BSS，并被运行时 patch：

```text
GOT tgkill intercepted caller=...libjiagu_vip.so offset=0x11cb88
patch_jiagu_self_kill_from_return_address: caller_map found=1 exec=1 line=... [anon:.bss]
patch_jiagu_self_kill_from_return_address: patched caller-4 ... before=0x97ffb823 after=0xd503201f
```

### v247 成功 dump

外置 dump 目录未清空时会同时保留旧失败文件和新成功文件，不能只看 `jiagu-runtime-maps.txt`。本次成功态应以 v247 日志写出的新文件为准：

```text
.tmp/v247-run1-success-dump/jiagu-runtime-04-rel_000dc000-00235000-r-xp.bin
SHA256 F817DF3E73964D901BCDC16F4435A311B11AAC3974E10B079DFD03B4EEB55AB4
```

该文件在关键 offset 上已是有效 AArch64 指令，而旧失败文件 `jiagu-runtime-11-rel_000dc000-00257000-rw-p.bin` 同 offset 仍全 0。

```text
interface20      0x10d3f4 bytes=f4 0f 1e f8 f3 7b 01 a9 f4 03 00 aa e0 03 1c 32
RegisterNatives 0x1116b4 bytes=00 01 f8 37 f5 03 00 32 0b 00 00 14 08 45 40 f9
selfkill        0x11cb84 bytes=1f 20 03 d5 e8 00 00 14 60 06 40 f9 e6 77 01 94
interface11     0x11fe2c bytes=fc 6f ba a9 fa 67 01 a9 f8 5f 02 a9 f6 57 03 a9
```

raw 反汇编确认：

```text
0x10d3f4 str x20, [sp, -0x20]!
0x1116b4 tbnz w0, 0x1f, 0x1116d4
0x11cb84 nop
0x11fe2c stp x28, x27, [sp, -0x60]!
```

### 旧当前结论（v256 已修正）

此前把 `currentPackageName()` 返回 clone 包名的成功样本当成可接受状态；`v256` 已确认这不是稳定成功条件。真正分界是 `0x25bde4 env-check` 是否在摘要/解密前看到原版包名 `com.qq.reader`。下面的 v253-v256 更新为最新结论。

## 2026-06-22 v253-v256 原版包名校验更新

`v253` 新增 JNI 原语诊断后，确认失败点不在 JNI 对象获取本身：

```text
currentPackageName() -> "com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8"
GetStringUTFChars ... preview="com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8"
GetStringUTFLength ... result=56
GetByteArrayElements ... result=non-null
GetArrayLength ... result=744
```

这说明 `ActivityThread.currentPackageName()`、`getPackageInfo()`、`Signature.toByteArray()` 都能返回对象，失败是后续 `0x25bde4 env-check` 内部摘要/解密条件不匹配。静态反汇编中，包名字符串和签名字节会在 `0x25bea0`、`0x25beb0` 两次进入 `0x258bac`，疑似参与摘要/密钥派生。

`v254` 曾把原版包名 spoof 放在 `LoaderFactory.preloadPackerLibViaGuestClassLoader()`，但实测未生效，因为 `PackerRuntime.Jiagu.prepareFiles/loadPackerLibrary` 已经更早触发壳 `JNI_OnLoad`。日志仍显示：

```text
JiaguJNI CallStaticObjectMethodV ... currentPackageName() ... result="com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8"
RegisterNatives: class=com.stub.StubApp count=4 caller=...libmultiapp-native.so
dump_decrypted: RegisterNatives-caller offset=0x1116b4 insn=[0x00000000, ...]
```

`v256` 将 `setJiaguPackageSpoof(stubPkg, originalPkg)` 前移到 `JiaguRuntime.prepareFiles()` 中，位于 `initNativeHooks()` 之后、`StubApp.load()`/壳 `JNI_OnLoad` 之前，实测恢复原壳注册：

```text
nativeSetJiaguPackageSpoof: stub=com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8 original=com.qq.reader
JiaguJNI currentPackageName spoof ... spoof="com.qq.reader"
JiaguJNI CallStaticObjectMethodV ... currentPackageName() ... result="com.qq.reader"
GetStringUTFChars ... preview="com.qq.reader"
GetStringUTFLength ... result=13
GetArrayLength ... result=599
RegisterNatives: class=com.stub.StubApp count=10 caller=...libjiagu_vip.so offset=0x1116b4
originalJiaguComplete=1 sawJiaguInterface11=1 sawJiaguInterface20=1
```

BSS 解密区也从全 0 变为有效 AArch64 指令：

```text
dump_decrypted: RegisterNatives-caller offset=0x1116b4 insn=[0x37f80100, 0x320003f5, 0x1400000b, 0xf9404508]
dump_decrypted: interface11 offset=0x11fe2c insn=[0xa9ba6ffc, 0xa90167fa, 0xa9025ff8, 0xa90357f6]
dump_decrypted: interface20 offset=0x10d3f4 insn=[0xf81e0ff4, 0xa9017bf3, 0xaa0003f4, 0x321c03e0]
```

因此当前成功/失败分界已确定：外层 clone 包名仍必须保留用于安装共存；但壳 native `env-check` 里的 `ActivityThread.currentPackageName()` 必须看到原版包名 `com.qq.reader`。此前不是“壳概率性可用”的随机问题，而是原版包名 spoof 下发时机错误，导致壳在摘要/解密前读到了 clone 包名。

## 2026-06-23 v260-v263 token map / interface20 诊断检查点

当前目标：修复 QQ 阅读分身登录 native 注册缺失。原壳 `StubApp` core native 注册已经稳定成功，但 `YWLoginManager.pwdLogin/sendPhoneCode/qrCodeV2` 的真实 native 仍未注册。

### 已排除

- v260/v261：新增 `NativeHookBridge.getJiaguTokenDiag(59494)`，native 侧读取 `libjiagu_vip.so` `base+0x253148` token manager。多轮日志均显示 `root=0 treeCount=0 allVec=0/0/0`。
- v261：在 app attach 后手动调用原始 `StubApp.interface20()`。日志显示 `nativeCallOriginalStubInterface20: completed result=1`，但 token map 仍为空，说明不是“只是没重跑 interface20”。
- v262：QQ Reader profile 下跳过 Java 层 `AntiDetectionEngine` / `PackerDetectionBypass` 的 `ClassLoader.loadClass` pass-through hook。日志显示跳过后 `interface20()` 仍返回 1 且 token map 仍为空，所以 loadClass 栈污染不是当前根因。

关键 v262 日志：

```text
PackerRuntime.Jiagu: installPostLoadHooks: skipping AntiDetectionEngine Java packer bypass for QQ Reader
RegisterNatives: class=com.stub.StubApp count=10 caller=...libjiagu_vip.so offset=0x1116b4
captured original interface11=... offset=0x11fe2c
captured original interface20=... offset=0x10d3f4
JiaguJNI currentPackageName spoof ... spoof="com.qq.reader"
nativeCallOriginalStubInterface20: completed result=1 tokenAfter=... root=0x0 treeCount=0 allVec=0x0/0x0/0x0
RegisterNatives YWLoginManager: wrapped pwdLogin original=0x0
wrapped_ywlogin_pwdLogin: original native not registered
```

### 静态逆向结论

`interface20` 入口：

```text
0x10d3f4 ...
0x10d410 bl 0x11ad70
0x10d418 bl 0x11cf5c
0x10d41c tbz w0, 0, 0x10d444
0x10d424 bl 0x10d468
```

`0x11cf5c` 是当前重点：

- 先调用 `0x17ab28` 获取/初始化壳内部 registry。
- `0x17ab28` 使用全局 slot `base+0x2531b0`。
- 初始化时从 `base+0x253150` 起按 4 个 `0x18` 大小 seed entry 注册 key/value。
- 解出的关键 key 包括 `fencrypt`、`ulr`，后续还有 `sigCheck`、`fileCheck`、`com/qihoo/sc/SC` 相关路径。

token manager 路径：

- `0x179898` 初始化 `base+0x253148` token manager。
- `0x11ce80` 遍历待注册 vector，调用 `0x179a70` 插入 token/payload。
- `0x179a70` 需要 payload 参数非空，内部会按 token key 插入树并追加 `allVec`。

当前判断：`interface20 -> 0x11cf5c` 成功返回，但它生成的待注册 payload 列表为空，导致 `0x11ce80/0x179a70` 没有有效插入。问题不是 `interface11(59494)` 本身，也不是 YWLogin `<clinit>` 顺序。

### v263 已改代码

文件：`core/hook/src/main/cpp/native-hook.cpp`

新增诊断：

- 扩展 `jiagu_jni_diag_caller` 窗口：
  - `0x10d3f4` `interface20`
  - `0x11cf5c` `interface20-fencrypt`
  - `0x11d310` `interface20-filecheck`
  - `0x11b9c8` `interface20-sigcheck`
  - `0x11d644` `interface20-error`
  - `0x116c94` `interface20-fencrypt-input`
  - `0x123438` `interface20-qiniu-check`
- 新增 JNI `ExceptionOccurred` / `ExceptionClear` hook，日志会打印异常对象类型和 message，用于判断 interface20 是早退还是被类加载/检查异常打断。
- 扩展 `getJiaguTokenDiag(59494)` 输出 registry：
  - `registrySlot=base+0x2531b0`
  - `registry/root/count/context`
  - 前 6 个 registry node 的 key/value
  - `base+0x253150` seed entries 的 key/value

编译验证已通过：

```powershell
.\gradlew.bat --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :core:hook:assembleDebug :app:assembleDebug
```

结果：

```text
BUILD SUCCESSFUL
```

### 下一步

生成并测试 v263：

```powershell
powershell -ExecutionPolicy Bypass -File tools\qqreader-offline-patch\build-qqreader-offline.ps1 `
  -VersionTag v263-interface20-registry-diag `
  -ForceExtract `
  -ForceRepack `
  -SkipVerify `
  -PreserveOuterJiagu

& 'C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe' -s 192.168.2.42:43673 shell setprop debug.multiapp.ywlogin.auto_open 1

powershell -ExecutionPolicy Bypass -File tools\qqreader-offline-patch\test-qqreader-offline.ps1 `
  -Serial 192.168.2.42:43673 `
  -Apk .tmp\qqreader-c9f8-neutralized-v263-interface20-registry-diag-signed.apk `
  -VersionTag v263-interface20-registry-diag-run1 `
  -WaitSeconds 25 `
  -StubAppFallback 0 `
  -PatchJiagu 0
```

重点 grep：

```powershell
rg -n "nativeCallOriginalStubInterface20|tokenAfterInterface20|registry=|JiaguJNI .*interface20|ExceptionOccurred|ExceptionClear|RegisterNatives YWLoginManager|wrapped_ywlogin|pwdLogin native missing" .tmp\qqreader-v263-interface20-registry-diag-run1-start-logcat.txt
```

## 2026-06-23 v263 实测结果与 v264 handoff

本段用于防止上下文压缩后丢失当前判断。恢复任务时优先从这里继续。

### v263 已跑完，不要重复当成待办

v263 APK：

```text
.tmp\qqreader-c9f8-neutralized-v263-interface20-registry-diag-signed.apk
```

测试日志：

```text
.tmp\qqreader-v263-interface20-registry-diag-run1-start-logcat.txt
.tmp\qqreader-v263-interface20-registry-diag-run1-start-crash.txt
```

测试命令：

```powershell
powershell -ExecutionPolicy Bypass -File tools\qqreader-offline-patch\test-qqreader-offline.ps1 `
  -Serial 192.168.2.42:43673 `
  -Apk .tmp\qqreader-c9f8-neutralized-v263-interface20-registry-diag-signed.apk `
  -VersionTag v263-interface20-registry-diag-run1 `
  -WaitSeconds 25 `
  -StubAppFallback 0 `
  -PatchJiagu 0
```

v263 仍未注册真实登录 native：

```text
RegisterNatives YWLoginManager: wrapped pwdLogin original=0x0
RegisterNatives YWLoginManager: wrapped sendPhoneCode original=0x0
RegisterNatives YWLoginManager: wrapped qrCodeV2 original=0x0
```

### v263 确认项

原壳 core native 注册稳定，`StubApp.interface11/interface20` 都捕获到原始指针：

```text
RegisterNatives: class=com.stub.StubApp count=10 caller=...libjiagu_vip.so offset=0x1116b4
captured original interface11=... offset=0x11fe2c
captured original interface20=... offset=0x10d3f4
```

手动补调原始 `interface20()` 不是解法；它返回成功，但 token manager 仍为空：

```text
nativeCallOriginalStubInterface20: completed result=1
tokenAfter=... root=0x0 treeCount=0 allVec=0x0/0x0/0x0 allCount=0 token=59494
```

`base+0x253150` seed table 是完整的，说明 `fencrypt/fileCheck/sigCheck/report` 静态入口存在：

```text
seeds=
0:{key=sigCheck value=...}
1:{key=fileCheck value=...}
2:{key=fencrypt value=...}
3:{key=report value=...}
```

`base+0x2531b0` registry/cache 在 v263 运行时为：

```text
registry=... sentinel=0x0 root=0x0 count=0 context=0x0
```

但 v247 成功 dump 里同 slot 也可能为 0，所以不能把 `0x2531b0 root=0 count=0` 单独当作根因；它更像临时 registry/cache，不是最终 token 状态。

`interface20` 阶段出现的 360 辅助类 `FindClass` miss 不是强根因，因为 v247 成功样本也存在类似 miss：

```text
com/qihoo360/replugin/Entry
com/qihoo/bugreport/javacrash/CrashReportDataFactory
com/qihoo/sc/SC
com/qihoo/vc/VC
com/stub/stub09/RU
com/common/busi/CustomView
com/stub/stub07/Stub01
```

### 当前最可信判断

问题已经从“壳没注册/没调用 interface20”收窄为：

```text
interface20(0x10d3f4)
  -> 0x11cf5c
    -> fencrypt/fileCheck/sigCheck/qiniu-check 链路
    -> 构造待注册条目 vector
  -> 0x11ce80 遍历待注册 vector
  -> 0x179a70 插入 token manager
```

`0x11ce80` 的填表循环要求：

- `x19` 指向的待注册 vector 非空。
- 每个元素 `[x21 + 0x160]` 的 payload vector 非空。
- `0x179a70` 的 payload 参数非空。

v260-v263 的共同结果是 token manager 始终：

```text
root=0x0 treeCount=0 allVec=0x0/0x0/0x0 payload=0x0
```

因此更可能是 `0x11cf5c` 成功走完但产出的待注册条目列表为空，或者 `0x11ce80/0x179a70` 从未被有效调用。`nativeCallOriginalStubInterface20 result=1` 只代表外层 JNI 返回成功，不能证明 token payload 已生成。

### v264 应该做什么

下一版不要继续重复 `interface20()` 顺序实验，直接贴近填表点做动态诊断：

1. 优先 hook 或 patch-log `base+0x179a70`，记录调用次数和参数：
   - `x0` token manager
   - `x1` token/key
   - `x2` payload
   - `payloadVec` / `payloadCount`
2. 同时 hook 或近距离 log `0x11ce80` 循环入口，确认 `x19` vector 是否为空。
3. 若 inline hook 成本高，先在 `jiagu_jni_diag_caller` 中继续扩大 `0x11d310` 后半段、`0x11b9c8`、`0x11d644` 的 JNI 日志窗口，确认是哪一段产物为空。

优先级判断：

- 如果 `0x179a70` 从未调用：根因在 `0x11cf5c` 生成列表为空或 `0x11ce80` 未进循环。
- 如果 `0x179a70` 被调用但 `x2=0` 或 payload vector 空：根因在 fencrypt/fileCheck/sigCheck 生成 payload 失败。
- 如果 `0x179a70` 被调用且 payload 非空但 token manager 仍为空：再回头拆 `0x179a70` 内部插入失败条件。

常用 grep：

```powershell
rg -n "nativeCallOriginalStubInterface20|tokenAfterInterface20|registry=|seeds=|JiaguJNI .*interface20|ExceptionOccurred|ExceptionClear|RegisterNatives YWLoginManager|wrapped_ywlogin|pwdLogin native missing" .tmp\qqreader-v263-interface20-registry-diag-run1-start-logcat.txt
```

常用 radare2：

```powershell
& 'D:\360Downloads\radare2-6.1.6-w32\radare2-6.1.6-w32\bin\radare2.exe' -q -n -a arm -b 64 -m 0xdc000 -c "e scr.color=false; s 0x11cf5c; pd 320; s 0x11ce80; pd 120; s 0x179a70; pd 180; s 0x11d310; pd 220" .tmp\v247-run1-success-dump\jiagu-runtime-04-rel_000dc000-00235000-r-xp.bin
```

## 2026-06-23 v264-v266 实测与直接 dump/反汇编结果

本段用于防止上下文压缩后丢失当前判断。恢复任务时优先从这里继续，不要重复 v264-v266 已完成的实验。

### 已跑版本与日志

v264：`v264-token-insert-manual-hook`

```text
.tmp\qqreader-c9f8-neutralized-v264-token-insert-manual-hook-signed.apk
.tmp\qqreader-v264-token-insert-manual-hook-run1-start-logcat.txt
```

结论：

```text
install_jiagu_token_insert_manual_hook: installed ...
insertHook={installed=1 ... calls=0 ...}
```

`0x179a70` manual hook 安装成功但调用次数为 0，排除“payload 到了 0x179a70 后为空早退”。根因在更上游：没有进入 token insert 调用点。

v265：`v265-fill-loop-hook`

```text
.tmp\qqreader-c9f8-neutralized-v265-fill-loop-hook-signed.apk
.tmp\qqreader-v265-fill-loop-hook-run1-start-logcat.txt
```

结论：

```text
fillLoopHooks={buildVectorInstalled=1 buildVectorCalls=0 ... managerInitInstalled=1 managerInitCalls=...}
```

`0x179898` token manager init 会被调用，但 `0x119fa8` build-register-vector 从未调用，`0x179a70` 也从未调用。说明 `0x11ce74 -> 0x119fa8 -> 0x179898 -> 0x179a70` 这条填表路径没有走通。

v266：`v266-register-gate-hook`

```text
.tmp\qqreader-c9f8-neutralized-v266-register-gate-hook-signed.apk
.tmp\qqreader-v266-register-gate-hook-run1-start-logcat.txt
.tmp\qqreader-v266-register-gate-hook-run1-start-crash.txt
```

结论：

```text
install_manual_entry_hook: installed label=register-gate-0x17ac6c ...
fillLoopHooks={... gateInstalled=1 gateCalls=0 ...}
```

`0x17ac6c` register gate hook 安装成功，但调用次数仍为 0。所以当前不是 `0x17ac6c` 返回 0 导致 `0x11ce70 tbz` 跳过，而是执行流根本没有到 `0x11cd20/0x11ce50` 这段填表前置逻辑。

### 直接 dump/反汇编文件

当前失败运行态 dump 已从设备拉到：

```text
.tmp\v266-register-gate-current-dump\
```

关键文件：

```text
.tmp\v266-register-gate-current-dump\jiagu-runtime-04-rel_000dc000-00235000-r-xp.bin
.tmp\v266-register-gate-current-dump\jiagu-runtime-07-rel_00248000-00257000-rw-p.bin
.tmp\v266-register-gate-current-dump\jiagu-runtime-maps.txt
.tmp\v266-register-gate-current-dump\v266-focused-disasm.txt
.tmp\v266-register-gate-current-dump\v247-focused-disasm.txt
.tmp\v266-register-gate-current-dump\branch-critical-disasm.txt
```

生成反汇编的命令：

```powershell
$r2='D:\360Downloads\radare2-6.1.6-w32\radare2-6.1.6-w32\bin\radare2.exe'
$bin='.tmp\v266-register-gate-current-dump\jiagu-runtime-04-rel_000dc000-00235000-r-xp.bin'
& $r2 -q -n -a arm -b 64 -m 0xdc000 -c "e scr.color=false; e asm.bytes=true; s 0x10d3f4; pd 32; s 0x10d468; pd 780; s 0x11cf5c; pd 320; s 0x11cd20; pd 180" $bin
```

### dump 对比结果

v266 当前失败 dump 与 v247 成功 dump 在核心逻辑入口完全一致：

```text
0x10d3f4: fail=0xf81e0ff4 succ=0xf81e0ff4 same=True
0x10d468: fail=0xa9ba6ffc succ=0xa9ba6ffc same=True
0x10db84: fail=0xb4000d89 succ=0xb4000d89 same=True
0x10dd10: fail=0x94006ef0 succ=0x94006ef0 same=True
0x11cf5c: fail=0xd10443ff succ=0xd10443ff same=True
0x11cd20: fail=0xf94017e0 succ=0xf94017e0 same=True
0x11ce50: fail=0x94017787 succ=0x94017787 same=True
```

只有我们安装过 hook 的位置不同：

```text
0x119fa8: fail=0x17fb7816 succ=0xd10243ff same=False
0x179898: fail=0x140441da succ=0xa9bf7bf3 same=False
0x179a70: fail=0x14040164 succ=0xb4000b02 same=False
0x17ac6c: fail=0x140400e5 succ=0xd10183ff same=False
```

结论：失败不是因为 `libjiagu_vip.so` 解密出了不同代码版本；核心代码和成功样本一致。差异在运行态输入、Java/JNI 查询结果、或壳内部对象状态。

v266 失败 dump 的 rw 全局槽：

```text
0x253148: 0x0
0x253150: 0x74af84d088
0x253168: 0x74af84d0a2
0x253180: 0x74af84d0ca
0x253198: 0x74af8459b0
0x2531b0: 0x0
0x2531b8: 0x0
0x2531c0: 0x0
```

`0x253150` seed table 存在，仍对应 `sigCheck/fileCheck/fencrypt/report`。但 `0x253148` token manager slot 和 `0x2531b0` registry/cache slot 在 dump 时为空。结合日志里 `manager=0xb400... root=0 treeCount=0`，说明 token manager 对象可能由 `0x179898` 临时返回，并未写入全局树状态。

### 当前最可信执行流判断

外层：

```text
interface20(0x10d3f4)
  -> 0x11cf5c
  -> 若 0x11cf5c 返回 true，调用 0x10d468
```

v266 日志显示手动 `interface20()` 返回 true，但 `0x17ac6c/0x119fa8/0x179a70` 都没有调用。由此判断：当前执行进入了 `0x10d468`，但停在 `0x10d468` 内部更早的 Java/资源检查或产物拼装阶段，尚未走到 `0x11cd20/0x11ce50`。

已知 `0x10d468` 中的重要分支：

```text
0x10d5fc FindClass com/qihoo/bugreport/javacrash/CrashReportDataFactory
0x10d8a0 FindClass com/common/busi/CustomView
0x10db84 cbz x9, 0x10dd34
0x10dd10 bl 0x1298d0
0x10e0f0 csel w23, w23, w0, eq
```

v266 日志只稳定出现：

```text
FindClass callerOff=0x10d5fc ... CrashReportDataFactory
ExceptionClear callerOff=0x10d674
FindClass callerOff=0x10d8a0 ... CustomView
ExceptionCheck callerOff=0x10d8b4 result=1
ExceptionCheck callerOff=0x10d8cc result=1
```

这与 `gateCalls=0` 一致：执行流在 `0x10d8a0` 之后的某个检查/产物构造点早退或走空路径，还没有进入真正填表段。

### 下一步

不要继续重复 `interface20()` 手动补调，也不要再优先 hook `0x17ac6c`。下一步应直接围绕 `0x10d468` 内部早退条件做 dump 对比：

1. 对 v247 成功运行和 v266 失败运行对比 `0x10d468` 中 Java 查询结果，重点是 `CrashReportDataFactory`、`CustomView`、以及后续通过 `[x19]+0x30/0x720/0x6b8/0xb8` 间接调用得到的对象。
2. 如果继续打点，优先打 `0x10d8d0`、`0x10db84`、`0x10dd10`、`0x10e0f0`，而不是 `0x11ce50` 之后。
3. 如果用户要求“继续直接 dump/反编译”，优先扩大 `branch-critical-disasm.txt` 的 0x10d468 后半段，并把关键栈上 std::string 产物从 dump/log 中解出来。
 
## 2026-06-23 v267 0x10d468 / 0x1298d0 entry probe

目的：继续排查 QQ 阅读分身登录 native 注册缺失。v264-v266 已证明 `0x17ac6c/0x119fa8/0x179a70` 都没有被调用，因此 v267 不再重复这些点，而是前移到 `interface20 -> 0x10d468` 内部。

本次代码改动：

- `core/hook/src/main/cpp/native-hook.cpp`
  - 新增 `Jiagu10d468` wrapper，hook `libjiagu_vip.so + 0x10d468`，首指令校验 `0xa9ba6ffc`。
  - 新增 `JiaguPayloadBuild` wrapper，hook `libjiagu_vip.so + 0x1298d0`，首指令校验 `0xd102c3ff`。
  - `fillLoopHooks={...}` 追加 `interface20Reg*`、`payloadBuild*`、`payloadS1/payloadS2/payloadS3`、`payloadFlags`。

反汇编依据：

```text
0x10d424 bl 0x10d468
0x10d468 fc6fbaa9 stp x28, x27, [sp, -0x60]!

0x10dd10 bl 0x1298d0
0x1298d0 ffc302d1 sub sp, sp, 0xb0
```

编译结果：

```powershell
.\gradlew.bat --no-daemon "-Dkotlin.compiler.execution.strategy=in-process" --console=plain :core:hook:assembleDebug :app:assembleDebug
```

结果：`BUILD SUCCESSFUL`，只编译目标模块。

打包结果：

```text
.tmp\qqreader-c9f8-neutralized-v267-10d468-entry-payload-probe-signed.apk
```

运行状态：

```text
adb devices -l
List of devices attached

adb connect 192.168.2.42:43673
cannot connect ... 10060

adb connect 192.168.2.95:37631
cannot connect ... 10061

adb mdns services
List of discovered mdns services
```

结论：v267 已完成编译和打包，但当前无可用 ADB 设备，尚未安装运行。拿到新的无线调试地址后，直接执行：

```powershell
& 'C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe' connect <ip:port>
& 'C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe' -s <ip:port> shell setprop debug.multiapp.ywlogin.auto_open 1
powershell -ExecutionPolicy Bypass -File tools\qqreader-offline-patch\test-qqreader-offline.ps1 -Serial <ip:port> -Apk .tmp\qqreader-c9f8-neutralized-v267-10d468-entry-payload-probe-signed.apk -VersionTag v267-10d468-entry-payload-probe-run1 -WaitSeconds 25 -StubAppFallback 0 -PatchJiagu 0
```

测试后 grep：

```powershell
rg -n "Jiagu10d468|JiaguPayloadBuild|interface20RegInstalled|interface20RegCalls|payloadBuildInstalled|payloadBuildCalls|nativeCallOriginalStubInterface20|tokenAfterInterface20|RegisterNatives YWLoginManager|wrapped pwdLogin|Fatal signal|SIGSEGV|SIGILL" .tmp\qqreader-v267-10d468-entry-payload-probe-run1-start-logcat.txt
```

判读规则：

- `interface20RegCalls=0`：`interface20()` 没有进入 `0x10d468`，需要回看 `0x11cf5c` 返回条件。
- `interface20RegCalls>0` 且 `payloadBuildCalls=0`：早退发生在 `0x10d468` 的 `0x10d8a0 -> 0x10db84/0x10dd10` 之前。
- `payloadBuildCalls>0` 但 `buildVectorCalls=0/gateCalls=0`：`0x1298d0` 产物构建后仍未进入 `0x11cd20/0x11ce50`，下一步看 `0x10e0f0/0x10e100/0x10e124/0x10e268` 的比较结果。
- 如果 `payloadBuildCalls>0` 且 `payloadS*` 显示空或 `<unreadable>`，优先分析传入 `0x1298d0` 的三个 `std::string` 来源。

## 2026-06-23 v267-v270 runtime update

当前目标仍是让 QQ Reader clone 的真实 YWLogin native 注册出来。直接失败点没有变化：

```text
RegisterNatives YWLoginManager: wrapped pwdLogin original=0x0
RegisterNatives YWLoginManager: wrapped sendPhoneCode original=0x0
RegisterNatives YWLoginManager: wrapped qrCodeV2 original=0x0
```

### v267: 0x10d468 / 0x1298d0 entry probe

运行包：

```text
.tmp\qqreader-c9f8-neutralized-v267-10d468-entry-payload-probe-signed.apk
.tmp\qqreader-v267-10d468-entry-payload-probe-run1-start-logcat.txt
```

关键日志：

```text
Jiagu10d468 enter
JiaguPayloadBuild enter ... s1=com.qq.reader flags=0/0 s2=17 s3=0
Jiagu10d468 leave ... buildVectorCalls=0 gateCalls=0 insertHook calls=0
```

结论：`interface20 -> 0x10d468 -> 0x1298d0` 确实进入，但没有进入 `0x11ce50 -> 0x17ac6c -> 0x119fa8 -> 0x179a70` 填表链路。

### v268: compare GOT hook

`0x10a4b0` 是 PLT/GOT 跳板，不能 inline hook 首指令；实际 GOT slot 为 `base + 0x246328`。

关键日志：

```text
install_jiagu_compare_got_hook: installed
JiaguCompare call=1 callerOff=0x10e0ec len=1 result=-1 leftText=0 rightText=1
JiaguCompare call=2 callerOff=0x10e36c len=1 result=-1 leftText=0 rightText=1
JiaguCompare call=4 callerOff=0x10e618 len=1 result=-1 leftText=0 rightText=1
```

结论：`0x10d468` 后半段存在 `"0"` vs `"1"` 比较门，返回 `-1` 时执行流不能推进到填表链。

### v269: force selected compare equal

只对以下三处强制返回相等：

```text
callerOff in {0x10e0ec, 0x10e36c, 0x10e618}
len == 1
left == '0'
right == '1'
```

运行包和日志：

```text
.tmp\qqreader-c9f8-neutralized-v269-compare-force-0eq1-signed.apk
.tmp\qqreader-v269-compare-force-0eq1-run1-start-logcat.txt
```

结果：

```text
forced=1
callerOff=0x10e758/0x10e8a4/0x10ea08/0x10eb34/0x10ec94
buildVectorCalls=0 gateCalls=0 insertHook calls=0
tokenAfterInterface20 root=0x0 treeCount=0
```

结论：前三个 compare 确实是门，强制后能推进到后续检查链，但仍未进入 payload 填表链。后续关键分叉集中在 `0x10eb34`、`0x10ec94` 以及 `0x1298d0` 的 payload 写入条件。

### v270: payload condition probe

新增诊断：

- hook `0x206360`，日志名 `JiaguEnvProbe`
- hook `0x10b210` GOT slot `base + 0x2469d8`，日志名 `JiaguStringEq`
- 在 `0x1298d0` wrapper 前后读取 `base + 0x253010` payload slot

运行包和日志：

```text
.tmp\qqreader-c9f8-neutralized-v270-payload-conditions-probe-signed.apk
.tmp\qqreader-v270-payload-conditions-probe-run1-start-logcat.txt
```

关键证据：

```text
JiaguPayloadBuild enter ... s1=com.qq.reader flags=0/0 s2=17 s3=0 slot=... before=0x0
JiaguEnvProbe call=1 callerOff=0x129918 ... result=36
JiaguPayloadBuild leave ... before=0x0 after=0x0
stringEqInstalled=1 stringEqCalls=0
```

反汇编对应：

```text
0x129914 bl 0x206360
0x129934 cmp w26, 0x19
0x129938 b.ne 0x129960
```

结论：`0x1298d0` 的第一道硬条件要求 `0x206360(env)` 返回 `0x19`，但当前 clone 环境返回 `36` (`0x24`)。因此 `0x1298d0` 走失败路径，`base+0x253010` payload slot 不写入，`0x10b210` 字符串匹配没有机会执行，后续 `0x119fa8/0x179a70` 填表链自然不会触发。

### 当前障碍

当前最大障碍不是 `interface20()` 没调用，也不是 `loadClass` 栈污染；障碍是壳内部环境探测 `0x206360(env)` 在 clone 中返回 `0x24`，不满足 `0x1298d0` 期望的 `0x19`，导致 payload 构建失败。下一步应反汇编/诊断 `0x206360` 内部调用链，找出哪个 JNI/环境值导致返回 `0x24`，再决定是 spoof 该输入，还是只在 `0x1298d0` 调用点安全修正返回值。

## 2026-06-23 v271 force SDK25 payload probe

v271 用于验证 v270 找到的 `SDK_INT` 门是否是唯一阻塞点。代码只在壳内两个已确认调用点修正 `0x206360(env)` 返回值：

```text
callerOff == 0x129918 || callerOff == 0x10eb7c
result = 0x19
```

编译和打包结果：

```text
BUILD SUCCESSFUL
.tmp\qqreader-c9f8-neutralized-v271-force-sdk25-payload-signed.apk
.tmp\qqreader-v271-force-sdk25-payload-run1-start-logcat.txt
```

关键日志：

```text
JiaguPayloadBuild enter ... s1=com.qq.reader flags=0/0 s2=17 s3=0 slot=... before=0x0
JiaguEnvProbe call=1 callerOff=0x129918 ... result=25 forcedSdk25=1
JiaguStringEq call=1 callerOff=0x12995c left=...Xiaomi... right=...samsung... result=5
JiaguPayloadBuild leave ... before=0x0 after=0x0
Jiagu10d468 leave ... buildVectorCalls=0 gateCalls=0 insertHook calls=0 payloadSlot=0x0->0x0
RegisterNatives YWLoginManager: wrapped pwdLogin original=0x0
RegisterNatives YWLoginManager: wrapped sendPhoneCode original=0x0
RegisterNatives YWLoginManager: wrapped qrCodeV2 original=0x0
pwdLogin native missing
```

反汇编对应：

```text
0x129914 bl 0x206360
0x129934 cmp w26, 0x19
0x129938 b.ne 0x129960
0x129958 bl 0x10b210
0x12995c cbz w0, 0x129a10
0x1299d4 bl 0x129c58
0x1299ec tbz w25, 0, 0x129a10
0x1299f4 adrp x25, 0x253000
0x1299f8 add x25, x25, 0x10
0x129a0c cbz w0, 0x129a50
0x129a6c str x0, [x25, 8]
```

结论：

- `SDK_INT` 门已经被排除：`0x129918` 和 `0x10eb7c` 都被修正为 `25`。
- 执行流已经推进到 `0x12995c` 的品牌/字符串匹配，当前输入表现为 clone 设备 `Xiaomi` 对壳内常量 `samsung`。
- 不能直接断言强制品牌匹配就能完成注册，因为 `0x12995c` 后还要经过 `0x129c58` 返回值和 `JNIEnv` 调用 `[x24]+0x6d8` 的存在性检查。
- 当前直接障碍仍是 `base+0x253010` payload slot 没有写入，导致 `0x11cd20/0x11ce50 -> 0x17ac6c/0x119fa8/0x179a70` 填表链未触发，`YWLoginManager` 的真实 native 仍没有注册。

下一步不要再重复手动调用 `interface20()`。优先做 v272 诊断：

1. hook `0x129c58`，记录参数 std::string 和返回值 `w0`，确认 `tbz w25, 0, 0x129a10` 是否早退。
2. hook 或记录 `[JNIEnv]+0x6d8` 在 `0x129a08` 的返回值，确认 `cbz w0, 0x129a50` 是否能进入 payload slot 写入。
3. 继续记录 `base+0x253010` 与 `base+0x253018`，因为反汇编显示 `x25 = base + 0x253010`，真正对象指针写在 `[x25, 8]`。

## 2026-06-23 strategy note: visualapp/NEXTVM vs direct jiagu patch

用户提出两个更直接的方向：

1. 参考 visualapp/NEXTVM，把需要的 hook 移植过来。
2. 直接逆向 `libjiagu_vip.so` 并 patch/crack 壳。

当前判断：

- 参考 `..\NEXTVM` 可行，但只能复用通用能力：`Runtime.nativeLoad`、LSPlant/ShadowHook、路径/属性/设备伪装、ActivityThread/Instrumentation 这类基础设施。`NEXTVM` 没有现成的 QQ Reader `libjiagu_vip.so` token 表、`interface20()`、`0x1298d0` payload 构建和 `YWLoginManager` native 注册恢复逻辑，因此不能直接替代当前壳分析。
- “把需要的 hook 全加上”也可行，但前提是先知道壳实际需要哪些输入。v267-v271 已证明不是单点问题：已经遇到 `SDK_INT`、`"0"` vs `"1"` compare、brand/string 匹配、payload slot 未写入、`0x129c58`/`JNIEnv+0x6d8` 后续门。盲目全局 spoof 容易让壳继续往后走，但也容易触发错误分支或污染 QQ Reader 正常业务环境。
- 直接 patch 壳技术上可行，且可能是最终更短路径。更稳的做法不是先全量破解，而是围绕当前已定位链路做最小 patch：让 `0x1298d0` 成功构建 `base+0x253010/+0x253018` payload，再观察 `0x11cd20/0x11ce50 -> 0x17ac6c/0x119fa8/0x179a70` 是否开始填 token 表。若这一步成立，再考虑固化为静态 patch；若不成立，继续 patch 下一个已证实门。

因此推荐路线：

1. 短期继续 v272 动态诊断，确认 `0x129c58` 和 `JNIEnv+0x6d8` 谁阻塞 payload 写入。
2. 一旦确认阻塞点，先做运行时定点 hook/force，验证 `token=59494` 表项和 `YWLoginManager` 真实 native 是否注册。
3. 验证成功后，再把运行时 hook 收敛成静态 patch 或更少的通用 spoof。这样既利用了直接破解的速度，也避免在未知分支上乱 patch。
## 2026-06-23 v272-v275 payload 后段诊断

### v272: `0x129c58` payload check probe

目的：确认 `0x1298d0` 内部在 SDK 门和 brand/string 门之后，是否因为 `0x129c58` 返回 0 早退。

关键证据：

```text
JiaguPayloadCheck call=1 arg=... text=com.qq.reader result=0
JiaguPayloadBuild leave ... payloadSlot=0x0->0x0 payloadSlot8=0x0->0x0
```

结论：`0x129c58(com.qq.reader)` 原始返回 0，触发反汇编中的失败分支：

```text
0x1299d4 bl 0x129c58
0x1299ec tbz w25, 0, 0x129a10
```

### v273: force `0x129c58(com.qq.reader)` success

改动：`hooked_jiagu_payload_check()` 在 `text == "com.qq.reader"` 且原始返回 0 时强制返回 1。

关键证据：

```text
JiaguPayloadCheck ... text=com.qq.reader result=1 forced=1
JiaguPayloadBuild leave ... before=0x0 after=0xb400007662a12aa0 before8=0x0 after8=0x3426
JiaguPayloadBuild leave ... before=0xb400007662a12aa0 after=0xb400007662a12aa0 before8=0x3426 after8=0x342e
```

结论：`0x1298d0` payload 构建已经被推进成功，`base+0x253010/+0x253018` 已写入。但 token 表仍为空：

```text
buildVectorCalls=0 gateCalls=0 insertHook calls=0
tokenAfterInterface20 root=0x0 treeCount=0
RegisterNatives YWLoginManager: wrapped pwdLogin original=0x0
```

因此新障碍从 payload 构建移动到 `0x10d468` 后半段：payload 已有，但未进入 `0x11cd20/0x11ce50 -> 0x17ac6c/0x119fa8/0x179a70` 填表链。

### v274b: post-payload branch probe

新增探针：

```text
0x123020 -> JiaguPostPayloadStatus
0x116c94 -> JiaguPostPayloadObject
```

运行包和日志：

```text
.tmp\qqreader-c9f8-neutralized-v274b-post-payload-branch-probe-signed.apk
.tmp\qqreader-v274b-post-payload-branch-probe-run1-start-logcat.txt
```

关键证据：

```text
postStatusInstalled=1 postStatusCalls=0
postObjectInstalled=1 postObjectCalls=4 postObjectResult=0xb4000075d2a5bad0
payloadSlot=0xb400007662a12aa0->0xb400007662a12aa0 payloadSlot8=0x342a->0x342e
buildVectorCalls=0 gateCalls=0 insertHook calls=0
```

反汇编对应：

```text
0x10ecc4 cmp w22, 0
0x10ecd4 tbnz w8, 0, 0x10f648
0x10ecd8 bl 0x123020
0x10ece8 bl 0x116c94
```

解释：`0x116c94` 有调用但 v274b 尚未记录 caller offset，不能证明它来自 `0x10ece8`；`0x123020` 没有调用，且没有 `0x10f270` 后段 `JiaguStringEq`，说明仍高度怀疑卡在 `0x10ecd4` 的失败跳转。

### v275: force post-payload branch

改动：

```text
NOP patch: base + 0x10ecd4
expected: 0x37004ba8  ; tbnz w8, #0, 0x10f648
replace:  0xd503201f  ; nop
```

同时扩展 `postStatus/postObject` 诊断，记录：

```text
postStatusCallerOff
postObjectCallerOff
forcePostBranchPatched
```

编译和打包：

```text
BUILD SUCCESSFUL
.tmp\qqreader-c9f8-neutralized-v275-force-post-payload-branch-signed.apk
```

当前状态：APK 已生成，但设备 `192.168.2.42:36127` 掉线；用户随后给的 `384591` 超过合法 TCP 端口范围，按 `38459` 尝试连接被拒绝：

```text
cannot connect to 192.168.2.42:38459: 10061
adb devices: empty
```

下一步：等设备恢复无线调试端口后运行 v275，重点验证：

```text
forcePostBranchPatched=1
JiaguPostPayloadStatus callerOff=0x10ece0
JiaguPostPayloadObject callerOff=0x10ecec
JiaguBuildRegisterVector / JiaguRegisterGate / JiaguTokenInsert 是否开始调用
tokenAfterInterface20 root/treeCount 是否非空
YWLoginManager pwdLogin/sendPhoneCode/qrCodeV2 original 是否非 0
```
## 2026-06-23 v276-v277 pre-materialize gate 诊断

### v276: `0x186f64` materialize probe

目的：确认 v275 已经 NOP `0x10ecd4` 后，执行流是否能进入 `0x10f5f8 bl 0x186f64`。

改动：

```text
hook 0x186f64 -> JiaguPostPayloadMaterialize
记录 callerOff、参数 std::string、base+0x253270/+0x253290/+0x2532f0/+0x253358
```

运行包和日志：

```text
.tmp\qqreader-c9f8-neutralized-v276-post-payload-materialize-probe-signed.apk
.tmp\qqreader-v276-post-payload-materialize-probe-run1-start-logcat.txt
device: 192.168.2.42:38591
```

关键证据：

```text
install_manual_entry_hook: installed label=post-payload-materialize-0x186f64
forcePostBranchPatched=1
postStatusCalls=2 postStatusCallerOff=0x10ece0 postStatusResult=259
postObjectCalls=6 postObjectCallerOff=0x10ecec
materializeInstalled=1 materializeCalls=0
buildVectorCalls=0 gateCalls=0 insertHook calls=0
tokenAfterInterface20 root=0x0 treeCount=0
RegisterNatives YWLoginManager: wrapped pwdLogin original=0x0
```

结论：v276 排除了“`0x186f64` hook 没装”的可能。hook 已安装但没有调用，说明阻塞点位于 `0x10efb8` 之后、`0x10f5f8` 之前。

对应反汇编：

```text
0x10efb4 bl 0x10a4b0
0x10efb8 cmp w0, 0
0x10efbc csel w25, w25, w0, eq
0x10efcc cbnz w25, 0x10f628
...
0x10efec cbnz w25, 0x10f628
0x10eff0 ...
0x10f5f8 bl 0x186f64
```

`0x10a4b0` compare 返回 0 仍不够，因为 `w25` 还可能携带长度/前置比较状态，`0x10efcc` 或 `0x10efec` 仍会跳到 `0x10f628` 清理段，绕过 materialize。

### v277: force pre-materialize gates

改动：

```text
NOP patch: base + 0x10efcc
expected: 0x350032f9  ; cbnz w25, 0x10f628
replace:  0xd503201f  ; nop

NOP patch: base + 0x10efec
expected: 0x350031f9  ; cbnz w25, 0x10f628
replace:  0xd503201f  ; nop
```

运行包和日志：

```text
.tmp\qqreader-c9f8-neutralized-v277-force-pre-materialize-gates-signed.apk
.tmp\qqreader-v277-force-pre-materialize-gates-run1-start-logcat.txt
device: 192.168.2.42:38591
```

关键证据：

```text
patched pre-materialize gate1 offset=0x10efcc
patched pre-materialize gate2 offset=0x10efec
JiaguPostPayloadMaterialize enter call=1 callerOff=0x10f5fc ... arg3=com.qq.reader flags=259/0
JiaguPostPayloadMaterialize leave call=1 ... slot270=<string-data-unreadable> slot290=com.qq.reader slot2f0=/data/user/0/ slot358=0
JiaguPostPayloadMaterialize enter call=2 callerOff=0x10f5fc ... arg3=com.qq.reader flags=259/0
JiaguPostPayloadMaterialize leave call=2 ... slot270=<string-data-unreadable> slot290=com.qq.reader slot2f0=/data/user/0/ slot358=0
materializeCalls=2
buildVectorCalls=0 gateCalls=0 insertHook calls=0
tokenAfterInterface20 root=0x0 treeCount=0
RegisterNatives YWLoginManager: wrapped pwdLogin original=0x0
```

结论：

- v277 证明 `0x10efcc/0x10efec` 是 materialize 前的实际阻塞点之一；patch 后已进入 `0x186f64`。
- 当前障碍继续后移：`0x186f64` 已执行并写入部分全局状态，但 token 注册链仍未触发，`0x17ac6c/0x119fa8/0x179a70` 仍无调用。
- `slot290=com.qq.reader` 和 `slot2f0=/data/user/0/` 有效，`slot270=<string-data-unreadable>`、`slot358=0` 可疑。下一步应围绕 materialize 后的分支和 `0x187900`/后续 compare 继续定位，而不是再重复 payload check 或手动 interface20。

下一步建议：

1. hook materialize 后半段的 `0x187900`，记录输入字符串和返回/写入结果。
2. 继续追踪 `0x10f648 -> 0x10fa30` 区间内的 compare/branch，确认为什么没有进入 `0x17ac6c/0x119fa8/0x179a70`。
3. 如果发现仍是 `std::string`/路径/包名 gate，再做最小 NOP 或参数修正；避免一次性全局 spoof 污染 QQ Reader 正常业务环境。

### v278: after-materialize normalize probe

改动：

```text
hook 0x187900 -> JiaguAfterMaterializeNormalize
扩大 JiaguCompare 记录窗口到 0x10fd00，覆盖 0x10fafc/0x10fc54
```

运行包和日志：

```text
.tmp\qqreader-c9f8-neutralized-v278-after-materialize-normalize-probe-signed.apk
.tmp\qqreader-v278-after-materialize-normalize-probe-run1-start-logcat.txt
device: 192.168.2.42:38591
```

关键证据：

```text
install_manual_entry_hook: installed label=after-materialize-normalize-0x187900
JiaguPostPayloadMaterialize ... callerOff=0x10f5fc ... arg3=com.qq.reader flags=259/0
JiaguPostPayloadMaterialize leave ... slot290=com.qq.reader slot2f0=/data/user/0/ slot358=0
JiaguCompare callerOff=0x10f748 len=0 result=0
JiaguCompare callerOff=0x10f880 len=1 result=0 leftText=1 rightText=1
JiaguCompare callerOff=0x10fafc len=0 result=0
JiaguAfterMaterializeNormalize enter/leave: not called
buildVectorCalls=0 gateCalls=0 insertHook calls=0
tokenAfterInterface20 root=0x0 treeCount=0
```

结论：

- `0x187900` hook 安装成功但没有调用，说明当前路径没有经过 `0x10fa08 -> 0x187900`。
- 结合反汇编，当前路径更可能在 `0x10f75c/0x10f760` 直接跳到 `0x10fa30`，绕过 normalize。
- 已确认执行到 `0x10fafc` compare，但没有看到 `0x10fc54` compare，说明阻塞点继续后移到 `0x10fb18 bl 0x123438` 及其后续 `cmp w0, 0x1e; b.gt 0x10fc70` 一带。

下一步：hook `0x123438` 记录 callerOff、参数和返回值。如果返回值大于 `0x1e`，优先验证是否需要对 `0x10fb20 b.gt 0x10fc70` 做最小 NOP/force，而不是继续动 payload 或 materialize。
## 2026-06-23 root emulator / Frida route

Current emulator:

```text
serial: emulator-5554
adb root: uid=0(root)
abi: x86_64
sdk: 34
native bridge: libhoudini.so
abilist: x86_64,arm64-v8a,x86,armeabi-v7a,armeabi
```

Local Frida setup:

```text
frida client: 17.3.2
frida-tools: 14.4.6
frida-server: .tmp\frida-17.3.2\frida-server-17.3.2-android-x86_64
device path: /data/local/tmp/frida-server-17.3.2
forwarded: tcp:27042, tcp:27043
```

Verification:

```text
frida-ps -U works
qqreader-c9f8-neutralized-v279-qiniu-check-probe-signed.apk installs successfully
```

Important difference from the physical arm64 device:

```text
Houdini path loads /data/data/.../lib/arm64/libjiagu_vip_x86.so
Process dies by signal 9 shortly after libjiagu_vip_x86.so execution
```

Observed emulator log:

```text
nativebridge: Failed to bind-mount /system/etc/cpuinfo.arm64.txt as /proc/cpuinfo
houdini: Houdini now in android app mode
Extracted origin native lib: libjiagu_vip.so
Extracted origin native lib: libjiagu_vip_x86.so
patchJiaguSo: disabled; preserving original libjiagu_vip.so
nativeInstallFindClassHook: mprotect failed
RegisterNatives logger installed=false
... execute /data/data/.../lib/arm64/libjiagu_vip_x86.so
Process com.qq.reader... has died: fg TOP
Zygote: Process exited due to signal 9 (Killed)
```

Conclusion:

- Root emulator + Frida is useful as a dynamic tooling accelerator.
- This x86_64/Houdini emulator is not equivalent to the physical arm64 path used by the previous `0x10fb20` analysis.
- The emulator currently enters `libjiagu_vip_x86.so`, while the physical-device blocker is in arm64 `libjiagu_vip.so`.
- Do not treat emulator results as proof for arm64 offsets unless the process is forced onto the same arm64 code path or an arm64-root emulator/device is used.
- Best use right now: use Frida on emulator to validate generic loader/self-kill behavior; keep arm64 gate decisions anchored to physical-device logs and arm64 runtime dumps.

Next options:

1. If an arm64-root emulator/device is available, push `frida-server-17.3.2-android-arm64` and use it for direct arm64 gate hot-patching.
2. On this x86_64 emulator, first solve the `libjiagu_vip_x86.so` self-kill/emulator gate if we want a stable Frida playground.
3. For the current login blocker, continue physical-device arm64 v280: patch or hot-validate `0x10fb20 b.gt 0x10fc70`, then check token build/register evidence.
## 2026-06-24 root VM original-vs-clone dump

Environment:

```text
serial: 192.168.2.42:10001
su: uid=0(root)
SELinux: Disabled
abi: arm64-v8a
sdk: 30
packages:
  original: com.qq.reader
  clone: com.qq.reader.clonestub_c9f8edb61aa74290a477823cf99c0ba8
```

Added root tooling:

```text
tools/root-dump/qqmemdump.c
tools/root-dump/qqdump_watch.sh
tools/root-dump/shorten_dump_names.sh
.tmp/qqmemdump-arm64
/data/local/tmp/qqmemdump
```

Purpose:

- Use root to dump `/proc/<pid>/maps` and `/proc/<pid>/mem` directly.
- Compare original QQ Reader and clone in the same root VM.
- Avoid depending on Frida, because `frida-server-17.3.2` aborts in this VM with:

```text
Abort message: 'Unsupported Android linker; please file a bug'
```

Dump artifacts:

```text
.tmp\qqdump-orig-rootvm-watch-run1
.tmp\qqdump-clone-rootvm-watch-run1\qqdump-clone-rootvm-watch-run1
.tmp\qqdump-orig-rootvm-bss-after-jiagu-run2\qqdump-orig-rootvm-bss-after-jiagu-run2
.tmp\qqdump-clone-rootvm-bss-after-jiagu-run1\qqdump-clone-rootvm-bss-after-jiagu-run1
```

Key maps evidence:

Original `com.qq.reader`:

```text
7837185000-7837186000 r-xp ... /data/app/.../com.qq.reader.../lib/arm64/libjiagu_vip.so
7837186000-78371a2000 r-xp ... /data/app/.../com.qq.reader.../lib/arm64/libjiagu_vip.so
78371b3000-7837261000 rw-p ... /data/app/.../com.qq.reader.../lib/arm64/libjiagu_vip.so
7837261000-78373ba000 r-xp 00000000 00:00 0 [anon:.bss]
78373ba000-78373c9000 rw-p 00000000 00:00 0 [anon:.bss]
78373c9000-78373cd000 r--p 00000000 00:00 0 [anon:.bss]
78373cd000-78373dc000 rw-p 00000000 00:00 0 [anon:.bss]
78373dc000-78373e5000 r-xp ... /data/app/.../com.qq.reader.../lib/arm64/libjiagu_vip.so
```

Clone:

```text
75b97c3000-75b97e0000 r-xp ... /data/app/.../clone.../lib/arm64/libjiagu_vip.so
75b97f1000-75b989f000 rw-p ... /data/app/.../clone.../lib/arm64/libjiagu_vip.so
75b989f000-75b9a1a000 rw-p 00000000 00:00 0 [anon:.bss]
75b9a1a000-75b9a23000 r-xp ... /data/app/.../clone.../lib/arm64/libjiagu_vip.so

75b9a40000-75b9a5d000 r-xp ... /data/data/.../clone.../lib/arm64/libjiagu_vip.so
75b9a6e000-75b9b1c000 rw-p ... /data/data/.../clone.../lib/arm64/libjiagu_vip.so
75b9b1c000-75b9c97000 rw-p 00000000 00:00 0 [anon:.bss]
75b9c97000-75b9ca0000 r-xp ... /data/data/.../clone.../lib/arm64/libjiagu_vip.so
```

Important comparison:

```text
original adjacent bss:
  r20.bin size=720896 sha256=195D853F09F7BF70...
  mapping perms: r-xp [anon:.bss]

clone adjacent bss:
  r7.bin  size=1552384 sha256=435726923B6DF50E...
  r8.bin  size=1552384 sha256=435726923B6DF50E...
  mapping perms: rw-p [anon:.bss]
```

Signature search:

```text
orig r20.bin:
  qiniu branch 0x54000a8c found at file offset 0x33b20
  interface20-register-like prologue 0xa9ba6ffc found

clone r7.bin/r8.bin:
  qiniu branch 0x54000a8c not found
  interface20-register-like prologue 0xa9ba6ffc not found
```

Conclusion:

- Root VM proves a stronger earlier failure than the physical-device v279 `0x10fb20` gate.
- In original QQ Reader, the adjacent `[anon:.bss]` becomes executable and contains the runtime code region where the known qiniu gate signature exists.
- In the clone, two copies of `libjiagu_vip.so` are loaded (`/data/app/...` and `/data/data/...`), but their adjacent `[anon:.bss]` regions remain `rw-p` and do not contain the known runtime signatures.
- Therefore the root-VM clone is not currently failing at `0x10fb20`; it fails before runtime code materialization / permission transition.
- This also explains earlier root-VM clone logs where `interface20 offset=0x10d3f4` was all zero and `YWLoginManager.getInstance` was native-missing.

Next root-route work:

1. Patch or hook the clone path so the extracted `/data/data/.../libjiagu_vip.so` follows the original package load/materialization path, not the duplicated split-load path.
2. Use root memory patching to test whether copying/mprotecting the original-style executable bss layout is enough to reach `StubApp.interface20` registration.
3. Keep physical-device v280 as a separate line: it already reaches materialized code and fails later at `0x123438 result=36 -> 0x10fb20`.

## 2026-06-24 root VM v281-v283 installed-original-lib experiments

Purpose:

- Test the root-route idea with both original QQ Reader and clone installed in the same root VM.
- First remove the clone's outer `libjiagu_vip.so` so the process does not load both `/data/app/...clone.../libjiagu_vip.so` and `/data/data/...clone.../libjiagu_vip.so`.
- Then force the clone to load the original installed QQ Reader native library path:

```text
/data/app/~~wF2WzuCifsMyPmwM6bRUnw==/com.qq.reader-TxdWX_iClGqT7GmJAxzqzw==/lib/arm64
```

Code changes:

```text
core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt
  debug.multiapp.jiagu.use_installed_origin_lib=1
  debug.multiapp.jiagu.installed_origin_lib_dir=<dir>
```

Build/test artifacts:

```text
.tmp\qqreader-c9f8-neutralized-v281-root-no-outer-jiagu-signed.apk
.tmp\qqreader-v281-root-no-outer-jiagu-run1-logcat-tail.txt
.tmp\v281-root-no-outer-jiagu-dump

.tmp\qqreader-c9f8-neutralized-v282-use-installed-origin-lib-signed.apk
.tmp\qqreader-v282-use-installed-origin-lib-run1-logcat-tail.txt

.tmp\qqreader-c9f8-neutralized-v283-force-installed-origin-lib-dir-signed.apk
.tmp\qqreader-v283-force-installed-origin-lib-dir-run1-logcat-tail.txt
.tmp\v283-force-installed-origin-lib-dir-dump
```

v281 result:

- Built without `-PreserveOuterJiagu`; patch output removed:

```text
lib/arm64-v8a/libjiagu_vip.so
lib/arm64-v8a/libjiagu_vip_x86.so
lib/armeabi-v7a/libjiagu_vip.so
lib/armeabi-v7a/libjiagu_vip_x86.so
```

- Runtime maps now contain only one `libjiagu_vip.so` instance from clone data:

```text
/data/data/com.qq.reader.clonestub.../lib/arm64/libjiagu_vip.so
75b7b25000-75b7ca0000 rw-p ... [anon:.bss]
```

- `StubApp.load()` returns, but original Jiagu `RegisterNatives count=10` still does not happen:

```text
StubApp binding after load: interface5=missing interface11=missing interface20=missing ... originalJiaguComplete=0
dump_decrypted: interface20 offset=0x10d3f4 insn=[0x00000000, ...]
YWLoginManager.getInstance native missing
```

v281 signature:

```text
jiagu-runtime-03-rel_000dc000-00257000-rw-p.bin
  size=1552384
  sha256=435726923B6DF50E0464A33FB7E6454F...
  qiniu_branch_54000a8c=-1
  interface20_register_a9ba6ffc=-1
```

v282 result:

- Added PM-based installed-origin native dir lookup.
- In clone process, `PackageManager.getApplicationInfo("com.qq.reader")` returned:

```text
NameNotFoundException: com.qq.reader
```

- Therefore v282 fell back to `/data/data/...clone.../lib/arm64` and reproduced v281.

v283 result:

- Added forced path property and ran with:

```text
debug.multiapp.jiagu.use_installed_origin_lib=1
debug.multiapp.jiagu.installed_origin_lib_dir=/data/app/~~wF2WzuCifsMyPmwM6bRUnw==/com.qq.reader-TxdWX_iClGqT7GmJAxzqzw==/lib/arm64
```

- Loader accepted the forced path:

```text
Installed origin native lib dir forced by prop: /data/app/.../com.qq.reader.../lib/arm64
Using installed origin native lib dir first: /data/app/.../com.qq.reader.../lib/arm64
Updated nativeLibraryDir -> /data/app/.../com.qq.reader.../lib/arm64
```

- Runtime maps confirmed the clone loaded the original installed `libjiagu_vip.so` path:

```text
75b7a46000-75b7a63000 r-xp ... /data/app/.../com.qq.reader.../lib/arm64/libjiagu_vip.so
75b7a74000-75b7b22000 rw-p ... /data/app/.../com.qq.reader.../lib/arm64/libjiagu_vip.so
75b7b22000-75b7c9d000 rw-p 00000000 00:00 0 [anon:.bss]
75b7c9d000-75b7ca6000 r-xp ... /data/app/.../com.qq.reader.../lib/arm64/libjiagu_vip.so
```

- But BSS still did not materialize; no original `StubApp.interface11/interface20` registration:

```text
installStubFallback: before fallback: interface5=missing interface11=missing interface20=missing ... originalJiaguComplete=0
RegisterNatives: class=com.stub.StubApp count=4 caller=...libmultiapp-native.so
YWLoginManager.getInstance native missing
```

v283 signature:

```text
jiagu-runtime-03-rel_000dc000-00257000-rw-p.bin
  size=1552384
  sha256=435726923B6DF50E0464A33FB7E6454F...
  qiniu_branch_54000a8c=-1
  interface20_register_a9ba6ffc=-1
```

Updated conclusion:

- Removing outer Jiagu fixed the duplicate-load shape but not the materialization failure.
- Forcing the clone to load the original installed `/data/app/...com.qq.reader.../libjiagu_vip.so` also did not trigger executable BSS generation.
- Therefore the root VM clone is not blocked only by library file path. The remaining delta is likely process/package/runtime identity observed by the shell before `0x186f64` materialization, such as ActivityThread package fields, LoadedApk package name, app data dir, PackageManager responses, UID/package relation, or `/proc/self/*` beyond cmdline.
- Next root-route work should compare original vs clone inputs consumed before materialization, not continue copying library paths. Good candidates: hook/log shell reads of `/proc/self/maps`, `/proc/self/cmdline`, package/data path strings, and PM/ActivityThread package queries; alternatively root-hotpatch the materialize pre-gates after identifying their current input values.

## 2026-06-24 root VM multi-user original-package route

Purpose:

- Use root to avoid the changed clone package name entirely.
- Install and start the already-installed original `com.qq.reader` for a second Android user, so the process still sees:

```text
packageName=com.qq.reader
signature=original QQ Reader signature
codePath=/data/app/.../com.qq.reader.../base.apk
nativeLibraryDir=/data/app/.../com.qq.reader.../lib
```

Commands used:

```powershell
adb -s 192.168.2.42:10001 shell pm create-user qqreader_clone
adb -s 192.168.2.42:10001 shell cmd package install-existing --user 10 com.qq.reader
adb -s 192.168.2.42:10001 shell am switch-user 10
adb -s 192.168.2.42:10001 shell am start -n com.qq.reader/.activity.DefaultAliasActivity
```

Added helper scripts:

```text
tools/root-dump/qqroot_probe.ps1
tools/root-dump/qqreader_multiuser_root.ps1
```

Observed user state:

```text
Users:
  UserInfo{0:机主:c13} running
  UserInfo{10:qqreader_clone:410} running

User 10:
  installed=true hidden=false stopped=false notLaunched=false
  dataDir=/data/user/10/com.qq.reader
```

Important result:

- The `user 10` original-package process starts as real `com.qq.reader`.
- It still exits quickly with:

```text
com.qq.reader: System.exit called, status: 1
AndroidRuntime: VM exiting with result code 1
```

- However, root watcher proves this route reaches Jiagu runtime materialization before the exit:

```text
/data/local/tmp/qqreader-user10-bss2/range-20-0000007836f65000-00000078370be000-off_0-r-xp-_anon_.bss_.bin
size=1413120
qiniu_branch_54000a8c offset=0x33b20
interface20_register_a9ba6ffc offset=0x31468
```

Conclusion:

- This is a better root route than repackaged clone execution.
- The previous clone failure was before materialization: `[anon:.bss]` stayed `rw-p`, `interface20` bytes were zero, and `YWLoginManager.getInstance` remained native-missing.
- The multi-user route preserves original package identity and crosses that barrier: `[anon:.bss]` becomes `r-xp` and contains the known qiniu/interface20 runtime signatures.
- Therefore login work should move to suppressing or diagnosing the new `System.exit(1)` path in original-package `user 10`, not to more clone-package library-path experiments.

Next root-route work:

1. Inject or hook early in the `user 10` original process, without repackaging, and intercept `System.exit(1)`, `_exit(1)`, `exit(1)`, `kill/tgkill`, and root/VM checks.
2. Prefer LSPosed/Zygisk/root-injection style for this route because the APK and package identity remain original.
3. If injection is too heavy, use root memory patching after materialization: locate the caller that invokes `System.exit(1)` and NOP/RET that callsite.
4. Keep the changed-package clone line as fallback only; it is currently blocked earlier than the multi-user route.

## 2026-06-24 root VM login route update: interface20 loop vs YWLogin native registration

Current device:

```text
serial=192.168.2.42:10001
user=10 qqreader_clone
package=com.qq.reader
dataDir=/data/user/10/com.qq.reader
SELinux=Disabled
```

### Clean run with `loop-interface20 + qqmempatch`

The root watcher successfully patches the Jiagu runtime after process creation:

```text
pid=16078 attempt=4
interface20/runtime patch base=0x7837182000
post_payload_tbnz patched
pre_materialize_cbnz_1 patched
pre_materialize_cbnz_2 patched
qiniu_b_gt patched
sdk25_b_ne patched
payload_check_mov_true patched
payload_check_ret patched
patch_once ... ready=7 mismatched=0 total=7
```

The process remains visible/resumed:

```text
ACTIVITY com.qq.reader/.activity.DefaultAliasActivity ... userId=10 pid=16078
baseDir=/data/app/~~wF2WzuCifsMyPmwM6bRUnw==/com.qq.reader-TxdWX_iClGqT7GmJAxzqzw==/base.apk
dataDir=/data/user/10/com.qq.reader
```

However the screen is pure black. `debuggerd -b 16078` shows the main thread stuck in Jiagu executable BSS:

```text
"com.qq.reader" sysTid=16078
    #00 pc 0000000000040f74  [anon:.bss]
```

Mapping this against the Jiagu executable BSS:

```text
libjiagu_vip base=0x7837182000
exec bss start=0x783725e000
pc offset in bss=0x40f74
jiagu virtual offset=0x11cf74
```

`0x11cf74` is inside `interface20 -> 0x11cf5c`:

```text
0x10d418 bl 0x11cf5c
0x10d41c tbz w0, 0, 0x10d444
0x10d424 bl 0x10d468

0x11cf5c sub sp, sp, 0x110
0x11cf74 mrs x26, tpidr_el0
```

Conclusion: the black screen is not an Activity/UI problem. It is caused by the Java `loop-interface20` patch keeping the main thread in repeated or stuck `interface20` execution while waiting for a success path that is not actually reached.

### `interface20_force_register` experiment

Added an optional root memory patch in `tools/root-dump/qqmempatch.c`:

```c
{0x10d41c, 0x36000140, ARM64_NOP, "interface20_force_register"}
```

It is not applied by default. Use the optional argument only for diagnostics:

```text
/data/local/tmp/qqmempatch <pid> 1 0 force-register
```

Purpose: force the outer `StubApp.interface20()` wrapper to continue into `0x10d468` even when `0x11cf5c` returns false:

```text
0x10d41c tbz w0, 0, 0x10d444  ->  NOP
```

Live patch result:

```text
interface20_force_register patched addr=0x783728f41c 0x36000140 -> 0xd503201f
patch_once base=0x7837182000 patched=1 ready=8 mismatched=0 total=8
success attempt=0 pid=16078
```

This unblocks the main thread and reaches app initialization:

```text
RFix.DefaultRFixApplicationLike: onCreate
testTime: application oncreate() start
QrKvConfig: initKVRootPath() -> path=/data/user/10/com.qq.reader/files/QrKvRootPath/
ARouter init success
The group [loginClient] has already been loaded
The group [loginServer] has already been loaded
```

But it then crashes at the same real blocker:

```text
No implementation found for com.yuewen.ywlogin.login.YWLoginManager
com.yuewen.ywlogin.login.YWLoginManager.getInstance()
java.lang.UnsatisfiedLinkError
  at com.yuewen.ywlogin.login.YWLoginManager.getInstance(Native Method)
  at com.yuewen.ywlogin.YWLogin.registerParameter
  at com.qq.reader.ReaderApplication.initLoginSDK
```

Updated conclusion:

- Root multi-user original-package execution is still the best route. It preserves package name, signature, native library path, and user data isolation.
- `loop-interface20` is only a timing aid. It can prevent immediate `System.exit(1)`, but if `0x11cf5c` never produces the required registration payload, it degenerates into a black-screen main-thread loop.
- Forcing `0x10d41c` proves that `0x10d468` alone is not enough. The missing part is still the payload/token list generated before `0x10d468` registers the real YWLogin natives.
- Therefore the direct login blocker is: **Jiagu runtime does not populate the YWLogin native registration payload/table, so `YWLoginManager.getInstance()` and related native methods are never registered.**

### Root-enabled solution directions

Preferred path:

1. Keep the root multi-user original-package route (`user 10`, `com.qq.reader`).
2. Stop relying on the Java `loop-interface20` as the final fix; it is useful only as a temporary window for memory patching.
3. Continue from the known payload chain, not from generic crash logs:

```text
0x11cf5c              interface20 precondition / fencrypt path
0x10d468              outer registration driver
0x10ecd4              post-payload branch
0x10efcc / 0x10efec  pre-materialize gates
0x10fb20              qiniu/result gate
0x1298d0              payload build
0x129c58              payload check
base+0x253010         payload slot
base+0x253148         token manager
0x11cd20/0x11ce50 -> 0x17ac6c/0x119fa8/0x179a70  token/register insertion chain
```

Root-specific options, ranked:

1. **In-process root memory patching, minimal branches**
   - Keep patching runtime BSS, but add diagnostics/patches after `0x10fb20` and around the insertion chain until `base+0x253148` becomes non-empty and `RegisterNatives YWLoginManager` receives non-zero method pointers.
   - This is closest to the current evidence and does not require changing app identity.

2. **Zygisk / LSPosed style early injection**
   - Current log says `zygisk64: [com.qq.reader] is on the denylist`; remove it from denylist if using Zygisk.
   - Hook before or during `JNI_OnLoad`/runtime materialization to intercept `RegisterNatives`, `FindClass`, `System.exit`, `_exit`, `tgkill`, and patch Jiagu BSS earlier than the watcher can.
   - This avoids the race and avoids Java `loop-interface20`.

3. **Direct YWLogin native table replacement**
   - Register a replacement native table for `com/yuewen/ywlogin/login/YWLoginManager`.
   - Hard part: the class has many native methods, not just `getInstance`, `pwdLogin`, `sendPhoneCode`, and `qrCodeV2`; a small stub can bypass startup but will not provide normal login unless the Java HTTP login path is reimplemented or enough native behavior is mirrored.

4. **Session/data transplant**
   - Copy a valid logged-in state from another user/profile into `/data/user/10/com.qq.reader`.
   - This can help after startup works, but it does not solve the current crash because `ReaderApplication.initLoginSDK` calls `YWLoginManager.getInstance()` before normal account data can matter.

Practical next step:

- Build a root-native diagnostic/patcher variant that watches `base+0x253010` and `base+0x253148` in the live user10 process, applies the existing 8 patches early, and then probes/patches the post-`0x10fb20` path until the YWLogin native table is actually registered.
- In parallel, prepare a Zygisk/LSPosed route only if the BSS patcher keeps losing the race or the required patch point must happen before `0x11cf5c`.

## 2026-06-24 root patcher diagnostic upgrade

Purpose:

- Avoid guessing from logcat whether Jiagu payload/token state exists.
- Make the root watcher print the key runtime slots immediately after a successful patch.
- Keep `interface20_force_register` as an explicit diagnostic mode, not a default patch.

Updated files:

```text
tools/root-dump/qqmempatch.c
tools/root-dump/qqpatch_watch.sh
```

`qqmempatch` now supports:

```text
/data/local/tmp/qqmempatch <pid> [loops] [delay_ms] [force-register] [diag-only] [diag-samples=N] [diag-interval-ms=N]
```

Default behavior:

- Apply the existing 7 runtime gate patches.
- When all patches are ready, print one diagnostic block.
- Do not apply `interface20_force_register` unless explicitly requested.

Optional modes:

```text
/data/local/tmp/qqmempatch <pid> 1 0 diag-only
/data/local/tmp/qqmempatch <pid> 1 0 force-register
/data/local/tmp/qqmempatch <pid> 1 0 diag-samples=8 diag-interval-ms=500
```

Diagnostic block:

```text
diag_begin base=0x...
diag payload_slot_253010 off=0x253010 addr=0x... value=0x...
diag payload_slot_253018 off=0x253018 addr=0x... value=0x...
diag token_manager_253148 off=0x253148 addr=0x... value=0x...
diag seed_table_253150 off=0x253150 addr=0x... value=0x...
diag registry_cache_2531b0 off=0x2531b0 addr=0x... value=0x...
diag_end
```

When `diag-samples=N` is supplied, each block is prefixed with:

```text
diag_sample index=1 total=8 interval_ms=500
```

For non-zero pointers, the tool also attempts to read the first four qwords at the pointed address:

```text
diag <slot>.ptr qwords=0x...,0x...,0x...,0x...
```

The watched slots correspond to the current suspected failure chain:

```text
base+0x253010  payload slot observed in v273/v274 payload-build work
base+0x253018  adjacent payload metadata/count slot
base+0x253148  token manager root slot
base+0x253150  seed table / fencrypt-fileCheck-sigCheck table
base+0x2531b0  registry/cache slot observed during v263 diagnostics
```

`qqpatch_watch.sh` now forwards extra options to `qqmempatch`:

```text
sh /data/local/tmp/qqpatch_watch.sh com.qq.reader /data/local/tmp/qqpatch_watch.log 1000 0.005
sh /data/local/tmp/qqpatch_watch.sh com.qq.reader /data/local/tmp/qqpatch_watch_force.log 1000 0.005 force-register
sh /data/local/tmp/qqpatch_watch.sh com.qq.reader /data/local/tmp/qqpatch_watch_sample.log 1000 0.005 diag-samples=8 diag-interval-ms=500
```

Added one-shot host helper:

```text
tools/root-dump/run_qqreader_user10_diag.ps1
```

Recommended run after ADB is back:

```powershell
powershell -ExecutionPolicy Bypass -File tools\root-dump\run_qqreader_user10_diag.ps1 -Serial 192.168.2.42:10001 -WaitSeconds 25 -DiagSamples 8 -DiagIntervalMs 500
```

Optional force-register diagnostic:

```powershell
powershell -ExecutionPolicy Bypass -File tools\root-dump\run_qqreader_user10_diag.ps1 -Serial 192.168.2.42:10001 -WaitSeconds 10 -DiagSamples 4 -DiagIntervalMs 250 -ForceRegister -VersionTag root-user10-force-register-diag
```

Local verification:

```text
bash -n tools/root-dump/qqpatch_watch.sh
aarch64-linux-android30-clang -O2 -Wall -Wextra -o .tmp\qqmempatch-arm64 tools\root-dump\qqmempatch.c
PowerShell parse OK: tools\root-dump\run_qqreader_user10_diag.ps1
compiled size: 13096 bytes
```

Runtime verification status:

- The rebuilt `.tmp\qqmempatch-arm64` was prepared locally.
- During runtime verification, `192.168.2.42:10001` moved to `offline` after ADB server restart:

```text
List of devices attached
192.168.2.42:10001 offline
192.168.2.42:33125 device
```

- Before the ADB channel dropped, `com.qq.reader` was restored for user 10 with:

```text
cmd package install-existing --user 10 com.qq.reader
Package com.qq.reader installed for user: 10
```

Next run when root VM ADB is back:

```powershell
$adb='C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe'
$serial='192.168.2.42:10001'
& $adb -s $serial push .tmp\qqmempatch-arm64 /data/local/tmp/qqmempatch
& $adb -s $serial push tools\root-dump\qqpatch_watch.sh /data/local/tmp/qqpatch_watch.sh
& $adb -s $serial shell "chmod 755 /data/local/tmp/qqmempatch /data/local/tmp/qqpatch_watch.sh"
& $adb -s $serial shell "cmd package install-existing --user 10 com.qq.reader; am start-user 10"
& $adb -s $serial shell "su -c 'rm -f /data/local/tmp/qqpatch_watch_diag.log; am force-stop --user 10 com.qq.reader'"
& $adb -s $serial shell "su -c 'sh /data/local/tmp/qqpatch_watch.sh com.qq.reader /data/local/tmp/qqpatch_watch_diag.log 1000 0.005 >/data/local/tmp/qqpatch_watch_diag.out 2>&1 &'"
& $adb -s $serial shell "am start --user 10 -n com.qq.reader/.activity.DefaultAliasActivity"
& $adb -s $serial shell "su -c 'cat /data/local/tmp/qqpatch_watch_diag.log /data/local/tmp/qqpatch_watch_diag.out 2>/dev/null'"
```

Expected useful evidence:

- If `payload_slot_253010` is non-zero but `token_manager_253148` remains empty, continue after the materialize/qiniu branch into insertion-chain patching.
- If both are empty, the remaining root cause is earlier in `0x11cf5c`/`0x1298d0` payload generation.
- If `token_manager_253148` becomes non-empty but `YWLoginManager` still native-missing, inspect the token/register insertion chain and `RegisterNatives` destination class resolution.

Static branch note for the next live run:

```text
0x10fb18 bl 0x123438
0x10fb1c cmp w0, 0x1e
0x10fb20 b.gt 0x10fc70      ; already NOPed by qqmempatch qiniu_b_gt
...
0x10fc54 bl 0x10a4b0        ; second compare after qiniu branch is bypassed
0x10fc58 cmp w0, 0
0x10fc5c csel w22, w22, w0, eq
0x10fc6c cbz w22, 0x10fcf4
0x10fc70 cleanup/return path
0x10fcf4 bl 0x116a94
0x10fd04 bl 0x110cf4
```

The next useful distinction is:

- If NOPing `0x10fb20` makes execution reach the second compare path and `payload_slot_253010` becomes non-zero, continue toward `0x11cd20/0x11ce50`.
- If `payload_slot_253010` is still zero after `qiniu_b_gt` is patched, do not patch `0x10fc6c` blindly; the empty payload means the required object was not constructed before this point.

## 2026-06-24 static slot check from existing user10 dump

Because the root VM ADB channel was unavailable, the existing dump was used to verify the new diagnostic target addresses:

```text
.tmp\qqreader-user10-bss2\maps.txt
.tmp\qqreader-user10-bss2\range-23-00000078370d1000-00000078370e0000-off_0-rw-p-_anon_.bss_.bin
```

Runtime base from the materialized user10 maps:

```text
base=0x7836e89000
```

The watched slots all map into the adjacent writable BSS range:

```text
base+0x253010 -> 0x78370dc010 -> 78370d1000-78370e0000 rw-p [anon:.bss]
base+0x253018 -> 0x78370dc018 -> 78370d1000-78370e0000 rw-p [anon:.bss]
base+0x253148 -> 0x78370dc148 -> 78370d1000-78370e0000 rw-p [anon:.bss]
base+0x253150 -> 0x78370dc150 -> 78370d1000-78370e0000 rw-p [anon:.bss]
base+0x2531b0 -> 0x78370dc1b0 -> 78370d1000-78370e0000 rw-p [anon:.bss]
```

Values from the dump:

```text
payload_slot_253010   value=0x0
payload_slot_253018   value=0x0
token_manager_253148  value=0x0
seed_table_253150     value=0x783709c088
registry_cache_2531b0 value=0x766ee88fd0
```

`seed_table_253150` points back into the executable Jiagu BSS and starts with expected seed strings:

```text
qwords=0x6b63656843676973,0x7574616e67695300,0x6b63656863206572,0x6843656c6966002e
```

Interpretation:

- The user10 original-package route definitely materializes the Jiagu runtime and has the seed table available.
- The same dump still has empty `payload_slot_253010/+0x253018` and empty `token_manager_253148`.
- This strengthens the current root cause: the remaining failure is payload/token generation or insertion, not early materialization.
- The new live `qqmempatch diag-only` should read the same slots during future runs; if root patches make `payload_slot_253010` non-zero, the investigation should move forward to the token insertion chain.

ADB status during this check:

```text
adb connect 192.168.2.42:33125 -> 10060
adb connect 192.168.2.42:10001 -> 10060
ping 192.168.2.42 -> Reply from 192.168.2.68: Destination host unreachable
```

ADB recovery notes:

- Android's adb documentation describes `offline` as the device not being connected or not responding.
- For wireless debugging, the workstation and device must be on the same wireless network, and if the connection is lost, first retry `adb connect`; if that fails, reset the adb host with `adb kill-server` and start over.
- If using Android 11+ wireless debugging, check the device's current wireless debugging port; it can change after toggling wireless debugging or rebooting the device.
- If mDNS discovery is needed, check `adb server-status`, `ADB_MDNS`, `ADB_MDNS_OPENSCREEN`, and `adb mdns track-services --proto-text`.

## 2026-06-24 offline slot analyzer

Added:

```text
tools/root-dump/analyze_jiagu_slots.py
```

Purpose:

- Reproduce the slot reads from offline dumps without one-off Python snippets.
- Support all dump formats used so far:
  - absolute address dumps: `range-XX-<start>-<end>-...bin`
  - relative Jiagu dumps: `jiagu-runtime-XX-rel_<start>-<end>-...bin` plus `jiagu-runtime-maps.txt base=...`
  - old sequential BSS dumps: `r0.bin/r1.bin/...` plus `maps.txt`
- Print every dump file that covers a slot, because older captures can contain duplicate overlapping ranges.

Usage:

```powershell
python tools\root-dump\analyze_jiagu_slots.py .tmp\qqreader-user10-bss2
python tools\root-dump\analyze_jiagu_slots.py .tmp\v247-run1-success-dump
python tools\root-dump\analyze_jiagu_slots.py .tmp\qqdump-clone-rootvm-bss-after-jiagu-run1
```

Validation:

```text
python -m py_compile tools\root-dump\analyze_jiagu_slots.py
```

Key outputs:

```text
.tmp\qqreader-user10-bss2
base=0x7836e89000
payload_slot_253010   value=0x0
payload_slot_253018   value=0x0
token_manager_253148  value=0x0
seed_table_253150     value=0x783709c088 -> ascii='sigCheck.Signature check..fileCh'
registry_cache_2531b0 value=0x766ee88fd0
```

```text
.tmp\v247-run1-success-dump
base=0x74b32ed000
payload_slot_253010   value=0x0 in both covering files
payload_slot_253018   value=0x0 in both covering files
token_manager_253148  value=0x0 in both covering files
seed_table_253150     value=0x74aa482088 in jiagu-runtime-07-rel_00248000-00257000-rw-p.bin
seed_table_253150     value=0x0 in jiagu-runtime-11-rel_000dc000-00257000-rw-p.bin
```

```text
.tmp\qqdump-clone-rootvm-bss-after-jiagu-run1
base=0x75b97c3000
payload_slot_253010   value=0x0
payload_slot_253018   value=0x0
token_manager_253148  value=0x0
seed_table_253150     value=0x0
registry_cache_2531b0 value=0x0
```

```text
.tmp\qqdump-orig-rootvm-bss-after-jiagu-run2
base=0x7837185000
payload/token/seed slots map to a BSS segment whose r23.bin dump is empty, so values are <not-dumped>.
```

Interpretation:

- User10 original-package route has a valid seed table, so it is past the clone materialization failure.
- Clone root dump has all watched slots zero, matching the known pre-materialization failure.
- v247 has overlapping copies of the same slot range; one has a non-zero seed table and one is zero. This confirms that future offline analysis must not silently pick only the first matching file.
- Across all currently available reliable dumps, `payload_slot_253010` and `token_manager_253148` remain empty. Live sampling after root patches is still required to prove whether they become non-zero transiently.

## 2026-06-24 root 后登录解法重新排序

Root 后不要继续把“重打包 clone + 伪装包名”当成唯一主线。Jiagu 对包名、签名、UID/package 关系、data dir、native library path 都敏感；root 的最大价值是尽量避免这些副作用，直接在原包名、原签名、原路径的进程里做控制。

当前登录阻塞仍然是：

```text
YWLoginManager.getInstance() native missing
RegisterNatives YWLoginManager original=0x0
payload_slot_253010=0
token_manager_253148=0
```

也就是说，不是 Java 登录 UI 本身坏了，而是 `libjiagu_vip.so` 没有产出或插入 `token=59494` 对应的 YWLogin native 注册 payload。

### 推荐优先级

1. **首选：root 多用户原包名分身**
   - 用 Android 自带 multi-user/profile 做真正的第二份 `com.qq.reader`。
   - 目标形态：`user 10 + com.qq.reader + /data/user/10/com.qq.reader`。
   - 优点：保留原包名、原签名、原 native library path，绕开 clone 包名导致的壳 materialization 失败。
   - 需要 root 做的事：早期注入或内存 patch，拦截 `System.exit/_exit/exit/kill/tgkill`、root/VM 检测、必要的 Jiagu gate。
   - 现有证据：user10 原包名路线已经能 materialize Jiagu runtime，优于 clone 包路线。

2. **最有价值的新 root 路线：原版进程取样，目标进程重放 RegisterNatives**
   - 在 root VM 里同时跑原版 `com.qq.reader` 和目标 user10/clone。
   - 用 root 注入/inline hook 记录原版进程里 `RegisterNatives` 对 `com.yuewen.ywlogin.login.YWLoginManager` 的真实 method table。
   - 记录每个 native 指针相对 `libjiagu_vip.so` 或 Jiagu runtime BSS 的 offset，而不是绝对地址。
   - 在目标进程中等 Jiagu runtime materialize 后，按相同 offset 主动调用 `RegisterNatives` 给目标 ClassLoader 里的 `YWLoginManager` 补表。
   - 这条路线不要求完整破解 `0x1298d0 -> 0x11ce50 -> 0x179a70` 的 payload 生成算法，只要求原版成功样本能给出真实函数偏移。
   - 风险：如果函数指针指向原版进程动态生成且目标进程未生成的代码段，就必须先让目标进程 materialize 同一段 runtime，或把相关 runtime 区域也迁移/复现。

3. **继续当前壳内 root patch 路线，但只作为定位和验证**
   - 当前已知阻塞点在 `interface20 -> 0x10d468 -> 0x1298d0` 附近，`payload_slot_253010` 仍为空。
   - 继续看 `0x206360(env)` / `0x1298d0` 的输入差异，确认为什么返回不满足 payload 构建条件。
   - 一旦找到单一环境返回值差异，可以 root-hotpatch 该返回值或调用点，验证 `payload_slot_253010`、`token_manager_253148` 是否变非空。
   - 这条路线最干净，但最耗时；适合在原版重放路线受阻时继续。

4. **兜底：登录态迁移，不解决 native 注册本身**
   - root 可以从原版 `/data/user/<id>/com.qq.reader` 拷贝登录态、数据库、shared_prefs、files、keystore 相关材料到分身 user。
   - 这可能解决“已有登录态复用”，但不能解决首次启动时 `YWLoginManager.getInstance()` native missing 的崩溃。
   - 因此它只能在启动稳定后做验证，不应作为当前第一解。

5. **最后才做完整静态破解壳**
   - 完整逆出 `token=59494` 生成、解密、注册链当然可行，但成本最高。
   - 当前更现实的 root 解法是“原版成功进程取真实答案，再在目标进程重放”，避免把整个 Jiagu 壳算法全拆完。

### 对 visualapp/NEXTVM 的使用边界

可以参考并迁移通用能力：

```text
Runtime.nativeLoad hook
RegisterNatives hook/logger
LSPlant/ShadowHook 基础设施
ActivityThread/LoadedApk/PackageManager/path/sysprop spoof
zygote/root 注入时机
```

不能直接期待 visualapp/NEXTVM 提供 QQ 阅读专用答案：

```text
token=59494 表结构
YWLoginManager 真实 native 指针
interface20/0x1298d0 payload 生成条件
Jiagu runtime BSS materialization 状态
```

### 下一步决策

如果设备恢复在线，优先做两个并行验证：

```powershell
powershell -ExecutionPolicy Bypass -File tools\root-dump\run_qqreader_user10_diag.ps1 -Serial 192.168.2.42:10001 -WaitSeconds 25 -DiagSamples 8 -DiagIntervalMs 500
```

以及新增一个 root hook/patcher 方向：

```text
hook RegisterNatives in original com.qq.reader
filter class=com.yuewen.ywlogin.login.YWLoginManager
dump method name/signature/fnPtr/lib-relative-offset
then replay same method table in user10 target process after Jiagu materialization
```

如果原版进程能捕获到 `YWLoginManager` 的真实 native offset，这会成为最快打通登录的路线；如果捕获不到，再回到 `0x206360/0x1298d0` 环境输入差异。

## 2026-06-24 root RegisterNatives 原版取样工具

Added:

```text
tools/root-dump/frida_register_natives_capture.js
tools/root-dump/run_qqreader_rn_capture.ps1
tools/root-dump/run_qqreader_frida_preflight.ps1
tools/root-dump/analyze_rn_capture.py
tools/root-dump/validate_ywlogin_register_table.py
tools/root-dump/frida_ywlogin_register_replay.js
tools/root-dump/run_qqreader_ywlogin_replay.ps1
tools/root-dump/test_rn_replay_toolchain.py
```

Local tool locations to preserve across context compaction:

```text
frida.exe              C:\Users\Administrator\AppData\Roaming\Python\Python312\Scripts\frida.exe
frida-ps.exe           C:\Users\Administrator\AppData\Roaming\Python\Python312\Scripts\frida-ps.exe
frida-trace.exe        C:\Users\Administrator\AppData\Roaming\Python\Python312\Scripts\frida-trace.exe
frida-server x86_64    .tmp\frida-17.3.2\frida-server-17.3.2-android-x86_64
frida-server arm64     .tmp\frida-17.3.2\frida-server-17.3.2-android-arm64
adb.exe                C:\Users\Administrator\.openclaw\workspace\apk_analysis\platform-tools\adb.exe
radare2.exe            D:\360Downloads\radare2-6.1.6-w32\radare2-6.1.6-w32\bin\radare2.exe
jadx.bat               D:\360Downloads\jadx-1.5.5\bin\jadx.bat
ndk clang arm64        C:\Users\Administrator\.openclaw\workspace\apk_analysis\ndk\29.0.13599879\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang.cmd
```

`run_qqreader_frida_preflight.ps1`, `run_qqreader_rn_capture.ps1`, and `run_qqreader_ywlogin_replay.ps1` now include the Python 3.12 user-script Frida path in their default search list.

Purpose:

- Hook `JNIEnv->RegisterNatives` directly in a root/Frida attached original `com.qq.reader` process.
- Capture the real `com.yuewen.ywlogin.login.YWLoginManager` native method table from a successful original-package run.
- Print each method's absolute pointer plus module-relative offset and `jiaguOffset`, so the target user10 process can later replay the same table after Jiagu materialization.

The Frida script hooks the standard JNI function table slot:

```text
JNI RegisterNatives index = 215
```

It emits JSON lines prefixed with:

```text
RN_CAPTURE
RN_RESULT
RN_MODULES
```

Key method targets:

```text
YWLoginManager.getInstance
YWLoginManager.pwdLogin
YWLoginManager.sendPhoneCode
YWLoginManager.qrCodeV2
StubApp.interface11/interface20
```

Recommended original-process capture once ADB/Frida are online:

```powershell
powershell -ExecutionPolicy Bypass -File tools\root-dump\run_qqreader_frida_preflight.ps1 `
  -Serial 192.168.2.42:10001 `
  -VersionTag root-frida-preflight
```

The preflight writes:

```text
.tmp/root-frida-preflight/summary.txt
.tmp/root-frida-preflight/adb-devices.txt
.tmp/root-frida-preflight/frida-version.txt
.tmp/root-frida-preflight/frida-help.txt
.tmp/root-frida-preflight/device-su-id.txt
.tmp/root-frida-preflight/frida-processes.txt
```

Current local preflight result:

```text
adbVersionFirstLine=Android Debug Bridge version 1.0.41
serialState=missing
fridaExe=<not-found>
fridaVersion=<not-found>
suRoot=<not-checked>
```

Only run capture after `serialState=device` and `fridaExe` is resolved or explicitly passed.

```powershell
powershell -ExecutionPolicy Bypass -File tools\root-dump\run_qqreader_rn_capture.ps1 `
  -Serial 192.168.2.42:10001 `
  -Mode spawn `
  -UserId 0 `
  -PackageName com.qq.reader `
  -WaitSeconds 35 `
  -VersionTag root-rn-capture-original
```

If `frida-server` is already on the device and running, omit `-StartServer`. If it needs to be pushed and started:

```powershell
powershell -ExecutionPolicy Bypass -File tools\root-dump\run_qqreader_rn_capture.ps1 `
  -Serial 192.168.2.42:10001 `
  -Mode spawn `
  -UserId 0 `
  -PackageName com.qq.reader `
  -StartServer `
  -FridaServerLocal D:\path\to\frida-server-17.3.2-android-arm64 `
  -WaitSeconds 35 `
  -VersionTag root-rn-capture-original
```

The runner writes:

```text
.tmp/root-rn-capture-original/frida-stdout.txt
.tmp/root-rn-capture-original/frida-stderr.txt
.tmp/root-rn-capture-original/rn-summary.txt
.tmp/root-rn-capture-original/ywlogin-register-table.json
.tmp/root-rn-capture-original/ywlogin-register-table-validation.txt
.tmp/root-rn-capture-original/logcat.txt
```

Manual summary command:

```powershell
python tools\root-dump\analyze_rn_capture.py .tmp\root-rn-capture-original\frida-stdout.txt --out-json .tmp\root-rn-capture-original\ywlogin-register-table.json
python tools\root-dump\validate_ywlogin_register_table.py .tmp\root-rn-capture-original\ywlogin-register-table.json
```

Useful output looks like:

```text
capture[1] class=com.yuewen.ywlogin.login.YWLoginManager nMethods=...
  [0] getInstance ()Lcom/yuewen/ywlogin/login/YWLoginManager; fn=0x... module=libjiagu_vip.so offset=0x... jiaguOffset=0x...
  [1] pwdLogin (...)V fn=0x... module=null offset=null jiaguOffset=0x...
```

Decision rules:

- If original `YWLoginManager` entries point into `libjiagu_vip.so` or a materialized Jiagu BSS range with stable `jiaguOffset`, build a replay helper for user10 target:
  - wait for target Jiagu runtime materialization,
  - resolve target `libjiagu_vip.so` base,
  - compute `targetJiaguBase + jiaguOffset`,
  - call `RegisterNatives` for target ClassLoader's `YWLoginManager`.
- If original entries point to generated memory that does not exist in target even after materialization, continue root patching the target materialization path before replay.
- If original process never emits `YWLoginManager`, then either capture timing is too late/early or the login SDK registers only when a login path is triggered; rerun with UI interaction or attach mode after opening login.

### Replay PoC

After `ywlogin-register-table.json` exists, the replay runner generates a Frida script with the captured table embedded, attaches to the target process, waits for `libjiagu_vip.so` and `YWLoginManager`, then calls JNI `RegisterNatives` with method pointers computed from:

```text
target libjiagu_vip.so base + captured jiaguOffset
```

The replay script does not rely only on Frida's default ClassLoader. It first tries the default factory, then enumerates all Java ClassLoaders and uses the loader that can resolve `com.yuewen.ywlogin.login.YWLoginManager`.

Both PowerShell runners now use `-TargetPid` for attach mode. `-Pid` is retained as an alias, but the backing variable is no longer named `$Pid` because PowerShell reserves `$PID` as a read-only process-id variable.

Target user10 replay command:

```powershell
powershell -ExecutionPolicy Bypass -File tools\root-dump\run_qqreader_ywlogin_replay.ps1 `
  -Serial 192.168.2.42:10001 `
  -UserId 10 `
  -PackageName com.qq.reader `
  -CaptureJson .tmp\root-rn-capture-original\ywlogin-register-table.json `
  -WaitSeconds 25 `
  -VersionTag root-ywlogin-register-replay
```

Replay dry-run after a capture table exists:

```powershell
powershell -ExecutionPolicy Bypass -File tools\root-dump\run_qqreader_ywlogin_replay.ps1 `
  -CaptureJson .tmp\root-rn-capture-original\ywlogin-register-table.json `
  -VersionTag root-ywlogin-register-replay-dryrun `
  -DryRun
```

This validates the table, generates `frida_ywlogin_register_replay.generated.js`, runs `node --check` when `node` is available, and exits before ADB/Frida work.

Expected useful output:

```text
RN_REPLAY_METHOD index=... name=getInstance ... jiaguOffset=0x... fn=0x...
RN_REPLAY_RESULT class=com.yuewen.ywlogin.login.YWLoginManager count=... result=0
```

Interpretation:

- `RN_REPLAY_RESULT result=0` means JNI accepted the table. Then verify logcat no longer contains `No implementation found for com.yuewen.ywlogin.login.YWLoginManager.getInstance()`.
- `RN_REPLAY_WAIT reason=libjiagu_vip.so-missing` means target process has not loaded/materialized Jiagu yet.
- `RN_REPLAY_WAIT reason=class-missing` means the attach point is too early, or the ClassLoader is not the default Frida Java loader.
- A crash after successful replay means the offsets are structurally valid but the target runtime state is still incomplete; then return to target materialization patching.
- Before replay, `run_qqreader_ywlogin_replay.ps1` runs `validate_ywlogin_register_table.py --strict-startup`; it will stop if `getInstance` has no replayable `jiaguOffset`.

Local verification:

```text
python -m py_compile tools/root-dump/analyze_rn_capture.py
python -m py_compile tools/root-dump/validate_ywlogin_register_table.py
PowerShell parse OK: tools/root-dump/run_qqreader_rn_capture.ps1
node --check tools/root-dump/frida_register_natives_capture.js
PowerShell parse OK: tools/root-dump/run_qqreader_ywlogin_replay.ps1
node --check tools/root-dump/frida_ywlogin_register_replay.js
python tools/root-dump/test_rn_replay_toolchain.py
PowerShell parse OK: tools/root-dump/run_qqreader_frida_preflight.ps1
powershell -ExecutionPolicy Bypass -File tools/root-dump/run_qqreader_frida_preflight.ps1 -Serial 192.168.2.42:10001 -VersionTag root-frida-preflight-local
```

The local dry-run test currently covers:

```text
sample RN_CAPTURE parsing
strict startup/login validation
empty table strict-startup failure
generated replay JS node --check
run_qqreader_ywlogin_replay.ps1 -DryRun
```

Current runtime status:

```text
adb devices -> no online devices
```

So the tooling is prepared locally, but live capture is pending device/Frida availability.

## 2026-06-25 LSPosed 模块突破：QQ 阅读首次成功启动

### 背景

Frida server 在 root VM 上不可用（`Unsupported Android linker`），改用 LSPosed Zygisk 模块注入。

### 新增工具

```text
tools/lsposed-rn-capture/          LSPosed 模块 APK
tools/xposed-api-stub/             Xposed API 编译桩
tools/magisk-auto-adb/             Magisk 自动开启无线 ADB 模块
```

LSPosed 模块核心能力：

- 绝对路径加载 `libmultiapp-native.so`
- `setSuppressSelfSigkill(true)` 抑制壳自杀
- `gotHookLibrary("libjiagu_vip.so")` 安装 GOT hook 和 self-kill patch
- `registerBusinessStubs` 注册 YWLoginManager stub 方法
- `registerQrencryptStubs` 注册 FockKeyPoolCache stub 方法
- `registerOnlineChapterStateStubs` 注册 OnlineChapterDownloadTask stub
- `registerOnlineChapterDownloadFallbackStubs` 注册下载 fallback

### 关键突破链

v284：LSPosed 模块首次注入成功，但 `nativeOk=false`（native lib 未加载）

v285：绝对路径加载 native lib，`nativeOk=true rnOk=true`，但进程被壳自杀（signal 9）

v286：`setSuppressSelfSigkill(true)` 生效，进程存活到 `StubApp RegisterNatives count=10`，但随后仍被杀

v287：`gotHookLibrary("libjiagu_vip.so")` 延迟重试，self-kill patch（`0x11cb84` BL→NOP）成功，进程存活到 `ReaderApplication.onCreate`，但 `JiaguQiniuCheck result=30` 触发 `0x10fb20 b.gt` 跳过 token 注册链

v288：NOP `0x10fb20`（qiniu gate），但 `0x10fc6c cbz` 仍阻塞

v289：NOP `0x10fc6c`（second compare gate），`buildVectorCalls/gateCalls` 仍为 0，壳内 gate 太多

v290：切换到 P0 路线 — `registerBusinessStubs` + `registerQrencryptStubs` + `registerAllMissingNativeMethods`

### v290 成功结果

```text
设备：192.168.2.52:10001（root arm64 VM）
包名：com.qq.reader（原版包名，非 clone）
PID：16955

YWLoginManager 注册成功：
  getInstance ✅
  registerParameter ✅
  resetParameter ✅
  setDefaultParameters ✅
  getDefaultParameters ✅
  getCommonParamaters ✅
  saveParameters ✅
  refreshParameters ✅

进程存活：pidof com.qq.reader = 16955 ✅
Activity 显示：Displayed com.qq.reader/.activity.DefaultAliasActivity: +8s461ms ✅
无 FATAL EXCEPTION ✅
无 No implementation found ✅
```

### 技术方案总结

最终生效的组合：

1. LSPosed 模块注入 QQ 阅读进程（非 clone 包名）
2. 绝对路径加载 `libmultiapp-native.so`
3. `setSuppressSelfSigkill(true)` + self-kill patch `0x11cb84`
4. `gotHookLibrary("libjiagu_vip.so")` 安装 GOT hook + self-kill patch
5. `registerBusinessStubs` 注册 YWLoginManager 全部 native 方法
6. `registerQrencryptStubs` 注册 FockKeyPoolCache 等加密方法
7. `registerOnlineChapterStateStubs` + `registerOnlineChapterDownloadFallbackStubs` 注册阅读链路方法
8. `registerAllMissingNativeMethods` 补全其他缺失 native

### 待解决

- 登录功能需要真实 native 实现（当前是 stub/fallback）
- 需要在真实设备上验证完整登录流程
- Magisk auto-adb 模块已打包 `.tmp/auto_adb_wireless.zip`，待刷入验证

## 2026-06-25 root user10 self-kill patch regression check

User pointed out that the previous anti-crash/self-kill script from this document may not have been applied in the root user10 diagnostic path. This was a valid catch.

Current `tools/root-dump/qqmempatch.c` now includes the old required self-kill callsite patch:

```text
offset=0x11cb84
expected=0x97ffb823
replacement=0xd503201f
name=self_kill_callsite
```

Local rebuilt patcher:

```text
.tmp\qqmempatch-arm64 size=13184
```

Device was reconnected:

```text
adb connect 192.168.2.52:10001
adb devices -l -> 192.168.2.52:10001 device product:marlin model:Pixel_4
```

Baseline with self-kill restored:

```text
.tmp\root-user10-live-diag-selfkill-run1\watcher.txt
self_kill_callsite patched ... 0x97ffb823 -> 0xd503201f
patch_once ... patched=8 ready=8 mismatched=0 total=8
payload_slot_253010 value=0x7009a09b50
registry_cache_2531b0 value=0x7149a62390
token_manager_253148 value=0x0
```

This proves the old self-kill patch is a necessary condition: after restoring it, payload and registry slots materialize instead of staying zero.

Follow-up with forced interface20 register branch:

```text
powershell -ExecutionPolicy Bypass -File tools\root-dump\run_qqreader_user10_diag.ps1 `
  -Serial 192.168.2.52:10001 `
  -WaitSeconds 8 `
  -DiagSamples 8 `
  -DiagIntervalMs 250 `
  -LogcatLines 1600 `
  -ForceRegister `
  -VersionTag root-user10-live-diag-selfkill-force-register-run2
```

Result:

```text
interface20_force_register patched ... 0x36000140 -> 0xd503201f
patch_once ... patched=9 ready=9 mismatched=0 total=9
payload_slot_253010 value=0x7009a09b50
registry_cache_2531b0 value=0x7149a61b50
token_manager_253148 value=0x0

No implementation found for com.yuewen.ywlogin.login.YWLoginManager
  com.yuewen.ywlogin.login.YWLoginManager.getInstance()
Process: com.qq.reader, PID: 20786
```

Conclusion:

- The old anti-crash `self_kill_callsite` patch was indeed missing from the root diag path before this fix.
- Restoring it moves execution forward enough for `payload_slot_253010` and `registry_cache_2531b0` to become non-zero.
- `-ForceRegister` at `0x10d41c` is still not sufficient: `token_manager_253148` stays zero and `YWLoginManager.getInstance()` remains native-missing.
- The root memory-patch path can keep diagnosing Jiagu gates, but the practical login route should use the LSPosed/root injected native-registration fallback or the original-process `RegisterNatives` capture/replay path.
