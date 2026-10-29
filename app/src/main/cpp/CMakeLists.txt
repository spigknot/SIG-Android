cmake_minimum_required(VERSION 3.22.1)

project(sig_whisper)

set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -DNDEBUG")
add_compile_options(-O3 -DNDEBUG)

if (ANDROID AND DEFINED ANDROID_NDK)
    if (CMAKE_HOST_WIN32)
        set(SIG_HOST_TAG windows-x86_64)
        set(SIG_GLSLC_NAME glslc.exe)
        if (NOT DEFINED SIG_ZIG_EXE OR NOT EXISTS "${SIG_ZIG_EXE}")
            message(FATAL_ERROR
                    "Zig 0.16.0 nao encontrado. Execute a tarefa Gradle prepareZig antes do build nativo.")
        else()
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

add_library(sig_whisper SHARED whisper_jni.cpp whisper_wav_io.cpp whisper_mutf8.cpp)

file(GLOB_RECURSE SIG_WEBRTC_VAD_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/webrtcvad/webrtc/common_audio/signal_processing/*.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/webrtcvad/webrtc/common_audio/third_party/*.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/webrtcvad/webrtc/common_audio/vad/*.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/webrtcvad/webrtc/rtc_base/checks.cc")
target_sources(sig_whisper PRIVATE ${SIG_WEBRTC_VAD_SOURCES})

target_include_directories(sig_whisper PRIVATE
        whisper.cpp/include
        whisper.cpp/ggml/include
        webrtcvad)

target_compile_definitions(sig_whisper PRIVATE WEBRTC_POSIX)

target_compile_definitions(sig_whisper PRIVATE
        SIG_BUILD_GGML_OPENCL=1
        SIG_BUILD_GGML_VULKAN=1
        SIG_BUILD_GGML_OPENCL_EMBED_KERNELS=1
        SIG_BUILD_GGML_OPENCL_SOA_Q=1
        SIG_BUILD_GGML_OPENCL_USE_ADRENO_KERNELS=1)

# R2/fase C — build de DIAGNÓSTICO (macros de teste, AUSENTES do build normal):
#  - SIG_WHISPER_DUMP_HOOK : dumps mel/enc/logits (C2; env SIG_WHISPER_DUMP)
#  - SIG_VK_FA_FAIL_INJECT : falha simulada da pipeline FA (C1; env SIG_VK_FA_FAIL_INJECT)
# Ligado por -PsigWhisperDiag=true (Gradle -> -DSIG_WHISPER_DIAG=1).
# A ausência das macros/strings no build normal é verificada por strings/nm
# (evidência em build/whisper-rodada2/20261009-0149/fase-c/SUMMARY-C.md).
if (SIG_WHISPER_DIAG)
    message(STATUS "SIG_WHISPER_DIAG=1: hooks de diagnostico ATIVOS (somente teste)")
    target_compile_definitions(sig_whisper PRIVATE SIG_WHISPER_DUMP_HOOK=1)
    target_sources(sig_whisper PRIVATE sig_whisper_dump.cpp)
    # R10/fase C: instrumento do PRODUTOR/bytes do cache K/V (C1) + canario (C2).
    target_sources(sig_whisper PRIVATE sig_kv_trace.cpp)
    target_include_directories(whisper PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_compile_definitions(whisper PRIVATE SIG_WHISPER_DUMP_HOOK=1)
    if (TARGET ggml-opencl)
        # R3/fase E (E2): habilita a injecao de falha do build FA lazy
        # (GGML_OPENCL_FA_FAIL_INJECT) SOMENTE no build de diagnostico.
        target_compile_definitions(ggml-opencl PRIVATE SIG_WHISPER_DUMP_HOOK=1)
        # R12/A3: rastro por evento do OpenCL (SIGOCL_*) e injecao de falha do
        # FA (GGML_OPENCL_FA_FAIL_INJECT) sao instrumentos de ENSAIO: so
        # compilam no DIAG. No NORMAL nao existe getenv nem string SIGOCL_*
        # (verificado por varredura de bytes no exportWhisperNativeLib).
        target_compile_definitions(ggml-opencl PRIVATE SIG_WHISPER_OCL_TRACE=1)
    endif()
    if (TARGET ggml-vulkan)
        # R13/B2 — DIAG de trabalho em voo (fence dedicada, submit_seq,
        # dispatch_count, ponte de cancel owned). SOMENTE diagnostico: no build
        # normal a macro, as strings e os simbolos sig_vk_inflight_* nao existem.
        target_compile_definitions(ggml-vulkan PRIVATE SIG_WHISPER_DUMP_HOOK=1)
        target_compile_definitions(ggml-vulkan PRIVATE SIG_VK_FA_FAIL_INJECT=1)
        # R5/fase B (B2): trace de kernel x dispatch x copia da familia SOFT_MAX
        # (SOMENTE diagnostico; nunca no build normal - ver strings/nm no aceite).
        target_compile_definitions(ggml-vulkan PRIVATE SIG_VK_SOFTMAX_TRACE=1)
    endif()
endif()

target_link_libraries(sig_whisper
        whisper
        android
        log)

# Experimental and optional. This target probes QAIRT/QNN dynamically and is
# deliberately independent from the stable Whisper/GGML library above.
add_library(sig_npu_probe SHARED npu_probe_jni.cpp)
target_link_libraries(sig_npu_probe android log dl)
