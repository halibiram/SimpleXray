LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := xray-signal-handler
LOCAL_SRC_FILES := src/xray_signal_handler.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include
LOCAL_LDLIBS := -llog -ldl -lunwind

# Enable C++ exceptions and RTTI
LOCAL_CPP_FEATURES := exceptions rtti

# C++ standard
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra

include $(BUILD_SHARED_LIBRARY)

