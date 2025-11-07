/*
 * TUN to QUIC Forwarder - Zero-Copy Packet Processing
 *
 * High-performance TUN interface forwarder using:
 * - Zero-copy I/O with ring buffers
 * - Batch packet processing
 * - Hardware crypto acceleration
 * - CPU affinity (big cores)
 */

#ifndef QUICHE_TUN_FORWARDER_H
#define QUICHE_TUN_FORWARDER_H

#include <stdint.h>
#include <stddef.h>
#include <memory>
#include <atomic>
#include <thread>
#include <vector>

#include "quiche_client.h"

namespace quiche_client {

/**
 * Packet buffer for zero-copy processing
 */
struct PacketBuffer {
    uint8_t* data;              // Packet data (mmap'd)
    size_t len;                 // Packet length
    size_t capacity;            // Buffer capacity
    uint64_t timestamp_us;      // Timestamp
    bool in_use;                // Buffer in use
};

/**
 * Ring buffer for zero-copy packet queue
 */
template<size_t Size>
class RingBuffer {
public:
    RingBuffer();
    ~RingBuffer();

    // Push packet to ring
    bool Push(PacketBuffer* pkt);

    // Pop packet from ring
    PacketBuffer* Pop();

    // Get available count
    size_t Available() const;

    // Check if empty
    bool IsEmpty() const;

    // Check if full
    bool IsFull() const;

private:
    PacketBuffer* buffers_[Size];
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

/**
 * TUN to QUIC forwarder statistics
 */
struct ForwarderStats {
    uint64_t packets_received = 0;
    uint64_t packets_sent = 0;
    uint64_t packets_dropped = 0;
    uint64_t bytes_received = 0;
    uint64_t bytes_sent = 0;
    double rx_rate_mbps = 0.0;
    double tx_rate_mbps = 0.0;
    uint64_t avg_latency_us = 0;
};

/**
 * TUN to QUIC forwarder configuration
 */
struct ForwarderConfig {
    int tun_fd;                         // TUN file descriptor
    size_t packet_pool_size = 8192;     // Pre-allocated packets
    size_t batch_size = 64;             // Packets per batch
    bool use_gso = true;                // UDP GSO
    bool use_gro = true;                // UDP GRO
    bool use_zero_copy = true;          // Zero-copy I/O
    CpuAffinity cpu_affinity = CpuAffinity::BIG_CORES;
    bool enable_realtime = false;
};

/**
 * Main TUN forwarder class
 */
class QuicheTunForwarder {
public:
    /**
     * Create TUN forwarder
     */
    static std::unique_ptr<QuicheTunForwarder> Create(
        const ForwarderConfig& config,
        QuicheClient* quic_client
    );

    /**
     * Destructor
     */
    ~QuicheTunForwarder();

    /**
     * Start forwarding
     */
    int Start();

    /**
     * Stop forwarding
     */
    void Stop();

    /**
     * Check if running
     */
    bool IsRunning() const { return running_.load(); }

    /**
     * Get statistics
     */
    ForwarderStats GetStats() const;

private:
    QuicheTunForwarder(const ForwarderConfig& config, QuicheClient* quic_client);

    // Initialize packet pool
    int InitializePacketPool();

    // Configure UDP socket for GSO/GRO
    int ConfigureUdpSocket(int sockfd);

    // Main forwarding loop (TUN → QUIC)
    void ForwardingLoop();

    // Receive packets from TUN (batch mode)
    int ReceiveTunPacketsBatch(struct mmsghdr* msgs, int max_packets);

    // Process packet batch
    void ProcessPacketBatch(struct mmsghdr* msgs, int count);

    // Send packet via QUIC
    int SendViaQuic(const uint8_t* data, size_t len);

    // Allocate packet buffer from pool
    PacketBuffer* AllocatePacket();

    // Free packet buffer to pool
    void FreePacket(PacketBuffer* pkt);

    // Configure CPU affinity
    int ConfigureCpuAffinity();

    // Update statistics
    void UpdateStats();

    // Configuration
    ForwarderConfig config_;

    // QUIC client
    QuicheClient* quic_client_;

    // State
    std::atomic<bool> running_{false};
    std::unique_ptr<std::thread> forward_thread_;

    // Packet pool (pre-allocated)
    std::vector<PacketBuffer> packet_pool_;
    std::atomic<size_t> pool_index_{0};

    // Ring buffers
    std::unique_ptr<RingBuffer<4096>> rx_ring_;
    std::unique_ptr<RingBuffer<4096>> tx_ring_;

    // Statistics
    mutable ForwarderStats stats_;
    uint64_t last_stats_update_us_ = 0;
};

} // namespace quiche_client

#endif // QUICHE_TUN_FORWARDER_H
