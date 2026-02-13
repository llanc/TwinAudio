# TwinAudio 项目结构文档

## 概述
本项目采用 **Magisk Zygisk 模块 + LSPosed 伴侣应用** 的混合架构。

## 目录结构

```
TwinAudio/
├── app/                                   # Android 伴侣应用（LSPosed 模块）
│   ├── src/main/
│   │   ├── assets/
│   │   │   └── xposed_init                # LSPosed 入口声明
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt             # JNI 构建配置
│   │   │   └── ipc_client.cpp             # IPC 客户端（Unix Socket）
│   │   └── java/com/lrust/twinaudio/
│   │       ├── MainActivity.kt            # UI 主界面
│   │       ├── HookEntry.kt               # LSPosed Hook 入口（待创建）
│   │       ├── AudioServiceHook.kt        # AudioService Hook（待创建）
│   │       └── IPCManager.kt              # IPC 管理器（待创建）
│   └── build.gradle.kts                   # 已配置 API 33 + LSPosed API
│
├── magisk/                                # Magisk 模块
│   ├── module.prop                        # 模块元信息
│   ├── sepolicy.rule                      # SELinux 策略
│   ├── customize.sh                       # 安装脚本
│   ├── service.sh                         # 启动脚本
│   └── zygisk/                            # Zygisk 原生模块
│       ├── build.gradle.kts               # Native 模块构建
│       └── jni/
│           ├── CMakeLists.txt             # CMake 配置
│           ├── main.cpp                   # Zygisk 入口（待创建）
│           ├── audio_hook.cpp             # AudioFlinger Hook（待创建）
│           ├── audio_buffer.cpp           # 环形缓冲区（待创建）
│           ├── ipc_server.cpp             # IPC 服务端（待创建）
│           └── dobby/                     # Dobby Hook 库
│               ├── include/dobby.h
│               └── libs/
│                   ├── arm64-v8a/libdobby.so  (需下载)
│                   └── armeabi-v7a/libdobby.so (需下载)
│
├── build.gradle.kts                       # 根构建文件（含 Magisk 打包任务）
└── settings.gradle.kts                    # 项目设置（已包含 zygisk 模块）
```

## 已完成的配置

### 1. Gradle 构建系统
- ✅ `app/build.gradle.kts`: 锁定 API 33, 添加 LSPosed API, libsu, NDK/CMake
- ✅ `build.gradle.kts`: 添加 `packageMagiskModule` 任务用于打包 .zip
- ✅ `magisk/zygisk/build.gradle.kts`: Native 模块构建配置
- ✅ `settings.gradle.kts`: 包含 zygisk 子项目

### 2. Magisk 模块文件
- ✅ `module.prop`: 模块元信息
- ✅ `sepolicy.rule`: SELinux 策略（允许 app ↔ audioserver 通信）
- ✅ `customize.sh`: 安装向导
- ✅ `service.sh`: 启动脚本（创建 IPC Socket 目录）

### 3. CMake 配置
- ✅ `app/src/main/cpp/CMakeLists.txt`: App JNI 构建
- ✅ `magisk/zygisk/jni/CMakeLists.txt`: Zygisk 模块构建（链接 Dobby）

### 4. LSPosed 入口
- ✅ `app/src/main/assets/xposed_init`: 指向 `HookEntry` 类

## 依赖项

### 需要手动获取
- **Dobby Hook 库**: 从 https://github.com/jmpews/Dobby/releases 下载
  - 放置到 `magisk/zygisk/jni/dobby/libs/{arm64-v8a,armeabi-v7a}/libdobby.so`

### Gradle 自动获取
- LSPosed API (82)
- libsu (5.0.5)
- Jetpack Compose
- AndroidX 库

## 构建流程

1. **构建伴侣应用**:
   ```bash
   ./gradlew :app:assembleRelease
   ```

2. **构建 Zygisk 模块**:
   ```bash
   ./gradlew :magisk:zygisk:assembleRelease
   ```

3. **打包 Magisk 模块**:
   ```bash
   ./gradlew packageMagiskModule
   ```
   输出: `build/outputs/magisk/TwinAudio-v1.0.zip`

## 下一步

待创建的代码文件：
1. **Native 层** (C++):
   - `main.cpp`: Zygisk 入口与进程过滤
   - `audio_hook.cpp`: AudioFlinger Hook 实现
   - `audio_buffer.cpp`: 线程安全环形缓冲区
   - `ipc_server.cpp`: Unix Socket IPC 服务端

2. **Java 层** (Kotlin):
   - `HookEntry.kt`: LSPosed 入口
   - `AudioServiceHook.kt`: AudioService/AudioPolicyManager Hook
   - `IPCManager.kt`: IPC 客户端管理
   - `MainActivity.kt`: UI（延迟滑块 + 音量控制）

## 注意事项

1. **API 版本**: 严格锁定在 API 33 (Android 13)
2. **Dobby 库**: 必须手动下载或编译
3. **SELinux**: 依赖 `sepolicy.rule` 中的策略
4. **Zygisk**: 需要在 Magisk 设置中启用
5. **LSPosed**: 需要激活 TwinAudio 应用并勾选 `system_server` 作用域

