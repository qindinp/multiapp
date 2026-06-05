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

    // Spoof /proc/self/maps — filter out MultiApp and hook framework entries
    if (is_proc_self_path(path) && strcmp(path, "/proc/self/maps") == 0) {
        FILE* real_maps = real_fopen(path, mode);
        if (real_maps) {
            FILE* tmp = tmpfile();
            if (tmp) {
                char line[1024];
                while (fgets(line, sizeof(line), real_maps)) {
                    // Hide entries containing hook framework / root / Xposed library names
                    if (strstr(line, "multiapp") == nullptr &&
                        strstr(line, "shadowhook") == nullptr &&
                        strstr(line, "lsplant") == nullptr &&
                        strstr(line, "dobby") == nullptr &&
                        strstr(line, "bhook") == nullptr &&
                        strstr(line, "xhook") == nullptr &&
                        strstr(line, "substrate") == nullptr &&
                        strstr(line, "xposed") == nullptr &&
                        strstr(line, "libnextvm") == nullptr &&
                        strstr(line, "LSPosed") == nullptr &&
                        strstr(line, "edxposed") == nullptr &&
                        strstr(line, "riru") == nullptr &&
                        strstr(line, "zygisk") == nullptr &&
                        strstr(line, "magisk") == nullptr &&
                        strstr(line, "/data/adb") == nullptr) {
                        // Linker path spoofing: if line contains linker, ensure path looks normal
                        if (strstr(line, "linker64") != nullptr || strstr(line, "linker") != nullptr) {
                            // Replace any suspicious linker paths with standard system path
                            char* suspicious = strstr(line, "/data/adb");
                            if (suspicious == nullptr) {
                                suspicious = strstr(line, "/data/local");
                            }
                            if (suspicious == nullptr) {
                                fputs(line, tmp);
                            }
                            // If linker path is suspicious, skip the line entirely
                        } else {
                            fputs(line, tmp);
                        }
                    }
                }
                fclose(real_maps);
                fseek(tmp, 0, SEEK_SET);
                return tmp;
            }
            fclose(real_maps);
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

// FindClass hook: 在 JNI_OnLoad 中用 guest ClassLoader 查找加固壳类
static jobject g_guest_classloader = nullptr;
static jclass g_findclass_target_class = nullptr;
static std::unordered_set<std::string> g_findclass_targets; // e.g. {"com/stub/StubApp", "com/qihoo/util/StubApp"}
static jmethodID g_classloader_loadclass = nullptr;
static void* g_orig_findclass = nullptr; // 原始 FindClass 函数指针

// Type alias matching ART's native signature for Runtime.nativeLoad
// static jni: (JNIEnv*, jclass, jstring filename, jobject classLoader, jclass caller) -> jstring
typedef jstring (*NativeLoadFn)(JNIEnv*, jclass, jstring, jobject, jclass);

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

// 检查路径是否是 proc maps 相关
static bool is_proc_maps_path(const char* path) {
    if (path == nullptr) return false;
    return strstr(path, "/proc/self/maps") != nullptr ||
           strstr(path, "/proc/self/smaps") != nullptr ||
           strstr(path, "/proc/self/pagemap") != nullptr ||
           strstr(path, "/proc/./self/maps") != nullptr;
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
    bool is_libc = (strstr(lib_name, "libc.so") != nullptr);
    if (!is_target && !is_libc) return 0;

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

        for (ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
            if (d->d_tag != DT_JMPREL) continue;

            ElfW(Rel)* rel = (ElfW(Rel)*)(info->dlpi_addr + d->d_un.d_ptr);
            size_t rel_count = 0;

            for (ElfW(Dyn)* d2 = dyn; d2->d_tag != DT_NULL; d2++) {
                if (d2->d_tag == DT_PLTRELSZ) {
                    rel_count = d2->d_un.d_val / sizeof(ElfW(Rel));
                    break;
                }
            }

            LOGI("got_hook: %s checking %zu relocations", lib_name, rel_count);
            int hooked = 0;

            for (size_t j = 0; j < rel_count; j++) {
                size_t sym_idx = get_elf_r_sym(rel[j].r_info);
                const char* sym_name = strtab + symtab[sym_idx].st_name;
                ElfW(Addr)* got_entry = (ElfW(Addr)*)(info->dlpi_addr + rel[j].r_offset);
                uintptr_t page = (uintptr_t)got_entry & ~(sysconf(_SC_PAGESIZE) - 1);

                if (strcmp(sym_name, "open") == 0) {
                    got_orig_open = (orig_open_t)*got_entry;
                    mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ | PROT_WRITE);
                    *got_entry = (ElfW(Addr))got_hooked_open;
                    mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ);
                    hooked++;
                }
                else if (strcmp(sym_name, "openat") == 0) {
                    got_orig_openat = (orig_openat_t)*got_entry;
                    mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ | PROT_WRITE);
                    *got_entry = (ElfW(Addr))got_hooked_openat;
                    mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ);
                    hooked++;
                }
                else if (strcmp(sym_name, "fopen") == 0) {
                    got_orig_fopen = (orig_fopen_t)*got_entry;
                    mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ | PROT_WRITE);
                    *got_entry = (ElfW(Addr))got_hooked_fopen;
                    mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ);
                    hooked++;
                }
                else if (strcmp(sym_name, "readlink") == 0) {
                    got_orig_readlink = (orig_readlink_t)*got_entry;
                    mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ | PROT_WRITE);
                    *got_entry = (ElfW(Addr))got_hooked_readlink;
                    mprotect((void*)page, sysconf(_SC_PAGESIZE), PROT_READ);
                    hooked++;
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
    bool has_open;
    bool has_openat;
    bool has_fopen;
    bool has_readlink;
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
        *orig_ptr = (void*)*got;
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

    LOGI("got_hook_immediate: base=%p open=%d openat=%d fopen=%d readlink=%d",
         (void*)base_addr, info.has_open, info.has_openat, info.has_fopen, info.has_readlink);
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
        LOGI("nativePreloadLibraries: pre-parsed %s (open=%d openat=%d fopen=%d readlink=%d)",
             path, got_info.has_open, got_info.has_openat, got_info.has_fopen, got_info.has_readlink);

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
            LOGW("nativePreloadLibraries: JNI_OnLoad returned %d for %s", onLoadResult, path);
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
    jmethodID nativeLoad = env->GetMethodID(
        runtimeClass, "nativeLoad",
        "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;");
    env->DeleteLocalRef(runtimeClass);

    if (nativeLoad == nullptr) {
        LOGE("nativeLoadLibraryForGuest: nativeLoad method not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(runtime);
        return -5;
    }

    // 调用 runtime.nativeLoad(libPath, classLoader, callerClass)
    const char* path = env->GetStringUTFChars(libPath, nullptr);
    LOGI("nativeLoadLibraryForGuest: calling nativeLoad(%s)", path ? path : "null");
    env->ReleaseStringUTFChars(libPath, path);

    jstring error = (jstring)env->CallObjectMethod(
        runtime, nativeLoad, libPath, classLoader, callerClass);
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
    if (g_guest_classloader != nullptr && name != nullptr && g_classloader_loadclass != nullptr) {
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

// interface5(Application) 的 stub 实现
static void JNICALL stub_interface_app(JNIEnv* env, jclass clazz, jobject app) {
    LOGI("stub_interface_app called (Application param, no-op)");
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
        // interface11(int)V
        {const_cast<char*>("interface11"), const_cast<char*>("(I)V"), (void*)stub_interface_app},
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

} // extern "C"
