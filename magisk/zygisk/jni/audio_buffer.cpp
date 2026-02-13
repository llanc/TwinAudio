// TwinAudio - Thread-Safe Circular Ring Buffer
// 用于实现音频延迟同步的高性能无锁环形缓冲区

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
#include <stdlib.h>       // malloc, free
#include <string.h>       // memset, memcpy

// ==========================================================================
// C++ STL（最后包含）
// ==========================================================================
#include <atomic>
#include <mutex>

#define TAG "TwinAudio-Buffer"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ============================================================================
// Ring Buffer 结构定义
// ============================================================================

struct RingBuffer {
    uint8_t* data;                      // 数据缓冲区
    size_t capacity;                    // 容量（字节）
    std::atomic<size_t> write_pos;      // 写入位置
    std::atomic<size_t> read_pos;       // 读取位置
    std::mutex mutex;                   // 互斥锁（保护关键区）

    RingBuffer(size_t cap) : capacity(cap), write_pos(0), read_pos(0) {
        data = (uint8_t*)malloc(cap);
        if (!data) {
            throw std::bad_alloc();
        }
        memset(data, 0, cap);
    }

    ~RingBuffer() {
        if (data) {
            free(data);
            data = nullptr;
        }
    }
};

// ============================================================================
// C API 实现（供 audio_hook.cpp 调用）
// ============================================================================

extern "C" {

// 创建环形缓冲区
void* audio_buffer_create(size_t capacity) {
    try {
        RingBuffer* buffer = new RingBuffer(capacity);
        LOGD("Ring buffer created: capacity=%zu bytes", capacity);
        return buffer;
    } catch (const std::exception& e) {
        LOGE("Failed to create ring buffer: %s", e.what());
        return nullptr;
    }
}

// 销毁环形缓冲区
void audio_buffer_destroy(void* buffer) {
    if (buffer) {
        RingBuffer* rb = (RingBuffer*)buffer;
        delete rb;
        LOGD("Ring buffer destroyed");
    }
}

// 获取可用数据量（字节）
size_t audio_buffer_available(void* buffer) {
    if (!buffer) return 0;

    RingBuffer* rb = (RingBuffer*)buffer;
    size_t w = rb->write_pos.load(std::memory_order_acquire);
    size_t r = rb->read_pos.load(std::memory_order_acquire);

    if (w >= r) {
        return w - r;
    } else {
        return rb->capacity - r + w;
    }
}

// 获取可写空间（字节）
size_t audio_buffer_free_space(void* buffer) {
    if (!buffer) return 0;

    RingBuffer* rb = (RingBuffer*)buffer;
    return rb->capacity - audio_buffer_available(buffer) - 1; // 保留1字节防止满/空混淆
}

// 写入数据到环形缓冲区
void audio_buffer_write(void* buffer, const void* data, size_t size) {
    if (!buffer || !data || size == 0) return;

    RingBuffer* rb = (RingBuffer*)buffer;
    std::lock_guard<std::mutex> lock(rb->mutex);

    size_t free_space = audio_buffer_free_space(buffer);
    if (size > free_space) {
        // 缓冲区空间不足，丢弃最旧的数据
        size_t bytes_to_drop = size - free_space;
        rb->read_pos.fetch_add(bytes_to_drop, std::memory_order_release);
        LOGD("Buffer overflow: dropped %zu bytes", bytes_to_drop);
    }

    size_t w = rb->write_pos.load(std::memory_order_relaxed);
    const uint8_t* src = (const uint8_t*)data;

    // 写入数据（处理环形边界）
    for (size_t i = 0; i < size; ++i) {
        rb->data[w] = src[i];
        w = (w + 1) % rb->capacity;
    }

    rb->write_pos.store(w, std::memory_order_release);
}

// 从环形缓冲区读取数据
size_t audio_buffer_read(void* buffer, void* data, size_t size) {
    if (!buffer || !data || size == 0) return 0;

    RingBuffer* rb = (RingBuffer*)buffer;
    std::lock_guard<std::mutex> lock(rb->mutex);

    size_t available = audio_buffer_available(buffer);
    size_t to_read = (size < available) ? size : available;

    if (to_read == 0) {
        return 0;
    }

    size_t r = rb->read_pos.load(std::memory_order_relaxed);
    uint8_t* dst = (uint8_t*)data;

    // 读取数据（处理环形边界）
    for (size_t i = 0; i < to_read; ++i) {
        dst[i] = rb->data[r];
        r = (r + 1) % rb->capacity;
    }

    rb->read_pos.store(r, std::memory_order_release);
    return to_read;
}

// 清空缓冲区
void audio_buffer_clear(void* buffer) {
    if (!buffer) return;

    RingBuffer* rb = (RingBuffer*)buffer;
    std::lock_guard<std::mutex> lock(rb->mutex);

    rb->write_pos.store(0, std::memory_order_release);
    rb->read_pos.store(0, std::memory_order_release);
    memset(rb->data, 0, rb->capacity);

    LOGD("Ring buffer cleared");
}

// 获取缓冲区统计信息（调试用）
void audio_buffer_stats(void* buffer, size_t* available, size_t* capacity) {
    if (!buffer) return;

    RingBuffer* rb = (RingBuffer*)buffer;
    if (available) *available = audio_buffer_available(buffer);
    if (capacity) *capacity = rb->capacity;
}

} // extern "C"

// ============================================================================
// 测试函数（可选）
// ============================================================================

#ifdef AUDIO_BUFFER_TEST
#include <cassert>

extern "C" void audio_buffer_test() {
    LOGD("=== Ring Buffer Unit Test ===");

    // 测试 1: 创建和销毁
    void* buf = audio_buffer_create(1024);
    assert(buf != nullptr);

    // 测试 2: 写入和读取
    uint8_t write_data[100];
    for (int i = 0; i < 100; ++i) write_data[i] = i;

    audio_buffer_write(buf, write_data, 100);
    assert(audio_buffer_available(buf) == 100);

    uint8_t read_data[100] = {0};
    size_t read = audio_buffer_read(buf, read_data, 100);
    assert(read == 100);
    assert(memcmp(write_data, read_data, 100) == 0);

    // 测试 3: 环形边界
    audio_buffer_clear(buf);
    uint8_t large_data[2000];
    for (int i = 0; i < 2000; ++i) large_data[i] = i & 0xFF;

    audio_buffer_write(buf, large_data, 2000); // 超过容量
    assert(audio_buffer_available(buf) < 1024);

    // 清理
    audio_buffer_destroy(buf);

    LOGD("✓ All tests passed");
}
#endif

