# NDK r25+ 头文件顺序修复

## 问题描述

编译时出现大量错误：
```
error: no member named 'int8_t' in the global namespace
using::int8_t;
error: no member named 'uint32_t' in the global namespace
...
```

## 根本原因

在 NDK r25+ 中，**不能直接使用 C++ 包装器头文件**如 `<cstdint>`、`<cstddef>`、`<cstdio>` 等，因为它们依赖底层的 C 头文件先被正确包含。

### 错误的做法（会导致编译失败）：
```cpp
#include <cstdint>    // ❌ C++ 包装器
#include <cstddef>    // ❌ C++ 包装器
#include <cstdio>     // ❌ C++ 包装器
#include <android/log.h>
```

### 正确的做法（NDK r25+ 要求）：
```cpp
// 1. 先包含 C 标准头文件
#include <stdint.h>   // ✅ C 头文件
#include <stddef.h>   // ✅ C 头文件
#include <stdio.h>    // ✅ C 头文件
#include <android/log.h>

// 2. 然后包含 C++ STL
#include <atomic>
#include <mutex>
```

## 修复内容

已修复所有 4 个 C++ 源文件的头文件顺序：

### 1. audio_hook.cpp
```cpp
// 修复前
#include <cstdint>
#include <cstddef>

// 修复后
#include <stdint.h>
#include <stddef.h>
```

### 2. audio_buffer.cpp
```cpp
// 修复前
#include <cstdlib>
#include <cstring>

// 修复后
#include <stdlib.h>
#include <string.h>
```

### 3. main.cpp
```cpp
// 修复前
#include <cstdio>
#include <cstring>

// 修复后
#include <stdio.h>
#include <string.h>
```

### 4. ipc_server.cpp
```cpp
// 修复前
#include <cerrno>
#include <cstring>

// 修复后
#include <errno.h>
#include <string.h>
```

## NDK r25+ 正确的头文件包含顺序

```cpp
// ============================================================================
// NDK r25+ 标准头文件包含顺序
// ============================================================================

// 第1步: C 标准类型定义（必须最先）
#include <stdint.h>      // int8_t, uint32_t 等
#include <stddef.h>      // size_t, ptrdiff_t 等

// 第2步: POSIX 系统头文件
#include <sys/types.h>   // pid_t, off_t 等

// 第3步: Android 特定头文件
#include <android/log.h>

// 第4步: 其他 C 标准库
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>

// 第5步: POSIX API
#include <unistd.h>
#include <sys/socket.h>

// 第6步: C++ STL（最后）
#include <atomic>
#include <mutex>
#include <thread>
#include <chrono>
```

## 为什么会这样？

### NDK r24 及之前
- `<cstdint>` 等 C++ 包装器可以自动包含底层的 C 头文件
- 包含顺序相对宽松

### NDK r25+
- C++ 包装器**不再自动包含**底层 C 头文件
- 必须**显式包含 C 头文件**（如 `<stdint.h>`）
- 必须**先包含 C 头文件，再包含 C++ STL**

## 验证方法

编译应该不再出现类型未定义的错误：

```powershell
# 清理并重新编译
.\gradlew :magisk:zygisk:clean
.\gradlew :magisk:zygisk:assembleRelease

# 应该看到 BUILD SUCCESSFUL
```

## 教训

在为 Android NDK r25+ 开发时：

1. ✅ **总是使用 C 头文件**：`<stdint.h>` 而非 `<cstdint>`
2. ✅ **遵循包含顺序**：C 头文件 → Android 头文件 → C++ STL
3. ✅ **避免使用 C++ 包装器**：在 NDK 项目中直接用 C 头文件
4. ❌ **不要混用**：不要在同一个文件中同时使用 `<stdint.h>` 和 `<cstdint>`

---

**修复时间**: 2026-02-13  
**影响文件**: audio_hook.cpp, audio_buffer.cpp, main.cpp, ipc_server.cpp  
**问题**: NDK r25+ C++ 包装器头文件依赖问题  
**解决方案**: 全部改用 C 标准头文件

