/*
 * MTU Tuning & Jumbo Frame Support
 * Optimizes MTU for LTE (1380-1436) and 5G (1420-1460)
 */

#include <jni.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <fcntl.h>
#include <unistd.h>
#include <android/log.h>
#include <errno.h>
#include <cstring>

#define LOG_TAG "PerfMTU"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Set optimal MTU based on network type
 * NOTE: This function is deprecated. MTU should be set via VpnService.Builder.setMtu()
 * before establishing the VPN connection. This function returns the optimal MTU value
 * but does not attempt to change it (SELinux blocks ioctl with interface names).
 * 
 * @param fd TUN interface file descriptor (unused, kept for API compatibility)
 * @param networkType 0=LTE, 1=5G, 2=WiFi
 * @return Optimal MTU value for the network type (MTU is not actually changed)
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeSetOptimalMTU(
    JNIEnv *env, jclass clazz, jint fd, jint networkType) {
    
    int optimal_mtu;
    
    switch (networkType) {
        case 0: // LTE
            optimal_mtu = 1436; // 1500 - 40 (IPv6 + options) - 24 (overhead)
            break;
        case 1: // 5G
            optimal_mtu = 1460; // Larger for 5G
            break;
        case 2: // WiFi
            optimal_mtu = 1500; // Standard Ethernet
            break;
        default:
            optimal_mtu = 1436;
    }
    
    // SELinux blocks ioctl with interface names on VpnService FDs
    // MTU should be set via VpnService.Builder.setMtu() before establish()
    // This function now only returns the recommended MTU value
    LOGD("Recommended MTU for network type %d: %d (not setting - use VpnService.Builder.setMtu())", 
         networkType, optimal_mtu);
    return optimal_mtu;
}

/**
 * Get current MTU
 * NOTE: SELinux blocks ioctl with interface names on VpnService FDs.
 * This function attempts to get MTU using TUNGETIFF, but may fail.
 * Consider using VpnService.Builder.getMtu() or storing the MTU value
 * when the VPN is established.
 * 
 * @param fd TUN interface file descriptor
 * @return Current MTU or -1 if unable to retrieve
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeGetMTU(
    JNIEnv *env, jclass clazz, jint fd) {
    
    // Try to get interface name using TUNGETIFF (may fail due to SELinux)
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    
    // Attempt to get interface info from TUN device
    // This may fail on Android due to SELinux restrictions
    int result = ioctl(fd, TUNGETIFF, &ifr);
    if (result != 0) {
        LOGE("Failed to get interface info via TUNGETIFF: %d (SELinux may be blocking)", errno);
        return -1;
    }
    
    // Now try to get MTU using the interface name we got
    // Note: This may still fail due to SELinux restrictions
    result = ioctl(fd, SIOCGIFMTU, &ifr);
    if (result == 0) {
        LOGD("Retrieved MTU: %d for interface: %s", ifr.ifr_mtu, ifr.ifr_name);
        return ifr.ifr_mtu;
    } else {
        LOGE("Failed to get MTU: %d (SELinux may be blocking ioctl with interface name)", errno);
        return -1;
    }
}

/**
 * Set socket buffer sizes for high throughput
 */
JNIEXPORT jint JNICALL
Java_com_simplexray_an_performance_PerformanceManager_nativeSetSocketBuffers(
    JNIEnv *env, jclass clazz, jint fd, jint sendBuffer, jint recvBuffer) {
    
    int result = 0;
    
    // Set send buffer
    if (setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sendBuffer, sizeof(sendBuffer)) < 0) {
        LOGE("Failed to set send buffer: %d", errno);
        result = -1;
    }
    
    // Set receive buffer
    if (setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &recvBuffer, sizeof(recvBuffer)) < 0) {
        LOGE("Failed to set recv buffer: %d", errno);
        result = -1;
    }
    
    if (result == 0) {
        LOGD("Socket buffers set: send=%d, recv=%d", sendBuffer, recvBuffer);
    }
    
    return result;
}

} // extern "C"

