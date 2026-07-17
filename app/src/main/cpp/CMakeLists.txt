cmake_minimum_required(VERSION 3.22.1)

project(sig_whisper)

set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -DNDEBUG")
add_compile_options(-O3 -DNDEBUG)

if (ANDROID AND DEFINED ANDROID_NDK)
    if (CMAKE_HOST_WIN32)
        set(SIG_HOST_TAG windows-x86_64)
        set(SIG_GLSLC_NAME glslc.exe)
        file(TO_CMAKE_PATH "$ENV{TEMP}/codex-zig-0.16.0/zig.exe" SIG_ZIG_EXE)
        if (EXISTS "${SIG_ZIG_EXE}")
            set(SIG_ZIG_CC "${CMAKE_BINARY_DIR}/sig-zig-cc.bat")
            set(SIG_ZIG_CXX "${CMAKE_BINARY_DIR}/sig-zig-cxx.bat")
            file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" SIG_NINJA_EXE)
            file(WRITE "${SIG_ZIG_CC}" "@echo off\r\n\"${SIG_ZIG_EXE}\" cc %*\r\n")
            file(WRITE "${SIG_ZIG_CXX}" "@echo off\r\n\"${SIG_ZIG_EXE}\" c++ %*\r\n")
            file(WRITE "${CMAKE_BINARY_DIR}/sig-zig-host-toolchain.cmake"
                    "set(CMAKE_BUILD_TYPE Release)\n"
                    "set(CMAKE_C_FLAGS -O2)\n"
                    "set(CMAKE_CXX_FLAGS -O2)\n"
                    "set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)\n"
                    "set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)\n"
                    "set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)\n"
                    "set(CMAKE_C_COMPILER \"${SIG_ZIG_CC}\" CACHE FILEPATH \"\" FORCE)\n"
                    "set(CMAKE_CXX_COMPILER \"${SIG_ZIG_CXX}\" CACHE FILEPATH \"\" FORCE)\n"
                    "set(CMAKE_MAKE_PROGRAM \"${SIG_NINJA_EXE}\" CACHE FILEPATH \"\" FORCE)\n")
            set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
                    "${CMAKE_BINARY_DIR}/sig-zig-host-toolchain.cmake"
                    CACHE FILEPATH "Host toolchain for Vulkan shader generator" FORCE)
            set(SIG_VULKAN_HOST_C_COMPILER "${SIG_ZIG_CC}" CACHE FILEPATH "Host C compiler for Vulkan shader generator" FORCE)
            set(SIG_VULKAN_HOST_CXX_COMPILER "${SIG_ZIG_CXX}" CACHE FILEPATH "Host CXX compiler for Vulkan shader generator" FORCE)
            set(SIG_VULKAN_HOST_MAKE_PROGRAM "${SIG_NINJA_EXE}" CACHE FILEPATH "Host Ninja for Vulkan shader generator" FORCE)
        endif()
    elseif (CMAKE_HOST_APPLE)
        set(SIG_HOST_TAG darwin-x86_64)
        set(SIG_GLSLC_NAME glslc)
    else()
        set(SIG_HOST_TAG linux-x86_64)
        set(SIG_GLSLC_NAME glslc)
    endif()
    set(Vulkan_GLSLC_EXECUTABLE
            "${ANDROID_NDK}/shader-tools/${SIG_HOST_TAG}/${SIG_GLSLC_NAME}"
            CACHE FILEPATH "Android NDK glslc" FORCE)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)
set(SIG_OPENCL_INCLUDE_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/opencl-headers/include"
        CACHE PATH "Bundled OpenCL headers" FORCE)
set(SIG_OPENCL_LOADER_SOURCE
        "${CMAKE_CURRENT_SOURCE_DIR}/android-opencl-loader.cpp"
        CACHE FILEPATH "Android OpenCL runtime loader" FORCE)

add_subdirectory(whisper.cpp whisper-build EXCLUDE_FROM_ALL)

if (TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/vulkan-headers/include"
            "${CMAKE_CURRENT_SOURCE_DIR}/spirv-headers/include")
endif()

add_library(sig_whisper SHARED whisper_jni.cpp)

target_include_directories(sig_whisper PRIVATE
        whisper.cpp/include
        whisper.cpp/ggml/include)

target_compile_definitions(sig_whisper PRIVATE
        SIG_BUILD_GGML_OPENCL=1
        SIG_BUILD_GGML_VULKAN=1
        SIG_BUILD_GGML_OPENCL_EMBED_KERNELS=1
        SIG_BUILD_GGML_OPENCL_SOA_Q=1
        SIG_BUILD_GGML_OPENCL_USE_ADRENO_KERNELS=1)

target_link_libraries(sig_whisper
        whisper
        android
        log)

# Experimental and optional. This target probes QAIRT/QNN dynamically and is
# deliberately independent from the stable Whisper/GGML library above.
add_library(sig_npu_probe SHARED npu_probe_jni.cpp)
target_link_libraries(sig_npu_probe android log dl)
