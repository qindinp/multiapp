cmake_minimum_required(VERSION 3.22)
project(multiapp-native-hook LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Visibility: only export JNI functions
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

# LSPlant include directory
set(LSPLANT_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lsplant)

# Main hook library — outputs libmultiapp-native.so to match System.loadLibrary("multiapp-native")
add_library(multiapp-native SHARED
    native-hook.cpp
)

# Link against Android log + dynamic linker
target_link_libraries(multiapp-native
    log     # __android_log_print
    dl      # dlsym, dlopen
)

# ShadowHook via Prefab (AAR provides headers + .so)
find_package(shadowhook REQUIRED CONFIG)
target_link_libraries(multiapp-native shadowhook::shadowhook)

# LSPlant — headers from lsplant/ directory
# The .so is loaded at runtime via dlopen in the JNI code
target_include_directories(multiapp-native PRIVATE ${LSPLANT_INCLUDE_DIR})

# Compiler flags
target_compile_options(multiapp-native PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -fno-exceptions
    -fno-rtti
    -O2
)

# 16KB page alignment for Android 15+ (CRITICAL)
target_link_options(multiapp-native PRIVATE
    -Wl,-z,max-page-size=16384
)
