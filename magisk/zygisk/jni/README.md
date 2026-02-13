# TwinAudio Native 层实现说明

## 文件概览

### 1. main.cpp - Zygisk 模块入口 ✅
- **功能**: Zygisk 模块的生命周期管理
- **核心逻辑**:
  - `TwinAudioModule` 类继承 `zygisk::ModuleBase`
  - `postServerSpecialize()`: 检测 `audioserver` 进程
  - 仅在 `audioserver` 中注入，避免污染其他进程
  - 延迟 2 秒初始化，等待 audioserver 完全启动
  - 异常保护，防止 crash

### 2. audio_hook.cpp - AudioFlinger Hook 核心 ✅
- **功能**: 拦截音频输出流，实现双路输出和延迟同步
- **Hook 目标**:
  - 优先: `AudioFlinger::PlaybackThread::threadLoop_write`
  - 备选: `AudioStreamOut::write`
  - 兜底: Audio HAL 层函数
  
- **关键技术**:
  - 使用 Dobby 进行 Inline Hook
  - `dlopen` + `dlsym` 动态查找符号地址
  - 原子变量实现无锁参数更新
  - 环形缓冲区实现延迟同步
  
- **音频处理流程**:
  ```
  原始 PCM 数据
       ↓
  hooked_audio_stream_out_write()
       ↓
  ┌────────────────┬────────────────┐
  │  蓝牙路径       │  USB 路径       │
  │  vol_bt 调整   │  写入 Ring Buffer│
  │  立即输出      │  延迟 delay_ms  │
  │                │  vol_usb 调整   │
  └────────────────┴────────────────┘
  ```

- **配置接口**:
  - `update_audio_config()`: 更新延迟和音量
  - `set_hook_enabled()`: 启用/禁用 Hook

### 3. audio_buffer.cpp - 线程安全环形缓冲区 ✅
- **功能**: 为 USB 音频提供延迟缓冲
- **特性**:
  - 无锁原子操作（读写指针）
  - 互斥锁保护关键区
  - 自动丢弃最旧数据（溢出保护）
  - 环形边界处理

- **API**:
  ```c
  void* audio_buffer_create(size_t capacity);
  void audio_buffer_write(void* buffer, const void* data, size_t size);
  size_t audio_buffer_read(void* buffer, void* data, size_t size);
  size_t audio_buffer_available(void* buffer);
  void audio_buffer_clear(void* buffer);
  void audio_buffer_destroy(void* buffer);
  ```

- **容量设计**: 默认 1MB（约 5.5 秒 @ 48kHz 16-bit Stereo）

### 4. ipc_server.cpp - IPC 服务端 ✅
- **功能**: Unix Domain Socket 服务器，接收 App 控制命令
- **Socket 路径**: `/data/local/tmp/twinaudio/audio.sock`
- **协议定义**:
  ```c
  struct IPCCommand {
      uint32_t magic;          // 0x54574155 ("TWAU")
      uint32_t version;        // 1
      uint32_t command_type;   // CMD_UPDATE_CONFIG, CMD_ENABLE_HOOK 等
      int32_t delay_ms;        // 延迟（毫秒）
      float volume_bt;         // 蓝牙音量（0.0 - 2.0）
      float volume_usb;        // USB 音量（0.0 - 2.0）
      uint32_t flags;          // 保留
  };
  ```

- **命令类型**:
  - `CMD_UPDATE_CONFIG`: 更新延迟和音量
  - `CMD_ENABLE_HOOK`: 启用 Hook
  - `CMD_DISABLE_HOOK`: 禁用 Hook
  - `CMD_QUERY_STATUS`: 查询状态

- **并发处理**: 每个客户端独立线程

### 5. zygisk.hpp - Zygisk API 头文件 ✅
- **来源**: Magisk 官方 API（简化版）
- **核心类**:
  - `ModuleBase`: 模块基类
  - `Api`: Zygisk API 函数指针
  - `AppSpecializeArgs`: App 进程参数
  - `ServerSpecializeArgs`: Server 进程参数

- **注意**: 实际使用时应从 Magisk 源码获取完整版本

## 关键技术点

### 1. Dobby Hook 使用
```cpp
int DobbyHook(
    void* target_address,          // 目标函数地址
    void* replacement_function,    // 替换函数
    void** original_function       // 保存原始函数指针
);
```

### 2. 符号查找
```cpp
void* handle = dlopen("libaudioflinger.so", RTLD_NOW);
void* addr = dlsym(handle, "_ZN7android12AudioFlinger14PlaybackThread17threadLoop_writeEv");
```

### 3. 原子配置更新
```cpp
std::atomic<int32_t> g_delay_ms{0};
g_delay_ms.store(100, std::memory_order_release);
int delay = g_delay_ms.load(std::memory_order_acquire);
```

### 4. 音频延迟同步算法
```cpp
// 计算延迟缓冲区大小
size_t delay_bytes = (sample_rate * channels * bytes_per_sample * delay_ms) / 1000;

// 写入缓冲区
audio_buffer_write(g_delay_buffer, pcm_data, size);

// 等待缓冲区累积足够数据
if (audio_buffer_available(g_delay_buffer) >= delay_bytes) {
    audio_buffer_read(g_delay_buffer, delayed_data, size);
    // 输出延迟后的数据
}
```

## 安全机制

### 1. 异常保护
- 所有 Hook 函数使用 `try-catch`
- 异常时回退到原始函数，避免 audioserver crash

### 2. 原子操作
- 配置参数使用 `std::atomic`，避免数据竞争
- `memory_order_release` / `acquire` 保证内存可见性

### 3. 缓冲区溢出保护
- 环形缓冲区自动丢弃最旧数据
- 防止内存无限增长

### 4. 进程过滤
- 仅在 `audioserver` 进程中注入
- 避免影响其他系统进程

## 编译配置

### CMakeLists.txt
- ✅ 自动检测 Dobby 库是否存在
- ✅ 条件编译：无 Dobby 时禁用 Hook 功能
- ✅ 输出库名称：`libzygisk.so`（Magisk 要求）
- ✅ 符号表去除，减小体积

### 依赖库
- **必需**: `liblog.so`, `libandroid.so`
- **可选**: `libdobby.so`（Hook 功能）

## 调试日志

所有模块使用统一的日志标签：
- `TwinAudio-Zygisk`: 主模块
- `TwinAudio-Hook`: Audio Hook
- `TwinAudio-Buffer`: 环形缓冲区
- `TwinAudio-IPC`: IPC 服务器

查看日志：
```bash
adb logcat | grep TwinAudio
```

## 测试建议

### 单元测试（可选）
```cpp
// audio_buffer.cpp
#define AUDIO_BUFFER_TEST
audio_buffer_test();

// ipc_server.cpp
#define IPC_TEST
ipc_client_test();
```

### 集成测试
1. 编译 Zygisk 模块
2. 将 `libzygisk.so` 放入 Magisk 模块
3. 安装模块并重启
4. 检查日志：`adb logcat | grep TwinAudio`
5. 测试 IPC 通信：使用 Companion App 发送命令

## 已知限制

### 1. 符号查找
- AudioFlinger 符号名称可能因 Android 版本/ROM 而异
- 需要多个备选符号名称
- 最坏情况：无法找到合适的 Hook 点

### 2. 设备识别
- 当前实现未区分蓝牙/USB 设备
- 需要进一步分析 AudioFlinger 的设备路由逻辑

### 3. 音频格式
- 当前假设 16-bit PCM
- 实际可能是 24-bit, 32-bit float 等
- 需要动态检测音频格式

### 4. Dobby 依赖
- 需要手动获取 Dobby 库
- 替代方案：使用 Zygisk API 的 PLT Hook

## 改进方向

### 短期
1. 完善设备类型识别（BT vs USB）
2. 支持多种音频格式
3. 添加更多符号名称备选

### 中期
1. 实现真正的双路输出（当前是单路延迟）
2. 自适应采样率检测
3. 动态调整缓冲区大小

### 长期
1. 替代 Dobby，使用 Zygisk PLT Hook
2. 支持多输出设备（3+ 设备）
3. 可视化延迟校准工具

## 状态

✅ 第二步（Native 层）已完成：
- ✅ Zygisk 模块入口
- ✅ Audio Hook 框架
- ✅ 环形缓冲区实现
- ✅ IPC 服务端
- ✅ CMake 配置更新

**下一步：第三步 - Java 层（LSPosed Hook + UI）**

