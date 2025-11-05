LOCAL_PATH := $(call my-dir)

# Save the current LOCAL_PATH before including crypto_wrapper
PERF_NET_LOCAL_PATH := $(LOCAL_PATH)

# Include crypto wrapper module
include $(PERF_NET_LOCAL_PATH)/../../../../../crypto_wrapper/Android.mk

# Restore LOCAL_PATH for this module (CLEAR_VARS doesn't clear LOCAL_PATH)
LOCAL_PATH := $(PERF_NET_LOCAL_PATH)

# Performance network module
include $(CLEAR_VARS)

LOCAL_MODULE := perf-net

# Source files
LOCAL_SRC_FILES := \
    src/perf_jni.cpp \
    src/perf_cpu_affinity.cpp \
    src/perf_epoll_loop.cpp \
    src/perf_zero_copy.cpp \
    src/perf_connection_pool.cpp \
    src/perf_crypto_neon.cpp \
    src/perf_crypto_boringssl.cpp \
    src/perf_tls_session.cpp \
    src/perf_mtu_tuning.cpp \
    src/perf_ring_buffer.cpp \
    src/perf_jit_warmup.cpp \
    src/perf_kernel_pacing.cpp \
    src/perf_readahead.cpp \
    src/perf_qos.cpp \
    src/perf_mmap_batch.cpp \
    src/perf_tcp_fastopen.cpp \
    src/hyper/hyper_ring.cpp \
    src/hyper/hyper_crypto.cpp \
    src/hyper/hyper_burst.cpp \
    src/hyper/hyper_cpu.cpp \
    src/hyper/hyper_jni.cpp

# Include directories
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/include \
    $(LOCAL_PATH)/src/hyper \
    $(LOCAL_PATH)/../../../../../crypto_wrapper

# BoringSSL is used via crypto_wrapper
# Always use BoringSSL (no OpenSSL fallback)
LOCAL_CPPFLAGS += -DUSE_BORINGSSL=1
LOCAL_CFLAGS += -DUSE_BORINGSSL=1
$(info ✅ Using BoringSSL via crypto_wrapper)

# C++ flags
LOCAL_CPPFLAGS += \
    -std=c++17 \
    -Wall \
    -Wextra \
    -O3 \
    -ffast-math \
    -funroll-loops \
    -fno-omit-frame-pointer \
    -frtti \
    -fexceptions

# Architecture-specific flags with crypto extensions
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
    LOCAL_CPPFLAGS += -march=armv8-a+crypto+simd
    LOCAL_CFLAGS += -march=armv8-a+crypto+simd -DENABLE_ARM_CRYPTO_EXT=1
else ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_CPPFLAGS += -march=armv7-a -mfpu=neon
    LOCAL_CFLAGS += -march=armv7-a -mfpu=neon
else ifeq ($(TARGET_ARCH_ABI),x86_64)
    LOCAL_CPPFLAGS += -march=x86-64 -msse4.2 -maes -mssse3
    LOCAL_CFLAGS += -march=x86-64 -msse4.2 -maes -mssse3
else ifeq ($(TARGET_ARCH_ABI),x86)
    LOCAL_CPPFLAGS += -march=i686 -msse3 -maes
    LOCAL_CFLAGS += -march=i686 -msse3 -maes
endif

# System libraries
LOCAL_LDLIBS := -llog

# Link crypto_wrapper (uses BoringSSL)
LOCAL_STATIC_LIBRARIES += crypto_wrapper

# Enable NEON
LOCAL_ARM_NEON := true

# C++ standard library
LOCAL_CXX_STL := c++_shared

# Build as shared library
include $(BUILD_SHARED_LIBRARY)

