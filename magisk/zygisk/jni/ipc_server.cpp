// TwinAudio - IPC Server (Unix Domain Socket)
// 接收来自 Companion App 的控制命令（延迟、音量等）

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
#include <sys/socket.h>   // socket, bind, listen
#include <sys/un.h>       // sockaddr_un
#include <sys/stat.h>     // chmod
#include <unistd.h>       // close, read, write
#include <errno.h>        // errno
#include <string.h>       // memset, strerror

// ==========================================================================
// C++ STL（最后包含）
// ==========================================================================
#include <thread>
#include <atomic>

#define TAG "TwinAudio-IPC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define SOCKET_PATH "/data/local/tmp/twinaudio/audio.sock"
#define MAX_CLIENTS 5

// 外部函数：更新音频配置
extern "C" void update_audio_config(int32_t delay_ms, float volume_bt, float volume_usb);
extern "C" void set_hook_enabled(bool enabled);

// ============================================================================
// IPC 协议定义
// ============================================================================

#pragma pack(push, 1)
struct IPCCommand {
    uint32_t magic;          // 魔数：0x54574155 ("TWAU")
    uint32_t version;        // 协议版本：1
    uint32_t command_type;   // 命令类型
    int32_t delay_ms;        // USB 延迟（毫秒）
    float volume_bt;         // 蓝牙音量（0.0 - 2.0）
    float volume_usb;        // USB 音量（0.0 - 2.0）
    uint32_t flags;          // 标志位
};
#pragma pack(pop)

enum CommandType {
    CMD_UPDATE_CONFIG = 1,
    CMD_ENABLE_HOOK = 2,
    CMD_DISABLE_HOOK = 3,
    CMD_QUERY_STATUS = 4
};

// ============================================================================
// IPC 服务端状态
// ============================================================================

namespace TwinAudioIPC {
    std::atomic<bool> g_running{false};
    std::atomic<int> g_server_socket{-1};
}

// ============================================================================
// 客户端处理函数
// ============================================================================

static void handle_client(int client_fd) {
    LOGI("Client connected: fd=%d", client_fd);

    IPCCommand cmd;
    while (true) {
        ssize_t received = recv(client_fd, &cmd, sizeof(cmd), 0);

        if (received <= 0) {
            if (received == 0) {
                LOGI("Client disconnected gracefully");
            } else {
                LOGE("recv error: %s", strerror(errno));
            }
            break;
        }

        if (received != sizeof(cmd)) {
            LOGE("Invalid command size: %zd (expected %zu)", received, sizeof(cmd));
            continue;
        }

        // 验证魔数
        if (cmd.magic != 0x54574155) {
            LOGE("Invalid magic: 0x%08X", cmd.magic);
            continue;
        }

        // 处理命令
        switch (cmd.command_type) {
            case CMD_UPDATE_CONFIG:
                LOGI("CMD_UPDATE_CONFIG: delay=%d, vol_bt=%.2f, vol_usb=%.2f",
                     cmd.delay_ms, cmd.volume_bt, cmd.volume_usb);
                update_audio_config(cmd.delay_ms, cmd.volume_bt, cmd.volume_usb);

                // 发送确认
                {
                    uint32_t ack = 0x4F4B; // "OK"
                    send(client_fd, &ack, sizeof(ack), 0);
                }
                break;

            case CMD_ENABLE_HOOK:
                LOGI("CMD_ENABLE_HOOK");
                set_hook_enabled(true);
                break;

            case CMD_DISABLE_HOOK:
                LOGI("CMD_DISABLE_HOOK");
                set_hook_enabled(false);
                break;

            case CMD_QUERY_STATUS:
                LOGI("CMD_QUERY_STATUS");
                // TODO: 实现状态查询
                break;

            default:
                LOGE("Unknown command type: %u", cmd.command_type);
                break;
        }
    }

    close(client_fd);
    LOGI("Client handler exit");
}

// ============================================================================
// IPC 服务端主循环
// ============================================================================

static void ipc_server_thread() {
    LOGI("IPC Server thread started");

    // 创建 Unix Domain Socket
    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return;
    }

    TwinAudioIPC::g_server_socket.store(server_fd);

    // 删除旧的 socket 文件
    unlink(SOCKET_PATH);

    // 绑定地址
    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("Failed to bind socket: %s", strerror(errno));
        close(server_fd);
        return;
    }

    // 设置权限（允许所有用户连接）
    chmod(SOCKET_PATH, 0666);

    // 监听连接
    if (listen(server_fd, MAX_CLIENTS) < 0) {
        LOGE("Failed to listen: %s", strerror(errno));
        close(server_fd);
        return;
    }

    LOGI("IPC Server listening on: %s", SOCKET_PATH);

    // 接受客户端连接
    while (TwinAudioIPC::g_running.load(std::memory_order_acquire)) {
        struct sockaddr_un client_addr{};
        socklen_t client_len = sizeof(client_addr);

        int client_fd = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client_fd < 0) {
            if (errno == EINTR) continue; // 被信号中断，继续
            LOGE("Failed to accept: %s", strerror(errno));
            break;
        }

        // 为每个客户端创建独立线程处理
        std::thread([client_fd]() {
            handle_client(client_fd);
        }).detach();
    }

    // 清理
    close(server_fd);
    unlink(SOCKET_PATH);
    LOGI("IPC Server stopped");
}

// ============================================================================
// 外部接口
// ============================================================================

extern "C" void start_ipc_server() {
    if (TwinAudioIPC::g_running.exchange(true)) {
        LOGI("IPC Server already running");
        return;
    }

    LOGI("Starting IPC Server...");

    std::thread(ipc_server_thread).detach();

    LOGI("IPC Server started");
}

extern "C" void stop_ipc_server() {
    if (!TwinAudioIPC::g_running.exchange(false)) {
        return; // 未运行
    }

    LOGI("Stopping IPC Server...");

    // 关闭 server socket 以中断 accept()
    int fd = TwinAudioIPC::g_server_socket.load();
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
    }

    LOGI("IPC Server stopped");
}

// ============================================================================
// 测试客户端（用于验证）
// ============================================================================

#ifdef IPC_TEST
extern "C" int ipc_client_test() {
    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0) {
        LOGE("Test client: socket failed");
        return -1;
    }

    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

    if (connect(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("Test client: connect failed: %s", strerror(errno));
        close(sock);
        return -1;
    }

    // 发送测试命令
    IPCCommand cmd{};
    cmd.magic = 0x54574155;
    cmd.version = 1;
    cmd.command_type = CMD_UPDATE_CONFIG;
    cmd.delay_ms = 100;
    cmd.volume_bt = 0.8f;
    cmd.volume_usb = 1.0f;

    if (send(sock, &cmd, sizeof(cmd), 0) != sizeof(cmd)) {
        LOGE("Test client: send failed");
        close(sock);
        return -1;
    }

    LOGI("Test client: command sent successfully");
    close(sock);
    return 0;
}
#endif

