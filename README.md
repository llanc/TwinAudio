# TwinAudio - 双音频同步输出模块

<div align="center">

![Android](https://img.shields.io/badge/Android-13%2B-green.svg)
![LSPosed](https://img.shields.io/badge/LSPosed-Required-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple.svg)

*打破 Android 有线独占限制，实现蓝牙与有线耳机同步双通道输出*

[功能特性](#功能特性) • [技术架构](#技术架构) • [安装使用](#安装使用) • [工作原理](#工作原理) • [常见问题](#常见问题)

</div>

---

## 📖 项目简介

**TwinAudio** 是一个基于 **LSPosed/Xposed** 框架的 Android 13+ 双音频输出模块，采用纯 Java 层 Hook 方案，实现蓝牙耳机与有线设备（USB-C/AUX）的同时发声，并提供毫秒级物理延迟同步功能。

### 核心目标

- ✅ **突破系统限制**：解除 Android 原生"有线设备插入时自动断开蓝牙"的锁定
- ✅ **双通道同步**：蓝牙与有线设备同时输出音频，无需切换
- ✅ **延迟补偿**：通过空轨垫砖技术，补偿蓝牙传输延迟（~150-200ms）
- ✅ **独立音量控制**：为有线设备提供独立音量调节，不影响蓝牙音量

### 应用场景

- 🎵 同时使用蓝牙音箱和有线音响，营造立体声环境
- 🎧 蓝牙耳机 + 有线录音设备，实时监听与录制同步
- 🚗 车载蓝牙 + AUX 备用输出，双重保障
- 🎮 游戏玩家：蓝牙耳机 + 有线监听音箱，团队语音与游戏音效分离

---

## 🚀 功能特性

### 1. 逆向路由架构（Reverse Routing）

由于有线设备物理延迟极低（~0ms），而蓝牙存在固有传输延迟（~150-200ms），TwinAudio 采用"主副通道逆向互换"策略：

- **蓝牙为主干道**：强制系统所有 `USAGE_MEDIA` 音频流直通蓝牙设备
- **有线为副干道**：通过系统级内录（AudioRecord）抓取音频数据，旁路复制给有线设备

### 2. 四大核心技术链路

#### A. 物理级防断开与状态锁
- **拦截断开指令**：Hook `AudioDeviceInventory` 的蓝牙断开方法，读取芯片物理连接状态，强行拦截系统抢占
- **瞬发唤醒**：在引擎启动瞬间调用 `setActiveDevice` 强启蓝牙硬件电波
- **防抖机制**：800ms 延迟防抖过滤频繁插拔，确保音频管道平滑重建

#### B. 特权内录与防回音
- **凭证伪装**：Hook `MediaProjectionManagerService.isValidMediaProjection`，动态代理伪造录音凭证
- **防回音循环**：通过 `AudioPlaybackCaptureConfiguration.excludeUid()` 排除自身进程，斩断啸叫反馈回路

#### C. 流量分发与防撞车
- **USAGE_GAME 马甲伪装**：将中转 AudioTrack 伪装为 `USAGE_GAME`，避开系统"MEDIA 必走蓝牙"的死命令
- **设备绑定**：使用 `setPreferredDevice(usbDevice)` 强制有线输出

#### D. 毫秒级物理同步
- **空轨垫砖法**：在 AudioTrack 开始前，写入一段纯静音数据（全0字节），实现零 CPU 损耗的绝对物理延迟同步

### 3. 跨进程通信

- **App 端**：Jetpack Compose + SharedPreferences 持久化存储用户配置
- **系统端**：BroadcastReceiver 接收配置广播，动态调整音量与延迟
- **自动同步**：开机后打开 App 即可自动下发存储的参数

---

## 📦 安装使用

### 前置要求

- ✅ Android 13 或更高版本
- ✅ 已安装 **LSPosed** 或 **EdXposed** 框架
- ✅ Root 权限（LSPosed 依赖）

### 安装步骤

1. **下载 APK**
   ```bash
   # 从 Release 页面下载最新版本
   # 或克隆仓库自行编译
   git clone https://github.com/yourusername/TwinAudio.git
   cd TwinAudio
   ./gradlew assembleDebug
   ```

2. **安装模块**
   - 安装 APK 到手机
   - 在 **LSPosed 管理器** 中激活 TwinAudio 模块
   - 作用域选择：**系统框架（System Framework）**

3. **重启设备**
   - 重启手机使 Xposed 模块生效

4. **打开 App 配置**
   - 启动 TwinAudio 应用
   - 调整 USB 延迟和音量参数
   - 开关引擎总开关

---

## 🔧 工作原理

### 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│                     TwinAudio 架构                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌───────────────┐         ┌──────────────────┐            │
│  │   App UI      │         │  system_server   │            │
│  │  (Compose)    │ <─────> │  AudioService    │            │
│  │               │ Broadcast│  Hook Layer      │            │
│  └───────────────┘         └──────────────────┘            │
│         │                           │                       │
│         │ SharedPreferences         │ Xposed Hook          │
│         ▼                           ▼                       │
│  ┌───────────────┐         ┌──────────────────┐            │
│  │  Local Store  │         │  Audio Pipeline  │            │
│  │  - delayMs    │         │  ┌──────────────┐ │            │
│  │  - volumeUsb  │         │  │  Bluetooth   │ │ ◄── 主通道│
│  │  - hookEnabled│         │  │  (A2DP)      │ │            │
│  └───────────────┘         │  └──────────────┘ │            │
│                            │         │         │            │
│                            │  ┌──────▼────────┐ │            │
│                            │  │ AudioRecord   │ │ ◄── 内录  │
│                            │  │ (Capture)     │ │            │
│                            │  └──────┬────────┘ │            │
│                            │         │         │            │
│                            │  ┌──────▼────────┐ │            │
│                            │  │ AudioTrack    │ │ ◄── 副通道│
│                            │  │ (USAGE_GAME)  │ │            │
│                            │  │   ↓           │ │            │
│                            │  │ USB/AUX       │ │            │
│                            │  └───────────────┘ │            │
│                            └──────────────────┘            │
└─────────────────────────────────────────────────────────────┘
```

### 核心技术流程

#### 1. Hook 初始化（HookEntry.kt）

```kotlin
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam?.packageName == "android") {  // 仅 Hook system_server
            AudioServiceHook.init(lpparam.classLoader)
        }
    }
}
```

#### 2. 防断开拦截（AudioServiceHook.kt）

```kotlin
// Hook AudioDeviceInventory 的断开方法
XposedBridge.hookAllMethods(inventoryClass, "makeA2dpDeviceUnavailableNow", object : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        // 读取蓝牙物理连接状态
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter?.getProfileConnectionState(2) == 2) {  // A2DP 已连接
            param.result = null  // 拦截断开指令
        }
    }
})
```

#### 3. 逆向路由锁定（AudioServiceHook.kt）

```kotlin
// 强制 USAGE_MEDIA 音频流走蓝牙
val strategies = AudioManager.getAudioProductStrategies()
val mediaStrategy = strategies.find { it.supportsAudioAttributes(USAGE_MEDIA) }
audioManager.setPreferredDeviceForStrategy(mediaStrategy, bluetoothDevice)
```

#### 4. 内录与防回音（AudioServiceHook.kt）

```kotlin
// 伪造 MediaProjection 凭证
val fakeProjection = createFakeMediaProjection(context)

// 配置内录（排除自身进程）
val captureConfig = AudioPlaybackCaptureConfiguration.Builder(fakeProjection)
    .addMatchingUsage(USAGE_MEDIA)
    .excludeUid(Process.myUid())  // 防止回音
    .build()

val recorder = AudioRecord.Builder()
    .setAudioPlaybackCaptureConfig(captureConfig)
    .build()
```

#### 5. USAGE_GAME 马甲伪装（AudioServiceHook.kt）

```kotlin
// 关键：使用 USAGE_GAME 标签绕过系统路由
val track = AudioTrack.Builder()
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)  // 马甲标签
            .build()
    )
    .build()

track.setPreferredDevice(usbDevice)  // 强制输出到 USB
```

#### 6. 空轨垫砖同步（AudioServiceHook.kt）

```kotlin
// 计算延迟对应的 PCM 字节数
val delayBytes = (currentDelayMs / 1000f * 48000 * 2 * 2).toInt()
val alignedBytes = delayBytes - (delayBytes % 4)  // 4 字节对齐

// 写入纯静音数据
val zeroBuf = ByteArray(alignedBytes)
track.write(zeroBuf, 0, alignedBytes)

// 开始写入真实音频数据
while (isStreamerRunning) {
    val read = recorder.read(buffer, 0, buffer.size)
    track.write(buffer, 0, read)
}
```

#### 7. 跨进程通信（MainActivity.kt）

```kotlin
// App 端：发送配置广播
val intent = Intent("com.lrust.twinaudio.UPDATE_CONFIG")
intent.putExtra("delayMs", delayMs)
intent.putExtra("volumeUsb", volumeUsb)
intent.putExtra("hookEnabled", hookEnabled)
context.sendBroadcast(intent)

// 系统端：接收广播
context.registerReceiver(object : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        currentUsbVolume = intent.getFloatExtra("volumeUsb", 1.0f)
        currentDelayMs = intent.getIntExtra("delayMs", 0)
        // 动态调整音量与重启引擎
    }
}, IntentFilter("com.lrust.twinaudio.UPDATE_CONFIG"))
```

---

## 📱 用户界面

### 主控制面板

<div align="center">

| 功能模块 | 说明 |
|---------|------|
| **引擎状态** | 开关双音频输出功能 |
| **USB 延迟** | 0-1000ms 可调，补偿蓝牙延迟 |
| **USB 音量** | 0-100% 独立音量控制 |
| **蓝牙音量** | 使用手机物理音量键控制 |

</div>

### 配置持久化

- 所有参数通过 `SharedPreferences` 自动保存
- 手机重启后，打开 App 即可自动下发存储的配置
- 无需每次手动调整

---

## 🛠️ 技术栈

| 技术 | 用途 |
|-----|------|
| **Kotlin** | 主开发语言 |
| **Jetpack Compose** | 现代化 UI 框架 |
| **LSPosed API** | Xposed Hook 框架 |
| **AudioRecord** | 系统级内录 |
| **AudioTrack** | 音频输出 |
| **BroadcastReceiver** | 跨进程通信 |
| **SharedPreferences** | 配置持久化 |

---

## 🧪 测试环境

- ✅ **Android 13** (API 33) - 主测试版本
- ✅ **Android 14** (API 34) - 兼容测试
- ✅ **LSPosed v1.9.2** - Zygisk 版本
- ✅ **Magisk v26.1** - Root 方案

### 已测试设备

- Pixel 6 Pro (Android 13)
- OnePlus 10 Pro (Android 13)
- Xiaomi 13 (Android 13)

---

## 🐛 常见问题

### Q1: 为什么插入 USB 后蓝牙仍然断开？

**A:** 确认以下事项：
1. LSPosed 模块已激活且作用域选择了"系统框架"
2. 已重启设备
3. 打开 TwinAudio App 确认"引擎状态"开关为开启状态

### Q2: 音频有明显延迟不同步？

**A:** 向右拖动"USB 延迟"滑块，推荐设置：
- 蓝牙 SBC 编码：150-200ms
- 蓝牙 AAC 编码：200-250ms
- 蓝牙 LDAC 编码：180-220ms

### Q3: 有线设备声音太大/太小？

**A:** 使用 App 内的"USB 独立音量"滑块调整，蓝牙音量请用手机物理按键控制。

### Q4: 调整延迟时声音会卡顿？

**A:** 正常现象。延迟变动时底层引擎会重启以填充新的空轨数据，停顿约 0.5 秒。

### Q5: 支持哪些蓝牙编码？

**A:** 支持所有 Android 系统支持的编码（SBC、AAC、LDAC、aptX 等），模块工作在系统层，不受编码限制。

### Q6: 能同时支持两个蓝牙设备吗？

**A:** 目前仅支持一个蓝牙 + 一个有线设备。多蓝牙同时输出需要系统级支持（Android 13+ 原生功能）。

---

## 🔒 隐私与安全

- ✅ **无网络请求**：模块运行完全离线，不联网
- ✅ **无数据收集**：不收集任何用户数据
- ✅ **开源透明**：所有代码公开，可审计
- ✅ **权限最小化**：仅使用必要的音频与系统权限

---

## 🚧 已知限制

1. **仅支持 Android 13+**：依赖 AudioPlaybackCaptureConfiguration 等新 API
2. **需要 LSPosed**：纯 Magisk 方案已废弃
3. **系统框架依赖**：需 Hook system_server，兼容性受 ROM 定制影响
4. **音频管道占用**：运行时会占用一定 CPU 和内存资源
5. **编解码延迟不可避免**：蓝牙物理延迟无法完全消除，仅能补偿

---

## 🗺️ 开发路线图

- [ ] 支持多蓝牙设备同时输出（需系统支持）
- [ ] 添加可视化延迟校准工具
- [ ] 优化音频管道性能，降低 CPU 占用
- [ ] 支持 Android 12（向下兼容）
- [ ] 添加音频效果器（均衡器、混响等）
- [ ] UI 国际化（多语言支持）

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

### 开发环境搭建

```bash
# 克隆仓库
git clone https://github.com/yourusername/TwinAudio.git
cd TwinAudio

# 使用 Android Studio 打开项目
# File -> Open -> 选择 TwinAudio 目录

# 编译 Debug 版本
./gradlew assembleDebug

# 生成 Release 版本
./gradlew assembleRelease
```

### 代码规范

- 使用 Kotlin 官方代码风格
- 提交前运行 `./gradlew ktlintCheck` 检查代码格式
- 为新功能添加详细注释

---

## 📄 开源协议

本项目基于 **MIT License** 开源。

```
MIT License

Copyright (c) 2026 TwinAudio Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 🙏 致谢

- **LSPosed Team** - 提供强大的 Xposed 框架
- **Android Open Source Project** - 音频系统架构参考
- **Jetpack Compose** - 现代化 UI 框架
- 所有贡献者和用户的支持

---

## 📬 联系方式

- **GitHub Issues**: [提交问题](https://github.com/yourusername/TwinAudio/issues)
- **Email**: your.email@example.com
- **Telegram**: @YourTelegramHandle

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给个 Star！**

Made with ❤️ by TwinAudio Contributors

</div>

