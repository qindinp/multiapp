#pragma once

#include <jni.h>
#include <string>
#include <vector>
#include <map>
#include <atomic>
#include <mutex>
#include <atomic>
#include <android/log.h>
#include <sys/system_properties.h>

#define LOG_TAG "MultiAppNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declarations
struct HookInfo;

// Global state declarations
extern std::atomic_bool g_initialized;
extern std::mutex g_mutex;
extern JavaVM* g_jvm;
