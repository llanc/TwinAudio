#!/system/bin/sh
# TwinAudio Magisk Module - Installation Script
# 基于 LD_PRELOAD 注入机制

SKIPUNZIP=0
ASH_STANDALONE=1

ui_print "============================================"
ui_print " TwinAudio - 双音频输出模块"
ui_print " 作者: lrust"
ui_print " 版本: v1.0"
ui_print " 注入方式: LD_PRELOAD"
ui_print "============================================"

# 检查 Android 版本
ANDROID_API=$(getprop ro.build.version.sdk)
ui_print "- 检测系统版本: Android API $ANDROID_API"

if [ "$ANDROID_API" -lt 33 ]; then
    ui_print "⚠ 警告: 此模块专为 Android 13 (API 33) 设计"
    ui_print "  当前系统可能无法正常工作！"
    ui_print ""
fi

# 检查 Magisk 版本
ui_print "- 检测 Magisk 版本: $MAGISK_VER_CODE"
if [ "$MAGISK_VER_CODE" -lt 25000 ]; then
    abort "✗ 需要 Magisk 25.0+ 版本！当前版本过低。"
fi

# 检测设备 ABI
ABI=$(getprop ro.product.cpu.abi)
ui_print "- 检测设备架构: $ABI"

# 验证 Native 库文件是否存在
LIB_PATH=""
case "$ABI" in
    arm64-v8a)
        LIB_PATH="$MODPATH/lib/arm64-v8a/libtwinaudio_native.so"
        ;;
    armeabi-v7a)
        LIB_PATH="$MODPATH/lib/armeabi-v7a/libtwinaudio_native.so"
        ;;
    x86_64)
        LIB_PATH="$MODPATH/lib/x86_64/libtwinaudio_native.so"
        ;;
    x86)
        LIB_PATH="$MODPATH/lib/x86/libtwinaudio_native.so"
        ;;
    *)
        abort "✗ 不支持的架构: $ABI"
        ;;
esac

# 检查 Native 库是否存在
if [ ! -f "$LIB_PATH" ]; then
    ui_print "✗ 错误: Native 库文件缺失！"
    ui_print "  预期位置: $LIB_PATH"
    abort "请重新下载完整模块或检查编译输出。"
fi

ui_print "✓ Native 库验证通过"

# 设置文件权限
ui_print "- 设置文件权限..."
set_perm_recursive "$MODPATH" 0 0 0755 0644

# 设置库文件权限（644 - 可读）
set_perm_recursive "$MODPATH/lib" 0 0 0755 0644

# 设置脚本权限（755 - 可执行）
if [ -f "$MODPATH/service.sh" ]; then
    set_perm "$MODPATH/service.sh" 0 0 0755
    ui_print "✓ service.sh 权限已设置"
fi

# 创建工作目录
mkdir -p /data/local/tmp/twinaudio
chmod 777 /data/local/tmp/twinaudio

ui_print "✓ 安装完成！"
ui_print "============================================"
ui_print " 重要提示:"
ui_print " 1. ✅ 无需启用 Magisk Zygisk"
ui_print " 2. ✅ 确保已安装 LSPosed 框架"
ui_print " 3. ✅ 在 LSPosed 中激活 TwinAudio 应用"
ui_print " 4. ✅ 勾选 system_server 作用域"
ui_print " 5. ✅ 重启设备生效"
ui_print ""
ui_print " 验证方法（重启后）:"
ui_print " adb logcat | grep TwinAudio"
ui_print "============================================"

