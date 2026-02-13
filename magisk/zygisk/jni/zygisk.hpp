// Zygisk API Header (Simplified)
// 完整版请从 Magisk 源码获取：https://github.com/topjohnwu/Magisk

#pragma once

#define ZYGISK_API_VERSION 4

#include <jni.h>

namespace zygisk {

struct Api;
struct AppSpecializeArgs;
struct ServerSpecializeArgs;

class ModuleBase {
public:
    virtual void onLoad(Api *api, JNIEnv *env) {}

    virtual void preAppSpecialize(AppSpecializeArgs *args) {}
    virtual void postAppSpecialize(const AppSpecializeArgs *args) {}

    virtual void preServerSpecialize(ServerSpecializeArgs *args) {}
    virtual void postServerSpecialize(const ServerSpecializeArgs *args) {}
};

struct AppSpecializeArgs {
    jstring nice_name;
    // ... 其他字段省略
};

struct ServerSpecializeArgs {
    // ... 字段省略
};

struct Api {
    // API 函数指针
    void (*hookJniNativeMethods)(JNIEnv *, const char *, JNINativeMethod *, int);
    void (*pltHookRegister)(const char *, const char *, void *, void **);
    // ... 其他 API
};

} // namespace zygisk

// 模块注册宏
#define REGISTER_ZYGISK_MODULE(clazz) \
    extern "C" void zygisk_module_entry(void *handle, void *logger) { \
        /* 初始化逻辑 */ \
    }

