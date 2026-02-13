// JNI IPC 客户端（可选）
// 用于 App 与 Zygisk 模块之间的 Unix Socket 通信

// ==========================================================================
// 必须最先包含：C 风格基础类型头文件（全局命名空间）
// ==========================================================================
#include <stddef.h>       // 提供全局的 size_t, ptrdiff_t
#include <stdint.h>       // 提供全局的 uint32_t, int32_t 等
#include <sys/types.h>    // NDK 底层系统类型
#include <android/log.h>  // NDK 日志

// ==========================================================================
// 其他头文件（在上面 4 个头文件之后）
// ==========================================================================
#include <jni.h>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>       // strncpy

#define TAG "TwinAudio-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define SOCKET_PATH "/data/local/tmp/twinaudio/audio.sock"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lrust_twinaudio_IPCManager_sendCommand(JNIEnv* env, jobject /* this */,
                                                  jint delay_ms, jfloat volume_bt, jfloat volume_usb) {
    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        LOGE("Failed to create socket");
        return JNI_FALSE;
    }

    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

    if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("Failed to connect to socket: %s", SOCKET_PATH);
        close(sock);
        return JNI_FALSE;
    }

    // 简单的协议：4字节延迟 + 4字节BT音量 + 4字节USB音量
    struct {
        int32_t delay;
        float vol_bt;
        float vol_usb;
    } cmd = { delay_ms, volume_bt, volume_usb };

    ssize_t sent = send(sock, &cmd, sizeof(cmd), 0);
    close(sock);

    if (sent != sizeof(cmd)) {
        LOGE("Failed to send command");
        return JNI_FALSE;
    }

    LOGI("Command sent: delay=%d, vol_bt=%.2f, vol_usb=%.2f", delay_ms, volume_bt, volume_usb);
    return JNI_TRUE;
}
