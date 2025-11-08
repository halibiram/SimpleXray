# Android.mk for perf-net (Rust implementation)
# This module is built using Cargo, then linked as prebuilt library

LOCAL_PATH := $(call my-dir)

# Try to use prebuilt library from jniLibs
PREBUILT_LIB := $(LOCAL_PATH)/../../jniLibs/$(TARGET_ARCH_ABI)/libperf-net.so

ifneq ($(wildcard $(PREBUILT_LIB)),)
    # Use prebuilt library from jniLibs
    include $(CLEAR_VARS)
    LOCAL_MODULE := perf-net
    LOCAL_SRC_FILES := ../../jniLibs/$(TARGET_ARCH_ABI)/libperf-net.so
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)
    include $(PREBUILT_SHARED_LIBRARY)
endif
# If prebuilt library not found, skip this module (it will be built via Rust/Cargo separately)
