package com.lrust.twinaudio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Binder
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
    private var isStreamerRunning = false
    private var streamerThread: Thread? = null
    private var btDevice: AudioDeviceInfo? = null

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

        // 2. 🛡️ 无死角锁死蓝牙状态！
        try {
            val inventoryClass = XposedHelpers.findClass("com.android.server.audio.AudioDeviceInventory", classLoader)
            XposedBridge.hookAllMethods(inventoryClass, "makeA2dpDeviceUnavailableNow", XC_MethodReplacement.returnConstant(null))
            XposedBridge.hookAllMethods(inventoryClass, "makeA2dpDeviceUnavailableLater", XC_MethodReplacement.returnConstant(null))
            XposedBridge.hookAllMethods(inventoryClass, "onSetA2dpSinkConnectionState", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[1] as Int == 0) param.result = null
                }
            })
        } catch (t: Throwable) {}

        // 3. 监听设备插拔
        try {
            val audioServiceClass = XposedHelpers.findClass("com.android.server.audio.AudioService", classLoader)
            XposedHelpers.findAndHookMethod(audioServiceClass, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                    setupDeviceMonitor(context)
                }
            })
        } catch (t: Throwable) {}
    }

    private fun setupDeviceMonitor(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val checkDevices = Runnable {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hasWired = devices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            btDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

            if (hasWired && btDevice != null) {
                Log.i(TAG, "🎧 检测到双设备！准备分发...")
                startDualAudioStreamer(context)
            } else {
                stopDualAudioStreamer()
            }
        }

        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { checkDevices.run() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { checkDevices.run() }
        }, null)

        checkDevices.run()
    }

    private fun stopDualAudioStreamer() {
        isStreamerRunning = false
        streamerThread?.interrupt()
        streamerThread = null
    }

    private fun wakeUpBluetoothRadio(context: Context, address: String) {
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.getProfileProxy(context, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    try {
                        val deviceObj = adapter.getRemoteDevice(address)
                        val setActiveMethod = proxy.javaClass.getMethod("setActiveDevice", android.bluetooth.BluetoothDevice::class.java)
                        setActiveMethod.invoke(proxy, deviceObj)
                    } catch (e: Exception) {}
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, 2)
        } catch (t: Throwable) {}
    }

    @SuppressLint("MissingPermission")
    private fun startDualAudioStreamer(context: Context) {
        if (isStreamerRunning) return
        if (btDevice == null) return
        isStreamerRunning = true

        streamerThread = thread {
            // 🚨 音质保驾护航 1：将当前线程提升为系统最高级别的紧急音频线程！杜绝卡顿！
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            try {
                // 凭证伪装
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
                    // 🚨 音质保驾护航 2：排除自己 (system_server) 发出的声音，彻底斩断无限循环的回音壁！
                    .excludeUid(android.os.Process.myUid())
                    .build()

                val format = AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()

                val bufferSize = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)

                val identity = Binder.clearCallingIdentity()
                var recorder: AudioRecord? = null
                try {
                    recorder = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize * 4)
                        .build()
                } finally { Binder.restoreCallingIdentity(identity) }

                val track = AudioTrack.Builder()
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

                track.setPreferredDevice(btDevice)

                recorder!!.startRecording()
                track.play()
                track.setVolume(AudioTrack.getMaxVolume())

                wakeUpBluetoothRadio(context, btDevice!!.address)
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setParameters("A2dpSuspended=false")

                val buffer = ByteArray(bufferSize)
                Log.i(TAG, "🎵 开始无损搬运！(已开启抗回音与 URGET_AUDIO 加速)")

                var loopCount = 0
                while (isStreamerRunning) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        track.write(buffer, 0, read)
                    }
                }

                recorder.stop(); recorder.release(); track.stop(); track.release()

            } catch (t: Throwable) {
                isStreamerRunning = false
            }
        }
    }
}