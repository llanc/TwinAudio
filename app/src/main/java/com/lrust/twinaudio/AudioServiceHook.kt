package com.lrust.twinaudio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.concurrent.thread

@Suppress("DEPRECATION")
object AudioServiceHook {
    private const val TAG = "TwinAudio-JavaHook"

    // 线程与设备引用
    private var isStreamerRunning = false
    private var streamerThread: Thread? = null
    private var btDevice: AudioDeviceInfo? = null
    private var usbDevice: AudioDeviceInfo? = null

    // 🚀 核心控制变量
    private var isPluginEnabled = true
    private var currentDelayMs = 0
    private var currentUsbVolume = 1.0f
    private var currentUsbTrack: AudioTrack? = null

    // 路由备份与线程锁
    private var savedAudioManager: AudioManager? = null
    private var savedMediaStrategy: Any? = null
    private val engineLock = Any() // 状态机锁

    // 防抖计时器
    private var mainHandler: Handler? = null
    private var debounceRunnable: Runnable? = null

    fun init(classLoader: ClassLoader) {
        // 1. 放行特权内录
        try {
            val projectionServiceClass = XposedHelpers.findClass("com.android.server.media.projection.MediaProjectionManagerService\$BinderService", classLoader)
            val iMediaProjectionClass = XposedHelpers.findClass("android.media.projection.IMediaProjection", classLoader)
            XposedHelpers.findAndHookMethod(projectionServiceClass, "isValidMediaProjection", iMediaProjectionClass,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam) = true
                })
        } catch (t: Throwable) {}

        // 2. 🛡️ 物理级防断开护盾
        try {
            val inventoryClass = XposedHelpers.findClass("com.android.server.audio.AudioDeviceInventory", classLoader)
            val smartDisconnectHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isPluginEnabled) return
                    try {
                        if (param.method.name == "onSetA2dpSinkConnectionState") {
                            if (param.args[1] as Int != 0) return
                        }
                        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                        if (adapter != null && adapter.getProfileConnectionState(2) == 2) {
                            param.result = null // 只要物理连着就不允许被系统踢掉
                        }
                    } catch (t: Throwable) {}
                }
            }
            XposedBridge.hookAllMethods(inventoryClass, "makeA2dpDeviceUnavailableNow", smartDisconnectHook)
            XposedBridge.hookAllMethods(inventoryClass, "makeA2dpDeviceUnavailableLater", smartDisconnectHook)
            XposedBridge.hookAllMethods(inventoryClass, "onSetA2dpSinkConnectionState", smartDisconnectHook)
        } catch (t: Throwable) {}

        // 3. 监听设备插拔与广播
        try {
            val audioServiceClass = XposedHelpers.findClass("com.android.server.audio.AudioService", classLoader)
            XposedHelpers.findAndHookMethod(audioServiceClass, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context

                    // 初始化防抖 Handler
                    mainHandler = Handler(Looper.getMainLooper())

                    val filter = android.content.IntentFilter("com.lrust.twinaudio.UPDATE_CONFIG")
                    context.registerReceiver(object : android.content.BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: android.content.Intent) {
                            isPluginEnabled = intent.getBooleanExtra("hookEnabled", true)
                            currentUsbVolume = intent.getFloatExtra("volumeUsb", 1.0f)
                            val newDelay = intent.getIntExtra("delayMs", 0)

                            currentUsbTrack?.setVolume(currentUsbVolume)

                            if (!isPluginEnabled) {
                                stopEngineLocked()
                            } else {
                                // 延迟变动时，平滑重启防死锁
                                if (currentDelayMs != newDelay && isStreamerRunning) {
                                    currentDelayMs = newDelay
                                    thread {
                                        stopEngineLocked()
                                        startEngineLocked(context)
                                    }
                                } else {
                                    currentDelayMs = newDelay
                                }
                            }
                        }
                    }, filter)

                    setupDeviceMonitor(context)
                }
            })
        } catch (t: Throwable) {}
    }

    private fun setupDeviceMonitor(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        debounceRunnable = Runnable {
            synchronized(engineLock) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

                val newUsb = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                val newBt = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

                val devicesChanged = (newUsb?.id != usbDevice?.id) || (newBt?.id != btDevice?.id)
                usbDevice = newUsb
                btDevice = newBt

                if (isPluginEnabled && usbDevice != null && btDevice != null) {
                    if (!isStreamerRunning || devicesChanged) {
                        Log.i(TAG, "🔄 触发双设备引擎 (设备状态已稳定)...")
                        stopEngineLocked()
                        startEngineLocked(context)
                    }
                } else {
                    if (isStreamerRunning) {
                        Log.i(TAG, "🔌 设备断开，安全清理引擎与系统路由...")
                        stopEngineLocked()
                    }
                }
            }
        }

        // 🚨 核心防抖：延迟 800ms 执行，过滤掉系统抽风的连环回调
        val triggerCheck = {
            mainHandler?.removeCallbacks(debounceRunnable!!)
            mainHandler?.postDelayed(debounceRunnable!!, 800)
        }

        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { triggerCheck() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { triggerCheck() }
        }, null)

        triggerCheck()
    }

    // 🧹 终极保洁员：专门负责清理系统遗留的路由策略
    @Synchronized
    private fun restoreSystemRouting() {
        try {
            if (savedAudioManager != null && savedMediaStrategy != null) {
                val clearPrefMethod = AudioManager::class.java.getMethod("removePreferredDeviceForStrategy", Class.forName("android.media.audiopolicy.AudioProductStrategy"))
                clearPrefMethod.invoke(savedAudioManager, savedMediaStrategy)
                Log.i(TAG, "🔄 已彻底清除系统路由霸占，恢复默认状态。")
            }
        } catch (e: Exception) {
            Log.e(TAG, "恢复路由失败", e)
        } finally {
            savedMediaStrategy = null
            savedAudioManager = null
        }
    }

    private fun stopEngineLocked() {
        if (!isStreamerRunning) return
        isStreamerRunning = false
        streamerThread?.interrupt()
        try {
            streamerThread?.join(1000) // 给予 1 秒钟的优雅死机时间
        } catch (e: Exception) {}
        streamerThread = null
        restoreSystemRouting()
    }

    @SuppressLint("MissingPermission")
    private fun startEngineLocked(context: Context) {
        if (btDevice == null || usbDevice == null) return
        isStreamerRunning = true

        streamerThread = thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            var recorder: AudioRecord? = null
            var track: AudioTrack? = null

            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                // 1. 逆向路由锁定：逼迫系统默认走蓝牙
                try {
                    val strategiesClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
                    val getStrategiesMethod = AudioManager::class.java.getMethod("getAudioProductStrategies")
                    val strategies = getStrategiesMethod.invoke(null) as List<*>

                    val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                    var mediaStrategy: Any? = null

                    for (strategy in strategies) {
                        val supports = strategy!!::class.java.getMethod("supportsAudioAttributes", AudioAttributes::class.java).invoke(strategy, attr) as Boolean
                        if (supports) { mediaStrategy = strategy; break }
                    }

                    if (mediaStrategy != null) {
                        val deviceAttrClass = Class.forName("android.media.AudioDeviceAttributes")
                        val attrCtor = deviceAttrClass.getConstructor(AudioDeviceInfo::class.java)
                        val btDeviceAttr = attrCtor.newInstance(btDevice)

                        val setPrefMethod = AudioManager::class.java.getMethod("setPreferredDeviceForStrategy", strategiesClass, deviceAttrClass)
                        setPrefMethod.invoke(audioManager, mediaStrategy, btDeviceAttr)

                        savedAudioManager = audioManager
                        savedMediaStrategy = mediaStrategy
                        Log.i(TAG, "🚀 [核心架构] 成功将系统主通道锁定至蓝牙！")
                    }
                } catch (e: Exception) {}

                // 2. 内录凭证伪装
                val iMediaProjectionClass = Class.forName("android.media.projection.IMediaProjection")
                val fakeBinder = Binder()
                val fakeIMediaProjection = Proxy.newProxyInstance(
                    context.classLoader, arrayOf(iMediaProjectionClass),
                    InvocationHandler { _, method, _ ->
                        when (method.name) {
                            "asBinder" -> fakeBinder
                            "canProjectAudio" -> true
                            "canProjectVideo", "canProjectSecureVideo" -> false
                            else -> if (method.returnType == java.lang.Boolean.TYPE) true else null
                        }
                    }
                )

                val ctor = MediaProjection::class.java.declaredConstructors.first()
                ctor.isAccessible = true
                val fakeProjection = ctor.newInstance(context, fakeIMediaProjection) as MediaProjection

                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(fakeProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(android.os.Process.myUid())
                    .build()

                val format = AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                val bufferSize = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

                val identity = Binder.clearCallingIdentity()
                try {
                    recorder = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize * 4)
                        .build()
                } finally { Binder.restoreCallingIdentity(identity) }

                // 3. 构建我们自己的播放器，并锁定到 USB
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.setPreferredDevice(usbDevice)
                currentUsbTrack = track

                recorder.startRecording()
                track.play()
                track.setVolume(currentUsbVolume)

                // 4. 空轨垫砖：执行 USB 延迟
                if (currentDelayMs > 0) {
                    val delayBytes = (currentDelayMs / 1000f * 48000 * 2 * 2).toInt()
                    val alignedBytes = delayBytes - (delayBytes % 4)
                    if (alignedBytes > 0) {
                        val zeroBuf = ByteArray(alignedBytes)
                        track.write(zeroBuf, 0, alignedBytes)
                        Log.i(TAG, "⏱️ 延迟生效：已向 USB 通道注入 $currentDelayMs ms 缓冲空白数据！")
                    }
                }

                val buffer = ByteArray(bufferSize)
                Log.i(TAG, "🎵 开始稳定无损搬运！")

                while (isStreamerRunning) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        track.write(buffer, 0, read)
                    }
                }

            } catch (t: Throwable) {
                Log.e(TAG, "引擎运行时崩溃跳出", t)
            } finally {
                // 🛑 终极安全锁：无论发生什么情况（拔插、崩溃），必须彻底清理资源！
                Log.i(TAG, "🛑 正在释放硬件管道和路由...")
                isStreamerRunning = false
                currentUsbTrack = null

                try { recorder?.stop() } catch (e: Exception) {}
                try { recorder?.release() } catch (e: Exception) {}
                try { track?.stop() } catch (e: Exception) {}
                try { track?.release() } catch (e: Exception) {}

                restoreSystemRouting()
            }
        }
    }
}