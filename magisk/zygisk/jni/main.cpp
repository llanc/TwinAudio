// TwinAudio Native Library - LD_PRELOAD Entry Point
// 通过 LD_PRELOAD 注入到 audioserver 进程

// ==========================================================================
// 必须最先包含：C 风格基础类型头文件（全局命名空间）
// ==========================================================================
#include <stddef.h>       // 提供全局的 size_t, ptrdiff_t
#include <stdint.h>       // 提供全局的 uint32_t, int32_t 等
#include <sys/types.h>    // NDK 底层系统类型
#include <android/log.h>  // NDK 日志

// ==========================================================================
// 其他 C 标准库
// ==========================================================================
#include <unistd.h>       // getpid, sleep
#include <stdio.h>        // FILE, fopen, fgets
#include <string.h>       // strstr

// ==========================================================================
// C++ STL（最后包含）
// ==========================================================================
#include <thread>
#include <chrono>


#define TAG "TwinAudio-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 外部函数声明
extern "C" {
    void init_audio_hook();
    void start_ipc_server();
}

// ============================================================================
// 库加载时的自动初始化入口
// ============================================================================
__attribute__((constructor))
static void on_library_load() {
    LOGI("========================================");
    LOGI("TwinAudio Native Library Loaded");
    LOGI("========================================");

    // 验证当前进程是否为 audioserver
    char process_name[256] = {0};
    FILE* fp = fopen("/proc/self/cmdline", "r");
    if (fp) {
        fgets(process_name, sizeof(process_name), fp);
        fclose(fp);
    }

    LOGI("Current process: %s", process_name);

    // 仅在 audioserver 进程中执行初始化
    if (strstr(process_name, "audioserver") == nullptr) {
        LOGI("Not audioserver process, skipping initialization");
        return;
    }

    LOGI("✓ Detected audioserver process, initializing...");

    // 延迟初始化，等待 audioserver 内部组件加载完成
    std::thread([]() {
        std::this_thread::sleep_for(std::chrono::seconds(3));

        try {
            LOGI("→ Initializing Audio Hook...");
            init_audio_hook();
            LOGI("✓ Audio Hook initialized successfully");

            LOGI("→ Starting IPC Server...");
            start_ipc_server();
            LOGI("✓ IPC Server started successfully");

            LOGI("========================================");
            LOGI("✓ TwinAudio fully initialized");
            LOGI("========================================");
        } catch (const std::exception& e) {
            LOGE("✗ Initialization failed: %s", e.what());
        } catch (...) {
            LOGE("✗ Initialization failed with unknown error");
        }
    }).detach();
}

// ============================================================================
// 库卸载时的清理
// ============================================================================
__attribute__((destructor))
static void on_library_unload() {
    LOGI("TwinAudio Native Library Unloaded");
}

