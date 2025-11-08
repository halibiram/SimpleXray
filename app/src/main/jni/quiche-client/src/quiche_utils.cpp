/*
 * QUICHE Utilities Implementation
 */

#include "quiche_utils.h"

#include <android/log.h>
#include <sched.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/mman.h>
#include <netdb.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <cstring>
#include <cstdio>
#include <cstdarg>
#include <fstream>

// Linux-specific headers for UDP GSO/GRO
#ifndef UDP_SEGMENT
#define UDP_SEGMENT 103
#endif

#ifndef UDP_GRO
#define UDP_GRO 104
#endif

namespace quiche_client {

// CPU Utils

int CpuUtils::SetCpuAffinity(uint64_t cpu_mask) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);

    for (int i = 0; i < 64; i++) {
        if (cpu_mask & (1ULL << i)) {
            CPU_SET(i, &cpuset);
        }
    }

    if (sched_setaffinity(0, sizeof(cpuset), &cpuset) != 0) {
        return -1;
    }

    return 0;
}

int CpuUtils::SetRealtimeScheduling(int priority) {
    struct sched_param param;
    param.sched_priority = priority;

    if (sched_setscheduler(0, SCHED_FIFO, &param) != 0) {
        return -1;
    }

    return 0;
}

int CpuUtils::GetNumCpus() {
    return sysconf(_SC_NPROCESSORS_ONLN);
}

uint64_t CpuUtils::GetBigCoresMask() {
    int num_cpus = GetNumCpus();

    // Heuristic: Assume cores 4-7 are big cores on typical ARM SoCs
    // This works for most Snapdragon/Exynos/MediaTek chips
    if (num_cpus == 8) {
        return 0xF0;  // Cores 4-7
    } else if (num_cpus >= 4) {
        // For 4-core devices, use upper half
        return (0xF << (num_cpus / 2));
    }

    // Default: all cores
    return (1ULL << num_cpus) - 1;
}

uint64_t CpuUtils::GetLittleCoresMask() {
    int num_cpus = GetNumCpus();

    // Heuristic: Cores 0-3 are little cores
    if (num_cpus == 8) {
        return 0x0F;  // Cores 0-3
    } else if (num_cpus >= 4) {
        return (0xF);  // Lower 4 cores
    }

    // Default: all cores
    return (1ULL << num_cpus) - 1;
}

uint64_t CpuUtils::GetCpuFrequency(int cpu) {
    char path[256];
    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);

    std::ifstream file(path);
    if (!file.is_open()) {
        return 0;
    }

    uint64_t freq = 0;
    file >> freq;
    return freq;
}

// Time Utils

uint64_t TimeUtils::GetTimestampUs() {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return tv.tv_sec * 1000000ULL + tv.tv_usec;
}

uint64_t TimeUtils::GetTimestampMs() {
    return GetTimestampUs() / 1000;
}

void TimeUtils::SleepUs(uint64_t us) {
    usleep(us);
}

void TimeUtils::SleepMs(uint64_t ms) {
    usleep(ms * 1000);
}

// Network Utils

int NetUtils::EnableUdpGSO(int sockfd) {
    int val = 1;
    if (setsockopt(sockfd, SOL_UDP, UDP_SEGMENT, &val, sizeof(val)) != 0) {
        return -1;
    }
    return 0;
}

int NetUtils::EnableUdpGRO(int sockfd) {
    int val = 1;
    if (setsockopt(sockfd, SOL_UDP, UDP_GRO, &val, sizeof(val)) != 0) {
        return -1;
    }
    return 0;
}

int NetUtils::SetSocketBuffers(int sockfd, size_t sndbuf, size_t rcvbuf) {
    if (setsockopt(sockfd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf)) != 0) {
        return -1;
    }

    if (setsockopt(sockfd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf)) != 0) {
        return -1;
    }

    return 0;
}

int NetUtils::SetNonBlocking(int sockfd) {
    int flags = fcntl(sockfd, F_GETFL, 0);
    if (flags < 0) {
        return -1;
    }

    if (fcntl(sockfd, F_SETFL, flags | O_NONBLOCK) < 0) {
        return -1;
    }

    return 0;
}

int NetUtils::GetMTU(const char* interface) {
    (void)interface;  // Unused parameter
    // TODO: Implement via netlink or ioctl
    return 1500;  // Default MTU
}

std::string NetUtils::ResolveHostname(const std::string& hostname) {
    struct addrinfo hints, *res;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    if (getaddrinfo(hostname.c_str(), nullptr, &hints, &res) != 0) {
        return "";
    }

    char ip[INET_ADDRSTRLEN];
    struct sockaddr_in* addr = (struct sockaddr_in*)res->ai_addr;
    inet_ntop(AF_INET, &addr->sin_addr, ip, sizeof(ip));

    freeaddrinfo(res);
    return std::string(ip);
}

// Logger

static Logger::Level current_log_level = Logger::DEBUG;

void Logger::SetLevel(Level level) {
    current_log_level = level;
}

void Logger::Log(Level level, const char* tag, const char* fmt, ...) {
    if (level < current_log_level) {
        return;
    }

    android_LogPriority prio;
    switch (level) {
        case DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case INFO:  prio = ANDROID_LOG_INFO; break;
        case WARN:  prio = ANDROID_LOG_WARN; break;
        case ERROR: prio = ANDROID_LOG_ERROR; break;
        default:    prio = ANDROID_LOG_INFO; break;
    }

    va_list args;
    va_start(args, fmt);
    __android_log_vprint(prio, tag, fmt, args);
    va_end(args);
}

// Memory Utils

void* MemUtils::AllocateAligned(size_t size, size_t alignment) {
    void* ptr = nullptr;
    if (posix_memalign(&ptr, alignment, size) != 0) {
        return nullptr;
    }
    return ptr;
}

void MemUtils::FreeAligned(void* ptr) {
    free(ptr);
}

size_t MemUtils::GetPageSize() {
    return sysconf(_SC_PAGESIZE);
}

int MemUtils::LockMemory(void* addr, size_t len) {
    return mlock(addr, len);
}

int MemUtils::UnlockMemory(void* addr, size_t len) {
    return munlock(addr, len);
}

} // namespace quiche_client
