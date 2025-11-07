# CMakeLists.txt for QUICHE Native Client
# MAXIMUM PERFORMANCE MODE - Aggressive optimizations
cmake_minimum_required(VERSION 3.22)

project(quiche-client CXX C)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# ============================================================================
# AGGRESSIVE OPTIMIZATION FLAGS - MAXIMUM SPEED MODE
# ============================================================================

# Base optimization flags
set(BASE_FLAGS "-Wall -Wextra")

# MAXIMUM PERFORMANCE FLAGS (Battery/CPU usage not a concern)
set(AGGRESSIVE_FLAGS "
    -Ofast                          # Maximum optimization (trades precision for speed)
    -ffast-math                     # Fast math (non-IEEE 754 compliant)
    -funroll-loops                  # Unroll loops for speed
    -fomit-frame-pointer            # Remove frame pointer overhead
    -flto                           # Link-time optimization
    -fwhole-program-vtables         # Optimize virtual tables
    -fvectorize                     # Auto-vectorization
    -fslp-vectorize                 # SLP vectorization
    -fno-stack-protector            # Remove stack protection (DANGEROUS but fast)
    -fno-exceptions                 # No exception overhead
    -fno-rtti                       # No runtime type info
    -DNDEBUG                        # Disable asserts
    -fvisibility=hidden             # Hide symbols by default
    -fvisibility-inlines-hidden     # Hide inline functions
")

# Architecture-specific flags for ARM64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ARCH_FLAGS "
        -march=armv8-a+crypto+aes+simd  # All ARM extensions
        -mtune=cortex-a76               # Tune for modern ARM cores
        -mcpu=cortex-a76                # Target Cortex-A76
    ")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(ARCH_FLAGS "
        -march=armv7-a
        -mfpu=neon
        -mtune=cortex-a15
    ")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(ARCH_FLAGS "
        -march=x86-64-v3                # Modern x86-64
        -mtune=generic
    ")
endif()

# Combine all flags
set(CMAKE_CXX_FLAGS_RELEASE "${BASE_FLAGS} ${AGGRESSIVE_FLAGS} ${ARCH_FLAGS}")
set(CMAKE_C_FLAGS_RELEASE "${BASE_FLAGS} ${AGGRESSIVE_FLAGS} ${ARCH_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${CMAKE_CXX_FLAGS_RELEASE}")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${CMAKE_C_FLAGS_RELEASE}")

message(STATUS "🚀 MAXIMUM PERFORMANCE MODE ENABLED")
message(STATUS "CXX Flags: ${CMAKE_CXX_FLAGS}")

# ============================================================================
# QUICHE Configuration
# ============================================================================

set(QUICHE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/quiche)
set(BORINGSSL_ROOT ${QUICHE_ROOT}/quiche/deps/boringssl)

# Check if QUICHE exists
if(NOT EXISTS "${QUICHE_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "QUICHE not found at ${QUICHE_ROOT}. Run: git submodule update --init --recursive")
endif()

# BoringSSL definitions for QUICHE
add_definitions(
    -DOPENSSL_NO_ASM=0              # Use assembly optimizations
    -DOPENSSL_ARM_NEON              # ARM NEON SIMD
    -DOPENSSL_AARCH64               # ARM64 optimized paths
    -DBORINGSSL_IMPLEMENTATION
    -DBORINGSSL_HAVE_LIBUNWIND=0
)

# QUICHE optimizations
add_definitions(
    -DQUICHE_ENABLE_QLOG=0          # Disable logging overhead
)

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${QUICHE_ROOT}/quiche/include
    ${BORINGSSL_ROOT}/include
)

# ============================================================================
# BoringSSL Build (for QUICHE)
# ============================================================================

# Note: QUICHE comes with its own BoringSSL
# We'll build it as part of QUICHE's build system

if(EXISTS "${BORINGSSL_ROOT}/CMakeLists.txt")
    message(STATUS "Building BoringSSL for QUICHE")

    # Build BoringSSL with optimizations
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)
    add_subdirectory(${BORINGSSL_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/boringssl EXCLUDE_FROM_ALL)

    message(STATUS "✅ BoringSSL configured for QUICHE")
else()
    message(WARNING "BoringSSL not found for QUICHE at ${BORINGSSL_ROOT}")
    message(WARNING "Run: git submodule update --init --recursive")
endif()

# ============================================================================
# QUICHE Library Build
# ============================================================================

# QUICHE is a Rust library, we need to use Cargo for building
# For now, we'll create a placeholder and assume QUICHE is built separately
# Android build will use cargo-ndk

# Note: QUICHE build requires Rust toolchain
# Build command: cargo build --release --target aarch64-linux-android

# Placeholder for QUICHE library path (will be built separately)
set(QUICHE_LIB_DIR ${QUICHE_ROOT}/target/${ANDROID_ABI}/release)

# We'll link against the pre-built QUICHE library
# TODO: Integrate Cargo build into CMake

# ============================================================================
# Source Files
# ============================================================================

set(QUICHE_CLIENT_SOURCES
    src/quiche_client.cpp
    src/quiche_tun_forwarder.cpp
    src/quiche_packet_batch.cpp
    src/quiche_crypto.cpp
    src/quiche_jni.cpp
)

# ============================================================================
# Shared Library
# ============================================================================

add_library(quiche-client SHARED ${QUICHE_CLIENT_SOURCES})

# Link libraries
target_link_libraries(quiche-client
    # QUICHE (Rust library - will be linked when built)
    # ${QUICHE_LIB_DIR}/libquiche.a

    # BoringSSL
    crypto
    ssl

    # Android libraries
    log
    android
    atomic
)

# Set properties
set_target_properties(quiche-client PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
    LINK_FLAGS "-flto -fuse-ld=lld"  # LTO + modern linker
)

# Strip symbols in release (smaller binary)
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(quiche-client PROPERTIES
        LINK_FLAGS "${LINK_FLAGS} -s"  # Strip symbols
    )
endif()

message(STATUS "✅ QUICHE client library configured")
message(STATUS "⚠️  Remember to build QUICHE with: cargo ndk build --release")
