# Android.mk for QUICHE Native Client
# Maximum Performance Build

LOCAL_PATH := $(call my-dir)

# Initialize BoringSSL availability
BORINGSSL_AVAILABLE := false

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
    # Try multiple BoringSSL paths
    # 1. QUICHE deps path
    BORINGSSL_PATH := $(LOCAL_PATH)/third_party/quiche/quiche/deps/boringssl
    BORINGSSL_INCLUDE := $(BORINGSSL_PATH)/include
    BORINGSSL_CRYPTO_LIB := $(BORINGSSL_PATH)/build/crypto/libcrypto.a
    BORINGSSL_SSL_LIB := $(BORINGSSL_PATH)/build/ssl/libssl.a

    # Check if BoringSSL headers exist
    ifneq ($(wildcard $(BORINGSSL_INCLUDE)/openssl/evp.h),)
        BORINGSSL_AVAILABLE := true
    else
        # 2. Try alternative path (third_party/boringssl)
        BORINGSSL_INCLUDE := $(LOCAL_PATH)/third_party/boringssl/include
        ifneq ($(wildcard $(BORINGSSL_INCLUDE)/openssl/evp.h),)
            BORINGSSL_AVAILABLE := true
        else
            # 3. Try perf-net/third_party/boringssl path (submodule location)
            BORINGSSL_PATH := $(LOCAL_PATH)/../perf-net/third_party/boringssl
            BORINGSSL_INCLUDE := $(BORINGSSL_PATH)/include
            # BoringSSL libraries are in external/boringssl/build/$(TARGET_ARCH_ABI)/
            BORINGSSL_CRYPTO_LIB := $(LOCAL_PATH)/../../../../../../external/boringssl/build/$(TARGET_ARCH_ABI)/libcrypto.a
            BORINGSSL_SSL_LIB := $(LOCAL_PATH)/../../../../../../external/boringssl/build/$(TARGET_ARCH_ABI)/libssl.a
            ifneq ($(wildcard $(BORINGSSL_INCLUDE)/openssl/evp.h),)
                BORINGSSL_AVAILABLE := true
            else
                # 4. Try external/boringssl path (relative to quiche-client)
                BORINGSSL_PATH := $(LOCAL_PATH)/../../../../../../external/boringssl
                BORINGSSL_INCLUDE := $(BORINGSSL_PATH)/include
                BORINGSSL_CRYPTO_LIB := $(BORINGSSL_PATH)/build/$(TARGET_ARCH_ABI)/libcrypto.a
                BORINGSSL_SSL_LIB := $(BORINGSSL_PATH)/build/$(TARGET_ARCH_ABI)/libssl.a
                ifneq ($(wildcard $(BORINGSSL_INCLUDE)/openssl/evp.h),)
                    BORINGSSL_AVAILABLE := true
                else
                    $(warning BoringSSL headers not found, skipping BoringSSL libraries)
                    $(warning   Checked paths:)
                    $(warning     - $(LOCAL_PATH)/third_party/quiche/quiche/deps/boringssl/include)
                    $(warning     - $(LOCAL_PATH)/third_party/boringssl/include)
                    $(warning     - $(LOCAL_PATH)/../../perf-net/third_party/boringssl/include)
                    $(warning     - $(BORINGSSL_INCLUDE))
                    $(warning   QUICHE build will be skipped. This is OK for debug builds.)
                    BORINGSSL_AVAILABLE := false
                endif
            endif
        endif
    endif

    # Only add BoringSSL libraries if headers are available
    ifeq ($(BORINGSSL_AVAILABLE),true)
        # BoringSSL crypto library
        ifneq ($(wildcard $(BORINGSSL_CRYPTO_LIB)),)
            include $(CLEAR_VARS)
            LOCAL_MODULE := boringssl-crypto
            LOCAL_SRC_FILES := $(BORINGSSL_CRYPTO_LIB)
            LOCAL_EXPORT_C_INCLUDES := $(BORINGSSL_INCLUDE)
            include $(PREBUILT_STATIC_LIBRARY)
        endif

        # BoringSSL SSL library
        ifneq ($(wildcard $(BORINGSSL_SSL_LIB)),)
            include $(CLEAR_VARS)
            LOCAL_MODULE := boringssl-ssl
            LOCAL_SRC_FILES := $(BORINGSSL_SSL_LIB)
            LOCAL_EXPORT_C_INCLUDES := $(BORINGSSL_INCLUDE)
            include $(PREBUILT_STATIC_LIBRARY)
        endif
    endif
endif

# ============================================================================
# QUICHE Client Shared Library
# ============================================================================

# Only build quiche-client if QUICHE library and BoringSSL are available
ifeq ($(QUICHE_AVAILABLE),true)
ifeq ($(BORINGSSL_AVAILABLE),true)

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
    # OPENSSL_AARCH64 is defined by BoringSSL headers, don't redefine
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
    $(LOCAL_PATH)/third_party/quiche/quiche/include

# Add BoringSSL include (we know it's available because we checked above)
LOCAL_C_INCLUDES += $(BORINGSSL_INCLUDE)

# Static libraries
LOCAL_STATIC_LIBRARIES := quiche-prebuilt

# Add BoringSSL libraries (we know they're available because we checked above)
ifneq ($(wildcard $(BORINGSSL_SSL_LIB)),)
    LOCAL_STATIC_LIBRARIES += boringssl-ssl
endif
ifneq ($(wildcard $(BORINGSSL_CRYPTO_LIB)),)
    LOCAL_STATIC_LIBRARIES += boringssl-crypto
endif

# System libraries
LOCAL_LDLIBS := -llog -landroid -latomic

include $(BUILD_SHARED_LIBRARY)

endif # BORINGSSL_AVAILABLE
endif # QUICHE_AVAILABLE
