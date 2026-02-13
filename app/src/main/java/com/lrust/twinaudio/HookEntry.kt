@file:Suppress("DEPRECATION", "unused")

package com.lrust.twinaudio

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * TwinAudio LSPosed Hook 入口
 * 在 system_server 中 Hook AudioService 和 AudioPolicyManager
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "TwinAudio-Hook"
        private const val SYSTEM_SERVER = "android"

        var modulePath: String = ""
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        startupParam?.let {
            modulePath = it.modulePath
            Log.i(TAG, "TwinAudio module loaded: $modulePath")
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        lpparam ?: return

        // 仅 Hook system_server 进程
        if (lpparam.packageName != SYSTEM_SERVER) {
            return
        }

        Log.i(TAG, "=== TwinAudio Hook Initializing in system_server ===")

        try {
            // 初始化 AudioService Hook
            AudioServiceHook.init(lpparam.classLoader)
            Log.i(TAG, "✓ AudioService hook initialized")

            // 如果需要，可以添加更多 Hook
            // AudioPolicyManagerHook.init(lpparam.classLoader)

            Log.i(TAG, "=== TwinAudio Hook Initialization Complete ===")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to initialize hooks", e)
        }
    }
}

