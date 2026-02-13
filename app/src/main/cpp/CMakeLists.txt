cmake_minimum_required(VERSION 3.22.1)
project("twinaudio_jni")

set(CMAKE_CXX_STANDARD 17)

find_library(log-lib log)

# 可选的 JNI 通信辅助库
add_library(${PROJECT_NAME} SHARED
    ipc_client.cpp
)

target_link_libraries(${PROJECT_NAME}
    ${log-lib}
    android
)

