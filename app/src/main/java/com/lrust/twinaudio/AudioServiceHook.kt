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

    private var isStreamerRunning = false
    private var streamerThread: Thread? = null
    private var btDevice: AudioDeviceInfo? = null
    private var usbDevice: AudioDeviceInfo? = null

    private var isPluginEnabled = true

    @Volatile private var targetDelayMs = 0
    @Volatile private var currentUsbVolume = 1.0f

    private var currentUsbTrack: AudioTrack? = null
    private var savedAudioManager: AudioManager? = null
    private var savedMediaStrategy: Any? = null
    private val engineLock = Any()

    private var mainHandler: Handler? = null
    private var debounceRunnable: Runnable? = null
    private var a2dpProxy: android.bluetooth.BluetoothProfile? = null

    fun init(classLoader: ClassLoader) {
        try {
            val projectionServiceClass = XposedHelpers.findClass("com.android.server.media.projection.MediaProjectionManagerService\$BinderService", classLoader)
            val iMediaProjectionClass = XposedHelpers.findClass("android.media.projection.IMediaProjection", classLoader)
            XposedHelpers.findAndHookMethod(projectionServiceClass, "isValidMediaProjection", iMediaProjectionClass,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam) = true
                })
        } catch (t: Throwable) {}

        try {
            val inventoryClass = XposedHelpers.findClass("com.android.server.audio.AudioDeviceInventory", classLoader)
            val smartDisconnectHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isPluginEnabled) return
                    try {
                        if (param.method.name == "onSetA2dpSinkConnectionState" && param.args[1] as Int != 0) return
                        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                        if (adapter != null && adapter.getProfileConnectionState(2) == 2) {
                            param.result = null
                        }
                    } catch (t: Throwable) {}
                }
            }
            XposedBridge.hookAllMethods(inventoryClass, "makeA2dpDeviceUnavailableNow", smartDisconnectHook)
            XposedBridge.hookAllMethods(inventoryClass, "makeA2dpDeviceUnavailableLater", smartDisconnectHook)
            XposedBridge.hookAllMethods(inventoryClass, "onSetA2dpSinkConnectionState", smartDisconnectHook)
        } catch (t: Throwable) {}

        try {
            val audioServiceClass = XposedHelpers.findClass("com.android.server.audio.AudioService", classLoader)
            XposedHelpers.findAndHookMethod(audioServiceClass, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                    mainHandler = Handler(Looper.getMainLooper())

                    try {
                        val cr = context.contentResolver
                        isPluginEnabled = android.provider.Settings.System.getInt(cr, "twinaudio_enabled", 1) == 1
                        currentUsbVolume = android.provider.Settings.System.getFloat(cr, "twinaudio_volume", 1.0f)
                        targetDelayMs = android.provider.Settings.System.getInt(cr, "twinaudio_delay", 0)
                    } catch (e: Exception) {}

                    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    adapter?.getProfileProxy(context, object : android.bluetooth.BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) { a2dpProxy = proxy }
                        override fun onServiceDisconnected(profile: Int) { a2dpProxy = null }
                    }, 2)

                    val filter = android.content.IntentFilter("com.lrust.twinaudio.UPDATE_CONFIG")
                    context.registerReceiver(object : android.content.BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: android.content.Intent) {
                            val hookEnabled = intent.getBooleanExtra("hookEnabled", true)
                            val volumeUsb = intent.getFloatExtra("volumeUsb", 1.0f)
                            val newDelay = intent.getIntExtra("delayMs", 0)

                            try {
                                val cr = context.contentResolver
                                android.provider.Settings.System.putInt(cr, "twinaudio_enabled", if (hookEnabled) 1 else 0)
                                android.provider.Settings.System.putFloat(cr, "twinaudio_volume", volumeUsb)
                                android.provider.Settings.System.putInt(cr, "twinaudio_delay", newDelay)
                            } catch (e: Exception) {}

                            isPluginEnabled = hookEnabled
                            currentUsbVolume = volumeUsb

                            // 🚀 设置硬件音量，最高不能超过 1.0，超过的部分由后续的 PCM 放大器处理
                            currentUsbTrack?.setVolume(currentUsbVolume.coerceAtMost(1.0f))

                            targetDelayMs = newDelay

                            if (!isPluginEnabled) {
                                synchronized(engineLock) { stopEngineLocked() }
                            } else if (!isStreamerRunning) {
                                thread { synchronized(engineLock) { startEngineLocked(context) } }
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
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                val newBt = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

                val devicesChanged = (newUsb?.id != usbDevice?.id) || (newBt?.id != btDevice?.id)
                usbDevice = newUsb
                btDevice = newBt

                if (isPluginEnabled && usbDevice != null && btDevice != null) {
                    if (!isStreamerRunning || devicesChanged) {
                        stopEngineLocked()
                        startEngineLocked(context)
                    }
                } else {
                    if (isStreamerRunning) stopEngineLocked()
                }
            }
        }

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

    private fun restoreSystemRouting() {
        try {
            if (savedAudioManager != null && savedMediaStrategy != null) {
                val clearPrefMethod = AudioManager::class.java.getMethod("removePreferredDeviceForStrategy", Class.forName("android.media.audiopolicy.AudioProductStrategy"))
                clearPrefMethod.invoke(savedAudioManager, savedMediaStrategy)
            }
        } catch (e: Exception) {} finally {
            savedMediaStrategy = null
            savedAudioManager = null
        }
    }

    private fun stopEngineLocked() {
        if (!isStreamerRunning) return
        isStreamerRunning = false
        streamerThread?.interrupt()
        try { streamerThread?.join(1000) } catch (e: Exception) {}
        streamerThread = null
        restoreSystemRouting()
    }

    private fun wakeUpBluetoothRadioAsync(address: String) {
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
            adapter.getProfileProxy(appContext, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    try {
                        proxy.javaClass.getMethod("setActiveDevice", android.bluetooth.BluetoothDevice::class.java).invoke(proxy, adapter.getRemoteDevice(address))
                    } catch (e: Exception) {}
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, 2)
        } catch (t: Throwable) {}
    }

    private var appContext: Context? = null

    @SuppressLint("MissingPermission")
    private fun startEngineLocked(context: Context) {
        if (btDevice == null || usbDevice == null) return
        appContext = context
        isStreamerRunning = true

        streamerThread = thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            var recorder: AudioRecord? = null
            var track: AudioTrack? = null

            try {
                val btAddress = btDevice!!.address
                if (a2dpProxy != null && btAddress.isNotEmpty()) {
                    try {
                        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                        a2dpProxy!!.javaClass.getMethod("setActiveDevice", android.bluetooth.BluetoothDevice::class.java).invoke(a2dpProxy, adapter.getRemoteDevice(btAddress))
                    } catch (e: Exception) { }
                } else {
                    wakeUpBluetoothRadioAsync(btAddress)
                }

                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setParameters("A2dpSuspended=false")

                try {
                    val strategiesClass = Class.forName("android.media.audiopolicy.AudioProductStrategy")
                    val strategies = AudioManager::class.java.getMethod("getAudioProductStrategies").invoke(null) as List<*>
                    val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                    var mediaStrategy: Any? = null
                    for (strategy in strategies) {
                        if (strategy!!::class.java.getMethod("supportsAudioAttributes", AudioAttributes::class.java).invoke(strategy, attr) as Boolean) {
                            mediaStrategy = strategy; break
                        }
                    }
                    if (mediaStrategy != null) {
                        val deviceAttrClass = Class.forName("android.media.AudioDeviceAttributes")
                        val btDeviceAttr = deviceAttrClass.getConstructor(AudioDeviceInfo::class.java).newInstance(btDevice)
                        AudioManager::class.java.getMethod("setPreferredDeviceForStrategy", strategiesClass, deviceAttrClass).invoke(audioManager, mediaStrategy, btDeviceAttr)
                        savedAudioManager = audioManager
                        savedMediaStrategy = mediaStrategy
                    }
                } catch (e: Exception) {}

                val iMediaProjectionClass = Class.forName("android.media.projection.IMediaProjection")
                val fakeBinder = Binder()
                val fakeIMediaProjection = Proxy.newProxyInstance(context.classLoader, arrayOf(iMediaProjectionClass), InvocationHandler { _, method, _ ->
                    when (method.name) {
                        "asBinder" -> fakeBinder
                        "canProjectAudio" -> true
                        "canProjectVideo", "canProjectSecureVideo" -> false
                        else -> if (method.returnType == java.lang.Boolean.TYPE) true else null
                    }
                })

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
                    recorder = AudioRecord.Builder().setAudioPlaybackCaptureConfig(captureConfig).setAudioFormat(format).setBufferSizeInBytes(bufferSize * 4).build()
                } finally { Binder.restoreCallingIdentity(identity) }

                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(format).setBufferSizeInBytes(bufferSize * 4).setTransferMode(AudioTrack.MODE_STREAM).build()

                track.setPreferredDevice(usbDevice)
                currentUsbTrack = track

                recorder.startRecording()
                track.play()
                track.setVolume(currentUsbVolume.coerceAtMost(1.0f))

                val frameSize = 4
                val sampleRate = 48000
                var totalWrittenFrames: Long = 0

                val MAX_DELAY_MS = 2000
                val maxDelayBytes = MAX_DELAY_MS * 48 * frameSize
                val delayBuffer = ByteArray(maxDelayBytes)
                var writePos = 0
                var actualDelayMs = targetDelayMs

                val buffer = ByteArray(bufferSize)
                val outBuffer = ByteArray(bufferSize)

                val targetUnplayedFrames = (bufferSize / frameSize) * 2
                val baseRate = 48000
                var currentRate = baseRate
                var loopCount = 0

                while (isStreamerRunning) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {

                        // =========================================================
                        // 🚀 终极黑科技 1：PCM 波形软件级数字放大器
                        // 突破系统音量压制，让 USB 音响瞬间爆炸！
                        // =========================================================
                        if (currentUsbVolume > 1.0f) {
                            val amp = currentUsbVolume
                            // 以 16-bit (2 byte) 为步长，修改底层音频数据
                            for (i in 0 until read - 1 step 2) {
                                val low = buffer[i].toInt() and 0xFF
                                val high = buffer[i + 1].toInt()
                                var sample = (high shl 8) or low
                                sample = (sample * amp).toInt()
                                // 硬件限幅器：防止爆音
                                sample = sample.coerceIn(-32768, 32767)
                                buffer[i] = sample.toByte()
                                buffer[i + 1] = (sample shr 8).toByte()
                            }
                        }

                        if (actualDelayMs != targetDelayMs) actualDelayMs = targetDelayMs
                        val delayBytes = (actualDelayMs * 48) * frameSize

                        var remain = read
                        var bufOff = 0
                        while (remain > 0) {
                            val chunk = minOf(remain, maxDelayBytes - writePos)
                            System.arraycopy(buffer, bufOff, delayBuffer, writePos, chunk)
                            writePos = (writePos + chunk) % maxDelayBytes
                            bufOff += chunk
                            remain -= chunk
                        }

                        var readPos = (writePos - delayBytes + maxDelayBytes) % maxDelayBytes
                        remain = read
                        bufOff = 0
                        while (remain > 0) {
                            val chunk = minOf(remain, maxDelayBytes - readPos)
                            System.arraycopy(delayBuffer, readPos, outBuffer, bufOff, chunk)
                            readPos = (readPos + chunk) % maxDelayBytes
                            bufOff += chunk
                            remain -= chunk
                        }

                        val written = track.write(outBuffer, 0, read)
                        if (written > 0) totalWrittenFrames += (written / frameSize)

                        // =========================================================
                        // 🚀 终极黑科技 2：空间重置 (Snap Resync) 切歌防错位引擎
                        // =========================================================
                        loopCount++
                        if (loopCount % 10 == 0) {
                            val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            val unplayedFrames = totalWrittenFrames - playedFrames
                            val errorFrames = unplayedFrames - targetUnplayedFrames

                            // 如果漂移量瞬间大得离谱（超过 100ms），说明是切歌导致的断流！
                            // 不再慢吞吞追赶，而是直接砸碎重建，一秒拍齐！
                            if (Math.abs(errorFrames) > 4800) {
                                Log.w(TAG, "⚡ 侦测到切歌断流 (偏差 $errorFrames 帧)，执行空间重置！")
                                track.pause()
                                track.flush()
                                delayBuffer.fill(0) // 抹除上首歌的残音
                                writePos = 0
                                totalWrittenFrames = 0
                                track.play()
                                currentRate = baseRate
                                track.playbackRate = currentRate
                                continue // 瞬间回到同一起跑线
                            }

                            // 如果只是微小的晶振漂移，依然使用 PID 丝滑拉回
                            var newRate = baseRate
                            if (Math.abs(errorFrames) > 48) {
                                newRate = baseRate + (errorFrames * 0.1f).toInt()
                            }
                            newRate = newRate.coerceIn(47800, 48200)

                            if (newRate != currentRate) {
                                currentRate = newRate
                                track.playbackRate = currentRate
                            }
                        }
                    }
                }

            } catch (t: Throwable) {
                Log.e(TAG, "引擎运行时崩溃跳出", t)
            } finally {
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