// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Magisk 模块打包任务
tasks.register<Zip>("packageMagiskModule") {
    group = "magisk"
    description = "打包 TwinAudio Magisk 模块"

    // 确保打包前先执行编译和拷贝任务（修复隐式依赖报错）
    dependsOn(":magisk:zygisk:assembleRelease")
    dependsOn(":magisk:zygisk:copyNativeLibs")  // <--- 就是补回这一行！

    archiveFileName.set("TwinAudio-v1.0.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/magisk"))

    // 1. 复制 Magisk 基础脚本和配置文件
    from("magisk") {
        include("module.prop", "service.sh", "customize.sh", "sepolicy.rule")
    }

    // 2. 暴力搜索生成的 .so 文件
    from(fileTree("magisk/zygisk/build/intermediates").matching {
        include("**/obj/**/*.so")
        include("**/stripped_native_libs/**/*.so")
    }) {
        // 将找到的 .so 文件拍扁，按架构放进 zip 包的 lib/ 目录下
        eachFile {
            val abi = file.parentFile.name // 获取 arm64-v8a 等架构名
            path = "lib/$abi/${file.name}"
        }
        includeEmptyDirs = false
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    doLast {
        println("========================================")
        println("✅ Magisk 模块打包成功！")
        println("📦 产物路径: ${outputs.files.singleFile.absolutePath}")
        println("========================================")
    }
}
