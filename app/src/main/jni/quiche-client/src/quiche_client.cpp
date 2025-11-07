/*
 * QUICHE Client Implementation - Maximum Performance Mode
 */

#include "quiche_client.h"
#include "quiche_crypto.h"
#include "quiche_utils.h"

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <netdb.h>
#include <sched.h>
#include <cstring>
#include <cstdio>

#define LOG_TAG "QuicheClient"

namespace quiche_client {

// Convert CongestionControl enum to quiche_cc_algorithm
static quiche_cc_algorithm ToQuicheCongestionControl(CongestionControl cc) {
    switch (cc) {
        case CongestionControl::RENO:
            return QUICHE_CC_RENO;
        case CongestionControl::CUBIC:
            return QUICHE_CC_CUBIC;
        case CongestionControl::BBR:
            return QUICHE_CC_BBR;
        case CongestionControl::BBR2:
            return QUICHE_CC_BBR2;
        default:
            return QUICHE_CC_BBR2;  // Default to BBR2
    }
}

// QuicheClient implementation

QuicheClient::QuicheClient(const QuicConfig& config)
    : config_(config) {
    LOGD(LOG_TAG, "Creating QUIC client for %s:%d",
         config_.server_host.c_str(), config_.server_port);
}

QuicheClient::~QuicheClient() {
    LOGD(LOG_TAG, "Destroying QUIC client");
    Disconnect();

    if (quiche_config_) {
        quiche_config_free(quiche_config_);
        quiche_config_ = nullptr;
    }

    if (socket_fd_ >= 0) {
        close(socket_fd_);
        socket_fd_ = -1;
    }
}

std::unique_ptr<QuicheClient> QuicheClient::Create(const QuicConfig& config) {
    auto client = std::unique_ptr<QuicheClient>(new QuicheClient(config));

    if (client->Initialize() != 0) {
        LOGE(LOG_TAG, "Failed to initialize QUIC client");
        return nullptr;
    }

    return client;
}

int QuicheClient::Initialize() {
    // Create QUICHE config
    quiche_config_ = quiche_config_new(QUICHE_PROTOCOL_VERSION);
    if (!quiche_config_) {
        LOGE(LOG_TAG, "Failed to create QUICHE config");
        return -1;
    }

    // Set QUIC parameters for MAXIMUM PERFORMANCE
    quiche_config_set_initial_max_data(quiche_config_, config_.initial_max_data);
    quiche_config_set_initial_max_stream_data_bidi_local(quiche_config_, config_.initial_max_stream_data);
    quiche_config_set_initial_max_stream_data_bidi_remote(quiche_config_, config_.initial_max_stream_data);
    quiche_config_set_initial_max_stream_data_uni(quiche_config_, config_.initial_max_stream_data);
    quiche_config_set_initial_max_streams_bidi(quiche_config_, config_.initial_max_streams_bidi);
    quiche_config_set_initial_max_streams_uni(quiche_config_, config_.initial_max_streams_uni);
    quiche_config_set_max_idle_timeout(quiche_config_, config_.max_idle_timeout_ms);
    quiche_config_set_max_recv_udp_payload_size(quiche_config_, config_.max_udp_payload_size);

    // Set congestion control (BBR2 for maximum performance)
    quiche_config_set_cc_algorithm(quiche_config_, ToQuicheCongestionControl(config_.cc_algorithm));

    // Enable early data (0-RTT) for minimum latency
    if (config_.enable_early_data) {
        quiche_config_enable_early_data(quiche_config_);
    }

    // Disable pacing for maximum throughput
    quiche_config_enable_pacing(quiche_config_, config_.enable_pacing);

    // Enable HyStart++ for better congestion control
    if (config_.enable_hystart) {
        quiche_config_enable_hystart(quiche_config_, true);
    }

    // Enable datagram support
    if (config_.enable_dgram) {
        quiche_config_enable_dgram(quiche_config_, true, 1000, 1000);
    }

    // Set application protocols (HTTP/3)
    const uint8_t alpn[] = "\x02h3";
    quiche_config_set_application_protos(quiche_config_, alpn, sizeof(alpn) - 1);

    LOGI(LOG_TAG, "QUICHE config initialized (CC=%d, 0-RTT=%d)",
         config_.cc_algorithm, config_.enable_early_data);

    // Create UDP socket
    if (CreateSocket() != 0) {
        LOGE(LOG_TAG, "Failed to create socket");
        return -1;
    }

    // Configure CPU affinity
    if (ConfigureCpuAffinity() != 0) {
        LOGW(LOG_TAG, "Failed to configure CPU affinity (non-fatal)");
    }

    // Configure realtime scheduling (optional, dangerous)
    if (config_.enable_realtime_sched) {
        if (ConfigureRealtimeScheduling() != 0) {
            LOGW(LOG_TAG, "Failed to configure realtime scheduling (non-fatal)");
        }
    }

    // Initialize crypto handler
    crypto_ = QuicheCrypto::Create(QuicheCrypto::GetRecommendedAlgorithm());
    if (!crypto_) {
        LOGE(LOG_TAG, "Failed to create crypto handler");
        return -1;
    }

    LOGI(LOG_TAG, "QUIC client initialized successfully");
    return 0;
}

int QuicheClient::CreateSocket() {
    // Create UDP socket
    socket_fd_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (socket_fd_ < 0) {
        LOGE(LOG_TAG, "socket() failed: %s", strerror(errno));
        return -1;
    }

    // Set non-blocking
    if (NetUtils::SetNonBlocking(socket_fd_) != 0) {
        LOGE(LOG_TAG, "Failed to set non-blocking");
        close(socket_fd_);
        socket_fd_ = -1;
        return -1;
    }

    // Set large socket buffers (8MB) for high throughput
    if (NetUtils::SetSocketBuffers(socket_fd_, 8 * 1024 * 1024, 8 * 1024 * 1024) != 0) {
        LOGW(LOG_TAG, "Failed to set socket buffers (non-fatal)");
    }

    // Enable UDP GSO/GRO for kernel offload
    if (NetUtils::EnableUdpGSO(socket_fd_) != 0) {
        LOGW(LOG_TAG, "UDP GSO not available (non-fatal)");
    }

    if (NetUtils::EnableUdpGRO(socket_fd_) != 0) {
        LOGW(LOG_TAG, "UDP GRO not available (non-fatal)");
    }

    LOGD(LOG_TAG, "UDP socket created (fd=%d)", socket_fd_);
    return 0;
}

int QuicheClient::ConfigureCpuAffinity() {
    uint64_t cpu_mask = config_.cpu_mask;

    // Auto-detect big cores if needed
    if (config_.cpu_affinity == CpuAffinity::BIG_CORES) {
        cpu_mask = CpuUtils::GetBigCoresMask();
    } else if (config_.cpu_affinity == CpuAffinity::LITTLE_CORES) {
        cpu_mask = CpuUtils::GetLittleCoresMask();
    } else if (config_.cpu_affinity == CpuAffinity::NONE) {
        return 0;  // No affinity
    }

    if (CpuUtils::SetCpuAffinity(cpu_mask) != 0) {
        LOGW(LOG_TAG, "Failed to set CPU affinity");
        return -1;
    }

    LOGI(LOG_TAG, "CPU affinity set to mask 0x%lx", cpu_mask);
    return 0;
}

int QuicheClient::ConfigureRealtimeScheduling() {
    if (CpuUtils::SetRealtimeScheduling(config_.realtime_priority) != 0) {
        LOGW(LOG_TAG, "Failed to set realtime scheduling");
        return -1;
    }

    LOGI(LOG_TAG, "Realtime scheduling enabled (priority=%d)", config_.realtime_priority);
    return 0;
}

int QuicheClient::Connect() {
    if (connected_.load()) {
        LOGW(LOG_TAG, "Already connected");
        return 0;
    }

    LOGI(LOG_TAG, "Connecting to %s:%d...", config_.server_host.c_str(), config_.server_port);

    // Resolve server address
    struct addrinfo hints, *res;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;

    char port_str[8];
    snprintf(port_str, sizeof(port_str), "%d", config_.server_port);

    if (getaddrinfo(config_.server_host.c_str(), port_str, &hints, &res) != 0) {
        LOGE(LOG_TAG, "getaddrinfo() failed");
        return -1;
    }

    // Connect socket
    if (connect(socket_fd_, res->ai_addr, res->ai_addrlen) != 0) {
        LOGE(LOG_TAG, "connect() failed: %s", strerror(errno));
        freeaddrinfo(res);
        return -1;
    }

    freeaddrinfo(res);

    // Generate connection ID
    scid_len_ = quiche::MAX_CONN_ID_LEN;
    for (size_t i = 0; i < scid_len_; i++) {
        scid_[i] = rand() % 256;
    }

    // Create QUIC connection
    struct sockaddr_storage local_addr, peer_addr;
    socklen_t local_addr_len = sizeof(local_addr);
    socklen_t peer_addr_len = sizeof(peer_addr);

    getsockname(socket_fd_, (struct sockaddr*)&local_addr, &local_addr_len);
    getpeername(socket_fd_, (struct sockaddr*)&peer_addr, &peer_addr_len);

    conn_ = quiche_connect(
        config_.server_host.c_str(),
        scid_, scid_len_,
        (struct sockaddr*)&local_addr, local_addr_len,
        (struct sockaddr*)&peer_addr, peer_addr_len,
        quiche_config_
    );

    if (!conn_) {
        LOGE(LOG_TAG, "quiche_connect() failed");
        return -1;
    }

    // Send initial packet
    uint8_t out[config_.max_udp_payload_size];
    quiche_send_info send_info;

    ssize_t written = quiche_conn_send(conn_, out, sizeof(out), &send_info);
    if (written < 0) {
        LOGE(LOG_TAG, "quiche_conn_send() failed: %zd", written);
        return -1;
    }

    ssize_t sent = send(socket_fd_, out, written, 0);
    if (sent != written) {
        LOGE(LOG_TAG, "send() failed: %zd", sent);
        return -1;
    }

    connected_.store(true);
    running_.store(true);

    uint64_t handshake_start = TimeUtils::GetTimestampUs();

    // Wait for handshake completion (with timeout)
    const int MAX_WAIT_MS = 5000;
    int elapsed_ms = 0;

    while (!quiche_conn_is_established(conn_) && elapsed_ms < MAX_WAIT_MS) {
        ProcessEvents();
        TimeUtils::SleepMs(10);
        elapsed_ms += 10;
    }

    if (!quiche_conn_is_established(conn_)) {
        LOGE(LOG_TAG, "Handshake timeout");
        Disconnect();
        return -1;
    }

    metrics_.handshake_duration_us = TimeUtils::GetTimestampUs() - handshake_start;
    metrics_.is_established = true;

    LOGI(LOG_TAG, "Connected successfully (handshake took %lu us)",
         metrics_.handshake_duration_us);

    return 0;
}

void QuicheClient::Disconnect() {
    if (!connected_.load()) {
        return;
    }

    LOGI(LOG_TAG, "Disconnecting...");

    running_.store(false);
    connected_.store(false);

    if (conn_) {
        quiche_conn_close(conn_, true, 0, nullptr, 0);
        quiche_conn_free(conn_);
        conn_ = nullptr;
    }

    metrics_.is_established = false;

    LOGI(LOG_TAG, "Disconnected");
}

bool QuicheClient::IsConnected() const {
    return connected_.load() && conn_ && quiche_conn_is_established(conn_);
}

ssize_t QuicheClient::Send(const uint8_t* data, size_t len) {
    if (!IsConnected()) {
        return -1;
    }

    // Send via QUIC stream (stream ID 0)
    ssize_t sent = quiche_conn_stream_send(conn_, 0, data, len, true);
    if (sent < 0) {
        LOGE(LOG_TAG, "quiche_conn_stream_send() failed: %zd", sent);
        return sent;
    }

    // Flush packets
    uint8_t out[config_.max_udp_payload_size];
    quiche_send_info send_info;

    ssize_t written = quiche_conn_send(conn_, out, sizeof(out), &send_info);
    if (written > 0) {
        send(socket_fd_, out, written, 0);
        metrics_.bytes_sent += written;
        metrics_.packets_sent++;
    }

    return sent;
}

ssize_t QuicheClient::Receive(uint8_t* buffer, size_t len) {
    if (!IsConnected()) {
        return -1;
    }

    // Receive from socket
    uint8_t buf[65535];
    ssize_t received = recv(socket_fd_, buf, sizeof(buf), MSG_DONTWAIT);

    if (received <= 0) {
        return received;
    }

    metrics_.bytes_received += received;
    metrics_.packets_received++;

    // Process QUIC packet
    quiche_recv_info recv_info;
    struct sockaddr_storage peer_addr;
    socklen_t peer_addr_len = sizeof(peer_addr);

    getpeername(socket_fd_, (struct sockaddr*)&peer_addr, &peer_addr_len);

    recv_info.from = (struct sockaddr*)&peer_addr;
    recv_info.from_len = peer_addr_len;
    recv_info.to = (struct sockaddr*)&peer_addr;
    recv_info.to_len = peer_addr_len;

    ssize_t done = quiche_conn_recv(conn_, buf, received, &recv_info);
    if (done < 0) {
        return done;
    }

    // Read stream data
    bool fin = false;
    ssize_t stream_recv = quiche_conn_stream_recv(conn_, 0, buffer, len, &fin);

    return stream_recv;
}

void QuicheClient::ProcessEvents() {
    if (!conn_) {
        return;
    }

    // Receive packets
    uint8_t buf[65535];
    ssize_t received = recv(socket_fd_, buf, sizeof(buf), MSG_DONTWAIT);

    if (received > 0) {
        quiche_recv_info recv_info;
        struct sockaddr_storage peer_addr;
        socklen_t peer_addr_len = sizeof(peer_addr);

        getpeername(socket_fd_, (struct sockaddr*)&peer_addr, &peer_addr_len);

        recv_info.from = (struct sockaddr*)&peer_addr;
        recv_info.from_len = peer_addr_len;
        recv_info.to = (struct sockaddr*)&peer_addr;
        recv_info.to_len = peer_addr_len;

        quiche_conn_recv(conn_, buf, received, &recv_info);
    }

    // Send pending packets
    uint8_t out[config_.max_udp_payload_size];
    quiche_send_info send_info;

    while (true) {
        ssize_t written = quiche_conn_send(conn_, out, sizeof(out), &send_info);
        if (written == QUICHE_ERR_DONE) {
            break;
        }

        if (written < 0) {
            break;
        }

        send(socket_fd_, out, written, 0);
    }
}

QuicMetrics QuicheClient::GetMetrics() const {
    if (conn_) {
        quiche_stats stats;
        quiche_conn_stats(conn_, &stats);

        metrics_.rtt_us = stats.rtt;
        metrics_.cwnd = stats.cwnd;
        metrics_.bytes_in_flight = stats.delivery_rate;
    }

    return metrics_;
}

void QuicheClient::SetPacketCallback(std::function<void(const uint8_t*, size_t)> callback) {
    packet_callback_ = callback;
}

} // namespace quiche_client
