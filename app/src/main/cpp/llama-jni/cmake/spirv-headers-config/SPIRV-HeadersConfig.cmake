# Config minimo do SPIRV-Headers para o build Android do llama-jni.
#
# O ggml-vulkan do fork exige `find_package(SPIRV-Headers CONFIG REQUIRED)`, que
# so encontra pacotes INSTALADOS. Em vez de instalar nada, apontamos para a copia
# empacotada em app/src/main/cpp/spirv-headers (mesma usada pelo whisper).
get_filename_component(_sig_spirv_headers_include
        "${CMAKE_CURRENT_LIST_DIR}/../../../spirv-headers/include" ABSOLUTE)

if (NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${_sig_spirv_headers_include}")
endif ()

set(SPIRV-Headers_INCLUDE_DIRS "${_sig_spirv_headers_include}")
