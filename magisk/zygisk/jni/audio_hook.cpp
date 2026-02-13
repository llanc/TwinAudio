// TwinAudio - AudioFlinger Hook Implementation
// Hook AudioFlinger::PlaybackThread::threadLoop_write 或 AudioStreamOut::write
// 实现音频流的双路输出和延迟同步

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
#include <dlfcn.h>        // dlopen, dlsym
#include <stdlib.h>       // malloc, free
#include <string.h>       // memset, memcpy

// ==========================================================================
// C++ STL（最后包含）
// ==========================================================================
#include <atomic>
#include <mutex>

// ==========================================================================
// 第三方库（Dobby Hook）
// ==========================================================================
#if DOBBY_AVAILABLE
#include <dobby.h>
#endif

#define TAG "TwinAudio-Hook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ============================================================================
// 配置参数（由 IPC 服务端更新）
// ============================================================================

namespace TwinAudio {
    // 全局配置
    std::atomic<int32_t> g_delay_ms{0};           // USB 延迟（毫秒）
    std::atomic<float> g_volume_bt{1.0f};         // 蓝牙音量系数
    std::atomic<float> g_volume_usb{1.0f};        // USB 音量系数
    std::atomic<bool> g_hook_enabled{true};       // Hook 启用状态

    std::mutex g_hook_mutex;                      // Hook 保护锁
}

// ============================================================================
// 外部依赖：环形缓冲区
// ============================================================================

extern "C" {
    void* audio_buffer_create(size_t capacity);
    void audio_buffer_destroy(void* buffer);
    void audio_buffer_write(void* buffer, const void* data, size_t size);
    size_t audio_buffer_read(void* buffer, void* data, size_t size);
    size_t audio_buffer_available(void* buffer);
}

// ============================================================================
// AudioStreamOut Hook (Android Audio HAL)
// ============================================================================

// 原始函数指针类型定义
typedef ssize_t (*audio_stream_out_write_t)(void* stream, const void* buffer, size_t bytes);

// 保存原始函数指针
static audio_stream_out_write_t original_audio_stream_out_write = nullptr;

// 环形缓冲区实例（用于 USB 延迟）
static void* g_delay_buffer = nullptr;

// Hook 函数：拦截音频写入
static ssize_t hooked_audio_stream_out_write(void* stream, const void* buffer, size_t bytes) {
    // 快速路径：如果 Hook 未启用，直接调用原始函数
    if (!TwinAudio::g_hook_enabled.load(std::memory_order_relaxed)) {
        return original_audio_stream_out_write(stream, buffer, bytes);
    }

    // 获取配置参数
    int32_t delay_ms = TwinAudio::g_delay_ms.load(std::memory_order_relaxed);
    float vol_bt = TwinAudio::g_volume_bt.load(std::memory_order_relaxed);
    float vol_usb = TwinAudio::g_volume_usb.load(std::memory_order_relaxed);

    // ========================================================================
    // 策略 1: 简化版 - 复制数据流到两个输出
    // 实际实现需要识别设备类型（BT vs USB）并分别处理
    // ========================================================================

    try {
        // 方案 A: 如果没有延迟，直接输出
        if (delay_ms <= 0) {
            // 应用音量系数（简化：假设 16-bit PCM）
            if (vol_bt != 1.0f || vol_usb != 1.0f) {
                // TODO: 实现音量调整（需要识别音频格式）
            }

            return original_audio_stream_out_write(stream, buffer, bytes);
        }

        // 方案 B: 有延迟 - 使用环形缓冲区
        if (g_delay_buffer) {
            // 写入延迟缓冲区
            audio_buffer_write(g_delay_buffer, buffer, bytes);

            // 计算需要缓冲的字节数（基于采样率和延迟）
            // 假设: 48kHz, 16-bit, Stereo = 192000 bytes/sec
            size_t delay_bytes = (192000 * delay_ms) / 1000;

            // 如果缓冲区累积足够，读取并输出
            if (audio_buffer_available(g_delay_buffer) >= delay_bytes) {
                void* delayed_data = malloc(bytes);
                if (delayed_data) {
                    size_t read = audio_buffer_read(g_delay_buffer, delayed_data, bytes);

                    // 应用 USB 音量系数
                    if (vol_usb != 1.0f) {
                        int16_t* samples = (int16_t*)delayed_data;
                        size_t sample_count = read / sizeof(int16_t);
                        for (size_t i = 0; i < sample_count; ++i) {
                            samples[i] = (int16_t)(samples[i] * vol_usb);
                        }
                    }

                    // 输出延迟后的数据
                    ssize_t result = original_audio_stream_out_write(stream, delayed_data, read);
                    free(delayed_data);
                    return result;
                }
            }
        }

        // 后备：直接输出（防止音频中断）
        return original_audio_stream_out_write(stream, buffer, bytes);

    } catch (...) {
        LOGE("Exception in hooked_audio_stream_out_write, fallback to original");
        return original_audio_stream_out_write(stream, buffer, bytes);
    }
}

// ============================================================================
// Hook 初始化
// ============================================================================

// 查找符号地址的辅助函数
static void* find_symbol(const char* lib_name, const char* symbol_name) {
    void* handle = dlopen(lib_name, RTLD_NOW);
    if (!handle) {
        LOGE("Failed to dlopen %s: %s", lib_name, dlerror());
        return nullptr;
    }

    void* addr = dlsym(handle, symbol_name);
    if (!addr) {
        LOGE("Failed to find symbol %s in %s: %s", symbol_name, lib_name, dlerror());
    }

    // 不调用 dlclose，保持句柄打开
    return addr;
}

extern "C" void init_audio_hook() {
    LOGI("=== Initializing Audio Hook ===");

#if !DOBBY_AVAILABLE
    LOGE("⚠️ Dobby is NOT available - Native audio hook DISABLED");
    LOGE("   To enable: Place libdobby.a in magisk/zygisk/jni/dobby/libs/<ABI>/");
    return;
#else

    try {
        // 创建延迟缓冲区（1MB）
        g_delay_buffer = audio_buffer_create(1024 * 1024);
        if (!g_delay_buffer) {
            LOGE("Failed to create delay buffer");
            return;
        }
        LOGI("✓ Delay buffer created");

        // ====================================================================
        // 查找目标函数地址
        // ====================================================================

        // 方案 1: Hook libaudioflinger.so 中的函数
        const char* target_lib = "libaudioflinger.so";

        // 可能的目标符号（按优先级）
        const char* target_symbols[] = {
            "_ZN7android12AudioFlinger14PlaybackThread17threadLoop_writeEv",
            "_ZN7android14AudioStreamOut5writeEPKvm",
            "_ZN7android12AudioFlinger14PlaybackThread5writeEPKvmPy",
            nullptr
        };

        void* target_addr = nullptr;
        for (int i = 0; target_symbols[i] != nullptr; ++i) {
            target_addr = find_symbol(target_lib, target_symbols[i]);
            if (target_addr) {
                LOGI("✓ Found target symbol: %s at %p", target_symbols[i], target_addr);
                break;
            }
        }

        if (!target_addr) {
            LOGE("✗ Failed to find any target symbol, trying fallback...");

            // 方案 2: Hook audio HAL 库
            target_lib = "audio.primary.default.so";
            target_addr = find_symbol(target_lib, "adev_open_output_stream");

            if (!target_addr) {
                LOGE("✗ All hook methods failed");
                return;
            }
        }

        // ====================================================================
        // 使用 Dobby 进行 Inline Hook
        // ====================================================================

        int ret = DobbyHook(
            target_addr,
            (dobby_dummy_func_t)hooked_audio_stream_out_write,
            (dobby_dummy_func_t*)&original_audio_stream_out_write
        );

        if (ret != 0) {
            LOGE("✗ DobbyHook failed with code: %d", ret);
            return;
        }

        LOGI("✓ Audio hook successfully installed at %p", target_addr);
        LOGI("=== Audio Hook Initialization Complete ===");

    } catch (const std::exception& e) {
        LOGE("Exception during hook initialization: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception during hook initialization");
    }
#endif
}

// 清理函数
extern "C" void cleanup_audio_hook() {
    if (g_delay_buffer) {
        audio_buffer_destroy(g_delay_buffer);
        g_delay_buffer = nullptr;
    }
}

// ============================================================================
// 配置更新接口（由 IPC 服务端调用）
// ============================================================================

extern "C" void update_audio_config(int32_t delay_ms, float volume_bt, float volume_usb) {
    TwinAudio::g_delay_ms.store(delay_ms, std::memory_order_release);
    TwinAudio::g_volume_bt.store(volume_bt, std::memory_order_release);
    TwinAudio::g_volume_usb.store(volume_usb, std::memory_order_release);

    LOGI("Config updated: delay=%dms, vol_bt=%.2f, vol_usb=%.2f",
         delay_ms, volume_bt, volume_usb);
}

extern "C" void set_hook_enabled(bool enabled) {
    TwinAudio::g_hook_enabled.store(enabled, std::memory_order_release);
    LOGI("Hook %s", enabled ? "enabled" : "disabled");
}

