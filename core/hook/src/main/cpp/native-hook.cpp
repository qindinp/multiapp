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
#include <unistd.h>

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

// Type alias matching ART's native signature for Runtime.nativeLoad
// static jni: (JNIEnv*, jclass, jstring filename, jobject classLoader, jclass caller) -> jstring
typedef jstring (*NativeLoadFn)(JNIEnv*, jclass, jstring, jobject, jclass);

/**
 * Runtime.nativeLoad hook — loads native libraries via dlopen + JNI_OnLoad
 * instead of forwarding to ART's nativeLoad which crashes on NULL
 * ProtectionDomain arrays in virtual environment caller classes.
 *
 * ART's nativeLoad accesses caller.getProtectionDomain() internally and
 * crashes with SIGABRT when the ProtectionDomain array is NULL. In virtual
 * environments, guest app classes loaded by custom ClassLoaders often have
 * NULL ProtectionDomain because the Class object isn't fully initialized
 * from ART's perspective.
 *
 * Our approach: load the .so via dlopen, find and call JNI_OnLoad if present.
 * This bypasses ART's ProtectionDomain check entirely while still properly
 * initializing the native library.
 */
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

    LOGI("hooked_nativeLoad: loading '%s' via dlopen (bypassing ART nativeLoad)", path);

    // Try RTLD_NOW first, fall back to RTLD_LAZY
    void* handle = dlopen(path, RTLD_NOW);
    if (handle == nullptr) {
        const char* err = dlerror();
        // Try RTLD_LAZY as fallback
        handle = dlopen(path, RTLD_LAZY);
        if (handle == nullptr) {
            const char* lazyErr = dlerror();
            LOGE("hooked_nativeLoad: dlopen failed for '%s': RTLD_NOW=%s, RTLD_LAZY=%s",
                 path, err ? err : "null", lazyErr ? lazyErr : "null");

            // Fall back to original ART nativeLoad as last resort
            env->ReleaseStringUTFChars(filename, path);
            if (g_orig_nativeLoad_fn != nullptr) {
                LOGW("hooked_nativeLoad: falling back to original ART nativeLoad");
                return ((NativeLoadFn)g_orig_nativeLoad_fn)(env, runtimeClass, filename, classLoader, caller);
            }
            return nullptr;
        }
    }

    LOGI("hooked_nativeLoad: dlopen succeeded for '%s'", path);

    // Call JNI_OnLoad if present — this is what ART's nativeLoad does internally
    // to initialize the JNI native library
    typedef jint (*JNI_OnLoadFn)(JavaVM*, void*);
    JNI_OnLoadFn jniOnLoad = reinterpret_cast<JNI_OnLoadFn>(dlsym(handle, "JNI_OnLoad"));
    if (jniOnLoad != nullptr) {
        JavaVM* vm = nullptr;
        env->GetJavaVM(&vm);
        if (vm != nullptr) {
            LOGI("hooked_nativeLoad: calling JNI_OnLoad for '%s'", path);
            jint jniVersion = jniOnLoad(vm, nullptr);
            if (jniVersion < 0) {
                LOGE("hooked_nativeLoad: JNI_OnLoad failed for '%s' (returned %d)", path, jniVersion);
            } else {
                LOGI("hooked_nativeLoad: JNI_OnLoad succeeded for '%s' (version 0x%x)", path, jniVersion);
            }
        }
    } else {
        LOGD("hooked_nativeLoad: no JNI_OnLoad in '%s' (pure native library)", path);
    }

    env->ReleaseStringUTFChars(filename, path);

    // Return null to indicate success (same as ART's nativeLoad on success)
    return nullptr;
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
    JNIEnv* env, jobject thiz)
{
    (void)thiz;
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

} // extern "C"
