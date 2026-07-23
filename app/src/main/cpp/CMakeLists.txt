cmake_minimum_required(VERSION 3.22.1)

project(androidcyaml LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(GENERATED_DIR "${CMAKE_CURRENT_SOURCE_DIR}/generated")
set(MIHOMO_LIBRARY "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmihomo.so")
set(MIHOMO_HEADER "${GENERATED_DIR}/libmihomo.h")

if(NOT EXISTS "${MIHOMO_LIBRARY}" OR NOT EXISTS "${MIHOMO_HEADER}")
    message(FATAL_ERROR "Run scripts/build_mihomo.sh before configuring CMake")
endif()

add_library(mihomo SHARED IMPORTED GLOBAL)
set_target_properties(mihomo PROPERTIES
        IMPORTED_LOCATION "${MIHOMO_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${GENERATED_DIR}"
)

add_library(androidcyaml SHARED mihomo_jni.cpp)

target_include_directories(androidcyaml PRIVATE "${GENERATED_DIR}")
target_compile_options(androidcyaml PRIVATE -Wall -Wextra -Werror)
target_link_options(androidcyaml PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)
target_link_libraries(androidcyaml PRIVATE
        mihomo
        android
        log
        dl
        m
)
