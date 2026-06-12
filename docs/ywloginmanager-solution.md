# YWLoginManager 阻塞问题解决方案

最后更新：2026-06-06

## 问题根因

`System.loadLibrary("jiagu_vip")` 在 Android 16 上静默失败——2ms 内"成功"返回但不触发 dlopen 和 JNI_OnLoad。

证据：
- `.so` 文件存在（exists=true）
- `nativeLibraryDir` 正确
- `System.loadLibrary` 不抛异常
- 但 JNI_OnLoad 从未调用（2ms 完成 = 没有真正加载）

根因：Android 16 的 hidden API 限制阻止了 `Runtime.nativeLoad`（System.loadLibrary 内部调用）。

根本原因链：
```
DEX 注入 System.loadLibrary("jiagu_vip") 可能失败
  → libjiagu_vip.so 未被 ART 加载
  → JNI_OnLoad 未执行
  → RegisterNatives 未调用
  → StubApp 的真实 native 方法未注册
  → 我们的 registerStubMethods 注册了 stub 空壳
  → StubApp.load() 调用 stub interface20（返回 true，但不做任何事）
  → 壳的业务初始化从未发生
  → YWLoginManager.getInstance() 未注册 → crash
```

## 解决方案（按推荐优先级）

### 方案 A：直接调用 System.loadLibrary（推荐）

**思路**：不依赖 DEX 注入，在 LoaderFactory 中直接触发 .so 加载。

**关键问题**：`Runtime.nativeLoad` 在 Android 16 上被 hidden API 阻止，JNI 调用失败。

**解决方法**：通过 guest ClassLoader 中的 StubApp 类调用 `System.loadLibrary`。
`System.loadLibrary` 内部使用 caller 类的 ClassLoader。如果 caller 是 guest ClassLoader 中的 StubApp，
ART 会将 .so 绑定到 guest ClassLoader 命名空间。

**实现**：

```kotlin
// LoaderFactory.kt — preloadPackerLibViaGuestClassLoader 中

// 在 registerStubMethods 之前，通过 StubApp 类调用 System.loadLibrary
try {
    // 通过 guest ClassLoader 加载 StubApp 类
    val stubAppClass = Class.forName("com.stub.StubApp", false, guestCl)
    
    // 用反射调用 System.loadLibrary("jiagu_vip")
    // 关键：从 StubApp 类上下文调用，使 ClassLoader 为 guestCl
    val loadLibrary = Class.forName("java.lang.System", false, guestCl)
        .getDeclaredMethod("loadLibrary", String::class.java)
    loadLibrary.invoke(null, "jiagu_vip")
    
    logD("  preloadPackerLib: System.loadLibrary('jiagu_vip') succeeded")
} catch (e: Throwable) {
    logD("  preloadPackerLib: System.loadLibrary failed: ${e.message}")
}
```

**风险**：`System.loadLibrary` 内部调用 `Runtime.nativeLoad`，可能也被 hidden API 阻止。
但 `System.loadLibrary` 是公开 API，不应该被阻止。需要验证。

**如果 System.loadLibrary 也被阻止**：使用方案 B。

---

### 方案 B：通过 JNI 直接调用 android_dlopen_ext（兜底）

**思路**：绕过 Java 层，直接在 native 层通过 `android_dlopen_ext` 加载 .so，
然后通过 JNI 调用 `Runtime.nativeLoad` 的 ART 内部路径。

**实现**：

```cpp
// native-hook.cpp 新增

// 直接调用 ART 的 JavaVMExt::LoadNativeLibrary
// 这是 Runtime.nativeLoad 的底层实现，绕过 Java hidden API
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeLoadViaArt(
    JNIEnv* env, jclass clazz, jstring libPath, jobject classLoader)
{
    // 获取 JavaVM
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    
    // 直接调用 dlopen
    const char* path = env->GetStringUTFChars(libPath, nullptr);
    void* handle = dlopen(path, RTLD_NOW);
    env->ReleaseStringUTFChars(libPath, path);
    
    if (!handle) return JNI_FALSE;
    
    // 找到 JNI_OnLoad 并调用
    auto jniOnLoad = (jint (*)(JavaVM*, void*))dlsym(handle, "JNI_OnLoad");
    if (jniOnLoad) {
        jint result = jniOnLoad(vm, nullptr);
        return result >= 0 ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_TRUE;
}
```

**问题**：这又回到了 dlopen 路径，ART 不做 ClassLoader 绑定。

---

### 方案 C：注册 stub native 方法（最保守兜底）

**思路**：不尝试加载壳的 .so，直接为所有缺失的 native 方法注册 stub 实现。

**实现**：

```kotlin
// LoaderFactory.kt — 在 StubApp.load() 之后

// 注册业务 native 方法的 stub 实现
try {
    bridge.registerBusinessNativeStubs(guestCl)
    logD("  preloadPackerLib: business native stubs registered")
} catch (e: Throwable) {
    logD("  preloadPackerLib: business stub registration failed: ${e.message}")
}
```

```cpp
// native-hook.cpp 新增

// YWLoginManager.getInstance() stub
static jobject JNICALL stub_ywlogin_getInstance(JNIEnv* env, jclass clazz) {
    // 通过 guest ClassLoader 创建 YWLoginManager 实例
    jclass ywClass = env->FindClass("com/yuewen/ywlogin/login/YWLoginManager");
    if (ywClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    
    // 尝试调用构造函数
    jmethodID ctor = env->GetMethodID(ywClass, "<init>", "()V");
    if (ctor == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        // 尝试带参数的构造函数
        ctor = env->GetMethodID(ywClass, "<init>", "(Landroid/content/Context;)V");
        if (ctor == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return nullptr;
        }
    }
    
    return env->NewObject(ywClass, ctor);
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterBusinessNativeStubs(
    JNIEnv* env, jclass clazz, jobject classLoader)
{
    // 注册 YWLoginManager.getInstance()
    jclass ywClass = env->FindClass("com/yuewen/ywlogin/login/YWLoginManager");
    if (ywClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        // 类还没加载，通过 guest ClassLoader 加载
        jclass clClass = env->FindClass("java/lang/ClassLoader");
        jmethodID loadClass = env->GetMethodID(clClass, "loadClass", 
            "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring name = env->NewStringUTF("com.yuewen.ywlogin.login.YWLoginManager");
        ywClass = (jclass)env->CallObjectMethod(classLoader, loadClass, name);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(clClass);
    }
    
    if (ywClass == nullptr) {
        LOGW("registerBusinessNativeStubs: YWLoginManager class not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
    
    JNINativeMethod methods[] = {
        {const_cast<char*>("getInstance"), 
         const_cast<char*>("()Lcom/yuewen/ywlogin/login/YWLoginManager;"), 
         (void*)stub_ywlogin_getInstance}
    };
    
    jint result = env->RegisterNatives(ywClass, methods, 1);
    LOGI("registerBusinessNativeStubs: YWLoginManager.getInstance result=%d", result);
    env->DeleteLocalRef(ywClass);
    
    return result == JNI_OK ? JNI_TRUE : JNI_FALSE;
}
```

**风险**：`stub_ywlogin_getInstance` 返回的实例可能缺少内部状态，
后续调用可能 NPE。但至少不会在 `getInstance()` 处崩溃。

---

## 推荐执行顺序

1. **~~先验证 DEX 注入~~**：已验证——DEX 注入成功，但 System.loadLibrary 在 Android 16 上静默失败

2. **~~如果 DEX 注入失败：实现方案 A~~**：已实现——System.loadLibrary 直接调用同样静默失败

3. **实现方案 C（注册 stub native 方法）**：当前唯一可行的止血方案

4. **长期方案**：研究 System.loadLibrary 在 Android 16 上静默失败的根因；逆向 libjiagu_vip.so 的 interface20 实现

## 已尝试方案汇总

| 方案 | 结果 | 原因 |
|---|---|---|
| dlopen + 手动 JNI_OnLoad | JNI_OnLoad 返回 65540 | ART 不做 ClassLoader 绑定 |
| System.loadLibrary（直接调用） | 静默失败（2ms） | Android 16 hidden API 限制 |
| JiaguLoader.loadLibrary()（DEX 注入） | 静默失败（1ms） | 同上 |
| loadLibraryForGuest（Runtime.nativeLoad JNI） | 失败 | nativeLoad method not found |
| 全局 GOT hook + System.loadLibrary | GOT hook 生效，loadLibrary 仍然失败 | System.loadLibrary 内部问题 |
| NOP 整个 JNI_OnLoad 函数体 | 已实现 | loadLibrary 不加载 .so，NOP 无意义 |

## 关键文件

| 文件 | 修改内容 |
|---|---|
| `core/hook/src/main/cpp/native-hook.cpp` | 新增 `stub_ywlogin_getInstance` + `nativeRegisterBusinessNativeStubs` |
| `core/hook/src/main/java/com/multiapp/core/hook/NativeHookBridge.kt` | 新增 `registerBusinessNativeStubs()` 方法 |
| `core/loader/src/main/java/com/multiapp/core/loader/LoaderFactory.kt` | 在 StubApp.load() 后调用 stub 注册 |
