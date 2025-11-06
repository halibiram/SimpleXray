LOCAL_PATH := $(call my-dir)

# PepperShaper native module
include $(CLEAR_VARS)

LOCAL_MODULE := pepper-shaper

# Source files
LOCAL_SRC_FILES := \
    src/pepper_jni.cpp \
    src/pepper_queue.cpp \
    src/pepper_pacing.cpp

# Include directories
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/include

# C++ flags
LOCAL_CPPFLAGS := \
    -std=c++17 \
    -Wall \
    -Wextra \
    -O3 \
    -fno-exceptions \
    -ffast-math

# Architecture-specific flags
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
    LOCAL_CPPFLAGS += -march=armv8-a+simd
    LOCAL_CFLAGS += -march=armv8-a+simd
endif

# System libraries
LOCAL_LDLIBS := \
    -llog \
    -latomic

# Enable NEON
LOCAL_ARM_NEON := true

# C++ standard library
LOCAL_CXX_STL := c++_shared

# Build as shared library
include $(BUILD_SHARED_LIBRARY)

