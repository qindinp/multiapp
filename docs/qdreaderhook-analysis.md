# QDReaderHook 模块分析结论

## 模块信息
- 名称：QDReadHook (起点阅读 Xposed 模块)
- 版本：3.3.6(2136)
- 作者：cn.xihan.qdds (希涵)
- 目标 app：com.qidian.QDReader (起点阅读)
- 反编译工具：jadx 1.5.5
- T2 解码工具：tools/qqreader-offline-patch/DecodeT2Strings.java

## 文件位置
- APK：tmp_apks/cn.xihan.qdds/QDReaderHook_3.3.6.apk
- 反编译源码：.tmp/qdreaderhook-decompiled/sources/（4594 个 Java 文件）
- T2 解码结果：.tmp/qdreaderhook-t2-all.txt（1514 行）
- T2 解码工具类：.tmp/qdreaderhook336/tools-classes/DecodeT2Strings.class

## T2 解码关键字符串

### Cookie 管理
```
LlPT3/toString; → cookie, getCookie()Ljava/lang/String;, report_cookie, getReport_cookie()Z
LlPT3/com7; → GRAB_COOKIE, grab_cookie
LlPT3/lpt8; → "请先登录和开启抓取cookie"
LLPT3/instanceof; onCreate → cookie, report_cookie_trigger
LLPT3/native; afterHookedMethod → Cookie (HTTP header)
LLpt4/implements; invoke → Cookie (HTTP header)
```

### Fock 签名
```
LlPT3/com9; → com.yuewen.fock.Fock, com.qidian.QDReader.component.util.FockUtil
LlPT3/lpt2; → fockUtil, getFockUtil()Ljava/lang/Class;, fock, getFock()Ljava/lang/Class;
LlpT3/hey_you; → sign, signParams
```

### 自动签到
```
LlPT3/com7; → AUTO_SIGN_IN, auto_sign_in
```

### 其他功能
```
LLPT3/instanceof; onCreate → hide_bottom_nav_list, hide_selection_list, hide_me_list, hide_read_page_list
LLPT3/instanceof; onCreate → read_theme_path_redirect, RedirectTheme, custom_bookshelf_style, start_image_url_sync
LLPT3/native; beforeHookedMethod → BookStoreItem, BookList, DownloadTips, ReaderThemeEntity
LLPT3/native; afterHookedMethod → QDChapterCommentModule, mMoreIconStyle, baseBookInfo, bookId, bookName, bookStatus
LLPt3/package; beforeHookedMethod → "QDReader process launched ...", "QDReaderHook version: 3.3.6(2136)"
LlPT3/com1; → TEENAGER_MODE_REQUEST, teenager_mode_request
LlPT3/hi_pls_stop; → adCodeId (广告相关)
```

## 反编译代码结构

### 混淆规则
- 包名混淆为短名：lPT3, LPt3, p012for, i1, o1 等
- 类名混淆为关键字：com7, com9, toString, Ccase, Ccontinue 等
- 字符串常量被 T2 编码为长整数（如 -93326180766622），运行时解码
- 方法名混淆为：assert, break, case, catch, class, const 等

### 关键类
- cn.xihan.qdds.HookEntry — Xposed 入口，实现 IXposedHookLoadPackage
- lpT3/Application — 主 hooker 类
- lPT3/com7 — 功能开关（GRAB_COOKIE, AUTO_SIGN_IN 等）
- lPT3/com9 — 类查找器（Fock, FockUtil 等）
- lPT3/lpt2 — Fock 工具类
- lPT3/toString — 配置类（cookie, report_cookie 等）
- lPT3/lpt8 — 功能实现（cookie 抓取提示）
- lPpT3/hey_you — 请求签名（sign, signParams）

## 对 QQ 阅读登录的价值

### 有参考价值
- com.yuewen.fock.Fock 签名类与 QQ 阅读相同
- Cookie 抓取逻辑可参考
- 请求签名逻辑可参考

### 不能直接使用
- 这是起点 app 的代码，不是 QQ 阅读的
- 模块需要用户先登录才能抓取 cookie（"请先登录和开启抓取cookie"）
- ywguid/ykkey 不是由 Fock 类生成，而是由壳的 native 代码在 JNI_OnLoad 时生成
- 模块是 Xposed 模块，不能直接在 clone 中使用
- 反编译代码高度混淆，难以提取具体实现

### 结论
QDReaderHook 模块**不能直接解决 QQ 阅读登录问题**。它是一个已登录状态下的 cookie 抓取和签名工具，不是登录凭证生成器。

## 反编译命令

```bash
# 使用 jadx 反编译
"D:/360Downloads/jadx-1.5.5/bin/jadx" -d ".tmp/qdreaderhook-decompiled" "tmp_apks/cn.xihan.qdds/QDReaderHook_3.3.6.apk"

# 使用 T2 解码器
cd /tmp/t2classes && java -cp ".;<dexlib2.jar>;<guava.jar>" -Dfile.encoding=UTF-8 DecodeT2Strings "<dex-path>" "<filter>"
```

依赖 jar：
- dexlib2-2.5.2.jar: ~/.gradle/caches/modules-2/files-2.1/org.smali/dexlib2/2.5.2/
- guava-27.1-android.jar: ~/.gradle/caches/modules-2/files-2.1/com.google.guava/guava/27.1-android/
