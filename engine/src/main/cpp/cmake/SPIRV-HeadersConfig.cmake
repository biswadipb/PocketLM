# Stands in for the config file that installing SPIRV-Headers would create, so llama.cpp's Vulkan backend can
# find the copy that lives in third_party/. SPIRV_HEADERS_ROOT is set by the engine's CMakeLists.txt.
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${SPIRV_HEADERS_ROOT}/include")
endif()
set(SPIRV-Headers_FOUND TRUE)
