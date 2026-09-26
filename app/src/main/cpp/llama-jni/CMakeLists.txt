cmake_minimum_required(VERSION 3.22.1)

# Projeto isolado do sig_llama (llama.cpp + kernel STQ + JNI da tradução).
#
# Fora do CMake principal de propósito: o whisper.cpp e o llama.cpp empacotam
# cópias do ggml com os MESMOS nomes de target (ggml, ggml-base, ggml-cpu) e não
# podem coexistir num mesmo projeto CMake. Aqui só o llama é compilado; o
# `build-android-native-dependencies.ps1` invoca este projeto por ABI e empacota
# libsig_llama.so no pacote nativo (o APK continua sem .so).
project(sig_llama C CXX)

set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options(-O3 -DNDEBUG)

# Ferramentas de shader do Vulkan para o HOST (Windows): mesmo arranjo do CMake
# principal do whisper — Zig compila o vulkan-shaders-gen para o host enquanto o
# resto é compilado para o Android. Sem o SIG_ZIG_EXE o configure falha de caso
# pensado no Windows.
if (ANDROID AND DEFINED ANDROID_NDK)
    if (CMAKE_HOST_WIN32)
        set(SIG_HOST_TAG windows-x86_64)
        set(SIG_GLSLC_NAME glslc.exe)
        if (NOT DEFINED SIG_ZIG_EXE OR NOT EXISTS "${SIG_ZIG_EXE}")
            message(FATAL_ERROR
                    "Zig 0.16.0 nao encontrado. Passe -DSIG_ZIG_EXE=<caminho do zig.exe>.")
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

# Vulkan: o stub do NDK para a API mínima (24) é Vulkan 1.0 e não expõe
# vkGetPhysicalDeviceFeatures2 (core desde Vulkan 1.1, usado pelo ggml-vulkan).
# O link usa o stub da API 28 (só a tabela de símbolos para o link); em runtime
# quem resolve é o loader do aparelho (Android 9+ = Vulkan 1.1).
if (ANDROID AND DEFINED SIG_HOST_TAG)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(SIG_ANDROID_TRIPLE "aarch64-linux-android")
    elseif (ANDROID_ABI STREQUAL "x86_64")
        set(SIG_ANDROID_TRIPLE "x86_64-linux-android")
    endif ()
    if (DEFINED SIG_ANDROID_TRIPLE)
        set(Vulkan_LIBRARY
                "${ANDROID_NDK}/toolchains/llvm/prebuilt/${SIG_HOST_TAG}/sysroot/usr/lib/${SIG_ANDROID_TRIPLE}/28/libvulkan.so"
                CACHE FILEPATH "NDK API-28 Vulkan stub (Vulkan 1.1 symbols)" FORCE)
    endif ()
endif()

# Android: STL compartilhado (libc++_shared.so já vai no pacote nativo).
# max-page-size=16384: segmentos alinhados a páginas de 16 KB — exigência dos
# devices Android 15+ (Pixel 9, OnePlus 15): sem isso o dlopen falha com
# "program alignment (4096) cannot be smaller than system page size (16384)".
if (ANDROID)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384")
endif ()

# Só a inferência é necessária: sem testes/exemplos/tools/common/server.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# CPU + GPU (Vulkan e OpenCL/Adreno — mesmos backends do whisper). NPU fica para
# depois. GGML_OPENMP OFF: o pacote já tem libomp para o whisper, mas manter o
# llama sem OpenMP evita carga extra de libomp no loadLibrary.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# OpenCL do Android: headers empacotados + loader proprio (espelho do whisper).
set(SIG_OPENCL_INCLUDE_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../opencl-headers/include"
        CACHE PATH "Bundled OpenCL headers" FORCE)
set(SIG_OPENCL_LOADER_SOURCE
        "${CMAKE_CURRENT_SOURCE_DIR}/../android-opencl-loader.cpp"
        CACHE FILEPATH "Android OpenCL runtime loader" FORCE)

# SPIRV-Headers: o find_package CONFIG do ggml-vulkan exige pacote instalado;
# apontamos para o config minimo que usa a copia empacotada do projeto.
set(SPIRV-Headers_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/cmake/spirv-headers-config"
        CACHE PATH "Bundled SPIRV-Headers config" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../llama llama-build EXCLUDE_FROM_ALL)

if (TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/../vulkan-headers/include"
            "${CMAKE_CURRENT_SOURCE_DIR}/../spirv-headers/include")
    # O patch de "sem crash" fica em ggml-vulkan.cpp (target ggml-vulkan), então a
    # flag precisa ser DEFINIDA nele — definir só no sig_llama não tem efeito.
    target_compile_definitions(ggml-vulkan PRIVATE SIG_VULKAN_NO_CRASH=1)
endif()

add_library(sig_llama SHARED llama_jni.cpp)

target_include_directories(sig_llama PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../llama/include
        ${CMAKE_CURRENT_SOURCE_DIR}/../llama/ggml/include)

target_compile_definitions(sig_llama PRIVATE
        SIG_BUILD_GGML_OPENCL=1
        SIG_BUILD_GGML_VULKAN=1
        SIG_VULKAN_NO_CRASH=1)

target_link_libraries(sig_llama
        llama
        android
        log)
