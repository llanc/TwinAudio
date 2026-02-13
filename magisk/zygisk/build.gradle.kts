plugins {
    id("com.android.library")
}

android {
    namespace = "com.lrust.twinaudio.zygisk"
    compileSdk = 33
    ndkVersion = "25.2.9519653"

    defaultConfig {
        minSdk = 33

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_CXX_STANDARD=17"
                )
                cppFlags += "-std=c++17"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// 编译后复制 .so 到 Magisk 模块目录
tasks.register<Copy>("copyNativeLibs") {
    dependsOn("assembleRelease")
    from("build/intermediates/stripped_native_libs/release/out/lib")
    into("../")
    include("**/*.so")
}

afterEvaluate {
    tasks.named("assembleRelease").configure {
        finalizedBy("copyNativeLibs")
    }
}

