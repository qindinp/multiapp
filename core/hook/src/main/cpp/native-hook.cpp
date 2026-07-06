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
#include <sys/syscall.h>
#include <signal.h>
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
#include <cstdint>
#include <atomic>
#include <chrono>
#include <fstream>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

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

static std::atomic_bool g_initialized{false};
static std::atomic_bool g_register_natives_business_wrappers_enabled{false};
static bool g_hooks_installed = false;
static std::shared_mutex g_mutex;
static std::atomic_bool g_suppress_self_sigkill{false};
static std::mutex g_online_materialize_mutex;

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
typedef void* (*orig_android_dlopen_ext_t)(const char*, int, const void*);
typedef void (*orig_exit_t)(int);
typedef void (*orig_abort_t)();
typedef int (*orig_kill_t)(pid_t, int);
typedef int (*orig_tgkill_t)(pid_t, pid_t, int);

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
static orig_android_dlopen_ext_t real_android_dlopen_ext = nullptr;
static orig_android_dlopen_ext_t real_loader_android_dlopen_ext = nullptr;
static orig_exit_t real_exit = nullptr;
static orig_exit_t real__exit = nullptr;
static orig_abort_t real_abort = nullptr;

// LSPlant bypass flag: when true, hooked_dlopen allows lsplant loading
// This is needed because nativeInitLsplant calls dlopen("liblsplant.so")
// which would otherwise be blocked by the anti-detection hook
static thread_local bool g_lsplant_dlopen_bypass = false;

static void got_hook_library_callback_wrapper(const char* path);
static void patch_loaded_jiagu_vip_self_kill_callsites();

static void on_native_library_loaded_early(const char* apiName, const char* filename, void* handle) {
    if (handle == nullptr || filename == nullptr || strstr(filename, "libjiagu_vip.so") == nullptr) {
        return;
    }
    LOGW("%s: libjiagu_vip.so loaded early, installing GOT/self-kill hooks before JNI_OnLoad path=%s handle=%p",
         apiName,
         filename,
         handle);
    got_hook_library_callback_wrapper(filename);
    patch_loaded_jiagu_vip_self_kill_callsites();
}

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
                if (len >= PROP_VALUE_MAX) len = PROP_VALUE_MAX - 1;
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

static void* hooked_android_dlopen_ext(const char* filename, int flags, const void* extinfo) {
    if (real_android_dlopen_ext == nullptr) {
        LOGW("android_dlopen_ext: original pointer is null for %s", filename ? filename : "null");
        return nullptr;
    }
    void* handle = real_android_dlopen_ext(filename, flags, extinfo);
    on_native_library_loaded_early("android_dlopen_ext", filename, handle);
    return handle;
}

static void* hooked_loader_android_dlopen_ext(const char* filename, int flags, const void* extinfo) {
    if (real_loader_android_dlopen_ext == nullptr) {
        LOGW("__loader_android_dlopen_ext: original pointer is null for %s", filename ? filename : "null");
        return nullptr;
    }
    void* handle = real_loader_android_dlopen_ext(filename, flags, extinfo);
    on_native_library_loaded_early("__loader_android_dlopen_ext", filename, handle);
    return handle;
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

// ==================== dl_iterate_phdr Hook ====================
// 360 加固壳使用 dl_iterate_phdr 枚举已加载库来检测 hook 框架。
// 我们需要过滤掉 libmultiapp-native.so、liblsplant.so、libshadowhook.so 等。

static const char* g_hidden_libs[] = {
    "libmultiapp-native.so",
    "liblsplant.so",
    "libshadowhook.so",
    "libshadowhook_nothing.so",
    "libc++_shared.so",
    nullptr
};

struct DlIteratePhdrWrapper {
    int (*original_callback)(struct dl_phdr_info*, size_t, void*);
    void* original_data;
};

static int wrapped_dl_iterate_phdr_callback(struct dl_phdr_info* info, size_t size, void* data) {
    auto* wrapper = static_cast<DlIteratePhdrWrapper*>(data);
    if (info != nullptr && info->dlpi_name != nullptr) {
        for (int i = 0; g_hidden_libs[i] != nullptr; i++) {
            if (strstr(info->dlpi_name, g_hidden_libs[i]) != nullptr) {
                return 0; // skip hidden library
            }
        }
    }
    return wrapper->original_callback(info, size, wrapper->original_data);
}

static int (*real_dl_iterate_phdr)(int (*callback)(struct dl_phdr_info*, size_t, void*), void* data) = nullptr;

static int hooked_dl_iterate_phdr(int (*callback)(struct dl_phdr_info*, size_t, void*), void* data) {
    if (real_dl_iterate_phdr == nullptr) return 0;
    DlIteratePhdrWrapper wrapper{callback, data};
    return real_dl_iterate_phdr(wrapped_dl_iterate_phdr_callback, &wrapper);
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
    {"libdl.so",    "android_dlopen_ext",      (void*)hooked_android_dlopen_ext,   (void**)&real_android_dlopen_ext},
    {"libdl.so",    "__loader_android_dlopen_ext", (void*)hooked_loader_android_dlopen_ext, (void**)&real_loader_android_dlopen_ext},
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
            LOGW("Failed to hook %s in %s: errno=%d(%s)", g_hook_entries[i].symbol, lib, err, shadowhook_to_errmsg(err));
        }
    }

    LOGI("ShadowHook: %d/%d hooks installed", success_count, g_hook_count);
    return success_count > 0;
}

static const char* shadowhook_mode_name(shadowhook_mode_t mode) {
    return mode == SHADOWHOOK_MODE_UNIQUE ? "UNIQUE" : "SHARED";
}

static bool init_shadowhook_with_mode(const char* caller, shadowhook_mode_t mode) {
    int ret = shadowhook_init(mode, false);
    int current_errno = shadowhook_get_errno();
    LOGI("%s: shadowhook_init mode=%s ret=%d(%s) current_errno=%d(%s) current_mode=%d debuggable=%d recordable=%d",
         caller,
         shadowhook_mode_name(mode),
         ret,
         shadowhook_to_errmsg(ret),
         current_errno,
         shadowhook_to_errmsg(current_errno),
         static_cast<int>(shadowhook_get_mode()),
         shadowhook_get_debuggable() ? 1 : 0,
         shadowhook_get_recordable() ? 1 : 0);
    return ret == SHADOWHOOK_ERRNO_OK;
}

static bool init_shadowhook_for_runtime(const char* caller) {
    if (init_shadowhook_with_mode(caller, SHADOWHOOK_MODE_SHARED)) {
        return true;
    }
    LOGW("%s: SHARED init failed, retrying UNIQUE mode", caller);
    if (init_shadowhook_with_mode(caller, SHADOWHOOK_MODE_UNIQUE)) {
        return true;
    }
    LOGW("%s: ShadowHook init failed in both SHARED and UNIQUE modes", caller);
    return false;
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

    if (g_initialized.load()) {
        LOGI("Native hook engine already initialized");
        return JNI_TRUE;
    }

    LOGI("Initializing MultiApp native hook engine...");

    bool shadowhookReady = init_shadowhook_for_runtime("nativeInit");
    g_hooks_installed = shadowhookReady && install_shadowhook_hooks();
    if (!g_hooks_installed) {
        LOGW("ShadowHook installation failed — falling back to Java-level hooks only");
    }

    g_initialized.store(true);
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
    return g_initialized.load() ? JNI_TRUE : JNI_FALSE;
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
static std::atomic_int g_online_chapter_register_count{0};

// FindClass hook: 在 JNI_OnLoad 中用 guest ClassLoader 查找加固壳类
static void patch_loaded_jiagu_vip_self_kill_callsites();
static bool patch_jiagu_self_kill_from_return_address(void* caller);
static bool patch_jiagu_vip_env_check(uintptr_t base, const char* path);
static void dump_decrypted_jiagu_code();
static int dump_jiagu_runtime_ranges(const char* dump_dir);
static uintptr_t find_loaded_library_base(const char* path);
static bool is_readable_proc_range(uintptr_t address, size_t length);
static bool patch_arm64_instruction(uintptr_t address, uint32_t expected_mask, uint32_t expected_value, uint32_t replacement);

static jobject g_guest_classloader = nullptr;
static jobject g_hook_classloader = nullptr;
static jclass g_findclass_target_class = nullptr;
static std::unordered_set<std::string> g_findclass_targets; // e.g. {"com/stub/StubApp", "com/qihoo/util/StubApp"}
static jmethodID g_classloader_loadclass = nullptr;

static bool ensure_classloader_loadclass(JNIEnv* env) {
    if (g_classloader_loadclass != nullptr) return true;
    jclass clClass = env->FindClass("java/lang/ClassLoader");
    if (clClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("ensure_classloader_loadclass: ClassLoader class not found");
        return false;
    }
    g_classloader_loadclass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    env->DeleteLocalRef(clClass);
    if (g_classloader_loadclass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("ensure_classloader_loadclass: ClassLoader.loadClass not found");
        return false;
    }
    return true;
}

static void remember_hook_classloader(JNIEnv* env, jclass bridgeClass) {
    if (g_hook_classloader != nullptr || bridgeClass == nullptr) return;
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("remember_hook_classloader: java.lang.Class not found");
        return;
    }
    jmethodID getClassLoader = env->GetMethodID(classClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    env->DeleteLocalRef(classClass);
    if (getClassLoader == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("remember_hook_classloader: Class.getClassLoader not found");
        return;
    }
    jobject loader = env->CallObjectMethod(bridgeClass, getClassLoader);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("remember_hook_classloader: Class.getClassLoader threw");
        return;
    }
    if (loader != nullptr) {
        g_hook_classloader = env->NewGlobalRef(loader);
        env->DeleteLocalRef(loader);
        LOGI("remember_hook_classloader: hook ClassLoader captured");
    } else {
        LOGW("remember_hook_classloader: hook ClassLoader is null");
    }
}
static void* g_orig_findclass = nullptr; // 原始 FindClass 函数指针

static bool g_jiagu_jni_diag_hooks_installed = false;
static void* g_orig_get_method_id = nullptr;
static void* g_orig_get_static_method_id = nullptr;
static void* g_orig_get_field_id = nullptr;
static void* g_orig_get_static_field_id = nullptr;
static void* g_orig_call_object_method_v = nullptr;
static void* g_orig_call_object_method_a = nullptr;
static void* g_orig_call_boolean_method_v = nullptr;
static void* g_orig_call_boolean_method_a = nullptr;
static void* g_orig_call_int_method_v = nullptr;
static void* g_orig_call_int_method_a = nullptr;
static void* g_orig_call_void_method_v = nullptr;
static void* g_orig_call_void_method_a = nullptr;
static void* g_orig_call_static_object_method_v = nullptr;
static void* g_orig_call_static_object_method_a = nullptr;
static void* g_orig_call_static_boolean_method_v = nullptr;
static void* g_orig_call_static_boolean_method_a = nullptr;
static void* g_orig_call_static_int_method_v = nullptr;
static void* g_orig_call_static_int_method_a = nullptr;
static void* g_orig_call_static_void_method_v = nullptr;
static void* g_orig_call_static_void_method_a = nullptr;
static void* g_orig_get_string_utf_length = nullptr;
static void* g_orig_get_string_utf_chars = nullptr;
static void* g_orig_release_string_utf_chars = nullptr;
static void* g_orig_get_array_length = nullptr;
static void* g_orig_get_byte_array_elements = nullptr;
static void* g_orig_release_byte_array_elements = nullptr;
static void* g_orig_exception_occurred = nullptr;
static void* g_orig_exception_clear = nullptr;
static void* g_orig_exception_check = nullptr;
static std::mutex g_jiagu_jni_diag_mutex;
static std::unordered_map<jmethodID, std::string> g_jiagu_jni_method_names;
static std::unordered_map<jfieldID, std::string> g_jiagu_jni_field_names;
static thread_local bool g_jiagu_jni_diag_in_hook = false;
static std::mutex g_jiagu_pkg_spoof_mutex;
static std::string g_jiagu_stub_package;
static std::string g_jiagu_original_package;

static bool remember_hook_classloader_object(JNIEnv* env, jobject classLoader, const char* source) {
    if (g_hook_classloader != nullptr) return true;
    if (classLoader == nullptr) {
        LOGW("remember_hook_classloader_object: null ClassLoader from %s", source);
        return false;
    }
    jobject globalLoader = env->NewGlobalRef(classLoader);
    if (globalLoader == nullptr) {
        LOGW("remember_hook_classloader_object: NewGlobalRef failed from %s", source);
        return false;
    }
    g_hook_classloader = globalLoader;
    LOGI("remember_hook_classloader_object: hook ClassLoader captured from %s", source);
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRememberHookClassLoader(
    JNIEnv* env, jclass clazz, jobject classLoader)
{
    (void)clazz;
    return remember_hook_classloader_object(env, classLoader, "NativeHookBridge") ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSetJiaguPackageSpoof(
    JNIEnv* env, jobject thiz, jstring stubPackageName, jstring originalPackageName)
{
    (void)thiz;
    const char* stubChars = stubPackageName != nullptr ? env->GetStringUTFChars(stubPackageName, nullptr) : nullptr;
    const char* originalChars = originalPackageName != nullptr ? env->GetStringUTFChars(originalPackageName, nullptr) : nullptr;
    {
        std::lock_guard<std::mutex> lock(g_jiagu_pkg_spoof_mutex);
        g_jiagu_stub_package = stubChars != nullptr ? stubChars : "";
        g_jiagu_original_package = originalChars != nullptr ? originalChars : "";
    }
    LOGI("nativeSetJiaguPackageSpoof: stub=%s original=%s",
         stubChars != nullptr ? stubChars : "<null>",
         originalChars != nullptr ? originalChars : "<null>");
    if (stubChars != nullptr) env->ReleaseStringUTFChars(stubPackageName, stubChars);
    if (originalChars != nullptr) env->ReleaseStringUTFChars(originalPackageName, originalChars);
}

// Type alias matching ART's native signature for Runtime.nativeLoad
// static jni: (JNIEnv*, jclass, jstring filename, jobject classLoader, jclass caller) -> jstring
typedef jstring (*NativeLoadFn)(JNIEnv*, jclass, jstring, jobject, jclass);
typedef jint (*RegisterNativesFn)(JNIEnv*, jclass, const JNINativeMethod*, jint);
typedef jmethodID (*GetMethodIDFn)(JNIEnv*, jclass, const char*, const char*);
typedef jmethodID (*GetStaticMethodIDFn)(JNIEnv*, jclass, const char*, const char*);
typedef jfieldID (*GetFieldIDFn)(JNIEnv*, jclass, const char*, const char*);
typedef jfieldID (*GetStaticFieldIDFn)(JNIEnv*, jclass, const char*, const char*);
typedef jobject (*CallObjectMethodVFn)(JNIEnv*, jobject, jmethodID, va_list);
typedef jobject (*CallObjectMethodAFn)(JNIEnv*, jobject, jmethodID, const jvalue*);
typedef jboolean (*CallBooleanMethodVFn)(JNIEnv*, jobject, jmethodID, va_list);
typedef jboolean (*CallBooleanMethodAFn)(JNIEnv*, jobject, jmethodID, const jvalue*);
typedef jint (*CallIntMethodVFn)(JNIEnv*, jobject, jmethodID, va_list);
typedef jint (*CallIntMethodAFn)(JNIEnv*, jobject, jmethodID, const jvalue*);
typedef void (*CallVoidMethodVFn)(JNIEnv*, jobject, jmethodID, va_list);
typedef void (*CallVoidMethodAFn)(JNIEnv*, jobject, jmethodID, const jvalue*);
typedef jobject (*CallStaticObjectMethodVFn)(JNIEnv*, jclass, jmethodID, va_list);
typedef jobject (*CallStaticObjectMethodAFn)(JNIEnv*, jclass, jmethodID, const jvalue*);
typedef jboolean (*CallStaticBooleanMethodVFn)(JNIEnv*, jclass, jmethodID, va_list);
typedef jboolean (*CallStaticBooleanMethodAFn)(JNIEnv*, jclass, jmethodID, const jvalue*);
typedef jint (*CallStaticIntMethodVFn)(JNIEnv*, jclass, jmethodID, va_list);
typedef jint (*CallStaticIntMethodAFn)(JNIEnv*, jclass, jmethodID, const jvalue*);
typedef void (*CallStaticVoidMethodVFn)(JNIEnv*, jclass, jmethodID, va_list);
typedef void (*CallStaticVoidMethodAFn)(JNIEnv*, jclass, jmethodID, const jvalue*);
typedef jsize (*GetStringUTFLengthFn)(JNIEnv*, jstring);
typedef const char* (*GetStringUTFCharsFn)(JNIEnv*, jstring, jboolean*);
typedef void (*ReleaseStringUTFCharsFn)(JNIEnv*, jstring, const char*);
typedef jsize (*GetArrayLengthFn)(JNIEnv*, jarray);
typedef jbyte* (*GetByteArrayElementsFn)(JNIEnv*, jbyteArray, jboolean*);
typedef void (*ReleaseByteArrayElementsFn)(JNIEnv*, jbyteArray, jbyte*, jint);
typedef jthrowable (*ExceptionOccurredFn)(JNIEnv*);
typedef void (*ExceptionClearFn)(JNIEnv*);
typedef jboolean (*ExceptionCheckFn)(JNIEnv*);
typedef jint (*FockItFn)(JNIEnv*, jclass, jbyteArray, jint);
typedef void (*FockAkFn)(JNIEnv*, jclass, jbyteArray, jint, jbyteArray);
typedef jstring (*FockSnFn)(JNIEnv*, jclass, jbyteArray, jint);
typedef jstring (*FockUrkFn)(JNIEnv*, jclass);
typedef void (*YwPwdLoginFn)(JNIEnv*, jobject, jobject, jstring, jstring, jobject);
typedef void (*YwSendPhoneCodeFn)(JNIEnv*, jobject, jobject, jstring, jint, jint, jobject);
typedef void (*YwQrCodeV2Fn)(JNIEnv*, jobject, jobject);

static jstring JNICALL stub_fock_sign_md5(JNIEnv* env, jclass clazz, jbyteArray data, jint len);
static void notify_ywlogin_error(JNIEnv* env, jobject callback, const char* message);
static void JNICALL wrapped_ywlogin_pwdLogin(JNIEnv* env, jobject thiz, jobject activity, jstring account, jstring password, jobject callback);
static void JNICALL wrapped_ywlogin_sendPhoneCode(JNIEnv* env, jobject thiz, jobject context, jstring phone, jint type, jint scene, jobject callback);
static void JNICALL wrapped_ywlogin_qrCodeV2(JNIEnv* env, jobject thiz, jobject callback);

static FockItFn g_orig_fock_it = nullptr;
static FockAkFn g_orig_fock_ak = nullptr;
static FockSnFn g_orig_fock_sn = nullptr;
static FockUrkFn g_orig_fock_urk = nullptr;
static YwPwdLoginFn g_orig_ywlogin_pwdLogin = nullptr;
static YwSendPhoneCodeFn g_orig_ywlogin_sendPhoneCode = nullptr;
static YwQrCodeV2Fn g_orig_ywlogin_qrCodeV2 = nullptr;
static std::mutex g_ywlogin_defaults_mutex;
static jobject g_ywlogin_default_parameters = nullptr;
static jobject g_ywlogin_application = nullptr;
static jobject g_ywlogin_sign_callback = nullptr;
static jobject g_ywlogin_parameter_getter = nullptr;
static jobject g_ywlogin_manager_instance = nullptr;
static std::mutex g_fock_bootstrap_mutex;
static std::mutex g_fock_sn_mutex;
static bool g_fock_bootstrap_done = false;

using StubInterfaceAppFn = void (*)(JNIEnv*, jclass, jobject);
using StubInterface11Fn = void (*)(JNIEnv*, jclass, jint);
using StubInterface20Fn = jboolean (*)(JNIEnv*, jclass);
using JiaguTokenInsertFn = uintptr_t (*)(void*, void*, void*);
using JiaguBuildRegisterVectorFn = uintptr_t (*)(void*);
using JiaguTokenManagerInitFn = uintptr_t (*)();
using JiaguRegisterGateFn = uintptr_t (*)(void*, void*);
using JiaguInterface20RegisterFn = void (*)(void*);
using JiaguPayloadBuildFn = void (*)(void*, void*, void*, int, int, void*, void*);
using JiaguCompareFn = int (*)(const void*, const void*, size_t);
using JiaguEnvProbeFn = int (*)(void*);
using JiaguStringEqualsFn = int (*)(const void*, const void*);
using JiaguPayloadCheckFn = int (*)(void*);
using JiaguQiniuCheckFn = int (*)(void*);
using JiaguPostPayloadStatusFn = int (*)(void*);
using JiaguPostPayloadObjectFn = void* (*)(void*);
using JiaguPostPayloadMaterializeFn = void (*)(void*, void*, void*, void*, int, int);
using JiaguAfterMaterializeNormalizeFn = void (*)(void*);

static StubInterfaceAppFn g_orig_stub_interface5 = nullptr;
static StubInterface11Fn g_orig_stub_interface11 = nullptr;
static StubInterface20Fn g_orig_stub_interface20 = nullptr;
static StubInterfaceAppFn g_orig_stub_interface21 = nullptr;
static JiaguTokenInsertFn g_orig_jiagu_token_insert = nullptr;
static void* g_jiagu_token_insert_hook_stub = nullptr;
static std::mutex g_jiagu_token_insert_hook_mutex;
static std::atomic_bool g_jiagu_token_insert_hook_installed{false};
static std::atomic_int g_jiagu_token_insert_hook_attempts{0};
static std::atomic_int g_jiagu_token_insert_hook_failures{0};
static std::atomic_int g_jiagu_token_insert_calls{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_manager{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_owner{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_payload{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_owner_vec_begin{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_owner_vec_end{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_owner_vec_cap{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_payload_word0{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_payload_word8{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_manager_root_after{0};
static std::atomic<uintptr_t> g_jiagu_token_insert_last_manager_count_after{0};
static std::atomic<uint32_t> g_jiagu_token_insert_last_payload_key{0};
static JiaguBuildRegisterVectorFn g_orig_jiagu_build_register_vector = nullptr;
static void* g_jiagu_build_register_vector_hook_stub = nullptr;
static std::atomic_bool g_jiagu_build_register_vector_hook_installed{false};
static std::atomic_int g_jiagu_build_register_vector_calls{0};
static std::atomic<uintptr_t> g_jiagu_build_register_vector_last_arg{0};
static std::atomic<uintptr_t> g_jiagu_build_register_vector_last_result{0};
static std::atomic<uintptr_t> g_jiagu_build_register_vector_last_begin{0};
static std::atomic<uintptr_t> g_jiagu_build_register_vector_last_end{0};
static std::atomic<uintptr_t> g_jiagu_build_register_vector_last_first_item{0};
static std::atomic<uintptr_t> g_jiagu_build_register_vector_last_first_payload_begin{0};
static std::atomic<uintptr_t> g_jiagu_build_register_vector_last_first_payload_end{0};
static JiaguTokenManagerInitFn g_orig_jiagu_token_manager_init = nullptr;
static void* g_jiagu_token_manager_init_hook_stub = nullptr;
static std::atomic_bool g_jiagu_token_manager_init_hook_installed{false};
static std::atomic_int g_jiagu_token_manager_init_calls{0};
static std::atomic<uintptr_t> g_jiagu_token_manager_init_last_result{0};
static std::atomic<uintptr_t> g_jiagu_token_manager_init_last_root{0};
static std::atomic<uintptr_t> g_jiagu_token_manager_init_last_count{0};
static JiaguRegisterGateFn g_orig_jiagu_register_gate = nullptr;
static void* g_jiagu_register_gate_hook_stub = nullptr;
static std::atomic_bool g_jiagu_register_gate_hook_installed{false};
static std::atomic_int g_jiagu_register_gate_calls{0};
static std::atomic<uintptr_t> g_jiagu_register_gate_last_registry{0};
static std::atomic<uintptr_t> g_jiagu_register_gate_last_key_arg{0};
static std::atomic<uintptr_t> g_jiagu_register_gate_last_result{0};
static std::mutex g_jiagu_register_gate_key_mutex;
static std::string g_jiagu_register_gate_last_key;
static JiaguInterface20RegisterFn g_orig_jiagu_interface20_register = nullptr;
static void* g_jiagu_interface20_register_hook_stub = nullptr;
static std::atomic_bool g_jiagu_interface20_register_hook_installed{false};
static std::atomic_int g_jiagu_interface20_register_calls{0};
static std::atomic<uintptr_t> g_jiagu_interface20_register_last_env{0};
static JiaguPayloadBuildFn g_orig_jiagu_payload_build = nullptr;
static void* g_jiagu_payload_build_hook_stub = nullptr;
static std::atomic_bool g_jiagu_payload_build_hook_installed{false};
static std::atomic_int g_jiagu_payload_build_calls{0};
static std::atomic<uintptr_t> g_jiagu_payload_build_last_env{0};
static std::atomic<uintptr_t> g_jiagu_payload_build_last_arg1{0};
static std::atomic<uintptr_t> g_jiagu_payload_build_last_s1{0};
static std::atomic<uintptr_t> g_jiagu_payload_build_last_s2{0};
static std::atomic<uintptr_t> g_jiagu_payload_build_last_s3{0};
static std::atomic_int g_jiagu_payload_build_last_flag3{0};
static std::atomic_int g_jiagu_payload_build_last_flag4{0};
static std::mutex g_jiagu_payload_build_mutex;
static std::string g_jiagu_payload_build_last_s1_text;
static std::string g_jiagu_payload_build_last_s2_text;
static std::string g_jiagu_payload_build_last_s3_text;
static JiaguPayloadCheckFn g_orig_jiagu_payload_check = nullptr;
static void* g_jiagu_payload_check_hook_stub = nullptr;
static std::atomic_bool g_jiagu_payload_check_hook_installed{false};
static std::atomic_int g_jiagu_payload_check_calls{0};
static std::atomic<uintptr_t> g_jiagu_payload_check_last_arg{0};
static std::atomic_int g_jiagu_payload_check_last_result{0};
static std::atomic_int g_jiagu_payload_check_last_forced{0};
static std::mutex g_jiagu_payload_check_mutex;
static std::string g_jiagu_payload_check_last_text;
static JiaguPostPayloadStatusFn g_orig_jiagu_post_payload_status = nullptr;
static void* g_jiagu_post_payload_status_hook_stub = nullptr;
static std::atomic_bool g_jiagu_post_payload_status_hook_installed{false};
static std::atomic_int g_jiagu_post_payload_status_calls{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_status_last_arg{0};
static std::atomic_int g_jiagu_post_payload_status_last_result{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_status_last_caller_off{0};
static JiaguPostPayloadObjectFn g_orig_jiagu_post_payload_object = nullptr;
static void* g_jiagu_post_payload_object_hook_stub = nullptr;
static std::atomic_bool g_jiagu_post_payload_object_hook_installed{false};
static std::atomic_int g_jiagu_post_payload_object_calls{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_object_last_arg{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_object_last_result{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_object_last_caller_off{0};
static JiaguPostPayloadMaterializeFn g_orig_jiagu_post_payload_materialize = nullptr;
static void* g_jiagu_post_payload_materialize_hook_stub = nullptr;
static std::atomic_bool g_jiagu_post_payload_materialize_hook_installed{false};
static std::atomic_int g_jiagu_post_payload_materialize_calls{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_materialize_last_caller_off{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_materialize_last_arg0{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_materialize_last_arg1{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_materialize_last_arg2{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_materialize_last_arg3{0};
static std::atomic_int g_jiagu_post_payload_materialize_last_flag4{0};
static std::atomic_int g_jiagu_post_payload_materialize_last_flag5{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_materialize_slot270{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_materialize_slot290{0};
static std::atomic<uintptr_t> g_jiagu_post_payload_materialize_slot2f0{0};
static std::atomic<uint32_t> g_jiagu_post_payload_materialize_slot358{0};
static std::mutex g_jiagu_post_payload_materialize_mutex;
static std::string g_jiagu_post_payload_materialize_arg1_text;
static std::string g_jiagu_post_payload_materialize_arg2_text;
static std::string g_jiagu_post_payload_materialize_arg3_text;
static std::string g_jiagu_post_payload_materialize_slot270_text;
static std::string g_jiagu_post_payload_materialize_slot290_text;
static std::string g_jiagu_post_payload_materialize_slot2f0_text;
static JiaguAfterMaterializeNormalizeFn g_orig_jiagu_after_materialize_normalize = nullptr;
static void* g_jiagu_after_materialize_normalize_hook_stub = nullptr;
static std::atomic_bool g_jiagu_after_materialize_normalize_hook_installed{false};
static std::atomic_int g_jiagu_after_materialize_normalize_calls{0};
static std::atomic<uintptr_t> g_jiagu_after_materialize_normalize_last_caller_off{0};
static std::atomic_bool g_jiagu_force_post_payload_branch_patched{false};
static std::atomic_bool g_jiagu_force_pre_materialize_gate1_patched{false};
static std::atomic_bool g_jiagu_force_pre_materialize_gate2_patched{false};
static std::atomic_bool g_jiagu_force_qiniu_gate_patched{false};
static JiaguCompareFn g_orig_jiagu_compare = nullptr;
static std::atomic_bool g_jiagu_compare_hook_installed{false};
static std::atomic_int g_jiagu_compare_calls{0};
static std::atomic_int g_jiagu_compare_logged_calls{0};
static std::atomic<uintptr_t> g_jiagu_compare_got_slot{0};
static std::atomic<uintptr_t> g_jiagu_compare_last_caller_off{0};
static std::atomic<uintptr_t> g_jiagu_compare_last_arg0{0};
static std::atomic<uintptr_t> g_jiagu_compare_last_arg1{0};
static std::atomic<size_t> g_jiagu_compare_last_len{0};
static std::atomic_int g_jiagu_compare_last_result{0};
static std::mutex g_jiagu_compare_mutex;
static std::string g_jiagu_compare_last_left;
static std::string g_jiagu_compare_last_right;
static JiaguEnvProbeFn g_orig_jiagu_env_probe = nullptr;
static void* g_jiagu_env_probe_hook_stub = nullptr;
static std::atomic_bool g_jiagu_env_probe_hook_installed{false};
static std::atomic_int g_jiagu_env_probe_calls{0};
static std::atomic<uintptr_t> g_jiagu_env_probe_last_caller_off{0};
static std::atomic<uintptr_t> g_jiagu_env_probe_last_arg{0};
static std::atomic_int g_jiagu_env_probe_last_result{0};
static JiaguQiniuCheckFn g_orig_jiagu_qiniu_check = nullptr;
static void* g_jiagu_qiniu_check_hook_stub = nullptr;
static std::atomic_bool g_jiagu_qiniu_check_hook_installed{false};
static std::atomic_int g_jiagu_qiniu_check_calls{0};
static std::atomic<uintptr_t> g_jiagu_qiniu_check_last_caller_off{0};
static std::atomic<uintptr_t> g_jiagu_qiniu_check_last_arg{0};
static std::atomic_int g_jiagu_qiniu_check_last_result{0};
static JiaguStringEqualsFn g_orig_jiagu_string_equals = nullptr;
static std::atomic_bool g_jiagu_string_equals_hook_installed{false};
static std::atomic_int g_jiagu_string_equals_calls{0};
static std::atomic<uintptr_t> g_jiagu_string_equals_got_slot{0};
static std::atomic<uintptr_t> g_jiagu_string_equals_last_caller_off{0};
static std::atomic<uintptr_t> g_jiagu_string_equals_last_arg0{0};
static std::atomic<uintptr_t> g_jiagu_string_equals_last_arg1{0};
static std::atomic_int g_jiagu_string_equals_last_result{0};
static std::mutex g_jiagu_string_equals_mutex;
static std::string g_jiagu_string_equals_last_left;
static std::string g_jiagu_string_equals_last_right;
static std::atomic<uintptr_t> g_jiagu_payload_build_slot_before{0};
static std::atomic<uintptr_t> g_jiagu_payload_build_slot_after{0};
static std::atomic<uintptr_t> g_jiagu_payload_build_slot8_before{0};
static std::atomic<uintptr_t> g_jiagu_payload_build_slot8_after{0};
static std::mutex g_stubapp_register_mutex;
static int g_stubapp_register_calls = 0;
static int g_stubapp_jiagu_register_calls = 0;
static int g_stubapp_multiapp_register_calls = 0;
static int g_stubapp_jiagu_complete_calls = 0;
static int g_stubapp_last_count = 0;
static int g_stubapp_last_result = JNI_ERR;
static bool g_stubapp_last_caller_is_jiagu = false;
static bool g_stubapp_last_all_multiapp = false;
static bool g_stubapp_last_has_interface11 = false;
static bool g_stubapp_last_has_interface20 = false;
static bool g_stubapp_original_jiagu_complete = false;
static bool g_stubapp_saw_jiagu_interface11 = false;
static bool g_stubapp_saw_jiagu_interface20 = false;
static std::string g_stubapp_last_class;
static std::string g_stubapp_last_caller;

static bool clear_logged_exception(JNIEnv* env, const char* label);
static void install_jiagu_token_insert_hook_from_stubapp(const char* source);
static void install_jiagu_fill_loop_hooks_from_stubapp(const char* source);

static std::atomic<long long> s_diag_last_check{0};
static std::atomic_bool s_diag_cached{false};

bool online_file_diag_enabled() {
    auto now_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    if (now_ms - s_diag_last_check.load() < 5000) {
        return s_diag_cached.load();
    }
    char val[PROP_VALUE_MAX];
    __system_property_get("debug.multiapp.online.file_diag", val);
    s_diag_cached.store(strcmp(val, "1") == 0);
    s_diag_last_check.store(now_ms);
    return s_diag_cached.load();
}

static bool should_call_original_fock_sn() {
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get("debug.multiapp.fock.call_original", value);
    if (len == 1 && value[0] == '0') {
        return false;
    }
    return true;
}

static bool should_use_diagnostic_fock_sn() {
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get("debug.multiapp.fock.diagnostic_md5", value);
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
    if (should_use_diagnostic_fock_sn()) {
        LOGW("Fock.sn original bypassed by debug property; returning diagnostic MD5");
        return stub_fock_sign_md5(env, clazz, data, len);
    }
    LOGW("Fock.sn original unavailable and diagnostic MD5 disabled");
    return env->NewStringUTF("");
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

static bool is_multiapp_native_fn(void* fnPtr) {
    if (fnPtr == nullptr) return false;
    Dl_info info{};
    if (dladdr(fnPtr, &info) == 0 || info.dli_fname == nullptr) {
        return false;
    }
    return strstr(info.dli_fname, "libmultiapp-native.so") != nullptr;
}

static bool is_address_from_library(void* address, const char* libraryName) {
    if (address == nullptr || libraryName == nullptr) return false;
    Dl_info info{};
    if (dladdr(address, &info) == 0 || info.dli_fname == nullptr) {
        return false;
    }
    return strstr(info.dli_fname, libraryName) != nullptr;
}

static std::string describe_native_address(void* address) {
    if (address == nullptr) return "<null>";
    Dl_info info{};
    if (dladdr(address, &info) == 0 || info.dli_fname == nullptr) {
        char buf[64];
        snprintf(buf, sizeof(buf), "%p <unknown>", address);
        return std::string(buf);
    }
    uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t pc = reinterpret_cast<uintptr_t>(address);
    uintptr_t offset = base > 0 && pc >= base ? pc - base : 0;
    char buf[1024];
    snprintf(buf, sizeof(buf), "%p lib=%s offset=0x%zx sym=%s",
             address,
             info.dli_fname,
             static_cast<size_t>(offset),
             info.dli_sname ? info.dli_sname : "<unknown>");
    return std::string(buf);
}

static bool jiagu_jni_diag_caller(void* caller, uintptr_t* outOffset, const char** outWindow) {
    if (caller == nullptr) return false;
    Dl_info info{};
    if (dladdr(caller, &info) == 0 || info.dli_fname == nullptr || info.dli_fbase == nullptr) {
        return false;
    }
    if (strstr(info.dli_fname, "libjiagu_vip.so") == nullptr) {
        return false;
    }
    uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
    if (pc < base) return false;
    uintptr_t off = pc - base;
    struct Window { uintptr_t center; uintptr_t radius; const char* name; };
    const Window windows[] = {
        {0x10d3f4, 0x500, "interface20"},
        {0x11b9c8, 0xd00, "interface20-sigcheck"},
        {0x11cf5c, 0x900, "interface20-fencrypt"},
        {0x11d310, 0xb00, "interface20-filecheck"},
        {0x11d644, 0x400, "interface20-error"},
        {0x116c94, 0x600, "interface20-fencrypt-input"},
        {0x123438, 0x600, "interface20-qiniu-check"},
        {0x258bac, 0x300, "onload-dispatch"},
        {0x25a5dc, 0x500, "decrypt-func"},
        {0x25a7ac, 0x500, "register-func"},
        {0x25b508, 0x500, "env-check-a"},
        {0x25ba74, 0x500, "env-check-b"},
        {0x25bde4, 0x700, "env-check"},
    };
    for (const auto& window : windows) {
        uintptr_t start = window.center > window.radius ? window.center - window.radius : 0;
        uintptr_t end = window.center + window.radius;
        if (off >= start && off <= end) {
            if (outOffset != nullptr) *outOffset = off;
            if (outWindow != nullptr) *outWindow = window.name;
            return true;
        }
    }
    return false;
}

static std::string jiagu_jni_describe_method(jmethodID method) {
    if (method == nullptr) return "<null-method>";
    std::lock_guard<std::mutex> lock(g_jiagu_jni_diag_mutex);
    auto it = g_jiagu_jni_method_names.find(method);
    if (it != g_jiagu_jni_method_names.end()) return it->second;
    char buf[64];
    snprintf(buf, sizeof(buf), "%p", method);
    return std::string(buf);
}

static std::string jiagu_jni_describe_field(jfieldID field) {
    if (field == nullptr) return "<null-field>";
    std::lock_guard<std::mutex> lock(g_jiagu_jni_diag_mutex);
    auto it = g_jiagu_jni_field_names.find(field);
    if (it != g_jiagu_jni_field_names.end()) return it->second;
    char buf[64];
    snprintf(buf, sizeof(buf), "%p", field);
    return std::string(buf);
}

static void jiagu_jni_remember_method(jmethodID method, const std::string& desc) {
    if (method == nullptr) return;
    std::lock_guard<std::mutex> lock(g_jiagu_jni_diag_mutex);
    g_jiagu_jni_method_names[method] = desc;
}

static void jiagu_jni_remember_field(jfieldID field, const std::string& desc) {
    if (field == nullptr) return;
    std::lock_guard<std::mutex> lock(g_jiagu_jni_diag_mutex);
    g_jiagu_jni_field_names[field] = desc;
}

static jmethodID hooked_GetMethodID(JNIEnv* env, jclass clazz, const char* name, const char* sig) {
    auto orig = (GetMethodIDFn)g_orig_get_method_id;
    if (orig == nullptr) return nullptr;
    if (g_jiagu_jni_diag_in_hook) return orig(env, clazz, name, sig);
    void* caller = __builtin_return_address(0);
    uintptr_t off = 0;
    const char* window = nullptr;
    bool log = jiagu_jni_diag_caller(caller, &off, &window);
    std::string cls = "<not-logged>";
    if (log) {
        g_jiagu_jni_diag_in_hook = true;
        cls = describe_java_class(env, clazz);
        g_jiagu_jni_diag_in_hook = false;
    }
    jmethodID result = orig(env, clazz, name, sig);
    if (log) {
        g_jiagu_jni_diag_in_hook = true;
        bool hasException = env->ExceptionCheck() == JNI_TRUE;
        std::string desc = cls + "." + (name ? name : "<null>") + (sig ? sig : "<null>");
        jiagu_jni_remember_method(result, desc);
        LOGW("JiaguJNI GetMethodID callerOff=0x%lx window=%s target=%s result=%p exception=%d",
             (unsigned long)off, window ? window : "<unknown>", desc.c_str(), result, hasException ? 1 : 0);
        g_jiagu_jni_diag_in_hook = false;
    }
    return result;
}

static jmethodID hooked_GetStaticMethodID(JNIEnv* env, jclass clazz, const char* name, const char* sig) {
    auto orig = (GetStaticMethodIDFn)g_orig_get_static_method_id;
    if (orig == nullptr) return nullptr;
    if (g_jiagu_jni_diag_in_hook) return orig(env, clazz, name, sig);
    void* caller = __builtin_return_address(0);
    uintptr_t off = 0;
    const char* window = nullptr;
    bool log = jiagu_jni_diag_caller(caller, &off, &window);
    std::string cls = "<not-logged>";
    if (log) {
        g_jiagu_jni_diag_in_hook = true;
        cls = describe_java_class(env, clazz);
        g_jiagu_jni_diag_in_hook = false;
    }
    jmethodID result = orig(env, clazz, name, sig);
    if (log) {
        g_jiagu_jni_diag_in_hook = true;
        bool hasException = env->ExceptionCheck() == JNI_TRUE;
        std::string desc = cls + "." + (name ? name : "<null>") + (sig ? sig : "<null>") + " static";
        jiagu_jni_remember_method(result, desc);
        LOGW("JiaguJNI GetStaticMethodID callerOff=0x%lx window=%s target=%s result=%p exception=%d",
             (unsigned long)off, window ? window : "<unknown>", desc.c_str(), result, hasException ? 1 : 0);
        g_jiagu_jni_diag_in_hook = false;
    }
    return result;
}

static jfieldID hooked_GetFieldID(JNIEnv* env, jclass clazz, const char* name, const char* sig) {
    auto orig = (GetFieldIDFn)g_orig_get_field_id;
    if (orig == nullptr) return nullptr;
    if (g_jiagu_jni_diag_in_hook) return orig(env, clazz, name, sig);
    void* caller = __builtin_return_address(0);
    uintptr_t off = 0;
    const char* window = nullptr;
    bool log = jiagu_jni_diag_caller(caller, &off, &window);
    std::string cls = "<not-logged>";
    if (log) {
        g_jiagu_jni_diag_in_hook = true;
        cls = describe_java_class(env, clazz);
        g_jiagu_jni_diag_in_hook = false;
    }
    jfieldID result = orig(env, clazz, name, sig);
    if (log) {
        g_jiagu_jni_diag_in_hook = true;
        bool hasException = env->ExceptionCheck() == JNI_TRUE;
        std::string desc = cls + "." + (name ? name : "<null>") + ":" + (sig ? sig : "<null>");
        jiagu_jni_remember_field(result, desc);
        LOGW("JiaguJNI GetFieldID callerOff=0x%lx window=%s target=%s result=%p exception=%d",
             (unsigned long)off, window ? window : "<unknown>", desc.c_str(), result, hasException ? 1 : 0);
        g_jiagu_jni_diag_in_hook = false;
    }
    return result;
}

static jfieldID hooked_GetStaticFieldID(JNIEnv* env, jclass clazz, const char* name, const char* sig) {
    auto orig = (GetStaticFieldIDFn)g_orig_get_static_field_id;
    if (orig == nullptr) return nullptr;
    if (g_jiagu_jni_diag_in_hook) return orig(env, clazz, name, sig);
    void* caller = __builtin_return_address(0);
    uintptr_t off = 0;
    const char* window = nullptr;
    bool log = jiagu_jni_diag_caller(caller, &off, &window);
    std::string cls = "<not-logged>";
    if (log) {
        g_jiagu_jni_diag_in_hook = true;
        cls = describe_java_class(env, clazz);
        g_jiagu_jni_diag_in_hook = false;
    }
    jfieldID result = orig(env, clazz, name, sig);
    if (log) {
        g_jiagu_jni_diag_in_hook = true;
        bool hasException = env->ExceptionCheck() == JNI_TRUE;
        std::string desc = cls + "." + (name ? name : "<null>") + ":" + (sig ? sig : "<null>") + " static";
        jiagu_jni_remember_field(result, desc);
        LOGW("JiaguJNI GetStaticFieldID callerOff=0x%lx window=%s target=%s result=%p exception=%d",
             (unsigned long)off, window ? window : "<unknown>", desc.c_str(), result, hasException ? 1 : 0);
        g_jiagu_jni_diag_in_hook = false;
    }
    return result;
}

static std::string jiagu_jni_object_result_summary(JNIEnv* env, jobject result, const std::string& methodDesc, bool hasException) {
    if (result == nullptr) return "<null>";
    if (hasException) return "<pending-exception>";
    if (methodDesc.find(")[B") != std::string::npos) {
        auto bytes = reinterpret_cast<jbyteArray>(result);
        jsize len = env->GetArrayLength(bytes);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return "<byte-array-read-failed>";
        }
        constexpr jsize kPreview = 16;
        jsize previewLen = len < kPreview ? len : kPreview;
        jbyte preview[kPreview] = {};
        if (previewLen > 0) {
            env->GetByteArrayRegion(bytes, 0, previewLen, preview);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return "<byte-array-read-failed>";
            }
        }
        uint64_t fnv = 1469598103934665603ull;
        constexpr jsize kHashChunk = 256;
        jbyte chunk[kHashChunk] = {};
        for (jsize pos = 0; pos < len; pos += kHashChunk) {
            jsize chunkLen = len - pos;
            if (chunkLen > kHashChunk) chunkLen = kHashChunk;
            env->GetByteArrayRegion(bytes, pos, chunkLen, chunk);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return "<byte-array-read-failed>";
            }
            for (jsize i = 0; i < chunkLen; ++i) {
                fnv ^= static_cast<unsigned char>(chunk[i]);
                fnv *= 1099511628211ull;
            }
        }
        static const char* hex = "0123456789abcdef";
        std::string firstHex;
        firstHex.reserve(static_cast<size_t>(previewLen) * 2);
        for (jsize i = 0; i < previewLen; ++i) {
            unsigned char b = static_cast<unsigned char>(preview[i]);
            firstHex.push_back(hex[b >> 4]);
            firstHex.push_back(hex[b & 0x0f]);
        }
        char buf[256];
        snprintf(buf, sizeof(buf), "[B len=%d fnv64=0x%016llx first%d=%s",
                 static_cast<int>(len),
                 static_cast<unsigned long long>(fnv),
                 static_cast<int>(previewLen),
                 firstHex.c_str());
        return std::string(buf);
    }
    if (methodDesc.find(")Ljava/lang/String;") == std::string::npos) {
        char buf[64];
        snprintf(buf, sizeof(buf), "%p", result);
        return std::string(buf);
    }
    const char* chars = env->GetStringUTFChars((jstring)result, nullptr);
    if (chars == nullptr) return "<string-read-failed>";
    std::string text(chars);
    env->ReleaseStringUTFChars((jstring)result, chars);
    if (text.size() > 160) {
        text.resize(160);
        text += "...";
    }
    return "\"" + text + "\"";
}

static void jiagu_jni_log_object_call(JNIEnv* env, const char* api, void* caller, jmethodID method, jobject result) {
    uintptr_t off = 0;
    const char* window = nullptr;
    if (!jiagu_jni_diag_caller(caller, &off, &window)) return;
    g_jiagu_jni_diag_in_hook = true;
    bool hasException = env->ExceptionCheck() == JNI_TRUE;
    std::string methodDesc = jiagu_jni_describe_method(method);
    std::string resultDesc = jiagu_jni_object_result_summary(env, result, methodDesc, hasException);
    LOGW("JiaguJNI %s callerOff=0x%lx window=%s method=%s result=%s exception=%d",
         api, (unsigned long)off, window ? window : "<unknown>",
         methodDesc.c_str(), resultDesc.c_str(), hasException ? 1 : 0);
    g_jiagu_jni_diag_in_hook = false;
}

static jobject maybe_spoof_jiagu_current_package(JNIEnv* env, void* caller, jmethodID method, jobject result) {
    if (result == nullptr) return result;
    uintptr_t off = 0;
    const char* window = nullptr;
    if (!jiagu_jni_diag_caller(caller, &off, &window)) return result;

    std::string methodDesc = jiagu_jni_describe_method(method);
    if (methodDesc.find("android.app.ActivityThread.currentPackageName()Ljava/lang/String;") == std::string::npos) {
        return result;
    }

    std::string originalPackage;
    {
        std::lock_guard<std::mutex> lock(g_jiagu_pkg_spoof_mutex);
        originalPackage = g_jiagu_original_package;
    }
    if (originalPackage.empty()) return result;

    jstring spoofed = env->NewStringUTF(originalPackage.c_str());
    if (spoofed == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("JiaguJNI currentPackageName spoof failed callerOff=0x%lx original=%s",
             (unsigned long)off, originalPackage.c_str());
        return result;
    }

    LOGW("JiaguJNI currentPackageName spoof callerOff=0x%lx window=%s originalResult=%p spoof=\"%s\"",
         (unsigned long)off, window ? window : "<unknown>", result, originalPackage.c_str());
    return spoofed;
}

static void jiagu_jni_log_boolean_call(JNIEnv* env, const char* api, void* caller, jmethodID method, jboolean result) {
    uintptr_t off = 0;
    const char* window = nullptr;
    if (!jiagu_jni_diag_caller(caller, &off, &window)) return;
    g_jiagu_jni_diag_in_hook = true;
    bool hasException = env->ExceptionCheck() == JNI_TRUE;
    LOGW("JiaguJNI %s callerOff=0x%lx window=%s method=%s result=%d exception=%d",
         api, (unsigned long)off, window ? window : "<unknown>",
         jiagu_jni_describe_method(method).c_str(), result, hasException ? 1 : 0);
    g_jiagu_jni_diag_in_hook = false;
}

static void jiagu_jni_log_int_call(JNIEnv* env, const char* api, void* caller, jmethodID method, jint result) {
    uintptr_t off = 0;
    const char* window = nullptr;
    if (!jiagu_jni_diag_caller(caller, &off, &window)) return;
    g_jiagu_jni_diag_in_hook = true;
    bool hasException = env->ExceptionCheck() == JNI_TRUE;
    LOGW("JiaguJNI %s callerOff=0x%lx window=%s method=%s result=%d exception=%d",
         api, (unsigned long)off, window ? window : "<unknown>",
         jiagu_jni_describe_method(method).c_str(), result, hasException ? 1 : 0);
    g_jiagu_jni_diag_in_hook = false;
}

static void jiagu_jni_log_void_call(JNIEnv* env, const char* api, void* caller, jmethodID method) {
    uintptr_t off = 0;
    const char* window = nullptr;
    if (!jiagu_jni_diag_caller(caller, &off, &window)) return;
    g_jiagu_jni_diag_in_hook = true;
    bool hasException = env->ExceptionCheck() == JNI_TRUE;
    LOGW("JiaguJNI %s callerOff=0x%lx window=%s method=%s exception=%d",
         api, (unsigned long)off, window ? window : "<unknown>",
         jiagu_jni_describe_method(method).c_str(), hasException ? 1 : 0);
    g_jiagu_jni_diag_in_hook = false;
}

static jobject hooked_CallObjectMethod(JNIEnv* env, jobject obj, jmethodID method, ...) {
    auto origV = (CallObjectMethodVFn)g_orig_call_object_method_v;
    if (origV == nullptr) return nullptr;
    va_list args;
    va_start(args, method);
    jobject result = origV(env, obj, method, args);
    va_end(args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            g_jiagu_jni_diag_in_hook = true;
            bool hasException = env->ExceptionCheck() == JNI_TRUE;
            std::string methodDesc = jiagu_jni_describe_method(method);
            std::string resultDesc = jiagu_jni_object_result_summary(env, result, methodDesc, hasException);
            LOGW("JiaguJNI CallObjectMethod callerOff=0x%lx window=%s method=%s result=%s exception=%d",
                 (unsigned long)off, window ? window : "<unknown>",
                 methodDesc.c_str(), resultDesc.c_str(), hasException ? 1 : 0);
            g_jiagu_jni_diag_in_hook = false;
        }
    }
    return result;
}

static jobject hooked_CallObjectMethodV(JNIEnv* env, jobject obj, jmethodID method, va_list args) {
    auto origV = (CallObjectMethodVFn)g_orig_call_object_method_v;
    if (origV == nullptr) return nullptr;
    jobject result = origV(env, obj, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_object_call(env, "CallObjectMethodV", __builtin_return_address(0), method, result);
    }
    return result;
}

static jobject hooked_CallObjectMethodA(JNIEnv* env, jobject obj, jmethodID method, const jvalue* args) {
    auto origA = (CallObjectMethodAFn)g_orig_call_object_method_a;
    if (origA == nullptr) return nullptr;
    jobject result = origA(env, obj, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_object_call(env, "CallObjectMethodA", __builtin_return_address(0), method, result);
    }
    return result;
}

static jboolean hooked_CallBooleanMethod(JNIEnv* env, jobject obj, jmethodID method, ...) {
    auto origV = (CallBooleanMethodVFn)g_orig_call_boolean_method_v;
    if (origV == nullptr) return JNI_FALSE;
    va_list args;
    va_start(args, method);
    jboolean result = origV(env, obj, method, args);
    va_end(args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            g_jiagu_jni_diag_in_hook = true;
            bool hasException = env->ExceptionCheck() == JNI_TRUE;
            LOGW("JiaguJNI CallBooleanMethod callerOff=0x%lx window=%s method=%s result=%d exception=%d",
                 (unsigned long)off, window ? window : "<unknown>",
                 jiagu_jni_describe_method(method).c_str(), result, hasException ? 1 : 0);
            g_jiagu_jni_diag_in_hook = false;
        }
    }
    return result;
}

static jboolean hooked_CallBooleanMethodV(JNIEnv* env, jobject obj, jmethodID method, va_list args) {
    auto origV = (CallBooleanMethodVFn)g_orig_call_boolean_method_v;
    if (origV == nullptr) return JNI_FALSE;
    jboolean result = origV(env, obj, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_boolean_call(env, "CallBooleanMethodV", __builtin_return_address(0), method, result);
    }
    return result;
}

static jboolean hooked_CallBooleanMethodA(JNIEnv* env, jobject obj, jmethodID method, const jvalue* args) {
    auto origA = (CallBooleanMethodAFn)g_orig_call_boolean_method_a;
    if (origA == nullptr) return JNI_FALSE;
    jboolean result = origA(env, obj, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_boolean_call(env, "CallBooleanMethodA", __builtin_return_address(0), method, result);
    }
    return result;
}

static jint hooked_CallIntMethod(JNIEnv* env, jobject obj, jmethodID method, ...) {
    auto origV = (CallIntMethodVFn)g_orig_call_int_method_v;
    if (origV == nullptr) return 0;
    va_list args;
    va_start(args, method);
    jint result = origV(env, obj, method, args);
    va_end(args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            g_jiagu_jni_diag_in_hook = true;
            bool hasException = env->ExceptionCheck() == JNI_TRUE;
            LOGW("JiaguJNI CallIntMethod callerOff=0x%lx window=%s method=%s result=%d exception=%d",
                 (unsigned long)off, window ? window : "<unknown>",
                 jiagu_jni_describe_method(method).c_str(), result, hasException ? 1 : 0);
            g_jiagu_jni_diag_in_hook = false;
        }
    }
    return result;
}

static jint hooked_CallIntMethodV(JNIEnv* env, jobject obj, jmethodID method, va_list args) {
    auto origV = (CallIntMethodVFn)g_orig_call_int_method_v;
    if (origV == nullptr) return 0;
    jint result = origV(env, obj, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_int_call(env, "CallIntMethodV", __builtin_return_address(0), method, result);
    }
    return result;
}

static jint hooked_CallIntMethodA(JNIEnv* env, jobject obj, jmethodID method, const jvalue* args) {
    auto origA = (CallIntMethodAFn)g_orig_call_int_method_a;
    if (origA == nullptr) return 0;
    jint result = origA(env, obj, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_int_call(env, "CallIntMethodA", __builtin_return_address(0), method, result);
    }
    return result;
}

static void hooked_CallVoidMethod(JNIEnv* env, jobject obj, jmethodID method, ...) {
    auto origV = (CallVoidMethodVFn)g_orig_call_void_method_v;
    if (origV == nullptr) return;
    va_list args;
    va_start(args, method);
    origV(env, obj, method, args);
    va_end(args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            g_jiagu_jni_diag_in_hook = true;
            bool hasException = env->ExceptionCheck() == JNI_TRUE;
            LOGW("JiaguJNI CallVoidMethod callerOff=0x%lx window=%s method=%s exception=%d",
                 (unsigned long)off, window ? window : "<unknown>",
                 jiagu_jni_describe_method(method).c_str(), hasException ? 1 : 0);
            g_jiagu_jni_diag_in_hook = false;
        }
    }
}

static void hooked_CallVoidMethodV(JNIEnv* env, jobject obj, jmethodID method, va_list args) {
    auto origV = (CallVoidMethodVFn)g_orig_call_void_method_v;
    if (origV == nullptr) return;
    origV(env, obj, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_void_call(env, "CallVoidMethodV", __builtin_return_address(0), method);
    }
}

static void hooked_CallVoidMethodA(JNIEnv* env, jobject obj, jmethodID method, const jvalue* args) {
    auto origA = (CallVoidMethodAFn)g_orig_call_void_method_a;
    if (origA == nullptr) return;
    origA(env, obj, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_void_call(env, "CallVoidMethodA", __builtin_return_address(0), method);
    }
}

static jobject hooked_CallStaticObjectMethod(JNIEnv* env, jclass clazz, jmethodID method, ...) {
    auto origV = (CallStaticObjectMethodVFn)g_orig_call_static_object_method_v;
    if (origV == nullptr) return nullptr;
    va_list args;
    va_start(args, method);
    jobject result = origV(env, clazz, method, args);
    va_end(args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        result = maybe_spoof_jiagu_current_package(env, caller, method, result);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            g_jiagu_jni_diag_in_hook = true;
            bool hasException = env->ExceptionCheck() == JNI_TRUE;
            std::string methodDesc = jiagu_jni_describe_method(method);
            std::string resultDesc = jiagu_jni_object_result_summary(env, result, methodDesc, hasException);
            LOGW("JiaguJNI CallStaticObjectMethod callerOff=0x%lx window=%s method=%s result=%s exception=%d",
                 (unsigned long)off, window ? window : "<unknown>",
                 methodDesc.c_str(), resultDesc.c_str(), hasException ? 1 : 0);
            g_jiagu_jni_diag_in_hook = false;
        }
    }
    return result;
}

static jobject hooked_CallStaticObjectMethodV(JNIEnv* env, jclass clazz, jmethodID method, va_list args) {
    auto origV = (CallStaticObjectMethodVFn)g_orig_call_static_object_method_v;
    if (origV == nullptr) return nullptr;
    jobject result = origV(env, clazz, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        result = maybe_spoof_jiagu_current_package(env, caller, method, result);
        jiagu_jni_log_object_call(env, "CallStaticObjectMethodV", caller, method, result);
    }
    return result;
}

static jobject hooked_CallStaticObjectMethodA(JNIEnv* env, jclass clazz, jmethodID method, const jvalue* args) {
    auto origA = (CallStaticObjectMethodAFn)g_orig_call_static_object_method_a;
    if (origA == nullptr) return nullptr;
    jobject result = origA(env, clazz, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        result = maybe_spoof_jiagu_current_package(env, caller, method, result);
        jiagu_jni_log_object_call(env, "CallStaticObjectMethodA", caller, method, result);
    }
    return result;
}

static jboolean hooked_CallStaticBooleanMethod(JNIEnv* env, jclass clazz, jmethodID method, ...) {
    auto origV = (CallStaticBooleanMethodVFn)g_orig_call_static_boolean_method_v;
    if (origV == nullptr) return JNI_FALSE;
    va_list args;
    va_start(args, method);
    jboolean result = origV(env, clazz, method, args);
    va_end(args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            g_jiagu_jni_diag_in_hook = true;
            bool hasException = env->ExceptionCheck() == JNI_TRUE;
            LOGW("JiaguJNI CallStaticBooleanMethod callerOff=0x%lx window=%s method=%s result=%d exception=%d",
                 (unsigned long)off, window ? window : "<unknown>",
                 jiagu_jni_describe_method(method).c_str(), result, hasException ? 1 : 0);
            g_jiagu_jni_diag_in_hook = false;
        }
    }
    return result;
}

static jboolean hooked_CallStaticBooleanMethodV(JNIEnv* env, jclass clazz, jmethodID method, va_list args) {
    auto origV = (CallStaticBooleanMethodVFn)g_orig_call_static_boolean_method_v;
    if (origV == nullptr) return JNI_FALSE;
    jboolean result = origV(env, clazz, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_boolean_call(env, "CallStaticBooleanMethodV", __builtin_return_address(0), method, result);
    }
    return result;
}

static jboolean hooked_CallStaticBooleanMethodA(JNIEnv* env, jclass clazz, jmethodID method, const jvalue* args) {
    auto origA = (CallStaticBooleanMethodAFn)g_orig_call_static_boolean_method_a;
    if (origA == nullptr) return JNI_FALSE;
    jboolean result = origA(env, clazz, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_boolean_call(env, "CallStaticBooleanMethodA", __builtin_return_address(0), method, result);
    }
    return result;
}

static jint hooked_CallStaticIntMethod(JNIEnv* env, jclass clazz, jmethodID method, ...) {
    auto origV = (CallStaticIntMethodVFn)g_orig_call_static_int_method_v;
    if (origV == nullptr) return 0;
    va_list args;
    va_start(args, method);
    jint result = origV(env, clazz, method, args);
    va_end(args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            g_jiagu_jni_diag_in_hook = true;
            bool hasException = env->ExceptionCheck() == JNI_TRUE;
            LOGW("JiaguJNI CallStaticIntMethod callerOff=0x%lx window=%s method=%s result=%d exception=%d",
                 (unsigned long)off, window ? window : "<unknown>",
                 jiagu_jni_describe_method(method).c_str(), result, hasException ? 1 : 0);
            g_jiagu_jni_diag_in_hook = false;
        }
    }
    return result;
}

static jint hooked_CallStaticIntMethodV(JNIEnv* env, jclass clazz, jmethodID method, va_list args) {
    auto origV = (CallStaticIntMethodVFn)g_orig_call_static_int_method_v;
    if (origV == nullptr) return 0;
    jint result = origV(env, clazz, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_int_call(env, "CallStaticIntMethodV", __builtin_return_address(0), method, result);
    }
    return result;
}

static jint hooked_CallStaticIntMethodA(JNIEnv* env, jclass clazz, jmethodID method, const jvalue* args) {
    auto origA = (CallStaticIntMethodAFn)g_orig_call_static_int_method_a;
    if (origA == nullptr) return 0;
    jint result = origA(env, clazz, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_int_call(env, "CallStaticIntMethodA", __builtin_return_address(0), method, result);
    }
    return result;
}

static void hooked_CallStaticVoidMethod(JNIEnv* env, jclass clazz, jmethodID method, ...) {
    auto origV = (CallStaticVoidMethodVFn)g_orig_call_static_void_method_v;
    if (origV == nullptr) return;
    va_list args;
    va_start(args, method);
    origV(env, clazz, method, args);
    va_end(args);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            g_jiagu_jni_diag_in_hook = true;
            bool hasException = env->ExceptionCheck() == JNI_TRUE;
            LOGW("JiaguJNI CallStaticVoidMethod callerOff=0x%lx window=%s method=%s exception=%d",
                 (unsigned long)off, window ? window : "<unknown>",
                 jiagu_jni_describe_method(method).c_str(), hasException ? 1 : 0);
            g_jiagu_jni_diag_in_hook = false;
        }
    }
}

static void hooked_CallStaticVoidMethodV(JNIEnv* env, jclass clazz, jmethodID method, va_list args) {
    auto origV = (CallStaticVoidMethodVFn)g_orig_call_static_void_method_v;
    if (origV == nullptr) return;
    origV(env, clazz, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_void_call(env, "CallStaticVoidMethodV", __builtin_return_address(0), method);
    }
}

static void hooked_CallStaticVoidMethodA(JNIEnv* env, jclass clazz, jmethodID method, const jvalue* args) {
    auto origA = (CallStaticVoidMethodAFn)g_orig_call_static_void_method_a;
    if (origA == nullptr) return;
    origA(env, clazz, method, args);
    if (!g_jiagu_jni_diag_in_hook) {
        jiagu_jni_log_void_call(env, "CallStaticVoidMethodA", __builtin_return_address(0), method);
    }
}

static jsize hooked_GetStringUTFLength(JNIEnv* env, jstring str) {
    auto orig = (GetStringUTFLengthFn)g_orig_get_string_utf_length;
    if (orig == nullptr) return 0;
    jsize result = orig(env, str);
    if (!g_jiagu_jni_diag_in_hook) {
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(__builtin_return_address(0), &off, &window)) {
            LOGW("JiaguJNI GetStringUTFLength callerOff=0x%lx window=%s str=%p result=%d",
                 (unsigned long)off, window ? window : "<unknown>", str, static_cast<int>(result));
        }
    }
    return result;
}

static const char* hooked_GetStringUTFChars(JNIEnv* env, jstring str, jboolean* isCopy) {
    auto orig = (GetStringUTFCharsFn)g_orig_get_string_utf_chars;
    if (orig == nullptr) return nullptr;
    const char* result = orig(env, str, isCopy);
    if (!g_jiagu_jni_diag_in_hook) {
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(__builtin_return_address(0), &off, &window)) {
            char preview[97] = {};
            if (result != nullptr) {
                size_t len = strnlen(result, sizeof(preview) - 1);
                memcpy(preview, result, len);
                preview[len] = '\0';
            }
            LOGW("JiaguJNI GetStringUTFChars callerOff=0x%lx window=%s str=%p result=%p isCopy=%d preview=\"%s\"",
                 (unsigned long)off, window ? window : "<unknown>", str, result,
                 isCopy != nullptr && *isCopy == JNI_TRUE ? 1 : 0,
                 result != nullptr ? preview : "<null>");
        }
    }
    return result;
}

static void hooked_ReleaseStringUTFChars(JNIEnv* env, jstring str, const char* chars) {
    auto orig = (ReleaseStringUTFCharsFn)g_orig_release_string_utf_chars;
    if (orig == nullptr) return;
    if (!g_jiagu_jni_diag_in_hook) {
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(__builtin_return_address(0), &off, &window)) {
            LOGW("JiaguJNI ReleaseStringUTFChars callerOff=0x%lx window=%s str=%p chars=%p",
                 (unsigned long)off, window ? window : "<unknown>", str, chars);
        }
    }
    orig(env, str, chars);
}

static jsize hooked_GetArrayLength(JNIEnv* env, jarray array) {
    auto orig = (GetArrayLengthFn)g_orig_get_array_length;
    if (orig == nullptr) return 0;
    jsize result = orig(env, array);
    if (!g_jiagu_jni_diag_in_hook) {
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(__builtin_return_address(0), &off, &window)) {
            LOGW("JiaguJNI GetArrayLength callerOff=0x%lx window=%s array=%p result=%d",
                 (unsigned long)off, window ? window : "<unknown>", array, static_cast<int>(result));
        }
    }
    return result;
}

static jbyte* hooked_GetByteArrayElements(JNIEnv* env, jbyteArray array, jboolean* isCopy) {
    auto orig = (GetByteArrayElementsFn)g_orig_get_byte_array_elements;
    if (orig == nullptr) return nullptr;
    jbyte* result = orig(env, array, isCopy);
    if (!g_jiagu_jni_diag_in_hook) {
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(__builtin_return_address(0), &off, &window)) {
            LOGW("JiaguJNI GetByteArrayElements callerOff=0x%lx window=%s array=%p result=%p isCopy=%d",
                 (unsigned long)off, window ? window : "<unknown>", array, result,
                 isCopy != nullptr && *isCopy == JNI_TRUE ? 1 : 0);
        }
    }
    return result;
}

static void hooked_ReleaseByteArrayElements(JNIEnv* env, jbyteArray array, jbyte* elems, jint mode) {
    auto orig = (ReleaseByteArrayElementsFn)g_orig_release_byte_array_elements;
    if (orig == nullptr) return;
    if (!g_jiagu_jni_diag_in_hook) {
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(__builtin_return_address(0), &off, &window)) {
            LOGW("JiaguJNI ReleaseByteArrayElements callerOff=0x%lx window=%s array=%p elems=%p mode=%d",
                 (unsigned long)off, window ? window : "<unknown>", array, elems, static_cast<int>(mode));
        }
    }
    orig(env, array, elems, mode);
}

static std::string jiagu_jni_describe_throwable(JNIEnv* env, jthrowable throwable) {
    if (throwable == nullptr) return "<null>";

    std::string className = "<throwable-class-unknown>";
    jclass throwableObjectClass = env->GetObjectClass(throwable);
    if (throwableObjectClass != nullptr) {
        className = describe_java_class(env, throwableObjectClass);
        env->DeleteLocalRef(throwableObjectClass);
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    std::string message;
    jclass throwableClass = env->FindClass("java/lang/Throwable");
    if (throwableClass != nullptr) {
        jmethodID getMessage = env->GetMethodID(throwableClass, "getMessage", "()Ljava/lang/String;");
        if (getMessage != nullptr) {
            auto msgObj = (jstring)env->CallObjectMethod(throwable, getMessage);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            } else if (msgObj != nullptr) {
                const char* chars = env->GetStringUTFChars(msgObj, nullptr);
                if (chars != nullptr) {
                    message = chars;
                    env->ReleaseStringUTFChars(msgObj, chars);
                }
                env->DeleteLocalRef(msgObj);
            }
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(throwableClass);
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    if (message.size() > 180) {
        message.resize(180);
        message += "...";
    }
    return message.empty() ? className : (className + ": " + message);
}

static jthrowable hooked_ExceptionOccurred(JNIEnv* env) {
    auto orig = (ExceptionOccurredFn)g_orig_exception_occurred;
    if (orig == nullptr) return nullptr;
    jthrowable result = orig(env);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            g_jiagu_jni_diag_in_hook = true;
            std::string desc = "<none>";
            if (result != nullptr) {
                auto clearOrig = (ExceptionClearFn)g_orig_exception_clear;
                if (clearOrig != nullptr) clearOrig(env);
                desc = jiagu_jni_describe_throwable(env, result);
                env->Throw(result);
            }
            LOGW("JiaguJNI ExceptionOccurred callerOff=0x%lx window=%s result=%p throwable=%s",
                 (unsigned long)off, window ? window : "<unknown>", result, desc.c_str());
            g_jiagu_jni_diag_in_hook = false;
        }
    }
    return result;
}

static void hooked_ExceptionClear(JNIEnv* env) {
    auto orig = (ExceptionClearFn)g_orig_exception_clear;
    if (orig == nullptr) return;
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            LOGW("JiaguJNI ExceptionClear callerOff=0x%lx window=%s",
                 (unsigned long)off, window ? window : "<unknown>");
        }
    }
    orig(env);
}

static jboolean hooked_ExceptionCheck(JNIEnv* env) {
    auto orig = (ExceptionCheckFn)g_orig_exception_check;
    if (orig == nullptr) return JNI_FALSE;
    jboolean result = orig(env);
    if (!g_jiagu_jni_diag_in_hook) {
        void* caller = __builtin_return_address(0);
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(caller, &off, &window)) {
            LOGW("JiaguJNI ExceptionCheck callerOff=0x%lx window=%s result=%d",
                 (unsigned long)off, window ? window : "<unknown>", result ? 1 : 0);
        }
    }
    return result;
}

static void update_stubapp_register_state(
    const std::string& className,
    const std::string& callerDesc,
    bool callerIsJiagu,
    bool allMultiAppMethods,
    bool hasInterface11,
    bool hasInterface20,
    jint nMethods,
    jint result) {
    std::lock_guard<std::mutex> lock(g_stubapp_register_mutex);
    g_stubapp_register_calls++;
    g_stubapp_last_class = className;
    g_stubapp_last_caller = callerDesc;
    g_stubapp_last_count = nMethods;
    g_stubapp_last_result = result;
    g_stubapp_last_caller_is_jiagu = callerIsJiagu;
    g_stubapp_last_all_multiapp = allMultiAppMethods;
    g_stubapp_last_has_interface11 = hasInterface11;
    g_stubapp_last_has_interface20 = hasInterface20;
    if (callerIsJiagu) {
        g_stubapp_jiagu_register_calls++;
        if (hasInterface11) g_stubapp_saw_jiagu_interface11 = true;
        if (hasInterface20) g_stubapp_saw_jiagu_interface20 = true;
        if (result == JNI_OK && nMethods >= 10 && hasInterface11 && hasInterface20) {
            g_stubapp_jiagu_complete_calls++;
            g_stubapp_original_jiagu_complete = true;
        }
    }
    if (allMultiAppMethods) {
        g_stubapp_multiapp_register_calls++;
    }
}

static void capture_stubapp_native(const char* name, const char* sig, void* fnPtr) {
    if (name == nullptr || sig == nullptr || fnPtr == nullptr) return;
    if (is_multiapp_native_fn(fnPtr)) {
        return;
    }

    if (strcmp(name, "interface5") == 0 && strcmp(sig, "(Landroid/app/Application;)V") == 0) {
        g_orig_stub_interface5 = (StubInterfaceAppFn)fnPtr;
        LOGW("RegisterNatives StubApp: captured original interface5=%s", describe_native_address(fnPtr).c_str());
    } else if (strcmp(name, "interface11") == 0 && strcmp(sig, "(I)V") == 0) {
        g_orig_stub_interface11 = (StubInterface11Fn)fnPtr;
        LOGW("RegisterNatives StubApp: captured original interface11=%s", describe_native_address(fnPtr).c_str());
    } else if (strcmp(name, "interface20") == 0 && strcmp(sig, "()Z") == 0) {
        g_orig_stub_interface20 = (StubInterface20Fn)fnPtr;
        LOGW("RegisterNatives StubApp: captured original interface20=%s", describe_native_address(fnPtr).c_str());
        if (g_register_natives_business_wrappers_enabled.load(std::memory_order_relaxed)) {
            install_jiagu_token_insert_hook_from_stubapp("capture-interface20");
            install_jiagu_fill_loop_hooks_from_stubapp("capture-interface20");
        } else {
            LOGI("RegisterNatives StubApp: observe-only capture; jiagu token/fill hooks not installed");
        }
    } else if (strcmp(name, "interface21") == 0 && strcmp(sig, "(Landroid/app/Application;)V") == 0) {
        g_orig_stub_interface21 = (StubInterfaceAppFn)fnPtr;
        LOGW("RegisterNatives StubApp: captured original interface21=%s", describe_native_address(fnPtr).c_str());
    }
}

static jint hooked_RegisterNatives(JNIEnv* env, jclass clazz, const JNINativeMethod* methods, jint nMethods) {
    std::string className = describe_java_class(env, clazz);
    void* caller = __builtin_return_address(0);
    std::string callerDesc = describe_native_address(caller);
    LOGI("RegisterNatives: class=%s count=%d caller=%s", className.c_str(), nMethods, callerDesc.c_str());

    std::vector<JNINativeMethod> patchedMethods;
    const JNINativeMethod* methodsToRegister = methods;
    if ((className == "com.stub.StubApp" || className == "com.qihoo.util.StubApp") &&
        methods != nullptr && nMethods > 0) {
        bool callerIsJiagu = is_address_from_library(caller, "libjiagu_vip.so");
        bool allMultiAppMethods = true;
        bool hasInterface11 = false;
        bool hasInterface20 = false;
        for (jint i = 0; i < nMethods; i++) {
            const char* name = methods[i].name ? methods[i].name : "";
            const char* sig = methods[i].signature ? methods[i].signature : "";
            if (strcmp(name, "interface11") == 0 && strcmp(sig, "(I)V") == 0) {
                hasInterface11 = true;
            } else if (strcmp(name, "interface20") == 0 && strcmp(sig, "()Z") == 0) {
                hasInterface20 = true;
            }
            if (!is_multiapp_native_fn(methods[i].fnPtr)) {
                allMultiAppMethods = false;
            }
            capture_stubapp_native(methods[i].name, methods[i].signature, methods[i].fnPtr);
        }
        LOGW("RegisterNatives StubApp DIAG: count=%d callerIsJiagu=%d allMultiApp=%d hasInterface11=%d hasInterface20=%d",
             nMethods,
             callerIsJiagu ? 1 : 0,
             allMultiAppMethods ? 1 : 0,
             hasInterface11 ? 1 : 0,
             hasInterface20 ? 1 : 0);
    }
    bool businessWrappersEnabled = g_register_natives_business_wrappers_enabled.load(std::memory_order_relaxed);
    if (className == "com.yuewen.ywlogin.login.YWLoginManager" &&
        methods != nullptr && nMethods > 0) {
        if (businessWrappersEnabled) {
            patchedMethods.assign(methods, methods + nMethods);
            for (jint i = 0; i < nMethods; i++) {
                const char* name = patchedMethods[i].name ? patchedMethods[i].name : "";
                const char* sig = patchedMethods[i].signature ? patchedMethods[i].signature : "";
                void* fnPtr = patchedMethods[i].fnPtr;
                if (strcmp(name, "pwdLogin") == 0 &&
                    strcmp(sig, "(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;Lcom/yuewen/ywlogin/login/YWCallBack;)V") == 0) {
                    if (fnPtr != nullptr && !is_multiapp_native_fn(fnPtr)) {
                        g_orig_ywlogin_pwdLogin = (YwPwdLoginFn)fnPtr;
                        LOGW("RegisterNatives YWLoginManager: captured pwdLogin original=%s", describe_native_address(fnPtr).c_str());
                    }
                    patchedMethods[i].fnPtr = (void*)wrapped_ywlogin_pwdLogin;
                    LOGW("RegisterNatives YWLoginManager: wrapped pwdLogin original=%p", (void*)g_orig_ywlogin_pwdLogin);
                } else if (strcmp(name, "sendPhoneCode") == 0 &&
                    strcmp(sig, "(Landroid/content/Context;Ljava/lang/String;IILcom/yuewen/ywlogin/login/YWCallBack;)V") == 0) {
                    if (fnPtr != nullptr && !is_multiapp_native_fn(fnPtr)) {
                        g_orig_ywlogin_sendPhoneCode = (YwSendPhoneCodeFn)fnPtr;
                        LOGW("RegisterNatives YWLoginManager: captured sendPhoneCode original=%s", describe_native_address(fnPtr).c_str());
                    }
                    patchedMethods[i].fnPtr = (void*)wrapped_ywlogin_sendPhoneCode;
                    LOGW("RegisterNatives YWLoginManager: wrapped sendPhoneCode original=%p", (void*)g_orig_ywlogin_sendPhoneCode);
                } else if (strcmp(name, "qrCodeV2") == 0 &&
                    strcmp(sig, "(Lcom/yuewen/ywlogin/callbacks/DefaultYWCallback;)V") == 0) {
                    if (fnPtr != nullptr && !is_multiapp_native_fn(fnPtr)) {
                        g_orig_ywlogin_qrCodeV2 = (YwQrCodeV2Fn)fnPtr;
                        LOGW("RegisterNatives YWLoginManager: captured qrCodeV2 original=%s", describe_native_address(fnPtr).c_str());
                    }
                    patchedMethods[i].fnPtr = (void*)wrapped_ywlogin_qrCodeV2;
                    LOGW("RegisterNatives YWLoginManager: wrapped qrCodeV2 original=%p", (void*)g_orig_ywlogin_qrCodeV2);
                }
            }
            methodsToRegister = patchedMethods.data();
        } else {
            LOGI("RegisterNatives YWLoginManager: observe-only; fnPtr left unchanged");
        }
    }
    if (className == "com.yuewen.fock.Fock" && methods != nullptr && nMethods > 0) {
        if (!businessWrappersEnabled) {
            LOGI("RegisterNatives Fock: observe-only; fnPtr left unchanged");
        } else {
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
    if ((className == "com.stub.StubApp" || className == "com.qihoo.util.StubApp") &&
        methods != nullptr && nMethods > 0) {
        bool callerIsJiagu = is_address_from_library(caller, "libjiagu_vip.so");
        bool allMultiAppMethods = true;
        bool hasInterface11 = false;
        bool hasInterface20 = false;
        for (jint i = 0; i < nMethods; i++) {
            const char* name = methods[i].name ? methods[i].name : "";
            const char* sig = methods[i].signature ? methods[i].signature : "";
            if (strcmp(name, "interface11") == 0 && strcmp(sig, "(I)V") == 0) {
                hasInterface11 = true;
            } else if (strcmp(name, "interface20") == 0 && strcmp(sig, "()Z") == 0) {
                hasInterface20 = true;
            }
            if (!is_multiapp_native_fn(methods[i].fnPtr)) {
                allMultiAppMethods = false;
            }
        }
        update_stubapp_register_state(
            className,
            callerDesc,
            callerIsJiagu,
            allMultiAppMethods,
            hasInterface11,
            hasInterface20,
            nMethods,
            result);
        int registerCalls = 0;
        int jiaguCalls = 0;
        int jiaguCompleteCalls = 0;
        int multiappCalls = 0;
        {
            std::lock_guard<std::mutex> lock(g_stubapp_register_mutex);
            registerCalls = g_stubapp_register_calls;
            jiaguCalls = g_stubapp_jiagu_register_calls;
            jiaguCompleteCalls = g_stubapp_jiagu_complete_calls;
            multiappCalls = g_stubapp_multiapp_register_calls;
        }
        LOGW("RegisterNatives StubApp DIAG result=%d calls=%d jiaguCalls=%d jiaguComplete=%d multiappCalls=%d",
             result,
             registerCalls,
             jiaguCalls,
             jiaguCompleteCalls,
             multiappCalls);
    }
    if (result == JNI_OK && className == "com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask") {
        int count = g_online_chapter_register_count.fetch_add(1, std::memory_order_relaxed) + 1;
        LOGW("RegisterNatives DIAG: OnlineChapterDownloadTask registered count=%d methods=%d", count, nMethods);
    }
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

static bool replace_jni_table_entry(void** jniFunctions, int index, void* replacement, void** original, const char* name) {
    if (jniFunctions == nullptr || replacement == nullptr || original == nullptr) return false;
    if (jniFunctions[index] == replacement) return true;
    if (*original == nullptr) {
        *original = jniFunctions[index];
    }
    if (*original == nullptr) {
        LOGE("installJiaguJniDiagHooks: original %s pointer is null", name);
        return false;
    }

    uintptr_t page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = (uintptr_t)&jniFunctions[index] & ~(page_size - 1);
    if (mprotect((void*)page_start, page_size, PROT_READ | PROT_WRITE) != 0) {
        LOGE("installJiaguJniDiagHooks: mprotect RW failed for %s errno=%d", name, errno);
        return false;
    }
    jniFunctions[index] = replacement;
    if (mprotect((void*)page_start, page_size, PROT_READ) != 0) {
        LOGW("installJiaguJniDiagHooks: mprotect R failed for %s errno=%d", name, errno);
    }
    LOGI("installJiaguJniDiagHooks: hooked %s original=%p", name, *original);
    return true;
}

static bool installJiaguJniDiagHooks(JNIEnv* env) {
    if (g_jiagu_jni_diag_hooks_installed) {
        LOGI("installJiaguJniDiagHooks: already installed");
        return true;
    }
    void** jniFunctions = *reinterpret_cast<void***>(env);
    bool ok = true;
    ok &= replace_jni_table_entry(jniFunctions, 33, (void*)hooked_GetMethodID, &g_orig_get_method_id, "GetMethodID");
    ok &= replace_jni_table_entry(jniFunctions, 94, (void*)hooked_GetFieldID, &g_orig_get_field_id, "GetFieldID");
    ok &= replace_jni_table_entry(jniFunctions, 113, (void*)hooked_GetStaticMethodID, &g_orig_get_static_method_id, "GetStaticMethodID");
    ok &= replace_jni_table_entry(jniFunctions, 144, (void*)hooked_GetStaticFieldID, &g_orig_get_static_field_id, "GetStaticFieldID");

    g_orig_call_object_method_v = jniFunctions[35];
    g_orig_call_object_method_a = jniFunctions[36];
    g_orig_call_boolean_method_v = jniFunctions[38];
    g_orig_call_boolean_method_a = jniFunctions[39];
    g_orig_call_int_method_v = jniFunctions[50];
    g_orig_call_int_method_a = jniFunctions[51];
    g_orig_call_void_method_v = jniFunctions[62];
    g_orig_call_void_method_a = jniFunctions[63];
    g_orig_call_static_object_method_v = jniFunctions[115];
    g_orig_call_static_object_method_a = jniFunctions[116];
    g_orig_call_static_boolean_method_v = jniFunctions[118];
    g_orig_call_static_boolean_method_a = jniFunctions[119];
    g_orig_call_static_int_method_v = jniFunctions[130];
    g_orig_call_static_int_method_a = jniFunctions[131];
    g_orig_call_static_void_method_v = jniFunctions[136];
    g_orig_call_static_void_method_a = jniFunctions[137];

    ok &= replace_jni_table_entry(jniFunctions, 34, (void*)hooked_CallObjectMethod, &g_orig_call_object_method_v, "CallObjectMethod");
    ok &= replace_jni_table_entry(jniFunctions, 35, (void*)hooked_CallObjectMethodV, &g_orig_call_object_method_v, "CallObjectMethodV");
    ok &= replace_jni_table_entry(jniFunctions, 36, (void*)hooked_CallObjectMethodA, &g_orig_call_object_method_a, "CallObjectMethodA");
    ok &= replace_jni_table_entry(jniFunctions, 37, (void*)hooked_CallBooleanMethod, &g_orig_call_boolean_method_v, "CallBooleanMethod");
    ok &= replace_jni_table_entry(jniFunctions, 38, (void*)hooked_CallBooleanMethodV, &g_orig_call_boolean_method_v, "CallBooleanMethodV");
    ok &= replace_jni_table_entry(jniFunctions, 39, (void*)hooked_CallBooleanMethodA, &g_orig_call_boolean_method_a, "CallBooleanMethodA");
    ok &= replace_jni_table_entry(jniFunctions, 49, (void*)hooked_CallIntMethod, &g_orig_call_int_method_v, "CallIntMethod");
    ok &= replace_jni_table_entry(jniFunctions, 50, (void*)hooked_CallIntMethodV, &g_orig_call_int_method_v, "CallIntMethodV");
    ok &= replace_jni_table_entry(jniFunctions, 51, (void*)hooked_CallIntMethodA, &g_orig_call_int_method_a, "CallIntMethodA");
    ok &= replace_jni_table_entry(jniFunctions, 61, (void*)hooked_CallVoidMethod, &g_orig_call_void_method_v, "CallVoidMethod");
    ok &= replace_jni_table_entry(jniFunctions, 62, (void*)hooked_CallVoidMethodV, &g_orig_call_void_method_v, "CallVoidMethodV");
    ok &= replace_jni_table_entry(jniFunctions, 63, (void*)hooked_CallVoidMethodA, &g_orig_call_void_method_a, "CallVoidMethodA");
    ok &= replace_jni_table_entry(jniFunctions, 114, (void*)hooked_CallStaticObjectMethod, &g_orig_call_static_object_method_v, "CallStaticObjectMethod");
    ok &= replace_jni_table_entry(jniFunctions, 115, (void*)hooked_CallStaticObjectMethodV, &g_orig_call_static_object_method_v, "CallStaticObjectMethodV");
    ok &= replace_jni_table_entry(jniFunctions, 116, (void*)hooked_CallStaticObjectMethodA, &g_orig_call_static_object_method_a, "CallStaticObjectMethodA");
    ok &= replace_jni_table_entry(jniFunctions, 117, (void*)hooked_CallStaticBooleanMethod, &g_orig_call_static_boolean_method_v, "CallStaticBooleanMethod");
    ok &= replace_jni_table_entry(jniFunctions, 118, (void*)hooked_CallStaticBooleanMethodV, &g_orig_call_static_boolean_method_v, "CallStaticBooleanMethodV");
    ok &= replace_jni_table_entry(jniFunctions, 119, (void*)hooked_CallStaticBooleanMethodA, &g_orig_call_static_boolean_method_a, "CallStaticBooleanMethodA");
    ok &= replace_jni_table_entry(jniFunctions, 129, (void*)hooked_CallStaticIntMethod, &g_orig_call_static_int_method_v, "CallStaticIntMethod");
    ok &= replace_jni_table_entry(jniFunctions, 130, (void*)hooked_CallStaticIntMethodV, &g_orig_call_static_int_method_v, "CallStaticIntMethodV");
    ok &= replace_jni_table_entry(jniFunctions, 131, (void*)hooked_CallStaticIntMethodA, &g_orig_call_static_int_method_a, "CallStaticIntMethodA");
    ok &= replace_jni_table_entry(jniFunctions, 135, (void*)hooked_CallStaticVoidMethod, &g_orig_call_static_void_method_v, "CallStaticVoidMethod");
    ok &= replace_jni_table_entry(jniFunctions, 136, (void*)hooked_CallStaticVoidMethodV, &g_orig_call_static_void_method_v, "CallStaticVoidMethodV");
    ok &= replace_jni_table_entry(jniFunctions, 137, (void*)hooked_CallStaticVoidMethodA, &g_orig_call_static_void_method_a, "CallStaticVoidMethodA");
    ok &= replace_jni_table_entry(jniFunctions, 168, (void*)hooked_GetStringUTFLength, &g_orig_get_string_utf_length, "GetStringUTFLength");
    ok &= replace_jni_table_entry(jniFunctions, 169, (void*)hooked_GetStringUTFChars, &g_orig_get_string_utf_chars, "GetStringUTFChars");
    ok &= replace_jni_table_entry(jniFunctions, 170, (void*)hooked_ReleaseStringUTFChars, &g_orig_release_string_utf_chars, "ReleaseStringUTFChars");
    ok &= replace_jni_table_entry(jniFunctions, 171, (void*)hooked_GetArrayLength, &g_orig_get_array_length, "GetArrayLength");
    ok &= replace_jni_table_entry(jniFunctions, 184, (void*)hooked_GetByteArrayElements, &g_orig_get_byte_array_elements, "GetByteArrayElements");
    ok &= replace_jni_table_entry(jniFunctions, 192, (void*)hooked_ReleaseByteArrayElements, &g_orig_release_byte_array_elements, "ReleaseByteArrayElements");
    ok &= replace_jni_table_entry(jniFunctions, 15, (void*)hooked_ExceptionOccurred, &g_orig_exception_occurred, "ExceptionOccurred");
    ok &= replace_jni_table_entry(jniFunctions, 17, (void*)hooked_ExceptionClear, &g_orig_exception_clear, "ExceptionClear");
    ok &= replace_jni_table_entry(jniFunctions, 228, (void*)hooked_ExceptionCheck, &g_orig_exception_check, "ExceptionCheck");

    g_jiagu_jni_diag_hooks_installed = ok;
    LOGI("installJiaguJniDiagHooks: installed=%d", ok ? 1 : 0);
    return ok;
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

    // 壳的 JNI_OnLoad 执行后，dump 解密区域用于分析
    if (strstr(path, "libjiagu_vip.so") != nullptr) {
        LOGI("hooked_nativeLoad: libjiagu_vip.so loaded, dumping decrypted regions...");
        dump_decrypted_jiagu_code();
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
    jclass runtimeClass = env->FindClass("java/lang/Runtime");
    if (runtimeClass == nullptr) {
        LOGE("installNativeLoadHook: cannot find java/lang/Runtime");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }

    void* libart = dlopen("libart.so", RTLD_NOLOAD);
    if (libart == nullptr) {
        LOGW("installNativeLoadHook: libart.so not found via RTLD_NOLOAD, trying dlopen");
        libart = dlopen("libart.so", RTLD_NOW);
    }
    LOGI("installNativeLoadHook: libart=%p", libart);

    if (libart != nullptr) {
        const char* symbols[] = {
            "_ZN3artL18Runtime_nativeLoadEP7_JNIEnvP7_jclassP8_jstringP8_jobjectS5_",
            "_ZN3art18Runtime_nativeLoadEP7_JNIEnvP7_jclassP8_jstringP8_jobjectS5_",
            "Runtime_nativeLoad",
            "_ZN3art7Runtime12nativeLoadEP7_JNIEnvP8_jstringP8_jobjectS5_",
            nullptr
        };

        for (int i = 0; symbols[i] != nullptr; i++) {
            g_orig_nativeLoad_fn = dlsym(libart, symbols[i]);
            if (g_orig_nativeLoad_fn != nullptr) {
                LOGI("installNativeLoadHook: found original at symbol '%s' ptr=%p", symbols[i], g_orig_nativeLoad_fn);
                break;
            }
        }
        if (g_orig_nativeLoad_fn == nullptr) {
            LOGW("installNativeLoadHook: dlsym failed for all symbols, dlerror=%s", dlerror());
        }
    }

    if (g_orig_nativeLoad_fn != nullptr) {
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

    jmethodID nativeLoadMethod = env->GetStaticMethodID(
        runtimeClass, "nativeLoad",
        "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;");
    if (nativeLoadMethod == nullptr) {
        LOGE("installNativeLoadHook: Runtime.nativeLoad method not found (hidden API blocked)");
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(runtimeClass);
        return false;
    }

    LOGE("installNativeLoadHook: cannot find original native symbol in libart.so");
    env->DeleteLocalRef(runtimeClass);
    return false;
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

JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSetRegisterNativesBusinessWrappersEnabled(
    JNIEnv* env, jobject thiz, jboolean enabled)
{
    (void)env;
    (void)thiz;
    g_register_natives_business_wrappers_enabled.store(enabled == JNI_TRUE, std::memory_order_relaxed);
    LOGI("RegisterNatives business wrappers enabled=%d", enabled == JNI_TRUE ? 1 : 0);
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeInstallJiaguJniDiagHooks(
    JNIEnv* env, jobject thiz)
{
    (void)thiz;
    return installJiaguJniDiagHooks(env) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeGetYwLoginBindingReport(
    JNIEnv* env, jobject thiz)
{
    (void)thiz;
    char buffer[512];
    snprintf(
        buffer,
        sizeof(buffer),
        "pwdLogin=%s ptr=%p sendPhoneCode=%s ptr=%p qrCodeV2=%s ptr=%p",
        g_orig_ywlogin_pwdLogin != nullptr ? "bound" : "missing",
        (void*)g_orig_ywlogin_pwdLogin,
        g_orig_ywlogin_sendPhoneCode != nullptr ? "bound" : "missing",
        (void*)g_orig_ywlogin_sendPhoneCode,
        g_orig_ywlogin_qrCodeV2 != nullptr ? "bound" : "missing",
        (void*)g_orig_ywlogin_qrCodeV2);
    return env->NewStringUTF(buffer);
}

JNIEXPORT jstring JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeGetStubAppBindingReport(
    JNIEnv* env, jobject thiz)
{
    (void)thiz;
    int registerCalls = 0;
    int jiaguRegisterCalls = 0;
    int multiappRegisterCalls = 0;
    int jiaguCompleteCalls = 0;
    int lastCount = 0;
    int lastResult = JNI_ERR;
    bool lastCallerIsJiagu = false;
    bool lastAllMultiApp = false;
    bool lastHasInterface11 = false;
    bool lastHasInterface20 = false;
    bool originalJiaguComplete = false;
    bool sawJiaguInterface11 = false;
    bool sawJiaguInterface20 = false;
    std::string lastClass;
    std::string lastCaller;
    {
        std::lock_guard<std::mutex> lock(g_stubapp_register_mutex);
        registerCalls = g_stubapp_register_calls;
        jiaguRegisterCalls = g_stubapp_jiagu_register_calls;
        multiappRegisterCalls = g_stubapp_multiapp_register_calls;
        jiaguCompleteCalls = g_stubapp_jiagu_complete_calls;
        lastCount = g_stubapp_last_count;
        lastResult = g_stubapp_last_result;
        lastCallerIsJiagu = g_stubapp_last_caller_is_jiagu;
        lastAllMultiApp = g_stubapp_last_all_multiapp;
        lastHasInterface11 = g_stubapp_last_has_interface11;
        lastHasInterface20 = g_stubapp_last_has_interface20;
        originalJiaguComplete = g_stubapp_original_jiagu_complete;
        sawJiaguInterface11 = g_stubapp_saw_jiagu_interface11;
        sawJiaguInterface20 = g_stubapp_saw_jiagu_interface20;
        lastClass = g_stubapp_last_class;
        lastCaller = g_stubapp_last_caller;
    }
    char buffer[2048];
    snprintf(
        buffer,
        sizeof(buffer),
        "interface5=%s ptr=%p interface11=%s ptr=%p interface20=%s ptr=%p interface21=%s ptr=%p "
        "stubRegCalls=%d jiaguRegCalls=%d multiappRegCalls=%d jiaguCompleteCalls=%d "
        "originalJiaguComplete=%d sawJiaguInterface11=%d sawJiaguInterface20=%d "
        "lastClass=%s lastCount=%d lastResult=%d lastCallerIsJiagu=%d lastAllMultiApp=%d "
        "lastHasInterface11=%d lastHasInterface20=%d lastCaller=%s",
        g_orig_stub_interface5 != nullptr ? "bound" : "missing",
        (void*)g_orig_stub_interface5,
        g_orig_stub_interface11 != nullptr ? "bound" : "missing",
        (void*)g_orig_stub_interface11,
        g_orig_stub_interface20 != nullptr ? "bound" : "missing",
        (void*)g_orig_stub_interface20,
        g_orig_stub_interface21 != nullptr ? "bound" : "missing",
        (void*)g_orig_stub_interface21,
        registerCalls,
        jiaguRegisterCalls,
        multiappRegisterCalls,
        jiaguCompleteCalls,
        originalJiaguComplete ? 1 : 0,
        sawJiaguInterface11 ? 1 : 0,
        sawJiaguInterface20 ? 1 : 0,
        lastClass.empty() ? "<none>" : lastClass.c_str(),
        lastCount,
        lastResult,
        lastCallerIsJiagu ? 1 : 0,
        lastAllMultiApp ? 1 : 0,
        lastHasInterface11 ? 1 : 0,
        lastHasInterface20 ? 1 : 0,
        lastCaller.empty() ? "<none>" : lastCaller.c_str());
    return env->NewStringUTF(buffer);
}

JNIEXPORT jstring JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeGetStubAppRegisterNativesEvidenceReport(
    JNIEnv* env, jobject thiz)
{
    (void)thiz;
    int registerCalls = 0;
    int lastCount = 0;
    int lastResult = JNI_ERR;
    bool lastCallerIsJiagu = false;
    bool lastAllMultiApp = false;
    bool lastHasInterface11 = false;
    bool lastHasInterface20 = false;
    bool originalJiaguComplete = false;
    std::string lastClass;
    {
        std::lock_guard<std::mutex> lock(g_stubapp_register_mutex);
        registerCalls = g_stubapp_register_calls;
        lastCount = g_stubapp_last_count;
        lastResult = g_stubapp_last_result;
        lastCallerIsJiagu = g_stubapp_last_caller_is_jiagu;
        lastAllMultiApp = g_stubapp_last_all_multiapp;
        lastHasInterface11 = g_stubapp_last_has_interface11;
        lastHasInterface20 = g_stubapp_last_has_interface20;
        originalJiaguComplete = g_stubapp_original_jiagu_complete;
        lastClass = g_stubapp_last_class;
    }

    if (registerCalls == 0) {
        return env->NewStringUTF("");
    }

    char buffer[1024];
    snprintf(
        buffer,
        sizeof(buffer),
        "className=%s\n"
        "methodCount=%d\n"
        "result=%d\n"
        "source=native:RegisterNatives\n"
        "callerIsJiagu=%d\n"
        "allMultiAppMethods=%d\n"
        "hasInterface11=%d\n"
        "hasInterface20=%d\n"
        "jiaguComplete=%d",
        lastClass.empty() ? "com.stub.StubApp" : lastClass.c_str(),
        lastCount,
        lastResult,
        lastCallerIsJiagu ? 1 : 0,
        lastAllMultiApp ? 1 : 0,
        lastHasInterface11 ? 1 : 0,
        lastHasInterface20 ? 1 : 0,
        originalJiaguComplete ? 1 : 0);
    return env->NewStringUTF(buffer);
}

static bool read_jiagu_ptr(uintptr_t address, uintptr_t* out) {
    if (out == nullptr || !is_readable_proc_range(address, sizeof(uintptr_t))) return false;
    memcpy(out, reinterpret_cast<const void*>(address), sizeof(uintptr_t));
    return true;
}

static bool read_jiagu_u32(uintptr_t address, uint32_t* out) {
    if (out == nullptr || !is_readable_proc_range(address, sizeof(uint32_t))) return false;
    memcpy(out, reinterpret_cast<const void*>(address), sizeof(uint32_t));
    return true;
}

static bool read_jiagu_u8(uintptr_t address, uint8_t* out) {
    if (out == nullptr || !is_readable_proc_range(address, sizeof(uint8_t))) return false;
    memcpy(out, reinterpret_cast<const void*>(address), sizeof(uint8_t));
    return true;
}

static std::string read_jiagu_c_string(uintptr_t address, size_t limit = 80) {
    if (address == 0) return "<null>";
    std::string out;
    out.reserve(limit);
    for (size_t i = 0; i < limit; ++i) {
        uint8_t ch = 0;
        if (!read_jiagu_u8(address + i, &ch)) {
            return out.empty() ? "<unreadable>" : out + "<cut-unreadable>";
        }
        if (ch == 0) return out;
        if (ch < 0x20 || ch >= 0x7f) {
            out.push_back('.');
        } else {
            out.push_back(static_cast<char>(ch));
        }
    }
    return out + "...";
}

static std::string read_jiagu_ascii_buffer(uintptr_t address, size_t length, size_t limit = 80) {
    if (address == 0) return "<null>";
    size_t capped = length < limit ? length : limit;
    std::string out;
    out.reserve(capped + 16);
    for (size_t i = 0; i < capped; ++i) {
        uint8_t ch = 0;
        if (!read_jiagu_u8(address + i, &ch)) {
            return out.empty() ? "<unreadable>" : out + "<cut-unreadable>";
        }
        if (ch == 0) {
            out += "\\0";
        } else if (ch < 0x20 || ch >= 0x7f) {
            out.push_back('.');
        } else {
            out.push_back(static_cast<char>(ch));
        }
    }
    if (length > capped) out += "...";
    return out;
}

static std::string read_jiagu_libcpp_string(uintptr_t address, size_t limit = 80) {
    if (address == 0) return "<null>";
    uint8_t tag = 0;
    if (!read_jiagu_u8(address, &tag)) return "<unreadable>";

    uintptr_t length = 0;
    uintptr_t data = 0;
    if ((tag & 1) == 0) {
        length = tag >> 1;
        data = address + 1;
    } else {
        if (!read_jiagu_ptr(address + 0x8, &length) || !read_jiagu_ptr(address + 0x10, &data)) {
            return "<unreadable-long>";
        }
    }

    if (length > limit) length = limit;
    std::string out;
    out.reserve(static_cast<size_t>(length));
    for (uintptr_t i = 0; i < length; ++i) {
        uint8_t ch = 0;
        if (!read_jiagu_u8(data + i, &ch)) {
            return out.empty() ? "<string-data-unreadable>" : out + "<cut-unreadable>";
        }
        if (ch < 0x20 || ch >= 0x7f) {
            out.push_back('.');
        } else {
            out.push_back(static_cast<char>(ch));
        }
    }
    return out;
}

static size_t vector_count_from_begin_end(uintptr_t begin, uintptr_t end, size_t itemSize) {
    if (begin == 0 || end < begin || itemSize == 0) return 0;
    return static_cast<size_t>((end - begin) / itemSize);
}

static std::string build_jiagu_token_insert_hook_diag() {
    uintptr_t ownerBegin = g_jiagu_token_insert_last_owner_vec_begin.load(std::memory_order_relaxed);
    uintptr_t ownerEnd = g_jiagu_token_insert_last_owner_vec_end.load(std::memory_order_relaxed);
    uintptr_t ownerCap = g_jiagu_token_insert_last_owner_vec_cap.load(std::memory_order_relaxed);
    char buf[768];
    snprintf(
        buf,
        sizeof(buf),
        "insertHook={installed=%d attempts=%d failures=%d calls=%d original=%p stub=%p "
        "lastManager=%p lastOwner=%p ownerPayloadVec=%p/%p/%p ownerPayloadCount=%zu "
        "lastPayload=%p payloadKey=%u payloadWord0=%p payloadWord8=%p managerRootAfter=%p managerCountAfter=%zu}",
        g_jiagu_token_insert_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_token_insert_hook_attempts.load(std::memory_order_relaxed),
        g_jiagu_token_insert_hook_failures.load(std::memory_order_relaxed),
        g_jiagu_token_insert_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_token_insert),
        g_jiagu_token_insert_hook_stub,
        reinterpret_cast<void*>(g_jiagu_token_insert_last_manager.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_token_insert_last_owner.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(ownerBegin),
        reinterpret_cast<void*>(ownerEnd),
        reinterpret_cast<void*>(ownerCap),
        vector_count_from_begin_end(ownerBegin, ownerEnd, sizeof(uintptr_t)),
        reinterpret_cast<void*>(g_jiagu_token_insert_last_payload.load(std::memory_order_relaxed)),
        g_jiagu_token_insert_last_payload_key.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_jiagu_token_insert_last_payload_word0.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_token_insert_last_payload_word8.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_token_insert_last_manager_root_after.load(std::memory_order_relaxed)),
        static_cast<size_t>(g_jiagu_token_insert_last_manager_count_after.load(std::memory_order_relaxed)));
    return buf;
}

static std::string build_jiagu_fill_loop_hook_diag() {
    uintptr_t vecBegin = g_jiagu_build_register_vector_last_begin.load(std::memory_order_relaxed);
    uintptr_t vecEnd = g_jiagu_build_register_vector_last_end.load(std::memory_order_relaxed);
    uintptr_t payloadBegin = g_jiagu_build_register_vector_last_first_payload_begin.load(std::memory_order_relaxed);
    uintptr_t payloadEnd = g_jiagu_build_register_vector_last_first_payload_end.load(std::memory_order_relaxed);
    std::string gateKey;
    {
        std::lock_guard<std::mutex> lock(g_jiagu_register_gate_key_mutex);
        gateKey = g_jiagu_register_gate_last_key;
    }
    std::string payloadS1;
    std::string payloadS2;
    std::string payloadS3;
    {
        std::lock_guard<std::mutex> lock(g_jiagu_payload_build_mutex);
        payloadS1 = g_jiagu_payload_build_last_s1_text;
        payloadS2 = g_jiagu_payload_build_last_s2_text;
        payloadS3 = g_jiagu_payload_build_last_s3_text;
    }
    std::string payloadCheckText;
    {
        std::lock_guard<std::mutex> lock(g_jiagu_payload_check_mutex);
        payloadCheckText = g_jiagu_payload_check_last_text;
    }
    std::string cmpLeft;
    std::string cmpRight;
    {
        std::lock_guard<std::mutex> lock(g_jiagu_compare_mutex);
        cmpLeft = g_jiagu_compare_last_left;
        cmpRight = g_jiagu_compare_last_right;
    }
    std::string eqLeft;
    std::string eqRight;
    {
        std::lock_guard<std::mutex> lock(g_jiagu_string_equals_mutex);
        eqLeft = g_jiagu_string_equals_last_left;
        eqRight = g_jiagu_string_equals_last_right;
    }
    std::string materializeArg1;
    std::string materializeArg2;
    std::string materializeArg3;
    std::string materializeSlot270;
    std::string materializeSlot290;
    std::string materializeSlot2f0;
    {
        std::lock_guard<std::mutex> lock(g_jiagu_post_payload_materialize_mutex);
        materializeArg1 = g_jiagu_post_payload_materialize_arg1_text;
        materializeArg2 = g_jiagu_post_payload_materialize_arg2_text;
        materializeArg3 = g_jiagu_post_payload_materialize_arg3_text;
        materializeSlot270 = g_jiagu_post_payload_materialize_slot270_text;
        materializeSlot290 = g_jiagu_post_payload_materialize_slot290_text;
        materializeSlot2f0 = g_jiagu_post_payload_materialize_slot2f0_text;
    }
    char buf[9000];
    snprintf(
        buf,
        sizeof(buf),
        "fillLoopHooks={buildVectorInstalled=%d buildVectorCalls=%d buildVectorOrig=%p buildVectorStub=%p "
        "lastArg=%p lastVector=%p vector=%p/%p vectorCount=%zu firstItem=%p firstPayloadVec=%p/%p firstPayloadCount=%zu "
        "managerInitInstalled=%d managerInitCalls=%d managerInitOrig=%p managerInitStub=%p manager=%p managerRoot=%p managerCount=%zu "
        "gateInstalled=%d gateCalls=%d gateOrig=%p gateStub=%p gateRegistry=%p gateKeyArg=%p gateKey=%s gateResult=%zu "
        "interface20RegInstalled=%d interface20RegCalls=%d interface20RegOrig=%p interface20RegStub=%p interface20RegEnv=%p "
        "payloadBuildInstalled=%d payloadBuildCalls=%d payloadBuildOrig=%p payloadBuildStub=%p payloadEnv=%p payloadArg1=%p "
        "payloadS1=%p:%s payloadS2=%p:%s payloadS3=%p:%s payloadFlags=%d/%d payloadSlot=%p->%p payloadSlot8=%p->%p "
        "payloadCheckInstalled=%d payloadCheckCalls=%d payloadCheckOrig=%p payloadCheckStub=%p payloadCheckArg=%p payloadCheckResult=%d payloadCheckForced=%d payloadCheckText=%s "
        "forcePostBranchPatched=%d forcePreMaterializeGate1Patched=%d forcePreMaterializeGate2Patched=%d forceQiniuGatePatched=%d "
        "postStatusInstalled=%d postStatusCalls=%d postStatusOrig=%p postStatusStub=%p postStatusCallerOff=0x%lx postStatusArg=%p postStatusResult=%d "
        "postObjectInstalled=%d postObjectCalls=%d postObjectOrig=%p postObjectStub=%p postObjectCallerOff=0x%lx postObjectArg=%p postObjectResult=%p "
        "materializeInstalled=%d materializeCalls=%d materializeOrig=%p materializeStub=%p materializeCallerOff=0x%lx "
        "materializeArgs=%p/%p/%p/%p flags=%d/%d materializeArgText=%s|%s|%s "
        "materializeSlots=%p:%s/%p:%s/%p:%s slot358=%u "
        "compareInstalled=%d compareCalls=%d compareLogged=%d compareGot=%p compareCallerOff=0x%lx "
        "compareArgs=%p/%p len=%zu result=%d left=%s right=%s "
        "envProbeInstalled=%d envProbeCalls=%d envProbeCallerOff=0x%lx envProbeArg=%p envProbeResult=%d "
        "stringEqInstalled=%d stringEqCalls=%d stringEqGot=%p stringEqCallerOff=0x%lx stringEqArgs=%p/%p stringEqResult=%d stringEqLeft=%s stringEqRight=%s}",
        g_jiagu_build_register_vector_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_build_register_vector_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_build_register_vector),
        g_jiagu_build_register_vector_hook_stub,
        reinterpret_cast<void*>(g_jiagu_build_register_vector_last_arg.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_build_register_vector_last_result.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(vecBegin),
        reinterpret_cast<void*>(vecEnd),
        vector_count_from_begin_end(vecBegin, vecEnd, sizeof(uintptr_t)),
        reinterpret_cast<void*>(g_jiagu_build_register_vector_last_first_item.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(payloadBegin),
        reinterpret_cast<void*>(payloadEnd),
        vector_count_from_begin_end(payloadBegin, payloadEnd, sizeof(uintptr_t)),
        g_jiagu_token_manager_init_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_token_manager_init_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_token_manager_init),
        g_jiagu_token_manager_init_hook_stub,
        reinterpret_cast<void*>(g_jiagu_token_manager_init_last_result.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_token_manager_init_last_root.load(std::memory_order_relaxed)),
        static_cast<size_t>(g_jiagu_token_manager_init_last_count.load(std::memory_order_relaxed)),
        g_jiagu_register_gate_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_register_gate_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_register_gate),
        g_jiagu_register_gate_hook_stub,
        reinterpret_cast<void*>(g_jiagu_register_gate_last_registry.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_register_gate_last_key_arg.load(std::memory_order_relaxed)),
        gateKey.c_str(),
        static_cast<size_t>(g_jiagu_register_gate_last_result.load(std::memory_order_relaxed)),
        g_jiagu_interface20_register_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_interface20_register_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_interface20_register),
        g_jiagu_interface20_register_hook_stub,
        reinterpret_cast<void*>(g_jiagu_interface20_register_last_env.load(std::memory_order_relaxed)),
        g_jiagu_payload_build_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_payload_build_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_payload_build),
        g_jiagu_payload_build_hook_stub,
        reinterpret_cast<void*>(g_jiagu_payload_build_last_env.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_payload_build_last_arg1.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_payload_build_last_s1.load(std::memory_order_relaxed)),
        payloadS1.c_str(),
        reinterpret_cast<void*>(g_jiagu_payload_build_last_s2.load(std::memory_order_relaxed)),
        payloadS2.c_str(),
        reinterpret_cast<void*>(g_jiagu_payload_build_last_s3.load(std::memory_order_relaxed)),
        payloadS3.c_str(),
        g_jiagu_payload_build_last_flag3.load(std::memory_order_relaxed),
        g_jiagu_payload_build_last_flag4.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_jiagu_payload_build_slot_before.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_payload_build_slot_after.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_payload_build_slot8_before.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_payload_build_slot8_after.load(std::memory_order_relaxed)),
        g_jiagu_payload_check_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_payload_check_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_payload_check),
        g_jiagu_payload_check_hook_stub,
        reinterpret_cast<void*>(g_jiagu_payload_check_last_arg.load(std::memory_order_relaxed)),
        g_jiagu_payload_check_last_result.load(std::memory_order_relaxed),
        g_jiagu_payload_check_last_forced.load(std::memory_order_relaxed),
        payloadCheckText.c_str(),
        g_jiagu_force_post_payload_branch_patched.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_force_pre_materialize_gate1_patched.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_force_pre_materialize_gate2_patched.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_force_qiniu_gate_patched.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_post_payload_status_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_post_payload_status_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_post_payload_status),
        g_jiagu_post_payload_status_hook_stub,
        static_cast<unsigned long>(g_jiagu_post_payload_status_last_caller_off.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_post_payload_status_last_arg.load(std::memory_order_relaxed)),
        g_jiagu_post_payload_status_last_result.load(std::memory_order_relaxed),
        g_jiagu_post_payload_object_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_post_payload_object_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_post_payload_object),
        g_jiagu_post_payload_object_hook_stub,
        static_cast<unsigned long>(g_jiagu_post_payload_object_last_caller_off.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_post_payload_object_last_arg.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_post_payload_object_last_result.load(std::memory_order_relaxed)),
        g_jiagu_post_payload_materialize_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_post_payload_materialize_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_orig_jiagu_post_payload_materialize),
        g_jiagu_post_payload_materialize_hook_stub,
        static_cast<unsigned long>(g_jiagu_post_payload_materialize_last_caller_off.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_post_payload_materialize_last_arg0.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_post_payload_materialize_last_arg1.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_post_payload_materialize_last_arg2.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_post_payload_materialize_last_arg3.load(std::memory_order_relaxed)),
        g_jiagu_post_payload_materialize_last_flag4.load(std::memory_order_relaxed),
        g_jiagu_post_payload_materialize_last_flag5.load(std::memory_order_relaxed),
        materializeArg1.c_str(),
        materializeArg2.c_str(),
        materializeArg3.c_str(),
        reinterpret_cast<void*>(g_jiagu_post_payload_materialize_slot270.load(std::memory_order_relaxed)),
        materializeSlot270.c_str(),
        reinterpret_cast<void*>(g_jiagu_post_payload_materialize_slot290.load(std::memory_order_relaxed)),
        materializeSlot290.c_str(),
        reinterpret_cast<void*>(g_jiagu_post_payload_materialize_slot2f0.load(std::memory_order_relaxed)),
        materializeSlot2f0.c_str(),
        g_jiagu_post_payload_materialize_slot358.load(std::memory_order_relaxed),
        g_jiagu_compare_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_compare_calls.load(std::memory_order_relaxed),
        g_jiagu_compare_logged_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_jiagu_compare_got_slot.load(std::memory_order_relaxed)),
        static_cast<unsigned long>(g_jiagu_compare_last_caller_off.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_compare_last_arg0.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_compare_last_arg1.load(std::memory_order_relaxed)),
        g_jiagu_compare_last_len.load(std::memory_order_relaxed),
        g_jiagu_compare_last_result.load(std::memory_order_relaxed),
        cmpLeft.c_str(),
        cmpRight.c_str(),
        g_jiagu_env_probe_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_env_probe_calls.load(std::memory_order_relaxed),
        static_cast<unsigned long>(g_jiagu_env_probe_last_caller_off.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_env_probe_last_arg.load(std::memory_order_relaxed)),
        g_jiagu_env_probe_last_result.load(std::memory_order_relaxed),
        g_jiagu_string_equals_hook_installed.load(std::memory_order_relaxed) ? 1 : 0,
        g_jiagu_string_equals_calls.load(std::memory_order_relaxed),
        reinterpret_cast<void*>(g_jiagu_string_equals_got_slot.load(std::memory_order_relaxed)),
        static_cast<unsigned long>(g_jiagu_string_equals_last_caller_off.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_string_equals_last_arg0.load(std::memory_order_relaxed)),
        reinterpret_cast<void*>(g_jiagu_string_equals_last_arg1.load(std::memory_order_relaxed)),
        g_jiagu_string_equals_last_result.load(std::memory_order_relaxed),
        eqLeft.c_str(),
        eqRight.c_str());
    return buf;
}

static int hooked_jiagu_compare(const void* left, const void* right, size_t len) {
    void* caller = __builtin_return_address(0);
    int result = 0;
    if (g_orig_jiagu_compare != nullptr) {
        result = g_orig_jiagu_compare(left, right, len);
    } else {
        result = 0;
    }

    g_jiagu_compare_calls.fetch_add(1, std::memory_order_relaxed);

    Dl_info info{};
    uintptr_t callerOff = 0;
    bool logCall = false;
    if (caller != nullptr &&
        dladdr(caller, &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
        if (pc >= base) {
            callerOff = pc - base;
            logCall = callerOff >= 0x10d468 && callerOff <= 0x10fd00;
        }
    }

    bool forcedEqual = false;
    if (logCall && len == 1 && left != nullptr && right != nullptr) {
        uint8_t leftByte = 0;
        uint8_t rightByte = 0;
        if (read_jiagu_u8(reinterpret_cast<uintptr_t>(left), &leftByte) &&
            read_jiagu_u8(reinterpret_cast<uintptr_t>(right), &rightByte) &&
            leftByte == '0' &&
            rightByte == '1' &&
            (callerOff == 0x10e0ec || callerOff == 0x10e36c || callerOff == 0x10e618)) {
            result = 0;
            forcedEqual = true;
        }
    }

    if (logCall) {
        int logged = g_jiagu_compare_logged_calls.fetch_add(1, std::memory_order_relaxed) + 1;
        std::string leftText = read_jiagu_ascii_buffer(reinterpret_cast<uintptr_t>(left), len);
        std::string rightText = read_jiagu_ascii_buffer(reinterpret_cast<uintptr_t>(right), len);
        g_jiagu_compare_last_caller_off.store(callerOff, std::memory_order_relaxed);
        g_jiagu_compare_last_arg0.store(reinterpret_cast<uintptr_t>(left), std::memory_order_relaxed);
        g_jiagu_compare_last_arg1.store(reinterpret_cast<uintptr_t>(right), std::memory_order_relaxed);
        g_jiagu_compare_last_len.store(len, std::memory_order_relaxed);
        g_jiagu_compare_last_result.store(result, std::memory_order_relaxed);
        {
            std::lock_guard<std::mutex> lock(g_jiagu_compare_mutex);
            g_jiagu_compare_last_left = leftText;
            g_jiagu_compare_last_right = rightText;
        }
        if (logged <= 80 || result == 0) {
            LOGW("JiaguCompare call=%d callerOff=0x%lx left=%p right=%p len=%zu result=%d forced=%d leftText=%s rightText=%s",
                 logged,
                 static_cast<unsigned long>(callerOff),
                 left,
                 right,
                 len,
                 result,
                 forcedEqual ? 1 : 0,
                 leftText.c_str(),
                 rightText.c_str());
        }
    }
    return result;
}

static int hooked_jiagu_env_probe(void* arg) {
    void* caller = __builtin_return_address(0);
    int result = 0;
    if (g_orig_jiagu_env_probe != nullptr) {
        result = g_orig_jiagu_env_probe(arg);
    }

    int callIndex = g_jiagu_env_probe_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    uintptr_t callerOff = 0;
    Dl_info info{};
    if (caller != nullptr &&
        dladdr(caller, &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
        if (pc >= base) callerOff = pc - base;
    }
    bool forcedSdk25 = false;
    if (callerOff == 0x129918 || callerOff == 0x10eb7c) {
        result = 0x19;
        forcedSdk25 = true;
    }
    g_jiagu_env_probe_last_caller_off.store(callerOff, std::memory_order_relaxed);
    g_jiagu_env_probe_last_arg.store(reinterpret_cast<uintptr_t>(arg), std::memory_order_relaxed);
    g_jiagu_env_probe_last_result.store(result, std::memory_order_relaxed);
    if (callerOff == 0x129918 || callerOff == 0x10eb7c || callIndex <= 8) {
        LOGW("JiaguEnvProbe call=%d callerOff=0x%lx arg=%p result=%d forcedSdk25=%d",
             callIndex,
             static_cast<unsigned long>(callerOff),
             arg,
             result,
             forcedSdk25 ? 1 : 0);
    }
    return result;
}

static int hooked_jiagu_qiniu_check(void* envLike) {
    void* caller = __builtin_return_address(0);
    int result = 0;
    if (g_orig_jiagu_qiniu_check != nullptr) {
        result = g_orig_jiagu_qiniu_check(envLike);
    }
    int callIndex = g_jiagu_qiniu_check_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    uintptr_t callerOff = 0;
    Dl_info info{};
    if (caller != nullptr &&
        dladdr(caller, &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
        if (pc >= base) callerOff = pc - base;
    }
    g_jiagu_qiniu_check_last_caller_off.store(callerOff, std::memory_order_relaxed);
    g_jiagu_qiniu_check_last_arg.store(reinterpret_cast<uintptr_t>(envLike), std::memory_order_relaxed);
    g_jiagu_qiniu_check_last_result.store(result, std::memory_order_relaxed);
    if (callIndex <= 20 || callerOff == 0x10fb1c || result > 0x1e) {
        LOGW("JiaguQiniuCheck call=%d callerOff=0x%lx env=%p result=%d",
             callIndex,
             static_cast<unsigned long>(callerOff),
             envLike,
             result);
    }
    return result;
}

static int hooked_jiagu_string_equals(const void* left, const void* right) {
    void* caller = __builtin_return_address(0);
    int result = 0;
    if (g_orig_jiagu_string_equals != nullptr) {
        result = g_orig_jiagu_string_equals(left, right);
    }

    int callIndex = g_jiagu_string_equals_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    uintptr_t callerOff = 0;
    bool logCall = false;
    Dl_info info{};
    if (caller != nullptr &&
        dladdr(caller, &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
        if (pc >= base) {
            callerOff = pc - base;
            logCall = (callerOff >= 0x129000 && callerOff <= 0x12a100) ||
                      (callerOff >= 0x10d468 && callerOff <= 0x10f800);
        }
    }

    std::string leftText;
    std::string rightText;
    if (logCall) {
        leftText = read_jiagu_ascii_buffer(reinterpret_cast<uintptr_t>(left), 64);
        rightText = read_jiagu_ascii_buffer(reinterpret_cast<uintptr_t>(right), 64);
        g_jiagu_string_equals_last_caller_off.store(callerOff, std::memory_order_relaxed);
        g_jiagu_string_equals_last_arg0.store(reinterpret_cast<uintptr_t>(left), std::memory_order_relaxed);
        g_jiagu_string_equals_last_arg1.store(reinterpret_cast<uintptr_t>(right), std::memory_order_relaxed);
        g_jiagu_string_equals_last_result.store(result, std::memory_order_relaxed);
        {
            std::lock_guard<std::mutex> lock(g_jiagu_string_equals_mutex);
            g_jiagu_string_equals_last_left = leftText;
            g_jiagu_string_equals_last_right = rightText;
        }
    }
    if (logCall && (callIndex <= 80 || callerOff == 0x12995c || callerOff == 0x10f270)) {
        LOGW("JiaguStringEq call=%d callerOff=0x%lx left=%p:%s right=%p:%s result=%d",
             callIndex,
             static_cast<unsigned long>(callerOff),
             left,
             leftText.c_str(),
             right,
             rightText.c_str(),
             result);
    }
    return result;
}

static int hooked_jiagu_payload_check(void* textArg) {
    int callIndex = g_jiagu_payload_check_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    std::string text = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(textArg));
    int result = 0;
    if (g_orig_jiagu_payload_check != nullptr) {
        result = g_orig_jiagu_payload_check(textArg);
    } else {
        LOGW("JiaguPayloadCheck original missing; returning 0");
    }
    bool forced = false;
    if (text == "com.qq.reader" && result == 0) {
        result = 1;
        forced = true;
    }

    g_jiagu_payload_check_last_arg.store(reinterpret_cast<uintptr_t>(textArg), std::memory_order_relaxed);
    g_jiagu_payload_check_last_result.store(result, std::memory_order_relaxed);
    g_jiagu_payload_check_last_forced.store(forced ? 1 : 0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_jiagu_payload_check_mutex);
        g_jiagu_payload_check_last_text = text;
    }
    if (callIndex <= 20 || result == 0 || forced) {
        LOGW("JiaguPayloadCheck call=%d arg=%p text=%s result=%d forced=%d",
             callIndex,
             textArg,
             text.c_str(),
             result,
             forced ? 1 : 0);
    }
    return result;
}

static int hooked_jiagu_post_payload_status(void* envLike) {
    void* caller = __builtin_return_address(0);
    int callIndex = g_jiagu_post_payload_status_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    int result = 0;
    if (g_orig_jiagu_post_payload_status != nullptr) {
        result = g_orig_jiagu_post_payload_status(envLike);
    } else {
        LOGW("JiaguPostPayloadStatus original missing; returning 0");
    }
    uintptr_t callerOff = 0;
    Dl_info info{};
    if (caller != nullptr &&
        dladdr(caller, &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
        if (pc >= base) callerOff = pc - base;
    }
    g_jiagu_post_payload_status_last_arg.store(reinterpret_cast<uintptr_t>(envLike), std::memory_order_relaxed);
    g_jiagu_post_payload_status_last_result.store(result, std::memory_order_relaxed);
    g_jiagu_post_payload_status_last_caller_off.store(callerOff, std::memory_order_relaxed);
    if (callIndex <= 20 || result != 0 || callerOff == 0x10ece0) {
        LOGW("JiaguPostPayloadStatus call=%d callerOff=0x%lx env=%p result=%d",
             callIndex,
             static_cast<unsigned long>(callerOff),
             envLike,
             result);
    }
    return result;
}

static void* hooked_jiagu_post_payload_object(void* envLike) {
    void* caller = __builtin_return_address(0);
    int callIndex = g_jiagu_post_payload_object_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    void* result = nullptr;
    if (g_orig_jiagu_post_payload_object != nullptr) {
        result = g_orig_jiagu_post_payload_object(envLike);
    } else {
        LOGW("JiaguPostPayloadObject original missing; returning null");
    }
    uintptr_t callerOff = 0;
    Dl_info info{};
    if (caller != nullptr &&
        dladdr(caller, &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
        if (pc >= base) callerOff = pc - base;
    }
    g_jiagu_post_payload_object_last_arg.store(reinterpret_cast<uintptr_t>(envLike), std::memory_order_relaxed);
    g_jiagu_post_payload_object_last_result.store(reinterpret_cast<uintptr_t>(result), std::memory_order_relaxed);
    g_jiagu_post_payload_object_last_caller_off.store(callerOff, std::memory_order_relaxed);
    if (callIndex <= 20 || result != nullptr || callerOff == 0x10ecec) {
        LOGW("JiaguPostPayloadObject call=%d callerOff=0x%lx env=%p result=%p",
             callIndex,
             static_cast<unsigned long>(callerOff),
             envLike,
             result);
    }
    return result;
}

static void hooked_jiagu_post_payload_materialize(
    void* arg0,
    void* arg1,
    void* arg2,
    void* arg3,
    int flag4,
    int flag5
) {
    void* caller = __builtin_return_address(0);
    int callIndex = g_jiagu_post_payload_materialize_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    uintptr_t callerOff = 0;
    uintptr_t base = 0;
    Dl_info info{};
    if (caller != nullptr &&
        dladdr(caller, &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
        if (pc >= base) callerOff = pc - base;
    }
    if (base == 0 && g_orig_stub_interface20 != nullptr &&
        dladdr(reinterpret_cast<void*>(g_orig_stub_interface20), &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    }

    std::string arg1Text = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(arg1));
    std::string arg2Text = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(arg2));
    std::string arg3Text = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(arg3));
    g_jiagu_post_payload_materialize_last_caller_off.store(callerOff, std::memory_order_relaxed);
    g_jiagu_post_payload_materialize_last_arg0.store(reinterpret_cast<uintptr_t>(arg0), std::memory_order_relaxed);
    g_jiagu_post_payload_materialize_last_arg1.store(reinterpret_cast<uintptr_t>(arg1), std::memory_order_relaxed);
    g_jiagu_post_payload_materialize_last_arg2.store(reinterpret_cast<uintptr_t>(arg2), std::memory_order_relaxed);
    g_jiagu_post_payload_materialize_last_arg3.store(reinterpret_cast<uintptr_t>(arg3), std::memory_order_relaxed);
    g_jiagu_post_payload_materialize_last_flag4.store(flag4, std::memory_order_relaxed);
    g_jiagu_post_payload_materialize_last_flag5.store(flag5, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_jiagu_post_payload_materialize_mutex);
        g_jiagu_post_payload_materialize_arg1_text = arg1Text;
        g_jiagu_post_payload_materialize_arg2_text = arg2Text;
        g_jiagu_post_payload_materialize_arg3_text = arg3Text;
    }

    if (callIndex <= 20 || callerOff == 0x10f5fc) {
        LOGW("JiaguPostPayloadMaterialize enter call=%d callerOff=0x%lx args=%p/%p:%s/%p:%s/%p:%s flags=%d/%d",
             callIndex,
             static_cast<unsigned long>(callerOff),
             arg0,
             arg1,
             arg1Text.c_str(),
             arg2,
             arg2Text.c_str(),
             arg3,
             arg3Text.c_str(),
             flag4,
             flag5);
    }

    if (g_orig_jiagu_post_payload_materialize != nullptr) {
        g_orig_jiagu_post_payload_materialize(arg0, arg1, arg2, arg3, flag4, flag5);
    } else {
        LOGW("JiaguPostPayloadMaterialize original missing; skipped");
    }

    uintptr_t slot270 = 0;
    uintptr_t slot290 = 0;
    uintptr_t slot2f0 = 0;
    uint32_t slot358 = 0;
    std::string slot270Text = "<no-base>";
    std::string slot290Text = "<no-base>";
    std::string slot2f0Text = "<no-base>";
    if (base != 0) {
        slot270 = base + 0x253270;
        slot290 = base + 0x253290;
        slot2f0 = base + 0x2532f0;
        slot270Text = read_jiagu_libcpp_string(slot270);
        slot290Text = read_jiagu_libcpp_string(slot290);
        slot2f0Text = read_jiagu_libcpp_string(slot2f0);
        read_jiagu_u32(base + 0x253358, &slot358);
    }
    g_jiagu_post_payload_materialize_slot270.store(slot270, std::memory_order_relaxed);
    g_jiagu_post_payload_materialize_slot290.store(slot290, std::memory_order_relaxed);
    g_jiagu_post_payload_materialize_slot2f0.store(slot2f0, std::memory_order_relaxed);
    g_jiagu_post_payload_materialize_slot358.store(slot358, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_jiagu_post_payload_materialize_mutex);
        g_jiagu_post_payload_materialize_slot270_text = slot270Text;
        g_jiagu_post_payload_materialize_slot290_text = slot290Text;
        g_jiagu_post_payload_materialize_slot2f0_text = slot2f0Text;
    }
    if (callIndex <= 20 || callerOff == 0x10f5fc) {
        LOGW("JiaguPostPayloadMaterialize leave call=%d callerOff=0x%lx slots=%p:%s/%p:%s/%p:%s slot358=%u",
             callIndex,
             static_cast<unsigned long>(callerOff),
             reinterpret_cast<void*>(slot270),
             slot270Text.c_str(),
             reinterpret_cast<void*>(slot290),
             slot290Text.c_str(),
             reinterpret_cast<void*>(slot2f0),
             slot2f0Text.c_str(),
             slot358);
    }
}

static void hooked_jiagu_after_materialize_normalize(void* pathArg) {
    void* caller = __builtin_return_address(0);
    int callIndex = g_jiagu_after_materialize_normalize_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    uintptr_t callerOff = 0;
    uintptr_t base = 0;
    Dl_info info{};
    if (caller != nullptr &&
        dladdr(caller, &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        base = reinterpret_cast<uintptr_t>(info.dli_fbase);
        uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
        if (pc >= base) callerOff = pc - base;
    }
    if (base == 0 && g_orig_stub_interface20 != nullptr &&
        dladdr(reinterpret_cast<void*>(g_orig_stub_interface20), &info) != 0 &&
        info.dli_fbase != nullptr &&
        info.dli_fname != nullptr &&
        strstr(info.dli_fname, "libjiagu_vip.so") != nullptr) {
        base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    }
    std::string before = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(pathArg), 160);
    g_jiagu_after_materialize_normalize_last_caller_off.store(callerOff, std::memory_order_relaxed);
    if (callIndex <= 20 || callerOff == 0x10fa10) {
        LOGW("JiaguAfterMaterializeNormalize enter call=%d callerOff=0x%lx arg=%p text=%s",
             callIndex,
             static_cast<unsigned long>(callerOff),
             pathArg,
             before.c_str());
    }
    if (g_orig_jiagu_after_materialize_normalize != nullptr) {
        g_orig_jiagu_after_materialize_normalize(pathArg);
    } else {
        LOGW("JiaguAfterMaterializeNormalize original missing; skipped");
    }
    std::string after = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(pathArg), 160);
    std::string slot2d0 = "<no-base>";
    std::string slot350 = "<no-base>";
    if (base != 0) {
        slot2d0 = read_jiagu_libcpp_string(base + 0x2532d0, 160);
        slot350 = read_jiagu_libcpp_string(base + 0x253350, 160);
    }
    if (callIndex <= 20 || callerOff == 0x10fa10) {
        LOGW("JiaguAfterMaterializeNormalize leave call=%d callerOff=0x%lx arg=%p text=%s slot2d0=%s slot350=%s",
             callIndex,
             static_cast<unsigned long>(callerOff),
             pathArg,
             after.c_str(),
             slot2d0.c_str(),
             slot350.c_str());
    }
}

static void hooked_jiagu_interface20_register(void* envLike) {
    int callIndex = g_jiagu_interface20_register_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    g_jiagu_interface20_register_last_env.store(reinterpret_cast<uintptr_t>(envLike), std::memory_order_relaxed);
    if (callIndex <= 20) {
        LOGW("Jiagu10d468 enter call=%d env=%p", callIndex, envLike);
    }
    if (g_orig_jiagu_interface20_register != nullptr) {
        g_orig_jiagu_interface20_register(envLike);
    } else {
        LOGW("Jiagu10d468 original missing; skipped");
    }
    if (callIndex <= 20) {
        std::string tokenDiag = build_jiagu_token_insert_hook_diag();
        std::string fillDiag = build_jiagu_fill_loop_hook_diag();
        LOGW("Jiagu10d468 leave call=%d %s %s",
             callIndex,
             tokenDiag.c_str(),
             fillDiag.c_str());
    }
}

static void hooked_jiagu_payload_build(
    void* envLike,
    void* arg1,
    void* s1,
    int flag3,
    int flag4,
    void* s2,
    void* s3
) {
    int callIndex = g_jiagu_payload_build_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    std::string s1Text = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(s1));
    std::string s2Text = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(s2));
    std::string s3Text = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(s3));

    g_jiagu_payload_build_last_env.store(reinterpret_cast<uintptr_t>(envLike), std::memory_order_relaxed);
    g_jiagu_payload_build_last_arg1.store(reinterpret_cast<uintptr_t>(arg1), std::memory_order_relaxed);
    g_jiagu_payload_build_last_s1.store(reinterpret_cast<uintptr_t>(s1), std::memory_order_relaxed);
    g_jiagu_payload_build_last_s2.store(reinterpret_cast<uintptr_t>(s2), std::memory_order_relaxed);
    g_jiagu_payload_build_last_s3.store(reinterpret_cast<uintptr_t>(s3), std::memory_order_relaxed);
    g_jiagu_payload_build_last_flag3.store(flag3, std::memory_order_relaxed);
    g_jiagu_payload_build_last_flag4.store(flag4, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_jiagu_payload_build_mutex);
        g_jiagu_payload_build_last_s1_text = s1Text;
        g_jiagu_payload_build_last_s2_text = s2Text;
        g_jiagu_payload_build_last_s3_text = s3Text;
    }

    uintptr_t payloadSlotAddr = 0;
    uintptr_t slotBefore = 0;
    uintptr_t slotAfter = 0;
    uintptr_t slot8Before = 0;
    uintptr_t slot8After = 0;
    Dl_info jiaguInfo{};
    if (g_orig_stub_interface20 != nullptr &&
        dladdr(reinterpret_cast<void*>(g_orig_stub_interface20), &jiaguInfo) != 0 &&
        jiaguInfo.dli_fbase != nullptr &&
        jiaguInfo.dli_fname != nullptr &&
        strstr(jiaguInfo.dli_fname, "libjiagu_vip.so") != nullptr) {
        payloadSlotAddr = reinterpret_cast<uintptr_t>(jiaguInfo.dli_fbase) + 0x253010;
        read_jiagu_ptr(payloadSlotAddr, &slotBefore);
        read_jiagu_ptr(payloadSlotAddr + 0x8, &slot8Before);
        g_jiagu_payload_build_slot_before.store(slotBefore, std::memory_order_relaxed);
        g_jiagu_payload_build_slot8_before.store(slot8Before, std::memory_order_relaxed);
    }

    if (callIndex <= 20) {
        LOGW("JiaguPayloadBuild enter call=%d env=%p arg1=%p s1=%p:%s flags=%d/%d s2=%p:%s s3=%p:%s slot=%p before=%p before8=%p",
             callIndex,
             envLike,
             arg1,
             s1,
             s1Text.c_str(),
             flag3,
             flag4,
             s2,
             s2Text.c_str(),
             s3,
             s3Text.c_str(),
             reinterpret_cast<void*>(payloadSlotAddr),
             reinterpret_cast<void*>(slotBefore),
             reinterpret_cast<void*>(slot8Before));
    }

    if (g_orig_jiagu_payload_build != nullptr) {
        g_orig_jiagu_payload_build(envLike, arg1, s1, flag3, flag4, s2, s3);
    } else {
        LOGW("JiaguPayloadBuild original missing; skipped");
    }

    if (payloadSlotAddr != 0) {
        read_jiagu_ptr(payloadSlotAddr, &slotAfter);
        read_jiagu_ptr(payloadSlotAddr + 0x8, &slot8After);
        g_jiagu_payload_build_slot_after.store(slotAfter, std::memory_order_relaxed);
        g_jiagu_payload_build_slot8_after.store(slot8After, std::memory_order_relaxed);
        if (callIndex <= 20) {
            LOGW("JiaguPayloadBuild leave call=%d slot=%p before=%p after=%p before8=%p after8=%p",
                 callIndex,
                 reinterpret_cast<void*>(payloadSlotAddr),
                 reinterpret_cast<void*>(slotBefore),
                 reinterpret_cast<void*>(slotAfter),
                 reinterpret_cast<void*>(slot8Before),
                 reinterpret_cast<void*>(slot8After));
        }
    }
}

static uintptr_t hooked_jiagu_build_register_vector(void* arg) {
    int callIndex = g_jiagu_build_register_vector_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    uintptr_t result = 0;
    if (g_orig_jiagu_build_register_vector != nullptr) {
        result = g_orig_jiagu_build_register_vector(arg);
    }

    uintptr_t begin = 0;
    uintptr_t end = 0;
    uintptr_t firstItem = 0;
    uintptr_t firstPayloadBegin = 0;
    uintptr_t firstPayloadEnd = 0;
    if (result != 0) {
        read_jiagu_ptr(result, &begin);
        read_jiagu_ptr(result + 0x8, &end);
        if (begin != 0) {
            read_jiagu_ptr(begin, &firstItem);
        }
        if (firstItem != 0) {
            read_jiagu_ptr(firstItem + 0x160, &firstPayloadBegin);
            read_jiagu_ptr(firstItem + 0x168, &firstPayloadEnd);
        }
    }

    g_jiagu_build_register_vector_last_arg.store(reinterpret_cast<uintptr_t>(arg), std::memory_order_relaxed);
    g_jiagu_build_register_vector_last_result.store(result, std::memory_order_relaxed);
    g_jiagu_build_register_vector_last_begin.store(begin, std::memory_order_relaxed);
    g_jiagu_build_register_vector_last_end.store(end, std::memory_order_relaxed);
    g_jiagu_build_register_vector_last_first_item.store(firstItem, std::memory_order_relaxed);
    g_jiagu_build_register_vector_last_first_payload_begin.store(firstPayloadBegin, std::memory_order_relaxed);
    g_jiagu_build_register_vector_last_first_payload_end.store(firstPayloadEnd, std::memory_order_relaxed);

    size_t vectorCount = vector_count_from_begin_end(begin, end, sizeof(uintptr_t));
    size_t firstPayloadCount = vector_count_from_begin_end(firstPayloadBegin, firstPayloadEnd, sizeof(uintptr_t));
    if (callIndex <= 20 || vectorCount > 0 || firstPayloadCount > 0) {
        LOGW("JiaguBuildRegisterVector call=%d arg=%p result=%p vector=%p/%p count=%zu firstItem=%p firstPayloadVec=%p/%p firstPayloadCount=%zu",
             callIndex,
             arg,
             reinterpret_cast<void*>(result),
             reinterpret_cast<void*>(begin),
             reinterpret_cast<void*>(end),
             vectorCount,
             reinterpret_cast<void*>(firstItem),
             reinterpret_cast<void*>(firstPayloadBegin),
             reinterpret_cast<void*>(firstPayloadEnd),
             firstPayloadCount);
    }
    return result;
}

static uintptr_t hooked_jiagu_token_manager_init() {
    int callIndex = g_jiagu_token_manager_init_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    uintptr_t result = 0;
    if (g_orig_jiagu_token_manager_init != nullptr) {
        result = g_orig_jiagu_token_manager_init();
    }
    uintptr_t root = 0;
    uintptr_t count = 0;
    if (result != 0) {
        read_jiagu_ptr(result + 0x20, &root);
        read_jiagu_ptr(result + 0x28, &count);
    }
    g_jiagu_token_manager_init_last_result.store(result, std::memory_order_relaxed);
    g_jiagu_token_manager_init_last_root.store(root, std::memory_order_relaxed);
    g_jiagu_token_manager_init_last_count.store(count, std::memory_order_relaxed);
    if (callIndex <= 20) {
        LOGW("JiaguTokenManagerInit call=%d result=%p root=%p treeCount=%zu",
             callIndex,
             reinterpret_cast<void*>(result),
             reinterpret_cast<void*>(root),
             static_cast<size_t>(count));
    }
    return result;
}

static uintptr_t hooked_jiagu_register_gate(void* registry, void* keyArg) {
    int callIndex = g_jiagu_register_gate_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    std::string key = read_jiagu_libcpp_string(reinterpret_cast<uintptr_t>(keyArg));
    uintptr_t result = 0;
    if (g_orig_jiagu_register_gate != nullptr) {
        result = g_orig_jiagu_register_gate(registry, keyArg);
    }
    g_jiagu_register_gate_last_registry.store(reinterpret_cast<uintptr_t>(registry), std::memory_order_relaxed);
    g_jiagu_register_gate_last_key_arg.store(reinterpret_cast<uintptr_t>(keyArg), std::memory_order_relaxed);
    g_jiagu_register_gate_last_result.store(result, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_jiagu_register_gate_key_mutex);
        g_jiagu_register_gate_last_key = key;
    }
    if (callIndex <= 20 || result == 0) {
        LOGW("JiaguRegisterGate call=%d registry=%p keyArg=%p key=%s result=%zu",
             callIndex,
             registry,
             keyArg,
             key.c_str(),
             static_cast<size_t>(result));
    }
    return result;
}

static uintptr_t hooked_jiagu_token_insert(void* manager, void* owner, void* payload) {
    int callIndex = g_jiagu_token_insert_calls.fetch_add(1, std::memory_order_relaxed) + 1;

    uintptr_t ownerBegin = 0;
    uintptr_t ownerEnd = 0;
    uintptr_t ownerCap = 0;
    uintptr_t payloadWord0 = 0;
    uintptr_t payloadWord8 = 0;
    uint32_t payloadKey = 0;
    uintptr_t ownerAddr = reinterpret_cast<uintptr_t>(owner);
    uintptr_t payloadAddr = reinterpret_cast<uintptr_t>(payload);
    if (ownerAddr != 0) {
        read_jiagu_ptr(ownerAddr + 0x160, &ownerBegin);
        read_jiagu_ptr(ownerAddr + 0x168, &ownerEnd);
        read_jiagu_ptr(ownerAddr + 0x170, &ownerCap);
    }
    if (payloadAddr != 0) {
        read_jiagu_ptr(payloadAddr, &payloadWord0);
        read_jiagu_ptr(payloadAddr + 0x8, &payloadWord8);
        read_jiagu_u32(payloadAddr + 0xc, &payloadKey);
    }

    g_jiagu_token_insert_last_manager.store(reinterpret_cast<uintptr_t>(manager), std::memory_order_relaxed);
    g_jiagu_token_insert_last_owner.store(ownerAddr, std::memory_order_relaxed);
    g_jiagu_token_insert_last_payload.store(payloadAddr, std::memory_order_relaxed);
    g_jiagu_token_insert_last_owner_vec_begin.store(ownerBegin, std::memory_order_relaxed);
    g_jiagu_token_insert_last_owner_vec_end.store(ownerEnd, std::memory_order_relaxed);
    g_jiagu_token_insert_last_owner_vec_cap.store(ownerCap, std::memory_order_relaxed);
    g_jiagu_token_insert_last_payload_word0.store(payloadWord0, std::memory_order_relaxed);
    g_jiagu_token_insert_last_payload_word8.store(payloadWord8, std::memory_order_relaxed);
    g_jiagu_token_insert_last_payload_key.store(payloadKey, std::memory_order_relaxed);

    if (callIndex <= 20 || payloadKey == 59494) {
        LOGW("JiaguTokenInsert enter call=%d manager=%p owner=%p ownerPayloadVec=%p/%p/%p ownerPayloadCount=%zu "
             "payload=%p payloadKey=%u payloadWord0=%p payloadWord8=%p",
             callIndex,
             manager,
             owner,
             reinterpret_cast<void*>(ownerBegin),
             reinterpret_cast<void*>(ownerEnd),
             reinterpret_cast<void*>(ownerCap),
             vector_count_from_begin_end(ownerBegin, ownerEnd, sizeof(uintptr_t)),
             payload,
             payloadKey,
             reinterpret_cast<void*>(payloadWord0),
             reinterpret_cast<void*>(payloadWord8));
    }

    uintptr_t result = 0;
    if (payload == nullptr) {
        result = 0;
    } else if (g_orig_jiagu_token_insert != nullptr) {
        result = g_orig_jiagu_token_insert(manager, owner, payload);
    } else {
        LOGW("JiaguTokenInsert original missing; skipping call");
    }

    uintptr_t rootAfter = 0;
    uintptr_t countAfter = 0;
    uintptr_t managerAddr = reinterpret_cast<uintptr_t>(manager);
    if (managerAddr != 0) {
        read_jiagu_ptr(managerAddr + 0x20, &rootAfter);
        read_jiagu_ptr(managerAddr + 0x28, &countAfter);
    }
    g_jiagu_token_insert_last_manager_root_after.store(rootAfter, std::memory_order_relaxed);
    g_jiagu_token_insert_last_manager_count_after.store(countAfter, std::memory_order_relaxed);

    if (callIndex <= 20 || payloadKey == 59494) {
        LOGW("JiaguTokenInsert leave call=%d result=%p rootAfter=%p treeCountAfter=%zu",
             callIndex,
             reinterpret_cast<void*>(result),
             reinterpret_cast<void*>(rootAfter),
             static_cast<size_t>(countAfter));
    }
    return result;
}

static bool arm64_branch_in_range(uintptr_t from, uintptr_t to, uint32_t* outInsn) {
    int64_t diff = static_cast<int64_t>(to) - static_cast<int64_t>(from);
    if ((diff & 0x3) != 0) return false;
    int64_t imm26 = diff >> 2;
    if (imm26 < -(1LL << 25) || imm26 >= (1LL << 25)) return false;
    if (outInsn != nullptr) {
        *outInsn = 0x14000000u | (static_cast<uint32_t>(imm26) & 0x03ffffffu);
    }
    return true;
}

static void* mmap_near_for_arm64_branch(uintptr_t target, size_t size) {
    int pageSize = sysconf(_SC_PAGESIZE);
    size_t allocSize = (size + static_cast<size_t>(pageSize) - 1) & ~(static_cast<size_t>(pageSize) - 1);
    uintptr_t targetPage = target & ~(static_cast<uintptr_t>(pageSize) - 1);
    constexpr uintptr_t kMaxBranchDistance = 0x07f00000; // Keep margin below B/BL +/-128MB.
    for (uintptr_t delta = static_cast<uintptr_t>(pageSize); delta < kMaxBranchDistance; delta += 0x10000) {
        uintptr_t candidates[2] = {targetPage + delta, targetPage > delta ? targetPage - delta : 0};
        for (uintptr_t hint : candidates) {
            if (hint == 0) continue;
            void* mapped = mmap(
                reinterpret_cast<void*>(hint),
                allocSize,
                PROT_READ | PROT_WRITE | PROT_EXEC,
                MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE,
                -1,
                0);
            if (mapped == MAP_FAILED) {
                continue;
            }
            uint32_t branch = 0;
            if (arm64_branch_in_range(target, reinterpret_cast<uintptr_t>(mapped), &branch)) {
                (void)branch;
                return mapped;
            }
            munmap(mapped, allocSize);
        }
    }

    void* mapped = mmap(
        nullptr,
        allocSize,
        PROT_READ | PROT_WRITE | PROT_EXEC,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0);
    if (mapped != MAP_FAILED) {
        uint32_t branch = 0;
        if (arm64_branch_in_range(target, reinterpret_cast<uintptr_t>(mapped), &branch)) {
            (void)branch;
            return mapped;
        }
        munmap(mapped, allocSize);
    }
    return nullptr;
}

static bool install_manual_entry_hook_with_first_insn_trampoline(
    uintptr_t target,
    uint32_t expectedFirstInsn,
    void* hookFn,
    void** originalOut,
    void** stubOut,
    const char* label,
    const char* source
) {
#if defined(__aarch64__)
    if (target == 0 || hookFn == nullptr || originalOut == nullptr || stubOut == nullptr) return false;
    if (!is_readable_proc_range(target, sizeof(uint32_t))) {
        LOGW("install_manual_entry_hook: target unreadable label=%s source=%s target=%p",
             label ? label : "<null>",
             source ? source : "<null>",
             reinterpret_cast<void*>(target));
        return false;
    }
    uint32_t before = *reinterpret_cast<uint32_t*>(target);
    if (before != expectedFirstInsn) {
        LOGW("install_manual_entry_hook: unexpected first insn label=%s source=%s target=%p before=0x%08x expected=0x%08x",
             label ? label : "<null>",
             source ? source : "<null>",
             reinterpret_cast<void*>(target),
             before,
             expectedFirstInsn);
        return false;
    }

    void* block = mmap_near_for_arm64_branch(target, 32);
    if (block == nullptr) {
        LOGW("install_manual_entry_hook: cannot allocate near block label=%s source=%s target=%p",
             label ? label : "<null>",
             source ? source : "<null>",
             reinterpret_cast<void*>(target));
        return false;
    }

    uintptr_t hookBridge = reinterpret_cast<uintptr_t>(block);
    uintptr_t originalTrampoline = hookBridge + 16;
    auto* hookCode = reinterpret_cast<uint32_t*>(hookBridge);
    hookCode[0] = 0x58000051u; // ldr x17, #8
    hookCode[1] = 0xd61f0220u; // br x17
    *reinterpret_cast<uintptr_t*>(hookBridge + 8) = reinterpret_cast<uintptr_t>(hookFn);

    auto* originalCode = reinterpret_cast<uint32_t*>(originalTrampoline);
    originalCode[0] = expectedFirstInsn;
    uint32_t backBranch = 0;
    if (!arm64_branch_in_range(originalTrampoline + sizeof(uint32_t), target + sizeof(uint32_t), &backBranch)) {
        LOGW("install_manual_entry_hook: original trampoline back branch out of range label=%s target=%p trampoline=%p",
             label ? label : "<null>",
             reinterpret_cast<void*>(target),
             reinterpret_cast<void*>(originalTrampoline));
        munmap(block, 32);
        return false;
    }
    originalCode[1] = backBranch;
    __builtin___clear_cache(
        reinterpret_cast<char*>(block),
        reinterpret_cast<char*>(hookBridge + 32));

    uint32_t branchInsn = 0;
    if (!arm64_branch_in_range(target, hookBridge, &branchInsn)) {
        LOGW("install_manual_entry_hook: hook bridge out of branch range label=%s target=%p hookBridge=%p",
             label ? label : "<null>",
             reinterpret_cast<void*>(target),
             reinterpret_cast<void*>(hookBridge));
        munmap(block, 32);
        return false;
    }
    if (!patch_arm64_instruction(target, 0xffffffffu, expectedFirstInsn, branchInsn)) {
        munmap(block, 32);
        return false;
    }

    *originalOut = reinterpret_cast<void*>(originalTrampoline);
    *stubOut = block;
    LOGW("install_manual_entry_hook: installed label=%s source=%s target=%p hookBridge=%p originalTrampoline=%p branch=0x%08x backBranch=0x%08x",
         label ? label : "<null>",
         source ? source : "<null>",
         reinterpret_cast<void*>(target),
         reinterpret_cast<void*>(hookBridge),
         reinterpret_cast<void*>(originalTrampoline),
         branchInsn,
         backBranch);
    return true;
#else
    (void)target;
    (void)expectedFirstInsn;
    (void)hookFn;
    (void)originalOut;
    (void)stubOut;
    (void)label;
    (void)source;
    return false;
#endif
}

static bool install_jiagu_token_insert_manual_branch_hook(uintptr_t target, const char* source) {
#if defined(__aarch64__)
    constexpr uint32_t kExpectedCbzX2 = 0xb4000b02u; // cbz x2, 0x179bd0
    if (!is_readable_proc_range(target, sizeof(uint32_t))) {
        LOGW("install_jiagu_token_insert_manual_hook: target unreadable source=%s target=%p",
             source ? source : "<null>",
             reinterpret_cast<void*>(target));
        return false;
    }
    uint32_t before = *reinterpret_cast<uint32_t*>(target);
    if (before != kExpectedCbzX2) {
        LOGW("install_jiagu_token_insert_manual_hook: unexpected first insn source=%s target=%p before=0x%08x expected=0x%08x",
             source ? source : "<null>",
             reinterpret_cast<void*>(target),
             before,
             kExpectedCbzX2);
        return false;
    }

    void* trampoline = mmap_near_for_arm64_branch(target, 16);
    if (trampoline == nullptr) {
        LOGW("install_jiagu_token_insert_manual_hook: cannot allocate near trampoline source=%s target=%p",
             source ? source : "<null>",
             reinterpret_cast<void*>(target));
        return false;
    }

    auto* code = reinterpret_cast<uint32_t*>(trampoline);
    code[0] = 0x58000051u; // ldr x17, #8
    code[1] = 0xd61f0220u; // br x17
    *reinterpret_cast<uintptr_t*>(reinterpret_cast<uintptr_t>(trampoline) + 8) =
        reinterpret_cast<uintptr_t>(hooked_jiagu_token_insert);
    __builtin___clear_cache(
        reinterpret_cast<char*>(trampoline),
        reinterpret_cast<char*>(reinterpret_cast<uintptr_t>(trampoline) + 16));

    uint32_t branchInsn = 0;
    if (!arm64_branch_in_range(target, reinterpret_cast<uintptr_t>(trampoline), &branchInsn)) {
        LOGW("install_jiagu_token_insert_manual_hook: trampoline out of branch range target=%p trampoline=%p",
             reinterpret_cast<void*>(target),
             trampoline);
        munmap(trampoline, 16);
        return false;
    }

    if (!patch_arm64_instruction(target, 0xffffffffu, kExpectedCbzX2, branchInsn)) {
        munmap(trampoline, 16);
        return false;
    }

    g_orig_jiagu_token_insert = reinterpret_cast<JiaguTokenInsertFn>(target + sizeof(uint32_t));
    g_jiagu_token_insert_hook_stub = trampoline;
    g_jiagu_token_insert_hook_installed.store(true, std::memory_order_relaxed);
    LOGW("install_jiagu_token_insert_manual_hook: installed source=%s target=%p trampoline=%p branch=0x%08x originalCont=%p",
         source ? source : "<null>",
         reinterpret_cast<void*>(target),
         trampoline,
         branchInsn,
         reinterpret_cast<void*>(target + sizeof(uint32_t)));
    return true;
#else
    (void)target;
    (void)source;
    return false;
#endif
}

static void install_jiagu_token_insert_hook_from_stubapp(const char* source) {
    std::lock_guard<std::mutex> lock(g_jiagu_token_insert_hook_mutex);
    if (g_jiagu_token_insert_hook_installed.load(std::memory_order_relaxed)) {
        return;
    }
    if (g_orig_stub_interface20 == nullptr) {
        LOGW("install_jiagu_token_insert_hook: skip source=%s interface20 missing", source ? source : "<null>");
        return;
    }

    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(g_orig_stub_interface20), &info) == 0 ||
        info.dli_fbase == nullptr ||
        info.dli_fname == nullptr ||
        strstr(info.dli_fname, "libjiagu_vip.so") == nullptr) {
        LOGW("install_jiagu_token_insert_hook: skip source=%s interface20 addr=%s",
             source ? source : "<null>",
             describe_native_address(reinterpret_cast<void*>(g_orig_stub_interface20)).c_str());
        return;
    }

    constexpr uintptr_t kTokenInsertOffset = 0x179a70;
    uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t target = base + kTokenInsertOffset;
    if (!is_readable_proc_range(target, sizeof(uint32_t))) {
        LOGW("install_jiagu_token_insert_hook: target unreadable source=%s target=%p",
             source ? source : "<null>",
             reinterpret_cast<void*>(target));
        return;
    }

    g_jiagu_token_insert_hook_attempts.fetch_add(1, std::memory_order_relaxed);
    void* backup = nullptr;
    void* stub = shadowhook_hook_sym_addr(
        reinterpret_cast<void*>(target),
        reinterpret_cast<void*>(hooked_jiagu_token_insert),
        &backup);
    if (stub == nullptr || backup == nullptr) {
        int err = shadowhook_get_errno();
        g_jiagu_token_insert_hook_failures.fetch_add(1, std::memory_order_relaxed);
        LOGW("install_jiagu_token_insert_hook: failed source=%s target=%p errno=%d(%s)",
             source ? source : "<null>",
             reinterpret_cast<void*>(target),
             err,
             shadowhook_to_errmsg(err));
        if (install_jiagu_token_insert_manual_branch_hook(target, source)) {
            return;
        }
        return;
    }

    g_orig_jiagu_token_insert = reinterpret_cast<JiaguTokenInsertFn>(backup);
    g_jiagu_token_insert_hook_stub = stub;
    g_jiagu_token_insert_hook_installed.store(true, std::memory_order_relaxed);
    LOGW("install_jiagu_token_insert_hook: installed source=%s target=%p original=%p stub=%p base=%p",
         source ? source : "<null>",
         reinterpret_cast<void*>(target),
         backup,
         stub,
         reinterpret_cast<void*>(base));
}

static void install_jiagu_fill_loop_hooks_from_stubapp(const char* source) {
    if (g_orig_stub_interface20 == nullptr) {
        return;
    }
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(g_orig_stub_interface20), &info) == 0 ||
        info.dli_fbase == nullptr ||
        info.dli_fname == nullptr ||
        strstr(info.dli_fname, "libjiagu_vip.so") == nullptr) {
        return;
    }

    uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    if (!g_jiagu_force_post_payload_branch_patched.load(std::memory_order_relaxed)) {
        constexpr uintptr_t kPostPayloadBranchOffset = 0x10ecd4;
        constexpr uint32_t kExpectedPostPayloadBranch = 0x37004ba8u; // tbnz w8, #0, 0x10f648
        constexpr uint32_t kArm64Nop = 0xd503201fu;
        uintptr_t target = base + kPostPayloadBranchOffset;
        if (patch_arm64_instruction(target, 0xffffffffu, kExpectedPostPayloadBranch, kArm64Nop)) {
            g_jiagu_force_post_payload_branch_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: patched post-payload failure branch source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kPostPayloadBranchOffset));
        } else if (is_readable_proc_range(target, sizeof(uint32_t)) &&
                   *reinterpret_cast<uint32_t*>(target) == kArm64Nop) {
            g_jiagu_force_post_payload_branch_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: post-payload failure branch already patched source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kPostPayloadBranchOffset));
        } else {
            LOGW("install_jiagu_fill_loop_hooks: post-payload failure branch patch failed source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kPostPayloadBranchOffset));
        }
    }
    if (!g_jiagu_force_qiniu_gate_patched.load(std::memory_order_relaxed)) {
        constexpr uintptr_t kQiniuGateOffset = 0x10fb20;
        constexpr uint32_t kExpectedQiniuGate = 0x54000a8cu; // b.gt 0x10fc70
        constexpr uint32_t kArm64Nop = 0xd503201fu;
        uintptr_t target = base + kQiniuGateOffset;
        if (patch_arm64_instruction(target, 0xffffffffu, kExpectedQiniuGate, kArm64Nop)) {
            g_jiagu_force_qiniu_gate_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: patched qiniu gate source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kQiniuGateOffset));
        } else if (is_readable_proc_range(target, sizeof(uint32_t)) &&
                   *reinterpret_cast<uint32_t*>(target) == kArm64Nop) {
            g_jiagu_force_qiniu_gate_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: qiniu gate already patched source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kQiniuGateOffset));
        } else {
            LOGW("install_jiagu_fill_loop_hooks: qiniu gate patch failed source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kQiniuGateOffset));
        }
    }

    {
        constexpr uintptr_t kSecondCompareGateOffset = 0x10fc6c;
        constexpr uint32_t kExpectedSecondCompareGate = 0x34000456u; // cbz w22, 0x10fcf4
        constexpr uint32_t kArm64Nop = 0xd503201fu;
        uintptr_t target = base + kSecondCompareGateOffset;
        if (is_readable_proc_range(target, sizeof(uint32_t)) &&
            *reinterpret_cast<uint32_t*>(target) != kArm64Nop) {
            if (patch_arm64_instruction(target, 0xffffffffu, kExpectedSecondCompareGate, kArm64Nop)) {
                LOGW("install_jiagu_fill_loop_hooks: patched second compare gate source=%s target=%p offset=0x%lx",
                     source ? source : "<null>",
                     reinterpret_cast<void*>(target),
                     static_cast<unsigned long>(kSecondCompareGateOffset));
            } else {
                uint32_t actual = is_readable_proc_range(target, sizeof(uint32_t)) ? *reinterpret_cast<uint32_t*>(target) : 0;
                LOGW("install_jiagu_fill_loop_hooks: second compare gate patch failed source=%s target=%p offset=0x%lx insn=0x%08x",
                     source ? source : "<null>",
                     reinterpret_cast<void*>(target),
                     static_cast<unsigned long>(kSecondCompareGateOffset),
                     actual);
            }
        }
    }

    if (!g_jiagu_force_pre_materialize_gate1_patched.load(std::memory_order_relaxed)) {
        constexpr uintptr_t kPreMaterializeGate1Offset = 0x10efcc;
        constexpr uint32_t kExpectedPreMaterializeGate1 = 0x350032f9u; // cbnz w25, 0x10f628
        constexpr uint32_t kArm64Nop = 0xd503201fu;
        uintptr_t target = base + kPreMaterializeGate1Offset;
        if (patch_arm64_instruction(target, 0xffffffffu, kExpectedPreMaterializeGate1, kArm64Nop)) {
            g_jiagu_force_pre_materialize_gate1_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: patched pre-materialize gate1 source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kPreMaterializeGate1Offset));
        } else if (is_readable_proc_range(target, sizeof(uint32_t)) &&
                   *reinterpret_cast<uint32_t*>(target) == kArm64Nop) {
            g_jiagu_force_pre_materialize_gate1_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: pre-materialize gate1 already patched source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kPreMaterializeGate1Offset));
        } else {
            LOGW("install_jiagu_fill_loop_hooks: pre-materialize gate1 patch failed source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kPreMaterializeGate1Offset));
        }
    }
    if (!g_jiagu_force_pre_materialize_gate2_patched.load(std::memory_order_relaxed)) {
        constexpr uintptr_t kPreMaterializeGate2Offset = 0x10efec;
        constexpr uint32_t kExpectedPreMaterializeGate2 = 0x350031f9u; // cbnz w25, 0x10f628
        constexpr uint32_t kArm64Nop = 0xd503201fu;
        uintptr_t target = base + kPreMaterializeGate2Offset;
        if (patch_arm64_instruction(target, 0xffffffffu, kExpectedPreMaterializeGate2, kArm64Nop)) {
            g_jiagu_force_pre_materialize_gate2_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: patched pre-materialize gate2 source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kPreMaterializeGate2Offset));
        } else if (is_readable_proc_range(target, sizeof(uint32_t)) &&
                   *reinterpret_cast<uint32_t*>(target) == kArm64Nop) {
            g_jiagu_force_pre_materialize_gate2_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: pre-materialize gate2 already patched source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kPreMaterializeGate2Offset));
        } else {
            LOGW("install_jiagu_fill_loop_hooks: pre-materialize gate2 patch failed source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kPreMaterializeGate2Offset));
        }
    }

    if (!g_jiagu_force_qiniu_gate_patched.load(std::memory_order_relaxed)) {
        constexpr uintptr_t kQiniuGateOffset = 0x10fb20;
        constexpr uint32_t kExpectedQiniuGate = 0x54000a8cu; // b.gt 0x10fc70
        constexpr uint32_t kArm64Nop = 0xd503201fu;
        uintptr_t target = base + kQiniuGateOffset;
        if (patch_arm64_instruction(target, 0xffffffffu, kExpectedQiniuGate, kArm64Nop)) {
            g_jiagu_force_qiniu_gate_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: patched qiniu gate source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kQiniuGateOffset));
        } else if (is_readable_proc_range(target, sizeof(uint32_t)) &&
                   *reinterpret_cast<uint32_t*>(target) == kArm64Nop) {
            g_jiagu_force_qiniu_gate_patched.store(true, std::memory_order_relaxed);
            LOGW("install_jiagu_fill_loop_hooks: qiniu gate already patched source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kQiniuGateOffset));
        } else {
            LOGW("install_jiagu_fill_loop_hooks: qiniu gate patch failed source=%s target=%p offset=0x%lx",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(target),
                 static_cast<unsigned long>(kQiniuGateOffset));
        }
    }

    if (!g_jiagu_compare_hook_installed.load(std::memory_order_relaxed)) {
        constexpr uintptr_t kCompareGotOffset = 0x246328;
        uintptr_t slot = base + kCompareGotOffset;
        uintptr_t original = 0;
        if (!read_jiagu_ptr(slot, &original) || original == 0) {
            LOGW("install_jiagu_compare_got_hook: unreadable source=%s slot=%p original=%p",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(slot),
                 reinterpret_cast<void*>(original));
        } else {
            int pageSize = sysconf(_SC_PAGESIZE);
            uintptr_t page = slot & ~(static_cast<uintptr_t>(pageSize) - 1);
            if (mprotect(reinterpret_cast<void*>(page), pageSize, PROT_READ | PROT_WRITE) != 0) {
                LOGW("install_jiagu_compare_got_hook: mprotect RW failed source=%s slot=%p errno=%d",
                     source ? source : "<null>",
                     reinterpret_cast<void*>(slot),
                     errno);
            } else {
                auto* ptr = reinterpret_cast<uintptr_t*>(slot);
                if (*ptr == original) {
                    *ptr = reinterpret_cast<uintptr_t>(hooked_jiagu_compare);
                    __builtin___clear_cache(reinterpret_cast<char*>(slot), reinterpret_cast<char*>(slot + sizeof(uintptr_t)));
                    g_orig_jiagu_compare = reinterpret_cast<JiaguCompareFn>(original);
                    g_jiagu_compare_got_slot.store(slot, std::memory_order_relaxed);
                    g_jiagu_compare_hook_installed.store(true, std::memory_order_relaxed);
                    LOGW("install_jiagu_compare_got_hook: installed source=%s slot=%p original=%p hook=%p",
                         source ? source : "<null>",
                         reinterpret_cast<void*>(slot),
                         reinterpret_cast<void*>(original),
                         reinterpret_cast<void*>(hooked_jiagu_compare));
                } else {
                    LOGW("install_jiagu_compare_got_hook: slot changed source=%s slot=%p before=%p current=%p",
                         source ? source : "<null>",
                         reinterpret_cast<void*>(slot),
                         reinterpret_cast<void*>(original),
                         reinterpret_cast<void*>(*ptr));
                }
                if (mprotect(reinterpret_cast<void*>(page), pageSize, PROT_READ) != 0) {
                    LOGW("install_jiagu_compare_got_hook: mprotect R failed source=%s slot=%p errno=%d",
                         source ? source : "<null>",
                         reinterpret_cast<void*>(slot),
                         errno);
                }
            }
        }
    }

    if (!g_jiagu_string_equals_hook_installed.load(std::memory_order_relaxed)) {
        constexpr uintptr_t kStringEqualsGotOffset = 0x2469d8;
        uintptr_t slot = base + kStringEqualsGotOffset;
        uintptr_t original = 0;
        if (!read_jiagu_ptr(slot, &original) || original == 0) {
            LOGW("install_jiagu_string_equals_got_hook: unreadable source=%s slot=%p original=%p",
                 source ? source : "<null>",
                 reinterpret_cast<void*>(slot),
                 reinterpret_cast<void*>(original));
        } else {
            int pageSize = sysconf(_SC_PAGESIZE);
            uintptr_t page = slot & ~(static_cast<uintptr_t>(pageSize) - 1);
            if (mprotect(reinterpret_cast<void*>(page), pageSize, PROT_READ | PROT_WRITE) != 0) {
                LOGW("install_jiagu_string_equals_got_hook: mprotect RW failed source=%s slot=%p errno=%d",
                     source ? source : "<null>",
                     reinterpret_cast<void*>(slot),
                     errno);
            } else {
                auto* ptr = reinterpret_cast<uintptr_t*>(slot);
                if (*ptr == original) {
                    *ptr = reinterpret_cast<uintptr_t>(hooked_jiagu_string_equals);
                    __builtin___clear_cache(reinterpret_cast<char*>(slot), reinterpret_cast<char*>(slot + sizeof(uintptr_t)));
                    g_orig_jiagu_string_equals = reinterpret_cast<JiaguStringEqualsFn>(original);
                    g_jiagu_string_equals_got_slot.store(slot, std::memory_order_relaxed);
                    g_jiagu_string_equals_hook_installed.store(true, std::memory_order_relaxed);
                    LOGW("install_jiagu_string_equals_got_hook: installed source=%s slot=%p original=%p hook=%p",
                         source ? source : "<null>",
                         reinterpret_cast<void*>(slot),
                         reinterpret_cast<void*>(original),
                         reinterpret_cast<void*>(hooked_jiagu_string_equals));
                } else {
                    LOGW("install_jiagu_string_equals_got_hook: slot changed source=%s slot=%p before=%p current=%p",
                         source ? source : "<null>",
                         reinterpret_cast<void*>(slot),
                         reinterpret_cast<void*>(original),
                         reinterpret_cast<void*>(*ptr));
                }
                if (mprotect(reinterpret_cast<void*>(page), pageSize, PROT_READ) != 0) {
                    LOGW("install_jiagu_string_equals_got_hook: mprotect R failed source=%s slot=%p errno=%d",
                         source ? source : "<null>",
                         reinterpret_cast<void*>(slot),
                         errno);
                }
            }
        }
    }

    if (!g_jiagu_env_probe_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x206360,
                0xa9be53f5u,
                reinterpret_cast<void*>(hooked_jiagu_env_probe),
                &original,
                &stub,
                "env-probe-0x206360",
                source)) {
            g_orig_jiagu_env_probe = reinterpret_cast<JiaguEnvProbeFn>(original);
            g_jiagu_env_probe_hook_stub = stub;
            g_jiagu_env_probe_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_qiniu_check_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x123438,
                0xf81d0ff6u,
                reinterpret_cast<void*>(hooked_jiagu_qiniu_check),
                &original,
                &stub,
                "qiniu-check-0x123438",
                source)) {
            g_orig_jiagu_qiniu_check = reinterpret_cast<JiaguQiniuCheckFn>(original);
            g_jiagu_qiniu_check_hook_stub = stub;
            g_jiagu_qiniu_check_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_interface20_register_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x10d468,
                0xa9ba6ffcu,
                reinterpret_cast<void*>(hooked_jiagu_interface20_register),
                &original,
                &stub,
                "interface20-register-0x10d468",
                source)) {
            g_orig_jiagu_interface20_register = reinterpret_cast<JiaguInterface20RegisterFn>(original);
            g_jiagu_interface20_register_hook_stub = stub;
            g_jiagu_interface20_register_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_payload_build_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x1298d0,
                0xd102c3ffu,
                reinterpret_cast<void*>(hooked_jiagu_payload_build),
                &original,
                &stub,
                "payload-build-0x1298d0",
                source)) {
            g_orig_jiagu_payload_build = reinterpret_cast<JiaguPayloadBuildFn>(original);
            g_jiagu_payload_build_hook_stub = stub;
            g_jiagu_payload_build_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_payload_check_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x129c58,
                0xd10403ffu,
                reinterpret_cast<void*>(hooked_jiagu_payload_check),
                &original,
                &stub,
                "payload-check-0x129c58",
                source)) {
            g_orig_jiagu_payload_check = reinterpret_cast<JiaguPayloadCheckFn>(original);
            g_jiagu_payload_check_hook_stub = stub;
            g_jiagu_payload_check_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_post_payload_status_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x123020,
                0xd10443ffu,
                reinterpret_cast<void*>(hooked_jiagu_post_payload_status),
                &original,
                &stub,
                "post-payload-status-0x123020",
                source)) {
            g_orig_jiagu_post_payload_status = reinterpret_cast<JiaguPostPayloadStatusFn>(original);
            g_jiagu_post_payload_status_hook_stub = stub;
            g_jiagu_post_payload_status_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_post_payload_object_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x116c94,
                0xf81c0ff8u,
                reinterpret_cast<void*>(hooked_jiagu_post_payload_object),
                &original,
                &stub,
                "post-payload-object-0x116c94",
                source)) {
            g_orig_jiagu_post_payload_object = reinterpret_cast<JiaguPostPayloadObjectFn>(original);
            g_jiagu_post_payload_object_hook_stub = stub;
            g_jiagu_post_payload_object_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_post_payload_materialize_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x186f64,
                0xd10583ffu,
                reinterpret_cast<void*>(hooked_jiagu_post_payload_materialize),
                &original,
                &stub,
                "post-payload-materialize-0x186f64",
                source)) {
            g_orig_jiagu_post_payload_materialize = reinterpret_cast<JiaguPostPayloadMaterializeFn>(original);
            g_jiagu_post_payload_materialize_hook_stub = stub;
            g_jiagu_post_payload_materialize_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_after_materialize_normalize_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x187900,
                0xd10343ffu,
                reinterpret_cast<void*>(hooked_jiagu_after_materialize_normalize),
                &original,
                &stub,
                "after-materialize-normalize-0x187900",
                source)) {
            g_orig_jiagu_after_materialize_normalize = reinterpret_cast<JiaguAfterMaterializeNormalizeFn>(original);
            g_jiagu_after_materialize_normalize_hook_stub = stub;
            g_jiagu_after_materialize_normalize_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_build_register_vector_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x119fa8,
                0xd10243ffu,
                reinterpret_cast<void*>(hooked_jiagu_build_register_vector),
                &original,
                &stub,
                "build-register-vector-0x119fa8",
                source)) {
            g_orig_jiagu_build_register_vector = reinterpret_cast<JiaguBuildRegisterVectorFn>(original);
            g_jiagu_build_register_vector_hook_stub = stub;
            g_jiagu_build_register_vector_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_token_manager_init_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x179898,
                0xa9bf7bf3u,
                reinterpret_cast<void*>(hooked_jiagu_token_manager_init),
                &original,
                &stub,
                "token-manager-init-0x179898",
                source)) {
            g_orig_jiagu_token_manager_init = reinterpret_cast<JiaguTokenManagerInitFn>(original);
            g_jiagu_token_manager_init_hook_stub = stub;
            g_jiagu_token_manager_init_hook_installed.store(true, std::memory_order_relaxed);
        }
    }

    if (!g_jiagu_register_gate_hook_installed.load(std::memory_order_relaxed)) {
        void* original = nullptr;
        void* stub = nullptr;
        if (install_manual_entry_hook_with_first_insn_trampoline(
                base + 0x17ac6c,
                0xd10183ffu,
                reinterpret_cast<void*>(hooked_jiagu_register_gate),
                &original,
                &stub,
                "register-gate-0x17ac6c",
                source)) {
            g_orig_jiagu_register_gate = reinterpret_cast<JiaguRegisterGateFn>(original);
            g_jiagu_register_gate_hook_stub = stub;
            g_jiagu_register_gate_hook_installed.store(true, std::memory_order_relaxed);
        }
    }
}

static void append_registry_node_summary(std::string& out, uintptr_t node, int index) {
    uintptr_t left = 0;
    uintptr_t right = 0;
    uintptr_t value = 0;
    read_jiagu_ptr(node, &left);
    read_jiagu_ptr(node + 0x8, &right);
    read_jiagu_ptr(node + 0x38, &value);

    char buf[256];
    snprintf(buf, sizeof(buf), " node%d=%p key=%s value=%p left=%p right=%p",
             index,
             (void*)node,
             read_jiagu_libcpp_string(node + 0x20).c_str(),
             (void*)value,
             (void*)left,
             (void*)right);
    out += buf;
}

static std::string build_jiagu_registry_diag(uintptr_t base) {
    uintptr_t registrySlot = base + 0x2531b0;
    uintptr_t registry = 0;
    if (!read_jiagu_ptr(registrySlot, &registry)) {
        char buf[160];
        snprintf(buf, sizeof(buf), "registrySlot=%p unreadable", (void*)registrySlot);
        return buf;
    }

    std::string out;
    char header[512];
    if (registry == 0) {
        snprintf(header, sizeof(header), "registrySlot=%p registry=null", (void*)registrySlot);
        out = header;
    } else {
        uintptr_t sentinel = 0;
        uintptr_t root = 0;
        uintptr_t count = 0;
        uintptr_t context = 0;
        read_jiagu_ptr(registry, &sentinel);
        read_jiagu_ptr(registry + 0x8, &root);
        read_jiagu_ptr(registry + 0x10, &count);
        read_jiagu_ptr(registry + 0x18, &context);
        snprintf(header, sizeof(header),
                 "registrySlot=%p registry=%p sentinel=%p root=%p count=%zu context=%p",
                 (void*)registrySlot,
                 (void*)registry,
                 (void*)sentinel,
                 (void*)root,
                 static_cast<size_t>(count),
                 (void*)context);
        out = header;

        struct PendingNode { uintptr_t node; int depth; };
        std::vector<PendingNode> stack;
        if (root != 0 && is_readable_proc_range(root, 0x40)) stack.push_back({root, 0});
        int emitted = 0;
        while (!stack.empty() && emitted < 6) {
            PendingNode current = stack.back();
            stack.pop_back();
            if (current.node == 0 || current.depth > 8 || !is_readable_proc_range(current.node, 0x40)) {
                continue;
            }
            append_registry_node_summary(out, current.node, emitted);
            ++emitted;

            uintptr_t left = 0;
            uintptr_t right = 0;
            read_jiagu_ptr(current.node, &left);
            read_jiagu_ptr(current.node + 0x8, &right);
            if (right != 0) stack.push_back({right, current.depth + 1});
            if (left != 0) stack.push_back({left, current.depth + 1});
        }
    }

    out += " seeds=";
    for (int i = 0; i < 4; ++i) {
        uintptr_t entry = base + 0x253150 + static_cast<uintptr_t>(i) * 0x18;
        uintptr_t keyPtr = 0;
        uintptr_t valuePtr = 0;
        read_jiagu_ptr(entry, &keyPtr);
        read_jiagu_ptr(entry + 0x8, &valuePtr);
        char seed[220];
        snprintf(seed, sizeof(seed), "%s%d:{keyPtr=%p key=%s value=%p}",
                 i == 0 ? "" : ",",
                 i,
                 (void*)keyPtr,
                 read_jiagu_c_string(keyPtr).c_str(),
                 (void*)valuePtr);
        out += seed;
    }
    return out;
}

static std::string build_jiagu_token_diag(int token) {
    if (g_orig_stub_interface11 == nullptr) {
        return "interface11=missing";
    }

    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(g_orig_stub_interface11), &info) == 0 || info.dli_fbase == nullptr) {
        return "dladdr=failed";
    }

    uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t globalSlot = base + 0x253148;
    uintptr_t manager = 0;
    if (!read_jiagu_ptr(globalSlot, &manager)) {
        char buffer[256];
        snprintf(buffer, sizeof(buffer), "base=%p globalSlot=%p unreadable", (void*)base, (void*)globalSlot);
        return buffer;
    }
    if (manager == 0) {
        char buffer[256];
        snprintf(buffer, sizeof(buffer), "base=%p globalSlot=%p manager=null", (void*)base, (void*)globalSlot);
        return buffer;
    }

    uintptr_t root = 0;
    uintptr_t treeCount = 0;
    uintptr_t allBegin = 0;
    uintptr_t allEnd = 0;
    uintptr_t allCap = 0;
    read_jiagu_ptr(manager + 0x20, &root);
    read_jiagu_ptr(manager + 0x28, &treeCount);
    read_jiagu_ptr(manager + 0x70, &allBegin);
    read_jiagu_ptr(manager + 0x78, &allEnd);
    read_jiagu_ptr(manager + 0x80, &allCap);

    uintptr_t node = root;
    uintptr_t found = 0;
    uint32_t foundKey = 0;
    int steps = 0;
    while (node != 0 && steps < 128) {
        uintptr_t left = 0;
        uintptr_t right = 0;
        uint32_t key = 0;
        if (!read_jiagu_u32(node + 0x20, &key)) break;
        if (static_cast<int32_t>(key) == token) {
            found = node;
            foundKey = key;
            break;
        }
        read_jiagu_ptr(node, &left);
        read_jiagu_ptr(node + 0x8, &right);
        node = token < static_cast<int32_t>(key) ? left : right;
        steps++;
    }

    uintptr_t payload = 0;
    uintptr_t payloadHead = 0;
    uintptr_t payloadBegin = 0;
    uintptr_t payloadEnd = 0;
    uintptr_t payloadCap = 0;
    uintptr_t payloadCount = 0;
    uintptr_t firstEntry = 0;
    if (found != 0) {
        read_jiagu_ptr(found + 0x28, &payload);
    }
    if (payload != 0) {
        read_jiagu_ptr(payload, &payloadHead);
        read_jiagu_ptr(payload + 0x8, &payloadBegin);
        read_jiagu_ptr(payload + 0x10, &payloadEnd);
        read_jiagu_ptr(payload + 0x18, &payloadCap);
        if (payloadEnd >= payloadBegin) payloadCount = (payloadEnd - payloadBegin) / sizeof(uintptr_t);
        if (payloadBegin != 0) read_jiagu_ptr(payloadBegin, &firstEntry);
    }

    uintptr_t allCount = 0;
    if (allEnd >= allBegin) allCount = (allEnd - allBegin) / 16;

    std::string registry = build_jiagu_registry_diag(base);
    std::string insertHook = build_jiagu_token_insert_hook_diag();
    std::string fillHook = build_jiagu_fill_loop_hook_diag();
    char buffer[4096];
    snprintf(
        buffer,
        sizeof(buffer),
        "base=%p manager=%p root=%p treeCount=%zu allVec=%p/%p/%p allCount=%zu "
        "token=%d steps=%d node=%p key=%u payload=%p payloadHead=%p payloadVec=%p/%p/%p payloadCount=%zu firstEntry=%p %s %s registry={%s}",
        (void*)base,
        (void*)manager,
        (void*)root,
        static_cast<size_t>(treeCount),
        (void*)allBegin,
        (void*)allEnd,
        (void*)allCap,
        static_cast<size_t>(allCount),
        token,
        steps,
        (void*)found,
        foundKey,
        (void*)payload,
        (void*)payloadHead,
        (void*)payloadBegin,
        (void*)payloadEnd,
        (void*)payloadCap,
        static_cast<size_t>(payloadCount),
        (void*)firstEntry,
        insertHook.c_str(),
        fillHook.c_str(),
        registry.c_str());
    return buffer;
}

JNIEXPORT jstring JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeGetJiaguTokenDiag(
    JNIEnv* env, jobject thiz, jint value)
{
    (void)thiz;
    std::string report = build_jiagu_token_diag(static_cast<int>(value));
    return env->NewStringUTF(report.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeCallOriginalStubInterface11(
    JNIEnv* env, jobject thiz, jobject classLoader, jstring className, jint value)
{
    (void)thiz;
    if (classLoader == nullptr || className == nullptr) return JNI_FALSE;
    if (g_orig_stub_interface11 == nullptr) {
        LOGW("nativeCallOriginalStubInterface11: original interface11 is not captured");
        return JNI_FALSE;
    }

    const char* name = env->GetStringUTFChars(className, nullptr);
    if (name == nullptr) return JNI_FALSE;

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    if (clClass == nullptr) {
        env->ReleaseStringUTFChars(className, name);
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeCallOriginalStubInterface11: ClassLoader class not found");
        return JNI_FALSE;
    }

    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (loadClass == nullptr) {
        env->ReleaseStringUTFChars(className, name);
        env->DeleteLocalRef(clClass);
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeCallOriginalStubInterface11: ClassLoader.loadClass not found");
        return JNI_FALSE;
    }

    jstring jName = env->NewStringUTF(name);
    env->ReleaseStringUTFChars(className, name);
    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, jName);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(clClass);

    if (targetClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeCallOriginalStubInterface11: StubApp class not found via guest ClassLoader");
        return JNI_FALSE;
    }

    LOGW(
        "nativeCallOriginalStubInterface11: begin value=%d original=%s",
        value,
        describe_native_address((void*)g_orig_stub_interface11).c_str());
    g_orig_stub_interface11(env, targetClass, value);
    env->DeleteLocalRef(targetClass);

    if (clear_logged_exception(env, "nativeCallOriginalStubInterface11 original")) {
        LOGW("nativeCallOriginalStubInterface11: original threw value=%d", value);
        return JNI_FALSE;
    }

    LOGW(
        "nativeCallOriginalStubInterface11: completed value=%d pwdLogin=%s sendPhoneCode=%s qrCodeV2=%s",
        value,
        g_orig_ywlogin_pwdLogin != nullptr ? "bound" : "missing",
        g_orig_ywlogin_sendPhoneCode != nullptr ? "bound" : "missing",
        g_orig_ywlogin_qrCodeV2 != nullptr ? "bound" : "missing");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeCallOriginalStubInterface5(
    JNIEnv* env, jobject thiz, jobject classLoader, jstring className, jobject application)
{
    (void)thiz;
    if (classLoader == nullptr || className == nullptr || application == nullptr) return JNI_FALSE;
    if (g_orig_stub_interface5 == nullptr) {
        LOGW("nativeCallOriginalStubInterface5: original interface5 is not captured");
        return JNI_FALSE;
    }

    const char* name = env->GetStringUTFChars(className, nullptr);
    if (name == nullptr) return JNI_FALSE;

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    if (clClass == nullptr) {
        env->ReleaseStringUTFChars(className, name);
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeCallOriginalStubInterface5: ClassLoader class not found");
        return JNI_FALSE;
    }

    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (loadClass == nullptr) {
        env->ReleaseStringUTFChars(className, name);
        env->DeleteLocalRef(clClass);
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeCallOriginalStubInterface5: ClassLoader.loadClass not found");
        return JNI_FALSE;
    }

    jstring jName = env->NewStringUTF(name);
    env->ReleaseStringUTFChars(className, name);
    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, jName);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(clClass);

    if (targetClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeCallOriginalStubInterface5: StubApp class not found via guest ClassLoader");
        return JNI_FALSE;
    }

    LOGW(
        "nativeCallOriginalStubInterface5: begin original=%s pwdLogin=%s sendPhoneCode=%s qrCodeV2=%s",
        describe_native_address((void*)g_orig_stub_interface5).c_str(),
        g_orig_ywlogin_pwdLogin != nullptr ? "bound" : "missing",
        g_orig_ywlogin_sendPhoneCode != nullptr ? "bound" : "missing",
        g_orig_ywlogin_qrCodeV2 != nullptr ? "bound" : "missing");
    g_orig_stub_interface5(env, targetClass, application);
    env->DeleteLocalRef(targetClass);

    if (clear_logged_exception(env, "nativeCallOriginalStubInterface5 original")) {
        LOGW("nativeCallOriginalStubInterface5: original threw");
        return JNI_FALSE;
    }

    LOGW(
        "nativeCallOriginalStubInterface5: completed pwdLogin=%s sendPhoneCode=%s qrCodeV2=%s",
        g_orig_ywlogin_pwdLogin != nullptr ? "bound" : "missing",
        g_orig_ywlogin_sendPhoneCode != nullptr ? "bound" : "missing",
        g_orig_ywlogin_qrCodeV2 != nullptr ? "bound" : "missing");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeCallOriginalStubInterface20(
    JNIEnv* env, jobject thiz, jobject classLoader, jstring className)
{
    (void)thiz;
    if (classLoader == nullptr || className == nullptr) return JNI_FALSE;
    if (g_orig_stub_interface20 == nullptr) {
        LOGW("nativeCallOriginalStubInterface20: original interface20 is not captured");
        return JNI_FALSE;
    }
    install_jiagu_token_insert_hook_from_stubapp("nativeCallOriginalStubInterface20");
    install_jiagu_fill_loop_hooks_from_stubapp("nativeCallOriginalStubInterface20");

    const char* name = env->GetStringUTFChars(className, nullptr);
    if (name == nullptr) return JNI_FALSE;

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    if (clClass == nullptr) {
        env->ReleaseStringUTFChars(className, name);
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeCallOriginalStubInterface20: ClassLoader class not found");
        return JNI_FALSE;
    }

    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (loadClass == nullptr) {
        env->ReleaseStringUTFChars(className, name);
        env->DeleteLocalRef(clClass);
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeCallOriginalStubInterface20: ClassLoader.loadClass not found");
        return JNI_FALSE;
    }

    jstring jName = env->NewStringUTF(name);
    env->ReleaseStringUTFChars(className, name);
    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, jName);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(clClass);

    if (targetClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeCallOriginalStubInterface20: StubApp class not found via guest ClassLoader");
        return JNI_FALSE;
    }

    std::string before = build_jiagu_token_diag(59494);
    LOGW(
        "nativeCallOriginalStubInterface20: begin original=%s tokenBefore=%s",
        describe_native_address((void*)g_orig_stub_interface20).c_str(),
        before.c_str());
    jboolean result = g_orig_stub_interface20(env, targetClass);
    env->DeleteLocalRef(targetClass);

    if (clear_logged_exception(env, "nativeCallOriginalStubInterface20 original")) {
        LOGW("nativeCallOriginalStubInterface20: original threw tokenAfter=%s", build_jiagu_token_diag(59494).c_str());
        return JNI_FALSE;
    }

    LOGW(
        "nativeCallOriginalStubInterface20: completed result=%d tokenAfter=%s",
        result ? 1 : 0,
        build_jiagu_token_diag(59494).c_str());
    return result ? JNI_TRUE : JNI_FALSE;
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
        if (!g_initialized.load()) {
            LOGI("nativeSetupForLoader: initializing shadowhook...");
            bool shadowhookReady = init_shadowhook_for_runtime("nativeSetupForLoader");
            g_hooks_installed = shadowhookReady && install_shadowhook_hooks();
            g_initialized.store(true);
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

JNIEXPORT void JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeSetSuppressSelfSigkill(
    JNIEnv* env, jclass clazz, jboolean enabled)
{
    (void)env; (void)clazz;
    g_suppress_self_sigkill.store(enabled == JNI_TRUE, std::memory_order_relaxed);
    LOGW("nativeSetSuppressSelfSigkill: enabled=%d", enabled == JNI_TRUE);
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
static orig_kill_t got_orig_kill = nullptr;
static orig_tgkill_t got_orig_tgkill = nullptr;
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

static pid_t current_tid_for_signal_check() {
#if defined(__NR_gettid)
    return static_cast<pid_t>(syscall(__NR_gettid));
#else
    return getpid();
#endif
}

static bool should_suppress_self_sigkill(pid_t tgid, pid_t tid, int sig) {
    if (sig != SIGKILL) return false;
    if (!g_suppress_self_sigkill.load(std::memory_order_relaxed)) return false;
    pid_t self_pid = getpid();
    pid_t self_tid = current_tid_for_signal_check();
    return tgid == self_pid && (tid == self_tid || tid == self_pid);
}

static int got_hooked_kill(pid_t pid, int sig) {
    void* caller = __builtin_return_address(0);
    LOGW("GOT kill intercepted: pid=%d sig=%d caller=%s",
         static_cast<int>(pid), sig, describe_native_address(caller).c_str());
    if (should_suppress_self_sigkill(pid, current_tid_for_signal_check(), sig)) {
        LOGW("GOT kill intercepted: suppressing self SIGKILL");
        return 0;
    }
    if (got_orig_kill) return got_orig_kill(pid, sig);
    errno = ENOSYS;
    return -1;
}

static int got_hooked_tgkill(pid_t tgid, pid_t tid, int sig) {
    void* caller = __builtin_return_address(0);
    LOGW("GOT tgkill intercepted: tgid=%d tid=%d sig=%d caller=%s",
         static_cast<int>(tgid), static_cast<int>(tid), sig, describe_native_address(caller).c_str());
    if (sig == SIGKILL && patch_jiagu_self_kill_from_return_address(caller)) {
        LOGW("GOT tgkill intercepted: suppressing exact jiagu self-kill caller");
        return 0;
    }
    if (should_suppress_self_sigkill(tgid, tid, sig)) {
        LOGW("GOT tgkill intercepted: suppressing self SIGKILL");
        return 0;
    }
    if (got_orig_tgkill) return got_orig_tgkill(tgid, tid, sig);
    errno = ENOSYS;
    return -1;
}

// GOT hook: 修改指定库的 GOT 表
// hook 策略：对目标库（壳库）和 libc.so 都进行 hook
// - 壳库 hook：拦截壳自身 PLT 调用
// - libc hook：拦截壳通过 libc PLT 的调用（覆盖 constructor 时序问题）
static void patch_loaded_jiagu_vip_self_kill_callsites();

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
                else if (strcmp(sym_name, "kill") == 0) {
                    if (patch_entry((void*)got_hooked_kill, (void**)&got_orig_kill)) hooked++;
                }
                else if (strcmp(sym_name, "tgkill") == 0) {
                    if (patch_entry((void*)got_hooked_tgkill, (void**)&got_orig_tgkill)) hooked++;
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
    if (strstr(name, "libjiagu_vip.so") != nullptr) {
        uintptr_t base = find_loaded_library_base(name);
        if (base != 0) {
            patch_jiagu_vip_env_check(base, name);
        } else {
            LOGW("nativeGotHookLibrary: cannot find base for %s before env-check patch", name);
        }
        patch_loaded_jiagu_vip_self_kill_callsites();
        dump_decrypted_jiagu_code();
    }
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
    uintptr_t kill_offset;
    uintptr_t tgkill_offset;
    bool has_open;
    bool has_openat;
    bool has_fopen;
    bool has_readlink;
    bool has_exit;
    bool has__exit;
    bool has_abort;
    bool has_kill;
    bool has_tgkill;
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

static const char* multiapp_get_method_shorty(JNIEnv* env, jmethodID mid);

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
    if (!prefix_match && (symbol_name == "_ZN3artL15GetMethodShortyEP7_JNIEnvP10_jmethodID" ||
        symbol_name == "_ZN3art15GetMethodShortyEP7_JNIEnvP10_jmethodID")) {
        LOGW("libart resolver: using MultiApp GetMethodShorty fallback for %.*s", (int)symbol_name.size(), symbol_name.data());
        return reinterpret_cast<void*>(multiapp_get_method_shorty);
    }

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

        if (strtab_file_off >= (uintptr_t)st.st_size ||
            symtab_file_off >= (uintptr_t)st.st_size ||
            jmprel_file_off >= (uintptr_t)st.st_size) {
            LOGE("ELF offset out of bounds, skipping GOT parse");
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
            else if (strcmp(name, "kill") == 0) { info.kill_offset = r_offset; info.has_kill = true; }
            else if (strcmp(name, "tgkill") == 0) { info.tgkill_offset = r_offset; info.has_tgkill = true; }
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

static bool patch_arm64_instruction(uintptr_t address, uint32_t expected_mask, uint32_t expected_value, uint32_t replacement) {
    uint32_t* instruction = reinterpret_cast<uint32_t*>(address);
    uint32_t before = *instruction;
    bool matches = (before & expected_mask) == expected_value;
    LOGW("patch_arm64_instruction: addr=%p before=0x%08x mask=0x%08x expected=0x%08x matches=%d",
         reinterpret_cast<void*>(address), before, expected_mask, expected_value, matches);
    if (!matches) {
        return false;
    }

    int page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page = address & ~(static_cast<uintptr_t>(page_size) - 1);
    if (mprotect(reinterpret_cast<void*>(page), page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGW("patch_arm64_instruction: mprotect RWX failed addr=%p errno=%d",
             reinterpret_cast<void*>(address), errno);
        return false;
    }
    *instruction = replacement;
    __builtin___clear_cache(reinterpret_cast<char*>(address), reinterpret_cast<char*>(address + sizeof(uint32_t)));
    if (mprotect(reinterpret_cast<void*>(page), page_size, PROT_READ | PROT_EXEC) != 0) {
        LOGW("patch_arm64_instruction: mprotect RX failed addr=%p errno=%d",
             reinterpret_cast<void*>(address), errno);
    }
    LOGW("patch_arm64_instruction: patched addr=%p 0x%08x -> 0x%08x",
         reinterpret_cast<void*>(address), before, replacement);
    return true;
}

struct ProcMapEntry {
    bool found;
    uintptr_t start;
    uintptr_t end;
    char perms[8];
    char line[1024];
};

struct ProcMapRange {
    uintptr_t start;
    uintptr_t end;
    char perms[8];
    char line[1024];
};

static FILE* open_proc_self_maps_for_read() {
    FILE* maps = nullptr;
    if (real_fopen != nullptr) {
        maps = real_fopen("/proc/self/maps", "r");
    }
    if (maps == nullptr) {
        maps = fopen("/proc/self/maps", "r");
    }
    return maps;
}

static ProcMapEntry find_proc_map_entry(uintptr_t address) {
    ProcMapEntry entry{};
    FILE* maps = open_proc_self_maps_for_read();
    if (maps == nullptr) {
        LOGW("find_proc_map_entry: cannot open /proc/self/maps errno=%d", errno);
        return entry;
    }

    char line[sizeof(entry.line)] = {0};
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long start = 0;
        unsigned long end = 0;
        char perms[sizeof(entry.perms)] = {0};
        if (sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) {
            continue;
        }
        uintptr_t mapStart = static_cast<uintptr_t>(start);
        uintptr_t mapEnd = static_cast<uintptr_t>(end);
        if (address >= mapStart && address < mapEnd) {
            entry.found = true;
            entry.start = mapStart;
            entry.end = mapEnd;
            strncpy(entry.perms, perms, sizeof(entry.perms) - 1);
            strncpy(entry.line, line, sizeof(entry.line) - 1);
            size_t len = strlen(entry.line);
            if (len > 0 && entry.line[len - 1] == '\n') {
                entry.line[len - 1] = '\0';
            }
            break;
        }
    }
    fclose(maps);
    return entry;
}

static bool patch_jiagu_vip_env_check(uintptr_t base, const char* path) {
#if defined(__aarch64__)
    char prop[PROP_VALUE_MAX] = {0};
    int len = __system_property_get("debug.multiapp.patch_jiagu", prop);
    if (len <= 0 || strcmp(prop, "1") != 0) {
        LOGI("patch_jiagu_vip_env_check: disabled prop=%s path=%s", len > 0 ? prop : "", path ? path : "null");
        return false;
    }

    constexpr uintptr_t kEnvCheckOffset = 0x25bde4;
    constexpr uint32_t kMovW0Zero = 0x52800000; // mov w0, #0
    constexpr uint32_t kRet = 0xd65f03c0;       // ret

    uintptr_t target = base + kEnvCheckOffset;
    ProcMapEntry entry = find_proc_map_entry(target);
    if (!entry.found) {
        LOGW("patch_jiagu_vip_env_check: target map missing base=%p target=%p path=%s",
             (void*)base, (void*)target, path ? path : "null");
        return false;
    }

    LOGW("patch_jiagu_vip_env_check: enabled base=%p target=%p map=%s path=%s",
         (void*)base, (void*)target, entry.line, path ? path : "null");
    bool patched_mov = patch_arm64_instruction(target, 0x00000000u, 0x00000000u, kMovW0Zero);
    bool patched_ret = patch_arm64_instruction(target + sizeof(uint32_t), 0x00000000u, 0x00000000u, kRet);
    LOGW("patch_jiagu_vip_env_check: patched=%d mov=%d ret=%d target=%p",
         patched_mov && patched_ret, patched_mov, patched_ret, (void*)target);
    return patched_mov && patched_ret;
#else
    (void)base;
    (void)path;
    return false;
#endif
}

static std::vector<ProcMapRange> read_proc_map_ranges() {
    std::vector<ProcMapRange> ranges;
    FILE* maps = open_proc_self_maps_for_read();
    if (maps == nullptr) {
        LOGW("read_proc_map_ranges: cannot open /proc/self/maps errno=%d", errno);
        return ranges;
    }

    char line[1024] = {0};
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long start = 0;
        unsigned long end = 0;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &start, &end, perms) != 3) {
            continue;
        }
        ProcMapRange range{};
        range.start = static_cast<uintptr_t>(start);
        range.end = static_cast<uintptr_t>(end);
        strncpy(range.perms, perms, sizeof(range.perms) - 1);
        strncpy(range.line, line, sizeof(range.line) - 1);
        size_t len = strlen(range.line);
        if (len > 0 && range.line[len - 1] == '\n') {
            range.line[len - 1] = '\0';
        }
        ranges.push_back(range);
    }
    fclose(maps);
    return ranges;
}

static bool is_executable_proc_map(const ProcMapEntry& entry) {
    return entry.found && strchr(entry.perms, 'x') != nullptr;
}

static bool is_readable_proc_range(uintptr_t address, size_t length) {
    if (length == 0) return true;
    if (address > UINTPTR_MAX - length) return false;
    ProcMapEntry entry = find_proc_map_entry(address);
    return entry.found &&
        strchr(entry.perms, 'r') != nullptr &&
        address + length <= entry.end;
}

static bool read_u32x4_if_readable(uintptr_t address, uint32_t out[4]) {
    if (!is_readable_proc_range(address, sizeof(uint32_t) * 4)) {
        return false;
    }
    memcpy(out, reinterpret_cast<void*>(address), sizeof(uint32_t) * 4);
    return true;
}

static int64_t sign_extend_u64(uint64_t value, int bits) {
    uint64_t signBit = 1ULL << (bits - 1);
    return static_cast<int64_t>((value ^ signBit) - signBit);
}

static void log_aarch64_branch_target(const char* label, uintptr_t base, uintptr_t pc, uint32_t insn) {
    uint32_t op = insn & 0xfc000000u;
    if (op != 0x94000000u && op != 0x14000000u) {
        return;
    }
    int64_t imm = sign_extend_u64(insn & 0x03ffffffu, 26) << 2;
    uintptr_t target = static_cast<uintptr_t>(static_cast<intptr_t>(pc) + imm);
    const char* kind = op == 0x94000000u ? "BL" : "B";
    if (target >= base) {
        LOGI("dump_decrypted: %s branch pcOff=0x%lx %s targetOff=0x%lx insn=0x%08x",
             label ? label : "<unknown>",
             (unsigned long)(pc - base),
             kind,
             (unsigned long)(target - base),
             insn);
    } else {
        LOGI("dump_decrypted: %s branch pc=%p %s target=%p insn=0x%08x",
             label ? label : "<unknown>",
             (void*)pc,
             kind,
             (void*)target,
             insn);
    }
}

static bool patch_jiagu_self_kill_from_return_address(void* caller) {
    if (caller == nullptr) return false;
    Dl_info info{};
    if (dladdr(caller, &info) == 0 || info.dli_fname == nullptr || info.dli_fbase == nullptr) {
        return false;
    }
    if (strstr(info.dli_fname, "libjiagu_vip.so") == nullptr) {
        return false;
    }
    uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    uintptr_t pc = reinterpret_cast<uintptr_t>(caller);
    if (pc < base) return false;
    uintptr_t offset = pc - base;
    if (offset != 0x11cb88) {
        return false;
    }

    uintptr_t callsite = pc - sizeof(uint32_t);
    ProcMapEntry callerMap = find_proc_map_entry(pc);
    ProcMapEntry callsiteMap = find_proc_map_entry(callsite);
    LOGW("patch_jiagu_self_kill_from_return_address: exact self-kill caller=%p base=%p offset=0x%lx callsite=%p",
         caller,
         reinterpret_cast<void*>(base),
         static_cast<unsigned long>(offset),
         reinterpret_cast<void*>(callsite));
    LOGW("patch_jiagu_self_kill_from_return_address: caller_map found=%d exec=%d line=%s",
         callerMap.found ? 1 : 0,
         is_executable_proc_map(callerMap) ? 1 : 0,
         callerMap.found ? callerMap.line : "<none>");
    LOGW("patch_jiagu_self_kill_from_return_address: callsite_map found=%d exec=%d line=%s",
         callsiteMap.found ? 1 : 0,
         is_executable_proc_map(callsiteMap) ? 1 : 0,
         callsiteMap.found ? callsiteMap.line : "<none>");

    if (!is_executable_proc_map(callsiteMap)) {
        LOGW("patch_jiagu_self_kill_from_return_address: callsite is not in executable mapping; not suppressing");
        return false;
    }

    uint32_t previous = *reinterpret_cast<uint32_t*>(callsite - sizeof(uint32_t));
    uint32_t before = *reinterpret_cast<uint32_t*>(callsite);
    uint32_t next = *reinterpret_cast<uint32_t*>(callsite + sizeof(uint32_t));
    LOGW("patch_jiagu_self_kill_from_return_address: instructions prev=0x%08x insn=0x%08x next=0x%08x",
         previous, before, next);

    constexpr uint32_t arm64_nop = 0xd503201f;
    bool patched = patch_arm64_instruction(callsite, 0xfc000000u, 0x94000000u, arm64_nop);
    if (!patched) {
        patched = patch_arm64_instruction(callsite, 0xfffffc1fu, 0xd63f0000u, arm64_nop);
    }
    if (!patched) {
        LOGW("patch_jiagu_self_kill_from_return_address: caller-4 is not BL/BLR; not suppressing");
        return false;
    }

    uint32_t after = *reinterpret_cast<uint32_t*>(callsite);
    LOGW("patch_jiagu_self_kill_from_return_address: patched caller-4 callsite=%p before=0x%08x after=0x%08x",
         reinterpret_cast<void*>(callsite), before, after);
    return true;
}

static bool loaded_library_contains_vaddr(const struct dl_phdr_info* info, uintptr_t vaddr) {
    if (info == nullptr || info->dlpi_phdr == nullptr) return false;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)& phdr = info->dlpi_phdr[i];
        if (phdr.p_type != PT_LOAD) continue;
        uintptr_t start = static_cast<uintptr_t>(phdr.p_vaddr);
        uintptr_t end = start + static_cast<uintptr_t>(phdr.p_memsz);
        if (vaddr >= start && vaddr < end) {
            return true;
        }
    }
    return false;
}

static bool patch_jiagu_vip_self_kill_callsite_at(const struct dl_phdr_info* info) {
    if (info == nullptr || info->dlpi_name == nullptr || strstr(info->dlpi_name, "libjiagu_vip.so") == nullptr) {
        return false;
    }

    // v110 evidence: GOT tgkill caller was libjiagu_vip.so offset 0x11cb88.
    // On AArch64 the caller address is the return address, so the callsite is
    // the previous instruction at 0x11cb84. Some clone flows load several
    // libjiagu_vip.so instances; only one may contain the decrypted code.
    constexpr uintptr_t return_offset = 0x11cb88;
    constexpr uintptr_t call_offset = return_offset - 4;
    constexpr uint32_t arm64_nop = 0xd503201f;

    uintptr_t base_addr = static_cast<uintptr_t>(info->dlpi_addr);
    if (base_addr == 0 || !loaded_library_contains_vaddr(info, call_offset)) {
        LOGW("patch_jiagu_vip_self_kill_callsite: skip unmapped offset path=%s base=%p offset=0x%lx",
             info->dlpi_name, reinterpret_cast<void*>(base_addr), static_cast<unsigned long>(call_offset));
        return false;
    }

    uintptr_t callsite = base_addr + call_offset;
    uint32_t before = *reinterpret_cast<uint32_t*>(callsite);
    uint32_t previous = loaded_library_contains_vaddr(info, call_offset - 4)
        ? *reinterpret_cast<uint32_t*>(base_addr + call_offset - 4)
        : 0;
    uint32_t next = loaded_library_contains_vaddr(info, call_offset + 4)
        ? *reinterpret_cast<uint32_t*>(base_addr + call_offset + 4)
        : 0;
    LOGW("patch_jiagu_vip_self_kill_callsite: path=%s base=%p callsite=%p offset=0x%lx prev=0x%08x insn=0x%08x next=0x%08x",
         info->dlpi_name, reinterpret_cast<void*>(base_addr), reinterpret_cast<void*>(callsite),
         static_cast<unsigned long>(call_offset), previous, before, next);

    if (patch_arm64_instruction(callsite, 0xfc000000u, 0x94000000u, arm64_nop)) {
        LOGW("patch_jiagu_vip_self_kill_callsite: patched direct BL path=%s offset=0x%lx",
             info->dlpi_name, static_cast<unsigned long>(call_offset));
        return true;
    }
    if (patch_arm64_instruction(callsite, 0xfffffc1fu, 0xd63f0000u, arm64_nop)) {
        LOGW("patch_jiagu_vip_self_kill_callsite: patched indirect BLR path=%s offset=0x%lx",
             info->dlpi_name, static_cast<unsigned long>(call_offset));
        return true;
    }

    LOGW("patch_jiagu_vip_self_kill_callsite: no patch applied path=%s offset=0x%lx",
         info->dlpi_name, static_cast<unsigned long>(call_offset));
    return false;
}

struct JiaguSelfKillPatchRequest {
    int checked;
    int patched;
};

static int patch_jiagu_vip_self_kill_callsite_callback(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    auto* request = static_cast<JiaguSelfKillPatchRequest*>(data);
    if (info == nullptr || info->dlpi_name == nullptr || strstr(info->dlpi_name, "libjiagu_vip.so") == nullptr) {
        return 0;
    }
    request->checked++;
    if (patch_jiagu_vip_self_kill_callsite_at(info)) {
        request->patched++;
    }
    return 0;
}

static void patch_loaded_jiagu_vip_self_kill_callsites() {
    JiaguSelfKillPatchRequest request{0, 0};
    dl_iterate_phdr(patch_jiagu_vip_self_kill_callsite_callback, &request);
    LOGW("patch_jiagu_vip_self_kill_callsite: checked=%d patched=%d",
         request.checked, request.patched);
}

/**
 * dump_decrypted_jiagu_code: dump 壳解密后的代码区域
 *
 * 在壳的 JNI_OnLoad 执行后调用，此时 BSS 区域已被解密。
 * 将关键地址的指令保存到日志，用于逆向分析。
 */
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
            {0x2586d4, "main-init"},
            {0x25bde4, "env-check"},
            {0x25a5dc, "decrypt-func"},
            {0x25a7ac, "register-func"},
        };

        for (int i = 0; i < 12; i++) {
            uintptr_t addr = base + targets[i].offset;
            uint32_t insns[4];
            if (!read_u32x4_if_readable(addr, insns)) {
                LOGW("dump_decrypted: %s offset=0x%lx addr=%p unreadable",
                     targets[i].name,
                     (unsigned long)targets[i].offset,
                     (void*)addr);
                continue;
            }
            LOGI("dump_decrypted: %s offset=0x%lx insn=[0x%08x, 0x%08x, 0x%08x, 0x%08x]",
                 targets[i].name, (unsigned long)targets[i].offset,
                 insns[0], insns[1], insns[2], insns[3]);
        }

        // dump interface11 函数体（前 512 字节）
        uintptr_t i11_addr = base + 0x11fe2c;
        LOGI("dump_decrypted: interface11 body start");
        for (int off = 0; off < 512; off += 16) {
            uint32_t p[4];
            if (!read_u32x4_if_readable(i11_addr + off, p)) {
                LOGW("dump_decrypted: interface11+0x%02x unreadable", off);
                break;
            }
            LOGI("dump_decrypted: interface11+0x%02x: %08x %08x %08x %08x",
                 off, p[0], p[1], p[2], p[3]);
        }

        // dump interface20 函数体（前 256 字节）
        uintptr_t i20_addr = base + 0x10d3f4;
        LOGI("dump_decrypted: interface20 body start");
        for (int off = 0; off < 256; off += 16) {
            uint32_t p[4];
            if (!read_u32x4_if_readable(i20_addr + off, p)) {
                LOGW("dump_decrypted: interface20+0x%02x unreadable", off);
                break;
            }
            LOGI("dump_decrypted: interface20+0x%02x: %08x %08x %08x %08x",
                 off, p[0], p[1], p[2], p[3]);
        }

        // dump 环境检查函数（前 256 字节）
        uintptr_t env_addr = base + 0x25bde4;
        LOGI("dump_decrypted: env-check body start");
        for (int off = 0; off < 256; off += 16) {
            uint32_t p[4];
            if (!read_u32x4_if_readable(env_addr + off, p)) {
                LOGW("dump_decrypted: env-check+0x%02x unreadable", off);
                break;
            }
            LOGI("dump_decrypted: env-check+0x%02x: %08x %08x %08x %08x",
                 off, p[0], p[1], p[2], p[3]);
            for (int i = 0; i < 4; ++i) {
                log_aarch64_branch_target("env-check", base, env_addr + off + (uintptr_t)i * 4, p[i]);
            }
        }

        // 读取 interface11 的 BR X8 目标地址（GOT 函数指针）
        // 使用 signal handler 保护读取，避免 SIGSEGV
        uintptr_t dispatch_ptr_addr = base + 0x1290520;
        uintptr_t dispatch_target = 0;
        if (is_readable_proc_range(dispatch_ptr_addr, sizeof(dispatch_target))) {
            memcpy(&dispatch_target, (void*)dispatch_ptr_addr, sizeof(dispatch_target));
        } else {
            LOGW("dump_decrypted: interface11-dispatch-ptr addr=%p unreadable", (void*)dispatch_ptr_addr);
        }
        if (dispatch_target != 0 && dispatch_target != 0xFFFFFFFFFFFFFFFFULL) {
            LOGI("dump_decrypted: interface11-dispatch-ptr addr=%p target=%p",
                 (void*)dispatch_ptr_addr, (void*)dispatch_target);
            if (dispatch_target > base && dispatch_target < base + 0x300000) {
                uintptr_t dispatch_offset = dispatch_target - base;
                LOGI("dump_decrypted: interface11-dispatch-func offset=0x%lx", (unsigned long)dispatch_offset);
                for (int off = 0; off < 256; off += 16) {
                    uint32_t p[4];
                    if (!read_u32x4_if_readable(dispatch_target + off, p)) {
                        LOGW("dump_decrypted: dispatch-func+0x%02x unreadable", off);
                        break;
                    }
                    LOGI("dump_decrypted: dispatch-func+0x%02x: %08x %08x %08x %08x",
                         off, p[0], p[1], p[2], p[3]);
                }
            } else {
                LOGI("dump_decrypted: interface11-dispatch-func target outside libjiagu_vip.so range");
            }
        } else {
            LOGI("dump_decrypted: interface11-dispatch-ptr addr=%p not initialized yet", (void*)dispatch_ptr_addr);
        }

        // dump RegisterNatives 调用点附近代码（前 128 字节）
        uintptr_t regnative_addr = base + 0x1116b4;
        LOGI("dump_decrypted: RegisterNatives-caller body start");
        for (int off = -32; off < 128; off += 16) {
            uint32_t p[4];
            if (!read_u32x4_if_readable(regnative_addr + off, p)) {
                LOGW("dump_decrypted: RegNative+0x%02x unreadable", off);
                break;
            }
            LOGI("dump_decrypted: RegNative+0x%02x: %08x %08x %08x %08x",
                 off, p[0], p[1], p[2], p[3]);
        }

        return 1;
    }, nullptr);
}

static bool range_contains_address(const ProcMapRange& range, uintptr_t address) {
    return address >= range.start && address < range.end;
}

static bool range_overlaps(const ProcMapRange& range, uintptr_t start, uintptr_t end) {
    return range.start < end && start < range.end;
}

static bool same_proc_range(const ProcMapRange& a, const ProcMapRange& b) {
    return a.start == b.start && a.end == b.end;
}

static void append_unique_proc_range(std::vector<ProcMapRange>& selected, const ProcMapRange& range) {
    for (const auto& item : selected) {
        if (same_proc_range(item, range)) return;
    }
    selected.push_back(range);
}

static bool proc_range_readable(const ProcMapRange& range) {
    return strchr(range.perms, 'r') != nullptr && range.end > range.start;
}

static int dump_jiagu_runtime_ranges(const char* dump_dir) {
    if (dump_dir == nullptr || dump_dir[0] == '\0') return 0;
    mkdir(dump_dir, 0755);

    uintptr_t base = find_loaded_library_base("libjiagu_vip.so");
    if (base == 0) {
        LOGW("dump_jiagu_runtime_ranges: libjiagu_vip.so base not found");
        return 0;
    }

    auto ranges = read_proc_map_ranges();
    if (ranges.empty()) {
        LOGW("dump_jiagu_runtime_ranges: no maps ranges available");
        return 0;
    }

    struct TargetOffset { uintptr_t offset; const char* name; };
    const TargetOffset targets[] = {
        {0x10d3f4, "interface20"},
        {0x1116b4, "RegisterNatives-caller"},
        {0x112820, "interface5"},
        {0x114bd4, "interface21"},
        {0x11cb84, "self-kill-callsite"},
        {0x11fe2c, "interface11"},
        {0x1290520, "interface11-dispatch-ptr"},
        {0x25861c, "init-function"},
        {0x2586d4, "main-init"},
        {0x258a38, "JNI_OnLoad"},
        {0x25a5dc, "decrypt-func"},
        {0x25a7ac, "register-func"},
        {0x25bde4, "env-check"},
    };

    std::vector<ProcMapRange> selected;
    uintptr_t jiaguWindowStart = base;
    uintptr_t jiaguWindowEnd = base + 0x1400000;
    for (const auto& range : ranges) {
        bool select = false;
        if (strstr(range.line, "libjiagu_vip.so") != nullptr) {
            select = true;
        }
        if (!select && strstr(range.line, "[anon:.bss]") != nullptr &&
            range_overlaps(range, jiaguWindowStart, jiaguWindowEnd)) {
            select = true;
        }
        if (!select) {
            for (const auto& target : targets) {
                if (range_contains_address(range, base + target.offset)) {
                    select = true;
                    break;
                }
            }
        }
        if (select) append_unique_proc_range(selected, range);
    }

    char mapsPath[1024];
    snprintf(mapsPath, sizeof(mapsPath), "%s/jiagu-runtime-maps.txt", dump_dir);
    FILE* mapsOut = fopen(mapsPath, "wb");
    if (mapsOut != nullptr) {
        fprintf(mapsOut, "base=0x%lx\n", static_cast<unsigned long>(base));
        fprintf(mapsOut, "target_count=%zu\n", sizeof(targets) / sizeof(targets[0]));
        for (const auto& target : targets) {
            ProcMapEntry entry = find_proc_map_entry(base + target.offset);
            fprintf(mapsOut,
                    "target name=%s offset=0x%lx addr=0x%lx mapped=%d perms=%s line=%s\n",
                    target.name,
                    static_cast<unsigned long>(target.offset),
                    static_cast<unsigned long>(base + target.offset),
                    entry.found ? 1 : 0,
                    entry.found ? entry.perms : "<none>",
                    entry.found ? entry.line : "<none>");
        }
        fprintf(mapsOut, "\nselected_ranges=%zu\n", selected.size());
        for (const auto& range : selected) {
            long relStart = static_cast<long>(range.start - base);
            long relEnd = static_cast<long>(range.end - base);
            fprintf(mapsOut,
                    "range start=0x%lx end=0x%lx rel=[0x%lx,0x%lx) size=0x%lx perms=%s line=%s\n",
                    static_cast<unsigned long>(range.start),
                    static_cast<unsigned long>(range.end),
                    static_cast<unsigned long>(relStart),
                    static_cast<unsigned long>(relEnd),
                    static_cast<unsigned long>(range.end - range.start),
                    range.perms,
                    range.line);
        }
        fclose(mapsOut);
    } else {
        LOGW("dump_jiagu_runtime_ranges: cannot write maps metadata %s errno=%d", mapsPath, errno);
    }

    int dumped = 0;
    int index = 0;
    constexpr size_t maxDumpSize = 128u * 1024u * 1024u;
    for (const auto& range : selected) {
        if (!proc_range_readable(range)) {
            LOGW("dump_jiagu_runtime_ranges: skip unreadable range line=%s", range.line);
            continue;
        }
        size_t size = static_cast<size_t>(range.end - range.start);
        if (size == 0 || size > maxDumpSize) {
            LOGW("dump_jiagu_runtime_ranges: skip size=%zu range line=%s", size, range.line);
            continue;
        }

        uintptr_t relStart = range.start >= base ? range.start - base : 0;
        uintptr_t relEnd = range.end >= base ? range.end - base : 0;
        char outPath[1024];
        snprintf(outPath,
                 sizeof(outPath),
                 "%s/jiagu-runtime-%02d-rel_%08lx-%08lx-%s.bin",
                 dump_dir,
                 index++,
                 static_cast<unsigned long>(relStart),
                 static_cast<unsigned long>(relEnd),
                 range.perms);
        FILE* out = fopen(outPath, "wb");
        if (out == nullptr) {
            LOGW("dump_jiagu_runtime_ranges: fopen failed %s errno=%d", outPath, errno);
            continue;
        }
        size_t written = fwrite(reinterpret_cast<void*>(range.start), 1, size, out);
        fclose(out);
        if (written != size) {
            LOGW("dump_jiagu_runtime_ranges: short write %s written=%zu size=%zu errno=%d",
                 outPath, written, size, errno);
            continue;
        }
        dumped++;
        LOGI("dump_jiagu_runtime_ranges: dumped %s size=%zu line=%s", outPath, size, range.line);
    }

    LOGI("dump_jiagu_runtime_ranges: dumped=%d selected=%zu dir=%s",
         dumped, selected.size(), dump_dir);
    return dumped;
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
    if (info.has_kill) { patch(info.kill_offset, (void*)got_hooked_kill, (void**)&got_orig_kill); }
    if (info.has_tgkill) { patch(info.tgkill_offset, (void*)got_hooked_tgkill, (void**)&got_orig_tgkill); }
    LOGI("got_hook_immediate: base=%p open=%d openat=%d fopen=%d readlink=%d exit=%d _exit=%d abort=%d kill=%d tgkill=%d",
         (void*)base_addr, info.has_open, info.has_openat, info.has_fopen, info.has_readlink,
         info.has_exit, info.has__exit, info.has_abort, info.has_kill, info.has_tgkill);
    patch_loaded_jiagu_vip_self_kill_callsites();

    // 如果是 libjiagu_vip.so，dump 解密后的代码
    if (path != nullptr && strstr(path, "libjiagu_vip.so") != nullptr) {
        patch_jiagu_vip_env_check(base_addr, path);
        dump_decrypted_jiagu_code();
    }
}

/**
 * 预解析 ELF 并记录 GOT 偏移量，但不调用 dlopen。
 * 用于在 StubApp.load() 之前预解析壳库，为后续 GOT hook 做准备。
 * 实际的 GOT patch 在 dlopen 后由 got_hook_immediate 完成。
 *
 * @param libPath .so 文件绝对路径
 * @return 预解析的 GOT 条目数量（0 = 无有效条目或失败）
 */
JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativePreParseAndInstallGotHooks(
    JNIEnv* env, jclass clazz, jstring libPath)
{
    (void)clazz;
    if (libPath == nullptr) return 0;

    const char* path = env->GetStringUTFChars(libPath, nullptr);
    if (path == nullptr) return 0;

    // 预解析 ELF GOT
    GotEntryInfo got_info = pre_parse_elf_got(path);
    int entryCount = (got_info.has_open ? 1 : 0) + (got_info.has_openat ? 1 : 0) +
                     (got_info.has_fopen ? 1 : 0) + (got_info.has_readlink ? 1 : 0) +
                     (got_info.has_exit ? 1 : 0) + (got_info.has__exit ? 1 : 0) +
                     (got_info.has_abort ? 1 : 0) + (got_info.has_kill ? 1 : 0) +
                     (got_info.has_tgkill ? 1 : 0);
    LOGI("nativePreParseAndInstallGotHooks: pre-parsed %s (entries=%d open=%d openat=%d fopen=%d readlink=%d exit=%d _exit=%d abort=%d kill=%d tgkill=%d)",
         path, entryCount, got_info.has_open, got_info.has_openat, got_info.has_fopen, got_info.has_readlink,
         got_info.has_exit, got_info.has__exit, got_info.has_abort, got_info.has_kill, got_info.has_tgkill);

    env->ReleaseStringUTFChars(libPath, path);

    if (entryCount == 0) {
        LOGI("nativePreParseAndInstallGotHooks: no GOT entries found, skip");
        return 0;
    }

    LOGI("nativePreParseAndInstallGotHooks: ELF pre-parsed, GOT hooks will be applied after dlopen");
    return entryCount;
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
        LOGI("nativePreloadLibraries: pre-parsed %s (open=%d openat=%d fopen=%d readlink=%d exit=%d _exit=%d abort=%d kill=%d tgkill=%d)",
             path, got_info.has_open, got_info.has_openat, got_info.has_fopen, got_info.has_readlink,
             got_info.has_exit, got_info.has__exit, got_info.has_abort, got_info.has_kill, got_info.has_tgkill);

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
    LOGI("nativeDlopenOnly: pre-parsed %s (open=%d openat=%d fopen=%d readlink=%d exit=%d _exit=%d abort=%d kill=%d tgkill=%d)",
         path, got_info.has_open, got_info.has_openat, got_info.has_fopen, got_info.has_readlink,
         got_info.has_exit, got_info.has__exit, got_info.has_abort, got_info.has_kill, got_info.has_tgkill);

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
    if (!g_jiagu_jni_diag_in_hook) {
        uintptr_t off = 0;
        const char* window = nullptr;
        if (jiagu_jni_diag_caller(__builtin_return_address(0), &off, &window)) {
            LOGW("JiaguJNI FindClass callerOff=0x%lx window=%s name=%s",
                 (unsigned long)off, window ? window : "<unknown>", name);
        }
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
static void JNICALL stub_ywlogin_registerParameter(JNIEnv* env, jobject thiz, jobject getter);
static void JNICALL stub_ywlogin_resetParameter(JNIEnv* env, jobject thiz, jstring key, jstring value);
static void JNICALL stub_ywlogin_setDefaultParameters(JNIEnv* env, jobject thiz, jobject application, jobject values);
static jobject JNICALL stub_ywlogin_getDefaultParameters(JNIEnv* env, jobject thiz);
static jobject JNICALL stub_ywlogin_getCommonParamaters(JNIEnv* env, jobject thiz);
static void JNICALL stub_ywlogin_saveParameters(JNIEnv* env, jobject thiz, jobject values);
static void JNICALL stub_ywlogin_refreshParameters(JNIEnv* env, jobject thiz);
static jobject JNICALL stub_ywlogin_getSignCallback(JNIEnv* env, jobject thiz);
static void JNICALL stub_ywlogin_setSignCallback(JNIEnv* env, jobject thiz, jobject callback);
static void JNICALL stub_ywlogin_pwdLogin(JNIEnv* env, jobject thiz, jobject activity, jstring account, jstring password, jobject callback);
static void JNICALL stub_ywlogin_sendPhoneCode(JNIEnv* env, jobject thiz, jobject context, jstring phone, jint type, jint scene, jobject callback);
static void JNICALL stub_ywlogin_qrCodeV2(JNIEnv* env, jobject thiz, jobject callback);
static jstring JNICALL stub_easyencrypt_md5_key(JNIEnv* env, jclass clazz);
static jbyteArray JNICALL stub_easyencrypt_bytes_identity(JNIEnv* env, jclass clazz, jbyteArray data);
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
static jstring JNICALL stub_online_buildUrl(JNIEnv* env, jobject thiz, jobject param);
static jobject JNICALL stub_online_obtainHeaders(JNIEnv* env, jobject thiz);
static jobject JNICALL stub_online_downloadChapterFile(JNIEnv* env, jobject thiz, jstring url);
static void JNICALL stub_online_run(JNIEnv* env, jobject thiz);
static jint read_online_result_code(JNIEnv* env, jobject result);
static void close_java_closeable(JNIEnv* env, jobject closeable, const char* label);
static jobject open_url_input_stream(JNIEnv* env, const std::string& url);
static jobject collect_qqreader_request_headers(JNIEnv* env);
static void apply_url_connection_headers(JNIEnv* env, jobject connection, jmethodID setRequestProperty, jobject headers);
static jstring JNICALL stub_fock_get_encrypt_pool(JNIEnv* env, jclass clazz, jstring key);
static jobject JNICALL stub_fock_get_encrypt_bean(JNIEnv* env, jclass clazz, jstring key);
static void JNICALL stub_fock_save_encrypt_pool(JNIEnv* env, jclass clazz, jstring key, jobject bean);
static void JNICALL stub_fock_update_encrypt_bean(JNIEnv* env, jclass clazz, jstring key, jstring value, jstring sign);
static jstring JNICALL stub_fock_sign_string(JNIEnv* env, jclass clazz, jstring value);

// interface5(Application) 的 stub 实现
// 壳在 Application 创建时调用此方法，之后才会调用 initLoginSDK()
// 在这里重新注册业务 stub，确保 YWLoginManager.getInstance 等方法有实现
static bool clear_logged_exception(JNIEnv* env, const char* label) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    LOGW("%s threw; falling back", label);
    return true;
}

static int current_thread_id_for_log() {
#if defined(__NR_gettid)
    return static_cast<int>(syscall(__NR_gettid));
#else
    return static_cast<int>(getpid());
#endif
}

static void forward_stub_interface_app(
        JNIEnv* env,
        jclass clazz,
        jobject app,
        StubInterfaceAppFn original,
        const char* label) {
    if (original != nullptr) {
        LOGI("%s forwarding to original=%p", label, (void*)original);
        original(env, clazz, app);
        if (!clear_logged_exception(env, label)) {
            return;
        }
    }
    LOGI("%s fallback no-op", label);
}

static void JNICALL stub_interface5(JNIEnv* env, jclass clazz, jobject app) {
    forward_stub_interface_app(env, clazz, app, g_orig_stub_interface5, "stub_interface5");
}

static void JNICALL stub_interface21(JNIEnv* env, jclass clazz, jobject app) {
    forward_stub_interface_app(env, clazz, app, g_orig_stub_interface21, "stub_interface21");
}

static void JNICALL stub_interface_app(JNIEnv* env, jclass clazz, jobject app) {
    (void)env;
    (void)clazz;
    (void)app;
    LOGI("stub_interface_app fallback no-op");
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
        JNINativeMethod methods[] = {
            {const_cast<char*>("getMd5Key"),
             const_cast<char*>("()Ljava/lang/String;"),
             (void*)stub_easyencrypt_md5_key},
            {const_cast<char*>("decrypt"),
             const_cast<char*>("([B)[B"),
             (void*)stub_easyencrypt_bytes_identity},
            {const_cast<char*>("encrypt"),
             const_cast<char*>("([B)[B"),
             (void*)stub_easyencrypt_bytes_identity},
        };
        if (env->RegisterNatives(easyClass, methods, (jint)(sizeof(methods) / sizeof(methods[0]))) == JNI_OK) {
            registered += (int)(sizeof(methods) / sizeof(methods[0]));
            LOGI("register_qrencrypt_stubs: EasyEncrypt methods OK");
        } else {
            if (env->ExceptionCheck()) env->ExceptionClear();
            failed++;
            LOGW("register_qrencrypt_stubs: EasyEncrypt methods failed");
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
    int beforeOnlineRegisters = g_online_chapter_register_count.load(std::memory_order_relaxed);
    if (g_orig_stub_interface11 != nullptr) {
        LOGW("stub_interface11 DIAG: begin value=%d tid=%d onlineRegisterCountBefore=%d original=%s",
             value,
             current_thread_id_for_log(),
             beforeOnlineRegisters,
             describe_native_address((void*)g_orig_stub_interface11).c_str());
        g_orig_stub_interface11(env, clazz, value);
        int afterOnlineRegisters = g_online_chapter_register_count.load(std::memory_order_relaxed);
        if (!clear_logged_exception(env, "stub_interface11 original")) {
            LOGW("stub_interface11 DIAG: original completed value=%d onlineRegisterCountAfter=%d delta=%d",
                 value,
                 afterOnlineRegisters,
                 afterOnlineRegisters - beforeOnlineRegisters);
            return;
        }
        LOGW("stub_interface11 DIAG: original threw value=%d onlineRegisterCountAfter=%d delta=%d",
             value,
             afterOnlineRegisters,
             afterOnlineRegisters - beforeOnlineRegisters);
    }
    LOGI("stub_interface11 fallback value=%d", value);
    register_qrencrypt_stubs(env);
    LOGW("stub_interface11 DIAG: fallback completed value=%d onlineRegisterCount=%d",
         value,
         g_online_chapter_register_count.load(std::memory_order_relaxed));
}

// interface20 的 stub 实现：返回 true
JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterQrencryptStubs(
    JNIEnv* env, jclass clazz, jobject classLoader)
{
    (void)clazz;
    if (classLoader == nullptr) return JNI_FALSE;

    if (g_guest_classloader == nullptr) {
        g_guest_classloader = env->NewGlobalRef(classLoader);
    }
    if (g_classloader_loadclass == nullptr) {
        jclass clClass = env->FindClass("java/lang/ClassLoader");
        if (clClass == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGW("nativeRegisterQrencryptStubs: ClassLoader class not found");
            return JNI_FALSE;
        }
        g_classloader_loadclass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        env->DeleteLocalRef(clClass);
        if (g_classloader_loadclass == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGW("nativeRegisterQrencryptStubs: ClassLoader.loadClass not found");
            return JNI_FALSE;
        }
    }

    register_qrencrypt_stubs(env);
    return JNI_TRUE;
}

static jboolean JNICALL stub_interface_bool(JNIEnv* env, jclass clazz) {
    if (g_orig_stub_interface20 != nullptr) {
        LOGI("stub_interface20 forwarding original=%p", (void*)g_orig_stub_interface20);
        jboolean result = g_orig_stub_interface20(env, clazz);
        if (!clear_logged_exception(env, "stub_interface20 original")) {
            LOGI("stub_interface20 original result=%d", result ? 1 : 0);
            return result;
        }
    }
    LOGI("stub_interface_bool fallback returning true");
    return JNI_TRUE;
}

// interface31(String) 的 stub 实现：返回 true
static jboolean JNICALL stub_interface_str(JNIEnv* env, jclass clazz, jstring s) {
    LOGI("stub_interface_str called (returning true)");
    return JNI_TRUE;
}

// YWLoginManager 的 stub native 方法实现

static jobject copy_content_values(JNIEnv* env, jobject source) {
    jclass valuesClass = env->FindClass("android/content/ContentValues");
    if (valuesClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    jobject result = nullptr;
    if (source != nullptr) {
        jmethodID copyCtor = env->GetMethodID(valuesClass, "<init>", "(Landroid/content/ContentValues;)V");
        if (copyCtor != nullptr) {
            result = env->NewObject(valuesClass, copyCtor, source);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            result = nullptr;
        }
    }
    if (result == nullptr) {
        jmethodID ctor = env->GetMethodID(valuesClass, "<init>", "()V");
        if (ctor != nullptr) {
            result = env->NewObject(valuesClass, ctor);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            result = nullptr;
        }
    }
    env->DeleteLocalRef(valuesClass);
    return result;
}

static bool content_values_put_string(JNIEnv* env, jobject values, jstring key, jstring value) {
    if (values == nullptr || key == nullptr) return false;
    jclass valuesClass = env->GetObjectClass(values);
    if (valuesClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID put = env->GetMethodID(valuesClass, "put", "(Ljava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(valuesClass);
    if (put == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    env->CallVoidMethod(values, put, key, value);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

static bool content_values_put_all(JNIEnv* env, jobject target, jobject source) {
    if (target == nullptr || source == nullptr) return false;
    jclass valuesClass = env->GetObjectClass(target);
    if (valuesClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID putAll = env->GetMethodID(valuesClass, "putAll", "(Landroid/content/ContentValues;)V");
    env->DeleteLocalRef(valuesClass);
    if (putAll == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    env->CallVoidMethod(target, putAll, source);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

static std::string ywlogin_object_to_string(JNIEnv* env, jobject object) {
    if (object == nullptr) return "null";
    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return "<Object class missing>";
    }
    jmethodID toString = env->GetMethodID(objectClass, "toString", "()Ljava/lang/String;");
    env->DeleteLocalRef(objectClass);
    if (toString == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return "<toString missing>";
    }
    jstring text = (jstring)env->CallObjectMethod(object, toString);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return "<toString threw>";
    }
    if (text == nullptr) return "null";
    const char* chars = env->GetStringUTFChars(text, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(text, chars);
    env->DeleteLocalRef(text);
    return result;
}

static std::string content_values_key_summary(JNIEnv* env, jobject values) {
    if (values == nullptr) return "null";
    jclass valuesClass = env->GetObjectClass(values);
    if (valuesClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return "<ContentValues class missing>";
    }
    jmethodID keySet = env->GetMethodID(valuesClass, "keySet", "()Ljava/util/Set;");
    env->DeleteLocalRef(valuesClass);
    if (keySet == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return "<keySet missing>";
    }
    jobject keys = env->CallObjectMethod(values, keySet);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return "<keySet threw>";
    }
    std::string result = ywlogin_object_to_string(env, keys);
    if (keys != nullptr) env->DeleteLocalRef(keys);
    return result;
}

static jobject call_ywlogin_parameter_getter(JNIEnv* env, jobject getter) {
    if (getter == nullptr) return nullptr;
    jclass getterClass = env->GetObjectClass(getter);
    if (getterClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jmethodID getParameter = env->GetMethodID(getterClass, "getParameter", "()Landroid/content/ContentValues;");
    env->DeleteLocalRef(getterClass);
    if (getParameter == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("stub_ywlogin_parameter_getter: getParameter method missing");
        return nullptr;
    }
    jobject values = env->CallObjectMethod(getter, getParameter);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("stub_ywlogin_parameter_getter: getParameter threw");
        return nullptr;
    }
    return values;
}

static void set_ywlogin_instance_field(JNIEnv* env, jobject thiz, const char* name, const char* sig, jobject value) {
    if (thiz == nullptr || name == nullptr || sig == nullptr) return;
    jclass cls = env->GetObjectClass(thiz);
    if (cls == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    jfieldID field = env->GetFieldID(cls, name, sig);
    if (field != nullptr) {
        env->SetObjectField(thiz, field, value);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(cls);
}

static void apply_ywlogin_cached_state_to_instance_unlocked(JNIEnv* env, jobject instance) {
    if (instance == nullptr) return;
    set_ywlogin_instance_field(env, instance, "mContext", "Landroid/app/Application;", g_ywlogin_application);
    set_ywlogin_instance_field(env, instance, "mDefaultParameters", "Landroid/content/ContentValues;", g_ywlogin_default_parameters);
    set_ywlogin_instance_field(
        env,
        instance,
        "parameterGetter",
        "Lcom/yuewen/ywlogin/login/IParameterGetter;",
        g_ywlogin_parameter_getter);
    set_ywlogin_instance_field(
        env,
        instance,
        "mSignCallback",
        "Lcom/yuewen/ywlogin/login/ParamsSignCallback;",
        g_ywlogin_sign_callback);
}

static void apply_ywlogin_cached_state_to_instance(JNIEnv* env, jobject instance) {
    std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
    apply_ywlogin_cached_state_to_instance_unlocked(env, instance);
}

static jobject get_ywlogin_manager_local_ref(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
    return g_ywlogin_manager_instance != nullptr
        ? env->NewLocalRef(g_ywlogin_manager_instance)
        : nullptr;
}

static void replace_global_ref(JNIEnv* env, jobject* slot, jobject value) {
    if (*slot != nullptr) {
        env->DeleteGlobalRef(*slot);
        *slot = nullptr;
    }
    if (value != nullptr) {
        *slot = env->NewGlobalRef(value);
    }
}

// getInstance() — YWLogin SDK expects a process-wide singleton.
static jobject JNICALL stub_ywlogin_getInstance(JNIEnv* env, jclass clazz) {
    if (clazz == nullptr) {
        LOGW("stub_ywlogin_getInstance: null clazz");
        return nullptr;
    }

    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        if (g_ywlogin_manager_instance != nullptr) {
            jobject local = env->NewLocalRef(g_ywlogin_manager_instance);
            LOGI("stub_ywlogin_getInstance: returning singleton=%p", local);
            return local;
        }
    }

    jmethodID ctor = env->GetMethodID(clazz, "<init>", "()V");
    if (ctor == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("stub_ywlogin_getInstance: default constructor not found");
        return nullptr;
    }
    jobject instance = env->NewObject(clazz, ctor);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("stub_ywlogin_getInstance: NewObject failed");
        return nullptr;
    }
    if (instance) {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        if (g_ywlogin_manager_instance == nullptr) {
            g_ywlogin_manager_instance = env->NewGlobalRef(instance);
            apply_ywlogin_cached_state_to_instance_unlocked(env, instance);
            LOGI("stub_ywlogin_getInstance: constructed singleton=%p", instance);
        } else {
            jobject local = env->NewLocalRef(g_ywlogin_manager_instance);
            env->DeleteLocalRef(instance);
            LOGI("stub_ywlogin_getInstance: race returning singleton=%p", local);
            return local;
        }
    } else {
        LOGW("stub_ywlogin_getInstance: constructed instance is null");
    }
    return instance;
}

// registerParameter(IParameterGetter)V — 空实现
static void JNICALL stub_ywlogin_registerParameter(JNIEnv* env, jclass clazz, jobject getter) {
    LOGI("stub_ywlogin_registerParameter: stub (no-op)");
}

static void JNICALL stub_ywlogin_resetParameter(JNIEnv* env, jobject thiz, jstring key, jstring value) {
    (void)env;
    (void)thiz;
    (void)key;
    (void)value;
    LOGI("stub_ywlogin_resetParameter: stub (no-op)");
}

static void JNICALL stub_ywlogin_setDefaultParameters(JNIEnv* env, jclass clazz, jobject application, jobject values) {
    (void)clazz;
    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        replace_global_ref(env, &g_ywlogin_application, application);
        replace_global_ref(env, &g_ywlogin_default_parameters, values);
        apply_ywlogin_cached_state_to_instance_unlocked(env, g_ywlogin_manager_instance);
    }
    LOGI("stub_ywlogin_setDefaultParameters: stored application=%p values=%p", application, values);
}

static jobject JNICALL stub_ywlogin_getDefaultParameters(JNIEnv* env, jobject thiz) {
    jobject source = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        source = g_ywlogin_default_parameters;
    }
    if (source == nullptr && thiz != nullptr) {
        jclass cls = env->GetObjectClass(thiz);
        if (cls != nullptr) {
            jfieldID field = env->GetFieldID(cls, "mDefaultParameters", "Landroid/content/ContentValues;");
            if (field != nullptr) {
                source = env->GetObjectField(thiz, field);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                source = nullptr;
            }
            env->DeleteLocalRef(cls);
        }
    }
    jobject result = copy_content_values(env, source);
    LOGI("stub_ywlogin_getDefaultParameters: returning values=%p source=%p", result, source);
    if (source != nullptr && source != g_ywlogin_default_parameters) {
        env->DeleteLocalRef(source);
    }
    return result;
}

static void JNICALL stub_ywlogin_registerParameter_tracked(JNIEnv* env, jobject thiz, jobject getter) {
    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        replace_global_ref(env, &g_ywlogin_parameter_getter, getter);
        set_ywlogin_instance_field(
            env,
            g_ywlogin_manager_instance,
            "parameterGetter",
            "Lcom/yuewen/ywlogin/login/IParameterGetter;",
            getter);
    }
    set_ywlogin_instance_field(
        env,
        thiz,
        "parameterGetter",
        "Lcom/yuewen/ywlogin/login/IParameterGetter;",
        getter);
    LOGI("stub_ywlogin_registerParameter_tracked: stored getter=%p", getter);
}

static void JNICALL stub_ywlogin_resetParameter_tracked(JNIEnv* env, jobject thiz, jstring key, jstring value) {
    jobject updated = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        updated = copy_content_values(env, g_ywlogin_default_parameters);
        if (updated != nullptr && content_values_put_string(env, updated, key, value)) {
            replace_global_ref(env, &g_ywlogin_default_parameters, updated);
            set_ywlogin_instance_field(
                env,
                g_ywlogin_manager_instance,
                "mDefaultParameters",
                "Landroid/content/ContentValues;",
                g_ywlogin_default_parameters);
        }
    }
    set_ywlogin_instance_field(env, thiz, "mDefaultParameters", "Landroid/content/ContentValues;", updated);
    std::string keyText = ywlogin_object_to_string(env, key);
    LOGI(
        "stub_ywlogin_resetParameter_tracked: key=%s stored=%d keys=%s",
        keyText.c_str(),
        updated != nullptr ? 1 : 0,
        content_values_key_summary(env, updated).c_str());
    if (updated != nullptr) env->DeleteLocalRef(updated);
}

static void JNICALL stub_ywlogin_setDefaultParameters_tracked(
        JNIEnv* env,
        jobject thiz,
        jobject application,
        jobject values) {
    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        replace_global_ref(env, &g_ywlogin_application, application);
        replace_global_ref(env, &g_ywlogin_default_parameters, values);
        apply_ywlogin_cached_state_to_instance_unlocked(env, g_ywlogin_manager_instance);
    }
    set_ywlogin_instance_field(env, thiz, "mContext", "Landroid/app/Application;", application);
    set_ywlogin_instance_field(env, thiz, "mDefaultParameters", "Landroid/content/ContentValues;", values);
    LOGI(
        "stub_ywlogin_setDefaultParameters_tracked: stored application=%p values=%p keys=%s",
        application,
        values,
        content_values_key_summary(env, values).c_str());
}

static jobject JNICALL stub_ywlogin_getDefaultParameters_tracked(JNIEnv* env, jobject thiz) {
    jobject source = nullptr;
    jobject getter = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        if (g_ywlogin_default_parameters != nullptr) {
            source = env->NewLocalRef(g_ywlogin_default_parameters);
        }
        if (g_ywlogin_parameter_getter != nullptr) {
            getter = env->NewLocalRef(g_ywlogin_parameter_getter);
        }
    }
    if (source == nullptr && thiz != nullptr) {
        jclass cls = env->GetObjectClass(thiz);
        if (cls != nullptr) {
            jfieldID field = env->GetFieldID(cls, "mDefaultParameters", "Landroid/content/ContentValues;");
            if (field != nullptr) {
                source = env->GetObjectField(thiz, field);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                source = nullptr;
            }
            env->DeleteLocalRef(cls);
        }
    }
    if (getter == nullptr && thiz != nullptr) {
        jclass cls = env->GetObjectClass(thiz);
        if (cls != nullptr) {
            jfieldID field = env->GetFieldID(
                cls,
                "parameterGetter",
                "Lcom/yuewen/ywlogin/login/IParameterGetter;");
            if (field != nullptr) {
                getter = env->GetObjectField(thiz, field);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                getter = nullptr;
            }
            env->DeleteLocalRef(cls);
        }
    }
    jobject result = copy_content_values(env, source);
    jobject dynamicValues = call_ywlogin_parameter_getter(env, getter);
    bool mergedDynamic = content_values_put_all(env, result, dynamicValues);
    LOGI(
        "stub_ywlogin_getDefaultParameters_tracked: values=%p source=%p dynamic=%p mergedDynamic=%d keys=%s",
        result,
        source,
        dynamicValues,
        mergedDynamic ? 1 : 0,
        content_values_key_summary(env, result).c_str());
    if (dynamicValues != nullptr) env->DeleteLocalRef(dynamicValues);
    if (getter != nullptr) env->DeleteLocalRef(getter);
    if (source != nullptr) env->DeleteLocalRef(source);
    return result;
}

static jobject JNICALL stub_ywlogin_getCommonParamaters(JNIEnv* env, jobject thiz) {
    return stub_ywlogin_getDefaultParameters_tracked(env, thiz);
}

static void JNICALL stub_ywlogin_saveParameters(JNIEnv* env, jobject thiz, jobject values) {
    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        replace_global_ref(env, &g_ywlogin_default_parameters, values);
        set_ywlogin_instance_field(
            env,
            g_ywlogin_manager_instance,
            "mDefaultParameters",
            "Landroid/content/ContentValues;",
            g_ywlogin_default_parameters);
    }
    set_ywlogin_instance_field(env, thiz, "mDefaultParameters", "Landroid/content/ContentValues;", values);
    LOGI("stub_ywlogin_saveParameters: stored values=%p", values);
}

static void JNICALL stub_ywlogin_refreshParameters(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    LOGI("stub_ywlogin_refreshParameters: stub (no-op)");
}

static jobject JNICALL stub_ywlogin_getSignCallback(JNIEnv* env, jobject thiz) {
    jobject source = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        source = g_ywlogin_sign_callback;
        if (source != nullptr) {
            jobject local = env->NewLocalRef(source);
            LOGI("stub_ywlogin_getSignCallback: returning stored callback=%p", local);
            return local;
        }
    }
    if (thiz != nullptr) {
        jclass cls = env->GetObjectClass(thiz);
        if (cls != nullptr) {
            jfieldID field = env->GetFieldID(
                cls,
                "mSignCallback",
                "Lcom/yuewen/ywlogin/login/ParamsSignCallback;");
            if (field != nullptr) {
                source = env->GetObjectField(thiz, field);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                source = nullptr;
            }
            env->DeleteLocalRef(cls);
        }
    }
    LOGI("stub_ywlogin_getSignCallback: returning instance callback=%p", source);
    return source;
}

static void JNICALL stub_ywlogin_setSignCallback(JNIEnv* env, jobject thiz, jobject callback) {
    {
        std::lock_guard<std::mutex> lock(g_ywlogin_defaults_mutex);
        replace_global_ref(env, &g_ywlogin_sign_callback, callback);
        set_ywlogin_instance_field(
            env,
            g_ywlogin_manager_instance,
            "mSignCallback",
            "Lcom/yuewen/ywlogin/login/ParamsSignCallback;",
            callback);
    }
    if (thiz != nullptr) {
        jclass cls = env->GetObjectClass(thiz);
        if (cls != nullptr) {
            jfieldID field = env->GetFieldID(
                cls,
                "mSignCallback",
                "Lcom/yuewen/ywlogin/login/ParamsSignCallback;");
            if (field != nullptr) {
                env->SetObjectField(thiz, field, callback);
            }
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            env->DeleteLocalRef(cls);
        }
    }
    LOGI("stub_ywlogin_setSignCallback: stored callback=%p", callback);
}

static void JNICALL stub_ywlogin_fetchSettings(JNIEnv* env, jobject thiz, jobject callback) {
    (void)env;
    (void)thiz;
    (void)callback;
    LOGI("stub_ywlogin_fetchSettings: stub (no-op)");
}

static void notify_ywlogin_error(JNIEnv* env, jobject callback, const char* message) {
    if (env == nullptr || callback == nullptr) return;
    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    jmethodID onError = env->GetMethodID(callbackClass, "onError", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(callbackClass);
    if (onError == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("notify_ywlogin_error: callback has no onError(int,String)");
        return;
    }
    jstring msg = env->NewStringUTF(message != nullptr ? message : "MultiApp login native unavailable");
    env->CallVoidMethod(callback, onError, -9001, msg);
    env->DeleteLocalRef(msg);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("notify_ywlogin_error: callback onError threw");
    }
}

// 其他可能的 native 方法 stub
static void throw_ywlogin_missing_native(JNIEnv* env, const char* methodName) {
    jclass errorClass = env->FindClass("java/lang/UnsatisfiedLinkError");
    if (errorClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    char message[256];
    snprintf(
        message,
        sizeof(message),
        "No implementation found for com.yuewen.ywlogin.login.YWLoginManager.%s; original native not registered",
        methodName != nullptr ? methodName : "<unknown>");
    env->ThrowNew(errorClass, message);
    env->DeleteLocalRef(errorClass);
}

static void JNICALL wrapped_ywlogin_pwdLogin(
        JNIEnv* env,
        jobject thiz,
        jobject activity,
        jstring account,
        jstring password,
        jobject callback) {
    if (g_orig_ywlogin_pwdLogin != nullptr) {
        LOGW("wrapped_ywlogin_pwdLogin: forwarding original=%p", (void*)g_orig_ywlogin_pwdLogin);
        g_orig_ywlogin_pwdLogin(env, thiz, activity, account, password, callback);
        return;
    }
    LOGE("wrapped_ywlogin_pwdLogin: original native not registered; throwing for Java fallback");
    throw_ywlogin_missing_native(env, "pwdLogin");
}

static void JNICALL wrapped_ywlogin_sendPhoneCode(
        JNIEnv* env,
        jobject thiz,
        jobject context,
        jstring phone,
        jint type,
        jint scene,
        jobject callback) {
    if (g_orig_ywlogin_sendPhoneCode != nullptr) {
        LOGW("wrapped_ywlogin_sendPhoneCode: forwarding original=%p type=%d scene=%d",
             (void*)g_orig_ywlogin_sendPhoneCode, type, scene);
        g_orig_ywlogin_sendPhoneCode(env, thiz, context, phone, type, scene, callback);
        return;
    }
    LOGE("wrapped_ywlogin_sendPhoneCode: original native not registered; throwing for Java fallback");
    throw_ywlogin_missing_native(env, "sendPhoneCode");
}

static void JNICALL wrapped_ywlogin_qrCodeV2(
        JNIEnv* env,
        jobject thiz,
        jobject callback) {
    if (g_orig_ywlogin_qrCodeV2 != nullptr) {
        LOGW("wrapped_ywlogin_qrCodeV2: forwarding original=%p", (void*)g_orig_ywlogin_qrCodeV2);
        g_orig_ywlogin_qrCodeV2(env, thiz, callback);
        return;
    }
    LOGE("wrapped_ywlogin_qrCodeV2: original native not registered; throwing for Java fallback");
    throw_ywlogin_missing_native(env, "qrCodeV2");
}

static void JNICALL stub_ywlogin_pwdLogin(
        JNIEnv* env,
        jobject thiz,
        jobject activity,
        jstring account,
        jstring password,
        jobject callback) {
    wrapped_ywlogin_pwdLogin(env, thiz, activity, account, password, callback);
}

static void JNICALL stub_ywlogin_sendPhoneCode(
        JNIEnv* env,
        jobject thiz,
        jobject context,
        jstring phone,
        jint type,
        jint scene,
        jobject callback) {
    wrapped_ywlogin_sendPhoneCode(env, thiz, context, phone, type, scene, callback);
}

static void JNICALL stub_ywlogin_qrCodeV2(
        JNIEnv* env,
        jobject thiz,
        jobject callback) {
    wrapped_ywlogin_qrCodeV2(env, thiz, callback);
}

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

static jbyteArray JNICALL stub_easyencrypt_bytes_identity(JNIEnv* env, jclass clazz, jbyteArray data) {
    (void)clazz;
    if (data == nullptr) {
        return nullptr;
    }
    jsize len = env->GetArrayLength(data);
    jbyteArray out = env->NewByteArray(len);
    if (out == nullptr) {
        return nullptr;
    }
    if (len > 0) {
        std::vector<jbyte> bytes((size_t)len);
        env->GetByteArrayRegion(data, 0, len, bytes.data());
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        env->SetByteArrayRegion(out, 0, len, bytes.data());
        if (env->ExceptionCheck()) {
            return nullptr;
        }
    }
    LOGI("stub_easyencrypt_bytes_identity: len=%d", (int)len);
    return out;
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

static void set_int_field(JNIEnv* env, jobject thiz, const char* name, jint value) {
    if (thiz == nullptr) return;
    jclass cls = env->GetObjectClass(thiz);
    if (cls == nullptr) return;
    jfieldID field = env->GetFieldID(cls, name, "I");
    env->DeleteLocalRef(cls);
    if (field == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    env->SetIntField(thiz, field, value);
}

static std::string jstring_to_string(JNIEnv* env, jstring value);

static jint get_int_field(JNIEnv* env, jobject thiz, const char* name, jint fallback = 0) {
    if (thiz == nullptr) return fallback;
    jclass cls = env->GetObjectClass(thiz);
    if (cls == nullptr) return fallback;
    jfieldID field = env->GetFieldID(cls, name, "I");
    env->DeleteLocalRef(cls);
    if (field == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return fallback;
    }
    return env->GetIntField(thiz, field);
}

static jlong get_long_field(JNIEnv* env, jobject thiz, const char* name, jlong fallback = 0) {
    if (thiz == nullptr) return fallback;
    jclass cls = env->GetObjectClass(thiz);
    if (cls == nullptr) return fallback;
    jfieldID field = env->GetFieldID(cls, name, "J");
    env->DeleteLocalRef(cls);
    if (field == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return fallback;
    }
    return env->GetLongField(thiz, field);
}

static std::string object_class_name(JNIEnv* env, jobject object) {
    if (object == nullptr) return "null";
    jclass objectClass = env->GetObjectClass(object);
    if (objectClass == nullptr) return "unknown";
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(objectClass);
        return "unknown";
    }
    jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    env->DeleteLocalRef(classClass);
    if (getName == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(objectClass);
        return "unknown";
    }
    auto name = (jstring)env->CallObjectMethod(objectClass, getName);
    env->DeleteLocalRef(objectClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return "unknown";
    }
    std::string result = jstring_to_string(env, name);
    if (name != nullptr) env->DeleteLocalRef(name);
    return result.empty() ? "unknown" : result;
}

static std::string object_to_string(JNIEnv* env, jobject object) {
    if (object == nullptr) return "null";
    jclass objectClass = env->GetObjectClass(object);
    if (objectClass == nullptr) return "unknown";
    jmethodID toString = env->GetMethodID(objectClass, "toString", "()Ljava/lang/String;");
    env->DeleteLocalRef(objectClass);
    if (toString == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return "unknown";
    }
    auto value = (jstring)env->CallObjectMethod(object, toString);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return "unknown";
    }
    std::string result = jstring_to_string(env, value);
    if (value != nullptr) env->DeleteLocalRef(value);
    return result.empty() ? "" : result;
}

static jstring call_string_method(JNIEnv* env, jobject object, const char* name, const char* sig, ...) {
    if (object == nullptr) return nullptr;
    jclass cls = env->GetObjectClass(object);
    if (cls == nullptr) return nullptr;
    jmethodID method = env->GetMethodID(cls, name, sig);
    env->DeleteLocalRef(cls);
    if (method == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    va_list args;
    va_start(args, sig);
    auto result = (jstring)env->CallObjectMethodV(object, method, args);
    va_end(args);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

static jint collection_size(JNIEnv* env, jobject collection) {
    if (collection == nullptr) return -1;
    jclass collectionClass = env->FindClass("java/util/Collection");
    if (collectionClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -1;
    }
    if (!env->IsInstanceOf(collection, collectionClass)) {
        env->DeleteLocalRef(collectionClass);
        return -1;
    }
    jmethodID sizeMethod = env->GetMethodID(collectionClass, "size", "()I");
    env->DeleteLocalRef(collectionClass);
    if (sizeMethod == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -1;
    }
    jint size = env->CallIntMethod(collection, sizeMethod);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return size;
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

static jobject new_read_online_result(JNIEnv* env) {
    jclass resultClass = env->FindClass("com/qq/reader/common/protocol/ReadOnline$ReadOnlineResult");
    if (resultClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("stub_online_run: ReadOnlineResult class not found");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(resultClass, "<init>", "()V");
    jobject result = nullptr;
    if (ctor != nullptr) {
        result = env->NewObject(resultClass, ctor);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            result = nullptr;
        }
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(resultClass);
    if (result != nullptr) {
        set_int_field(env, result, "a", -1);
    }
    return result;
}

static bool online_failure_callback_enabled() {
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get("debug.multiapp.online.failure_callback", value);
    if (len <= 0) return true;
    return strcmp(value, "1") == 0 || strcasecmp(value, "true") == 0;
}

static bool notify_online_download_success(JNIEnv* env, jobject thiz, jobject result) {
    if (result == nullptr) {
        LOGW("stub_online_run: no ReadOnlineResult; cannot dispatch getBookSucces");
        return false;
    }

    jobject listener = get_object_field(env, thiz, "mListener", "Lcom/qq/reader/cservice/onlineread/qdaf;");
    if (listener == nullptr) {
        LOGW("stub_online_run: no mListener; cannot dispatch getBookSucces");
        return false;
    }

    jobject tag = get_object_field(env, thiz, "tag", "Lcom/qq/reader/cservice/onlineread/OnlineTag;");
    if (tag == nullptr) {
        LOGW("stub_online_run: no tag; cannot dispatch getBookSucces");
        env->DeleteLocalRef(listener);
        return false;
    }

    set_object_field(env, thiz, "mResult", "Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;", result);

    jclass listenerClass = env->GetObjectClass(listener);
    if (listenerClass == nullptr) {
        env->DeleteLocalRef(tag);
        env->DeleteLocalRef(listener);
        return false;
    }
    jmethodID success = env->GetMethodID(
            listenerClass,
            "getBookSucces",
            "(Lcom/qq/reader/cservice/onlineread/OnlineTag;Lcom/qq/reader/cservice/onlineread/OnlineChapterDownloadTask;Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;)V");
    env->DeleteLocalRef(listenerClass);
    if (success == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("stub_online_run: getBookSucces method not found");
        env->DeleteLocalRef(tag);
        env->DeleteLocalRef(listener);
        return false;
    }

    env->CallVoidMethod(listener, success, tag, thiz, result);
    bool ok = !clear_logged_exception(env, "stub_online_run getBookSucces");
    LOGW("stub_online_run: getBookSucces dispatched ok=%d result=%s",
         ok ? 1 : 0,
         object_class_name(env, result).c_str());

    env->DeleteLocalRef(tag);
    env->DeleteLocalRef(listener);
    return ok;
}

static void notify_online_download_failed(JNIEnv* env, jobject thiz, jobject explicitResult = nullptr) {
    if (!online_failure_callback_enabled()) {
        LOGI("stub_online_run: failure callback disabled");
        return;
    }

    jobject listener = get_object_field(env, thiz, "mListener", "Lcom/qq/reader/cservice/onlineread/qdaf;");
    if (listener == nullptr) {
        LOGW("stub_online_run: no mListener; cannot dispatch getBookFailed");
        return;
    }
    jobject tag = get_object_field(env, thiz, "tag", "Lcom/qq/reader/cservice/onlineread/OnlineTag;");
    jobject result = explicitResult;
    bool ownsResultRef = false;
    if (result == nullptr) {
        result = get_object_field(env, thiz, "mResult", "Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;");
        ownsResultRef = result != nullptr;
    }
    if (result == nullptr) {
        result = get_object_field(env, thiz, "preLoadResult", "Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;");
        ownsResultRef = result != nullptr;
    }
    bool createdResult = false;
    if (result == nullptr) {
        result = new_read_online_result(env);
        createdResult = result != nullptr;
        ownsResultRef = result != nullptr;
    }

    jclass listenerClass = env->GetObjectClass(listener);
    if (listenerClass == nullptr) {
        if (tag != nullptr) env->DeleteLocalRef(tag);
        if (result != nullptr) env->DeleteLocalRef(result);
        env->DeleteLocalRef(listener);
        return;
    }

    jmethodID failed = env->GetMethodID(
            listenerClass,
            "getBookFailed",
            "(Lcom/qq/reader/cservice/onlineread/OnlineTag;Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;Lcom/qq/reader/cservice/onlineread/OnlineChapterDownloadTask;)V");
    env->DeleteLocalRef(listenerClass);
    if (failed == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("stub_online_run: getBookFailed method not found");
    } else {
        env->CallVoidMethod(listener, failed, tag, result, thiz);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOGW("stub_online_run: getBookFailed callback threw");
        } else {
            LOGW("stub_online_run: dispatched getBookFailed callback resultCode=%d createdResult=%d",
                 read_online_result_code(env, result),
                 createdResult ? 1 : 0);
        }
    }

    if (tag != nullptr) env->DeleteLocalRef(tag);
    if (ownsResultRef && result != nullptr) env->DeleteLocalRef(result);
    env->DeleteLocalRef(listener);
}

static bool notify_online_need_vip_or_pay(JNIEnv* env, jobject thiz, jobject result) {
    if (result == nullptr) {
        LOGW("stub_online_run: no ReadOnlineResult; cannot dispatch getBookNeedVIPOrPay");
        return false;
    }

    jobject listener = get_object_field(env, thiz, "mListener", "Lcom/qq/reader/cservice/onlineread/qdaf;");
    if (listener == nullptr) {
        LOGW("stub_online_run: no mListener; cannot dispatch getBookNeedVIPOrPay");
        return false;
    }

    jobject tag = get_object_field(env, thiz, "tag", "Lcom/qq/reader/cservice/onlineread/OnlineTag;");
    if (tag == nullptr) {
        LOGW("stub_online_run: no tag; cannot dispatch getBookNeedVIPOrPay");
        env->DeleteLocalRef(listener);
        return false;
    }

    set_object_field(env, thiz, "mResult", "Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;", result);

    jclass listenerClass = env->GetObjectClass(listener);
    if (listenerClass == nullptr) {
        env->DeleteLocalRef(tag);
        env->DeleteLocalRef(listener);
        return false;
    }
    jmethodID needVipOrPay = env->GetMethodID(
            listenerClass,
            "getBookNeedVIPOrPay",
            "(Lcom/qq/reader/cservice/onlineread/OnlineTag;Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;)V");
    env->DeleteLocalRef(listenerClass);
    if (needVipOrPay == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("stub_online_run: getBookNeedVIPOrPay method not found");
        env->DeleteLocalRef(tag);
        env->DeleteLocalRef(listener);
        return false;
    }

    env->CallVoidMethod(listener, needVipOrPay, tag, result);
    bool ok = !clear_logged_exception(env, "stub_online_run getBookNeedVIPOrPay");
    LOGW("stub_online_run: getBookNeedVIPOrPay dispatched ok=%d resultCode=%d result=%s",
         ok ? 1 : 0,
         read_online_result_code(env, result),
         object_class_name(env, result).c_str());

    env->DeleteLocalRef(tag);
    env->DeleteLocalRef(listener);
    return ok;
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

static jstring JNICALL stub_online_buildUrl(JNIEnv* env, jobject thiz, jobject param) {
    (void)thiz;
    if (param == nullptr) {
        LOGW("stub_online_buildUrl: qdac param=null");
        return env->NewStringUTF("");
    }

    jstring searchValue = (jstring)get_object_field(env, param, "search", "Ljava/lang/String;");
    jstring judianValue = (jstring)get_object_field(env, param, "judian", "Ljava/lang/String;");
    jstring cihaiValue = (jstring)get_object_field(env, param, "cihai", "Ljava/lang/String;");
    std::string searchStr = jstring_to_string(env, searchValue);
    std::string judianStr = jstring_to_string(env, judianValue);
    std::string cihaiStr = jstring_to_string(env, cihaiValue);
    jboolean offline = get_boolean_field(env, param, "a");
    jlong expireAt = get_long_field(env, param, "b", -1);

    const std::string* candidate = &cihaiStr;
    if (candidate->empty()) candidate = &searchStr;
    if (candidate->empty()) candidate = &judianStr;

    LOGW("stub_online_buildUrl: paramClass=%s search=%s judian=%s cihai=%s offline=%d expireAt=%lld candidate=%s",
         object_class_name(env, param).c_str(),
         searchStr.c_str(),
         judianStr.c_str(),
         cihaiStr.c_str(),
         offline ? 1 : 0,
         (long long)expireAt,
         candidate->c_str());

    if (searchValue != nullptr) env->DeleteLocalRef(searchValue);
    if (judianValue != nullptr) env->DeleteLocalRef(judianValue);
    if (cihaiValue != nullptr) env->DeleteLocalRef(cihaiValue);
    return env->NewStringUTF(candidate->c_str());
}

static jobject JNICALL stub_online_obtainHeaders(JNIEnv* env, jobject thiz) {
    jstring bid = (jstring)get_object_field(env, thiz, "bid", "Ljava/lang/String;");
    jstring bookTaskId = (jstring)get_object_field(env, thiz, "bookTaskId", "Ljava/lang/String;");
    jstring sessionKey = (jstring)get_object_field(env, thiz, "sessionKey", "Ljava/lang/String;");
    jstring usid = (jstring)get_object_field(env, thiz, "usid", "Ljava/lang/String;");
    LOGW("stub_online_obtainHeaders: bid=%s bookTaskId=%s sessionKey=%s usid=%s",
         jstring_to_string(env, bid).c_str(),
         jstring_to_string(env, bookTaskId).c_str(),
         sessionKey == nullptr ? "null" : "<present>",
         usid == nullptr ? "null" : "<present>");

    if (bid != nullptr) env->DeleteLocalRef(bid);
    if (bookTaskId != nullptr) env->DeleteLocalRef(bookTaskId);
    if (sessionKey != nullptr) env->DeleteLocalRef(sessionKey);
    if (usid != nullptr) env->DeleteLocalRef(usid);

    jclass hashMapClass = env->FindClass("java/util/HashMap");
    if (hashMapClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(hashMapClass, "<init>", "()V");
    jobject map = ctor == nullptr ? nullptr : env->NewObject(hashMapClass, ctor);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        map = nullptr;
    }
    env->DeleteLocalRef(hashMapClass);
    return map;
}

static jobject JNICALL stub_online_downloadChapterFile(JNIEnv* env, jobject thiz, jstring url) {
    std::string urlStr = jstring_to_string(env, url);
    jobject tag = get_object_field(env, thiz, "tag", "Lcom/qq/reader/cservice/onlineread/OnlineTag;");
    jstring bid = (jstring)get_object_field(env, thiz, "bid", "Ljava/lang/String;");
    jstring bookTaskId = (jstring)get_object_field(env, thiz, "bookTaskId", "Ljava/lang/String;");
    std::string tagText = object_to_string(env, tag);
    LOGW("stub_online_downloadChapterFile: url=%s bid=%s bookTaskId=%s tag=%s",
         urlStr.c_str(),
         jstring_to_string(env, bid).c_str(),
         jstring_to_string(env, bookTaskId).c_str(),
         tagText.c_str());

    if (tag != nullptr) env->DeleteLocalRef(tag);
    if (bid != nullptr) env->DeleteLocalRef(bid);
    if (bookTaskId != nullptr) env->DeleteLocalRef(bookTaskId);
    return nullptr;
}

static void close_java_closeable(JNIEnv* env, jobject closeable, const char* label) {
    if (closeable == nullptr) return;
    jclass closeableClass = env->FindClass("java/io/Closeable");
    if (closeableClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    if (!env->IsInstanceOf(closeable, closeableClass)) {
        env->DeleteLocalRef(closeableClass);
        return;
    }
    jmethodID closeMethod = env->GetMethodID(closeableClass, "close", "()V");
    env->DeleteLocalRef(closeableClass);
    if (closeMethod == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    env->CallVoidMethod(closeable, closeMethod);
    clear_logged_exception(env, label);
}

static jobject collect_qqreader_request_headers(JNIEnv* env) {
    jclass providerClass = env->FindClass("com/qq/reader/common/readertask/ordinal/qdaa");
    if (providerClass == nullptr) {
        clear_logged_exception(env, "stub_online_run FindClass header provider");
        return nullptr;
    }
    jmethodID searchMethod = env->GetStaticMethodID(providerClass, "search", "()Ljava/util/HashMap;");
    if (searchMethod == nullptr) {
        clear_logged_exception(env, "stub_online_run header provider method");
        env->DeleteLocalRef(providerClass);
        return nullptr;
    }
    jobject headers = env->CallStaticObjectMethod(providerClass, searchMethod);
    env->DeleteLocalRef(providerClass);
    if (clear_logged_exception(env, "stub_online_run collect QQReader headers")) {
        if (headers != nullptr) env->DeleteLocalRef(headers);
        return nullptr;
    }
    return headers;
}

static void apply_url_connection_headers(JNIEnv* env, jobject connection, jmethodID setRequestProperty, jobject headers) {
    if (connection == nullptr || setRequestProperty == nullptr || headers == nullptr) return;

    jclass mapClass = env->FindClass("java/util/Map");
    jmethodID entrySetMethod = mapClass == nullptr ? nullptr :
            env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
    if (mapClass != nullptr) env->DeleteLocalRef(mapClass);
    if (entrySetMethod == nullptr) {
        clear_logged_exception(env, "stub_online_run headers entrySet method");
        return;
    }

    jobject entrySet = env->CallObjectMethod(headers, entrySetMethod);
    if (clear_logged_exception(env, "stub_online_run headers entrySet") || entrySet == nullptr) {
        if (entrySet != nullptr) env->DeleteLocalRef(entrySet);
        return;
    }

    jclass iterableClass = env->FindClass("java/lang/Iterable");
    jmethodID iteratorMethod = iterableClass == nullptr ? nullptr :
            env->GetMethodID(iterableClass, "iterator", "()Ljava/util/Iterator;");
    if (iterableClass != nullptr) env->DeleteLocalRef(iterableClass);
    jobject iterator = iteratorMethod == nullptr ? nullptr : env->CallObjectMethod(entrySet, iteratorMethod);
    clear_logged_exception(env, "stub_online_run headers iterator");
    env->DeleteLocalRef(entrySet);
    if (iterator == nullptr) return;

    jclass iteratorClass = env->FindClass("java/util/Iterator");
    jmethodID hasNext = iteratorClass == nullptr ? nullptr :
            env->GetMethodID(iteratorClass, "hasNext", "()Z");
    jmethodID next = iteratorClass == nullptr ? nullptr :
            env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");
    if (iteratorClass != nullptr) env->DeleteLocalRef(iteratorClass);

    jclass entryClass = env->FindClass("java/util/Map$Entry");
    jmethodID getKey = entryClass == nullptr ? nullptr :
            env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
    jmethodID getValue = entryClass == nullptr ? nullptr :
            env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");
    if (entryClass != nullptr) env->DeleteLocalRef(entryClass);

    int applied = 0;
    std::string keyNames;
    while (hasNext != nullptr && next != nullptr && getKey != nullptr && getValue != nullptr &&
           env->CallBooleanMethod(iterator, hasNext)) {
        if (clear_logged_exception(env, "stub_online_run headers hasNext")) break;
        jobject entry = env->CallObjectMethod(iterator, next);
        if (clear_logged_exception(env, "stub_online_run headers next") || entry == nullptr) {
            if (entry != nullptr) env->DeleteLocalRef(entry);
            break;
        }
        jobject keyObject = env->CallObjectMethod(entry, getKey);
        jobject valueObject = env->CallObjectMethod(entry, getValue);
        if (clear_logged_exception(env, "stub_online_run headers get key/value")) {
            if (keyObject != nullptr) env->DeleteLocalRef(keyObject);
            if (valueObject != nullptr) env->DeleteLocalRef(valueObject);
            env->DeleteLocalRef(entry);
            break;
        }

        std::string key = object_to_string(env, keyObject);
        std::string value = object_to_string(env, valueObject);
        if (!key.empty() && !value.empty()) {
            jstring jKey = env->NewStringUTF(key.c_str());
            jstring jValue = env->NewStringUTF(value.c_str());
            if (jKey != nullptr && jValue != nullptr) {
                env->CallVoidMethod(connection, setRequestProperty, jKey, jValue);
                if (!clear_logged_exception(env, "stub_online_run setRequestProperty QQReader header")) {
                    applied++;
                    if (keyNames.size() < 512) {
                        if (!keyNames.empty()) keyNames += ",";
                        keyNames += key;
                    }
                }
            }
            if (jKey != nullptr) env->DeleteLocalRef(jKey);
            if (jValue != nullptr) env->DeleteLocalRef(jValue);
        }

        if (keyObject != nullptr) env->DeleteLocalRef(keyObject);
        if (valueObject != nullptr) env->DeleteLocalRef(valueObject);
        env->DeleteLocalRef(entry);
    }
    env->DeleteLocalRef(iterator);
    LOGW("stub_online_run: applied QQReader request headers count=%d keys=%s",
         applied,
         keyNames.c_str());
}

static jobject open_url_input_stream(JNIEnv* env, const std::string& url) {
    if (url.empty()) {
        LOGW("stub_online_run: cannot fetch empty url");
        return nullptr;
    }

    jclass urlClass = env->FindClass("java/net/URL");
    if (urlClass == nullptr) {
        clear_logged_exception(env, "stub_online_run FindClass URL");
        return nullptr;
    }
    jmethodID urlCtor = env->GetMethodID(urlClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID openConnection = env->GetMethodID(urlClass, "openConnection", "()Ljava/net/URLConnection;");
    if (urlCtor == nullptr || openConnection == nullptr) {
        clear_logged_exception(env, "stub_online_run URL methods");
        env->DeleteLocalRef(urlClass);
        return nullptr;
    }

    jstring urlString = env->NewStringUTF(url.c_str());
    jobject urlObject = urlString == nullptr ? nullptr : env->NewObject(urlClass, urlCtor, urlString);
    env->DeleteLocalRef(urlClass);
    if (urlString != nullptr) env->DeleteLocalRef(urlString);
    if (clear_logged_exception(env, "stub_online_run new URL") || urlObject == nullptr) {
        if (urlObject != nullptr) env->DeleteLocalRef(urlObject);
        return nullptr;
    }

    jobject connection = env->CallObjectMethod(urlObject, openConnection);
    env->DeleteLocalRef(urlObject);
    if (clear_logged_exception(env, "stub_online_run openConnection") || connection == nullptr) {
        if (connection != nullptr) env->DeleteLocalRef(connection);
        return nullptr;
    }

    jclass connectionClass = env->FindClass("java/net/URLConnection");
    if (connectionClass == nullptr) {
        clear_logged_exception(env, "stub_online_run FindClass URLConnection");
        env->DeleteLocalRef(connection);
        return nullptr;
    }
    jmethodID setConnectTimeout = env->GetMethodID(connectionClass, "setConnectTimeout", "(I)V");
    jmethodID setReadTimeout = env->GetMethodID(connectionClass, "setReadTimeout", "(I)V");
    jmethodID setUseCaches = env->GetMethodID(connectionClass, "setUseCaches", "(Z)V");
    jmethodID setRequestProperty = env->GetMethodID(
            connectionClass, "setRequestProperty", "(Ljava/lang/String;Ljava/lang/String;)V");
    jmethodID getInputStream = env->GetMethodID(connectionClass, "getInputStream", "()Ljava/io/InputStream;");

    if (setConnectTimeout != nullptr) env->CallVoidMethod(connection, setConnectTimeout, 15000);
    clear_logged_exception(env, "stub_online_run setConnectTimeout");
    if (setReadTimeout != nullptr) env->CallVoidMethod(connection, setReadTimeout, 30000);
    clear_logged_exception(env, "stub_online_run setReadTimeout");
    if (setUseCaches != nullptr) env->CallVoidMethod(connection, setUseCaches, JNI_FALSE);
    clear_logged_exception(env, "stub_online_run setUseCaches");

    if (setRequestProperty != nullptr) {
        jobject qqReaderHeaders = collect_qqreader_request_headers(env);
        apply_url_connection_headers(env, connection, setRequestProperty, qqReaderHeaders);
        if (qqReaderHeaders != nullptr) env->DeleteLocalRef(qqReaderHeaders);

        jstring key = env->NewStringUTF("User-Agent");
        jstring value = env->NewStringUTF("QQReader");
        if (key != nullptr && value != nullptr) {
            env->CallVoidMethod(connection, setRequestProperty, key, value);
            clear_logged_exception(env, "stub_online_run setRequestProperty User-Agent");
        }
        if (key != nullptr) env->DeleteLocalRef(key);
        if (value != nullptr) env->DeleteLocalRef(value);
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    jobject inputStream = nullptr;
    if (getInputStream != nullptr) {
        inputStream = env->CallObjectMethod(connection, getInputStream);
        if (clear_logged_exception(env, "stub_online_run getInputStream")) {
            inputStream = nullptr;
        }
    } else {
        clear_logged_exception(env, "stub_online_run getInputStream method");
    }

    env->DeleteLocalRef(connectionClass);
    env->DeleteLocalRef(connection);
    return inputStream;
}

static jint read_online_result_code(JNIEnv* env, jobject result) {
    if (result == nullptr) return -9999;
    jclass resultClass = env->FindClass("com/qq/reader/common/protocol/ReadOnline$ReadOnlineResult");
    if (resultClass == nullptr) {
        clear_logged_exception(env, "stub_online_run FindClass ReadOnlineResult");
        return -9999;
    }
    jmethodID codeMethod = env->GetStaticMethodID(
            resultClass,
            "search",
            "(Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;)I");
    jint code = -9999;
    if (codeMethod != nullptr) {
        code = env->CallStaticIntMethod(resultClass, codeMethod, result);
        clear_logged_exception(env, "stub_online_run ReadOnlineResult.search(result)");
    } else {
        clear_logged_exception(env, "stub_online_run ReadOnlineResult code method");
    }
    env->DeleteLocalRef(resultClass);
    return code;
}

static long long file_size_or_negative(const std::string& path) {
    if (path.empty()) return -1;
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return -1;
    return static_cast<long long>(st.st_size);
}

static std::string preview_for_log(const std::string& value, size_t maxLen = 1024) {
    std::string out = value;
    for (char& ch : out) {
        if (ch == '\r' || ch == '\n' || ch == '\t') ch = ' ';
    }
    if (out.size() > maxLen) {
        out = out.substr(0, maxLen) + "...<truncated>";
    }
    return out;
}

static std::string read_text_file_preview(const std::string& path, size_t maxBytes = 2048) {
    if (path.empty()) return "";
    std::ifstream in(path, std::ios::binary);
    if (!in.good()) return "";
    std::string out;
    out.resize(maxBytes);
    in.read(&out[0], static_cast<std::streamsize>(maxBytes));
    out.resize(static_cast<size_t>(in.gcount()));
    return preview_for_log(out);
}

static bool copy_file_binary(const std::string& source, const std::string& dest) {
    if (source.empty() || dest.empty()) return false;
    if (source == dest) return file_size_or_negative(dest) > 0;

    std::ifstream in(source, std::ios::binary);
    if (!in.good()) {
        LOGW("stub_online_run: source file open failed source=%s errno=%d", source.c_str(), errno);
        return false;
    }
    std::ofstream out(dest, std::ios::binary | std::ios::trunc);
    if (!out.good()) {
        LOGW("stub_online_run: dest file open failed dest=%s errno=%d", dest.c_str(), errno);
        return false;
    }
    out << in.rdbuf();
    out.flush();
    bool ok = out.good() && file_size_or_negative(dest) > 0;
    LOGW("stub_online_run: copy file source=%s sourceSize=%lld dest=%s destSize=%lld ok=%d",
         source.c_str(),
         file_size_or_negative(source),
         dest.c_str(),
         file_size_or_negative(dest),
         ok ? 1 : 0);
    return ok;
}

static bool download_url_to_file(JNIEnv* env, const std::string& url, const std::string& dest) {
    if (url.empty() || dest.empty()) return false;

    jobject inputStream = open_url_input_stream(env, url);
    if (inputStream == nullptr) {
        LOGW("stub_online_run: download open failed url=%s dest=%s", url.c_str(), dest.c_str());
        return false;
    }

    std::ofstream out(dest, std::ios::binary | std::ios::trunc);
    if (!out.good()) {
        LOGW("stub_online_run: download dest open failed dest=%s errno=%d", dest.c_str(), errno);
        close_java_closeable(env, inputStream, "stub_online_run close inputStream after dest open failed");
        env->DeleteLocalRef(inputStream);
        return false;
    }

    jclass inputStreamClass = env->FindClass("java/io/InputStream");
    jmethodID readMethod = inputStreamClass == nullptr ? nullptr :
            env->GetMethodID(inputStreamClass, "read", "([B)I");
    if (inputStreamClass != nullptr) env->DeleteLocalRef(inputStreamClass);
    if (readMethod == nullptr) {
        clear_logged_exception(env, "stub_online_run InputStream.read method");
        close_java_closeable(env, inputStream, "stub_online_run close inputStream after missing read");
        env->DeleteLocalRef(inputStream);
        return false;
    }

    jbyteArray buffer = env->NewByteArray(8192);
    if (buffer == nullptr) {
        clear_logged_exception(env, "stub_online_run NewByteArray download buffer");
        close_java_closeable(env, inputStream, "stub_online_run close inputStream after buffer failed");
        env->DeleteLocalRef(inputStream);
        return false;
    }

    long long total = 0;
    while (true) {
        jint read = env->CallIntMethod(inputStream, readMethod, buffer);
        if (clear_logged_exception(env, "stub_online_run InputStream.read")) {
            total = -1;
            break;
        }
        if (read < 0) break;
        if (read == 0) continue;
        jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
        if (bytes == nullptr) {
            clear_logged_exception(env, "stub_online_run GetByteArrayElements");
            total = -1;
            break;
        }
        out.write(reinterpret_cast<const char*>(bytes), read);
        env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);
        if (!out.good()) {
            LOGW("stub_online_run: download write failed dest=%s errno=%d", dest.c_str(), errno);
            total = -1;
            break;
        }
        total += read;
    }

    out.flush();
    env->DeleteLocalRef(buffer);
    close_java_closeable(env, inputStream, "stub_online_run close inputStream after download");
    env->DeleteLocalRef(inputStream);

    bool ok = total > 0 && out.good() && file_size_or_negative(dest) > 0;
    LOGW("stub_online_run: download url=%s dest=%s bytes=%lld size=%lld ok=%d",
         url.c_str(),
         dest.c_str(),
         total,
         file_size_or_negative(dest),
         ok ? 1 : 0);
    return ok;
}

static std::string java_file_absolute_path(JNIEnv* env, jobject file) {
    if (file == nullptr) return "";
    jclass fileClass = env->FindClass("java/io/File");
    if (fileClass == nullptr) {
        clear_logged_exception(env, "stub_online_run FindClass File");
        return "";
    }
    jmethodID getAbsolutePath = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    env->DeleteLocalRef(fileClass);
    if (getAbsolutePath == nullptr) {
        clear_logged_exception(env, "stub_online_run File.getAbsolutePath");
        return "";
    }
    auto path = (jstring)env->CallObjectMethod(file, getAbsolutePath);
    if (clear_logged_exception(env, "stub_online_run call File.getAbsolutePath")) {
        if (path != nullptr) env->DeleteLocalRef(path);
        return "";
    }
    std::string out = jstring_to_string(env, path);
    if (path != nullptr) env->DeleteLocalRef(path);
    return out;
}

static std::string read_online_file_dest_path(JNIEnv* env, jobject onlineFile) {
    if (onlineFile == nullptr) return "";
    jclass fileInfoClass = env->GetObjectClass(onlineFile);
    if (fileInfoClass == nullptr) return "";
    jmethodID getDestFile = env->GetMethodID(fileInfoClass, "getDestFile", "()Ljava/io/File;");
    env->DeleteLocalRef(fileInfoClass);
    if (getDestFile == nullptr) {
        clear_logged_exception(env, "stub_online_run ReadOnlineFile.getDestFile");
        return "";
    }
    jobject destFile = env->CallObjectMethod(onlineFile, getDestFile);
    if (clear_logged_exception(env, "stub_online_run call ReadOnlineFile.getDestFile")) {
        if (destFile != nullptr) env->DeleteLocalRef(destFile);
        return "";
    }
    std::string path = java_file_absolute_path(env, destFile);
    if (destFile != nullptr) env->DeleteLocalRef(destFile);
    return path;
}

static std::string read_online_file_string(JNIEnv* env, jobject onlineFile, const char* methodName) {
    if (onlineFile == nullptr || methodName == nullptr) return "";
    jclass fileInfoClass = env->GetObjectClass(onlineFile);
    if (fileInfoClass == nullptr) return "";
    jmethodID method = env->GetMethodID(fileInfoClass, methodName, "()Ljava/lang/String;");
    env->DeleteLocalRef(fileInfoClass);
    if (method == nullptr) {
        clear_logged_exception(env, "stub_online_run ReadOnlineFile string getter");
        return "";
    }
    auto value = (jstring)env->CallObjectMethod(onlineFile, method);
    if (clear_logged_exception(env, "stub_online_run call ReadOnlineFile string getter")) {
        if (value != nullptr) env->DeleteLocalRef(value);
        return "";
    }
    std::string out = jstring_to_string(env, value);
    if (value != nullptr) env->DeleteLocalRef(value);
    return out;
}

static jint read_online_file_chapter_id(JNIEnv* env, jobject onlineFile) {
    if (onlineFile == nullptr) return -1;
    jclass fileInfoClass = env->GetObjectClass(onlineFile);
    if (fileInfoClass == nullptr) return -1;
    jmethodID getChapterId = env->GetMethodID(fileInfoClass, "getChapterId", "()I");
    env->DeleteLocalRef(fileInfoClass);
    if (getChapterId == nullptr) {
        clear_logged_exception(env, "stub_online_run ReadOnlineFile.getChapterId");
        return -1;
    }
    jint chapterId = env->CallIntMethod(onlineFile, getChapterId);
    if (clear_logged_exception(env, "stub_online_run call ReadOnlineFile.getChapterId")) {
        return -1;
    }
    return chapterId;
}

static jobject read_online_result_files(JNIEnv* env, jobject result) {
    if (result == nullptr) return nullptr;
    jclass resultClass = env->FindClass("com/qq/reader/common/protocol/ReadOnline$ReadOnlineResult");
    if (resultClass == nullptr) {
        clear_logged_exception(env, "stub_online_run FindClass ReadOnlineResult files");
        return nullptr;
    }
    jmethodID filesMethod = env->GetMethodID(resultClass, "B", "()Ljava/util/List;");
    jobject files = nullptr;
    if (filesMethod != nullptr) {
        files = env->CallObjectMethod(result, filesMethod);
        clear_logged_exception(env, "stub_online_run ReadOnlineResult.B");
    } else {
        clear_logged_exception(env, "stub_online_run ReadOnlineResult.B method");
    }
    env->DeleteLocalRef(resultClass);
    return files;
}

static void log_online_result_files(JNIEnv* env, jobject result, jint effectiveCid, const char* label) {
    if (result == nullptr) {
        LOGW("stub_online_run: %s ReadOnlineResult=null", label ? label : "diag");
        return;
    }
    jobject files = read_online_result_files(env, result);
    if (files == nullptr) {
        LOGW("stub_online_run: %s ReadOnlineResult files=null resultCode=%d",
             label ? label : "diag",
             read_online_result_code(env, result));
        return;
    }
    jint size = collection_size(env, files);
    LOGW("stub_online_run: %s ReadOnlineResult resultCode=%d filesSize=%d effectiveCid=%d",
         label ? label : "diag",
         read_online_result_code(env, result),
         size,
         effectiveCid);

    jclass iterableClass = env->FindClass("java/lang/Iterable");
    jmethodID iteratorMethod = iterableClass == nullptr ? nullptr :
            env->GetMethodID(iterableClass, "iterator", "()Ljava/util/Iterator;");
    if (iterableClass != nullptr) env->DeleteLocalRef(iterableClass);
    jobject iterator = iteratorMethod == nullptr ? nullptr : env->CallObjectMethod(files, iteratorMethod);
    clear_logged_exception(env, "stub_online_run diag files.iterator");
    if (iterator != nullptr) {
        jclass iteratorClass = env->FindClass("java/util/Iterator");
        jmethodID hasNext = iteratorClass == nullptr ? nullptr :
                env->GetMethodID(iteratorClass, "hasNext", "()Z");
        jmethodID next = iteratorClass == nullptr ? nullptr :
                env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");
        if (iteratorClass != nullptr) env->DeleteLocalRef(iteratorClass);
        int index = 0;
        while (hasNext != nullptr && next != nullptr && env->CallBooleanMethod(iterator, hasNext)) {
            if (clear_logged_exception(env, "stub_online_run diag iterator.hasNext")) break;
            jobject onlineFile = env->CallObjectMethod(iterator, next);
            if (clear_logged_exception(env, "stub_online_run diag iterator.next")) {
                if (onlineFile != nullptr) env->DeleteLocalRef(onlineFile);
                break;
            }
            jint chapterId = read_online_file_chapter_id(env, onlineFile);
            std::string path = read_online_file_dest_path(env, onlineFile);
            std::string fileUrl = read_online_file_string(env, onlineFile, "getFileDownloadUrl");
            std::string resourceUrl = read_online_file_string(env, onlineFile, "getResourceDownloadUrl");
            LOGW("stub_online_run: %s ReadOnlineFile[%d] chapterId=%d path=%s size=%lld effectiveCid=%d fileUrlEmpty=%d resourceUrlEmpty=%d fileUrl=%s resourceUrl=%s",
                 label ? label : "diag",
                 index,
                 chapterId,
                 path.c_str(),
                 file_size_or_negative(path),
                 effectiveCid,
                 fileUrl.empty() ? 1 : 0,
                 resourceUrl.empty() ? 1 : 0,
                 preview_for_log(fileUrl, 512).c_str(),
                 preview_for_log(resourceUrl, 512).c_str());
            if (onlineFile != nullptr) env->DeleteLocalRef(onlineFile);
            index++;
            if (index >= 20) {
                LOGW("stub_online_run: %s ReadOnlineFile logging truncated at 20 entries", label ? label : "diag");
                break;
            }
        }
        env->DeleteLocalRef(iterator);
    }
    env->DeleteLocalRef(files);
}

static bool online_result_has_usable_file(JNIEnv* env, jobject result, jint effectiveCid) {
    if (result == nullptr) return false;
    jobject files = read_online_result_files(env, result);
    if (files == nullptr) return false;

    bool found = false;
    jclass iterableClass = env->FindClass("java/lang/Iterable");
    jmethodID iteratorMethod = iterableClass == nullptr ? nullptr :
            env->GetMethodID(iterableClass, "iterator", "()Ljava/util/Iterator;");
    if (iterableClass != nullptr) env->DeleteLocalRef(iterableClass);
    jobject iterator = iteratorMethod == nullptr ? nullptr : env->CallObjectMethod(files, iteratorMethod);
    clear_logged_exception(env, "stub_online_run usable files.iterator");
    if (iterator != nullptr) {
        jclass iteratorClass = env->FindClass("java/util/Iterator");
        jmethodID hasNext = iteratorClass == nullptr ? nullptr :
                env->GetMethodID(iteratorClass, "hasNext", "()Z");
        jmethodID next = iteratorClass == nullptr ? nullptr :
                env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");
        if (iteratorClass != nullptr) env->DeleteLocalRef(iteratorClass);
        while (hasNext != nullptr && next != nullptr && env->CallBooleanMethod(iterator, hasNext)) {
            if (clear_logged_exception(env, "stub_online_run usable iterator.hasNext")) break;
            jobject onlineFile = env->CallObjectMethod(iterator, next);
            if (clear_logged_exception(env, "stub_online_run usable iterator.next")) {
                if (onlineFile != nullptr) env->DeleteLocalRef(onlineFile);
                break;
            }
            jint chapterId = read_online_file_chapter_id(env, onlineFile);
            std::string path = read_online_file_dest_path(env, onlineFile);
            bool chapterMatches = effectiveCid <= 0 || chapterId == effectiveCid;
            if (chapterMatches && file_size_or_negative(path) > 0) {
                found = true;
                if (onlineFile != nullptr) env->DeleteLocalRef(onlineFile);
                break;
            }
            if (onlineFile != nullptr) env->DeleteLocalRef(onlineFile);
        }
        env->DeleteLocalRef(iterator);
    }
    env->DeleteLocalRef(files);
    return found;
}

static bool materialize_online_eqct(
        JNIEnv* env,
        jobject result,
        jint effectiveCid,
        const std::string& expectedEqctPath,
        const std::string& fallbackAllPath) {
    if (result == nullptr || expectedEqctPath.empty()) return false;

    long long existingSize = file_size_or_negative(expectedEqctPath);
    if (existingSize > 0) {
        LOGW("stub_online_run: expected eqct already exists path=%s size=%lld",
             expectedEqctPath.c_str(),
             existingSize);
        return true;
    }

    std::string bestPath;
    long long bestSize = -1;
    jobject files = read_online_result_files(env, result);
    if (files != nullptr) {
        jclass iterableClass = env->FindClass("java/lang/Iterable");
        jmethodID iteratorMethod = iterableClass == nullptr ? nullptr :
                env->GetMethodID(iterableClass, "iterator", "()Ljava/util/Iterator;");
        if (iterableClass != nullptr) env->DeleteLocalRef(iterableClass);
        jobject iterator = iteratorMethod == nullptr ? nullptr : env->CallObjectMethod(files, iteratorMethod);
        clear_logged_exception(env, "stub_online_run files.iterator");
        if (iterator != nullptr) {
            jclass iteratorClass = env->FindClass("java/util/Iterator");
            jmethodID hasNext = iteratorClass == nullptr ? nullptr :
                    env->GetMethodID(iteratorClass, "hasNext", "()Z");
            jmethodID next = iteratorClass == nullptr ? nullptr :
                    env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");
            if (iteratorClass != nullptr) env->DeleteLocalRef(iteratorClass);
            while (hasNext != nullptr && next != nullptr && env->CallBooleanMethod(iterator, hasNext)) {
                if (clear_logged_exception(env, "stub_online_run iterator.hasNext")) break;
                jobject onlineFile = env->CallObjectMethod(iterator, next);
                if (clear_logged_exception(env, "stub_online_run iterator.next")) {
                    if (onlineFile != nullptr) env->DeleteLocalRef(onlineFile);
                    break;
                }
                jint chapterId = read_online_file_chapter_id(env, onlineFile);
                std::string path = read_online_file_dest_path(env, onlineFile);
                std::string fileUrl = read_online_file_string(env, onlineFile, "getFileDownloadUrl");
                std::string resourceUrl = read_online_file_string(env, onlineFile, "getResourceDownloadUrl");
                long long size = file_size_or_negative(path);
                LOGW("stub_online_run: ReadOnlineFile chapterId=%d path=%s size=%lld effectiveCid=%d fileUrl=%s resourceUrl=%s",
                     chapterId,
                     path.c_str(),
                     size,
                     effectiveCid,
                     fileUrl.c_str(),
                     resourceUrl.c_str());
                if (chapterId == effectiveCid && size <= 0) {
                    if (!fileUrl.empty()) {
                        download_url_to_file(env, fileUrl, path);
                        size = file_size_or_negative(path);
                    }
                    if (size <= 0 && !resourceUrl.empty()) {
                        download_url_to_file(env, resourceUrl, path);
                        size = file_size_or_negative(path);
                    }
                }
                if (size > 0 && chapterId == effectiveCid) {
                    bestPath = path;
                    bestSize = size;
                    if (onlineFile != nullptr) env->DeleteLocalRef(onlineFile);
                    break;
                }
                if (size > 0 && bestPath.empty()) {
                    bestPath = path;
                    bestSize = size;
                }
                if (onlineFile != nullptr) env->DeleteLocalRef(onlineFile);
            }
            env->DeleteLocalRef(iterator);
        }
        env->DeleteLocalRef(files);
    }

    if (bestPath.empty()) {
        LOGW("stub_online_run: no materializable chapter source expected=%s fallbackAll=%s fallbackAllSize=%lld",
             expectedEqctPath.c_str(),
             fallbackAllPath.c_str(),
             file_size_or_negative(fallbackAllPath));
        return false;
    }
    return copy_file_binary(bestPath, expectedEqctPath);
}

static jobject read_online_search_from_url(JNIEnv* env, jobject tag, const std::string& url) {
    if (tag == nullptr) {
        LOGW("stub_online_run: cannot call ReadOnline.search without tag");
        return nullptr;
    }

    jobject inputStream = open_url_input_stream(env, url);
    if (inputStream == nullptr) {
        LOGW("stub_online_run: direct fetch failed url=%s", url.c_str());
        return nullptr;
    }

    jclass readOnlineClass = env->FindClass("com/qq/reader/common/protocol/ReadOnline");
    if (readOnlineClass == nullptr) {
        clear_logged_exception(env, "stub_online_run FindClass ReadOnline");
        close_java_closeable(env, inputStream, "stub_online_run close inputStream after missing ReadOnline");
        env->DeleteLocalRef(inputStream);
        return nullptr;
    }

    jmethodID search = env->GetStaticMethodID(
            readOnlineClass,
            "search",
            "(Ljava/io/InputStream;Lcom/qq/reader/cservice/onlineread/OnlineTag;Ljava/lang/String;)Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;");
    if (search == nullptr) {
        clear_logged_exception(env, "stub_online_run ReadOnline.search method");
        env->DeleteLocalRef(readOnlineClass);
        close_java_closeable(env, inputStream, "stub_online_run close inputStream after missing search");
        env->DeleteLocalRef(inputStream);
        return nullptr;
    }

    jstring urlString = env->NewStringUTF(url.c_str());
    jobject result = nullptr;
    if (urlString != nullptr) {
        result = env->CallStaticObjectMethod(readOnlineClass, search, inputStream, tag, urlString);
    }
    bool ok = !clear_logged_exception(env, "stub_online_run ReadOnline.search");
    if (urlString != nullptr) env->DeleteLocalRef(urlString);
    env->DeleteLocalRef(readOnlineClass);
    close_java_closeable(env, inputStream, "stub_online_run close inputStream");
    env->DeleteLocalRef(inputStream);

    if (!ok || result == nullptr) {
        LOGW("stub_online_run: ReadOnline.search failed ok=%d result=%s",
             ok ? 1 : 0,
             object_class_name(env, result).c_str());
        if (result != nullptr) env->DeleteLocalRef(result);
        return nullptr;
    }

    LOGW("stub_online_run: ReadOnline.search returned resultCode=%d result=%s",
         read_online_result_code(env, result),
         object_to_string(env, result).c_str());
    return result;
}

static jobject read_online_search_via_reader_protocol(
        JNIEnv* env,
        jobject onlineTask,
        jobject tag,
        const std::string& url) {
    if (tag == nullptr || url.empty()) {
        LOGW("stub_online_run: protocol fallback skipped: missing tag/url");
        return nullptr;
    }
    if (g_hook_classloader == nullptr) {
        LOGW("stub_online_run: protocol fallback unavailable: no hook ClassLoader");
        return nullptr;
    }
    if (!ensure_classloader_loadclass(env)) {
        LOGW("stub_online_run: protocol fallback unavailable: no ClassLoader.loadClass");
        return nullptr;
    }

    jstring helperName = env->NewStringUTF("com.multiapp.core.hook.QqReaderOnlineProtocolFallback");
    jclass helperClass = helperName == nullptr ? nullptr :
            (jclass)env->CallObjectMethod(g_hook_classloader, g_classloader_loadclass, helperName);
    if (helperName != nullptr) env->DeleteLocalRef(helperName);
    if (helperClass == nullptr) {
        clear_logged_exception(env, "stub_online_run load protocol helper");
        LOGW("stub_online_run: protocol fallback helper class not found");
        return nullptr;
    }

    jmethodID fetch = env->GetStaticMethodID(
            helperClass,
            "fetch",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
    if (fetch == nullptr) {
        clear_logged_exception(env, "stub_online_run protocol helper method");
        env->DeleteLocalRef(helperClass);
        return nullptr;
    }

    jstring urlString = env->NewStringUTF(url.c_str());
    jobject result = nullptr;
    if (urlString != nullptr) {
        result = env->CallStaticObjectMethod(helperClass, fetch, onlineTask, tag, urlString);
    }
    bool ok = !clear_logged_exception(env, "stub_online_run protocol helper fetch");
    if (urlString != nullptr) env->DeleteLocalRef(urlString);
    env->DeleteLocalRef(helperClass);

    if (!ok || result == nullptr) {
        LOGW("stub_online_run: protocol fallback returned result=%s ok=%d",
             object_class_name(env, result).c_str(),
             ok ? 1 : 0);
        if (result != nullptr) env->DeleteLocalRef(result);
        return nullptr;
    }

    LOGW("stub_online_run: protocol fallback returned resultCode=%d result=%s",
         read_online_result_code(env, result),
         object_to_string(env, result).c_str());
    return result;
}

static bool materialize_mini_content_eqct(
        JNIEnv* env,
        jobject tag,
        const std::string& bid,
        jint cid,
        const std::string& expectedEqctPath) {
    if (bid.empty() || cid <= 0 || expectedEqctPath.empty()) {
        LOGW("stub_online_run: mini materialize JNI skipped bid=%s cid=%d expectedEqct=%s",
             bid.c_str(),
             cid,
             expectedEqctPath.c_str());
        return false;
    }
    if (g_hook_classloader == nullptr) {
        LOGW("stub_online_run: mini materialize JNI unavailable: no hook ClassLoader");
        return false;
    }
    if (!ensure_classloader_loadclass(env)) {
        LOGW("stub_online_run: mini materialize JNI unavailable: no ClassLoader.loadClass");
        return false;
    }

    jstring helperName = env->NewStringUTF("com.multiapp.core.hook.QqReaderOnlineProtocolFallback");
    jclass helperClass = helperName == nullptr ? nullptr :
            (jclass)env->CallObjectMethod(g_hook_classloader, g_classloader_loadclass, helperName);
    if (helperName != nullptr) env->DeleteLocalRef(helperName);
    if (helperClass == nullptr) {
        clear_logged_exception(env, "stub_online_run load mini materialize helper");
        LOGW("stub_online_run: mini materialize helper class not found");
        return false;
    }

    jmethodID materialize = env->GetStaticMethodID(
            helperClass,
            "materializeMiniContentEqct",
            "(Ljava/lang/Object;Ljava/lang/String;ILjava/lang/String;)Z");
    if (materialize == nullptr) {
        clear_logged_exception(env, "stub_online_run mini materialize helper method");
        env->DeleteLocalRef(helperClass);
        return false;
    }

    jstring jBid = env->NewStringUTF(bid.c_str());
    jstring jExpected = env->NewStringUTF(expectedEqctPath.c_str());
    bool ok = false;
    if (jBid != nullptr && jExpected != nullptr) {
        ok = env->CallStaticBooleanMethod(helperClass, materialize, tag, jBid, cid, jExpected) == JNI_TRUE;
    }
    bool noException = !clear_logged_exception(env, "stub_online_run mini materialize helper call");
    if (jBid != nullptr) env->DeleteLocalRef(jBid);
    if (jExpected != nullptr) env->DeleteLocalRef(jExpected);
    env->DeleteLocalRef(helperClass);

    long long size = file_size_or_negative(expectedEqctPath);
    LOGW("stub_online_run: mini materialize JNI result ok=%d noException=%d expectedEqct=%s size=%lld",
         ok ? 1 : 0,
         noException ? 1 : 0,
         expectedEqctPath.c_str(),
         size);
    return ok && noException && size > 0;
}

static std::string replace_query_param_value(
        const std::string& url,
        const std::string& key,
        const std::string& value) {
    const std::string prefix = key + "=";
    size_t start = url.find(prefix);
    if (start == std::string::npos) return url;
    start += prefix.size();
    size_t end = url.find('&', start);
    std::string out = url.substr(0, start) + value;
    if (end != std::string::npos) {
        out += url.substr(end);
    }
    return out;
}

static std::string build_online_chapter_url_for_current_cid(
        const std::string& tagUrl,
        jint tagCid,
        jint tagProgressCid) {
    jint effectiveCid = tagCid > 0 ? tagCid : tagProgressCid;
    if (effectiveCid <= 0) return tagUrl;
    if (tagUrl.find("scids=") == std::string::npos) return tagUrl;
    return replace_query_param_value(tagUrl, "scids", std::to_string(effectiveCid));
}

static bool online_run_fallback_enabled() {
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get("debug.multiapp.online.run_fallback", value);
    if (len <= 0) return false;
    return strcmp(value, "1") == 0 || strcasecmp(value, "true") == 0;
}

static bool online_materialize_eqct_enabled() {
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get("debug.multiapp.online.materialize_eqct", value);
    if (len <= 0) return false;
    return strcmp(value, "1") == 0 || strcasecmp(value, "true") == 0;
}

static void JNICALL stub_online_run(JNIEnv* env, jobject thiz) {
    if (!online_run_fallback_enabled()) {
        LOGW("stub_online_run: fallback disabled by debug.multiapp.online.run_fallback");
        return;
    }

    jobject chapters = ensure_online_download_list(env, thiz);
    jobject tag = get_object_field(env, thiz, "tag", "Lcom/qq/reader/cservice/onlineread/OnlineTag;");
    jobject listener = get_object_field(env, thiz, "mListener", "Lcom/qq/reader/cservice/onlineread/qdaf;");
    jstring bid = (jstring)get_object_field(env, thiz, "bid", "Ljava/lang/String;");
    jstring bookName = (jstring)get_object_field(env, thiz, "bookName", "Ljava/lang/String;");
    jstring scene = (jstring)get_object_field(env, thiz, "mScene", "Ljava/lang/String;");
    jstring bookTaskId = (jstring)get_object_field(env, thiz, "bookTaskId", "Ljava/lang/String;");
    jobject taskDownloadListener = get_object_field(env, thiz, "taskDownloadListener", "Lcom/qq/reader/common/conn/http/search/qdaf$qdaa;");
    jobject downloadListener = get_object_field(env, thiz, "mDownloadListener", "Lcom/qq/reader/common/conn/http/search/qdaf$qdaa;");
    jobject result = get_object_field(env, thiz, "mResult", "Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;");
    jobject preloadResult = get_object_field(env, thiz, "preLoadResult", "Lcom/qq/reader/common/protocol/ReadOnline$ReadOnlineResult;");

    std::string bidStr = jstring_to_string(env, bid);
    std::string bookNameStr = jstring_to_string(env, bookName);
    std::string sceneStr = jstring_to_string(env, scene);
    std::string bookTaskIdStr = jstring_to_string(env, bookTaskId);
    std::string chaptersText = object_to_string(env, chapters);
    jint chapterCount = collection_size(env, chapters);
    jint prepareStyle = get_int_field(env, thiz, "prepareChapterStyle", -1);
    jboolean background = get_boolean_field(env, thiz, "mRunInBackground");
    jboolean batch = get_boolean_field(env, thiz, "mBatDownload");

    std::string tagBookId;
    std::string tagBookName;
    std::string tagRaw;
    std::string tagChapterPath;
    std::string tagFileType;
    std::string tagBaseDir;
    std::string tagChapterQPath;
    std::string tagBookMetaPath;
    std::string tagCurrentPath;
    std::string tagSearchPath;
    std::string tagUrl;
    jint tagCid = -1;
    jint tagNextCid = -1;
    jint tagProgressCid = -1;
    jlong tagLongA = -1;
    jlong tagLongR = -1;
    if (tag != nullptr) {
        jstring tagBookIdValue = (jstring)get_object_field(env, tag, "cihai", "Ljava/lang/String;");
        jstring tagBookNameValue = (jstring)get_object_field(env, tag, "d", "Ljava/lang/String;");
        jstring tagRawValue = (jstring)get_object_field(env, tag, "h", "Ljava/lang/String;");
        jstring tagChapterPathValue = (jstring)get_object_field(env, tag, "m", "Ljava/lang/String;");
        jstring tagFileTypeValue = (jstring)get_object_field(env, tag, "t", "Ljava/lang/String;");
        tagBookId = jstring_to_string(env, tagBookIdValue);
        tagBookName = jstring_to_string(env, tagBookNameValue);
        tagRaw = jstring_to_string(env, tagRawValue);
        tagChapterPath = jstring_to_string(env, tagChapterPathValue);
        tagFileType = jstring_to_string(env, tagFileTypeValue);
        tagCid = get_int_field(env, tag, "f", -1);
        tagNextCid = get_int_field(env, tag, "g", -1);
        tagProgressCid = get_int_field(env, tag, "q", -1);
        tagLongA = get_long_field(env, tag, "a", -1);
        tagLongR = get_long_field(env, tag, "r", -1);
        jstring tagBaseDirValue = call_string_method(env, tag, "a", "()Ljava/lang/String;");
        jstring tagChapterQPathValue = call_string_method(env, tag, "b", "()Ljava/lang/String;");
        jstring tagBookMetaPathValue = call_string_method(env, tag, "c", "()Ljava/lang/String;");
        jstring tagCurrentPathValue = call_string_method(env, tag, "d", "()Ljava/lang/String;");
        jstring tagUrlValue = nullptr;
        jstring emptyArg = env->NewStringUTF("");
        if (emptyArg != nullptr) {
            tagUrlValue = call_string_method(env, tag, "f", "(Ljava/lang/String;)Ljava/lang/String;", emptyArg);
            env->DeleteLocalRef(emptyArg);
        }
        jstring tagSearchPathValue = nullptr;
        if (tagCid >= 0) {
            tagSearchPathValue = call_string_method(env, tag, "search", "(I)Ljava/lang/String;", tagCid);
            tagSearchPath = jstring_to_string(env, tagSearchPathValue);
        }
        tagBaseDir = jstring_to_string(env, tagBaseDirValue);
        tagChapterQPath = jstring_to_string(env, tagChapterQPathValue);
        tagBookMetaPath = jstring_to_string(env, tagBookMetaPathValue);
        tagCurrentPath = jstring_to_string(env, tagCurrentPathValue);
        tagUrl = jstring_to_string(env, tagUrlValue);
        if (tagBookIdValue != nullptr) env->DeleteLocalRef(tagBookIdValue);
        if (tagBookNameValue != nullptr) env->DeleteLocalRef(tagBookNameValue);
        if (tagRawValue != nullptr) env->DeleteLocalRef(tagRawValue);
        if (tagChapterPathValue != nullptr) env->DeleteLocalRef(tagChapterPathValue);
        if (tagFileTypeValue != nullptr) env->DeleteLocalRef(tagFileTypeValue);
        if (tagBaseDirValue != nullptr) env->DeleteLocalRef(tagBaseDirValue);
        if (tagChapterQPathValue != nullptr) env->DeleteLocalRef(tagChapterQPathValue);
        if (tagBookMetaPathValue != nullptr) env->DeleteLocalRef(tagBookMetaPathValue);
        if (tagCurrentPathValue != nullptr) env->DeleteLocalRef(tagCurrentPathValue);
        if (tagSearchPathValue != nullptr) env->DeleteLocalRef(tagSearchPathValue);
        if (tagUrlValue != nullptr) env->DeleteLocalRef(tagUrlValue);
    }

    LOGW("stub_online_run: native run unavailable; task bid=%s bookName=%s bookTaskId=%s scene=%s chapters=%d chaptersText=%s prepareStyle=%d background=%d batch=%d listenerClass=%s taskDownloadListener=%s downloadListener=%s result=%s preLoadResult=%s",
         bidStr.c_str(),
         bookNameStr.c_str(),
         bookTaskIdStr.c_str(),
         sceneStr.c_str(),
         chapterCount,
         chaptersText.c_str(),
         prepareStyle,
         background ? 1 : 0,
         batch ? 1 : 0,
         object_class_name(env, listener).c_str(),
         object_class_name(env, taskDownloadListener).c_str(),
         object_class_name(env, downloadListener).c_str(),
         object_class_name(env, result).c_str(),
         object_class_name(env, preloadResult).c_str());
    LOGW("stub_online_run: tag class=%s bookId=%s bookName=%s cid=%d nextCid=%d progressCid=%d longA=%lld longR=%lld raw=%s chapterPath=%s fileType=%s",
         object_class_name(env, tag).c_str(),
         tagBookId.c_str(),
         tagBookName.c_str(),
         tagCid,
         tagNextCid,
         tagProgressCid,
         (long long)tagLongA,
         (long long)tagLongR,
         tagRaw.c_str(),
         tagChapterPath.c_str(),
         tagFileType.c_str());
    LOGW("stub_online_run: tag derived baseDir=%s chapterQ=%s bookMeta=%s currentPath=%s searchPath=%s url=%s",
         tagBaseDir.c_str(),
         tagChapterQPath.c_str(),
         tagBookMetaPath.c_str(),
         tagCurrentPath.c_str(),
         tagSearchPath.c_str(),
         tagUrl.c_str());
    jint effectiveCid = tagCid > 0 ? tagCid : tagProgressCid;
    std::string expectedEqctPath = tagCurrentPath;
    if (expectedEqctPath.empty() && !tagBaseDir.empty() && effectiveCid > 0) {
        expectedEqctPath = tagBaseDir + std::to_string(effectiveCid) + ".eqct";
    }
    std::string fallbackAllPath;
    if (!tagBaseDir.empty() && !tagBookId.empty()) {
        fallbackAllPath = tagBaseDir + tagBookId + "_ALL_o";
    }
    std::string infoPath = tagBaseDir.empty() ? "" : tagBaseDir + "info.txt";
    jobject callbackResult = result != nullptr ? result : preloadResult;
    jobject fetchedResult = nullptr;
    bool allowMaterialize = online_materialize_eqct_enabled();
    bool materialized = false;
    bool materializeAttempted = false;
    if (callbackResult == nullptr && tag != nullptr && !tagUrl.empty()) {
        std::string fetchUrl = build_online_chapter_url_for_current_cid(tagUrl, tagCid, tagProgressCid);
        LOGW("stub_online_run: attempting ReaderProtocolTask ReadOnline fetch url=%s originalUrl=%s effectiveCid=%d",
             fetchUrl.c_str(),
             tagUrl.c_str(),
             effectiveCid);
        fetchedResult = read_online_search_via_reader_protocol(env, thiz, tag, fetchUrl);
        if (fetchedResult == nullptr) {
            LOGW("stub_online_run: ReaderProtocolTask fetch failed; falling back to direct URLConnection");
            fetchedResult = read_online_search_from_url(env, tag, fetchUrl);
        }
        callbackResult = fetchedResult;
        LOGW("stub_online_run: post-search files expectedEqct=%s expectedEqctSize=%lld infoPath=%s infoSize=%lld infoPreview=%s allPath=%s allSize=%lld",
             expectedEqctPath.c_str(),
             file_size_or_negative(expectedEqctPath),
             infoPath.c_str(),
             file_size_or_negative(infoPath),
             read_text_file_preview(infoPath).c_str(),
             fallbackAllPath.c_str(),
             file_size_or_negative(fallbackAllPath));
        jint fetchedCode = read_online_result_code(env, callbackResult);
        log_online_result_files(env, callbackResult, effectiveCid, "direct-fetch");
        if (allowMaterialize && callbackResult != nullptr && fetchedCode == 0) {
            std::lock_guard<std::mutex> onlineLock(g_online_materialize_mutex);
            materialized = materialize_online_eqct(
                    env, callbackResult, effectiveCid, expectedEqctPath, fallbackAllPath);
            materializeAttempted = true;
        }
    }
    jint callbackCode = read_online_result_code(env, callbackResult);
    if (callbackResult != nullptr && fetchedResult == nullptr) {
        log_online_result_files(env, callbackResult, effectiveCid, "existing");
    }
    if (allowMaterialize && !materializeAttempted && callbackResult != nullptr && callbackCode == 0) {
        std::lock_guard<std::mutex> onlineLock(g_online_materialize_mutex);
        materialized = materialize_online_eqct(
                env, callbackResult, effectiveCid, expectedEqctPath, fallbackAllPath);
        materializeAttempted = true;
    }
    bool hasUsableChapterFile = callbackResult != nullptr && callbackCode == 0 &&
            (file_size_or_negative(expectedEqctPath) > 0 ||
             online_result_has_usable_file(env, callbackResult, effectiveCid));
    if (callbackResult != nullptr && callbackCode == 0 && !allowMaterialize && hasUsableChapterFile &&
        notify_online_download_success(env, thiz, callbackResult)) {
        LOGW("stub_online_run: completed via %s ReadOnlineResult resultCode=%d without eqct materialize",
             fetchedResult != nullptr ? "direct-fetched" : "existing",
             callbackCode);
    } else if (callbackResult != nullptr && callbackCode == 0 && materialized &&
        notify_online_download_success(env, thiz, callbackResult)) {
        LOGW("stub_online_run: completed via %s ReadOnlineResult resultCode=%d expectedEqct=%s size=%lld",
             fetchedResult != nullptr ? "direct-fetched" : "existing",
             callbackCode,
             expectedEqctPath.c_str(),
             file_size_or_negative(expectedEqctPath));
    } else if (callbackResult != nullptr && callbackCode == 0) {
        if (allowMaterialize && !hasUsableChapterFile && !expectedEqctPath.empty() && effectiveCid > 0) {
            std::lock_guard<std::mutex> onlineLock(g_online_materialize_mutex);
            materialized = materialize_mini_content_eqct(
                    env,
                    tag,
                    !tagBookId.empty() ? tagBookId : bidStr,
                    effectiveCid,
                    expectedEqctPath);
            hasUsableChapterFile = materialized && file_size_or_negative(expectedEqctPath) > 0;
            if (hasUsableChapterFile && notify_online_download_success(env, thiz, callbackResult)) {
                LOGW("stub_online_run: completed via mini materialized eqct resultCode=%d expectedEqct=%s size=%lld",
                     callbackCode,
                     expectedEqctPath.c_str(),
                     file_size_or_negative(expectedEqctPath));
                if (fetchedResult != nullptr) env->DeleteLocalRef(fetchedResult);
                if (bid != nullptr) env->DeleteLocalRef(bid);
                if (bookName != nullptr) env->DeleteLocalRef(bookName);
                if (scene != nullptr) env->DeleteLocalRef(scene);
                if (bookTaskId != nullptr) env->DeleteLocalRef(bookTaskId);
                if (taskDownloadListener != nullptr) env->DeleteLocalRef(taskDownloadListener);
                if (downloadListener != nullptr) env->DeleteLocalRef(downloadListener);
                if (result != nullptr) env->DeleteLocalRef(result);
                if (preloadResult != nullptr) env->DeleteLocalRef(preloadResult);
                if (tag != nullptr) env->DeleteLocalRef(tag);
                if (listener != nullptr) env->DeleteLocalRef(listener);
                if (chapters != nullptr) env->DeleteLocalRef(chapters);
                return;
            }
        }
        LOGW("stub_online_run: ReadOnlineResult success but no usable chapter file allow=%d materialized=%d hasUsable=%d expectedEqct=%s expectedEqctSize=%lld fallbackAll=%s; dispatching failed",
             allowMaterialize ? 1 : 0,
             materialized ? 1 : 0,
             hasUsableChapterFile ? 1 : 0,
             expectedEqctPath.c_str(),
             file_size_or_negative(expectedEqctPath),
             fallbackAllPath.c_str());
        notify_online_download_failed(env, thiz, callbackResult);
    } else if (callbackResult != nullptr && (callbackCode == -8 || callbackCode == -9)) {
        LOGW("stub_online_run: ReadOnlineResult requires vip/pay resultCode=%d; dispatching needVipOrPay",
             callbackCode);
        if (!notify_online_need_vip_or_pay(env, thiz, callbackResult)) {
            notify_online_download_failed(env, thiz, callbackResult);
        }
    } else if (callbackResult != nullptr) {
        LOGW("stub_online_run: ReadOnlineResult not successful resultCode=%d; dispatching failed",
             callbackCode);
        notify_online_download_failed(env, thiz, callbackResult);
    } else {
        LOGW("stub_online_run: no usable existing ReadOnlineResult; real network implementation still missing");
        notify_online_download_failed(env, thiz);
    }
    if (fetchedResult != nullptr) env->DeleteLocalRef(fetchedResult);
    if (bid != nullptr) env->DeleteLocalRef(bid);
    if (bookName != nullptr) env->DeleteLocalRef(bookName);
    if (scene != nullptr) env->DeleteLocalRef(scene);
    if (bookTaskId != nullptr) env->DeleteLocalRef(bookTaskId);
    if (taskDownloadListener != nullptr) env->DeleteLocalRef(taskDownloadListener);
    if (downloadListener != nullptr) env->DeleteLocalRef(downloadListener);
    if (result != nullptr) env->DeleteLocalRef(result);
    if (preloadResult != nullptr) env->DeleteLocalRef(preloadResult);
    if (tag != nullptr) env->DeleteLocalRef(tag);
    if (listener != nullptr) env->DeleteLocalRef(listener);
    if (chapters != nullptr) env->DeleteLocalRef(chapters);
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
        {const_cast<char*>("interface5"),  const_cast<char*>("(Landroid/app/Application;)V"), (void*)stub_interface5},
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
        {const_cast<char*>("interface21"), const_cast<char*>("(Landroid/app/Application;)V"), (void*)stub_interface21},
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
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterStubInterface20Only(
    JNIEnv* env, jclass clazz, jobject classLoader, jstring className)
{
    (void)clazz;
    if (classLoader == nullptr || className == nullptr) return JNI_FALSE;

    const char* name = env->GetStringUTFChars(className, nullptr);
    if (name == nullptr) return JNI_FALSE;

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jName = env->NewStringUTF(name);
    env->ReleaseStringUTFChars(className, name);

    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, jName);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(clClass);

    if (targetClass == nullptr) {
        LOGW("nativeRegisterStubInterface20Only: class not found via guest ClassLoader");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    JNINativeMethod methods[] = {
        {const_cast<char*>("interface20"), const_cast<char*>("()Z"), (void*)stub_interface_bool},
    };

    jint result = env->RegisterNatives(targetClass, methods, (jint)(sizeof(methods) / sizeof(methods[0])));
    LOGI("nativeRegisterStubInterface20Only: RegisterNatives returned %d for interface20 only", result);
    env->DeleteLocalRef(targetClass);

    if (result == JNI_OK) {
        LOGI("nativeRegisterStubInterface20Only: registered interface20 only");
        return JNI_TRUE;
    }

    LOGW("nativeRegisterStubInterface20Only: RegisterNatives failed with code %d", result);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterStubCoreBootstrapMethods(
    JNIEnv* env, jclass clazz, jobject classLoader, jstring className)
{
    (void)clazz;
    if (classLoader == nullptr || className == nullptr) return JNI_FALSE;

    const char* name = env->GetStringUTFChars(className, nullptr);
    if (name == nullptr) return JNI_FALSE;

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jName = env->NewStringUTF(name);
    env->ReleaseStringUTFChars(className, name);

    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, jName);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(clClass);

    if (targetClass == nullptr) {
        LOGW("nativeRegisterStubCoreBootstrapMethods: class not found via guest ClassLoader");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    JNINativeMethod methods[] = {
        {const_cast<char*>("interface5"), const_cast<char*>("(Landroid/app/Application;)V"), (void*)stub_interface5},
        {const_cast<char*>("interface11"), const_cast<char*>("(I)V"), (void*)stub_interface11},
        {const_cast<char*>("interface20"), const_cast<char*>("()Z"), (void*)stub_interface_bool},
        {const_cast<char*>("interface21"), const_cast<char*>("(Landroid/app/Application;)V"), (void*)stub_interface21},
    };

    jint result = env->RegisterNatives(targetClass, methods, (jint)(sizeof(methods) / sizeof(methods[0])));
    LOGI("nativeRegisterStubCoreBootstrapMethods: RegisterNatives returned %d for interface5/interface11/interface20/interface21", result);
    env->DeleteLocalRef(targetClass);

    if (result == JNI_OK) {
        LOGI("nativeRegisterStubCoreBootstrapMethods: registered interface5/interface11/interface20/interface21");
        return JNI_TRUE;
    }

    LOGW("nativeRegisterStubCoreBootstrapMethods: RegisterNatives failed with code %d", result);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterBusinessStubs(
    JNIEnv* env, jclass clazz, jobject classLoader);

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterStubBootstrapMethods(
    JNIEnv* env, jclass clazz, jobject classLoader, jstring className)
{
    (void)clazz;
    if (classLoader == nullptr || className == nullptr) return JNI_FALSE;

    const char* name = env->GetStringUTFChars(className, nullptr);
    if (name == nullptr) return JNI_FALSE;

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jName = env->NewStringUTF(name);
    env->ReleaseStringUTFChars(className, name);

    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, jName);
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(clClass);

    if (targetClass == nullptr) {
        LOGW("nativeRegisterStubBootstrapMethods: class not found via guest ClassLoader");
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_FALSE;
    }

    JNINativeMethod methods[] = {
        {const_cast<char*>("interface5"), const_cast<char*>("(Landroid/app/Application;)V"), (void*)stub_interface5},
        {const_cast<char*>("interface11"), const_cast<char*>("(I)V"), (void*)stub_interface11},
        {const_cast<char*>("interface20"), const_cast<char*>("()Z"), (void*)stub_interface_bool},
        {const_cast<char*>("interface21"), const_cast<char*>("(Landroid/app/Application;)V"), (void*)stub_interface21},
    };

    jint result = env->RegisterNatives(targetClass, methods, (jint)(sizeof(methods) / sizeof(methods[0])));
    LOGI("nativeRegisterStubBootstrapMethods: RegisterNatives returned %d for bootstrap methods", result);
    env->DeleteLocalRef(targetClass);

    if (result == JNI_OK) {
        LOGI("nativeRegisterStubBootstrapMethods: registered interface5/interface11/interface20/interface21");
        return JNI_TRUE;
    }

    LOGW("nativeRegisterStubBootstrapMethods: RegisterNatives failed with code %d", result);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return JNI_FALSE;
}

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

    auto ywlogin_action_fallback_enabled = []() -> bool {
        char value[PROP_VALUE_MAX] = {0};
        int len = __system_property_get("debug.multiapp.ywlogin.action_fallback", value);
        if (len <= 0) return true;
        if (strcmp(value, "0") == 0 || strcasecmp(value, "false") == 0) return false;
        return strcmp(value, "1") == 0 || strcasecmp(value, "true") == 0;
    };
    auto ywlogin_qrcode_fallback_enabled = []() -> bool {
        char value[PROP_VALUE_MAX] = {0};
        int len = __system_property_get("debug.multiapp.ywlogin.qrcode_fallback", value);
        if (len <= 0) return true;
        return strcmp(value, "1") == 0 || strcasecmp(value, "true") == 0;
    };

    std::vector<StubEntry> stubs = {
        // Minimal login entry shims. Without these, ReaderApplication crashes
        // in initLoginSDK before the main UI is created. Keep login actions
        // untouched so the real YWLogin SDK can RegisterNatives later.
        {"com.yuewen.ywlogin.login.YWLoginManager", "getInstance",
         "()Lcom/yuewen/ywlogin/login/YWLoginManager;", (void*)stub_ywlogin_getInstance},
        {"com.yuewen.ywlogin.login.YWLoginManager", "registerParameter",
         "(Lcom/yuewen/ywlogin/login/IParameterGetter;)V", (void*)stub_ywlogin_registerParameter_tracked},
        {"com.yuewen.ywlogin.login.YWLoginManager", "resetParameter",
         "(Ljava/lang/String;Ljava/lang/String;)V", (void*)stub_ywlogin_resetParameter_tracked},
        {"com.yuewen.ywlogin.login.YWLoginManager", "setDefaultParameters",
         "(Landroid/app/Application;Landroid/content/ContentValues;)V", (void*)stub_ywlogin_setDefaultParameters_tracked},
        {"com.yuewen.ywlogin.login.YWLoginManager", "getDefaultParameters",
         "()Landroid/content/ContentValues;", (void*)stub_ywlogin_getDefaultParameters_tracked},
        {"com.yuewen.ywlogin.login.YWLoginManager", "getCommonParamaters",
         "()Landroid/content/ContentValues;", (void*)stub_ywlogin_getCommonParamaters},
        {"com.yuewen.ywlogin.login.YWLoginManager", "saveParameters",
         "(Landroid/content/ContentValues;)V", (void*)stub_ywlogin_saveParameters},
        {"com.yuewen.ywlogin.login.YWLoginManager", "refreshParameters",
         "()V", (void*)stub_ywlogin_refreshParameters},
        {"com.yuewen.ywlogin.login.YWLoginManager", "getSignCallback",
         "()Lcom/yuewen/ywlogin/login/ParamsSignCallback;", (void*)stub_ywlogin_getSignCallback},
        {"com.yuewen.ywlogin.login.YWLoginManager", "setSignCallback",
         "(Lcom/yuewen/ywlogin/login/ParamsSignCallback;)V", (void*)stub_ywlogin_setSignCallback},
        {"com.yuewen.ywlogin.login.YWLoginManager", "fetchSettings",
         "(Lcom/yuewen/ywlogin/callbacks/DefaultYWCallback;)V", (void*)stub_ywlogin_fetchSettings},
        {"com.qq.reader.common.utils.crypto.EasyEncrypt", "getMd5Key",
         "()Ljava/lang/String;", (void*)stub_easyencrypt_md5_key},
        {"com.qq.reader.common.utils.crypto.EasyEncrypt", "decrypt",
         "([B)[B", (void*)stub_easyencrypt_bytes_identity},
        {"com.qq.reader.common.utils.crypto.EasyEncrypt", "encrypt",
         "([B)[B", (void*)stub_easyencrypt_bytes_identity},
        // Keep Fock payload signing/encryption untouched. Faking those avoids
        // crashes but causes bookcity responses to fail.
        // libfock.so JNI_OnLoad 返回 -1 → 函数指针表为空 → 调用即 SIGSEGV
        // 注册多种签名（反射无法确定确切签名），全部返回空字符串
    };

    if (ywlogin_action_fallback_enabled()) {
        stubs.push_back({"com.yuewen.ywlogin.login.YWLoginManager", "pwdLogin",
                         "(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;Lcom/yuewen/ywlogin/login/YWCallBack;)V", (void*)wrapped_ywlogin_pwdLogin});
        stubs.push_back({"com.yuewen.ywlogin.login.YWLoginManager", "sendPhoneCode",
                         "(Landroid/content/Context;Ljava/lang/String;IILcom/yuewen/ywlogin/login/YWCallBack;)V", (void*)wrapped_ywlogin_sendPhoneCode});
        LOGW("nativeRegisterBusinessStubs: YWLoginManager action fallback enabled by debug.multiapp.ywlogin.action_fallback");
    } else {
        LOGI("nativeRegisterBusinessStubs: YWLoginManager action fallback disabled; waiting for real SDK RegisterNatives");
    }
    if (ywlogin_qrcode_fallback_enabled()) {
        stubs.push_back({"com.yuewen.ywlogin.login.YWLoginManager", "qrCodeV2",
                         "(Lcom/yuewen/ywlogin/callbacks/DefaultYWCallback;)V", (void*)wrapped_ywlogin_qrCodeV2});
        LOGW("nativeRegisterBusinessStubs: YWLoginManager qrCodeV2 fallback enabled");
    } else {
        LOGI("nativeRegisterBusinessStubs: YWLoginManager qrCodeV2 fallback disabled");
    }

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
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterOnlineChapterStateStubs(
    JNIEnv* env, jclass clazz, jobject classLoader)
{
    (void)clazz;
    if (classLoader == nullptr) return JNI_FALSE;

    if (!ensure_classloader_loadclass(env)) {
        return JNI_FALSE;
    }

    jstring name = env->NewStringUTF("com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask");
    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, g_classloader_loadclass, name);
    env->DeleteLocalRef(name);
    if (targetClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeRegisterOnlineChapterStateStubs: class not found");
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
        {const_cast<char*>("getScene"),
         const_cast<char*>("()Ljava/lang/String;"), (void*)stub_online_getScene},
        {const_cast<char*>("setScene"),
         const_cast<char*>("(Ljava/lang/String;)V"), (void*)stub_online_setScene},
    };

    jint result = env->RegisterNatives(targetClass, methods, (jint)(sizeof(methods) / sizeof(methods[0])));
    if (result != JNI_OK) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeRegisterOnlineChapterStateStubs: RegisterNatives failed code=%d", result);
        env->DeleteLocalRef(targetClass);
        return JNI_FALSE;
    }

    env->DeleteLocalRef(targetClass);
    LOGI("nativeRegisterOnlineChapterStateStubs: registered %d methods", (int)(sizeof(methods) / sizeof(methods[0])));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeRegisterOnlineChapterDownloadFallbackStubs(
    JNIEnv* env, jclass clazz, jobject classLoader)
{
    (void)clazz;
    if (classLoader == nullptr) return JNI_FALSE;

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    if (clClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeRegisterOnlineChapterDownloadFallbackStubs: ClassLoader class not found");
        return JNI_FALSE;
    }
    jmethodID loadClass = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    env->DeleteLocalRef(clClass);
    if (loadClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeRegisterOnlineChapterDownloadFallbackStubs: ClassLoader.loadClass not found");
        return JNI_FALSE;
    }

    jstring name = env->NewStringUTF("com.qq.reader.cservice.onlineread.OnlineChapterDownloadTask");
    if (name == nullptr) {
        LOGW("nativeRegisterOnlineChapterDownloadFallbackStubs: NewStringUTF class name failed");
        return JNI_FALSE;
    }
    LOGI("nativeRegisterOnlineChapterDownloadFallbackStubs: loading OnlineChapterDownloadTask");
    jclass targetClass = (jclass)env->CallObjectMethod(classLoader, loadClass, name);
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("nativeRegisterOnlineChapterDownloadFallbackStubs: loadClass threw");
        return JNI_FALSE;
    }
    if (targetClass == nullptr) {
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
        {const_cast<char*>("buildUrl"),
         const_cast<char*>("(Lcom/qq/reader/common/conn/search/qdac;)Ljava/lang/String;"), (void*)stub_online_buildUrl},
        {const_cast<char*>("obtainHeaders"),
         const_cast<char*>("()Ljava/util/HashMap;"), (void*)stub_online_obtainHeaders},
        {const_cast<char*>("downloadChapterFile"),
         const_cast<char*>("(Ljava/lang/String;)Ljava/io/File;"), (void*)stub_online_downloadChapterFile},
    };

    LOGI("nativeRegisterOnlineChapterDownloadFallbackStubs: registering state/listener/url stubs");
    jint result = env->RegisterNatives(targetClass, methods, (jint)(sizeof(methods) / sizeof(methods[0])));
    if (result != JNI_OK) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("nativeRegisterOnlineChapterDownloadFallbackStubs: RegisterNatives failed code=%d", result);
        env->DeleteLocalRef(targetClass);
        return JNI_FALSE;
    }

    int registered = (int)(sizeof(methods) / sizeof(methods[0]));
    if (online_run_fallback_enabled()) {
        LOGI("nativeRegisterOnlineChapterDownloadFallbackStubs: registering run fallback");
        JNINativeMethod runMethod = {
            const_cast<char*>("run"),
            const_cast<char*>("()V"),
            (void*)stub_online_run
        };
        jint runResult = env->RegisterNatives(targetClass, &runMethod, 1);
        if (runResult == JNI_OK) {
            registered++;
            LOGW("nativeRegisterOnlineChapterDownloadFallbackStubs: run fallback registered");
        } else {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LOGW("nativeRegisterOnlineChapterDownloadFallbackStubs: run fallback failed code=%d", runResult);
        }
    } else {
        LOGI("nativeRegisterOnlineChapterDownloadFallbackStubs: run fallback disabled");
    }

    env->DeleteLocalRef(targetClass);
    LOGI("nativeRegisterOnlineChapterDownloadFallbackStubs: registered %d methods", registered);
    return JNI_TRUE;
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

    // PT_LOAD segments — 包括 BSS 段（p_filesz==0 但 p_memsz>0）
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (phdrs[i].p_type != PT_LOAD) continue;
        if (phdrs[i].p_memsz == 0) continue;

        uintptr_t seg_addr = info->dlpi_addr + phdrs[i].p_vaddr;
        if (seg_addr == 0 || phdrs[i].p_memsz > max_end) continue;

        // 写入文件中有数据的部分
        if (phdrs[i].p_filesz > 0) {
            void* seg = reinterpret_cast<void*>(seg_addr);
            fseek(out, phdrs[i].p_offset, SEEK_SET);
            fwrite(seg, 1, phdrs[i].p_filesz, out);
        }
        // BSS 段：p_filesz < p_memsz，用零填充剩余部分
        if (phdrs[i].p_memsz > phdrs[i].p_filesz) {
            size_t bss_size = phdrs[i].p_memsz - phdrs[i].p_filesz;
            // 写入运行时解密的 BSS 内容（已解密到内存中）
            void* bss_addr = reinterpret_cast<void*>(seg_addr + phdrs[i].p_filesz);
            fseek(out, phdrs[i].p_offset + phdrs[i].p_filesz, SEEK_SET);
            fwrite(bss_addr, 1, bss_size, out);
        }
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

JNIEXPORT jint JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeDumpJiaguRuntimeRanges(
    JNIEnv* env, jclass clazz, jstring dumpDir)
{
    (void)clazz;
    if (dumpDir == nullptr) return 0;

    const char* dir = env->GetStringUTFChars(dumpDir, nullptr);
    if (dir == nullptr) return 0;
    int count = dump_jiagu_runtime_ranges(dir);
    env->ReleaseStringUTFChars(dumpDir, dir);
    return count;
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
static std::unordered_map<jmethodID, std::string> g_method_shorty_cache;
static std::mutex g_method_shorty_mutex;

static char class_to_shorty_char(JNIEnv* env, jobject class_obj, bool is_return_type) {
    if (class_obj == nullptr) return is_return_type ? 'V' : 'L';
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return is_return_type ? 'V' : 'L';
    }
    jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    if (getName == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(classClass);
        return is_return_type ? 'V' : 'L';
    }
    jstring nameObj = static_cast<jstring>(env->CallObjectMethod(class_obj, getName));
    if (env->ExceptionCheck() || nameObj == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(classClass);
        return is_return_type ? 'V' : 'L';
    }
    const char* name = env->GetStringUTFChars(nameObj, nullptr);
    char result = 'L';
    if (name != nullptr) {
        if (strcmp(name, "void") == 0) result = 'V';
        else if (strcmp(name, "boolean") == 0) result = 'Z';
        else if (strcmp(name, "byte") == 0) result = 'B';
        else if (strcmp(name, "char") == 0) result = 'C';
        else if (strcmp(name, "short") == 0) result = 'S';
        else if (strcmp(name, "int") == 0) result = 'I';
        else if (strcmp(name, "long") == 0) result = 'J';
        else if (strcmp(name, "float") == 0) result = 'F';
        else if (strcmp(name, "double") == 0) result = 'D';
        env->ReleaseStringUTFChars(nameObj, name);
    }
    env->DeleteLocalRef(nameObj);
    env->DeleteLocalRef(classClass);
    return result;
}

static std::string build_shorty_for_reflected_executable(JNIEnv* env, jobject method_obj) {
    if (method_obj == nullptr) return "V";
    std::string shorty;
    jclass methodClass = env->FindClass("java/lang/reflect/Method");
    bool isMethod = methodClass != nullptr && env->IsInstanceOf(method_obj, methodClass);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (isMethod) {
        jmethodID getReturnType = env->GetMethodID(methodClass, "getReturnType", "()Ljava/lang/Class;");
        jobject returnType = getReturnType != nullptr ? env->CallObjectMethod(method_obj, getReturnType) : nullptr;
        if (env->ExceptionCheck()) env->ExceptionClear();
        shorty.push_back(class_to_shorty_char(env, returnType, true));
        if (returnType != nullptr) env->DeleteLocalRef(returnType);
    } else {
        shorty.push_back('V');
    }
    if (methodClass != nullptr) env->DeleteLocalRef(methodClass);
    jclass executableClass = env->FindClass("java/lang/reflect/Executable");
    jmethodID getParameterTypes = executableClass != nullptr ? env->GetMethodID(executableClass, "getParameterTypes", "()[Ljava/lang/Class;") : nullptr;
    jobjectArray params = getParameterTypes != nullptr ? static_cast<jobjectArray>(env->CallObjectMethod(method_obj, getParameterTypes)) : nullptr;
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (params != nullptr) {
        jsize count = env->GetArrayLength(params);
        for (jsize i = 0; i < count; ++i) {
            jobject param = env->GetObjectArrayElement(params, i);
            shorty.push_back(class_to_shorty_char(env, param, false));
            if (param != nullptr) env->DeleteLocalRef(param);
        }
        env->DeleteLocalRef(params);
    }
    if (executableClass != nullptr) env->DeleteLocalRef(executableClass);
    return shorty.empty() ? std::string("V") : shorty;
}

static void cache_reflected_method_shorty(JNIEnv* env, jobject method_obj, const char* label) {
    if (method_obj == nullptr) return;
    jmethodID mid = env->FromReflectedMethod(method_obj);
    if (mid == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGW("GetMethodShorty fallback: FromReflectedMethod failed for %s", label ? label : "method");
        return;
    }
    std::string shorty = build_shorty_for_reflected_executable(env, method_obj);
    {
        std::lock_guard<std::mutex> lock(g_method_shorty_mutex);
        g_method_shorty_cache[mid] = shorty;
    }
    LOGI("GetMethodShorty fallback: cached %s mid=%p shorty=%s", label ? label : "method", mid, shorty.c_str());
}

static const char* multiapp_get_method_shorty(JNIEnv* env, jmethodID mid) {
    (void)env;
    std::lock_guard<std::mutex> lock(g_method_shorty_mutex);
    auto it = g_method_shorty_cache.find(mid);
    if (it != g_method_shorty_cache.end()) {
        return it->second.c_str();
    }
    LOGW("GetMethodShorty fallback: cache miss mid=%p", mid);
    return "V";
}

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

    if (!init_shadowhook_for_runtime("nativeInitLsplant")) {
        LOGE("nativeInitLsplant: shadowhook backend unavailable");
        return JNI_FALSE;
    }

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
        std::unique_lock<std::shared_mutex> lock(g_lsplant_stub_mutex);
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
    cache_reflected_method_shorty(env, targetMethod, "target");
    cache_reflected_method_shorty(env, callbackMethodObj, "callback");

    jobject backup = g_lsplant_hook(env, targetMethod, hookerObject, callbackMethodObj);

    env->DeleteLocalRef(hookerClass);
    env->DeleteLocalRef(callbackMethodObj);

    if (backup != nullptr) {
        LOGI("nativeHookMethod: hook succeeded");
        return JNI_TRUE;
    } else {
        LOGE("nativeHookMethod: lsplant::Hook returned null (hook failed)");
        return JNI_FALSE;
    }
}

JNIEXPORT jobject JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeHookMethodWithBackup(
    JNIEnv* env, jclass clazz, jobject targetMethod, jobject hookerObject)
{
    (void)clazz;
    if (!g_lsplant_initialized || g_lsplant_hook == nullptr) {
        LOGE("nativeHookMethodWithBackup: LSPlant not initialized");
        return nullptr;
    }
    if (targetMethod == nullptr || hookerObject == nullptr) {
        LOGE("nativeHookMethodWithBackup: null args");
        return nullptr;
    }
    jclass hookerClass = env->GetObjectClass(hookerObject);
    if (hookerClass == nullptr) return nullptr;

    jmethodID callbackMethodId = env->GetMethodID(
        hookerClass, "callback", "([Ljava/lang/Object;)Ljava/lang/Object;");
    if (callbackMethodId == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(hookerClass);
        return nullptr;
    }
    jobject callbackMethodObj = env->ToReflectedMethod(
        hookerClass, callbackMethodId, JNI_FALSE);
    if (callbackMethodObj == nullptr) {
        env->DeleteLocalRef(hookerClass);
        return nullptr;
    }
    cache_reflected_method_shorty(env, targetMethod, "target");
    cache_reflected_method_shorty(env, callbackMethodObj, "callback");

    jobject backup = g_lsplant_hook(env, targetMethod, hookerObject, callbackMethodObj);
    env->DeleteLocalRef(hookerClass);
    env->DeleteLocalRef(callbackMethodObj);

    if (backup != nullptr) {
        LOGI("nativeHookMethodWithBackup: success, backup=%p", backup);
        return backup;
    }
    LOGE("nativeHookMethodWithBackup: lsplant::Hook returned null");
    return nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeUnhookMethod(
    JNIEnv* env, jclass clazz, jobject targetMethod)
{
    (void)clazz;
    if (!g_lsplant_initialized || g_lsplant_unhook == nullptr) return JNI_FALSE;
    return g_lsplant_unhook(env, targetMethod) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeIsMethodHooked(
    JNIEnv* env, jclass clazz, jobject method)
{
    (void)clazz;
    if (!g_lsplant_initialized || g_lsplant_is_hooked == nullptr) return JNI_FALSE;
    return g_lsplant_is_hooked(env, method) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_multiapp_core_hook_NativeHookBridge_nativeDeoptimizeMethod(
    JNIEnv* env, jclass clazz, jobject method)
{
    (void)clazz;
    if (!g_lsplant_initialized || g_lsplant_deoptimize == nullptr) return JNI_FALSE;
    return g_lsplant_deoptimize(env, method) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    (void)reserved;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: early LSPlant init begin");
    jboolean ok = Java_com_multiapp_core_hook_NativeHookBridge_nativeInitLsplant(env, nullptr, nullptr);
    LOGI("JNI_OnLoad: early LSPlant init result=%d", ok == JNI_TRUE ? 1 : 0);
    return JNI_VERSION_1_6;
}

} // extern "C"
