# TwinAudio 架构重构文档

## 重构原因

**致命错误修正**: 原架构试图使用 Zygisk 的 `postServerSpecialize` 注入 `audioserver`，但这是不可能的。

### 为什么 Zygisk 无法注入 audioserver？

- ✅ **Zygisk 可以注入**: App 进程、system_server（它们都通过 Zygote fork）
- ❌ **Zygisk 无法注入**: audioserver、surfaceflinger、mediaserver 等 Native Daemon

**原因**: `audioserver` 是 Native Daemon，由 `init` 进程直接启动，**根本不经过 Zygote**。

## 新架构：LD_PRELOAD 注入

### 1. Native 库层 (libtwinaudio_native.so)

**文件**: `magisk/zygisk/jni/main.cpp`

**变化**:
- ❌ 删除: 所有 `zygisk::ModuleBase` 相关代码
- ❌ 删除: `#include "zygisk.hpp"`
- ✅ 新增: `__attribute__((constructor))` 作为库加载入口
- ✅ 新增: `__attribute__((destructor))` 作为库卸载清理

**工作原理**:
```cpp
__attribute__((constructor))
static void on_library_load() {
    // 1. 验证当前进程是否为 audioserver
    // 2. 如果是，初始化 Dobby Hook
    // 3. 启动 IPC 服务端
}
```

### 2. Magisk 注入脚本 (service.sh)

**文件**: `magisk/service.sh`

**完全重写**:
```bash
# 等待系统启动完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done

# 查找 audioserver 进程
AUDIO_PID=$(pidof audioserver)

# 选择正确的 ABI 库文件
case "$(getprop ro.product.cpu.abi)" in
    arm64-v8a) LIB_PATH="$MODDIR/lib/arm64-v8a/libtwinaudio_native.so" ;;
    armeabi-v7a) LIB_PATH="$MODDIR/lib/armeabi-v7a/libtwinaudio_native.so" ;;
esac

# 方法1: 使用 setprop 重启 audioserver
setprop ctl.restart audioserver

# 方法2 (备用): 强制 kill 并用 LD_PRELOAD 启动
killall audioserver
LD_PRELOAD="$LIB_PATH" /system/bin/audioserver &

# 验证注入是否成功
grep -q "libtwinaudio_native.so" "/proc/$(pidof audioserver)/maps"
```

**注入流程**:
1. 等待系统启动完成 (`sys.boot_completed=1`)
2. 检测设备 ABI 架构
3. 设置库文件权限和 SELinux 上下文
4. 使用 `LD_PRELOAD` 环境变量重启 audioserver
5. 验证库是否成功加载到进程内存

### 3. CMakeLists.txt 变化

**文件**: `magisk/zygisk/jni/CMakeLists.txt`

**变化**:
```cmake
# 旧: project("twinaudio_zygisk")
新: project("twinaudio_native")

# 旧: OUTPUT_NAME "zygisk"  (Magisk Zygisk 要求命名为 libzygisk.so)
新: OUTPUT_NAME "twinaudio_native"  (输出 libtwinaudio_native.so)
```

### 4. 模块目录结构

```
/data/adb/modules/twinaudio/
├── module.prop
├── service.sh                    # Magisk 启动脚本（新）
├── sepolicy.rule                 # SELinux 规则
├── lib/
│   ├── arm64-v8a/
│   │   └── libtwinaudio_native.so  # Native 注入库（新名称）
│   └── armeabi-v7a/
│       └── libtwinaudio_native.so
└── system/
    └── framework/
        └── twinaudio.apk         # LSPosed 模块 APK
```

## 保持不变的部分

✅ **LSPosed Java 层逻辑**: 保持不变
- Hook `AudioService`
- Hook `AudioPolicyManager`
- 音频路由策略拦截

✅ **IPC 通信**: 保持不变
- Unix Domain Socket
- App UI ↔ Native 库通信

✅ **Dobby Hook 逻辑**: 保持不变
- Hook `AudioStreamOut::write`
- 音频数据双路复制
- 环形缓冲区延迟同步

## 测试验证

### 验证注入是否成功

```bash
# 1. 检查 audioserver 是否加载了我们的库
cat /proc/$(pidof audioserver)/maps | grep twinaudio

# 2. 查看 logcat 日志
logcat | grep TwinAudio

# 应该看到:
# TwinAudio-Native: ========================================
# TwinAudio-Native: TwinAudio Native Library Loaded
# TwinAudio-Native: Current process: audioserver
# TwinAudio-Native: ✓ Detected audioserver process
# TwinAudio-Native: ✓ Audio Hook initialized
# TwinAudio-Native: ✓ IPC Server started
```

### 验证服务脚本日志

```bash
cat /data/local/tmp/twinaudio_service.log
```

## 关键差异对比

| 特性 | 旧架构 (Zygisk) | 新架构 (LD_PRELOAD) |
|------|----------------|---------------------|
| 注入目标 | ❌ audioserver (不可能) | ✅ audioserver (正确) |
| 注入方式 | Zygisk API | LD_PRELOAD 环境变量 |
| 入口点 | `postServerSpecialize()` | `__attribute__((constructor))` |
| 库名称 | libzygisk.so | libtwinaudio_native.so |
| 依赖 | Magisk Zygisk 模块 | Magisk 基础模块 |
| 启动脚本 | ❌ 无用（Zygisk自动） | ✅ service.sh（必需） |

## 总结

这次重构修正了一个**架构层面的致命错误**。通过改用 LD_PRELOAD 注入机制，我们现在可以正确地将 Native 代码注入到 `audioserver` 进程中。

---

**重构完成时间**: 2026-02-13  
**修正的核心问题**: audioserver 不经过 Zygote，必须使用 LD_PRELOAD  
**状态**: ✅ 架构重构完成，等待编译验证

