/*
 * Lock-free ring buffer implementation for PepperShaper
 * Based on perf-net ring buffer with sequence number protection
 */

#include "pepper_queue.h"
#include <cstring>
#include <cstdlib>
#include <new>
#include <malloc.h>
#include <limits.h>
#include <android/log.h>

#define LOG_TAG "PepperQueue"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

PepperRingBuffer* pepper_queue_create(size_t capacity) {
    if (capacity <= 0 || capacity > 64 * 1024 * 1024) { // Max 64MB
        LOGE("Invalid capacity: %zu (must be 1-67108864)", capacity);
        return nullptr;
    }
    
    PepperRingBuffer* rb = new (std::nothrow) PepperRingBuffer();
    if (!rb) {
        LOGE("Failed to allocate RingBuffer structure");
        return nullptr;
    }
    
    rb->capacity = capacity;
    
    // Use posix_memalign for cache line alignment
    void* aligned_ptr = nullptr;
    int align_result = posix_memalign(&aligned_ptr, CACHE_LINE_SIZE, capacity);
    if (align_result != 0 || !aligned_ptr) {
        LOGE("Failed to allocate aligned ring buffer data: %zu bytes (errno: %d)", 
             capacity, align_result);
        delete rb;
        return nullptr;
    }
    rb->data = static_cast<char*>(aligned_ptr);
    
    // Initialize atomics
    rb->write_pos.store(0, std::memory_order_relaxed);
    rb->write_seq.store(0, std::memory_order_relaxed);
    rb->read_pos.store(0, std::memory_order_relaxed);
    rb->read_seq.store(0, std::memory_order_relaxed);
    
    LOGD("Ring buffer created: capacity=%zu", capacity);
    return rb;
}

void pepper_queue_destroy(PepperRingBuffer* rb) {
    if (!rb) return;
    
    if (rb->data) {
        free(rb->data);
        rb->data = nullptr;
    }
    
    delete rb;
}

size_t pepper_queue_enqueue(PepperRingBuffer* rb, const void* data, size_t length) {
    if (!rb || !data || length == 0) return 0;
    
    uint64_t write_pos = rb->write_pos.load(std::memory_order_relaxed);
    uint32_t write_seq = rb->write_seq.load(std::memory_order_acquire);
    uint64_t read_pos = rb->read_pos.load(std::memory_order_acquire);
    uint32_t read_seq = rb->read_seq.load(std::memory_order_acquire);
    
    // Calculate available space
    size_t available;
    if (write_seq == read_seq) {
        // Same generation
        if (write_pos >= read_pos) {
            available = rb->capacity - (write_pos - read_pos);
        } else {
            available = read_pos - write_pos;
        }
    } else {
        // Different generation (wrapped)
        available = rb->capacity - (read_pos - write_pos);
    }
    
    // Reserve one byte to distinguish full from empty
    if (available <= 1) {
        return 0; // Full
    }
    
    size_t to_write = (length < available - 1) ? length : (available - 1);
    
    // Write data (may wrap around)
    size_t first_part = (write_pos + to_write <= rb->capacity) 
                        ? to_write 
                        : (rb->capacity - write_pos);
    
    memcpy(rb->data + write_pos, data, first_part);
    if (first_part < to_write) {
        memcpy(rb->data, static_cast<const char*>(data) + first_part, to_write - first_part);
    }
    
    // Update write position and sequence
    uint64_t new_pos = (write_pos + to_write) % rb->capacity;
    uint32_t new_seq = write_seq;
    if (write_pos + to_write >= rb->capacity) {
        new_seq = (write_seq + 1) % 0xFFFFFFFF;
    }
    
    rb->write_pos.store(new_pos, std::memory_order_release);
    if (new_seq != write_seq) {
        rb->write_seq.store(new_seq, std::memory_order_release);
    }
    
    return to_write;
}

size_t pepper_queue_dequeue(PepperRingBuffer* rb, void* data, size_t max_length) {
    if (!rb || !data || max_length == 0) return 0;
    
    uint64_t read_pos = rb->read_pos.load(std::memory_order_relaxed);
    uint32_t read_seq = rb->read_seq.load(std::memory_order_acquire);
    uint64_t write_pos = rb->write_pos.load(std::memory_order_acquire);
    uint32_t write_seq = rb->write_seq.load(std::memory_order_acquire);
    
    // Check if empty
    if (read_seq == write_seq && read_pos == write_pos) {
        return 0; // Empty
    }
    
    // Calculate available data
    size_t available;
    if (read_seq == write_seq) {
        if (read_pos < write_pos) {
            available = write_pos - read_pos;
        } else {
            available = 0; // Shouldn't happen if not empty
        }
    } else {
        available = rb->capacity - (read_pos - write_pos);
    }
    
    size_t to_read = (max_length < available) ? max_length : available;
    
    // Read data (may wrap around)
    size_t first_part = (read_pos + to_read <= rb->capacity)
                        ? to_read
                        : (rb->capacity - read_pos);
    
    memcpy(data, rb->data + read_pos, first_part);
    if (first_part < to_read) {
        memcpy(static_cast<char*>(data) + first_part, rb->data, to_read - first_part);
    }
    
    // Update read position and sequence
    uint64_t new_pos = (read_pos + to_read) % rb->capacity;
    uint32_t new_seq = read_seq;
    if (read_pos + to_read >= rb->capacity) {
        new_seq = (read_seq + 1) % 0xFFFFFFFF;
    }
    
    rb->read_pos.store(new_pos, std::memory_order_release);
    if (new_seq != read_seq) {
        rb->read_seq.store(new_seq, std::memory_order_release);
    }
    
    return to_read;
}

size_t pepper_queue_available(PepperRingBuffer* rb) {
    if (!rb) return 0;
    
    uint64_t write_pos = rb->write_pos.load(std::memory_order_acquire);
    uint32_t write_seq = rb->write_seq.load(std::memory_order_acquire);
    uint64_t read_pos = rb->read_pos.load(std::memory_order_acquire);
    uint32_t read_seq = rb->read_seq.load(std::memory_order_acquire);
    
    if (write_seq == read_seq) {
        if (write_pos >= read_pos) {
            return rb->capacity - (write_pos - read_pos) - 1;
        } else {
            return read_pos - write_pos - 1;
        }
    } else {
        return rb->capacity - (read_pos - write_pos) - 1;
    }
}

size_t pepper_queue_used(PepperRingBuffer* rb) {
    if (!rb) return 0;
    
    uint64_t write_pos = rb->write_pos.load(std::memory_order_acquire);
    uint32_t write_seq = rb->write_seq.load(std::memory_order_acquire);
    uint64_t read_pos = rb->read_pos.load(std::memory_order_acquire);
    uint32_t read_seq = rb->read_seq.load(std::memory_order_acquire);
    
    if (read_seq == write_seq && read_pos == write_pos) {
        return 0; // Empty
    }
    
    if (read_seq == write_seq) {
        if (read_pos < write_pos) {
            return write_pos - read_pos;
        } else {
            return 0;
        }
    } else {
        return rb->capacity - (read_pos - write_pos);
    }
}
