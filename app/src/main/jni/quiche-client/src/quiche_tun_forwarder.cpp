/*
 * TUN to QUIC Forwarder - Zero-Copy Implementation
 */

#include "quiche_tun_forwarder.h"
#include "quiche_utils.h"

#include <sys/socket.h>
#include <sys/uio.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <algorithm>
#include <vector>

#define LOG_TAG "TunForwarder"

namespace quiche_client {

// Ring buffer implementation
template<size_t Size>
RingBuffer<Size>::RingBuffer() {
    memset(buffers_, 0, sizeof(buffers_));
}

template<size_t Size>
RingBuffer<Size>::~RingBuffer() {
}

template<size_t Size>
bool RingBuffer<Size>::Push(PacketBuffer* pkt) {
    size_t head = head_.load(std::memory_order_relaxed);
    size_t next_head = (head + 1) % Size;

    if (next_head == tail_.load(std::memory_order_acquire)) {
        return false;  // Full
    }

    buffers_[head] = pkt;
    head_.store(next_head, std::memory_order_release);
    return true;
}

template<size_t Size>
PacketBuffer* RingBuffer<Size>::Pop() {
    size_t tail = tail_.load(std::memory_order_relaxed);

    if (tail == head_.load(std::memory_order_acquire)) {
        return nullptr;  // Empty
    }

    PacketBuffer* pkt = buffers_[tail];
    tail_.store((tail + 1) % Size, std::memory_order_release);
    return pkt;
}

template<size_t Size>
size_t RingBuffer<Size>::Available() const {
    size_t head = head_.load(std::memory_order_relaxed);
    size_t tail = tail_.load(std::memory_order_relaxed);

    if (head >= tail) {
        return head - tail;
    } else {
        return Size - tail + head;
    }
}

template<size_t Size>
bool RingBuffer<Size>::IsEmpty() const {
    return head_.load(std::memory_order_relaxed) ==
           tail_.load(std::memory_order_relaxed);
}

template<size_t Size>
bool RingBuffer<Size>::IsFull() const {
    size_t head = head_.load(std::memory_order_relaxed);
    size_t tail = tail_.load(std::memory_order_relaxed);
    return ((head + 1) % Size) == tail;
}

// Explicit template instantiation
template class RingBuffer<4096>;

// QuicheTunForwarder implementation

QuicheTunForwarder::QuicheTunForwarder(
    const ForwarderConfig& config,
    QuicheClient* quic_client)
    : config_(config), quic_client_(quic_client) {

    LOGI(LOG_TAG, "Creating TUN forwarder (tun_fd=%d)", config_.tun_fd);
}

QuicheTunForwarder::~QuicheTunForwarder() {
    LOGI(LOG_TAG, "Destroying TUN forwarder");
    Stop();
}

std::unique_ptr<QuicheTunForwarder> QuicheTunForwarder::Create(
    const ForwarderConfig& config,
    QuicheClient* quic_client) {

    if (config.tun_fd < 0) {
        LOGE(LOG_TAG, "Invalid TUN fd");
        return nullptr;
    }

    if (!quic_client) {
        LOGE(LOG_TAG, "NULL QUIC client");
        return nullptr;
    }

    auto forwarder = std::unique_ptr<QuicheTunForwarder>(
        new QuicheTunForwarder(config, quic_client)
    );

    if (forwarder->InitializePacketPool() != 0) {
        LOGE(LOG_TAG, "Failed to initialize packet pool");
        return nullptr;
    }

    return forwarder;
}

int QuicheTunForwarder::InitializePacketPool() {
    LOGI(LOG_TAG, "Initializing packet pool (size=%zu)", config_.packet_pool_size);

    // Allocate packet pool
    packet_pool_.resize(config_.packet_pool_size);

    for (size_t i = 0; i < config_.packet_pool_size; i++) {
        // Allocate aligned memory for each packet (for SIMD operations)
        packet_pool_[i].capacity = 65536;  // Max IP packet size
        packet_pool_[i].data = (uint8_t*)MemUtils::AllocateAligned(
            packet_pool_[i].capacity, 64);  // 64-byte alignment

        if (!packet_pool_[i].data) {
            LOGE(LOG_TAG, "Failed to allocate packet buffer");
            return -1;
        }

        packet_pool_[i].len = 0;
        packet_pool_[i].in_use = false;
    }

    // Create ring buffers
    rx_ring_ = std::make_unique<RingBuffer<4096>>();
    tx_ring_ = std::make_unique<RingBuffer<4096>>();

    LOGI(LOG_TAG, "Packet pool initialized successfully");
    return 0;
}

int QuicheTunForwarder::ConfigureUdpSocket(int sockfd) {
    // Enable UDP GSO (Generic Segmentation Offload)
    if (config_.use_gso) {
        if (NetUtils::EnableUdpGSO(sockfd) != 0) {
            LOGW(LOG_TAG, "UDP GSO not available");
        } else {
            LOGI(LOG_TAG, "UDP GSO enabled");
        }
    }

    // Enable UDP GRO (Generic Receive Offload)
    if (config_.use_gro) {
        if (NetUtils::EnableUdpGRO(sockfd) != 0) {
            LOGW(LOG_TAG, "UDP GRO not available");
        } else {
            LOGI(LOG_TAG, "UDP GRO enabled");
        }
    }

    return 0;
}

int QuicheTunForwarder::Start() {
    if (running_.load()) {
        LOGW(LOG_TAG, "Already running");
        return -1;
    }

    LOGI(LOG_TAG, "Starting TUN forwarder...");

    // Configure CPU affinity
    if (ConfigureCpuAffinity() != 0) {
        LOGW(LOG_TAG, "Failed to configure CPU affinity (non-fatal)");
    }

    // Configure UDP socket
    int quic_fd = quic_client_->GetSocketFd();
    if (quic_fd >= 0) {
        ConfigureUdpSocket(quic_fd);
    }

    running_.store(true);

    // Start forwarding thread
    forward_thread_ = std::make_unique<std::thread>(
        &QuicheTunForwarder::ForwardingLoop, this);

    LOGI(LOG_TAG, "TUN forwarder started");
    return 0;
}

void QuicheTunForwarder::Stop() {
    if (!running_.load()) {
        return;
    }

    LOGI(LOG_TAG, "Stopping TUN forwarder...");

    running_.store(false);

    if (forward_thread_ && forward_thread_->joinable()) {
        forward_thread_->join();
    }

    LOGI(LOG_TAG, "TUN forwarder stopped");
}

void QuicheTunForwarder::ForwardingLoop() {
    LOGI(LOG_TAG, "Forwarding loop started");

    // Set thread name for debugging
    pthread_setname_np(pthread_self(), "TunForward");

    // Configure CPU affinity for this thread
    ConfigureCpuAffinity();

    // Set realtime scheduling if enabled
    if (config_.enable_realtime) {
        CpuUtils::SetRealtimeScheduling(50);
    }

    // Prepare for batch receiving
    const size_t batch_size = config_.batch_size;
    std::vector<struct mmsghdr> msgs(batch_size);
    std::vector<struct iovec> iovecs(batch_size);
    std::vector<PacketBuffer*> packets(batch_size);

    memset(msgs.data(), 0, msgs.size() * sizeof(msgs[0]));
    memset(iovecs.data(), 0, iovecs.size() * sizeof(iovecs[0]));

    // Pre-allocate buffers for batch
    for (size_t i = 0; i < batch_size; i++) {
        packets[i] = AllocatePacket();
        if (!packets[i]) {
            LOGE(LOG_TAG, "Failed to allocate packet buffer");
            return;
        }

        iovecs[i].iov_base = packets[i]->data;
        iovecs[i].iov_len = packets[i]->capacity;

        msgs[i].msg_hdr.msg_iov = iovecs.data() + i;
        msgs[i].msg_hdr.msg_iovlen = 1;
    }

    uint64_t loop_count = 0;
    uint64_t last_stats_time = TimeUtils::GetTimestampUs();

    while (running_.load()) {
        // Receive batch of packets from TUN
        int received = ReceiveTunPacketsBatch(msgs.data(), batch_size);

        if (received > 0) {
            // Update packet lengths
            for (int i = 0; i < received; i++) {
                packets[i]->len = msgs[i].msg_len;
                packets[i]->timestamp_us = TimeUtils::GetTimestampUs();
            }

            // Process batch
            ProcessPacketBatch(msgs.data(), received);

            // Update stats
            stats_.packets_received += received;
        } else if (received < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
            LOGE(LOG_TAG, "recvmmsg() failed: %s", strerror(errno));
            break;
        }

        // Update statistics every 100ms
        uint64_t now = TimeUtils::GetTimestampUs();
        if (now - last_stats_time >= 100000) {  // 100ms
            UpdateStats();
            last_stats_time = now;
        }

        loop_count++;

        // Yield CPU occasionally to prevent monopolization
        if (loop_count % 1000 == 0) {
            sched_yield();
        }
    }

    // Free packet buffers
    for (size_t i = 0; i < batch_size; i++) {
        if (packets[i]) {
            FreePacket(packets[i]);
        }
    }

    LOGI(LOG_TAG, "Forwarding loop stopped");
}

int QuicheTunForwarder::ReceiveTunPacketsBatch(
    struct mmsghdr* msgs, int max_packets) {

    // Use recvmmsg for batch receiving (high performance)
    int received = recvmmsg(config_.tun_fd, msgs, max_packets,
                           MSG_DONTWAIT, nullptr);

    if (received < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            LOGE(LOG_TAG, "recvmmsg() failed: %s", strerror(errno));
        }
        return received;
    }

    return received;
}

void QuicheTunForwarder::ProcessPacketBatch(struct mmsghdr* msgs, int count) {
    for (int i = 0; i < count; i++) {
        uint8_t* data = (uint8_t*)msgs[i].msg_hdr.msg_iov[0].iov_base;
        size_t len = msgs[i].msg_len;

        // Send via QUIC
        if (SendViaQuic(data, len) < 0) {
            stats_.packets_dropped++;
        } else {
            stats_.packets_sent++;
            stats_.bytes_sent += len;
        }
    }
}

int QuicheTunForwarder::SendViaQuic(const uint8_t* data, size_t len) {
    if (!quic_client_ || !quic_client_->IsConnected()) {
        return -1;
    }

    // Send packet via QUIC
    ssize_t sent = quic_client_->Send(data, len);

    return sent >= 0 ? 0 : -1;
}

PacketBuffer* QuicheTunForwarder::AllocatePacket() {
    // Find free packet in pool (simple round-robin)
    for (size_t i = 0; i < packet_pool_.size(); i++) {
        size_t idx = (pool_index_.fetch_add(1) + i) % packet_pool_.size();

        if (!packet_pool_[idx].in_use) {
            packet_pool_[idx].in_use = true;
            packet_pool_[idx].len = 0;
            return &packet_pool_[idx];
        }
    }

    // Pool exhausted
    return nullptr;
}

void QuicheTunForwarder::FreePacket(PacketBuffer* pkt) {
    if (!pkt) {
        return;
    }

    pkt->in_use = false;
    pkt->len = 0;
}

int QuicheTunForwarder::ConfigureCpuAffinity() {
    uint64_t cpu_mask;

    if (config_.cpu_affinity == CpuAffinity::BIG_CORES) {
        cpu_mask = CpuUtils::GetBigCoresMask();
    } else if (config_.cpu_affinity == CpuAffinity::LITTLE_CORES) {
        cpu_mask = CpuUtils::GetLittleCoresMask();
    } else {
        return 0;  // No affinity
    }

    if (CpuUtils::SetCpuAffinity(cpu_mask) != 0) {
        return -1;
    }

    LOGI(LOG_TAG, "CPU affinity configured (mask=0x%lx)", cpu_mask);
    return 0;
}

void QuicheTunForwarder::UpdateStats() {
    uint64_t now = TimeUtils::GetTimestampUs();
    uint64_t elapsed_us = now - last_stats_update_us_;

    if (elapsed_us == 0) {
        return;
    }

    // Calculate rates (Mbps)
    stats_.rx_rate_mbps = (stats_.bytes_received * 8.0) / elapsed_us;
    stats_.tx_rate_mbps = (stats_.bytes_sent * 8.0) / elapsed_us;

    last_stats_update_us_ = now;
}

ForwarderStats QuicheTunForwarder::GetStats() const {
    return stats_;
}

} // namespace quiche_client
