# TwinAudio 编译错误解决方案

## 问题摘要

当前遇到的编译错误主要有两类:

### 1. Dobby 源码兼容性问题
- ❌ ARM64 汇编错误: `invalid symbol kind for ADRP relocation`
- ❌ 头文件缺失: `'core/arch/Cpu.h' file not found`
- ❌ API 不匹配: `RuntimeModule` 缺少 `load_address` 成员

### 2. C/C++ 标准库冲突
- ❌ `no member named 'size_t' in the global namespace`
- ❌ `no member named 'ptrdiff_t' in the global namespace`

## 根本原因

你下载的 Dobby 源码版本不完整或与 Android NDK 25 不兼容。Dobby 项目本身已经不太活跃维护。

## 最佳解决方案 (推荐)

### 方案 A: 使用 LSPosed 预编译的 Dobby

LSPosed 项目维护了一个可用的 Dobby Fork:

1. **下载地址**: https://github.com/LSPosed/Dobby/releases
2. **下载文件**: `libdobby.a` (静态库) 或 `libdobby.so` (动态库)
3. **放置位置**:
   ```
   TwinAudio/magisk/zygisk/jni/dobby/libs/
   ├── arm64-v8a/
   │   └── libdobby.a
   └── armeabi-v7a/
       └── libdobby.a
   ```

4. **修改 CMakeLists.txt**:
   ```cmake
   # 使用预编译库
   add_library(dobby STATIC IMPORTED)
   set_target_properties(dobby PROPERTIES
       IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/dobby/libs/${ANDROID_ABI}/libdobby.a
   )
   ```

### 方案 B: 切换到 Shadowhook (更推荐)

**Shadowhook** 是字节跳动开源的 Android Hook 库,官方支持预编译版本:

1. **下载**: https://github.com/bytedance/android-inline-hook/releases
2. **优点**:
   - ✅ 官方维护活跃
   - ✅ 提供预编译库
   - ✅ 专为 Android 优化
   - ✅ 文档齐全,示例丰富

3. **API 替换对照**:
   ```cpp
   // Dobby
   DobbyHook(target, replace, &original);
   
   // Shadowhook
   shadowhook_hook_sym_addr(target, replace, &original);
   ```

### 方案 C: 暂时禁用 Native Hook (快速验证)

如果只想先验证项目流程,可以暂时禁用 audioserver 的 native hook:

1. **修改 `CMakeLists.txt`**:
   ```cmake
   # 暂时禁用 Dobby
   set(DOBBY_AVAILABLE FALSE)
   target_compile_definitions(${PROJECT_NAME} PRIVATE DOBBY_AVAILABLE=0)
   
   # 注释掉 Dobby 相关代码
   # add_subdirectory(dobby)
   # target_link_libraries(${PROJECT_NAME} dobby)
   ```

2. **修改 `audio_hook.cpp`**:
   所有 Dobby 相关代码用 `#if DOBBY_AVAILABLE` 包裹

3. **只保留 Java 层功能**:
   - LSPosed Hook (AudioService)
   - UI 控制界面
   - IPC 通信框架

## 我的建议

**对于非 Android 开发者,强烈推荐方案 C + 方案 B 的组合**:

1. **第一步**: 先禁用 Dobby,让项目编译通过,验证:
   - ✅ Magisk 模块能正常安装
   - ✅ App 能正常运行
   - ✅ LSPosed Hook 生效
   - ✅ IPC 通信正常

2. **第二步**: 再切换到 Shadowhook 实现 Native Hook:
   - ✅ 下载预编译库
   - ✅ 替换 Hook API
   - ✅ 测试音频劫持功能

## 下一步操作

请告诉我你想采用哪个方案,我会帮你完成:

1. **方案 A**: 我帮你修改 CMakeLists.txt 使用预编译 Dobby (需要你先下载)
2. **方案 B**: 我帮你切换到 Shadowhook (需要你先下载)
3. **方案 C**: 我帮你暂时禁用 Dobby,让项目先编译通过

**回复**: "方案 A"、"方案 B" 或 "方案 C"

