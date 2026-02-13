package com.lrust.twinaudio

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * 适配 Android 13 (API 33) 的 AudioService Hook
 * 目标：强行阻止系统在插入 USB/AUX 时切断蓝牙 A2DP
 */
object AudioServiceHook {

    private const val TAG = "TwinAudio-JavaHook"
    private var isHookEnabled = true

    fun init(classLoader: ClassLoader) {
        try {
            // Android 13 核心设备清点类
            val inventoryClass = XposedHelpers.findClass(
                "com.android.server.audio.AudioDeviceInventory",
                classLoader
            )

            // 1. 暴力拦截系统断开 A2DP 设备的指令
            XposedHelpers.findAndHookMethod(
                inventoryClass,
                "makeA2dpDeviceUnavailableNow",
                String::class.java, // address
                Int::class.java,    // a2dpCodec
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHookEnabled) {
                            Log.i(TAG, "⛔ 拦截成功: 阻止系统断开蓝牙 A2DP 设备！")
                            // 直接 return null，强行掐断系统的执行流
                            param.result = null
                        }
                    }
                }
            )

            // 2. 同样拦截延迟断开的指令 (防止系统耍花招)
            XposedHelpers.findAndHookMethod(
                inventoryClass,
                "makeA2dpDeviceUnavailableLater",
                String::class.java,
                Int::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHookEnabled) {
                            Log.i(TAG, "⛔ 拦截成功: 阻止系统延迟断开蓝牙 A2DP！")
                            param.result = null
                        }
                    }
                }
            )

            Log.i(TAG, "✓ Android 13 路由防断开 Hook 部署完毕")

        } catch (e: Exception) {
            Log.e(TAG, "Hook 失败: 找不到 Android 13 目标函数", e)
        }
    }

    fun setEnabled(enabled: Boolean) {
        isHookEnabled = enabled
    }
}