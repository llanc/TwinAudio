# 🚀 TwinAudio 快速开始指南

## 一分钟了解 TwinAudio

**TwinAudio** 是一个混合 Magisk + LSPosed 模块，实现蓝牙和 USB/AUX 音频的**同时输出**，支持**独立延迟同步**和**音量控制**。

---

## ⚡ 快速开始（5 步）

### 1️⃣ 准备环境
```bash
✅ Android 13 设备（已 Root）
✅ Magisk 25.0+（Zygisk 已启用）
✅ LSPosed 已安装
```

### 2️⃣ 下载 Dobby（必需）
```
https://github.com/jmpews/Dobby/releases
放置到：magisk/zygisk/jni/dobby/libs/{arm64-v8a,armeabi-v7a}/libdobby.so
```

### 3️⃣ 构建项目
```powershell
# Windows PowerShell
.\gradlew :app:assembleRelease
.\gradlew :magisk:zygisk:assembleRelease
.\gradlew packageMagiskModule
```

### 4️⃣ 安装部署
```
1. Magisk Manager → 安装 TwinAudio-v1.0.zip
2. 安装 app-release.apk
3. LSPosed → 启用 TwinAudio → 勾选 system_server
4. 重启设备
```

### 5️⃣ 配置使用
```
1. 连接蓝牙音频设备
2. 连接 USB/AUX 有线设备
3. 打开 TwinAudio 应用
4. 调整延迟滑块以同步音频
5. 点击"立即应用设置"
```

---

## 📂 项目结构（简化版）

```
TwinAudio/
├── app/                    # 伴侣应用（LSPosed 模块）
│   ├── MainActivity.kt     # UI 界面
│   ├── HookEntry.kt        # LSPosed 入口
│   ├── AudioServiceHook.kt # AudioService Hook
│   └── IPCManager.kt       # IPC 管理器
│
├── magisk/                 # Magisk 模块
│   ├── module.prop         # 模块信息
│   ├── sepolicy.rule       # SELinux 策略
│   └── zygisk/jni/
│       ├── main.cpp        # Zygisk 入口
│       ├── audio_hook.cpp  # AudioFlinger Hook
│       ├── audio_buffer.cpp# 环形缓冲区
│       └── ipc_server.cpp  # IPC 服务端
│
├── README.md               # 完整文档
└── build.gradle.kts        # 构建配置
```

---

## 🎯 核心工作原理

```
┌──────────────┐
│  蓝牙音频     │ ← 立即输出（vol_bt 调整）
└──────────────┘
        ↑
        │ 分流
        │
┌──────────────┐
│  PCM 数据流   │
└──────────────┘
        │ Hook
        ↓
┌──────────────┐
│  USB/AUX 音频 │ ← 延迟输出（delay_ms + vol_usb）
└──────────────┘
```

---

## 🔧 常用命令

### 查看日志
```bash
adb logcat | grep TwinAudio
```

### 检查模块状态
```bash
adb shell su -c "ls /data/adb/modules/twinaudio"
```

### 测试 IPC 连接
```bash
adb shell su -c "ls -la /data/local/tmp/twinaudio/"
```

### 重启 audioserver
```bash
adb shell su -c "killall audioserver"
```

---

## ❓ 常见问题

### Q: 只有一个设备有声音？
**A:** 检查 LSPosed 是否勾选了 `system_server` 作用域，重启设备。

### Q: 编译报错 "Unresolved reference"？
**A:** 这是正常的，LSPosed API 是 `compileOnly` 依赖，运行时由 LSPosed 提供。

### Q: 音频不同步？
**A:** 调整延迟滑块，每次调整 10ms，直到音频对齐。

### Q: 没有 Dobby 库？
**A:** 从 https://github.com/jmpews/Dobby/releases 下载，或编译。

---

## 📚 详细文档

- **README.md**: 完整项目文档（1500+ 行）
- **PROJECT_STRUCTURE.md**: 项目架构详解
- **magisk/zygisk/jni/README.md**: Native 层技术文档
- **DELIVERY-CHECKLIST.md**: 交付清单

---

## 🎉 就这么简单！

5 个步骤，即可实现双音频同时输出。

**遇到问题？查看完整文档 README.md**

