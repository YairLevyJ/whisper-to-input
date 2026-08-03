# Builds the on-device Whisper inference engine used by the "Local model" transcription backend.
# whisper.cpp is vendored as a pinned git submodule at ./whisper.cpp (see .gitmodules); this file
# only adds our own JNI glue on top of it. The CMake structure mirrors whisper.cpp's own Android
# JNI example (examples/whisper.android/lib/src/main/jni/whisper/CMakeLists.txt as of v1.7.6),
# simplified to a single shared library per ABI instead of the fp16/vfpv4 fallback pair upstream
# builds, since Gradle already produces one build per ABI.

cmake_minimum_required(VERSION 3.10)
project(quickdictate_whisper)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF)

set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

find_library(LOG_LIB log)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

add_library(local_whisper SHARED
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/local_whisper_jni.cpp
)

target_compile_definitions(local_whisper PUBLIC GGML_USE_CPU)

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(local_whisper PRIVATE -march=armv8.2-a+fp16)
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(local_whisper PRIVATE -mfpu=neon-vfpv4)
endif()

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(local_whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
    target_link_options(local_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()

target_include_directories(local_whisper PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

target_link_libraries(local_whisper ${LOG_LIB} android ggml)
