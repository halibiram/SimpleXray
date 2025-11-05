# CMakeLists.txt for perf-net module with BoringSSL integration
cmake_minimum_required(VERSION 3.22)

project(perf-net)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build flags for BoringSSL
add_definitions(
    -DOPENSSL_SMALL=1
    -DOPENSSL_NO_DEPRECATED=1
    -DOPENSSL_NO_ASM=0
    -DBORINGSSL_HAVE_LIBUNWIND=0
    -DBORINGSSL_IMPLEMENTATION
)

# Architecture-specific flags
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd+crypto")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a+simd+crypto")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv7-a -mfpu=neon")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv7-a -mfpu=neon")
endif()

# Compiler flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -O3 -ffast-math -funroll-loops -fomit-frame-pointer")

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/boringssl/include
)

# Add BoringSSL as subdirectory
# Note: BoringSSL should be built separately or included as prebuilt
# For now, we'll link against system-provided BoringSSL or use find_package
set(BORINGSSL_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/boringssl)

# Check if BoringSSL is available as a submodule
if(EXISTS "${BORINGSSL_ROOT}/CMakeLists.txt")
    # BoringSSL is available, add it as subdirectory
    # Note: BoringSSL needs to be configured for Android
    set(BORINGSSL_BUILD_DIR ${CMAKE_CURRENT_BINARY_DIR}/boringssl)
    add_subdirectory(${BORINGSSL_ROOT} ${BORINGSSL_BUILD_DIR})
    
    # BoringSSL targets: crypto and ssl (static libraries)
    message(STATUS "BoringSSL found, linking against submodule")
else()
    # Fallback: try to find BoringSSL or use system libraries
    # For Android, we expect BoringSSL to be built separately
    message(WARNING "BoringSSL submodule not found, expecting prebuilt libraries")
    
    # Try to find crypto and ssl libraries
    find_library(CRYPTO_LIB crypto)
    find_library(SSL_LIB ssl)
    
    if(CRYPTO_LIB AND SSL_LIB)
        add_library(crypto STATIC IMPORTED)
        set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${CRYPTO_LIB})
        
        add_library(ssl STATIC IMPORTED)
        set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${SSL_LIB})
        
        message(STATUS "Found BoringSSL libraries: ${CRYPTO_LIB}, ${SSL_LIB}")
    else()
        message(FATAL_ERROR "BoringSSL libraries not found. Please build BoringSSL first.")
    endif()
endif()

# Source files
set(PERF_NET_SOURCES
    src/perf_jni.cpp
    src/perf_cpu_affinity.cpp
    src/perf_epoll_loop.cpp
    src/perf_zero_copy.cpp
    src/perf_connection_pool.cpp
    src/perf_crypto_neon.cpp
    src/perf_tls_session.cpp
    src/perf_tls_handshake.cpp
    src/perf_quic_handshake.cpp
    src/perf_tls_evasion.cpp
    src/perf_cert_verifier.cpp
    src/perf_tls_keylog.cpp
    src/perf_mtu_tuning.cpp
    src/perf_ring_buffer.cpp
    src/perf_jit_warmup.cpp
    src/perf_kernel_pacing.cpp
    src/perf_readahead.cpp
    src/perf_qos.cpp
    src/perf_mmap_batch.cpp
    src/perf_tcp_fastopen.cpp
)

# Create shared library
add_library(perf-net SHARED ${PERF_NET_SOURCES})

# Link against BoringSSL
target_link_libraries(perf-net
    crypto
    ssl
    log
    atomic
)

# Set properties
set_target_properties(perf-net PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)

# Static linking (no shared libraries)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)

