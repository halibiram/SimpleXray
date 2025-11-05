LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := crypto_wrapper
LOCAL_SRC_FILES := crypto.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(LOCAL_PATH)/../external/boringssl/include

BORINGSSL_LIB_DIR := $(LOCAL_PATH)/../external/boringssl/lib/$(TARGET_ARCH_ABI)
OPENSSL_LIB_DIR := $(LOCAL_PATH)/../openssl/lib/$(TARGET_ARCH_ABI)

ifeq ($(wildcard $(BORINGSSL_LIB_DIR)/libcrypto.a),)
    ifeq ($(wildcard $(OPENSSL_LIB_DIR)/libcrypto.a),)
        LOCAL_CFLAGS += -DUSE_NONE=1
    else
        LOCAL_CFLAGS += -DUSE_OPENSSL=1
        LOCAL_STATIC_LIBRARIES += libcrypto_static libssl_static
    endif
else
    LOCAL_CFLAGS += -DUSE_BORINGSSL=1
    LOCAL_STATIC_LIBRARIES += libboringssl_crypto libboringssl_ssl
endif

LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti -O3
LOCAL_LDLIBS := -llog -latomic
include $(BUILD_STATIC_LIBRARY)

ifeq ($(wildcard $(BORINGSSL_LIB_DIR)/libcrypto.a),$(BORINGSSL_LIB_DIR)/libcrypto.a)
include $(CLEAR_VARS)
LOCAL_MODULE := libboringssl_crypto
LOCAL_SRC_FILES := ../external/boringssl/lib/$(TARGET_ARCH_ABI)/libcrypto.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/../external/boringssl/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libboringssl_ssl
LOCAL_SRC_FILES := ../external/boringssl/lib/$(TARGET_ARCH_ABI)/libssl.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/../external/boringssl/include
include $(PREBUILT_STATIC_LIBRARY)
endif
