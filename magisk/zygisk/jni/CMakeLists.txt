cmake_minimum_required(VERSION 3.22.1)
project("twinaudio_native")

# C++17 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -D__STDC_LIMIT_MACROS -D__STDC_CONSTANT_MACROS -D__STDC_FORMAT_MACROS")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fvisibility=hidden -ffunction-sections -fdata-sections")

find_library(log-lib log)

# ========================================
# 终极方案：FetchContent 自动拉取并编译 LSPosed 版 Dobby
# ========================================
include(FetchContent)

# 强制将 Dobby 编译为静态库，并强制开启 -fPIC（完美解决你的重定位报错！）
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "" FORCE)

message(STATUS "Downloading Dobby from LSPosed...")
FetchContent_Declare(
    dobby
    GIT_REPOSITORY https://github.com/LSPosed/Dobby.git
    GIT_TAG        master
)
FetchContent_MakeAvailable(dobby)

# ========================================

# 主 Native 库
add_library(${PROJECT_NAME} SHARED
    main.cpp
    audio_hook.cpp
    audio_buffer.cpp
    ipc_server.cpp
)

target_include_directories(${PROJECT_NAME} PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${dobby_SOURCE_DIR}/include  # 自动指向刚才下载的 Dobby 源码头文件
)

# 链接库：自动将编译好的 Dobby 完美融合进我们的 .so 中
target_link_libraries(${PROJECT_NAME}
    ${log-lib}
    android
    dobby
)

target_compile_definitions(${PROJECT_NAME} PRIVATE DOBBY_AVAILABLE=1)

set_target_properties(${PROJECT_NAME} PROPERTIES
    LINK_FLAGS "-Wl,--gc-sections,--strip-debug"
    OUTPUT_NAME "twinaudio_native"
)