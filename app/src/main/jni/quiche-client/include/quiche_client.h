/*
 * QUICHE Native Client - Maximum Performance Mode
 *
 * High-performance QUIC client using Cloudflare QUICHE + BoringSSL
 * Optimized for maximum throughput and minimum latency on Android
 */

#ifndef QUICHE_CLIENT_H
#define QUICHE_CLIENT_H

#include <stdint.h>
#include <stddef.h>
#include <memory>
#include <string>
#include <functional>
#include <atomic>

#include <quiche.h>

namespace quiche_client {

// Forward declarations
class QuicheCrypto;
class QuicheTunForwarder;

/**
 * Congestion control algorithms
 */
enum class CongestionControl {
    RENO,           // Traditional TCP Reno
    CUBIC,          // CUBIC (default)
    BBR,            // BBR v1
    BBR2,           // BBR v2 (recommended for mobile)
};

/**
 * CPU affinity modes
 */
enum class CpuAffinity {
    NONE,           // No CPU pinning
    BIG_CORES,      // Pin to big cores (4-7)
    LITTLE_CORES,   // Pin to little cores (0-3)
    CUSTOM,         // Custom CPU mask
};

/**
 * QUIC connection configuration
 */
struct QuicConfig {
    // Server address
    std::string server_host;
    uint16_t server_port;

    // QUIC parameters
    uint64_t initial_max_data = 100 * 1024 * 1024;          // 100MB
    uint64_t initial_max_stream_data = 50 * 1024 * 1024;    // 50MB per stream
    uint64_t initial_max_streams_bidi = 1000;
    uint64_t initial_max_streams_uni = 1000;
    uint64_t max_idle_timeout_ms = 300000;                   // 5 minutes
    uint16_t max_udp_payload_size = 1350;                    // MTU - overhead

    // Congestion control
    CongestionControl cc_algorithm = CongestionControl::BBR2;

    // Performance options
    bool enable_early_data = true;           // 0-RTT
    bool enable_pacing = false;              // Disable pacing for max speed
    bool enable_dgram = true;                // Enable datagram support
    bool enable_hystart = true;              // HyStart++

    // CPU affinity
    CpuAffinity cpu_affinity = CpuAffinity::BIG_CORES;
    uint64_t cpu_mask = 0xF0;                // Cores 4-7 (big cores)

    // Zero-copy
    bool enable_zero_copy = true;

    // Realtime scheduling
    bool enable_realtime_sched = false;      // SCHED_FIFO (dangerous)
    int realtime_priority = 50;
};

/**
 * QUIC connection metrics
 */
struct QuicMetrics {
    // Throughput
    uint64_t bytes_sent = 0;
    uint64_t bytes_received = 0;
    double throughput_mbps = 0.0;

    // Latency
    uint64_t rtt_us = 0;                     // RTT in microseconds
    uint64_t min_rtt_us = 0;

    // Loss
    uint64_t packets_sent = 0;
    uint64_t packets_received = 0;
    uint64_t packets_lost = 0;
    double packet_loss_rate = 0.0;

    // Congestion
    uint64_t cwnd = 0;                       // Congestion window
    uint64_t bytes_in_flight = 0;

    // Connection state
    bool is_established = false;
    bool is_in_early_data = false;
    uint64_t handshake_duration_us = 0;
};

/**
 * Main QUIC client class
 */
class QuicheClient {
public:
    /**
     * Create QUIC client instance
     */
    static std::unique_ptr<QuicheClient> Create(const QuicConfig& config);

    /**
     * Destructor
     */
    ~QuicheClient();

    /**
     * Connect to QUIC server
     * Returns: 0 on success, < 0 on error
     */
    int Connect();

    /**
     * Disconnect from server
     */
    void Disconnect();

    /**
     * Check if connected
     */
    bool IsConnected() const;

    /**
     * Send data over QUIC
     * Returns: bytes sent, or < 0 on error
     */
    ssize_t Send(const uint8_t* data, size_t len);

    /**
     * Receive data from QUIC
     * Returns: bytes received, or < 0 on error
     */
    ssize_t Receive(uint8_t* buffer, size_t len);

    /**
     * Get current metrics
     */
    QuicMetrics GetMetrics() const;

    /**
     * Set packet receive callback
     */
    void SetPacketCallback(std::function<void(const uint8_t*, size_t)> callback);

    /**
     * Get underlying UDP socket FD
     */
    int GetSocketFd() const { return socket_fd_; }

    /**
     * Get QUIC connection pointer
     */
    quiche_conn* GetConnection() { return conn_; }

private:
    QuicheClient(const QuicConfig& config);

    // Initialize connection
    int Initialize();

    // Create UDP socket
    int CreateSocket();

    // Configure CPU affinity
    int ConfigureCpuAffinity();

    // Configure realtime scheduling
    int ConfigureRealtimeScheduling();

    // Process QUIC events
    void ProcessEvents();

    // Configuration
    QuicConfig config_;

    // QUIC connection
    quiche_conn* conn_ = nullptr;
    quiche_config* quiche_config_ = nullptr;

    // Socket
    int socket_fd_ = -1;

    // State
    std::atomic<bool> connected_{false};
    std::atomic<bool> running_{false};

    // Metrics
    mutable QuicMetrics metrics_;

    // Callback
    std::function<void(const uint8_t*, size_t)> packet_callback_;

    // Crypto handler
    std::unique_ptr<QuicheCrypto> crypto_;

    // Connection ID
    uint8_t scid_[quiche::MAX_CONN_ID_LEN];
    size_t scid_len_ = 0;
};

} // namespace quiche_client

#endif // QUICHE_CLIENT_H
