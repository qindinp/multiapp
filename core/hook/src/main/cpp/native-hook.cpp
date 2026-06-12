/**
 * MultiApp Native Hook Library
 *
 * ShadowHook-based inline hooks for libc file I/O functions to implement
 * native-level path redirection for guest app file isolation.
 *
 * Hooks (installed via ShadowHook inline hooking):
 *   - open/openat     → redirect file paths to sandbox
 *   - access          → check sandbox path instead
 *   - stat/lstat      → stat sandbox path instead
 *   - readlink        → return sandbox path
 *   - fopen           → redirect file paths to sandbox, spoof /proc files
 *   - mkdir           → redirect directory creation to sandbox
 *   - unlink          → redirect file deletion to sandbox
 *   - rename          → redirect file/directory rename to sandbox
 *   - __system_property_get → spoof device properties
 *   - ptrace          → bypass anti-debug PTRACE_TRACEME checks (libc.so)
 *   - dlopen          → hide hook framework libraries (libdl.so)
 *
 * Architecture:
 *   Java (NativeHookBridge.kt)
 *     ↓ JNI
 *   native-hook.cpp (this file)
 *     ↓ ShadowHook inline hooking (shadowhook_hook_sym_name)
 *   hooked libc functions ←→ original libc functions (via saved pointers)
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <fcntl.h>
#include <unistd.h>
#include <link.h>

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cstdarg>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <mutex>
#include <shared_mutex>
#include <cerrno>

// ShadowHook — Android 16 compatible inline hook library (ByteDance)
#include "shadowhook.h"

// LSPlant — ART method hooking framework
#include "lsplant.hpp"

#define LOG_TAG "MultiApp-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== Global State ====================

static bool g_initialized = false;
static bool g_hooks_installed = false;
static std::shared_mutex g_mutex;

// Path redirection: source prefix → target prefix
static std::unordered_map<std::string, std::string> g_path_redirects;

// Property spoofing: property name → spoofed value
static std::unordered_map<std::string, std::string> g_property_spoofs;

// Hidden paths: paths that should appear non-existent
static std::unordered_set<std::string> g_hidden_paths;

// /proc/self spoofing
static int g_spoofed_pid = -1;
static std::string g_spoofed_package_name;

// Host package data dir prefix
static std::string g_host_data_prefix;

// Virtual data root
static std::string g_virtual_data_root;

// QQ Reader qrencrypt keypool cache. The original 360 shell registers these
// native methods from interface11(); in the clone runtime we provide the same
// storage contract so libfock can be initialized with real server keypools.
static std::shared_mutex g_fock_keypool_mutex;
static std::unordered_map<std::string, std::unordered_map<std::string, std::string>> g_fock_keypools;

// ==================== Original Function Pointers ====================

typedef int (*orig_open_t)(const char*, int, ...);
typedef int (*orig_openat_t)(int, const char*, int, ...);
typedef int (*orig_access_t)(const char*, int);
typedef int (*orig_stat_t)(const char*, struct stat*);
typedef int (*orig_lstat_t)(const char*, struct stat*);
typedef ssize_t (*orig_readlink_t)(const char*, char*, size_t);
typedef FILE* (*orig_fopen_t)(const char*, const char*);
typedef int (*orig_mkdir_t)(const char*, mode_t);
typedef int (*orig_unlink_t)(const char*);
typedef int (*orig_rename_t)(const char*, const char*);
typedef int (*orig_system_property_get_t)(const char*, char*);
typedef long (*orig_ptrace_t)(int, pid_t, void*, void*);
typedef void* (*orig_dlopen_t)(const char*, int);
typedef void (*orig_exit_t)(int);
typedef void (*orig_abort_t)();

static orig_open_t real_open = nullptr;
static orig_openat_t real_openat = nullptr;
static orig_access_t real_access = nullptr;
static orig_stat_t real_stat = nullptr;
static orig_lstat_t real_lstat = nullptr;
static orig_readlink_t real_readlink = nullptr;

// 完整性校验重定向：壳的 JNI_OnLoad 读 APK 校验 DEX 时，重定向到原始 APK
static thread_local bool g_integrity_redirect_active = false;
static std::string g_integrity_redirect_from;  // 修改后的 APK 路径
static std::string g_integrity_redirect_to;    // 原始 APK 路径
static orig_fopen_t real_fopen = nullptr;
static orig_mkdir_t real_mkdir = nullptr;
static orig_unlink_t real_unlink = nullptr;
static orig_rename_t real_rename = nullptr;
static orig_system_property_get_t real_system_property_get = nullptr;
static orig_ptrace_t real_ptrace = nullptr;
static orig_dlopen_t real_dlopen = nullptr;
static orig_exit_t real_exit = nullptr;
static orig_exit_t real__exit = nullptr;
static orig_abort_t real_abort = nullptr;

// LSPlant bypass flag: when true, hooked_dlopen allows lsplant loading
// This is needed because nativeInitLsplant calls dlopen("liblsplant.so")
// which would otherwise be blocked by the anti-detection hook
static thread_local bool g_lsplant_dlopen_bypass = false;

// ==================== Path Redirection Logic ====================

/**
 * Check if a path is hidden (thread-safe via shared_mutex).
 */
static bool is_path_hidden(const char* path) {
    if (path == nullptr) return false;
    std::shared_lock<std::shared_mutex> lock(g_mutex);
    return g_hidden_paths.count(std::string(path)) > 0;
}

/**
 * Check if a path needs redirection and return the redirected path.
 * Returns empty string if no redirection needed. Thread-safe.
 */
static std::string redirect_path(const char* path) {
    if (path == nullptr || path[0] == '\0') return "";

    // Normalize /data/user/0/ to /data/data/ before lock acquisition
    std::string path_str(path);
    if (path_str.compare(0, 13, "/data/user/0/") == 0) {
        path_str = "/data/data/" + path_str.substr(13);
    }

    // 完整性校验重定向：JNI_OnLoad 期间，壳读 APK 校验 DEX → 重定向到原始 APK
    if (g_integrity_redirect_active && !g_integrity_redirect_from.empty()) {
        if (path_str == g_integrity_redirect_from) {
            LOGI("redirect_path: integrity redirect %s -> %s", path, g_integrity_redirect_to.c_str());
            return g_integrity_redirect_to;
        }
    }

    std::shared_lock<std::shared_mutex> lock(g_mutex);
    if (g_path_redirects.empty()) return "";

    // Check each registered redirect prefix (longest match wins)
    std::string best_from;
    std::string best_to;
    for (const auto& redirect : g_path_redirects) {
        if (path_str.compare(0, redirect.first.length(), redirect.first) == 0) {
            if (redirect.first.length() > best_from.length()) {
                best_from = redirect.first;
                best_to = redirect.second;
            }
        }
    }

    if (!best_from.empty()) {
        return best_to + path_str.substr(best_from.length());
    }

    return "";
}

/**
 * Check if a path is a /proc/self path that needs spoofing.
 */
static bool is_proc_self_path(const char* path) {
    if (path == nullptr) return false;
    return strncmp(path, "/proc/self/", 11) == 0;
}

// ==================== Hook Implementations ====================

/**
 * Hooked open() — redirects file paths to sandbox, hides paths.
 */
static int hooked_open(const char* path, int flags, ...) {
    // Check hidden paths
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("open: %s -> %s", path, actual_path);
    }

    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
        return real_open(actual_path, flags, mode);
    }
    return real_open(actual_path, flags);
}

/**
 * Hooked openat() — redirects file paths to sandbox, hides paths.
 */
static int hooked_openat(int dirfd, const char* path, int flags, ...) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("openat: %s -> %s", path, actual_path);
    }

    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode_t mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
        return real_openat(dirfd, actual_path, flags, mode);
    }
    return real_openat(dirfd, actual_path, flags);
}

/**
 * Hooked access() — checks sandbox path instead, hides paths.
 */
static int hooked_access(const char* path, int mode) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_access(actual_path, mode);
}

/**
 * Hooked stat() — stats sandbox path instead, hides paths.
 */
static int hooked_stat(const char* path, struct stat* buf) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_stat(actual_path, buf);
}

/**
 * Hooked lstat() — lstats sandbox path instead, hides paths.
 */
static int hooked_lstat(const char* path, struct stat* buf) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_lstat(actual_path, buf);
}

/**
 * Hooked readlink() — returns sandbox path instead.
 */
static ssize_t hooked_readlink(const char* path, char* buf, size_t bufsiz) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    // Spoof /proc/self/exe
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/exe") == 0) {
        std::shared_lock<std::shared_mutex> lock(g_mutex);
        if (!g_spoofed_package_name.empty()) {
            // Return a fake exe path
            std::string fake = "/system/bin/app_process64";
            size_t len = fake.length();
            if (len > bufsiz) len = bufsiz;
            memcpy(buf, fake.c_str(), len);
            return static_cast<ssize_t>(len);
        }
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    return real_readlink(actual_path, buf, bufsiz);
}

/**
 * Hooked fopen() — redirects file paths to sandbox, hides paths.
 */
static FILE* hooked_fopen(const char* path, const char* mode) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return nullptr;
    }

    // Spoof /proc/self/cmdline
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/cmdline") == 0) {
        std::shared_lock<std::shared_mutex> lock(g_mutex);
        if (!g_spoofed_package_name.empty()) {
            // Create a temp file with spoofed cmdline
            FILE* tmp = tmpfile();
            if (tmp) {
                fwrite(g_spoofed_package_name.c_str(), 1, g_spoofed_package_name.length() + 1, tmp);
                fseek(tmp, 0, SEEK_SET);
                return tmp;
            }
        }
    }

    // Spoof /proc/self/status — replace TracerPid with 0
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/status") == 0) {
        FILE* real_status = real_fopen(path, mode);
        if (real_status) {
            FILE* tmp = tmpfile();
            if (tmp) {
                char line[256];
                while (fgets(line, sizeof(line), real_status)) {
                    if (strncmp(line, "TracerPid:", 10) == 0) {
                        fputs("TracerPid:\t0\n", tmp);
                    } else {
                        fputs(line, tmp);
                    }
                }
                fclose(real_status);
                fseek(tmp, 0, SEEK_SET);
                LOGD("fopen: /proc/self/status TracerPid spoofed to 0");
                return tmp;
            }
            fclose(real_status);
        }
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("fopen: %s -> %s", path, actual_path);
    }

    return real_fopen(actual_path, mode);
}

/**
 * Hooked mkdir() — redirects directory creation to sandbox.
 */
static int hooked_mkdir(const char* path, mode_t mode) {
    if (is_path_hidden(path)) {
        errno = EACCES;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("mkdir: %s -> %s", path, actual_path);
    }

    return real_mkdir(actual_path, mode);
}

/**
 * Hooked unlink() — redirects file deletion to sandbox.
 */
static int hooked_unlink(const char* path) {
    if (is_path_hidden(path)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected = redirect_path(path);
    const char* actual_path = redirected.empty() ? path : redirected.c_str();

    if (!redirected.empty()) {
        LOGD("unlink: %s -> %s", path, actual_path);
    }

    return real_unlink(actual_path);
}

/**
 * Hooked rename() — redirects file/directory rename to sandbox.
 * Both source and destination paths may need redirection.
 */
static int hooked_rename(const char* oldpath, const char* newpath) {
    if (is_path_hidden(oldpath) || is_path_hidden(newpath)) {
        errno = ENOENT;
        return -1;
    }

    std::string redirected_old = redirect_path(oldpath);
    std::string redirected_new = redirect_path(newpath);
    const char* actual_old = redirected_old.empty() ? oldpath : redirected_old.c_str();
    const char* actual_new = redirected_new.empty() ? newpath : redirected_new.c_str();

    if (!redirected_old.empty() || !redirected_new.empty()) {
        LOGD("rename: %s -> %s, %s -> %s", oldpath, actual_old, newpath, actual_new);
    }

    return real_rename(actual_old, actual_new);
}

/**
 * Hooked __system_property_get() — returns spoofed device properties.
 */
static int hooked_system_property_get(const char* name, char* value) {
    // Check if we have a spoofed value for this property (thread-safe)
    if (name != nullptr) {
        std::shared_lock<std::shared_mutex> lock(g_mutex);
        if (!g_property_spoofs.empty()) {
            std::string prop_name(name);
            auto it = g_property_spoofs.find(prop_name);
            if (it != g_property_spoofs.end()) {
                const std::string& spoofed = it->second;
                size_t len = spoofed.length();
                if (len > 91) len = 91; // PROP_VALUE_MAX - 1
                memcpy(value, spoofed.c_str(), len);
                value[len] = '\0';
                LOGD("property_get: %s -> %s (spoofed)", name, value);
                return static_cast<int>(len);
            }
        }
    }

    // No spoof — call real implementation
    return real_system_property_get(name, value);
}

/**
 * Hooked ptrace() — bypass anti-debug self-ptrace checks.
 * When request == PTRACE_TRACEME, return 0 (no debugger attached).
 * Other requests are forwarded to the real ptrace.
 */
static long hooked_ptrace(int request, pid_t pid, void* addr, void* data) {
    if (request == PTRACE_TRACEME) {
        LOGD("ptrace: PTRACE_TRACEME intercepted, returning 0");
        return 0;
    }
    return real_ptrace(request, pid, addr, data);
}

/**
 * Hooked dlopen() — hide hook framework libraries.
 * If the filename contains known hook framework keywords, return nullptr
 * (load failure) to prevent detection of shadowhook/lsplant/multiapp.
 */
static void* hooked_dlopen(const char* filename, int flags) {
    if (filename != nullptr) {
        // Allow lsplant loading when our code explicitly requests it
        if (g_lsplant_dlopen_bypass && strstr(filename, "lsplant") != nullptr) {
            LOGI("dlopen: allowing lsplant (bypass active): %s", filename);
            return real_dlopen(filename, flags);
        }
        if (strstr(filename, "multiapp") != nullptr ||
            strstr(filename, "shadowhook") != nullptr ||
            strstr(filename, "lsplant") != nullptr) {
            LOGD("dlopen: blocked loading of hook framework library: %s", filename);
            errno = ENOENT;
            return nullptr;
        }
    }
    return real_dlopen(filename, flags);
}

static void hooked_exit(int status) {
    LOGW("exit intercepted: status=%d", status);
    if (status == 1) {
        LOGW("exit intercepted: suppressing status=1 self-exit");
        return;
    }
    if (real_exit != nullptr) {
        real_exit(status);
    }
}

static void hooked__exit(int status) {
    LOGW("_exit intercepted: status=%d", status);
    if (status == 1) {
        LOGW("_exit intercepted: suppressing status=1 self-exit");
        return;
    }
    if (real__exit != nullptr) {
        real__exit(status);
    }
}

static void hooked_abort() {
    LOGW("abort intercepted: forwarding abort");
    if (real_abort != nullptr) {
        real_abort();
        return;
    }
    _exit(134);
}

// ==================== ShadowHook Installation ====================

/**
 * Hook entry: maps a symbol name to our hook function and the saved original pointer.
 * lib_name specifies which library to hook (nullptr defaults to "libc.so").
 */
struct HookEntry {
    const char* lib_name;   // target library (nullptr = "libc.so")
    const char* symbol;
    void* hook_func;
    void** original_func;
};

static HookEntry g_hook_entries[] = {
    {nullptr,       "open",                    (void*)hooked_open,                 (void**)&real_open},
    {nullptr,       "openat",                  (void*)hooked_openat,               (void**)&real_openat},
    {nullptr,       "access",                  (void*)hooked_access,               (void**)&real_access},
    {nullptr,       "stat",                    (void*)hooked_stat,                 (void**)&real_stat},
    {nullptr,       "lstat",                   (void*)hooked_lstat,                (void**)&real_lstat},
    {nullptr,       "readlink",                (void*)hooked_readlink,             (void**)&real_readlink},
    {nullptr,       "fopen",                   (void*)hooked_fopen,                (void**)&real_fopen},
    {nullptr,       "mkdir",                   (void*)hooked_mkdir,                (void**)&real_mkdir},
    {nullptr,       "unlink",                  (void*)hooked_unlink,               (void**)&real_unlink},
    {nullptr,       "rename",                  (void*)hooked_rename,               (void**)&real_rename},
    {nullptr,       "__system_property_get",   (void*)hooked_system_property_get,  (void**)&real_system_property_get},
    {nullptr,       "ptrace",                  (void*)hooked_ptrace,               (void**)&real_ptrace},
    {nullptr,       "exit",                    (void*)hooked_exit,                 (void**)&real_exit},
    {nullptr,       "_exit",                   (void*)hooked__exit,                (void**)&real__exit},
    {"libdl.so",    "dlopen",                  (void*)hooked_dlopen,               (void**)&real_dlopen},
};
static constexpr int g_hook_count = sizeof(g_hook_entries) / sizeof(g_hook_entries[0]);

// ShadowHook stub pointers for unhooking
static void* g_hook_stubs[g_hook_count] = {};

// ==================== Inline Hook Restore Mode ====================
// 360 加固会直接读函数入口指令，检测是否有 inline hook trampoline。
// 对策: hook 完成后临时恢复函数入口，检测时返回原始指令，检测完再 patch 回去。
// 使用 shadowhook 的 shadowhook_unhook / shadowhook_hook_sym_name 实现。

// 每个 hook 的原始函数入口备份（用于恢复）
struct HookBackup {
    void* stub;           // shadowhook stub
    const char* lib_name;
    const char* symbol;
    void* hook_func;
    void** original_func;
    bool is_restored;     // 当前是否处于恢复状态
};

static HookBackup g_hook_backups[g_hook_count] = {};

/**
 * 恢复所有 hook 的函数入口（对抗 360 inline hook 检测）
 * 360 在 JNI_OnLoad 中调用检测函数时，函数入口已经是原始指令
 */
static void restore_all_hooks() {
    for (int i = 0; i < g_hook_count; i++) {
        if (g_hook_stubs[i] != nullptr && !g_hook_backups[i].is_restored) {
            int ret = shadowhook_unhook(g_hook_stubs[i]);
            if (ret == 0) {
                g_hook_backups[i].is_restored = true;
                LOGD("restore_all_hooks: restored %s", g_hook_entries[i].symbol);
            }
        }
    }
}

/**
 * 重新安装所有 hook（360 检测完成后）
 */
static void reinstall_all_hooks() {
    for (int i = 0; i < g_hook_count; i++) {
        if (g_hook_backups[i].is_restored) {
            const char* lib = g_hook_entries[i].lib_name ? g_hook_entries[i].lib_name : "libc.so";
            void* stub = shadowhook_hook_sym_name(
                lib,
                g_hook_entries[i].symbol,
                g_hook_entries[i].hook_func,
                g_hook_entries[i].original_func);
            if (stub != nullptr) {
                g_hook_stubs[i] = stub;
                g_hook_backups[i].is_restored = false;
                LOGD("reinstall_all_hooks: re-hooked %s", g_hook_entries[i].symbol);
            }
        }
    }
}

/**
 * Install all libc hooks using ShadowHook inline hooking.
 * ShadowHook handles trampoline allocation, instruction relocation,
 * and Android 16 seccomp-BPF compatibility automatically.
 */
static bool install_shadowhook_hooks() {
    LOGI("Installing hooks via ShadowHook...");

    int success_count = 0;
    for (int i = 0; i < g_hook_count; i++) {
        const char* lib = g_hook_entries[i].lib_name ? g_hook_entries[i].lib_name : "libc.so";
        void* stub = shadowhook_hook_sym_name(
            lib,
            g_hook_entries[i].symbol,
            g_hook_entries[i].hook_func,
            g_hook_entries[i].original_func);

        if (stub != nullptr) {
            g_hook_stubs[i] = stub;
            success_count++;
            LOGD("Hooked %s in %s via ShadowHook", g_hook_entries[i].symbol, lib);
        } else {
            int err = shadowhook_get_errno();
            LOGW("Failed to hook %s in %s: errno=%d", g_hook_entries[i].symbol, lib, err);
        }
    }

    LOGI("ShadowHook: %d/%d hooks installed", success_count, g_hook_count);
    return success_count > 0;
}

// ==================== JNI Bridge ====================

extern "C" {

/**
 * Initialize the native hook engine.
 * Initializes ShadowHook and installs inline hooks for libc functions.
 */
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeInit(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::unique_lock<std::shared_mutex> lock(g_mutex);

    if (g_initialized) {
        LOGI("Native hook engine already initialized");
        return JNI_TRUE;
    }

    LOGI("Initializing MultiApp native hook engine...");

    // Initialize ShadowHook in shared mode (Android 16 compatible)
    shadowhook_init(SHADOWHOOK_MODE_SHARED, false);

    // Install libc hooks via ShadowHook inline hooking
    g_hooks_installed = install_shadowhook_hooks();
    if (!g_hooks_installed) {
        LOGW("ShadowHook installation failed — falling back to Java-level hooks only");
    }

    g_initialized = true;
    LOGI("Native hook engine initialized");
    return JNI_TRUE;
}

/**
 * Add a path redirection rule.
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeAddPathRedirection(
    JNIEnv* env, jobject thiz, jstring sourcePath, jstring targetPath)
{
    (void)thiz;
    const char* src = env->GetStringUTFChars(sourcePath, nullptr);
    const char* tgt = env->GetStringUTFChars(targetPath, nullptr);

    if (src && tgt) {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_path_redirects[std::string(src)] = std::string(tgt);
        LOGI("Path redirect added: %s -> %s", src, tgt);
    }

    if (src) env->ReleaseStringUTFChars(sourcePath, src);
    if (tgt) env->ReleaseStringUTFChars(targetPath, tgt);
}

/**
 * Remove a path redirection rule.
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRemovePathRedirection(
    JNIEnv* env, jobject thiz, jstring sourcePath)
{
    (void)thiz;
    const char* src = env->GetStringUTFChars(sourcePath, nullptr);

    if (src) {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_path_redirects.erase(std::string(src));
        LOGI("Path redirect removed: %s", src);
    }

    if (src) env->ReleaseStringUTFChars(sourcePath, src);
}

/**
 * Clear all path redirection rules.
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeClearPathRedirections(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::unique_lock<std::shared_mutex> lock(g_mutex);
    g_path_redirects.clear();
    LOGI("All path redirects cleared");
}

/**
 * Set up /proc/self spoofing at native level.
 * Spoofs cmdline, comm, exe, and maps filtering.
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSpoofProcSelf(
    JNIEnv* env, jobject thiz, jint pid, jstring packageName)
{
    (void)thiz;
    const char* pkg = env->GetStringUTFChars(packageName, nullptr);
    if (pkg) {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_spoofed_pid = pid;
        g_spoofed_package_name = std::string(pkg);
        LOGI("/proc/self spoof set: pid=%d, pkg=%s", pid, pkg);
        env->ReleaseStringUTFChars(packageName, pkg);
    }
}

/**
 * Enable or disable TracerPid spoofing in /proc/self/status.
 * When enabled, the hooked_fopen intercepts /proc/self/status reads
 * and replaces TracerPid line with TracerPid:\t0.
 * Note: The actual filtering is always active in hooked_fopen when
 * g_spoofed_package_name is set. This JNI method is provided for
 * explicit control and logging.
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSpoofTracerPid(
    JNIEnv* env, jobject thiz, jboolean enable)
{
    (void)env; (void)thiz;
    // TracerPid spoofing is automatically active when /proc/self/status
    // is read through hooked_fopen. This method logs the intent.
    LOGI("TracerPid spoofing %s", enable ? "enabled" : "disabled");
}

/**
 * Override a system property at native level.
 * MUST match Kotlin: nativeSpoofSystemProperty(key: String, value: String)
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSpoofSystemProperty(
    JNIEnv* env, jobject thiz, jstring key, jstring value)
{
    (void)thiz;
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);

    if (k && v) {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_property_spoofs[std::string(k)] = std::string(v);
        LOGI("Property spoof set: %s -> %s", k, v);
    }

    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

/**
 * Hide a path at native level (return ENOENT on access).
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeHidePath(
    JNIEnv* env, jobject thiz, jstring path)
{
    (void)thiz;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_hidden_paths.insert(std::string(p));
        LOGD("Path hidden: %s", p);
        env->ReleaseStringUTFChars(path, p);
    }
}

/**
 * Unhide a path at native level.
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeUnhidePath(
    JNIEnv* env, jobject thiz, jstring path)
{
    (void)thiz;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_hidden_paths.erase(std::string(p));
        LOGD("Path unhidden: %s", p);
        env->ReleaseStringUTFChars(path, p);
    }
}

/**
 * Clean up all native hooks and state.
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeCleanup(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::unique_lock<std::shared_mutex> lock(g_mutex);
    g_path_redirects.clear();
    g_property_spoofs.clear();
    g_hidden_paths.clear();
    g_spoofed_pid = -1;
    g_spoofed_package_name.clear();
    g_host_data_prefix.clear();
    g_virtual_data_root.clear();
    // Note: ShadowHook inline hooks remain in place but do nothing without redirect rules
    LOGI("Native hook state cleaned up");
}

/**
 * Set the host data directory prefix.
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSetHostDataPrefix(
    JNIEnv* env, jobject thiz, jstring prefix)
{
    (void)thiz;
    const char* p = env->GetStringUTFChars(prefix, nullptr);
    if (p) {
        std::string val(p);
        env->ReleaseStringUTFChars(prefix, p);
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_host_data_prefix = std::move(val);
        LOGI("Host data prefix: %s", g_host_data_prefix.c_str());
    }
}

/**
 * Set the virtual data root directory.
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSetVirtualDataRoot(
    JNIEnv* env, jobject thiz, jstring root)
{
    (void)thiz;
    const char* r = env->GetStringUTFChars(root, nullptr);
    if (r) {
        std::string val(r);
        env->ReleaseStringUTFChars(root, r);
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_virtual_data_root = std::move(val);
        LOGI("Virtual data root: %s", g_virtual_data_root.c_str());
    }
}

/**
 * Get the current redirect count (for debugging).
 */
JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeGetRedirectCount(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::shared_lock<std::shared_mutex> lock(g_mutex);
    return static_cast<jint>(g_path_redirects.size());
}

/**
 * Get the current property spoof count (for debugging).
 */
JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeGetPropertySpoofCount(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    std::shared_lock<std::shared_mutex> lock(g_mutex);
    return static_cast<jint>(g_property_spoofs.size());
}

/**
 * Check if the native engine is initialized.
 */
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeIsInitialized(
    JNIEnv* env, jobject thiz)
{
    (void)env; (void)thiz;
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

// ==================== Runtime.nativeLoad Hook ====================
//
// Jiagu/360-protected apps call Runtime.load0() which delegates to
// Runtime.nativeLoad(String filename, ClassLoader loader, Class caller).
// In the virtual environment, the "caller" Class argument becomes null
// because Jiagu's native code calls System.loadLibrary via JNI reflection
// from a thread where the caller class cannot be resolved.
//
// ART's CheckJNI mode then calls GetObjectArrayElement on NULL (the
// ProtectionDomain array derived from the null caller) → SIGABRT.
//
// Fix: Hook Runtime.nativeLoad at JNI registration level via RegisterNatives.
// If caller==null, synthesize a non-null Class from the classLoader or use
// java.lang.Runtime as a fallback caller.

// Original native implementation saved via method registration replacement
static void* g_orig_nativeLoad_fn = nullptr;
static std::vector<std::string> g_native_load_fallback_callers;
static void* g_orig_register_natives = nullptr;
static bool g_register_natives_logger_installed = false;

// FindClass hook: 在 JNI_OnLoad 中用 guest ClassLoader 查找加固壳类
static jobject g_guest_classloader = nullptr;
static jclass g_findclass_target_class = nullptr;
static std::unordered_set<std::string> g_findclass_targets; // e.g. {"com/stub/StubApp", "com/qihoo/util/StubApp"}
static jmethodID g_classloader_loadclass = nullptr;
static void* g_orig_findclass = nullptr; // 原始 FindClass 函数指针

// Type alias matching ART's native signature for Runtime.nativeLoad
// static jni: (JNIEnv*, jclass, jstring filename, jobject classLoader, jclass caller) -> jstring
typedef jstring (*NativeLoadFn)(JNIEnv*, jclass, jstring, jobject, jclass);
typedef jint (*RegisterNativesFn)(JNIEnv*, jclass, const JNINativeMethod*, jint);
typedef jint (*FockItFn)(JNIEnv*, jclass, jbyteArray, jint);
typedef void (*FockAkFn)(JNIEnv*, jclass, jbyteArray, jint, jbyteArray);
typedef jstring (*FockSnFn)(JNIEnv*, jclass, jbyteArray, jint);
typedef jstring (*FockUrkFn)(JNIEnv*, jclass);

static jstring JNICALL stub_fock_sign_md5(JNIEnv* env, jclass clazz, jbyteArray data, jint len);

static FockItFn g_orig_fock_it = nullptr;
static FockAkFn g_orig_fock_ak = nullptr;
static FockSnFn g_orig_fock_sn = nullptr;
static FockUrkFn g_orig_fock_urk = nullptr;
static std::mutex g_fock_bootstrap_mutex;
static std::mutex g_fock_sn_mutex;
static bool g_fock_bootstrap_done = false;

static bool should_call_original_fock_sn() {
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get("debug.multiapp.fock.call_original", value);
    return len == 1 && value[0] == '1';
}

static std::string fock_bootstrap_key() {
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get("debug.multiapp.fock.bootstrap_key", value);
    if (len > 0) {
        return std::string(value, (size_t)len);
    }
    return "1d67ae1d3420405c9c1e9a193c4b3d12";
}

static void bootstrap_fock_if_needed(JNIEnv* env, jclass clazz) {
    if (g_orig_fock_it == nullptr) {
        LOGW("Fock bootstrap skipped: original it is null");
        return;
    }
    std::lock_guard<std::mutex> lock(g_fock_bootstrap_mutex);
    if (g_fock_bootstrap_done) {
        return;
    }

    std::string key = fock_bootstrap_key();
    jbyteArray bytes = env->NewByteArray((jsize)key.size());
    if (bytes == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("Fock bootstrap failed: cannot allocate key bytes len=%zu", key.size());
        return;
    }
    env->SetByteArrayRegion(bytes, 0, (jsize)key.size(), reinterpret_cast<const jbyte*>(key.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(bytes);
        LOGW("Fock bootstrap failed: SetByteArrayRegion len=%zu", key.size());
        return;
    }
    LOGI("Fock bootstrap: calling it with keyLen=%zu", key.size());
    jint result = g_orig_fock_it(env, clazz, bytes, (jint)key.size());
    env->DeleteLocalRef(bytes);
    if (env->ExceptionCheck()) {
        LOGW("Fock bootstrap: it threw");
        return;
    }
    g_fock_bootstrap_done = true;
    LOGI("Fock bootstrap: it result=%d", result);
}

static jint JNICALL wrapped_fock_it(JNIEnv* env, jclass clazz, jbyteArray data, jint len) {
    jsize actualLen = data != nullptr ? env->GetArrayLength(data) : -1;
    LOGI("Fock.it setup called len=%d actualLen=%d orig=%p", len, (int)actualLen, (void*)g_orig_fock_it);
    if (g_orig_fock_it == nullptr) {
        return 0;
    }
    jint result = g_orig_fock_it(env, clazz, data, len);
    LOGI("Fock.it setup result=%d", result);
    return result;
}

static void JNICALL wrapped_fock_ak(JNIEnv* env, jclass clazz, jbyteArray userKey, jint version, jbyteArray pool) {
    jsize userKeyLen = userKey != nullptr ? env->GetArrayLength(userKey) : -1;
    jsize poolLen = pool != nullptr ? env->GetArrayLength(pool) : -1;
    LOGI("Fock.ak addKeys called version=%d userKeyLen=%d poolLen=%d orig=%p",
         version, (int)userKeyLen, (int)poolLen, (void*)g_orig_fock_ak);
    if (g_orig_fock_ak != nullptr) {
        g_orig_fock_ak(env, clazz, userKey, version, pool);
    }
}

static jstring JNICALL wrapped_fock_urk(JNIEnv* env, jclass clazz) {
    if (g_orig_fock_urk == nullptr) {
        LOGI("Fock.urk currentUserKey called with null original");
        return env->NewStringUTF("");
    }
    jstring result = g_orig_fock_urk(env, clazz);
    if (env->ExceptionCheck()) {
        LOGW("Fock.urk original threw");
        return result;
    }
    const char* chars = result != nullptr ? env->GetStringUTFChars(result, nullptr) : nullptr;
    LOGI("Fock.urk currentUserKey resultLen=%d", chars != nullptr ? (int)strlen(chars) : -1);
    if (chars != nullptr) env->ReleaseStringUTFChars(result, chars);
    return result;
}

static jstring JNICALL wrapped_fock_sn(JNIEnv* env, jclass clazz, jbyteArray data, jint len) {
    jsize actualLen = data != nullptr ? env->GetArrayLength(data) : -1;
    bool callOriginal = should_call_original_fock_sn();
    LOGI("Fock.sn sign called len=%d actualLen=%d orig=%p callOriginal=%d",
         len, (int)actualLen, (void*)g_orig_fock_sn, callOriginal ? 1 : 0);
    if (callOriginal && g_orig_fock_sn != nullptr) {
        std::lock_guard<std::mutex> snLock(g_fock_sn_mutex);
        bootstrap_fock_if_needed(env, clazz);
        jstring result = g_orig_fock_sn(env, clazz, data, len);
        if (!env->ExceptionCheck()) {
            const char* chars = result != nullptr ? env->GetStringUTFChars(result, nullptr) : nullptr;
            LOGI("Fock.sn original resultLen=%d", chars != nullptr ? (int)strlen(chars) : -1);
            if (chars != nullptr) env->ReleaseStringUTFChars(result, chars);
        }
        return result;
    }
    LOGW("Fock.sn original bypassed to avoid native SIGSEGV; returning diagnostic MD5");
    return stub_fock_sign_md5(env, clazz, data, len);
}

static std::string describe_java_class(JNIEnv* env, jclass clazz) {
    if (clazz == nullptr) return "<null>";

    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return "<Class lookup failed>";
    }

    jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    env->DeleteLocalRef(classClass);
    if (getName == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return "<Class.getName missing>";
    }

    auto nameObj = (jstring)env->CallObjectMethod(clazz, getName);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return "<Class.getName threw>";
    }
    if (nameObj == nullptr) return "<unnamed>";

    const char* chars = env->GetStringUTFChars(nameObj, nullptr);
    std::string result = chars ? chars : "<utf failed>";
    if (chars) env->ReleaseStringUTFChars(nameObj, chars);
    env->DeleteLocalRef(nameObj);
    return result;
}

static jint hooked_RegisterNatives(JNIEnv* env, jclass clazz, const JNINativeMethod* methods, jint nMethods) {
    std::string className = describe_java_class(env, clazz);
    LOGI("RegisterNatives: class=%s count=%d", className.c_str(), nMethods);

    std::vector<JNINativeMethod> patchedMethods;
    const JNINativeMethod* methodsToRegister = methods;
    if (className == "com.yuewen.fock.Fock" && methods != nullptr && nMethods > 0) {
        patchedMethods.assign(methods, methods + nMethods);
        for (jint i = 0; i < nMethods; i++) {
            const char* name = patchedMethods[i].name ? patchedMethods[i].name : "";
            const char* sig = patchedMethods[i].signature ? patchedMethods[i].signature : "";
            if (strcmp(name, "it") == 0 && strcmp(sig, "([BI)I") == 0) {
                g_orig_fock_it = (FockItFn)patchedMethods[i].fnPtr;
                patchedMethods[i].fnPtr = (void*)wrapped_fock_it;
                LOGW("RegisterNatives Fock: wrapped it original=%p", (void*)g_orig_fock_it);
            } else if (strcmp(name, "ak") == 0 && strcmp(sig, "([BI[B)V") == 0) {
                g_orig_fock_ak = (FockAkFn)patchedMethods[i].fnPtr;
                patchedMethods[i].fnPtr = (void*)wrapped_fock_ak;
                LOGW("RegisterNatives Fock: wrapped ak original=%p", (void*)g_orig_fock_ak);
            } else if (strcmp(name, "sn") == 0 && strcmp(sig, "([BI)Ljava/lang/String;") == 0) {
                g_orig_fock_sn = (FockSnFn)patchedMethods[i].fnPtr;
                patchedMethods[i].fnPtr = (void*)wrapped_fock_sn;
                LOGW("RegisterNatives Fock: wrapped sn original=%p", (void*)g_orig_fock_sn);
            } else if (strcmp(name, "urk") == 0 && strcmp(sig, "()Ljava/lang/String;") == 0) {
                g_orig_fock_urk = (FockUrkFn)patchedMethods[i].fnPtr;
                patchedMethods[i].fnPtr = (void*)wrapped_fock_urk;
                LOGW("RegisterNatives Fock: wrapped urk original=%p", (void*)g_orig_fock_urk);
            }
        }
        methodsToRegister = patchedMethods.data();
    }

    if (methods != nullptr && nMethods > 0) {
        for (jint i = 0; i < nMethods; i++) {
            const char* name = methods[i].name ? methods[i].name : "<null>";
            const char* sig = methods[i].signature ? methods[i].signature : "<null>";
            Dl_info originalInfo{};
            Dl_info registeredInfo{};
            const char* originalLib = "<unknown>";
            const char* registeredLib = "<unknown>";
            const char* originalSym = "<unknown>";
            const char* registeredSym = "<unknown>";
            if (methods[i].fnPtr != nullptr && dladdr(methods[i].fnPtr, &originalInfo) != 0) {
                originalLib = originalInfo.dli_fname ? originalInfo.dli_fname : "<unknown>";
                originalSym = originalInfo.dli_sname ? originalInfo.dli_sname : "<unknown>";
            }
            if (methodsToRegister[i].fnPtr != nullptr && dladdr(methodsToRegister[i].fnPtr, &registeredInfo) != 0) {
                registeredLib = registeredInfo.dli_fname ? registeredInfo.dli_fname : "<unknown>";
                registeredSym = registeredInfo.dli_sname ? registeredInfo.dli_sname : "<unknown>";
            }
            LOGI("RegisterNatives:   [%d] %s %s fn=%p lib=%s sym=%s registeredFn=%p registeredLib=%s registeredSym=%s",
                 i, name, sig, methods[i].fnPtr, originalLib, originalSym,
                 methodsToRegister[i].fnPtr, registeredLib, registeredSym);
            if (className == "com.yuewen.ywlogin.login.YWLoginManager" ||
                className == "com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask" ||
                strcmp(name, "getInstance") == 0 ||
                strstr(className.c_str(), "YWLogin") != nullptr) {
                LOGW("RegisterNatives MATCH: class=%s method=%s sig=%s fn=%p lib=%s sym=%s",
                     className.c_str(), name, sig, methods[i].fnPtr, originalLib, originalSym);
            }
        }
    }

    if (g_orig_register_natives == nullptr) {
        LOGE("RegisterNatives logger: original pointer is null");
        return JNI_ERR;
    }
    jint result = ((RegisterNativesFn)g_orig_register_natives)(env, clazz, methodsToRegister, nMethods);
    LOGI("RegisterNatives: result=%d class=%s", result, className.c_str());
    return result;
}

static bool installRegisterNativesLogger(JNIEnv* env) {
    if (g_register_natives_logger_installed) {
        LOGI("installRegisterNativesLogger: already installed");
        return true;
    }

    void** jniFunctions = *reinterpret_cast<void***>(env);
    constexpr int REGISTER_NATIVES_INDEX = 215;
    g_orig_register_natives = jniFunctions[REGISTER_NATIVES_INDEX];
    if (g_orig_register_natives == nullptr) {
        LOGE("installRegisterNativesLogger: original RegisterNatives pointer is null");
        return false;
    }

    uintptr_t page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = (uintptr_t)&jniFunctions[REGISTER_NATIVES_INDEX] & ~(page_size - 1);
    if (mprotect((void*)page_start, page_size, PROT_READ | PROT_WRITE) != 0) {
        LOGE("installRegisterNativesLogger: mprotect RW failed errno=%d", errno);
        return false;
    }

    jniFunctions[REGISTER_NATIVES_INDEX] = (void*)hooked_RegisterNatives;
    if (mprotect((void*)page_start, page_size, PROT_READ) != 0) {
        LOGW("installRegisterNativesLogger: mprotect R failed errno=%d", errno);
    }
    g_register_natives_logger_installed = true;
    LOGI("installRegisterNativesLogger: installed (original=%p)", g_orig_register_natives);
    return true;
}

/**
 * Runtime.nativeLoad hook — fixes a null caller Class and then forwards to
 * ART's original nativeLoad.
 *
 * ART's nativeLoad accesses caller.getProtectionDomain() internally and
 * crashes with SIGABRT when the ProtectionDomain array is NULL. In virtual
 * environments, guest app classes loaded by custom ClassLoaders often have
 * NULL ProtectionDomain because the Class object isn't fully initialized
 * from ART's perspective.
 *
 * Loading via raw dlopen + manual JNI_OnLoad is not equivalent to ART
 * nativeLoad: ART records the native library against the ClassLoader and that
 * association is required for FindClass/RegisterNatives inside packer
 * JNI_OnLoad. Forwarding to ART keeps libjiagu_vip.so bound to the guest
 * ClassLoader so registrations such as StubApp.interface20 can complete.
 */
static jclass resolve_native_load_caller(JNIEnv* env, jobject classLoader) {
    if (classLoader != nullptr) {
        jclass classLoaderClass = env->GetObjectClass(classLoader);
        jmethodID loadClass = classLoaderClass != nullptr
            ? env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;")
            : nullptr;

        if (loadClass != nullptr) {
            std::vector<std::string> candidates;
            {
                std::shared_lock<std::shared_mutex> lock(g_mutex);
                candidates = g_native_load_fallback_callers;
            }

            for (const std::string& className : candidates) {
                jstring name = env->NewStringUTF(className.c_str());
                if (name == nullptr) continue;

                auto clazz = reinterpret_cast<jclass>(
                    env->CallObjectMethod(classLoader, loadClass, name));
                env->DeleteLocalRef(name);

                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    clazz = nullptr;
                }

                if (clazz != nullptr) {
                    LOGI("resolve_native_load_caller: using %s", className.c_str());
                    env->DeleteLocalRef(classLoaderClass);
                    return clazz;
                }
            }
        }

        if (classLoaderClass != nullptr) {
            env->DeleteLocalRef(classLoaderClass);
        }
    }

    jclass runtimeClass = env->FindClass("java/lang/Runtime");
    if (runtimeClass == nullptr && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (runtimeClass != nullptr) {
        LOGW("resolve_native_load_caller: falling back to java.lang.Runtime");
    }
    return runtimeClass;
}

static jstring hooked_nativeLoad(JNIEnv* env, jclass runtimeClass,
                                  jstring filename, jobject classLoader, jclass caller) {
    if (filename == nullptr) {
        LOGW("hooked_nativeLoad: filename is null, calling original");
        if (g_orig_nativeLoad_fn != nullptr) {
            return ((NativeLoadFn)g_orig_nativeLoad_fn)(env, runtimeClass, filename, classLoader, caller);
        }
        return nullptr;
    }

    // Convert jstring to C string
    const char* path = env->GetStringUTFChars(filename, nullptr);
    if (path == nullptr) {
        LOGE("hooked_nativeLoad: GetStringUTFChars returned null");
        return nullptr;
    }

    LOGI("hooked_nativeLoad: forwarding '%s' to ART nativeLoad", path);

    jclass effectiveCaller = caller;
    if (effectiveCaller == nullptr) {
        LOGW("hooked_nativeLoad: caller is null, resolving fallback caller");
        effectiveCaller = resolve_native_load_caller(env, classLoader);
    }

    jstring result = nullptr;
    if (g_orig_nativeLoad_fn != nullptr) {
        result = ((NativeLoadFn)g_orig_nativeLoad_fn)(
            env,
            runtimeClass,
            filename,
            classLoader,
            effectiveCaller);
    } else {
        LOGE("hooked_nativeLoad: original ART nativeLoad pointer is null");
    }

    if (result == nullptr) {
        LOGI("hooked_nativeLoad: ART nativeLoad succeeded for '%s'", path);
    } else {
        const char* error = env->GetStringUTFChars(result, nullptr);
        LOGE("hooked_nativeLoad: ART nativeLoad failed for '%s': %s",
             path, error ? error : "null");
        if (error) env->ReleaseStringUTFChars(result, error);
    }

    env->ReleaseStringUTFChars(filename, path);

    if (effectiveCaller != nullptr && effectiveCaller != caller) {
        env->DeleteLocalRef(effectiveCaller);
    }
    return result;
}

/**
 * Install the Runtime.nativeLoad hook using RegisterNatives.
 *
 * Approach:
 * 1. Find java.lang.Runtime class
 * 2. Save original nativeLoad function pointer via GetMethodID + JNI internal lookup
 * 3. Register our hooked version via RegisterNatives
 */
static bool installNativeLoadHook(JNIEnv* env) {
    // Find java.lang.Runtime
    jclass runtimeClass = env->FindClass("java/lang/Runtime");
    if (runtimeClass == nullptr) {
        LOGE("installNativeLoadHook: cannot find java/lang/Runtime");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }

    // Try to get the original native function pointer from libart.so
    // The symbol name varies by ART version but we try the common ones
    void* libart = dlopen("libart.so", RTLD_NOLOAD);
    if (libart == nullptr) {
        LOGW("installNativeLoadHook: libart.so not found via RTLD_NOLOAD, trying dlopen");
        libart = dlopen("libart.so", RTLD_NOW);
    }

    if (libart != nullptr) {
        // ART internal symbol for Runtime_nativeLoad (static JNI method)
        // Try multiple symbol names as it varies across Android versions
        const char* symbols[] = {
            "_ZN3artL18Runtime_nativeLoadEP7_JNIEnvP7_jclassP8_jstringP8_jobjectS5_",
            "Runtime_nativeLoad",
            nullptr
        };

        for (int i = 0; symbols[i] != nullptr; i++) {
            g_orig_nativeLoad_fn = dlsym(libart, symbols[i]);
            if (g_orig_nativeLoad_fn != nullptr) {
                LOGI("installNativeLoadHook: found original at symbol '%s'", symbols[i]);
                break;
            }
        }
    }

    if (g_orig_nativeLoad_fn == nullptr) {
        // Fallback: use env->GetMethodID to verify the method exists,
        // then rely on RegisterNatives to stash the original internally.
        // We'll use a JNI trick: register, then we ARE the native now.
        // To call original, we need the old function pointer.
        // Without it, we try a different approach — call doLoad on Runtime directly.

        // Try using JNI GetStaticMethodID to verify method exists
        jmethodID nativeLoadMethod = env->GetStaticMethodID(
            runtimeClass, "nativeLoad",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;");
        if (nativeLoadMethod == nullptr) {
            LOGE("installNativeLoadHook: Runtime.nativeLoad method not found");
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(runtimeClass);
            return false;
        }

        // Without the original symbol, we can still hook but won't be able to forward.
        // Use a wrapper approach: save the method ID and call via JNI CallStatic
        // But this would recursively call our hook... So we MUST have the original.
        LOGE("installNativeLoadHook: cannot find original native symbol in libart.so");
        env->DeleteLocalRef(runtimeClass);
        return false;
    }

    // Register our hooked version
    JNINativeMethod methods[] = {
        {
            const_cast<char*>("nativeLoad"),
            const_cast<char*>("(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;"),
            reinterpret_cast<void*>(hooked_nativeLoad)
        }
    };

    jint result = env->RegisterNatives(runtimeClass, methods, 1);
    env->DeleteLocalRef(runtimeClass);

    if (result != JNI_OK) {
        LOGE("installNativeLoadHook: RegisterNatives failed with code %d", result);
        g_orig_nativeLoad_fn = nullptr;
        return false;
    }

    LOGI("installNativeLoadHook: SUCCESS — Runtime.nativeLoad hooked via RegisterNatives");
    return true;
}

/**
 * JNI bridge for NativeHookBridge.nativeInstallRuntimeLoadHook()
 */
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeInstallRuntimeLoadHook(
    JNIEnv* env, jobject thiz, jobjectArray fallbackCallerClasses)
{
    (void)thiz;
    {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_native_load_fallback_callers.clear();
        if (fallbackCallerClasses != nullptr) {
            jsize count = env->GetArrayLength(fallbackCallerClasses);
            for (jsize i = 0; i < count; i++) {
                auto item = (jstring)env->GetObjectArrayElement(fallbackCallerClasses, i);
                if (item == nullptr) continue;
                const char* chars = env->GetStringUTFChars(item, nullptr);
                if (chars != nullptr && chars[0] != '\0') {
                    g_native_load_fallback_callers.emplace_back(chars);
                }
                if (chars) env->ReleaseStringUTFChars(item, chars);
                env->DeleteLocalRef(item);
            }
        }
    }
    return installNativeLoadHook(env) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeInstallRegisterNativesLogger(
    JNIEnv* env, jobject thiz)
{
    (void)thiz;
    return installRegisterNativesLogger(env) ? JNI_TRUE : JNI_FALSE;
}

// ==================== LoaderFactory Static JNI Methods ====================

/**
 * LoaderFactory 专用: 一次性完成 shadowhook 初始化 + /proc/self 伪装 + 属性伪装
 * Static JNI — 不需要 NativeHookBridge 实例
 *
 * 时序: 必须在 instantiateApplication() 中、ClassLoader 替换前调用
 */
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSetupForLoader(
    JNIEnv* env, jclass clazz, jstring packageName, jobjectArray propKeys, jobjectArray propValues)
{
    (void)clazz;

    // 1. 初始化 shadowhook + 安装 PLT/GOT Hook
    {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        if (!g_initialized) {
            LOGI("nativeSetupForLoader: initializing shadowhook...");
            shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
            g_hooks_installed = install_shadowhook_hooks();
            g_initialized = true;
            if (g_hooks_installed) {
                LOGI("nativeSetupForLoader: PLT/GOT hooks installed");
            } else {
                LOGW("nativeSetupForLoader: hook installation failed");
            }
        }
    }

    // 2. 配置 /proc/self 伪装
    if (packageName != nullptr) {
        const char* pkg = env->GetStringUTFChars(packageName, nullptr);
        if (pkg) {
            std::unique_lock<std::shared_mutex> lock(g_mutex);
            g_spoofed_pid = getpid();
            g_spoofed_package_name = std::string(pkg);
            LOGI("nativeSetupForLoader: /proc/self spoofed to '%s'", pkg);
            env->ReleaseStringUTFChars(packageName, pkg);
        }
    }

    // 3. 配置系统属性伪装
    if (propKeys != nullptr && propValues != nullptr) {
        int count = env->GetArrayLength(propKeys);
        int valCount = env->GetArrayLength(propValues);
        if (count == valCount) {
            std::unique_lock<std::shared_mutex> lock(g_mutex);
            for (int i = 0; i < count; i++) {
                auto key = (jstring)env->GetObjectArrayElement(propKeys, i);
                auto value = (jstring)env->GetObjectArrayElement(propValues, i);
                if (key && value) {
                    const char* k = env->GetStringUTFChars(key, nullptr);
                    const char* v = env->GetStringUTFChars(value, nullptr);
                    if (k && v) {
                        g_property_spoofs[std::string(k)] = std::string(v);
                        LOGD("nativeSetupForLoader: property %s -> %s", k, v);
                    }
                    if (k) env->ReleaseStringUTFChars(key, k);
                    if (v) env->ReleaseStringUTFChars(value, v);
                }
                env->DeleteLocalRef(key);
                env->DeleteLocalRef(value);
            }
            LOGI("nativeSetupForLoader: %d properties configured", count);
        }
    }

    return g_hooks_installed ? JNI_TRUE : JNI_FALSE;
}

/**
 * LoaderFactory 专用: 配置 /proc/self 伪装 (static JNI)
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSpoofProcSelfStatic(
    JNIEnv* env, jclass clazz, jint pid, jstring packageName)
{
    (void)clazz;
    const char* pkg = env->GetStringUTFChars(packageName, nullptr);
    if (pkg) {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_spoofed_pid = pid;
        g_spoofed_package_name = std::string(pkg);
        LOGI("nativeSpoofProcSelfStatic: pid=%d, pkg=%s", pid, pkg);
        env->ReleaseStringUTFChars(packageName, pkg);
    }
}

/**
 * LoaderFactory 专用: 配置系统属性伪装 (static JNI)
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSpoofSystemPropertyStatic(
    JNIEnv* env, jclass clazz, jstring key, jstring value)
{
    (void)clazz;
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);

    if (k && v) {
        std::unique_lock<std::shared_mutex> lock(g_mutex);
        g_property_spoofs[std::string(k)] = std::string(v);
        LOGI("nativeSpoofSystemPropertyStatic: %s -> %s", k, v);
    }

    if (k) env->ReleaseStringUTFChars(key, k);
    if (v) env->ReleaseStringUTFChars(value, v);
}

/**
 * 设置完整性校验重定向：壳的 JNI_OnLoad 读 APK 校验 DEX 时，重定向到原始 APK。
 * 必须在调用 System.loadLibrary() 之前设置，之后清除。
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSetIntegrityRedirect(
    JNIEnv* env, jclass clazz, jstring fromPath, jstring toPath)
{
    (void)clazz;
    if (fromPath != nullptr) {
        const char* s = env->GetStringUTFChars(fromPath, nullptr);
        g_integrity_redirect_from = s ? s : "";
        env->ReleaseStringUTFChars(fromPath, s);
    }
    if (toPath != nullptr) {
        const char* s = env->GetStringUTFChars(toPath, nullptr);
        g_integrity_redirect_to = s ? s : "";
        env->ReleaseStringUTFChars(toPath, s);
    }
    g_integrity_redirect_active = true;
    LOGI("nativeSetIntegrityRedirect: from=%s -> to=%s",
        g_integrity_redirect_from.c_str(), g_integrity_redirect_to.c_str());
}

JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeClearIntegrityRedirect(
    JNIEnv* env, jclass clazz)
{
    (void)env; (void)clazz;
    g_integrity_redirect_active = false;
    g_integrity_redirect_from.clear();
    g_integrity_redirect_to.clear();
    LOGI("nativeClearIntegrityRedirect: done");
}

// ==================== GOT Hook Implementation ====================
// PLT/GOT hook: 修改目标库的 GOT 表中的函数指针
// 不需要 trampoline 内存，Android 16 上可行

struct GotHookEntry {
    const char* symbol_name;
    void* hook_func;
    void** orig_func_ptr;
};

static size_t get_elf_r_sym(uintptr_t r_info);

// 保存原始函数指针
static orig_open_t got_orig_open = nullptr;
static orig_openat_t got_orig_openat = nullptr;
static orig_fopen_t got_orig_fopen = nullptr;
static orig_exit_t got_orig_exit = nullptr;
static orig_exit_t got_orig__exit = nullptr;
static orig_abort_t got_orig_abort = nullptr;
static thread_local bool g_filtering_proc_maps = false;

// 检查路径是否是 proc maps 相关
static bool is_proc_maps_path(const char* path) {
    if (path == nullptr) return false;
    return strstr(path, "/proc/self/maps") != nullptr ||
           strstr(path, "/proc/self/smaps") != nullptr ||
           strstr(path, "/proc/self/pagemap") != nullptr ||
           strstr(path, "/proc/./self/maps") != nullptr;
}

static bool is_proc_maps_text_path(const char* path) {
    if (path == nullptr) return false;
    return strstr(path, "/proc/self/maps") != nullptr ||
           strstr(path, "/proc/self/smaps") != nullptr ||
           strstr(path, "/proc/./self/maps") != nullptr;
}

static bool should_hide_maps_line(const char* line) {
    if (line == nullptr) return false;
    return strstr(line, "multiapp") != nullptr ||
           strstr(line, "shadowhook") != nullptr ||
           strstr(line, "lsplant") != nullptr ||
           strstr(line, "dobby") != nullptr ||
           strstr(line, "bhook") != nullptr ||
           strstr(line, "xhook") != nullptr ||
           strstr(line, "substrate") != nullptr ||
           strstr(line, "xposed") != nullptr ||
           strstr(line, "libnextvm") != nullptr ||
           strstr(line, "LSPosed") != nullptr ||
           strstr(line, "edxposed") != nullptr ||
           strstr(line, "riru") != nullptr ||
           strstr(line, "zygisk") != nullptr ||
           strstr(line, "magisk") != nullptr ||
           strstr(line, "/data/adb") != nullptr;
}

static FILE* create_filtered_maps_file(const char* path) {
    FILE* real_maps = nullptr;
    bool old_filtering = g_filtering_proc_maps;
    g_filtering_proc_maps = true;
    if (got_orig_fopen) {
        real_maps = got_orig_fopen(path, "r");
    }
    if (real_maps == nullptr && real_fopen) {
        real_maps = real_fopen(path, "r");
    }
    g_filtering_proc_maps = old_filtering;
    if (real_maps == nullptr) {
        return nullptr;
    }

    FILE* tmp = tmpfile();
    if (tmp == nullptr) {
        fclose(real_maps);
        return nullptr;
    }

    char line[2048];
    while (fgets(line, sizeof(line), real_maps)) {
        if (!should_hide_maps_line(line)) {
            fputs(line, tmp);
        }
    }
    fclose(real_maps);
    fflush(tmp);
    fseek(tmp, 0, SEEK_SET);
    return tmp;
}

static int create_filtered_maps_fd(const char* path) {
    FILE* tmp = create_filtered_maps_file(path);
    if (tmp == nullptr) {
        errno = ENOENT;
        return -1;
    }
    int fd = fileno(tmp);
    int dupfd = dup(fd);
    fclose(tmp);
    if (dupfd >= 0) {
        lseek(dupfd, 0, SEEK_SET);
        return dupfd;
    }
    return -1;
}

// 创建一个空的 tmpfile fd（返回 dup 后的 fd，FILE* 自动关闭原始 fd）
static int create_empty_fd() {
    FILE* tmp = tmpfile();
    if (tmp) {
        int fd = fileno(tmp);
        int dupfd = dup(fd);
        fclose(tmp); // 关闭原始 FILE*，dupfd 仍然有效
        if (dupfd >= 0) {
            lseek(dupfd, 0, SEEK_SET);
            return dupfd;
        }
    }
    // fallback: 创建空 pipe
    int pipefd[2];
    if (pipe(pipefd) == 0) {
        close(pipefd[1]);
        return pipefd[0];
    }
    errno = ENOENT;
    return -1;
}

// GOT hook 函数 — 拦截壳库对 /proc/self/maps 的读取
// 修复：
//   1. 用 tmpfile 替代 pipe（pipe 可被 fstat 检测为 S_IFIFO）
//   2. 正确处理 variadic args（仅在 O_CREAT 时读 mode_t）
//   3. 扩展覆盖 smaps/pagemap

static int got_hooked_open(const char* path, int flags, ...) {
    if (is_proc_maps_path(path)) {
        if (g_filtering_proc_maps && got_orig_open) {
            return got_orig_open(path, flags);
        }
        if (is_proc_maps_text_path(path)) {
            return create_filtered_maps_fd(path);
        }
        return create_empty_fd();
    }
    // 正确处理 variadic args：仅 O_CREAT 时有 mode_t 参数
    if (got_orig_open) {
        if (flags & O_CREAT) {
            va_list args;
            va_start(args, flags);
            mode_t mode = static_cast<mode_t>(va_arg(args, int));
            va_end(args);
            return got_orig_open(path, flags, mode);
        }
        return got_orig_open(path, flags);
    }
    errno = ENOSYS;
    return -1;
}

static int got_hooked_openat(int dirfd, const char* path, int flags, ...) {
    if (is_proc_maps_path(path)) {
        if (g_filtering_proc_maps && got_orig_openat) {
            return got_orig_openat(dirfd, path, flags);
        }
        if (is_proc_maps_text_path(path)) {
            return create_filtered_maps_fd(path);
        }
        return create_empty_fd();
    }
    if (got_orig_openat) {
        if (flags & O_CREAT) {
            va_list args;
            va_start(args, flags);
            mode_t mode = static_cast<mode_t>(va_arg(args, int));
            va_end(args);
            return got_orig_openat(dirfd, path, flags, mode);
        }
        return got_orig_openat(dirfd, path, flags);
    }
    errno = ENOSYS;
    return -1;
}

static FILE* got_hooked_fopen(const char* path, const char* mode) {
    if (is_proc_maps_path(path)) {
        if (g_filtering_proc_maps && got_orig_fopen) {
            return got_orig_fopen(path, mode);
        }
        if (is_proc_maps_text_path(path)) {
            return create_filtered_maps_file(path);
        }
        return tmpfile(); // 空 tmpfile
    }
    if (got_orig_fopen) return got_orig_fopen(path, mode);
    return nullptr;
}

// readlink hook — 拦截 /proc/self/map_files/ 等 readlink 调用
static orig_readlink_t got_orig_readlink = nullptr;
static ssize_t got_hooked_readlink(const char* path, char* buf, size_t bufsiz) {
    if (path != nullptr && strstr(path, "/proc/self/map_files") != nullptr) {
        errno = ENOENT;
        return -1;
    }
    if (got_orig_readlink) return got_orig_readlink(path, buf, bufsiz);
    errno = ENOSYS;
    return -1;
}

static void got_hooked_exit(int status) {
    LOGW("GOT exit intercepted: status=%d", status);
    if (status == 1) {
        LOGW("GOT exit intercepted: suppressing status=1 self-exit");
        return;
    }
    if (got_orig_exit) {
        got_orig_exit(status);
        return;
    }
    if (real_exit) {
        real_exit(status);
    }
}

static void got_hooked__exit(int status) {
    LOGW("GOT _exit intercepted: status=%d", status);
    if (status == 1) {
        LOGW("GOT _exit intercepted: suppressing status=1 self-exit");
        return;
    }
    if (got_orig__exit) {
        got_orig__exit(status);
        return;
    }
    if (real__exit) {
        real__exit(status);
    }
}

static void got_hooked_abort() {
    LOGW("GOT abort intercepted: forwarding abort");
    if (got_orig_abort) {
        got_orig_abort();
        return;
    }
    if (real_abort) {
        real_abort();
        return;
    }
    _exit(134);
}

// GOT hook: 修改指定库的 GOT 表
// hook 策略：对目标库（壳库）和 libc.so 都进行 hook
// - 壳库 hook：拦截壳自身 PLT 调用
// - libc hook：拦截壳通过 libc PLT 的调用（覆盖 constructor 时序问题）
static int got_hook_library_callback(struct dl_phdr_info* info, size_t size, void* data) {
    const char* target_lib = (const char*)data;
    const char* lib_name = info->dlpi_name;

    if (lib_name == nullptr) return 0;

    // 匹配目标库或 libc.so
    bool is_target = (strstr(lib_name, target_lib) != nullptr);
    if (!is_target) return 0;

    LOGI("got_hook: found library %s at %p", lib_name, (void*)info->dlpi_addr);

    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type != PT_DYNAMIC) continue;

        ElfW(Dyn)* dyn = (ElfW(Dyn)*)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
        ElfW(Sym)* symtab = nullptr;
        const char* strtab = nullptr;

        for (ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
            switch (d->d_tag) {
                case DT_SYMTAB:
                    symtab = (ElfW(Sym)*)(info->dlpi_addr + d->d_un.d_ptr);
                    break;
                case DT_STRTAB:
                    strtab = (const char*)(info->dlpi_addr + d->d_un.d_ptr);
                    break;
            }
        }

        if (symtab == nullptr || strtab == nullptr) {
            LOGW("got_hook: missing symtab=%p strtab=%p for %s", symtab, strtab, lib_name);
            // 遍历 dynamic section 看有哪些条目
            for (ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
                LOGD("got_hook:   d_tag=0x%lx d_val=0x%lx", (unsigned long)d->d_tag, (unsigned long)d->d_un.d_val);
            }
            return 0;
        }

        // 判断是 REL 还是 RELA（ARM64 通常用 RELA，24 字节/条目）
        bool use_rela = false;
        for (ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
            if (d->d_tag == DT_PLTREL && d->d_un.d_val == DT_RELA) {
                use_rela = true;
                break;
            }
        }

        for (ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
            if (d->d_tag != DT_JMPREL) continue;

            size_t rel_count = 0;
            for (ElfW(Dyn)* d2 = dyn; d2->d_tag != DT_NULL; d2++) {
                if (d2->d_tag == DT_PLTRELSZ) {
                    rel_count = use_rela
                        ? d2->d_un.d_val / sizeof(ElfW(Rela))
                        : d2->d_un.d_val / sizeof(ElfW(Rel));
                    break;
                }
            }

            LOGI("got_hook: %s checking %zu relocations (rela=%d)", lib_name, rel_count, use_rela);
            int hooked = 0;

            for (size_t j = 0; j < rel_count; j++) {
                size_t sym_idx;
                ElfW(Addr) r_offset;
                if (use_rela) {
                    ElfW(Rela)* rela = (ElfW(Rela)*)(info->dlpi_addr + d->d_un.d_ptr);
                    sym_idx = get_elf_r_sym(rela[j].r_info);
                    r_offset = rela[j].r_offset;
                } else {
                    ElfW(Rel)* rel = (ElfW(Rel)*)(info->dlpi_addr + d->d_un.d_ptr);
                    sym_idx = get_elf_r_sym(rel[j].r_info);
                    r_offset = rel[j].r_offset;
                }

                const char* sym_name = strtab + symtab[sym_idx].st_name;
                ElfW(Addr)* got_entry = (ElfW(Addr)*)(info->dlpi_addr + r_offset);
                uintptr_t page = (uintptr_t)got_entry & ~(sysconf(_SC_PAGESIZE) - 1);
                auto patch_entry = [&](void* hook_func, void** orig_func) -> bool {
                    if ((void*)*got_entry == hook_func) return false;
                    if (*orig_func == nullptr) {
                        *orig_func = (void*)*got_entry;
                    }
                    if (mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ | PROT_WRITE) != 0) {
                        LOGW("got_hook: mprotect RW failed for %s in %s errno=%d",
                             sym_name, lib_name, errno);
                        return false;
                    }
                    *got_entry = (ElfW(Addr))hook_func;
                    if (mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ) != 0) {
                        LOGW("got_hook: mprotect R failed for %s in %s errno=%d",
                             sym_name, lib_name, errno);
                    }
                    return true;
                };

                if (strcmp(sym_name, "open") == 0) {
                    if (patch_entry((void*)got_hooked_open, (void**)&got_orig_open)) hooked++;
                }
                else if (strcmp(sym_name, "openat") == 0) {
                    if (patch_entry((void*)got_hooked_openat, (void**)&got_orig_openat)) hooked++;
                }
                else if (strcmp(sym_name, "fopen") == 0) {
                    if (patch_entry((void*)got_hooked_fopen, (void**)&got_orig_fopen)) hooked++;
                }
                else if (strcmp(sym_name, "readlink") == 0) {
                    if (patch_entry((void*)got_hooked_readlink, (void**)&got_orig_readlink)) hooked++;
                }
                else if (strcmp(sym_name, "exit") == 0) {
                    if (patch_entry((void*)got_hooked_exit, (void**)&got_orig_exit)) hooked++;
                }
                else if (strcmp(sym_name, "_exit") == 0) {
                    if (patch_entry((void*)got_hooked__exit, (void**)&got_orig__exit)) hooked++;
                }
            }
            if (hooked > 0) {
                LOGI("got_hook: hooked %d functions in %s", hooked, lib_name);
            }
            break;
        }
        // 不 return 1 — 继续遍历以 hook 其他库（如 libc.so）
    }
    return 0;
}

/**
 * 对指定库进行 GOT hook（open/openat/fopen）
 * 用于过滤 /proc/self/maps 读取，绕过壳的反调试检测
 *
 * @param libName 库名（如 "libjiagu_vip.so"）
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeGotHookLibrary(
    JNIEnv* env, jclass clazz, jstring libName)
{
    (void)clazz;
    if (libName == nullptr) return;
    const char* name = env->GetStringUTFChars(libName, nullptr);
    LOGI("nativeGotHookLibrary: hooking GOT for %s", name);
    dl_iterate_phdr(got_hook_library_callback, (void*)name);
    env->ReleaseStringUTFChars(libName, name);
}

// 从完整路径提取库名并调用 GOT hook
static void got_hook_library_callback_wrapper(const char* path) {
    if (path == nullptr) return;
    const char* name = strrchr(path, '/');
    if (name != nullptr) name++; else name = path;
    LOGI("got_hook_wrapper: hooking GOT for %s (from %s)", name, path);

    // 枚举所有已加载的库，检查是否能找到目标
    int found = 0;
    dl_iterate_phdr([](struct dl_phdr_info* info, size_t size, void* data) -> int {
        int* count = (int*)data;
        if (info->dlpi_name != nullptr && strstr(info->dlpi_name, "libjiagu") != nullptr) {
            LOGI("got_hook_enum: found %s at %p", info->dlpi_name, (void*)info->dlpi_addr);
            (*count)++;
        }
        return 0;
    }, &found);
    LOGI("got_hook_wrapper: found %d jiagu libraries", found);

    dl_iterate_phdr(got_hook_library_callback, (void*)name);
}

/**
 * 预解析 ELF 文件，记录 GOT 条目偏移量。
 * dlopen 返回后立即用这些偏移量 hook GOT（抢在 constructor 读 maps 之前）。
 */
struct GotEntryInfo {
    uintptr_t open_offset;
    uintptr_t openat_offset;
    uintptr_t fopen_offset;
    uintptr_t readlink_offset;
    uintptr_t exit_offset;
    uintptr_t _exit_offset;
    uintptr_t abort_offset;
    bool has_open;
    bool has_openat;
    bool has_fopen;
    bool has_readlink;
    bool has_exit;
    bool has__exit;
    bool has_abort;
};

static size_t get_elf_r_sym(uintptr_t r_info) {
#if defined(__LP64__)
    return ELF64_R_SYM(r_info);
#else
    return ELF32_R_SYM(r_info);
#endif
}

static uintptr_t elf_vaddr_to_file_offset(
    const ElfW(Phdr)* phdrs,
    int phnum,
    uintptr_t vaddr
) {
    for (int i = 0; i < phnum; i++) {
        const auto& phdr = phdrs[i];
        if (phdr.p_type != PT_LOAD) continue;
        uintptr_t start = phdr.p_vaddr;
        uintptr_t end = phdr.p_vaddr + phdr.p_filesz;
        if (vaddr >= start && vaddr < end) {
            return phdr.p_offset + (vaddr - phdr.p_vaddr);
        }
    }
    return 0;
}

static void* g_libart_handle = nullptr;
static uintptr_t g_libart_base = 0;
static std::string g_libart_path;

struct LibraryLookup {
    const char* name;
    uintptr_t base;
    std::string path;
};

static int find_loaded_library_callback(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    auto* lookup = static_cast<LibraryLookup*>(data);
    if (info == nullptr || info->dlpi_name == nullptr || lookup == nullptr) return 0;
    if (strstr(info->dlpi_name, lookup->name) == nullptr) return 0;

    lookup->base = static_cast<uintptr_t>(info->dlpi_addr);
    lookup->path = info->dlpi_name;
    return 1;
}

static bool refresh_libart_info() {
    if (g_libart_base != 0 && !g_libart_path.empty()) return true;

    LibraryLookup lookup{"libart.so", 0, ""};
    dl_iterate_phdr(find_loaded_library_callback, &lookup);
    if (lookup.base == 0 || lookup.path.empty()) {
        LOGW("libart resolver: loaded libart.so not found");
        return false;
    }

    g_libart_base = lookup.base;
    g_libart_path = lookup.path;
    LOGI("libart resolver: path=%s base=%p", g_libart_path.c_str(), (void*)g_libart_base);
    return true;
}

static void* resolve_symbol_from_elf_sections(
    const char* path,
    uintptr_t load_base,
    std::string_view requested,
    bool prefix_match
) {
    if (path == nullptr || load_base == 0 || requested.empty()) return nullptr;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGW("libart resolver: open failed for %s errno=%d", path, errno);
        return nullptr;
    }

    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        LOGW("libart resolver: fstat failed for %s errno=%d", path, errno);
        close(fd);
        return nullptr;
    }

    void* mapped = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) {
        LOGW("libart resolver: mmap failed for %s errno=%d", path, errno);
        return nullptr;
    }

    auto cleanup = [&]() { munmap(mapped, st.st_size); };
    auto* ehdr = reinterpret_cast<ElfW(Ehdr)*>(mapped);
    if (st.st_size < static_cast<off_t>(sizeof(ElfW(Ehdr))) ||
        memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr->e_shoff == 0 ||
        ehdr->e_shnum == 0 ||
        ehdr->e_shentsize != sizeof(ElfW(Shdr))) {
        cleanup();
        return nullptr;
    }

    auto sh_end = ehdr->e_shoff + static_cast<uint64_t>(ehdr->e_shnum) * sizeof(ElfW(Shdr));
    if (sh_end > static_cast<uint64_t>(st.st_size)) {
        cleanup();
        return nullptr;
    }

    auto* shdrs = reinterpret_cast<ElfW(Shdr)*>(static_cast<uint8_t*>(mapped) + ehdr->e_shoff);
    for (int i = 0; i < ehdr->e_shnum; i++) {
        const auto& sym_section = shdrs[i];
        if (sym_section.sh_type != SHT_SYMTAB && sym_section.sh_type != SHT_DYNSYM) continue;
        if (sym_section.sh_entsize != sizeof(ElfW(Sym)) || sym_section.sh_link >= ehdr->e_shnum) continue;

        const auto& str_section = shdrs[sym_section.sh_link];
        if (sym_section.sh_offset + sym_section.sh_size > static_cast<uint64_t>(st.st_size) ||
            str_section.sh_offset + str_section.sh_size > static_cast<uint64_t>(st.st_size) ||
            str_section.sh_size == 0) {
            continue;
        }

        auto* symbols = reinterpret_cast<ElfW(Sym)*>(static_cast<uint8_t*>(mapped) + sym_section.sh_offset);
        auto* strings = reinterpret_cast<const char*>(mapped) + str_section.sh_offset;
        size_t symbol_count = sym_section.sh_size / sizeof(ElfW(Sym));

        for (size_t j = 0; j < symbol_count; j++) {
            if (symbols[j].st_name == 0 || symbols[j].st_name >= str_section.sh_size) continue;
            if (symbols[j].st_value == 0) continue;

            const char* name = strings + symbols[j].st_name;
            bool matched = prefix_match
                ? strncmp(name, requested.data(), requested.size()) == 0
                : requested == name;
            if (!matched) continue;

            void* resolved = reinterpret_cast<void*>(load_base + symbols[j].st_value);
            LOGI("libart resolver: %s match %.*s -> %s at %p",
                 prefix_match ? "prefix" : "exact",
                 (int)requested.size(), requested.data(), name, resolved);
            cleanup();
            return resolved;
        }
    }

    cleanup();
    return nullptr;
}

static void* resolve_libart_symbol(std::string_view symbol_name, bool prefix_match) {
    if (symbol_name.empty()) return nullptr;

    if (!prefix_match && g_libart_handle != nullptr) {
        std::string name(symbol_name);
        void* addr = dlsym(g_libart_handle, name.c_str());
        if (addr != nullptr) return addr;
    }

    if (!refresh_libart_info()) return nullptr;
    void* addr = resolve_symbol_from_elf_sections(
        g_libart_path.c_str(),
        g_libart_base,
        symbol_name,
        prefix_match
    );
    if (addr == nullptr) {
        LOGD("libart resolver: %s not found: %.*s",
             prefix_match ? "prefix" : "exact",
             (int)symbol_name.size(), symbol_name.data());
    }
    return addr;
}

static GotEntryInfo pre_parse_elf_got(const char* so_path) {
    GotEntryInfo info = {};
    int fd = open(so_path, O_RDONLY);
    if (fd < 0) return info;

    struct stat st;
    fstat(fd, &st);
    void* mapped = mmap(NULL, st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) return info;

    auto* ehdr = (ElfW(Ehdr)*)mapped;
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) { munmap(mapped, st.st_size); return info; }

    // 找 PT_DYNAMIC
    auto* phdrs = (ElfW(Phdr)*)((uintptr_t)mapped + ehdr->e_phoff);
    ElfW(Dyn)* dyn_table = nullptr;
    size_t dyn_count = 0;
    for (int i = 0; i < ehdr->e_phnum; i++) {
        if (phdrs[i].p_type == PT_DYNAMIC) {
            dyn_table = (ElfW(Dyn)*)((uintptr_t)mapped + phdrs[i].p_offset);
            dyn_count = phdrs[i].p_filesz / sizeof(ElfW(Dyn));
            break;
        }
    }
    if (!dyn_table) { munmap(mapped, st.st_size); return info; }

    uintptr_t strtab_off = 0, symtab_off = 0, jmprel_off = 0;
    size_t jmprel_size = 0;
    bool jmprel_is_rela = false;
    for (size_t i = 0; i < dyn_count; i++) {
        switch (dyn_table[i].d_tag) {
            case DT_STRTAB: strtab_off = dyn_table[i].d_un.d_ptr; break;
            case DT_SYMTAB: symtab_off = dyn_table[i].d_un.d_ptr; break;
            case DT_JMPREL: jmprel_off = dyn_table[i].d_un.d_ptr; break;
            case DT_PLTRELSZ: jmprel_size = dyn_table[i].d_un.d_val; break;
            case DT_PLTREL: jmprel_is_rela = dyn_table[i].d_un.d_val == DT_RELA; break;
        }
    }

    if (strtab_off && symtab_off && jmprel_off && jmprel_size > 0) {
        uintptr_t strtab_file_off = elf_vaddr_to_file_offset(phdrs, ehdr->e_phnum, strtab_off);
        uintptr_t symtab_file_off = elf_vaddr_to_file_offset(phdrs, ehdr->e_phnum, symtab_off);
        uintptr_t jmprel_file_off = elf_vaddr_to_file_offset(phdrs, ehdr->e_phnum, jmprel_off);
        if (strtab_file_off == 0 || symtab_file_off == 0 || jmprel_file_off == 0) {
            LOGW("pre_parse_elf_got: vaddr conversion failed for %s", so_path);
            munmap(mapped, st.st_size);
            return info;
        }

        auto* strtab = (const char*)((uintptr_t)mapped + strtab_file_off);
        auto* symtab = (ElfW(Sym)*)((uintptr_t)mapped + symtab_file_off);

        auto record_symbol = [&](uintptr_t r_info, uintptr_t r_offset) {
            size_t sym_idx = get_elf_r_sym(r_info);
            const char* name = strtab + symtab[sym_idx].st_name;

            if (strcmp(name, "open") == 0) { info.open_offset = r_offset; info.has_open = true; }
            else if (strcmp(name, "openat") == 0) { info.openat_offset = r_offset; info.has_openat = true; }
            else if (strcmp(name, "fopen") == 0) { info.fopen_offset = r_offset; info.has_fopen = true; }
            else if (strcmp(name, "readlink") == 0) { info.readlink_offset = r_offset; info.has_readlink = true; }
            else if (strcmp(name, "exit") == 0) { info.exit_offset = r_offset; info.has_exit = true; }
            else if (strcmp(name, "_exit") == 0) { info._exit_offset = r_offset; info.has__exit = true; }
            else if (strcmp(name, "abort") == 0) { info.abort_offset = r_offset; info.has_abort = true; }
        };

        if (jmprel_is_rela) {
            auto* jmprel = (ElfW(Rela)*)((uintptr_t)mapped + jmprel_file_off);
            size_t jmprel_count = jmprel_size / sizeof(ElfW(Rela));
            for (size_t j = 0; j < jmprel_count; j++) {
                record_symbol(jmprel[j].r_info, jmprel[j].r_offset);
            }
        } else {
            auto* jmprel = (ElfW(Rel)*)((uintptr_t)mapped + jmprel_file_off);
            size_t jmprel_count = jmprel_size / sizeof(ElfW(Rel));
            for (size_t j = 0; j < jmprel_count; j++) {
                record_symbol(jmprel[j].r_info, jmprel[j].r_offset);
            }
        }
    }

    munmap(mapped, st.st_size);
    return info;
}

struct LoadedLibraryLookup {
    const char* full_path;
    const char* basename;
    uintptr_t base_addr;
};

static int find_loaded_library_base_callback(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    auto* lookup = static_cast<LoadedLibraryLookup*>(data);
    if (info == nullptr || info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;

    if ((lookup->full_path != nullptr && strcmp(info->dlpi_name, lookup->full_path) == 0) ||
        (lookup->basename != nullptr && strstr(info->dlpi_name, lookup->basename) != nullptr)) {
        lookup->base_addr = static_cast<uintptr_t>(info->dlpi_addr);
        LOGI("find_loaded_library_base: %s base=%p", info->dlpi_name, (void*)lookup->base_addr);
        return 1;
    }
    return 0;
}

static uintptr_t find_loaded_library_base(const char* path) {
    if (path == nullptr) return 0;
    const char* basename = strrchr(path, '/');
    basename = basename != nullptr ? basename + 1 : path;

    LoadedLibraryLookup lookup {
        path,
        basename,
        0
    };
    dl_iterate_phdr(find_loaded_library_base_callback, &lookup);
    return lookup.base_addr;
}

// 立即 hook GOT（用预解析的偏移量）
static void got_hook_immediate(const char* path, const GotEntryInfo& info) {
    uintptr_t base_addr = find_loaded_library_base(path);
    if (base_addr == 0) {
        LOGW("got_hook_immediate: cannot find load base for %s", path ? path : "null");
        return;
    }

    int page_size = sysconf(_SC_PAGESIZE);

    auto patch = [&](uintptr_t offset, void* hook_fn, void** orig_ptr) {
        if (offset == 0) return;
        ElfW(Addr)* got = (ElfW(Addr)*)(base_addr + offset);
        if ((void*)*got == hook_fn) return;
        if (*orig_ptr == nullptr) {
            *orig_ptr = (void*)*got;
        }
        uintptr_t page = (uintptr_t)got & ~(page_size - 1);
        if (mprotect((void*)page, page_size, PROT_READ | PROT_WRITE) != 0) {
            LOGW("got_hook_immediate: mprotect RW failed for offset=%p errno=%d",
                 (void*)offset, errno);
            return;
        }
        *got = (ElfW(Addr))hook_fn;
        if (mprotect((void*)page, page_size, PROT_READ) != 0) {
            LOGW("got_hook_immediate: mprotect R failed for offset=%p errno=%d",
                 (void*)offset, errno);
        }
    };

    if (info.has_open) { patch(info.open_offset, (void*)got_hooked_open, (void**)&got_orig_open); }
    if (info.has_openat) { patch(info.openat_offset, (void*)got_hooked_openat, (void**)&got_orig_openat); }
    if (info.has_fopen) { patch(info.fopen_offset, (void*)got_hooked_fopen, (void**)&got_orig_fopen); }
    if (info.has_readlink) { patch(info.readlink_offset, (void*)got_hooked_readlink, (void**)&got_orig_readlink); }
    if (info.has_exit) { patch(info.exit_offset, (void*)got_hooked_exit, (void**)&got_orig_exit); }
    if (info.has__exit) { patch(info._exit_offset, (void*)got_hooked__exit, (void**)&got_orig__exit); }
    LOGI("got_hook_immediate: base=%p open=%d openat=%d fopen=%d readlink=%d exit=%d _exit=%d abort=%d",
         (void*)base_addr, info.has_open, info.has_openat, info.has_fopen, info.has_readlink,
         info.has_exit, info.has__exit, info.has_abort);
}

/**
 * LoaderFactory 专用: 通过 dlopen 直接加载加固壳 native 库，并手动调用 JNI_OnLoad
 *
 * 关键时序：
 * 1. 预解析 ELF（记录 GOT 偏移量）
 * 2. dlopen（constructor 执行，可能读 /proc/self/maps）
 * 3. 立即 hook GOT（用预解析偏移量，微秒级）
 * 4. 手动调用 JNI_OnLoad
 *
 * @param libPaths 要加载的 .so 文件绝对路径数组
 * @return 成功加载并调用 JNI_OnLoad 的数量
 */
JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativePreloadLibraries(
    JNIEnv* env, jclass clazz, jobjectArray libPaths)
{
    (void)clazz;
    if (libPaths == nullptr) return 0;

    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr) {
        LOGE("nativePreloadLibraries: GetJavaVM failed");
        return 0;
    }

    jsize count = env->GetArrayLength(libPaths);
    jint loaded = 0;

    for (jsize i = 0; i < count; i++) {
        auto jPath = (jstring)env->GetObjectArrayElement(libPaths, i);
        if (jPath == nullptr) continue;

        const char* path = env->GetStringUTFChars(jPath, nullptr);
        if (path == nullptr) { env->DeleteLocalRef(jPath); continue; }

        // Step 0: 预解析 ELF，记录 GOT 条目偏移量
        GotEntryInfo got_info = pre_parse_elf_got(path);
        LOGI("nativePreloadLibraries: pre-parsed %s (open=%d openat=%d fopen=%d readlink=%d exit=%d _exit=%d abort=%d)",
             path, got_info.has_open, got_info.has_openat, got_info.has_fopen, got_info.has_readlink,
             got_info.has_exit, got_info.has__exit, got_info.has_abort);

        // Step 1: dlopen 加载 .so（constructor 可能在此执行）
        LOGI("nativePreloadLibraries: dlopen %s", path);
        void* handle = dlopen(path, RTLD_NOW);
        if (handle == nullptr) {
            const char* err = dlerror();
            LOGW("nativePreloadLibraries: dlopen FAILED %s: %s", path, err ? err : "unknown");
            env->ReleaseStringUTFChars(jPath, path);
            env->DeleteLocalRef(jPath);
            continue;
        }
        LOGI("nativePreloadLibraries: dlopen OK %s", path);

        // Step 1.5: 立即 hook GOT（用预解析偏移量，微秒级完成）
        // 此时 constructor 可能还在执行，但 GOT hook 会拦截后续的 open/fopen 调用
        got_hook_immediate(path, got_info);

        // Step 2: dlsym 找到 JNI_OnLoad
        auto jniOnLoad = (jint (*)(JavaVM*, void*))dlsym(handle, "JNI_OnLoad");
        if (jniOnLoad == nullptr) {
            LOGI("nativePreloadLibraries: no JNI_OnLoad in %s (pure native)", path);
            loaded++;
            env->ReleaseStringUTFChars(jPath, path);
            env->DeleteLocalRef(jPath);
            continue;
        }

        // Step 3: 手动调用 JNI_OnLoad
        LOGI("nativePreloadLibraries: calling JNI_OnLoad for %s", path);
        jint onLoadResult = jniOnLoad(vm, nullptr);
        if (onLoadResult < 0) {
            // 壳的反检测导致 JNI_OnLoad 返回 -1
            // 但壳可能已经部分初始化（解密了部分 DEX、注册了部分方法）
            // 强制继续，不视为失败
            LOGW("nativePreloadLibraries: JNI_OnLoad returned %d for %s (forcing continue anyway)",
                 onLoadResult, path);
            loaded++; // 强制计为成功
        } else {
            LOGI("nativePreloadLibraries: JNI_OnLoad returned %d (JNI_VERSION_%d) for %s",
                 onLoadResult, onLoadResult, path);
            loaded++;
        }

        env->ReleaseStringUTFChars(jPath, path);
        env->DeleteLocalRef(jPath);
    }

    LOGI("nativePreloadLibraries: loaded %d/%d", loaded, count);
    return loaded;
}

/**
 * 只做 dlopen + GOT hook，不调 JNI_OnLoad。
 * 用于混合方案：先 dlopen 加载并 hook GOT，再通过 Runtime.nativeLoad 让 ART 做 ClassLoader 绑定 + JNI_OnLoad。
 *
 * @param libPath .so 文件绝对路径
 * @return dlopen handle 的低 32 位（0 = 失败）
 */
JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeDlopenOnly(
    JNIEnv* env, jclass clazz, jstring libPath)
{
    (void)clazz;
    if (libPath == nullptr) return 0;

    const char* path = env->GetStringUTFChars(libPath, nullptr);
    if (path == nullptr) return 0;

    // 预解析 ELF GOT
    GotEntryInfo got_info = pre_parse_elf_got(path);
    LOGI("nativeDlopenOnly: pre-parsed %s (open=%d openat=%d fopen=%d readlink=%d exit=%d _exit=%d abort=%d)",
         path, got_info.has_open, got_info.has_openat, got_info.has_fopen, got_info.has_readlink,
         got_info.has_exit, got_info.has__exit, got_info.has_abort);

    // dlopen 加载（不调 JNI_OnLoad）
    void* handle = dlopen(path, RTLD_NOW);
    if (handle == nullptr) {
        const char* err = dlerror();
        LOGW("nativeDlopenOnly: dlopen FAILED %s: %s", path, err ? err : "unknown");
        env->ReleaseStringUTFChars(libPath, path);
        return 0;
    }
    LOGI("nativeDlopenOnly: dlopen OK %s", path);

    // 立即安装 GOT hook
    got_hook_immediate(path, got_info);

    env->ReleaseStringUTFChars(libPath, path);

    // 返回 handle 的低 32 位作为成功标志
    return (jint)((uintptr_t)handle & 0xFFFFFFFF);
}

/**
 * 通过 JNI 调用 Runtime.nativeLoad(path, classLoader, callerClass)
 *
 * JNI 调用绕过 Java 层 hidden API 检查。
 * 传入 guest ClassLoader 确保库加载到正确的命名空间。
 *
 * @param libPath .so 文件绝对路径
 * @param classLoader guest ClassLoader (PathClassLoader)
 * @param callerClass guest 中的调用者类 (如 com.stub.StubApp)
 * @return JNI_OnLoad 返回值 (0=成功, <0=失败)
 */
JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeLoadLibraryForGuest(
    JNIEnv* env, jclass clazz, jstring libPath, jobject classLoader, jobject callerClass)
{
    (void)clazz;
    if (libPath == nullptr) return -1;

    // 找到 Runtime 类和 nativeLoad 方法
    jclass runtimeClass = env->FindClass("java/lang/Runtime");
    if (runtimeClass == nullptr) {
        LOGE("nativeLoadLibraryForGuest: Runtime class not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -2;
    }

    // 获取 Runtime.getRuntime() 实例
    jmethodID getRuntime = env->GetStaticMethodID(runtimeClass, "getRuntime", "()Ljava/lang/Runtime;");
    if (getRuntime == nullptr) {
        LOGE("nativeLoadLibraryForGuest: getRuntime not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(runtimeClass);
        return -3;
    }
    jobject runtime = env->CallStaticObjectMethod(runtimeClass, getRuntime);
    if (runtime == nullptr) {
        LOGE("nativeLoadLibraryForGuest: getRuntime returned null");
        env->DeleteLocalRef(runtimeClass);
        return -4;
    }

    // 获取 nativeLoad(String, ClassLoader, Class) 方法
    bool nativeLoadHasCaller = true;
    jmethodID nativeLoad = env->GetStaticMethodID(
        runtimeClass, "nativeLoad",
        "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;");
    if (nativeLoad == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        nativeLoadHasCaller = false;
        nativeLoad = env->GetStaticMethodID(
            runtimeClass, "nativeLoad",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;");
    }

    if (nativeLoad == nullptr) {
        LOGE("nativeLoadLibraryForGuest: nativeLoad method not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(runtimeClass);
        env->DeleteLocalRef(runtime);
        return -5;
    }

    // 调用 runtime.nativeLoad(libPath, classLoader, callerClass)
    const char* path = env->GetStringUTFChars(libPath, nullptr);
    LOGI("nativeLoadLibraryForGuest: calling nativeLoad(%s)", path ? path : "null");
    env->ReleaseStringUTFChars(libPath, path);

    jstring error = nativeLoadHasCaller
        ? (jstring)env->CallStaticObjectMethod(runtimeClass, nativeLoad, libPath, classLoader, callerClass)
        : (jstring)env->CallStaticObjectMethod(runtimeClass, nativeLoad, libPath, classLoader);
    env->DeleteLocalRef(runtimeClass);
    env->DeleteLocalRef(runtime);

    if (error == nullptr) {
        // null 表示成功
        LOGI("nativeLoadLibraryForGuest: SUCCESS");
        return 0;
    }

    const char* errStr = env->GetStringUTFChars(error, nullptr);
    LOGE("nativeLoadLibraryForGuest: FAILED: %s", errStr ? errStr : "unknown");
    env->ReleaseStringUTFChars(error, errStr);
    env->DeleteLocalRef(error);
    return -6;
}

/**
 * hook_FindClass: 拦截 JNI_OnLoad 中的所有 FindClass 调用
 *
 * 策略：先试 guest ClassLoader，找不到再走原始路径。
 * 这样壳的 JNI_OnLoad 无论查找什么类（StubApp、内部工具类等）都能通过。
 * 系统类（java.lang.* 等）在 guest ClassLoader 中找不到，自动 fallback 到 boot。
 */
static jclass hooked_FindClass(JNIEnv* env, const char* name) {
    if (name == nullptr) {
        return nullptr;
    }

    // Keep framework/JDK classes on ART's original FindClass path. Loading
    // java.net/android.system exception classes through the guest loader
    // polluted network error handling and produced false "network abnormal"
    // states in QQ Reader.
    if (strncmp(name, "java/", 5) == 0 ||
        strncmp(name, "javax/", 6) == 0 ||
        strncmp(name, "android/", 8) == 0 ||
        strncmp(name, "androidx/", 9) == 0 ||
        strncmp(name, "dalvik/", 7) == 0 ||
        strncmp(name, "libcore/", 8) == 0 ||
        strncmp(name, "org/json/", 9) == 0) {
        if (g_orig_findclass != nullptr) {
            using FindClassFn = jclass(*)(JNIEnv*, const char*);
            return ((FindClassFn)g_orig_findclass)(env, name);
        }
        return nullptr;
    }

    if (g_guest_classloader != nullptr && g_classloader_loadclass != nullptr) {
        // 转换 "/" -> "." 给 ClassLoader.loadClass
        std::string dotName(name);
        for (auto& c : dotName) { if (c == '/') c = '.'; }

        jstring jClassName = env->NewStringUTF(dotName.c_str());
        if (jClassName != nullptr) {
            // 先试 guest ClassLoader（覆盖壳类 + app 类）
            jclass clazz = (jclass)env->CallObjectMethod(
                g_guest_classloader, g_classloader_loadclass, jClassName);
            env->DeleteLocalRef(jClassName);

            if (clazz != nullptr) {
                LOGI("hooked_FindClass: guest hit \"%s\"", name);
                return clazz;
            }
            // loadClass 抛了 ClassNotFoundException，清掉异常走 fallback
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGD("hooked_FindClass: guest miss \"%s\", trying original", name);
        }
    }

    // guest 没找到（系统类、内部类等），走原始 FindClass
    if (g_orig_findclass != nullptr) {
        using FindClassFn = jclass(*)(JNIEnv*, const char*);
        jclass result = ((FindClassFn)g_orig_findclass)(env, name);
        if (result == nullptr) {
            LOGW("hooked_FindClass: original also failed for \"%s\"", name ? name : "null");
        }
        return result;
    }

    return nullptr;
}

/**
 * 设置 guest ClassLoader 并安装 FindClass hook
 *
 * 必须在 System.loadLibrary("jiagu_vip") 之前调用。
 * hook 仅在 JNI_OnLoad 执行期间有效（单线程）。
 */
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSetupFindClassHook(
    JNIEnv* env, jclass clazz, jobject classLoader, jobjectArray targetClassNames)
{
    (void)clazz;
    if (classLoader == nullptr || targetClassNames == nullptr) return JNI_FALSE;

    // 保存 guest ClassLoader 全局引用
    if (g_guest_classloader != nullptr) {
        env->DeleteGlobalRef(g_guest_classloader);
    }
    g_guest_classloader = env->NewGlobalRef(classLoader);

    // 保存候选类名列表（转换为 slash 格式: "com.stub.StubApp" -> "com/stub/StubApp"）
    g_findclass_targets.clear();
    jsize nameCount = env->GetArrayLength(targetClassNames);
    for (jsize i = 0; i < nameCount; i++) {
        auto jName = (jstring)env->GetObjectArrayElement(targetClassNames, i);
        if (jName == nullptr) continue;
        const char* name = env->GetStringUTFChars(jName, nullptr);
        if (name == nullptr) { env->DeleteLocalRef(jName); continue; }

        std::string slashName;
        for (const char* p = name; *p; p++) {
            slashName += (*p == '.') ? '/' : *p;
        }
        g_findclass_targets.insert(slashName);
        LOGI("nativeSetupFindClassHook: candidate [%d] = %s", i, slashName.c_str());

        env->ReleaseStringUTFChars(jName, name);
        env->DeleteLocalRef(jName);
    }

    if (g_findclass_targets.empty()) {
        LOGW("nativeSetupFindClassHook: no target classes provided");
        return JNI_FALSE;
    }

    // 获取 ClassLoader.loadClass 方法
    jclass clClass = env->FindClass("java/lang/ClassLoader");
    if (clClass == nullptr) {
        LOGE("nativeSetupFindClassHook: ClassLoader class not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
    g_classloader_loadclass = env->GetMethodID(
        clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    env->DeleteLocalRef(clClass);

    if (g_classloader_loadclass == nullptr) {
        LOGE("nativeSetupFindClassHook: loadClass method not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    LOGI("nativeSetupFindClassHook: configured with %d candidate classes", (int)g_findclass_targets.size());
    return JNI_TRUE;
}

/**
 * 在 JNI 函数表中替换 FindClass
 * 仅在 libjiagu_vip.so 的 JNI_OnLoad 执行前调用，之后恢复
 */
JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeInstallFindClassHook(
    JNIEnv* env, jclass clazz)
{
    (void)clazz;
    if (g_guest_classloader == nullptr) {
        LOGW("nativeInstallFindClassHook: no guest ClassLoader set");
        return;
    }

    // 获取 JNI 函数表指针
    void** jniFunctions = *reinterpret_cast<void***>(env);

    // FindClass 在 JNI 函数表中的位置
    // JNI 1.6+: reserved0(0), reserved1(1), reserved2(2), reserved3(3),
    //           GetVersion(4), DefineClass(5), FindClass(6)
    constexpr int FIND_CLASS_INDEX = 6;

    // 保存原始指针
    g_orig_findclass = jniFunctions[FIND_CLASS_INDEX];

    // 替换 JNI 函数表中的 FindClass
    // 需要先 mprotect 修改为可写
    uintptr_t page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = (uintptr_t)&jniFunctions[FIND_CLASS_INDEX] & ~(page_size - 1);
    if (mprotect((void*)page_start, page_size, PROT_READ | PROT_WRITE) == 0) {
        jniFunctions[FIND_CLASS_INDEX] = (void*)hooked_FindClass;
        // 恢复页面保护
        mprotect((void*)page_start, page_size, PROT_READ);
        LOGI("nativeInstallFindClassHook: installed (original=%p)", g_orig_findclass);
    } else {
        LOGE("nativeInstallFindClassHook: mprotect failed");
    }
}

/**
 * 手动注册 StubApp 的 native 方法（兜底方案）
 *
 * 当 FindClass hook 不生效时，直接用 RegisterNatives 注册 interface20() 等方法。
 * 返回一个默认值让壳的初始化流程能继续。
 */

// 前向声明：YWLoginManager 和 Fock 的 stub 函数（stub_interface_app 中使用）
static jobject JNICALL stub_ywlogin_getInstance(JNIEnv* env, jclass clazz);
static void JNICALL stub_ywlogin_registerParameter(JNIEnv* env, jclass clazz, jobject getter);
static jstring JNICALL stub_easyencrypt_md5_key(JNIEnv* env, jclass clazz);
static jobject JNICALL stub_online_getDownloadChap(JNIEnv* env, jobject thiz);
static jobject JNICALL stub_online_getDownloadChapters(JNIEnv* env, jobject thiz);
static void JNICALL stub_online_setToDownloadChapters(JNIEnv* env, jobject thiz, jobject chapters);
static jboolean JNICALL stub_online_isBackgroundRun(JNIEnv* env, jobject thiz);
static void JNICALL stub_online_setBackgroundRun(JNIEnv* env, jobject thiz, jboolean value);
static jboolean JNICALL stub_online_hasRetryTag(JNIEnv* env, jobject thiz);
static void JNICALL stub_online_setRetryTag(JNIEnv* env, jobject thiz);
static jobject JNICALL stub_online_getListener(JNIEnv* env, jobject thiz);
static void JNICALL stub_online_setListener(JNIEnv* env, jobject thiz, jobject listener);
static jstring JNICALL stub_online_getScene(JNIEnv* env, jobject thiz);
static void JNICALL stub_online_setScene(JNIEnv* env, jobject thiz, jstring scene);
static void JNICALL stub_online_run(JNIEnv* env, jobject thiz);
static jstring JNICALL stub_fock_get_encrypt_pool(JNIEnv* env, jclass clazz, jstring key);
static jobject JNICALL stub_fock_get_encrypt_bean(JNIEnv* env, jclass clazz, jstring key);
static void JNICALL stub_fock_save_encrypt_pool(JNIEnv* env, jclass clazz, jstring key, jobject bean);
static void JNICALL stub_fock_update_encrypt_bean(JNIEnv* env, jclass clazz, jstring key, jstring value, jstring sign);
static jstring JNICALL stub_fock_sign_string(JNIEnv* env, jclass clazz, jstring value);

// interface5(Application) 的 stub 实现
// 壳在 Application 创建时调用此方法，之后才会调用 initLoginSDK()
// 在这里重新注册业务 stub，确保 YWLoginManager.getInstance 等方法有实现
static void JNICALL stub_interface_app(JNIEnv* env, jclass clazz, jobject app) {
    LOGI("stub_interface_app called; keeping guest business native registrations untouched");
    return;

    // 重新注册业务 stub（壳的 interface20 可能覆盖了之前的注册）
    if (g_guest_classloader != nullptr && g_classloader_loadclass != nullptr) {
        // 重新注册 YWLoginManager.getInstance
        jstring ywName = env->NewStringUTF("com.yuewen.ywlogin.login.YWLoginManager");
        jclass ywClass = (jclass)env->CallObjectMethod(g_guest_classloader, g_classloader_loadclass, ywName);
        env->DeleteLocalRef(ywName);

        if (ywClass != nullptr) {
            JNINativeMethod ywMethods[] = {
                {const_cast<char*>("getInstance"),
                 const_cast<char*>("()Lcom/yuewen/ywlogin/login/YWLoginManager;"),
                 (void*)stub_ywlogin_getInstance},
                {const_cast<char*>("registerParameter"),
                 const_cast<char*>("(Lcom/yuewen/ywlogin/login/IParameterGetter;)V"),
                 (void*)stub_ywlogin_registerParameter},
            };
            jint ret = env->RegisterNatives(ywClass, ywMethods, 2);
            if (ret == JNI_OK) {
                LOGI("stub_interface_app: re-registered YWLoginManager stubs OK");
            } else {
                if (env->ExceptionCheck()) env->ExceptionClear();
                LOGW("stub_interface_app: re-register YWLoginManager failed");
            }
            env->DeleteLocalRef(ywClass);
        } else {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGW("stub_interface_app: YWLoginManager class not found");
        }

        // 重新注册 Fock.sign stub
        LOGI("stub_interface_app: leave Fock/qrencrypt signing classes untouched");
    } else {
        LOGW("stub_interface_app: no guest ClassLoader, skip re-registration");
    }
}

static void register_qrencrypt_stubs(JNIEnv* env) {
    if (g_guest_classloader == nullptr || g_classloader_loadclass == nullptr) {
        LOGW("register_qrencrypt_stubs: no guest ClassLoader");
        return;
    }

    int registered = 0;
    int failed = 0;

    jstring easyName = env->NewStringUTF("com.qq.reader.common.utils.crypto.EasyEncrypt");
    jclass easyClass = (jclass)env->CallObjectMethod(g_guest_classloader, g_classloader_loadclass, easyName);
    env->DeleteLocalRef(easyName);
    if (easyClass != nullptr) {
        JNINativeMethod method = {
            const_cast<char*>("getMd5Key"),
            const_cast<char*>("()Ljava/lang/String;"),
            (void*)stub_easyencrypt_md5_key
        };
        if (env->RegisterNatives(easyClass, &method, 1) == JNI_OK) {
            registered++;
            LOGI("register_qrencrypt_stubs: EasyEncrypt.getMd5Key OK");
        } else {
            if (env->ExceptionCheck()) env->ExceptionClear();
            failed++;
            LOGW("register_qrencrypt_stubs: EasyEncrypt.getMd5Key failed");
        }
        env->DeleteLocalRef(easyClass);
    } else {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("register_qrencrypt_stubs: EasyEncrypt class not found");
    }

    jstring poolName = env->NewStringUTF("com.qq.reader.qrencrypt.fock.FockKeyPoolCache");
    jclass poolClass = (jclass)env->CallObjectMethod(g_guest_classloader, g_classloader_loadclass, poolName);
    env->DeleteLocalRef(poolName);
    if (poolClass != nullptr) {
        JNINativeMethod methods[] = {
            {const_cast<char*>("getEncryptPool"),
             const_cast<char*>("(Ljava/lang/String;)Ljava/lang/String;"),
             (void*)stub_fock_get_encrypt_pool},
            {const_cast<char*>("getFockEncryptBean"),
             const_cast<char*>("(Ljava/lang/String;)Lcom/qq/reader/qrencrypt/fock/FockEncryptBean;"),
             (void*)stub_fock_get_encrypt_bean},
            {const_cast<char*>("saveEncryptPool"),
             const_cast<char*>("(Ljava/lang/String;Lcom/qq/reader/qrencrypt/fock/FockEncryptBean;)V"),
             (void*)stub_fock_save_encrypt_pool},
            {const_cast<char*>("updateForckEncryptBean"),
             const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"),
             (void*)stub_fock_update_encrypt_bean},
        };
        jint ret = env->RegisterNatives(poolClass, methods, (jint)(sizeof(methods) / sizeof(methods[0])));
        if (ret == JNI_OK) {
            registered += (int)(sizeof(methods) / sizeof(methods[0]));
            LOGI("register_qrencrypt_stubs: FockKeyPoolCache methods OK");
        } else {
            if (env->ExceptionCheck()) env->ExceptionClear();
            failed++;
            LOGW("register_qrencrypt_stubs: FockKeyPoolCache methods failed");
        }
        env->DeleteLocalRef(poolClass);
    } else {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("register_qrencrypt_stubs: FockKeyPoolCache class not found");
    }

    LOGI("register_qrencrypt_stubs: registered=%d failed=%d", registered, failed);
}

static void JNICALL stub_interface11(JNIEnv* env, jclass clazz, jint value) {
    (void)clazz;
    LOGI("stub_interface11 called value=%d", value);
    register_qrencrypt_stubs(env);
}

// interface20 的 stub 实现：返回 true
static jboolean JNICALL stub_interface_bool(JNIEnv* env, jclass clazz) {
    LOGI("stub_interface_bool called (returning true)");
    return JNI_TRUE;
}

// interface31(String) 的 stub 实现：返回 true
static jboolean JNICALL stub_interface_str(JNIEnv* env, jclass clazz, jstring s) {
    LOGI("stub_interface_str called (returning true)");
    return JNI_TRUE;
}

// YWLoginManager 的 stub native 方法实现

// getInstance() — 创建实例（不调用构造函数，确保不返回 null）
static jobject JNICALL stub_ywlogin_getInstance(JNIEnv* env, jclass clazz) {
    if (g_guest_classloader == nullptr || g_classloader_loadclass == nullptr) {
        LOGW("stub_ywlogin_getInstance: no guest ClassLoader");
        return nullptr;
    }
    jstring className = env->NewStringUTF("com.yuewen.ywlogin.login.YWLoginManager");
    jclass ywClass = (jclass)env->CallObjectMethod(g_guest_classloader, g_classloader_loadclass, className);
    env->DeleteLocalRef(className);
    if (ywClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    // AllocObject 分配实例但不调用构造函数 — 确保不返回 null
    jobject instance = env->AllocObject(ywClass);
    env->DeleteLocalRef(ywClass);
    if (instance) {
        LOGI("stub_ywlogin_getInstance: allocated instance OK");
    } else {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("stub_ywlogin_getInstance: AllocObject failed");
    }
    return instance;
}

// registerParameter(IParameterGetter)V — 空实现
static void JNICALL stub_ywlogin_registerParameter(JNIEnv* env, jclass clazz, jobject getter) {
    LOGI("stub_ywlogin_registerParameter: stub (no-op)");
}

static void JNICALL stub_ywlogin_setDefaultParameters(JNIEnv* env, jclass clazz, jobject application, jobject values) {
    (void)env;
    (void)clazz;
    (void)application;
    (void)values;
    LOGI("stub_ywlogin_setDefaultParameters: stub (no-op)");
}

static void JNICALL stub_ywlogin_fetchSettings(JNIEnv* env, jobject thiz, jobject callback) {
    (void)env;
    (void)thiz;
    (void)callback;
    LOGI("stub_ywlogin_fetchSettings: stub (no-op)");
}

// 其他可能的 native 方法 stub
static void JNICALL stub_ywlogin_void(JNIEnv* env, jclass clazz) {
    // 通用 void stub
}
static jobject JNICALL stub_ywlogin_null(JNIEnv* env, jclass clazz) {
    return nullptr;
}
static jboolean JNICALL stub_ywlogin_false(JNIEnv* env, jclass clazz) {
    return JNI_FALSE;
}
static jstring JNICALL stub_ywlogin_empty_string(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("");
}

static jstring JNICALL stub_easyencrypt_md5_key(JNIEnv* env, jclass clazz) {
    (void)clazz;
    // MD5("Q9*11q^REaDer%Bs1&#@[") from EasyEncrypt.KEY.
    // This avoids the obsolete native registration name mismatch without
    // returning an empty key that breaks caller-side signatures.
    return env->NewStringUTF("51076a5fd0b1fd440c06277855f27311");
}

static jobject get_object_field(JNIEnv* env, jobject thiz, const char* name, const char* sig) {
    if (thiz == nullptr) return nullptr;
    jclass cls = env->GetObjectClass(thiz);
    if (cls == nullptr) return nullptr;
    jfieldID field = env->GetFieldID(cls, name, sig);
    env->DeleteLocalRef(cls);
    if (field == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    return env->GetObjectField(thiz, field);
}

static void set_object_field(JNIEnv* env, jobject thiz, const char* name, const char* sig, jobject value) {
    if (thiz == nullptr) return;
    jclass cls = env->GetObjectClass(thiz);
    if (cls == nullptr) return;
    jfieldID field = env->GetFieldID(cls, name, sig);
    env->DeleteLocalRef(cls);
    if (field == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    env->SetObjectField(thiz, field, value);
}

static jboolean get_boolean_field(JNIEnv* env, jobject thiz, const char* name) {
    if (thiz == nullptr) return JNI_FALSE;
    jclass cls = env->GetObjectClass(thiz);
    if (cls == nullptr) return JNI_FALSE;
    jfieldID field = env->GetFieldID(cls, name, "Z");
    env->DeleteLocalRef(cls);
    if (field == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
    return env->GetBooleanField(thiz, field);
}

static void set_boolean_field(JNIEnv* env, jobject thiz, const char* name, jboolean value) {
    if (thiz == nullptr) return;
    jclass cls = env->GetObjectClass(thiz);
    if (cls == nullptr) return;
    jfieldID field = env->GetFieldID(cls, name, "Z");
    env->DeleteLocalRef(cls);
    if (field == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    env->SetBooleanField(thiz, field, value);
}

static jobject new_collection(JNIEnv* env, const char* className, jobject source) {
    jclass cls = env->FindClass(className);
    if (cls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    jobject out = nullptr;
    if (source != nullptr) {
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/util/Collection;)V");
        if (ctor != nullptr) {
            out = env->NewObject(cls, ctor, source);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            out = nullptr;
        }
    }

    if (out == nullptr) {
        jmethodID emptyCtor = env->GetMethodID(cls, "<init>", "()V");
        if (emptyCtor != nullptr) {
            out = env->NewObject(cls, emptyCtor);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            out = nullptr;
        }
    }

    env->DeleteLocalRef(cls);
    return out;
}

static jobject ensure_online_download_list(JNIEnv* env, jobject thiz) {
    jobject chapters = get_object_field(env, thiz, "downloadChapters", "Ljava/util/List;");
    if (chapters != nullptr) return chapters;

    jobject empty = new_collection(env, "java/util/ArrayList", nullptr);
    if (empty != nullptr) {
        set_object_field(env, thiz, "downloadChapters", "Ljava/util/List;", empty);
    }
    return empty;
}

static jobject JNICALL stub_online_getDownloadChap(JNIEnv* env, jobject thiz) {
    jobject chapters = ensure_online_download_list(env, thiz);
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    if (arrayListClass != nullptr && chapters != nullptr && env->IsInstanceOf(chapters, arrayListClass)) {
        env->DeleteLocalRef(arrayListClass);
        return chapters;
    }
    if (arrayListClass != nullptr) env->DeleteLocalRef(arrayListClass);
    jobject out = new_collection(env, "java/util/ArrayList", chapters);
    if (chapters != nullptr) env->DeleteLocalRef(chapters);
    return out != nullptr ? out : new_collection(env, "java/util/ArrayList", nullptr);
}

static jobject JNICALL stub_online_getDownloadChapters(JNIEnv* env, jobject thiz) {
    jobject chapters = ensure_online_download_list(env, thiz);
    jobject out = new_collection(env, "java/util/HashSet", chapters);
    if (chapters != nullptr) env->DeleteLocalRef(chapters);
    return out != nullptr ? out : new_collection(env, "java/util/HashSet", nullptr);
}

static void JNICALL stub_online_setToDownloadChapters(JNIEnv* env, jobject thiz, jobject chapters) {
    jobject value = chapters;
    if (value == nullptr) {
        value = new_collection(env, "java/util/ArrayList", nullptr);
    }
    set_object_field(env, thiz, "downloadChapters", "Ljava/util/List;", value);
    if (chapters == nullptr && value != nullptr) env->DeleteLocalRef(value);
}

static jboolean JNICALL stub_online_isBackgroundRun(JNIEnv* env, jobject thiz) {
    return get_boolean_field(env, thiz, "mRunInBackground");
}

static void JNICALL stub_online_setBackgroundRun(JNIEnv* env, jobject thiz, jboolean value) {
    set_boolean_field(env, thiz, "mRunInBackground", value);
}

static jboolean JNICALL stub_online_hasRetryTag(JNIEnv* env, jobject thiz) {
    return get_boolean_field(env, thiz, "hasRetryed");
}

static void JNICALL stub_online_setRetryTag(JNIEnv* env, jobject thiz) {
    set_boolean_field(env, thiz, "hasRetryed", JNI_TRUE);
}

static jobject JNICALL stub_online_getListener(JNIEnv* env, jobject thiz) {
    return get_object_field(env, thiz, "mListener", "Lcom/qq/reader/cservice/onlineread/qdaf;");
}

static void JNICALL stub_online_setListener(JNIEnv* env, jobject thiz, jobject listener) {
    set_object_field(env, thiz, "mListener", "Lcom/qq/reader/cservice/onlineread/qdaf;", listener);
}

static jstring JNICALL stub_online_getScene(JNIEnv* env, jobject thiz) {
    jstring scene = (jstring)get_object_field(env, thiz, "mScene", "Ljava/lang/String;");
    return scene != nullptr ? scene : env->NewStringUTF("");
}

static void JNICALL stub_online_setScene(JNIEnv* env, jobject thiz, jstring scene) {
    set_object_field(env, thiz, "mScene", "Ljava/lang/String;", scene);
}

static std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return "";
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static bool put_string_pair(JNIEnv* env, jobject map, const std::string& key, const std::string& value) {
    if (map == nullptr) return false;
    jclass mapClass = env->FindClass("java/util/Map");
    if (mapClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID put = env->GetMethodID(
        mapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(mapClass);
    if (put == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jstring jKey = env->NewStringUTF(key.c_str());
    jstring jValue = env->NewStringUTF(value.c_str());
    jobject old = env->CallObjectMethod(map, put, jKey, jValue);
    if (old != nullptr) env->DeleteLocalRef(old);
    env->DeleteLocalRef(jKey);
    env->DeleteLocalRef(jValue);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

static jstring JNICALL stub_fock_get_encrypt_pool(JNIEnv* env, jclass clazz, jstring key) {
    (void)clazz;
    std::string fuid = jstring_to_string(env, key);
    if (fuid.empty()) {
        LOGI("stub_fock_get_encrypt_pool: empty fuid");
        return nullptr;
    }
    std::shared_lock<std::shared_mutex> lock(g_fock_keypool_mutex);
    auto it = g_fock_keypools.find(fuid);
    if (it == g_fock_keypools.end() || it->second.empty()) {
        LOGI("stub_fock_get_encrypt_pool: miss fuid=%s", fuid.c_str());
        return nullptr;
    }
    const auto& first = *it->second.begin();
    LOGI("stub_fock_get_encrypt_pool: hit fuid=%s version=%s poolLen=%zu",
         fuid.c_str(), first.first.c_str(), first.second.size());
    return env->NewStringUTF(first.second.c_str());
}

static jobject JNICALL stub_fock_get_encrypt_bean(JNIEnv* env, jclass clazz, jstring key) {
    (void)clazz;
    std::string fuidString = jstring_to_string(env, key);
    if (fuidString.empty()) {
        LOGI("stub_fock_get_encrypt_bean: empty fuid -> null");
        return nullptr;
    }

    std::unordered_map<std::string, std::string> pools;
    {
        std::shared_lock<std::shared_mutex> lock(g_fock_keypool_mutex);
        auto it = g_fock_keypools.find(fuidString);
        if (it != g_fock_keypools.end()) {
            pools = it->second;
        }
    }
    if (pools.empty()) {
        LOGI("stub_fock_get_encrypt_bean: miss fuid=%s -> null", fuidString.c_str());
        return nullptr;
    }

    if (g_guest_classloader == nullptr || g_classloader_loadclass == nullptr) {
        LOGW("stub_fock_get_encrypt_bean: no guest ClassLoader");
        return nullptr;
    }
    jstring className = env->NewStringUTF("com.qq.reader.qrencrypt.fock.FockEncryptBean");
    jclass beanClass = (jclass)env->CallObjectMethod(g_guest_classloader, g_classloader_loadclass, className);
    env->DeleteLocalRef(className);
    if (beanClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("stub_fock_get_encrypt_bean: FockEncryptBean class not found");
        return nullptr;
    }

    jclass mapClass = env->FindClass("java/util/HashMap");
    jmethodID mapCtor = mapClass ? env->GetMethodID(mapClass, "<init>", "()V") : nullptr;
    jobject map = (mapClass && mapCtor) ? env->NewObject(mapClass, mapCtor) : nullptr;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        map = nullptr;
    }
    if (mapClass != nullptr) {
        env->DeleteLocalRef(mapClass);
    }
    if (map != nullptr) {
        for (const auto& entry : pools) {
            put_string_pair(env, map, entry.first, entry.second);
        }
    }

    jmethodID ctor = env->GetMethodID(beanClass, "<init>", "(Ljava/lang/String;Ljava/util/Map;)V");
    jobject bean = nullptr;
    if (ctor != nullptr) {
        jstring fuid = env->NewStringUTF(fuidString.c_str());
        bean = env->NewObject(beanClass, ctor, fuid, map);
        env->DeleteLocalRef(fuid);
    }
    if (map != nullptr) {
        env->DeleteLocalRef(map);
    }
    env->DeleteLocalRef(beanClass);
    if (bean == nullptr && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    LOGI("stub_fock_get_encrypt_bean: %s fuid=%s poolCount=%zu",
         bean ? "allocated bean" : "allocation failed", fuidString.c_str(), pools.size());
    return bean;
}

static void JNICALL stub_fock_save_encrypt_pool(JNIEnv* env, jclass clazz, jstring key, jobject bean) {
    (void)clazz;
    std::string fuid = jstring_to_string(env, key);
    if (fuid.empty() || bean == nullptr) {
        LOGI("stub_fock_save_encrypt_pool: skip empty fuid or bean");
        return;
    }

    jclass beanClass = env->GetObjectClass(bean);
    jmethodID getKeyPools = beanClass ? env->GetMethodID(beanClass, "getKeyPools", "()Ljava/util/Map;") : nullptr;
    jobject map = (beanClass && getKeyPools) ? env->CallObjectMethod(bean, getKeyPools) : nullptr;
    if (beanClass != nullptr) env->DeleteLocalRef(beanClass);
    if (env->ExceptionCheck() || map == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGI("stub_fock_save_encrypt_pool: no keyPools fuid=%s", fuid.c_str());
        return;
    }

    jclass mapClass = env->FindClass("java/util/Map");
    jclass setClass = env->FindClass("java/util/Set");
    jclass iteratorClass = env->FindClass("java/util/Iterator");
    jclass entryClass = env->FindClass("java/util/Map$Entry");
    jmethodID entrySet = mapClass ? env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;") : nullptr;
    jmethodID iterator = setClass ? env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;") : nullptr;
    jmethodID hasNext = iteratorClass ? env->GetMethodID(iteratorClass, "hasNext", "()Z") : nullptr;
    jmethodID next = iteratorClass ? env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;") : nullptr;
    jmethodID getKey = entryClass ? env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;") : nullptr;
    jmethodID getValue = entryClass ? env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;") : nullptr;

    int stored = 0;
    if (entrySet && iterator && hasNext && next && getKey && getValue) {
        jobject set = env->CallObjectMethod(map, entrySet);
        jobject it = set ? env->CallObjectMethod(set, iterator) : nullptr;
        while (it != nullptr && env->CallBooleanMethod(it, hasNext) == JNI_TRUE) {
            jobject entry = env->CallObjectMethod(it, next);
            jobject versionObj = entry ? env->CallObjectMethod(entry, getKey) : nullptr;
            jobject poolObj = entry ? env->CallObjectMethod(entry, getValue) : nullptr;
            std::string version = jstring_to_string(env, (jstring)versionObj);
            std::string pool = jstring_to_string(env, (jstring)poolObj);
            if (!version.empty() && !pool.empty()) {
                std::unique_lock<std::shared_mutex> lock(g_fock_keypool_mutex);
                g_fock_keypools[fuid][version] = pool;
                stored++;
            }
            if (versionObj != nullptr) env->DeleteLocalRef(versionObj);
            if (poolObj != nullptr) env->DeleteLocalRef(poolObj);
            if (entry != nullptr) env->DeleteLocalRef(entry);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
        }
        if (it != nullptr) env->DeleteLocalRef(it);
        if (set != nullptr) env->DeleteLocalRef(set);
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    if (mapClass != nullptr) env->DeleteLocalRef(mapClass);
    if (setClass != nullptr) env->DeleteLocalRef(setClass);
    if (iteratorClass != nullptr) env->DeleteLocalRef(iteratorClass);
    if (entryClass != nullptr) env->DeleteLocalRef(entryClass);
    env->DeleteLocalRef(map);
    LOGI("stub_fock_save_encrypt_pool: fuid=%s stored=%d", fuid.c_str(), stored);
}

static void JNICALL stub_fock_update_encrypt_bean(JNIEnv* env, jclass clazz, jstring key, jstring value, jstring sign) {
    (void)clazz;
    std::string fuid = jstring_to_string(env, key);
    std::string version = jstring_to_string(env, value);
    std::string pool = jstring_to_string(env, sign);
    if (fuid.empty() || version.empty() || pool.empty()) {
        LOGI("stub_fock_update_encrypt_bean: skip fuidLen=%zu versionLen=%zu poolLen=%zu",
             fuid.size(), version.size(), pool.size());
        return;
    }
    {
        std::unique_lock<std::shared_mutex> lock(g_fock_keypool_mutex);
        g_fock_keypools[fuid][version] = pool;
    }
    LOGI("stub_fock_update_encrypt_bean: stored fuid=%s version=%s poolLen=%zu",
         fuid.c_str(), version.c_str(), pool.size());
}

static jstring JNICALL stub_fock_sign_md5(JNIEnv* env, jclass clazz, jbyteArray data, jint len) {
    (void)clazz;
    (void)len;
    if (data == nullptr) {
        return env->NewStringUTF("");
    }

    jclass mdClass = env->FindClass("java/security/MessageDigest");
    if (mdClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return env->NewStringUTF("");
    }
    jmethodID getInstance = env->GetStaticMethodID(
        mdClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jmethodID digest = env->GetMethodID(mdClass, "digest", "([B)[B");
    if (getInstance == nullptr || digest == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(mdClass);
        return env->NewStringUTF("");
    }

    jstring algorithm = env->NewStringUTF("MD5");
    jobject md = env->CallStaticObjectMethod(mdClass, getInstance, algorithm);
    env->DeleteLocalRef(algorithm);
    if (env->ExceptionCheck() || md == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(mdClass);
        return env->NewStringUTF("");
    }

    auto digestBytes = (jbyteArray)env->CallObjectMethod(md, digest, data);
    env->DeleteLocalRef(md);
    env->DeleteLocalRef(mdClass);
    if (env->ExceptionCheck() || digestBytes == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return env->NewStringUTF("");
    }

    jsize n = env->GetArrayLength(digestBytes);
    jbyte* bytes = env->GetByteArrayElements(digestBytes, nullptr);
    static const char* hex = "0123456789abcdef";
    std::string out;
    out.reserve((size_t)n * 2);
    for (jsize i = 0; i < n; i++) {
        unsigned char b = static_cast<unsigned char>(bytes[i]);
        out.push_back(hex[b >> 4]);
        out.push_back(hex[b & 0x0f]);
    }
    env->ReleaseByteArrayElements(digestBytes, bytes, JNI_ABORT);
    env->DeleteLocalRef(digestBytes);
    return env->NewStringUTF(out.c_str());
}

static jstring JNICALL stub_fock_sign_string(JNIEnv* env, jclass clazz, jstring value) {
    (void)clazz;
    if (value == nullptr) {
        return env->NewStringUTF("");
    }

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return env->NewStringUTF("");
    }
    jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    if (getBytes == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(stringClass);
        return env->NewStringUTF("");
    }

    jstring charset = env->NewStringUTF("UTF-8");
    jbyteArray bytes = (jbyteArray)env->CallObjectMethod(value, getBytes, charset);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    if (env->ExceptionCheck() || bytes == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return env->NewStringUTF("");
    }

    jstring out = stub_fock_sign_md5(env, clazz, bytes, env->GetArrayLength(bytes));
    env->DeleteLocalRef(bytes);
    if (out == nullptr && env->ExceptionCheck()) {
        env->ExceptionClear();
        return env->NewStringUTF("");
    }
    return out != nullptr ? out : env->NewStringUTF("");
}

static jstring JNICALL stub_fock_identity_string(JNIEnv* env, jobject thiz, jstring value) {
    (void)thiz;
    if (value != nullptr) {
        return (jstring)env->NewLocalRef(value);
    }
    return env->NewStringUTF("");
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterStubMethods(
    JNIEnv* env, jclass clazz, jobject classLoader, jstring className)
{
    (void)clazz;
    if (classLoader == nullptr || className == nullptr) return JNI_FALSE;

    // 获取类名
    const char* name = env->GetStringUTFChars(className, nullptr);
    if (name == nullptr) return JNI_FALSE;

    // 通过 guest ClassLoader 加载类
    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jName = env->NewStringUTF(name);
    env->ReleaseStringUTFChars(className, name);

    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, jName);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(clClass);

    if (targetClass == nullptr) {
        LOGW("nativeRegisterStubMethods: class not found via guest ClassLoader");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    // 360 加固壳 StubApp 的完整 native 方法签名（从 logcat 错误信息提取）
    JNINativeMethod methods[] = {
        // interface5(Application)V
        {const_cast<char*>("interface5"),  const_cast<char*>("(Landroid/app/Application;)V"), (void*)stub_interface_app},
        // interface10(Context)V
        {const_cast<char*>("interface10"), const_cast<char*>("(Landroid/content/Context;)V"), (void*)stub_interface_app},
        // interface11(int)V is used by qrencrypt/FockKeyPoolCache class init.
        {const_cast<char*>("interface11"), const_cast<char*>("(I)V"), (void*)stub_interface11},
        // interface12(DexFile)Enumeration
        {const_cast<char*>("interface12"), const_cast<char*>("(Ldalvik/system/DexFile;)Ljava/util/Enumeration;"), (void*)stub_interface_bool},
        // interface14(int)String
        {const_cast<char*>("interface14"), const_cast<char*>("(I)Ljava/lang/String;"), (void*)stub_interface_str},
        // interface17(AssetManager, String)AssetFileDescriptor
        {const_cast<char*>("interface17"), const_cast<char*>("(Landroid/content/res/AssetManager;Ljava/lang/String;)Landroid/content/res/AssetFileDescriptor;"), (void*)stub_interface_bool},
        // interface18(Class, String)InputStream
        {const_cast<char*>("interface18"), const_cast<char*>("(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;"), (void*)stub_interface_bool},
        // interface19(ClassLoader, String)InputStream
        {const_cast<char*>("interface19"), const_cast<char*>("(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/io/InputStream;"), (void*)stub_interface_bool},
        // interface199(AssetManager, String)InputStream
        {const_cast<char*>("interface199"), const_cast<char*>("(Landroid/content/res/AssetManager;Ljava/lang/String;)Ljava/io/InputStream;"), (void*)stub_interface_bool},
        // interface20()Z
        {const_cast<char*>("interface20"), const_cast<char*>("()Z"), (void*)stub_interface_bool},
        // interface21(Application)V
        {const_cast<char*>("interface21"), const_cast<char*>("(Landroid/app/Application;)V"), (void*)stub_interface_app},
        // interface22(int, String[], int[])V
        {const_cast<char*>("interface22"), const_cast<char*>("(I[Ljava/lang/String;[I)V"), (void*)stub_interface_app},
        // interface24(Activity, String[], int)V
        {const_cast<char*>("interface24"), const_cast<char*>("(Landroid/app/Activity;[Ljava/lang/String;I)V"), (void*)stub_interface_app},
        // interface30(ZipFile, String)ZipEntry
        {const_cast<char*>("interface30"), const_cast<char*>("(Ljava/util/zip/ZipFile;Ljava/lang/String;)Ljava/util/zip/ZipEntry;"), (void*)stub_interface_bool},
        // interface51(Resources, int)InputStream
        {const_cast<char*>("interface51"), const_cast<char*>("(Landroid/content/res/Resources;I)Ljava/io/InputStream;"), (void*)stub_interface_bool},
    };

    jint result = env->RegisterNatives(targetClass, methods, sizeof(methods)/sizeof(methods[0]));
    LOGI("nativeRegisterStubMethods: RegisterNatives returned %d for %d methods", result, (int)(sizeof(methods)/sizeof(methods[0])));
    env->DeleteLocalRef(targetClass);

    if (result == JNI_OK) {
        LOGI("nativeRegisterStubMethods: registered %d stub methods", (int)(sizeof(methods)/sizeof(methods[0])));
        return JNI_TRUE;
    } else {
        LOGW("nativeRegisterStubMethods: RegisterNatives failed with code %d", result);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }
}

/**
 * 注册 YWLoginManager.getInstance() 的 stub 实现
 * 让应用不崩溃，登录功能不可用，但其他功能可能正常
 */
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterBusinessStubs(
    JNIEnv* env, jclass clazz, jobject classLoader)
{
    (void)clazz;
    if (classLoader == nullptr) return JNI_FALSE;

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

    // 已知需要 stub 的 native 方法
    struct StubEntry {
        const char* className;
        const char* methodName;
        const char* signature;
        void* fnPtr;
    };

    StubEntry stubs[] = {
        // Minimal login entry shims. Without these, ReaderApplication crashes
        // in initLoginSDK before the main UI is created. Keep the rest of
        // YWLoginManager untouched; blanket stubbing breaks content APIs.
        {"com.yuewen.ywlogin.login.YWLoginManager", "getInstance",
         "()Lcom/yuewen/ywlogin/login/YWLoginManager;", (void*)stub_ywlogin_getInstance},
        {"com.yuewen.ywlogin.login.YWLoginManager", "registerParameter",
         "(Lcom/yuewen/ywlogin/login/IParameterGetter;)V", (void*)stub_ywlogin_registerParameter},
        {"com.yuewen.ywlogin.login.YWLoginManager", "setDefaultParameters",
         "(Landroid/app/Application;Landroid/content/ContentValues;)V", (void*)stub_ywlogin_setDefaultParameters},
        {"com.yuewen.ywlogin.login.YWLoginManager", "fetchSettings",
         "(Lcom/yuewen/ywlogin/callbacks/DefaultYWCallback;)V", (void*)stub_ywlogin_fetchSettings},
        {"com.qq.reader.common.utils.crypto.EasyEncrypt", "getMd5Key",
         "()Ljava/lang/String;", (void*)stub_easyencrypt_md5_key},
        // Keep Fock payload signing/encryption untouched. Faking those avoids
        // crashes but causes bookcity responses to fail.
        // libfock.so JNI_OnLoad 返回 -1 → 函数指针表为空 → 调用即 SIGSEGV
        // 注册多种签名（反射无法确定确切签名），全部返回空字符串
    };

    int registered = 0;
    int failed = 0;

    for (auto& stub : stubs) {
        jstring name = env->NewStringUTF(stub.className);
        jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, name);
        env->DeleteLocalRef(name);

        if (targetClass == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            continue; // 类不存在，跳过
        }

        JNINativeMethod method = {
            const_cast<char*>(stub.methodName),
            const_cast<char*>(stub.signature),
            stub.fnPtr
        };

        jint result = env->RegisterNatives(targetClass, &method, 1);
        if (result == JNI_OK) {
            LOGI("nativeRegisterBusinessStubs: %s.%s OK", stub.className, stub.methodName);
            registered++;
        } else {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGW("nativeRegisterBusinessStubs: %s.%s failed", stub.className, stub.methodName);
            failed++;
        }
        env->DeleteLocalRef(targetClass);
    }

    LOGI("nativeRegisterBusinessStubs: registered=%d failed=%d", registered, failed);
    env->DeleteLocalRef(clClass);

    return registered > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterOnlineChapterDownloadFallbackStubs(
    JNIEnv* env, jclass clazz, jobject classLoader)
{
    (void)clazz;
    if (classLoader == nullptr) return JNI_FALSE;

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    env->DeleteLocalRef(clClass);

    jstring name = env->NewStringUTF("com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask");
    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, name);
    env->DeleteLocalRef(name);
    if (targetClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeRegisterOnlineChapterDownloadFallbackStubs: class not found");
        return JNI_FALSE;
    }

    JNINativeMethod methods[] = {
        {const_cast<char*>("getDownloadChap"),
         const_cast<char*>("()Ljava/util/ArrayList;"), (void*)stub_online_getDownloadChap},
        {const_cast<char*>("getDownloadChapters"),
         const_cast<char*>("()Ljava/util/HashSet;"), (void*)stub_online_getDownloadChapters},
        {const_cast<char*>("setToDownloadChapters"),
         const_cast<char*>("(Ljava/util/List;)V"), (void*)stub_online_setToDownloadChapters},
        {const_cast<char*>("isBackgroundRun"),
         const_cast<char*>("()Z"), (void*)stub_online_isBackgroundRun},
        {const_cast<char*>("setBackgroundRun"),
         const_cast<char*>("(Z)V"), (void*)stub_online_setBackgroundRun},
        {const_cast<char*>("hasRetryTag"),
         const_cast<char*>("()Z"), (void*)stub_online_hasRetryTag},
        {const_cast<char*>("setRetryTag"),
         const_cast<char*>("()V"), (void*)stub_online_setRetryTag},
        {const_cast<char*>("getListener"),
         const_cast<char*>("()Lcom/qq/reader/cservice/onlineread/qdaf;"), (void*)stub_online_getListener},
        {const_cast<char*>("setListener"),
         const_cast<char*>("(Lcom/qq/reader/cservice/onlineread/qdaf;)V"), (void*)stub_online_setListener},
        {const_cast<char*>("getScene"),
         const_cast<char*>("()Ljava/lang/String;"), (void*)stub_online_getScene},
        {const_cast<char*>("setScene"),
         const_cast<char*>("(Ljava/lang/String;)V"), (void*)stub_online_setScene},
    };

    jint result = env->RegisterNatives(targetClass, methods, (jint)(sizeof(methods) / sizeof(methods[0])));
    env->DeleteLocalRef(targetClass);
    if (result == JNI_OK) {
        LOGI("nativeRegisterOnlineChapterDownloadFallbackStubs: registered %d methods",
             (int)(sizeof(methods) / sizeof(methods[0])));
        return JNI_TRUE;
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    LOGW("nativeRegisterOnlineChapterDownloadFallbackStubs: RegisterNatives failed code=%d", result);
    return JNI_FALSE;
}

// forward declaration
static std::string javaTypeToJni(const char* typeName);

/**
 * 扫描 guest ClassLoader 中所有类的 native 方法，批量注册 stub 实现
 * 这是解决"壳不注册业务 native 方法"问题的通用方案
 */
JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterAllMissingNativeMethods(
    JNIEnv* env, jclass clazz, jobject classLoader)
{
    (void)clazz;
    if (classLoader == nullptr) return 0;

    // 获取 ClassLoader.loadClass
    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    env->DeleteLocalRef(clClass);

    // 获取 Class.getDeclaredMethods
    jclass classClass = env->FindClass("java/lang/Class");
    jmethodID getDeclaredMethods = env->GetMethodID(classClass, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    jclass methodClass = env->FindClass("java/lang/reflect/Method");
    jmethodID getModifiers = env->GetMethodID(methodClass, "getModifiers", "()I");
    jmethodID getName = env->GetMethodID(methodClass, "getName", "()Ljava/lang/String;");
    jmethodID getReturnType = env->GetMethodID(methodClass, "getReturnType", "()Ljava/lang/Class;");
    jmethodID getParameterTypes = env->GetMethodID(methodClass, "getParameterTypes", "()[Ljava/lang/Class;");
    jclass classClass2 = env->FindClass("java/lang/Class");
    jmethodID getClassName = env->GetMethodID(classClass2, "getName", "()Ljava/lang/String;");
    jclass typeClass = env->FindClass("java/lang/reflect/Type");
    jmethodID getTypeName = env->GetMethodID(typeClass, "getTypeName", "()Ljava/lang/String;");

    // 已知的 native 类列表（从 DEX 扫描结果获取）
    // 这些类包含 native 方法，需要注册 stub
    const char* knownNativeClasses[] = {
        "__multiapp.noop.NativeClass",
        // Keep QQ Reader URL/signing/encryption native classes untouched.
        // Blanket null/false stubs break network-backed content.
        // EasyEncrypt 已由 registerBusinessStubs 处理（返回空字符串），不放在这里
        // 避免 registerAllMissingNativeMethods 用 null 覆盖空字符串
    };
    int classCount = sizeof(knownNativeClasses) / sizeof(knownNativeClasses[0]);

    int totalRegistered = 0;

    for (int ci = 0; ci < classCount; ci++) {
        jstring className = env->NewStringUTF(knownNativeClasses[ci]);
        jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, className);
        env->DeleteLocalRef(className);

        if (targetClass == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            continue;
        }

        // 获取所有声明的方法
        jobjectArray methods = (jobjectArray)env->CallObjectMethod(targetClass, getDeclaredMethods);
        if (methods == nullptr) {
            env->DeleteLocalRef(targetClass);
            continue;
        }

        jsize methodCount = env->GetArrayLength(methods);
        std::vector<JNINativeMethod> nativeMethods;

        for (jsize mi = 0; mi < methodCount; mi++) {
            jobject method = env->GetObjectArrayElement(methods, mi);
            if (method == nullptr) continue;

            // 检查是否是 native 方法 (Modifier.NATIVE = 0x100)
            jint modifiers = env->CallIntMethod(method, getModifiers);
            if ((modifiers & 0x100) == 0) {
                env->DeleteLocalRef(method);
                continue;
            }

            // 获取方法名
            auto methodNameObj = (jstring)env->CallObjectMethod(method, getName);
            const char* methodName = env->GetStringUTFChars(methodNameObj, nullptr);

            // 获取返回类型
            jobject returnType = env->CallObjectMethod(method, getReturnType);
            auto returnTypeNameObj = (jstring)env->CallObjectMethod(returnType, getTypeName);
            const char* returnTypeName = env->GetStringUTFChars(returnTypeNameObj, nullptr);

            // 获取参数类型
            jobjectArray paramTypes = (jobjectArray)env->CallObjectMethod(method, getParameterTypes);
            jsize paramCount = paramTypes ? env->GetArrayLength(paramTypes) : 0;

            // 构建 JNI 签名
            std::string signature = "(";
            for (jsize pi = 0; pi < paramCount; pi++) {
                jobject paramType = env->GetObjectArrayElement(paramTypes, pi);
                auto paramTypeNameObj = (jstring)env->CallObjectMethod(paramType, getTypeName);
                const char* paramTypeName = env->GetStringUTFChars(paramTypeNameObj, nullptr);
                signature += javaTypeToJni(paramTypeName);
                env->ReleaseStringUTFChars(paramTypeNameObj, paramTypeName);
                env->DeleteLocalRef(paramTypeNameObj);
                env->DeleteLocalRef(paramType);
            }
            signature += ")";
            signature += javaTypeToJni(returnTypeName);

            // 选择 stub 函数
            void* stubFn = nullptr;
            std::string retStr(returnTypeName);
            if (retStr == "void") {
                stubFn = (void*)stub_ywlogin_void;
            } else if (retStr == "boolean" || retStr == "java.lang.Boolean") {
                stubFn = (void*)stub_ywlogin_false;
            } else {
                stubFn = (void*)stub_ywlogin_null;
            }

            // 存储方法名（需要持久化）
            char* nameCopy = strdup(methodName);
            char* sigCopy = strdup(signature.c_str());

            JNINativeMethod jniMethod = { nameCopy, sigCopy, stubFn };
            nativeMethods.push_back(jniMethod);

            env->ReleaseStringUTFChars(methodNameObj, methodName);
            env->ReleaseStringUTFChars(returnTypeNameObj, returnTypeName);
            env->DeleteLocalRef(methodNameObj);
            env->DeleteLocalRef(returnTypeNameObj);
            env->DeleteLocalRef(returnType);
            if (paramTypes) env->DeleteLocalRef(paramTypes);
            env->DeleteLocalRef(method);
        }

        env->DeleteLocalRef(methods);

        // 批量注册
        if (!nativeMethods.empty()) {
            jint result = env->RegisterNatives(targetClass, nativeMethods.data(), (jint)nativeMethods.size());
            if (result == JNI_OK) {
                LOGI("nativeRegisterAll: %s registered %d native methods", knownNativeClasses[ci], (int)nativeMethods.size());
                totalRegistered += (int)nativeMethods.size();
            } else {
                if (env->ExceptionCheck()) env->ExceptionClear();
                LOGW("nativeRegisterAll: %s RegisterNatives failed", knownNativeClasses[ci]);
            }

            // 释放 strdup 的内存
            for (auto& m : nativeMethods) {
                std::free((void*)m.name);
                std::free((void*)m.signature);
            }
        }

        env->DeleteLocalRef(targetClass);
    }

    env->DeleteLocalRef(classClass);
    env->DeleteLocalRef(classClass2);
    env->DeleteLocalRef(methodClass);
    env->DeleteLocalRef(typeClass);

    LOGI("nativeRegisterAll: total registered %d methods", totalRegistered);
    return totalRegistered;
}

// Java 类型名转 JNI 签名
static std::string javaTypeToJni(const char* typeName) {
    if (strcmp(typeName, "void") == 0) return "V";
    if (strcmp(typeName, "boolean") == 0) return "Z";
    if (strcmp(typeName, "byte") == 0) return "B";
    if (strcmp(typeName, "char") == 0) return "C";
    if (strcmp(typeName, "short") == 0) return "S";
    if (strcmp(typeName, "int") == 0) return "I";
    if (strcmp(typeName, "long") == 0) return "J";
    if (strcmp(typeName, "float") == 0) return "F";
    if (strcmp(typeName, "double") == 0) return "D";
    // 对象类型: java.lang.String -> Ljava/lang/String;
    std::string result = "L";
    for (const char* p = typeName; *p; p++) {
        result += (*p == '.') ? '/' : *p;
    }
    result += ";";
    return result;
}

/**
 * 从 DexFile C++ 对象指针中提取 begin_ 和 size_。
 * 通过扫描对象内存寻找 DEX magic 来定位，不依赖固定偏移。
 */
static int dump_dex_from_dexfile_ptr(const void* dexfile_ptr, const char* dumpDir, int index) {
    if (dexfile_ptr == nullptr) return -1;

    // DexFile 对象通常在前 64 字节内包含 begin_ 指针和 size_
    // 我们扫描前 128 字节寻找一个指针，指向的内存以 "dex\n" 开头
    const uintptr_t* fields = (const uintptr_t*)dexfile_ptr;

    for (int i = 1; i < 16; i++) { // 从 1 开始跳过 vtable
        uintptr_t candidate = fields[i];
        // 检查是否是合理的指针（非零、合理范围）
        if (candidate == 0 || candidate < 0x10000) continue;
        // C2 修复：移除 4KB 对齐检查，InMemoryDexClassLoader 的 DEX 是 16 字节对齐

        const uint8_t* possible_begin = (const uint8_t*)candidate;
        // 检查 DEX magic
        if (memcmp(possible_begin, "dex\n", 4) != 0) continue;

        uint32_t file_size = *(const uint32_t*)(possible_begin + 0x20);
        if (file_size < 0x70 || file_size > 50 * 1024 * 1024) continue;

        // 下一个字段可能是 size_
        uintptr_t possible_size = fields[i + 1];
        if (possible_size >= file_size) {
            char path[512];
            snprintf(path, sizeof(path), "%s/dump_%d.dex", dumpDir, index);
            FILE* out = fopen(path, "wb");
            if (!out) {
                LOGE("dump_dex: fopen failed %s: %s", path, strerror(errno));
                return -1;
            }
            fwrite(possible_begin, 1, file_size, out);
            fclose(out);
            LOGI("dump_dex: wrote %s (%u bytes)", path, file_size);
            return 0;
        }
    }

    return -1;
}

/**
 * JNI: 从 guest ClassLoader 的 DexPathList.dexElements 中提取所有 DexFile，
 * 通过 mCookie 读取 DEX 字节并写入文件。
 *
 * @param classLoader guest ClassLoader (PathClassLoader)
 * @param dumpDir     输出目录路径
 * @return 成功 dump 的 DEX 数量
 */
JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeDumpDexFromClassLoader(
    JNIEnv* env, jclass clazz, jobject classLoader, jstring dumpDir)
{
    (void)clazz;
    if (classLoader == nullptr || dumpDir == nullptr) return 0;

    const char* dir = env->GetStringUTFChars(dumpDir, nullptr);
    if (dir == nullptr) return 0;

    mkdir(dir, 0755);
    int dumped = 0;

    // 1. ClassLoader -> pathList (BaseDexClassLoader.pathList)
    jclass baseDexClClass = env->FindClass("dalvik/system/BaseDexClassLoader");
    if (baseDexClClass == nullptr) {
        LOGE("dumpDex: BaseDexClassLoader not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->ReleaseStringUTFChars(dumpDir, dir);
        return 0;
    }

    jfieldID pathListField = env->GetFieldID(baseDexClClass, "pathList", "Ldalvik/system/DexPathList;");
    env->DeleteLocalRef(baseDexClClass);
    if (pathListField == nullptr) {
        LOGE("dumpDex: pathList field not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->ReleaseStringUTFChars(dumpDir, dir);
        return 0;
    }

    jobject pathList = env->GetObjectField(classLoader, pathListField);
    if (pathList == nullptr) {
        LOGW("dumpDex: pathList is null");
        env->ReleaseStringUTFChars(dumpDir, dir);
        return 0;
    }

    // 2. pathList -> dexElements (DexPathList.Element[])
    jclass pathListClass = env->GetObjectClass(pathList);
    jfieldID dexElementsField = env->GetFieldID(pathListClass, "dexElements", "[Ldalvik/system/DexPathList$Element;");
    env->DeleteLocalRef(pathListClass);

    if (dexElementsField == nullptr) {
        LOGW("dumpDex: dexElements field not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(pathList);
        env->ReleaseStringUTFChars(dumpDir, dir);
        return 0;
    }

    jobjectArray dexElements = (jobjectArray)env->GetObjectField(pathList, dexElementsField);
    env->DeleteLocalRef(pathList);

    if (dexElements == nullptr) {
        LOGW("dumpDex: dexElements is null");
        env->ReleaseStringUTFChars(dumpDir, dir);
        return 0;
    }

    jsize elemCount = env->GetArrayLength(dexElements);
    LOGI("dumpDex: found %d dexElements", elemCount);

    // 3. 遍历每个 Element -> dexFile -> mCookie
    jclass elementClass = nullptr;
    jfieldID dexFileField = nullptr;
    jclass dexFileClass = nullptr;
    jfieldID cookieField = nullptr;

    for (jsize i = 0; i < elemCount; i++) {
        jobject element = env->GetObjectArrayElement(dexElements, i);
        if (element == nullptr) continue;

        if (elementClass == nullptr) {
            elementClass = env->GetObjectClass(element);
            dexFileField = env->GetFieldID(elementClass, "dexFile", "Ldalvik/system/DexFile;");
            if (dexFileField == nullptr) {
                LOGW("dumpDex: dexFile field not found in Element");
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(element);
                continue;
            }
        }

        jobject dexFile = env->GetObjectField(element, dexFileField);
        env->DeleteLocalRef(element);
        if (dexFile == nullptr) continue;

        if (dexFileClass == nullptr) {
            dexFileClass = env->GetObjectClass(dexFile);

            // 调试：枚举 DexFile 的所有字段
            jclass classClass = env->FindClass("java/lang/Class");
            jmethodID getDeclaredFields = env->GetMethodID(classClass, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
            jobjectArray fields = (jobjectArray)env->CallObjectMethod(dexFileClass, getDeclaredFields);
            if (fields != nullptr) {
                jsize fieldCount = env->GetArrayLength(fields);
                LOGI("dumpDex: DexFile has %d fields", fieldCount);
                for (jsize fi = 0; fi < fieldCount; fi++) {
                    jobject field = env->GetObjectArrayElement(fields, fi);
                    jclass fieldClass = env->GetObjectClass(field);
                    jmethodID getName = env->GetMethodID(fieldClass, "getName", "()Ljava/lang/String;");
                    auto name = (jstring)env->CallObjectMethod(field, getName);
                    const char* nameStr = env->GetStringUTFChars(name, nullptr);
                    jmethodID getType = env->GetMethodID(fieldClass, "getType", "()Ljava/lang/Class;");
                    jclass type = (jclass)env->CallObjectMethod(field, getType);
                    jclass typeClass = env->GetObjectClass(type);
                    jmethodID getTypeName = env->GetMethodID(typeClass, "getName", "()Ljava/lang/String;");
                    auto typeName = (jstring)env->CallObjectMethod(type, getTypeName);
                    const char* typeStr = env->GetStringUTFChars(typeName, nullptr);
                    LOGI("dumpDex:   field[%d] %s : %s", fi, nameStr, typeStr);
                    env->ReleaseStringUTFChars(name, nameStr);
                    env->ReleaseStringUTFChars(typeName, typeStr);
                    env->DeleteLocalRef(field);
                    env->DeleteLocalRef(fieldClass);
                    env->DeleteLocalRef(name);
                    env->DeleteLocalRef(type);
                    env->DeleteLocalRef(typeClass);
                    env->DeleteLocalRef(typeName);
                }
                env->DeleteLocalRef(fields);
            }
            env->DeleteLocalRef(classClass);

            // Android 16: DexFile 字段是 Object 类型
            // 先尝试 long 字段，再尝试 Object 字段
            bool foundCookie = false;
            const char* cookieNames[] = {"mCookie", "mInternalCookie", "cookie", "mNativePtr"};
            for (int ci = 0; ci < 4 && !foundCookie; ci++) {
                // 尝试 long
                jfieldID longField = env->GetFieldID(dexFileClass, cookieNames[ci], "J");
                if (longField != nullptr) {
                    if (env->ExceptionCheck()) env->ExceptionClear();
                    jlong val = env->GetLongField(dexFile, longField);
                    LOGI("dumpDex: %s (long) = %p", cookieNames[ci], (void*)val);
                    if (val != 0) {
                        cookieField = longField;
                        foundCookie = true;
                    }
                }
                if (env->ExceptionCheck()) env->ExceptionClear();

                if (!foundCookie) {
                    // 尝试 Object
                    jfieldID objField = env->GetFieldID(dexFileClass, cookieNames[ci], "Ljava/lang/Object;");
                    if (objField != nullptr) {
                        if (env->ExceptionCheck()) env->ExceptionClear();
                        jobject objVal = env->GetObjectField(dexFile, objField);
                        if (objVal != nullptr) {
                            jclass objClass = env->GetObjectClass(objVal);
                            // 打印 Object 的实际类型
                            jclass classClass = env->FindClass("java/lang/Class");
                            jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
                            auto className = (jstring)env->CallObjectMethod(objClass, getName);
                            const char* classNameStr = env->GetStringUTFChars(className, nullptr);
                            LOGI("dumpDex: %s Object class = %s", cookieNames[ci], classNameStr);
                            env->ReleaseStringUTFChars(className, classNameStr);
                            env->DeleteLocalRef(className);
                            env->DeleteLocalRef(classClass);

                            jmethodID longValue = env->GetMethodID(objClass, "longValue", "()J");
                            if (longValue != nullptr) {
                                jlong val = env->CallLongMethod(objVal, longValue);
                                LOGI("dumpDex: %s (Long obj) = %p", cookieNames[ci], (void*)val);
                                if (val != 0) {
                                    // 手动提取 cookie 值，不依赖 cookieField
                                    // 直接跳到 cookie 处理逻辑
                                    env->DeleteLocalRef(objVal);
                                    env->DeleteLocalRef(objClass);
                                    // 用这个值作为 cookie
                                    auto** vec_storage = reinterpret_cast<void**>(val);
                                    if (vec_storage != nullptr) {
                                        void** dex_ptrs = reinterpret_cast<void**>(vec_storage[0]);
                                        size_t vec_size = reinterpret_cast<size_t>(vec_storage[1]);
                                        if (dex_ptrs != nullptr && vec_size > 0 && vec_size < 100) {
                                            for (size_t j = 0; j < vec_size; j++) {
                                                if (dump_dex_from_dexfile_ptr(dex_ptrs[j], dir, dumped) == 0) dumped++;
                                            }
                                        } else {
                                            if (dump_dex_from_dexfile_ptr(reinterpret_cast<void*>(val), dir, dumped) == 0) dumped++;
                                        }
                                    }
                                    foundCookie = true;
                                    cookieField = objField; // C1 修复：保存 field ID 供后续元素复用
                                    continue; // 跳过后续 GetLongField
                                }
                            }
                            if (env->ExceptionCheck()) env->ExceptionClear();
                            env->DeleteLocalRef(objVal);
                            env->DeleteLocalRef(objClass);
                        }
                    }
                    if (env->ExceptionCheck()) env->ExceptionClear();
                }
            }
        }

        if (cookieField == nullptr) {
            // 备用方案：用 mFileName 直接读取 DEX 文件
            jfieldID fileNameField = env->GetFieldID(dexFileClass, "mFileName", "Ljava/lang/String;");
            if (fileNameField != nullptr) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                auto fileName = (jstring)env->GetObjectField(dexFile, fileNameField);
                if (fileName != nullptr) {
                    const char* fileNameStr = env->GetStringUTFChars(fileName, nullptr);
                    if (fileNameStr != nullptr && fileNameStr[0] != '\0') {
                        LOGI("dumpDex: trying mFileName fallback: %s", fileNameStr);
                        // 检查文件是否存在且可读
                        FILE* f = fopen(fileNameStr, "rb");
                        if (f != nullptr) {
                            // 获取文件大小
                            fseek(f, 0, SEEK_END);
                            long fileSize = ftell(f);
                            fseek(f, 0, SEEK_SET);

                            if (fileSize > 0x70 && fileSize < 50 * 1024 * 1024) {
                                // 读取文件内容 (H6: 使用 unique_ptr 自动释放)
                                std::unique_ptr<uint8_t[]> buf(new(std::nothrow) uint8_t[fileSize]);
                                if (buf) {
                                    size_t read = fread(buf.get(), 1, fileSize, f);
                                    if (read == (size_t)fileSize) {
                                        // 检查 DEX magic
                                        if (memcmp(buf.get(), "dex\n", 4) == 0) {
                                            char path[1024]; // H5: 扩大 buffer
                                            snprintf(path, sizeof(path), "%s/dump_%d.dex", dir, dumped);
                                            FILE* out = fopen(path, "wb");
                                            if (out != nullptr) {
                                                fwrite(buf.get(), 1, fileSize, out);
                                                fclose(out);
                                                LOGI("dumpDex: wrote %s (%ld bytes) via mFileName fallback", path, fileSize);
                                                dumped++;
                                            }
                                        } else {
                                            LOGW("dumpDex: mFileName file is not a DEX (magic mismatch)");
                                        }
                                    }
                                    // H6: buf 自动释放，无需手动 free
                                }
                            }
                            fclose(f);
                        } else {
                            LOGW("dumpDex: mFileName file not readable: %s", fileNameStr);
                        }
                        env->ReleaseStringUTFChars(fileName, fileNameStr);
                    }
                    env->DeleteLocalRef(fileName);
                }
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(dexFile);
            continue;
        }

        // Android 16: mCookie 是 Object 类型（不是 long）
        // 尝试 GetLongField，如果失败则 GetObjectField 再取其 nativePtr
        jlong cookie = 0;
        {
            // 先检查字段类型
            jclass classClass = env->FindClass("java/lang/Class");
            jmethodID getDeclaredField = env->GetMethodID(classClass, "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
            jstring cookieName = env->NewStringUTF("mCookie");
            jobject fieldObj = env->CallObjectMethod(dexFileClass, getDeclaredField, cookieName);
            env->DeleteLocalRef(cookieName);
            env->DeleteLocalRef(classClass);

            if (fieldObj != nullptr) {
                jclass fieldClass = env->GetObjectClass(fieldObj);
                jmethodID getType = env->GetMethodID(fieldClass, "getType", "()Ljava/lang/Class;");
                jclass type = (jclass)env->CallObjectMethod(fieldObj, getType);

                jclass longClass = env->FindClass("java/lang/Long");

                if (env->IsAssignableFrom(type, longClass)) {
                    // long 类型 — 直接 GetLongField
                    cookie = env->GetLongField(dexFile, cookieField);
                    LOGI("dumpDex: mCookie (long) = %p", (void*)cookie);
                } else {
                    // Object 类型 — GetObjectField，然后找里面的 value 或 nativePtr
                    jobject cookieObj = env->GetObjectField(dexFile, cookieField);
                    if (cookieObj != nullptr) {
                        // 可能是 Long 对象
                        jclass longObjClass = env->GetObjectClass(cookieObj);
                        jmethodID longValue = env->GetMethodID(longObjClass, "longValue", "()J");
                        if (longValue != nullptr) {
                            cookie = env->CallLongMethod(cookieObj, longValue);
                            LOGI("dumpDex: mCookie (Long object) = %p", (void*)cookie);
                        } else {
                            // 可能直接是 native pointer 封装在对象中
                            // 尝试读取对象的第一个非引用字段
                            LOGW("dumpDex: mCookie is Object but not Long, class=%s",
                                 describe_java_class(env, longObjClass).c_str());
                        }
                        env->DeleteLocalRef(cookieObj);
                        env->DeleteLocalRef(longObjClass);
                    }
                }

                env->DeleteLocalRef(type);
                env->DeleteLocalRef(longClass);
                env->DeleteLocalRef(fieldObj);
                env->DeleteLocalRef(fieldClass);
            }
        }
        env->DeleteLocalRef(dexFile);
        if (cookie == 0) continue;

        // mCookie 在 Android 8+ 是 vector<DexFile*>* 的 native 指针
        // vector 内部布局: { data_ptr (void*), size (size_t), capacity (size_t) }
        auto** vec_storage = reinterpret_cast<void**>(cookie);
        if (vec_storage == nullptr) continue;

        void** dex_ptrs = reinterpret_cast<void**>(vec_storage[0]); // vector::data()
        size_t vec_size = reinterpret_cast<size_t>(vec_storage[1]); // vector::size()

        if (dex_ptrs != nullptr && vec_size > 0 && vec_size < 100) {
            for (size_t j = 0; j < vec_size; j++) {
                if (dump_dex_from_dexfile_ptr(dex_ptrs[j], dir, dumped) == 0) {
                    dumped++;
                }
            }
        } else {
            // 回退：cookie 直接就是 DexFile*
            if (dump_dex_from_dexfile_ptr(reinterpret_cast<void*>(cookie), dir, dumped) == 0) {
                dumped++;
            }
        }
    }

    if (elementClass) env->DeleteLocalRef(elementClass);
    if (dexFileClass) env->DeleteLocalRef(dexFileClass);
    env->DeleteLocalRef(dexElements);
    env->ReleaseStringUTFChars(dumpDir, dir);

    LOGI("dumpDex: dumped %d DEX files to %s", dumped, dir);
    return dumped;
}

/**
 * dl_iterate_phdr 回调：dump 已加载的 native library
 */
struct SoDumpRequest {
    const char* targetBasename;  // 要匹配的库名（null = dump 所有 app .so）
    const char* dumpDir;
    int count;
};

static int dump_so_callback(struct dl_phdr_info* info, size_t size, void* data) {
    auto* req = static_cast<SoDumpRequest*>(data);
    if (info == nullptr || info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;

    const char* basename = strrchr(info->dlpi_name, '/');
    basename = basename ? basename + 1 : info->dlpi_name;

    bool match = false;
    if (req->targetBasename != nullptr) {
        match = (strstr(basename, req->targetBasename) != nullptr);
    } else {
        match = (strstr(info->dlpi_name, "/data/") != nullptr &&
                 strstr(basename, "lib") == basename &&
                 strstr(basename, ".so") != nullptr);
    }
    if (!match) return 0;

    if (info->dlpi_phdr == nullptr || info->dlpi_phnum == 0) return 0;

    // 计算总加载大小
    size_t max_end = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_LOAD) {
            size_t end = info->dlpi_phdr[i].p_vaddr + info->dlpi_phdr[i].p_memsz;
            if (end > max_end) max_end = end;
        }
    }
    if (max_end < sizeof(ElfW(Ehdr))) return 0;

    // 验证 ELF magic
    auto* ehdr = reinterpret_cast<ElfW(Ehdr)*>(info->dlpi_addr);
    if (ehdr->e_ident[EI_MAG0] != ELFMAG0 || ehdr->e_ident[EI_MAG1] != ELFMAG1 ||
        ehdr->e_ident[EI_MAG2] != ELFMAG2 || ehdr->e_ident[EI_MAG3] != ELFMAG3) {
        LOGW("dump_so: invalid ELF magic for %s", info->dlpi_name);
        return 0;
    }

    char outPath[1024]; // H5: 从 512 扩大到 1024，避免长路径截断
    int pathLen = snprintf(outPath, sizeof(outPath), "%s/%s", req->dumpDir, basename);
    if (pathLen < 0 || pathLen >= (int)sizeof(outPath)) {
        LOGE("dump_so: path truncated (%d bytes): %s/%s", pathLen, req->dumpDir, basename);
        return 0;
    }

    FILE* out = fopen(outPath, "wb");
    if (!out) {
        LOGE("dump_so: fopen failed %s: %s", outPath, strerror(errno));
        return 0;
    }

    // ELF header
    fwrite(ehdr, 1, sizeof(ElfW(Ehdr)), out);

    // Program headers
    auto* phdrs = reinterpret_cast<ElfW(Phdr)*>(info->dlpi_addr + ehdr->e_phoff);
    fseek(out, ehdr->e_phoff, SEEK_SET);
    fwrite(phdrs, 1, ehdr->e_phnum * sizeof(ElfW(Phdr)), out);

    // PT_LOAD segments — 跳过 filesz==0 的纯 BSS 段
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (phdrs[i].p_type != PT_LOAD) continue;
        if (phdrs[i].p_filesz == 0) continue;

        uintptr_t seg_addr = info->dlpi_addr + phdrs[i].p_vaddr;
        if (seg_addr == 0 || phdrs[i].p_filesz > max_end) continue;

        void* seg = reinterpret_cast<void*>(seg_addr);
        fseek(out, phdrs[i].p_offset, SEEK_SET);
        fwrite(seg, 1, phdrs[i].p_filesz, out);
    }

    fclose(out);
    req->count++;
    LOGI("dump_so: %s (base=%p, size=%zu)", outPath, (void*)info->dlpi_addr, max_end);
    return 0; // 继续遍历
}

/**
 * JNI: dump 已加载的 native libraries
 *
 * @param dumpDir  输出目录路径
 * @param targetLib 要 dump 的特定库名（null = dump 所有 app .so）
 * @return 成功 dump 的 .so 数量
 */
JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeDumpLoadedLibraries(
    JNIEnv* env, jclass clazz, jstring dumpDir, jstring targetLib)
{
    (void)clazz;
    if (dumpDir == nullptr) return 0;

    const char* dir = env->GetStringUTFChars(dumpDir, nullptr);
    if (dir == nullptr) return 0;

    const char* target = nullptr;
    if (targetLib != nullptr) {
        target = env->GetStringUTFChars(targetLib, nullptr);
    }

    mkdir(dir, 0755);

    SoDumpRequest req { target, dir, 0 };
    dl_iterate_phdr(dump_so_callback, &req);

    env->ReleaseStringUTFChars(dumpDir, dir);
    if (target != nullptr) env->ReleaseStringUTFChars(targetLib, target);

    LOGI("dump_so: dumped %d libraries to %s", req.count, dir);
    return req.count;
}

// ==================== LSPlant Integration ====================
// LSPlant — ART method hooking framework
// 通过 dlopen/dlsym 动态加载 liblsplant.so，避免构建时链接导致的崩溃

// LSPlant mangled 符号名（从 liblsplant.so 用 llvm-nm -D 获取）
static constexpr const char* LSPLANT_INIT_SYM = "_ZN7lsplant2v24InitEP7_JNIEnvRKNS0_8InitInfoE";
static constexpr const char* LSPLANT_HOOK_SYM = "_ZN7lsplant2v24HookEP7_JNIEnvP8_jobjectS4_S4_";
static constexpr const char* LSPLANT_UNHOOK_SYM = "_ZN7lsplant2v26UnHookEP7_JNIEnvP8_jobject";
static constexpr const char* LSPLANT_IS_HOOKED_SYM = "_ZN7lsplant2v28IsHookedEP7_JNIEnvP8_jobject";
static constexpr const char* LSPLANT_DEOPTIMIZE_SYM = "_ZN7lsplant2v210DeoptimizeEP7_JNIEnvP8_jobject";

// LSPlant 函数指针类型（匹配 C++ ABI）
typedef bool (*LsplantInitFn)(JNIEnv*, const void*);
typedef jobject (*LsplantHookFn)(JNIEnv*, jobject, jobject, jobject);
typedef bool (*LsplantUnhookFn)(JNIEnv*, jobject);
typedef bool (*LsplantIsHookedFn)(JNIEnv*, jobject);
typedef bool (*LsplantDeoptimizeFn)(JNIEnv*, jobject);

// 全局状态
static void* g_lsplant_handle = nullptr;
static LsplantInitFn g_lsplant_init = nullptr;
static LsplantHookFn g_lsplant_hook = nullptr;
static LsplantUnhookFn g_lsplant_unhook = nullptr;
static LsplantIsHookedFn g_lsplant_is_hooked = nullptr;
static LsplantDeoptimizeFn g_lsplant_deoptimize = nullptr;
static bool g_lsplant_initialized = false;

// ShadowHook stub 跟踪（用于 unhook）
static std::unordered_map<void*, void*> g_lsplant_hook_stubs;
static std::shared_mutex g_lsplant_stub_mutex;

/**
 * 初始化 LSPlant：dlopen + dlsym + InitInfo 配置
 */
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeInitLsplant(
    JNIEnv* env, jclass clazz, jstring libDir)
{
    (void)clazz;
    if (g_lsplant_initialized) {
        LOGI("nativeInitLsplant: already initialized");
        return JNI_TRUE;
    }

    LOGI("nativeInitLsplant: starting LSPlant initialization...");

    // Enable bypass so hooked_dlopen allows lsplant loading
    g_lsplant_dlopen_bypass = true;

    // Step 1: dlopen liblsplant.so
    // 如果传入了 libDir，用完整路径加载（绕过 ClassLoader 命名空间限制）
    if (libDir != nullptr) {
        const char* dir = env->GetStringUTFChars(libDir, nullptr);
        if (dir != nullptr && dir[0] != '\0') {
            char fullPath[512];
            snprintf(fullPath, sizeof(fullPath), "%s/liblsplant.so", dir);
            LOGI("nativeInitLsplant: trying dlopen with full path: %s", fullPath);
            g_lsplant_handle = dlopen(fullPath, RTLD_NOW);
            if (g_lsplant_handle == nullptr) {
                const char* err = dlerror();
                LOGW("nativeInitLsplant: dlopen full path failed: %s", err ? err : "unknown");
            } else {
                LOGI("nativeInitLsplant: dlopen full path OK: %p", g_lsplant_handle);
            }
            env->ReleaseStringUTFChars(libDir, dir);
        }
    }

    // 如果完整路径失败，尝试默认方式
    if (g_lsplant_handle == nullptr) {
        g_lsplant_handle = dlopen("liblsplant.so", RTLD_NOW);
        if (g_lsplant_handle == nullptr) {
            const char* err = dlerror();
            LOGE("nativeInitLsplant: dlopen liblsplant.so failed: %s", err ? err : "unknown");
            // 尝试加载 libc++_shared.so 后重试
            void* libcxx = dlopen("libc++_shared.so", RTLD_NOW);
            if (libcxx) {
                LOGI("nativeInitLsplant: loaded libc++_shared.so, retrying dlopen...");
                g_lsplant_handle = dlopen("liblsplant.so", RTLD_NOW);
            }
            if (g_lsplant_handle == nullptr) {
                err = dlerror();
                LOGE("nativeInitLsplant: dlopen retry failed: %s", err ? err : "unknown");

                // 最后手段：从 /proc/self/maps 找 libmultiapp-native.so 的路径，
                // liblsplant.so 在同一目录
                FILE* maps = fopen("/proc/self/maps", "r");
                if (maps) {
                    char line[1024];
                    while (fgets(line, sizeof(line), maps)) {
                        if (strstr(line, "libmultiapp-native.so")) {
                            char* abs_path = strchr(line, '/');
                            if (abs_path) {
                                // 去掉换行符
                                char* nl = strchr(abs_path, '\n');
                                if (nl) *nl = '\0';
                                // 替换文件名: libmultiapp-native.so -> liblsplant.so
                                char* name_pos = strrchr(abs_path, '/');
                                if (name_pos) {
                                    strcpy(name_pos + 1, "liblsplant.so");
                                    LOGI("nativeInitLsplant: trying from maps: %s", abs_path);
                                    g_lsplant_handle = dlopen(abs_path, RTLD_NOW);
                                    if (g_lsplant_handle) {
                                        LOGI("nativeInitLsplant: dlopen from maps OK: %p", g_lsplant_handle);
                                    } else {
                                        err = dlerror();
                                        LOGW("nativeInitLsplant: dlopen from maps failed: %s", err ? err : "unknown");
                                    }
                                }
                            }
                            break;
                        }
                    }
                    fclose(maps);
                }

                if (g_lsplant_handle == nullptr) {
                    LOGE("nativeInitLsplant: all dlopen attempts failed");
                    g_lsplant_dlopen_bypass = false;
                    return JNI_FALSE;
                }
            }
        }
    }
    LOGI("nativeInitLsplant: liblsplant.so loaded at %p", g_lsplant_handle);

    // Disable bypass — no longer needed after dlopen
    g_lsplant_dlopen_bypass = false;

    // Step 2: dlsym 解析函数指针
    g_lsplant_init = (LsplantInitFn)dlsym(g_lsplant_handle, LSPLANT_INIT_SYM);
    g_lsplant_hook = (LsplantHookFn)dlsym(g_lsplant_handle, LSPLANT_HOOK_SYM);
    g_lsplant_unhook = (LsplantUnhookFn)dlsym(g_lsplant_handle, LSPLANT_UNHOOK_SYM);
    g_lsplant_is_hooked = (LsplantIsHookedFn)dlsym(g_lsplant_handle, LSPLANT_IS_HOOKED_SYM);
    g_lsplant_deoptimize = (LsplantDeoptimizeFn)dlsym(g_lsplant_handle, LSPLANT_DEOPTIMIZE_SYM);

    if (g_lsplant_init == nullptr || g_lsplant_hook == nullptr) {
        LOGE("nativeInitLsplant: dlsym failed — init=%p hook=%p", g_lsplant_init, g_lsplant_hook);
        dlclose(g_lsplant_handle);
        g_lsplant_handle = nullptr;
        return JNI_FALSE;
    }
    LOGI("nativeInitLsplant: symbols resolved — init=%p hook=%p unhook=%p",
         g_lsplant_init, g_lsplant_hook, g_lsplant_unhook);

    // Step 3: 打开 libart.so（用于 ART 符号解析）
    if (g_libart_handle == nullptr) {
        g_libart_handle = dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD);
        if (g_libart_handle == nullptr) {
            g_libart_handle = dlopen("libart.so", RTLD_NOW);
        }
        LOGI("nativeInitLsplant: libart.so handle=%p", g_libart_handle);
    }

    // Step 4: 构造 InitInfo
    lsplant::InitInfo init_info{};

    // inline_hooker: 用 ShadowHook 的地址 hook
    init_info.inline_hooker = [](void* target, void* replace) -> void* {
        void* backup = nullptr;
        void* stub = shadowhook_hook_sym_addr(target, replace, &backup);
        if (stub != nullptr) {
            std::unique_lock<std::shared_mutex> lock(g_lsplant_stub_mutex);
            g_lsplant_hook_stubs[backup] = stub;
            LOGD("lsplant inline_hooker: hooked %p -> %p (backup=%p)", target, replace, backup);
            return backup;
        } else {
            int err = shadowhook_get_errno();
            LOGW("lsplant inline_hooker: failed to hook %p, errno=%d", target, err);
            return nullptr;
        }
    };

    // inline_unhooker: 通过 stub 映射 unhook
    init_info.inline_unhooker = [](void* backup) -> bool {
        std::shared_lock<std::shared_mutex> lock(g_lsplant_stub_mutex);
        auto it = g_lsplant_hook_stubs.find(backup);
        if (it != g_lsplant_hook_stubs.end()) {
            void* stub = it->second;
            int ret = shadowhook_unhook(stub);
            if (ret == 0) {
                g_lsplant_hook_stubs.erase(it);
                LOGD("lsplant inline_unhooker: unhooked backup=%p", backup);
                return true;
            }
            LOGW("lsplant inline_unhooker: shadowhook_unhook failed, ret=%d", ret);
            return false;
        }
        LOGW("lsplant inline_unhooker: no stub found for backup=%p", backup);
        return false;
    };

    refresh_libart_info();

    // art_symbol_resolver: dlsym first, then libart ELF .symtab/.dynsym fallback.
    init_info.art_symbol_resolver = [](std::string_view symbol_name) -> void* {
        return resolve_libart_symbol(symbol_name, false);
    };

    init_info.art_symbol_prefix_resolver = [](std::string_view symbol_prefix) -> void* {
        return resolve_libart_symbol(symbol_prefix, true);
    };

    // Step 5: 调用 lsplant::Init
    bool result = g_lsplant_init(env, &init_info);
    g_lsplant_initialized = result;

    if (result) {
        LOGI("nativeInitLsplant: LSPlant initialized successfully!");
    } else {
        LOGE("nativeInitLsplant: lsplant::Init returned false");
    }

    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * 检查 LSPlant 是否已初始化
 */
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeIsLsplantInitialized(
    JNIEnv* env, jclass clazz)
{
    (void)env; (void)clazz;
    return g_lsplant_initialized ? JNI_TRUE : JNI_FALSE;
}

/**
 * 用 LSPlant hook Java 方法
 *
 * @param targetMethod 要 hook 的方法 (java.lang.reflect.Executable)
 * @param hookerObject 包含 callback 方法的对象
 *                     callback 签名: public Object callback(Object[] args)
 * @return true 表示 hook 成功
 */
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeHookMethod(
    JNIEnv* env, jclass clazz, jobject targetMethod, jobject hookerObject)
{
    (void)clazz;
    if (!g_lsplant_initialized || g_lsplant_hook == nullptr) {
        LOGE("nativeHookMethod: LSPlant not initialized");
        return JNI_FALSE;
    }

    if (targetMethod == nullptr || hookerObject == nullptr) {
        LOGE("nativeHookMethod: targetMethod or hookerObject is null");
        return JNI_FALSE;
    }

    // 获取 hooker 对象的 callback 方法
    jclass hookerClass = env->GetObjectClass(hookerObject);
    if (hookerClass == nullptr) {
        LOGE("nativeHookMethod: cannot get hooker class");
        return JNI_FALSE;
    }

    jmethodID callbackMethodId = env->GetMethodID(
        hookerClass, "callback", "([Ljava/lang/Object;)Ljava/lang/Object;");
    if (callbackMethodId == nullptr) {
        LOGE("nativeHookMethod: callback method not found in hooker class");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(hookerClass);
        return JNI_FALSE;
    }

    // 将 jmethodID 转换为 jobject (Method 引用)
    jobject callbackMethodObj = env->ToReflectedMethod(
        hookerClass, callbackMethodId, JNI_FALSE);
    if (callbackMethodObj == nullptr) {
        LOGE("nativeHookMethod: cannot convert callback methodID to Method object");
        env->DeleteLocalRef(hookerClass);
        return JNI_FALSE;
    }

    // 调用 lsplant::Hook(env, targetMethod, hookerObject, callbackMethod)
    jobject backup = g_lsplant_hook(env, targetMethod, hookerObject, callbackMethodObj);

    env->DeleteLocalRef(hookerClass);
    env->DeleteLocalRef(callbackMethodObj);

    if (backup != nullptr) {
        env->DeleteLocalRef(backup);
        LOGI("nativeHookMethod: hook succeeded");
        return JNI_TRUE;
    } else {
        LOGE("nativeHookMethod: lsplant::Hook returned null (hook failed)");
        return JNI_FALSE;
    }
}

} // extern "C"
