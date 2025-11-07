/*
 * Signal Handler for Xray-core Process
 * Catches SIGABRT, SIGSEGV, SIGBUS and logs to logcat
 */

#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <sys/types.h>
#include <stdlib.h>
#include <cxxabi.h>
#include <unwind.h>
#include <dlfcn.h>

#define LOG_TAG "XraySignalHandler"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Maximum stack trace depth
#define MAX_STACK_DEPTH 32

// Original signal handlers (for chaining)
static struct sigaction g_old_sigabrt;
static struct sigaction g_old_sigsegv;
static struct sigaction g_old_sigbus;

/**
 * Demangle C++ symbol name
 */
static char* demangle(const char* symbol) {
    size_t len;
    int status;
    char* demangled = abi::__cxa_demangle(symbol, nullptr, &len, &status);
    if (status == 0) {
        return demangled;
    }
    return nullptr;
}

// Structure to hold stack trace data
struct stack_trace_data {
    void* addresses[MAX_STACK_DEPTH];
    size_t count;
};

// Callback for _Unwind_Backtrace
static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg) {
    stack_trace_data* data = static_cast<stack_trace_data*>(arg);
    if (data->count >= MAX_STACK_DEPTH) {
        return _URC_END_OF_STACK;
    }
    data->addresses[data->count++] = reinterpret_cast<void*>(_Unwind_GetIP(context));
    return _URC_NO_REASON;
}

/**
 * Print stack trace to logcat using _Unwind_Backtrace
 */
static void print_stack_trace() {
    stack_trace_data data;
    data.count = 0;
    
    _Unwind_Backtrace(unwind_callback, &data);
    
    if (data.count == 0) {
        LOGE("No stack frames found");
        return;
    }
    
    LOGE("Stack trace (%zu frames):", data.count);
    for (size_t i = 0; i < data.count; i++) {
        Dl_info info;
        if (dladdr(data.addresses[i], &info) != 0 && info.dli_sname != nullptr) {
            // Try to demangle C++ symbols
            char* demangled = demangle(info.dli_sname);
            if (demangled) {
                LOGE("  #%zu: %s", i, demangled);
                free(demangled);
            } else {
                LOGE("  #%zu: %s", i, info.dli_sname);
            }
        } else {
            LOGE("  #%zu: <unknown> (%p)", i, data.addresses[i]);
        }
    }
}

/**
 * Signal handler for SIGABRT, SIGSEGV, SIGBUS
 */
static void signal_handler(int sig, siginfo_t* info, void* context) {
    const char* sig_name;
    switch (sig) {
        case SIGABRT:
            sig_name = "SIGABRT";
            break;
        case SIGSEGV:
            sig_name = "SIGSEGV";
            break;
        case SIGBUS:
            sig_name = "SIGBUS";
            break;
        default:
            sig_name = "UNKNOWN";
            break;
    }
    
    LOGE("========================================");
    LOGE("Xray-core Signal Caught: %s (signal %d)", sig_name, sig);
    LOGE("PID: %d, UID: %d", getpid(), getuid());
    
    if (info) {
        LOGE("Signal Info:");
        LOGE("  si_code: %d", info->si_code);
        LOGE("  si_errno: %d", info->si_errno);
        if (info->si_code == SI_USER) {
            LOGE("  sent by user (kill)");
        } else if (info->si_code == SI_QUEUE) {
            LOGE("  sent by sigqueue");
        } else if (sig == SIGSEGV) {
            LOGE("  fault address: %p", info->si_addr);
        }
    }
    
    // Print stack trace
    print_stack_trace();
    LOGE("========================================");
    
    // Call original handler if it exists
    struct sigaction* old_handler = nullptr;
    switch (sig) {
        case SIGABRT:
            old_handler = &g_old_sigabrt;
            break;
        case SIGSEGV:
            old_handler = &g_old_sigsegv;
            break;
        case SIGBUS:
            old_handler = &g_old_sigbus;
            break;
    }
    
    if (old_handler && old_handler->sa_handler != SIG_DFL && old_handler->sa_handler != SIG_IGN) {
        if (old_handler->sa_flags & SA_SIGINFO) {
            old_handler->sa_sigaction(sig, info, context);
        } else {
            old_handler->sa_handler(sig);
        }
    } else {
        // Default action: abort
        _exit(128 + sig);
    }
}

/**
 * Install signal handlers
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_simplexray_an_xray_XraySignalHandler_nativeInstallHandlers(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sigemptyset(&sa.sa_mask);
    sa.sa_sigaction = signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    
    // Install handlers
    if (sigaction(SIGABRT, &sa, &g_old_sigabrt) != 0) {
        LOGE("Failed to install SIGABRT handler: %s", strerror(errno));
        return -1;
    }
    LOGI("SIGABRT handler installed");
    
    if (sigaction(SIGSEGV, &sa, &g_old_sigsegv) != 0) {
        LOGE("Failed to install SIGSEGV handler: %s", strerror(errno));
        return -1;
    }
    LOGI("SIGSEGV handler installed");
    
    if (sigaction(SIGBUS, &sa, &g_old_sigbus) != 0) {
        LOGE("Failed to install SIGBUS handler: %s", strerror(errno));
        return -1;
    }
    LOGI("SIGBUS handler installed");
    
    LOGI("All signal handlers installed successfully");
    return 0;
}

/**
 * Restore original signal handlers
 */
extern "C" JNIEXPORT void JNICALL
Java_com_simplexray_an_xray_XraySignalHandler_nativeRestoreHandlers(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    
    sigaction(SIGABRT, &g_old_sigabrt, nullptr);
    sigaction(SIGSEGV, &g_old_sigsegv, nullptr);
    sigaction(SIGBUS, &g_old_sigbus, nullptr);
    
    LOGI("Signal handlers restored");
}

