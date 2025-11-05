# CMake build file for perf-net native module
# BoringSSL integration for SimpleXray Android

cmake_minimum_required(VERSION 3.18.1)
project(perf-net)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# BoringSSL configuration
set(BORINGSSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/boringssl)

# Check if BoringSSL exists
if(NOT EXISTS ${BORINGSSL_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "BoringSSL not found at ${BORINGSSL_DIR}. Please run: ./app/src/main/jni/perf-net/init_boringssl.sh")
endif()

set(BORINGSSL_BUILD_DIR ${CMAKE_CURRENT_BINARY_DIR}/boringssl)

# BoringSSL build flags
set(BORINGSSL_CMAKE_ARGS
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DBUILD_SHARED_LIBS=OFF
    -DOPENSSL_SMALL=1
    -DOPENSSL_NO_DEPRECATED=1
)

# Android-specific flags
if(ANDROID)
    set(BORINGSSL_CMAKE_ARGS ${BORINGSSL_CMAKE_ARGS}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=android-${ANDROID_PLATFORM_LEVEL}
        -DANDROID_NDK=${ANDROID_NDK}
        -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK}/build/cmake/android.toolchain.cmake
    )
endif()

# Build BoringSSL as external project
include(ExternalProject)
ExternalProject_Add(boringssl
    SOURCE_DIR ${BORINGSSL_DIR}
    BINARY_DIR ${BORINGSSL_BUILD_DIR}
    CMAKE_ARGS ${BORINGSSL_CMAKE_ARGS}
    BUILD_COMMAND ${CMAKE_COMMAND} --build . --config Release --parallel 4
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS
        ${BORINGSSL_BUILD_DIR}/ssl/libssl.a
        ${BORINGSSL_BUILD_DIR}/crypto/libcrypto.a
)

# BoringSSL includes
set(BORINGSSL_INCLUDE_DIR ${BORINGSSL_DIR}/include)
set(BORINGSSL_SSL_LIB ${BORINGSSL_BUILD_DIR}/ssl/libssl.a)
set(BORINGSSL_CRYPTO_LIB ${BORINGSSL_BUILD_DIR}/crypto/libcrypto.a)

# Source files
set(PERF_NET_SOURCES
    src/perf_jni.cpp
    src/perf_cpu_affinity.cpp
    src/perf_epoll_loop.cpp
    src/perf_zero_copy.cpp
    src/perf_connection_pool.cpp
    src/perf_crypto_neon.cpp
    src/perf_tls_session.cpp
    src/perf_mtu_tuning.cpp
    src/perf_ring_buffer.cpp
    src/perf_jit_warmup.cpp
    src/perf_kernel_pacing.cpp
    src/perf_readahead.cpp
    src/perf_qos.cpp
    src/perf_mmap_batch.cpp
    src/perf_tcp_fastopen.cpp
    src/hyper/hyper_ring.cpp
    src/hyper/hyper_crypto.cpp
    src/hyper/hyper_burst.cpp
    src/hyper/hyper_cpu.cpp
    src/hyper/hyper_jni.cpp
    src/boringssl_bridge.cpp
    src/crypto_adapter.cpp
)

# Create library
add_library(perf-net SHARED ${PERF_NET_SOURCES})

# Include directories
target_include_directories(perf-net PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/src/hyper
    ${BORINGSSL_INCLUDE_DIR}
)

# Link BoringSSL
add_dependencies(perf-net boringssl)
target_link_libraries(perf-net PRIVATE
    ${BORINGSSL_SSL_LIB}
    ${BORINGSSL_CRYPTO_LIB}
    log
    atomic
)

# Compiler flags
target_compile_definitions(perf-net PRIVATE
    USE_BORINGSSL=1
    PKGNAME=com/simplexray/an
)

# Architecture-specific flags
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(perf-net PRIVATE
        -march=armv8-a+simd+crypto
        -mfpu=neon
    )
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(perf-net PRIVATE
        -march=armv7-a
        -mfpu=neon
    )
endif()

# Optimization flags
target_compile_options(perf-net PRIVATE
    -O3
    -Wall
    -Wextra
    -ffast-math
    -funroll-loops
)

# Enable NEON for ARM
if(ANDROID_ABI MATCHES "arm")
    target_compile_definitions(perf-net PRIVATE HAS_NEON=1)
endif()

