# Builds the on-device Whisper inference engine used by the "Local model" transcription backend.
# whisper.cpp is vendored as a pinned git submodule at ./whisper.cpp (see .gitmodules); this file
# only adds our own JNI glue on top of it. The CMake structure follows whisper.cpp's own Android
# JNI example (examples/whisper.android/lib/src/main/jni/whisper/CMakeLists.txt as of v1.7.6),
# simplified to a single shared library per ABI since Gradle already produces one build per ABI.

cmake_minimum_required(VERSION 3.10)
project(quickdictate_whisper)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF)

set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

find_library(LOG_LIB log)

# CPU architecture baseline. This has to be set *before* ggml is added, and it matters far more
# than it looks: essentially all of the inference math lives inside the ggml target, not in our
# JNI wrapper or in whisper.cpp itself.
#
# ggml/src/ggml-cpu/CMakeLists.txt only derives ARM arch flags from `-mcpu=native` probing when
# GGML_NATIVE is on. Android is always a cross-compile, so GGML_NATIVE is off and ggml instead
# looks for GGML_CPU_ARM_ARCH (or GGML_CPU_ALL_VARIANTS). With neither set it emits no -march at
# all and silently falls back to the NDK's plain armv8-a baseline - no FP16 vector arithmetic, no
# dot-product instructions - which makes every quantized/F16 matmul take a slow conversion path
# and costs roughly an order of magnitude in transcription speed.
#
# fp16 (FEAT_FP16 / "asimdhp") and dotprod (FEAT_DotProd / "asimddp") are the two that matter for
# Whisper. Both arrived with Cortex-A75/A55-era cores (~2017); LocalWhisperNative refuses to load
# the library on a device missing either, so an older chip degrades to "local model unavailable"
# rather than crashing on an illegal instruction.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
    set(QUICKDICTATE_ARCH_FLAGS -march=armv8.2-a+fp16+dotprod)
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(GGML_CPU_ARM_ARCH "armv7-a" CACHE STRING "" FORCE)
    set(QUICKDICTATE_ARCH_FLAGS -mfpu=neon-vfpv4)
endif()

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

# The CPU kernels live in the separate `ggml-cpu` target, not `ggml` (which is a thin wrapper), so
# that is what any extra compile options have to be attached to - whisper.cpp's Android example
# applies them to `ggml` instead, which never reaches the actual math. GGML_CPU_ARM_ARCH above
# already feeds ggml-cpu's own -march; this additionally covers the 32-bit FPU selection, which
# ggml's CMake does not derive on its own.
if (TARGET ggml-cpu)
    target_compile_options(ggml-cpu PRIVATE ${QUICKDICTATE_ARCH_FLAGS})
endif()

add_library(local_whisper SHARED
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/local_whisper_jni.cpp
)

target_compile_definitions(local_whisper PUBLIC GGML_USE_CPU)
target_compile_options(local_whisper PRIVATE ${QUICKDICTATE_ARCH_FLAGS})

# Gradle's release build configures CMake as RelWithDebInfo, which defaults to -O2; whisper.cpp's
# own Android example forces -O3 for anything that isn't a Debug build, so match that for both the
# wrapper and ggml rather than leaving the hot kernels a rung below their intended optimization.
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(local_whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
    if (TARGET ggml-cpu)
        target_compile_options(ggml-cpu PRIVATE -O3)
    endif()
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
