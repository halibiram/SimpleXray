# Android.mk for QUICHE Native Client
# Maximum Performance Build

LOCAL_PATH := $(call my-dir)

# ============================================================================
# QUICHE Static Library (pre-built from Rust)
# ============================================================================

# Check if QUICHE library exists, skip if not available
QUICHE_LIB_PATH := $(LOCAL_PATH)/libs/$(TARGET_ARCH_ABI)/libquiche.a
ifneq ($(wildcard $(QUICHE_LIB_PATH)),)
    include $(CLEAR_VARS)
    LOCAL_MODULE := quiche-prebuilt
    LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libquiche.a
    LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/third_party/quiche/quiche/include
    include $(PREBUILT_STATIC_LIBRARY)
    QUICHE_AVAILABLE := true
else
    $(warning QUICHE library not found at $(QUICHE_LIB_PATH), skipping quiche-client build)
    QUICHE_AVAILABLE := false
endif

# ============================================================================
# BoringSSL (from QUICHE dependencies)
# ============================================================================

# BoringSSL is only needed if QUICHE is available
ifeq ($(QUICHE_AVAILABLE),true)
    BORINGSSL_PATH := $(LOCAL_PATH)/third_party/quiche/quiche/deps/boringssl

    # BoringSSL crypto library
    include $(CLEAR_VARS)
    LOCAL_MODULE := boringssl-crypto
    LOCAL_SRC_FILES := $(BORINGSSL_PATH)/build/crypto/libcrypto.a
    LOCAL_EXPORT_C_INCLUDES := $(BORINGSSL_PATH)/include
    include $(PREBUILT_STATIC_LIBRARY)

    # BoringSSL SSL library
    include $(CLEAR_VARS)
    LOCAL_MODULE := boringssl-ssl
    LOCAL_SRC_FILES := $(BORINGSSL_PATH)/build/ssl/libssl.a
    LOCAL_EXPORT_C_INCLUDES := $(BORINGSSL_PATH)/include
    include $(PREBUILT_STATIC_LIBRARY)
endif

# ============================================================================
# QUICHE Client Shared Library
# ============================================================================

# Only build quiche-client if QUICHE library is available
ifeq ($(QUICHE_AVAILABLE),true)

include $(CLEAR_VARS)

LOCAL_MODULE := quiche-client

# MAXIMUM PERFORMANCE FLAGS
LOCAL_CFLAGS := -Wall -Wextra -Werror
LOCAL_CFLAGS += -Ofast                          # Maximum optimization
LOCAL_CFLAGS += -ffast-math                     # Fast math
LOCAL_CFLAGS += -funroll-loops                  # Loop unrolling
LOCAL_CFLAGS += -fomit-frame-pointer            # Remove frame pointer
LOCAL_CFLAGS += -fvisibility=hidden             # Hide symbols
LOCAL_CFLAGS += -DNDEBUG                        # Disable asserts
LOCAL_CFLAGS += -flto                           # Link-time optimization

# Architecture-specific flags
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
    LOCAL_CFLAGS += -march=armv8-a+crypto+aes+simd
    LOCAL_CFLAGS += -mtune=cortex-a76
    LOCAL_CFLAGS += -DOPENSSL_ARM_NEON
    LOCAL_CFLAGS += -DOPENSSL_AARCH64
endif

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
    LOCAL_CFLAGS += -march=armv7-a
    LOCAL_CFLAGS += -mfpu=neon
    LOCAL_CFLAGS += -mtune=cortex-a15
    LOCAL_CFLAGS += -DOPENSSL_ARM_NEON
endif

LOCAL_CPPFLAGS := $(LOCAL_CFLAGS) -std=c++17 -fno-exceptions -fno-rtti

# Linker flags
LOCAL_LDFLAGS := -flto -fuse-ld=lld
LOCAL_LDFLAGS += -Wl,--gc-sections              # Remove unused sections
LOCAL_LDFLAGS += -Wl,-O3                        # Linker optimization

# Source files
LOCAL_SRC_FILES := \
    src/quiche_client.cpp \
    src/quiche_tun_forwarder.cpp \
    src/quiche_crypto.cpp \
    src/quiche_utils.cpp \
    src/quiche_jni.cpp

# Include directories
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/include \
    $(LOCAL_PATH)/third_party/quiche/quiche/include \
    $(LOCAL_PATH)/third_party/quiche/quiche/deps/boringssl/include

# Static libraries
LOCAL_STATIC_LIBRARIES := \
    quiche-prebuilt \
    boringssl-ssl \
    boringssl-crypto

# System libraries
LOCAL_LDLIBS := -llog -landroid -latomic

include $(BUILD_SHARED_LIBRARY)

endif # QUICHE_AVAILABLE
