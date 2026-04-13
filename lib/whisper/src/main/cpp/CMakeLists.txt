cmake_minimum_required(VERSION 3.10)

project(whisper.cpp.android)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../../third_party/whisper.cpp)
option(VOICE_WHISPER_ENABLE_VULKAN "Enable Vulkan acceleration for whisper.cpp on Android" ON)

file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()

set(
    SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

include(FetchContent)

function(configure_whisper_acceleration)
    set(GGML_VULKAN OFF)
    set(GGML_VULKAN OFF CACHE BOOL "Disable Vulkan backend" FORCE)

    if (NOT ANDROID OR NOT VOICE_WHISPER_ENABLE_VULKAN)
        message(STATUS "whisper.cpp Android acceleration: Vulkan disabled")
        return()
    endif()

    set(_host_tag "")
    set(_host_suffix "")
    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(_host_tag "windows-x86_64")
        set(_host_suffix ".exe")
    elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        set(_host_tag "linux-x86_64")
    elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        if (CMAKE_HOST_SYSTEM_PROCESSOR STREQUAL "arm64")
            set(_host_tag "darwin-x86_64")
        else()
            set(_host_tag "darwin-x86_64")
        endif()
    endif()

    set(_ndk_root "")
    foreach(candidate
            "${CMAKE_ANDROID_NDK}"
            "${ANDROID_NDK}"
            "$ENV{ANDROID_NDK_HOME}"
            "$ENV{ANDROID_NDK_ROOT}")
        if (candidate AND EXISTS "${candidate}")
            set(_ndk_root "${candidate}")
            break()
        endif()
    endforeach()

    if (NOT _ndk_root)
        message(WARNING "whisper.cpp Android acceleration: Android NDK path not found, falling back to CPU")
        return()
    endif()

    if (NOT _host_tag)
        message(WARNING "whisper.cpp Android acceleration: unsupported host ${CMAKE_HOST_SYSTEM_NAME}, falling back to CPU")
        return()
    endif()

    set(_shader_tools_dir "${_ndk_root}/shader-tools/${_host_tag}")
    set(_glslc_path "${_shader_tools_dir}/glslc${_host_suffix}")
    set(_toolchain_sysroot "${_ndk_root}/toolchains/llvm/prebuilt/${_host_tag}/sysroot")
    set(_host_c_compiler "")
    set(_host_cxx_compiler "")

    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        find_program(_host_c_compiler NAMES cl gcc clang NO_CMAKE_FIND_ROOT_PATH)
        find_program(_host_cxx_compiler NAMES cl g++ clang++ NO_CMAKE_FIND_ROOT_PATH)
        if (NOT _host_c_compiler)
            execute_process(
                COMMAND where.exe gcc
                OUTPUT_VARIABLE _host_c_where
                OUTPUT_STRIP_TRAILING_WHITESPACE
                ERROR_QUIET
            )
            if (_host_c_where)
                string(REPLACE "\r\n" ";" _host_c_where_list "${_host_c_where}")
                list(GET _host_c_where_list 0 _host_c_compiler)
            endif()
        endif()
        if (NOT _host_cxx_compiler)
            execute_process(
                COMMAND where.exe g++
                OUTPUT_VARIABLE _host_cxx_where
                OUTPUT_STRIP_TRAILING_WHITESPACE
                ERROR_QUIET
            )
            if (_host_cxx_where)
                string(REPLACE "\r\n" ";" _host_cxx_where_list "${_host_cxx_where}")
                list(GET _host_cxx_where_list 0 _host_cxx_compiler)
            endif()
        endif()
    else()
        find_program(_host_c_compiler NAMES gcc clang NO_CMAKE_FIND_ROOT_PATH)
        find_program(_host_cxx_compiler NAMES g++ clang++ NO_CMAKE_FIND_ROOT_PATH)
    endif()

    set(_android_triple "")
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(_android_triple "aarch64-linux-android")
    elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
        set(_android_triple "arm-linux-androideabi")
    elseif (ANDROID_ABI STREQUAL "x86")
        set(_android_triple "i686-linux-android")
    elseif (ANDROID_ABI STREQUAL "x86_64")
        set(_android_triple "x86_64-linux-android")
    endif()

    if (NOT _android_triple)
        message(WARNING "whisper.cpp Android acceleration: unsupported ABI ${ANDROID_ABI}, falling back to CPU")
        return()
    endif()

    set(_vulkan_api_level "${CMAKE_SYSTEM_VERSION}")
    if (_vulkan_api_level LESS 28)
        set(_vulkan_api_level 28)
    endif()

    set(_vulkan_include_dir "${_toolchain_sysroot}/usr/include")
    set(_vulkan_library "${_toolchain_sysroot}/usr/lib/${_android_triple}/${_vulkan_api_level}/libvulkan.so")

    if (NOT EXISTS "${_glslc_path}")
        message(WARNING "whisper.cpp Android acceleration: glslc not found at ${_glslc_path}, falling back to CPU")
        return()
    endif()

    if (NOT EXISTS "${_vulkan_library}")
        message(WARNING "whisper.cpp Android acceleration: Vulkan library not found at ${_vulkan_library}, falling back to CPU")
        return()
    endif()

    if (NOT _host_c_compiler OR NOT _host_cxx_compiler)
        message(WARNING "whisper.cpp Android acceleration: host compiler not found, falling back to CPU")
        return()
    endif()

    list(APPEND CMAKE_PROGRAM_PATH "${_shader_tools_dir}")
    set(CMAKE_PROGRAM_PATH "${CMAKE_PROGRAM_PATH}" PARENT_SCOPE)

    file(TO_CMAKE_PATH "${_host_c_compiler}" _host_c_compiler_cmake)
    file(TO_CMAKE_PATH "${_host_cxx_compiler}" _host_cxx_compiler_cmake)
    file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" _host_make_program_cmake)

    set(_host_toolchain_file "${CMAKE_BINARY_DIR}/voice-whisper-vulkan-host-toolchain.cmake")
    file(WRITE "${_host_toolchain_file}" [=[
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_FLAGS -O2)
set(CMAKE_CXX_FLAGS -O2)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
set(CMAKE_C_COMPILER "@HOST_C_COMPILER@" CACHE FILEPATH "Host C compiler" FORCE)
set(CMAKE_CXX_COMPILER "@HOST_CXX_COMPILER@" CACHE FILEPATH "Host CXX compiler" FORCE)
set(CMAKE_MAKE_PROGRAM "@HOST_MAKE_PROGRAM@" CACHE FILEPATH "Host build program" FORCE)
]=])
    file(READ "${_host_toolchain_file}" _host_toolchain_contents)
    string(REPLACE "@HOST_C_COMPILER@" "${_host_c_compiler_cmake}" _host_toolchain_contents "${_host_toolchain_contents}")
    string(REPLACE "@HOST_CXX_COMPILER@" "${_host_cxx_compiler_cmake}" _host_toolchain_contents "${_host_toolchain_contents}")
    string(REPLACE "@HOST_MAKE_PROGRAM@" "${_host_make_program_cmake}" _host_toolchain_contents "${_host_toolchain_contents}")
    file(WRITE "${_host_toolchain_file}" "${_host_toolchain_contents}")

    set(VOICE_WHISPER_VULKAN_INCLUDE_DIR "${_vulkan_include_dir}" CACHE PATH "Path to Vulkan headers for ggml" FORCE)
    set(VOICE_WHISPER_VULKAN_LIBRARY "${_vulkan_library}" CACHE FILEPATH "Path to Android Vulkan stub library for ggml" FORCE)
    set(VOICE_WHISPER_VULKAN_GLSLC "${_glslc_path}" CACHE FILEPATH "Path to Vulkan glslc executable for ggml" FORCE)
    set(VOICE_WHISPER_VULKAN_HOST_TOOLCHAIN "${_host_toolchain_file}" CACHE FILEPATH "Host toolchain for ggml Vulkan shader generation" FORCE)
    message(STATUS "whisper.cpp Android acceleration: Vulkan enabled with glslc=${_glslc_path} lib=${_vulkan_library}")
endfunction()

configure_whisper_acceleration()

function(build_library target_name)
    add_library(
        ${target_name}
        SHARED
        ${SOURCE_FILES}
    )

    target_include_directories(
        ${target_name}
        PRIVATE
        ${WHISPER_LIB_DIR}
        ${WHISPER_LIB_DIR}/src
        ${WHISPER_LIB_DIR}/include
        ${WHISPER_LIB_DIR}/ggml/include
        ${WHISPER_LIB_DIR}/ggml/src
        ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
    )

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        set(GGML_COMPILE_OPTIONS -mfpu=neon-vfpv4)
    endif()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)

        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)
    endif()

    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")
endif ()

build_library("whisper")
