cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)

# --------------------------------------------------------------------------
# GPU backends: Vulkan (any vendor) and OpenCL (Qualcomm Adreno). Each becomes its own library, which the app
# loads only when the user turns a GPU on, so a phone without a working driver is never touched. The Android NDK
# lacks the C++ Vulkan header, the SPIR-V headers and OpenCL, so small Khronos header repositories in third_party/
# provide them.
# --------------------------------------------------------------------------
set(THIRD_PARTY ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)

set(GGML_VULKAN ON)
# CMake 3.22 does not look for the shader compiler where the NDK keeps it.
if(ANDROID_NDK AND EXISTS "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc")
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc" CACHE FILEPATH "" FORCE)
endif()
set(SPIRV_HEADERS_ROOT ${THIRD_PARTY}/SPIRV-Headers)
set(SPIRV-Headers_DIR ${CMAKE_CURRENT_LIST_DIR}/cmake CACHE PATH "Where the stand-in SPIRV-HeadersConfig.cmake is" FORCE)
include_directories(BEFORE SYSTEM ${THIRD_PARTY}/Vulkan-Headers/include ${THIRD_PARTY}/SPIRV-Headers/include)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENCL ON)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON)
    set(GGML_OPENCL_EMBED_KERNELS ON)
    # The OpenCL loader is built only to link against. It is left out of the app, so at run time the phone's own
    # libOpenCL.so is used (the manifest asks Android to make it available).
    set(OPENCL_ICD_LOADER_HEADERS_DIR ${THIRD_PARTY}/OpenCL-Headers CACHE PATH "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    add_subdirectory(${THIRD_PARTY}/OpenCL-ICD-Loader build-opencl-loader)
    set_target_properties(OpenCL PROPERTIES VERSION "" SOVERSION "")
    set(OpenCL_INCLUDE_DIR ${THIRD_PARTY}/OpenCL-Headers CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
endif()

# The Vulkan shaders are compiled by a small helper built for the computer doing the build (not for the phone).
# llama.cpp would configure it without a build tool when that tool comes from the Android SDK, so say which to use.
# Prefer the computer's own compilers over the Android NDK's, which do not know where the Mac or Linux headers are.
find_program(POCKETLM_HOST_CC NAMES clang gcc cc PATHS /usr/bin /usr/local/bin NO_DEFAULT_PATH)
find_program(POCKETLM_HOST_CXX NAMES clang++ g++ c++ PATHS /usr/bin /usr/local/bin NO_DEFAULT_PATH)
if(POCKETLM_HOST_CC AND POCKETLM_HOST_CXX)
    file(WRITE ${CMAKE_BINARY_DIR}/vulkan-host-toolchain.cmake
        "set(CMAKE_C_COMPILER \"${POCKETLM_HOST_CC}\")\n"
        "set(CMAKE_CXX_COMPILER \"${POCKETLM_HOST_CXX}\")\n")
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN ${CMAKE_BINARY_DIR}/vulkan-host-toolchain.cmake CACHE FILEPATH "" FORCE)
endif()
# The Ninja that comes with the Android SDK is not on the PATH of the helper's own build, so that one uses plain
# Makefiles. Only the helper is changed: it is the one ExternalProject here, and everything else keeps its generator.
include(ExternalProject)
function(ExternalProject_Add name)
    if(CMAKE_HOST_UNIX AND name STREQUAL "vulkan-shaders-gen")
        _ExternalProject_Add(${name} ${ARGN} CMAKE_GENERATOR "Unix Makefiles")
    else()
        _ExternalProject_Add(${name} ${ARGN})
    endif()
endfunction()

add_subdirectory(${LLAMA_SRC} build-llama)

# llama.cpp loads every library named libggml-<backend>.so when it starts. Rename the GPU ones so that does not
# happen; the app loads them itself, later, and only on request.
if(TARGET ggml-vulkan)
    set_target_properties(ggml-vulkan PROPERTIES OUTPUT_NAME "gpu-vulkan")
endif()
if(TARGET ggml-opencl)
    set_target_properties(ggml-opencl PROPERTIES OUTPUT_NAME "gpu-opencl")
endif()
# Multimodal (vision) support lives in llama.cpp's tools/, which the main build skips.
add_subdirectory(${LLAMA_SRC}/tools/mtmd build-mtmd)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp
        embedder.cpp
        speech.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_SRC}/tools/mtmd)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        mtmd
        android
        log)
