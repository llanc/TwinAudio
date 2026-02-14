// TwinAudio - 安全模式 (禁用所有 C++ Hook，防止看门狗重启)
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>
#include <android/log.h>

#define TAG "TwinAudio-SafeMode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" void init_audio_hook() {
    LOGI("==============================================");
    LOGI("🛡️ TwinAudio SAFE MODE ACTIVE 🛡️");
    LOGI("已禁用所有底层 C++ 注入，防止 audioserver 崩溃和重启！");
    LOGI("==============================================");
}

// 保留空函数，防止编译报错
extern "C" void cleanup_audio_hook() {}
extern "C" void update_audio_config(int32_t delay_ms, float volume_bt, float volume_usb) {}
extern "C" void set_hook_enabled(bool enabled) {}