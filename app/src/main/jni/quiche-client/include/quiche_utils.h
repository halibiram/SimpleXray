/*
 * QUICHE Client Utilities
 *
 * Helper functions for:
 * - CPU affinity and scheduling
 * - Time measurement
 * - Logging
 * - Network utilities
 */

#ifndef QUICHE_UTILS_H
#define QUICHE_UTILS_H

#include <stdint.h>
#include <stddef.h>
#include <string>

namespace quiche_client {

/**
 * CPU utilities
 */
class CpuUtils {
public:
    /**
     * Set CPU affinity for current thread
     * cpu_mask: Bitmask of CPUs (e.g., 0xF0 for cores 4-7)
     */
    static int SetCpuAffinity(uint64_t cpu_mask);

    /**
     * Set realtime scheduling (SCHED_FIFO)
     * priority: 1-99 (higher = more priority)
     */
    static int SetRealtimeScheduling(int priority);

    /**
     * Get number of CPUs
     */
    static int GetNumCpus();

    /**
     * Get big cores mask (Android-specific heuristic)
     */
    static uint64_t GetBigCoresMask();

    /**
     * Get little cores mask
     */
    static uint64_t GetLittleCoresMask();

    /**
     * Get CPU frequency for core
     */
    static uint64_t GetCpuFrequency(int cpu);
};

/**
 * Time utilities
 */
class TimeUtils {
public:
    /**
     * Get current time in microseconds
     */
    static uint64_t GetTimestampUs();

    /**
     * Get current time in milliseconds
     */
    static uint64_t GetTimestampMs();

    /**
     * Sleep for microseconds
     */
    static void SleepUs(uint64_t us);

    /**
     * Sleep for milliseconds
     */
    static void SleepMs(uint64_t ms);
};

/**
 * Network utilities
 */
class NetUtils {
public:
    /**
     * Enable UDP GSO (Generic Segmentation Offload)
     */
    static int EnableUdpGSO(int sockfd);

    /**
     * Enable UDP GRO (Generic Receive Offload)
     */
    static int EnableUdpGRO(int sockfd);

    /**
     * Set socket buffer sizes
     */
    static int SetSocketBuffers(int sockfd, size_t sndbuf, size_t rcvbuf);

    /**
     * Set socket to non-blocking mode
     */
    static int SetNonBlocking(int sockfd);

    /**
     * Get MTU for interface
     */
    static int GetMTU(const char* interface);

    /**
     * Resolve hostname to IP address
     */
    static std::string ResolveHostname(const std::string& hostname);
};

/**
 * Logging utilities
 */
class Logger {
public:
    enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR,
    };

    /**
     * Set log level
     */
    static void SetLevel(Level level);

    /**
     * Log message
     */
    static void Log(Level level, const char* tag, const char* fmt, ...);

    /**
     * Convenience macros (defined below)
     */
};

// Logging macros
#define LOGD(tag, ...) Logger::Log(Logger::DEBUG, tag, __VA_ARGS__)
#define LOGI(tag, ...) Logger::Log(Logger::INFO, tag, __VA_ARGS__)
#define LOGW(tag, ...) Logger::Log(Logger::WARN, tag, __VA_ARGS__)
#define LOGE(tag, ...) Logger::Log(Logger::ERROR, tag, __VA_ARGS__)

/**
 * Memory utilities
 */
class MemUtils {
public:
    /**
     * Allocate aligned memory
     */
    static void* AllocateAligned(size_t size, size_t alignment);

    /**
     * Free aligned memory
     */
    static void FreeAligned(void* ptr);

    /**
     * Get page size
     */
    static size_t GetPageSize();

    /**
     * Lock memory (prevent swapping)
     */
    static int LockMemory(void* addr, size_t len);

    /**
     * Unlock memory
     */
    static int UnlockMemory(void* addr, size_t len);
};

} // namespace quiche_client

#endif // QUICHE_UTILS_H
