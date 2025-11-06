#ifndef PEPPER_QUEUE_H
#define PEPPER_QUEUE_H

#include <atomic>
#include <cstdint>
#include <cstddef>

// Cache line size for alignment
#define CACHE_LINE_SIZE 64

/**
 * Lock-free ring buffer for traffic shaping
 * Based on perf-net ring buffer with sequence number protection
 */
struct alignas(CACHE_LINE_SIZE) PepperRingBuffer {
    std::atomic<uint64_t> write_pos;
    std::atomic<uint32_t> write_seq;  // Sequence number for ABA protection
    std::atomic<uint64_t> read_pos;
    std::atomic<uint32_t> read_seq;  // Sequence number for ABA protection
    size_t capacity;
    char* data;
    char padding[CACHE_LINE_SIZE - sizeof(std::atomic<uint64_t>) * 2 
                  - sizeof(std::atomic<uint32_t>) * 2 - sizeof(size_t) - sizeof(char*)];
};

/**
 * Packet metadata for queue
 */
struct PepperPacket {
    uint64_t timestamp_ns;  // High-resolution timestamp
    size_t length;
    uint32_t seq;           // Sequence number for loss detection
    bool is_retransmit;
};

/**
 * Queue statistics
 */
struct PepperQueueStats {
    uint64_t bytes_enqueued;
    uint64_t bytes_dequeued;
    uint64_t packets_dropped;
    uint64_t packets_retransmitted;
    size_t current_queue_depth;
    float loss_rate;        // 0.0-1.0
    uint64_t avg_rtt_ns;    // Average RTT in nanoseconds
};

/**
 * Create a lock-free ring buffer
 */
PepperRingBuffer* pepper_queue_create(size_t capacity);

/**
 * Destroy ring buffer
 */
void pepper_queue_destroy(PepperRingBuffer* rb);

/**
 * Enqueue data (lock-free)
 * Returns bytes written, 0 if full
 */
size_t pepper_queue_enqueue(PepperRingBuffer* rb, const void* data, size_t length);

/**
 * Dequeue data (lock-free)
 * Returns bytes read, 0 if empty
 */
size_t pepper_queue_dequeue(PepperRingBuffer* rb, void* data, size_t max_length);

/**
 * Get available space
 */
size_t pepper_queue_available(PepperRingBuffer* rb);

/**
 * Get used space
 */
size_t pepper_queue_used(PepperRingBuffer* rb);

#endif // PEPPER_QUEUE_H
