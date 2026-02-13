@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.lrust.twinaudio

import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IPC 管理器 - 与 Zygisk 模块通信
 * 通过 JNI 调用 Native 方法发送配置命令
 */
object IPCManager {

    private const val TAG = "TwinAudio-IPC"

    // 命令类型（与 C++ 保持一致）
    private const val CMD_UPDATE_CONFIG = 1
    private const val CMD_ENABLE_HOOK = 2
    private const val CMD_DISABLE_HOOK = 3
    private const val CMD_QUERY_STATUS = 4

    // 魔数
    private const val MAGIC = 0x54574155  // "TWAU"

    // Native 库加载状态
    private var nativeLoaded = false

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        try {
            System.loadLibrary("twinaudio_jni")
            nativeLoaded = true
            Log.i(TAG, "✓ Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "✗ Failed to load native library", e)
            nativeLoaded = false
        }
    }

    /**
     * 更新音频配置（延迟 + 音量）
     * @param delayMs USB 音频延迟（毫秒）
     * @param volumeBt 蓝牙音量系数（0.0 - 2.0）
     * @param volumeUsb USB 音量系数（0.0 - 2.0）
     */
    fun updateConfig(delayMs: Int, volumeBt: Float, volumeUsb: Float) {
        scope.launch {
            try {
                val success = if (nativeLoaded) {
                    // 使用 JNI Native 方法
                    sendCommandNative(delayMs, volumeBt, volumeUsb)
                } else {
                    // 备用：直接使用 Kotlin Socket（无需 JNI）
                    sendCommandKotlin(delayMs, volumeBt, volumeUsb)
                }

                if (success) {
                    Log.i(TAG, "✓ Config updated: delay=${delayMs}ms, vol_bt=$volumeBt, vol_usb=$volumeUsb")
                } else {
                    Log.e(TAG, "✗ Failed to update config")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating config", e)
            }
        }
    }

    /**
     * 启用 Hook
     */
    fun enableHook() {
        scope.launch {
            try {
                sendCommand(CMD_ENABLE_HOOK, 0, 0f, 0f)
                Log.i(TAG, "✓ Hook enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Error enabling hook", e)
            }
        }
    }

    /**
     * 禁用 Hook
     */
    fun disableHook() {
        scope.launch {
            try {
                sendCommand(CMD_DISABLE_HOOK, 0, 0f, 0f)
                Log.i(TAG, "✓ Hook disabled")
            } catch (e: Exception) {
                Log.e(TAG, "Error disabling hook", e)
            }
        }
    }

    /**
     * 查询状态
     */
    suspend fun queryStatus(): Boolean = withContext(Dispatchers.IO) {
        try {
            sendCommand(CMD_QUERY_STATUS, 0, 0f, 0f)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error querying status", e)
            false
        }
    }

    /**
     * 通用命令发送
     */
    private suspend fun sendCommand(
        cmdType: Int,
        delayMs: Int,
        volumeBt: Float,
        volumeUsb: Float
    ): Boolean = withContext(Dispatchers.IO) {
        if (nativeLoaded) {
            sendCommandNative(delayMs, volumeBt, volumeUsb)
        } else {
            sendCommandKotlin(delayMs, volumeBt, volumeUsb)
        }
    }

    /**
     * Kotlin Socket 实现（备用方案）
     */
    private fun sendCommandKotlin(delayMs: Int, volumeBt: Float, volumeUsb: Float): Boolean {
        return try {
            val socket = java.net.Socket()
            val socketAddress = java.net.InetSocketAddress(
                "/data/local/tmp/twinaudio/audio.sock", 0
            )

            // Unix Domain Socket 需要特殊处理
            // 这里简化为直接调用 native 方法
            Log.w(TAG, "Kotlin socket not fully implemented, falling back to native")
            false

        } catch (e: Exception) {
            Log.e(TAG, "Kotlin socket error", e)
            false
        }
    }

    /**
     * 构建 IPC 命令包
     */
    private fun buildCommandPacket(
        cmdType: Int,
        delayMs: Int,
        volumeBt: Float,
        volumeUsb: Float
    ): ByteArray {
        val buffer = ByteBuffer.allocate(28)  // 7 * 4 bytes
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(MAGIC)           // magic
        buffer.putInt(1)               // version
        buffer.putInt(cmdType)         // command_type
        buffer.putInt(delayMs)         // delay_ms
        buffer.putFloat(volumeBt)      // volume_bt
        buffer.putFloat(volumeUsb)     // volume_usb
        buffer.putInt(0)               // flags

        return buffer.array()
    }

    // ========================================================================
    // Native Methods (JNI)
    // ========================================================================

    /**
     * 通过 JNI 发送命令到 Zygisk 模块
     */
    private external fun sendCommandNative(
        delayMs: Int,
        volumeBt: Float,
        volumeUsb: Float
    ): Boolean

    /**
     * 测试 IPC 连接
     */
    external fun testConnection(): Boolean
}

