/*
 * High-resolution pacing implementation for PepperShaper
 * Token bucket with loss-aware backoff
 */

#include "pepper_pacing.h"
#include <chrono>
#include <algorithm>
#include <cmath>
#include <android/log.h>

#define LOG_TAG "PepperPacing"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Token bucket refill rate (tokens per second)
#define TOKEN_BUCKET_REFILL_RATE 1000000  // 1M tokens/sec

void pepper_pacing_init(PepperPacingState* state, const PepperPacingParams* params) {
    if (!state || !params) return;
    
    uint64_t now_ns = pepper_pacing_get_time_ns();
    state->next_send_time_ns = now_ns;
    state->tokens = params->max_burst_bytes;
    state->last_update_ns = now_ns;
    state->loss_rate = 0.0f;
    state->rtt_ns = 0;
    state->in_backoff = false;
    state->backoff_until_ns = 0;
}

bool pepper_pacing_can_send(PepperPacingState* state, const PepperPacingParams* params,
                            size_t packet_size, uint64_t current_time_ns) {
    if (!state || !params || !params->enable_pacing) {
        return true; // Pacing disabled
    }
    
    // Check backoff
    if (state->in_backoff && current_time_ns < state->backoff_until_ns) {
        return false;
    }
    state->in_backoff = false;
    
    // Check time-based pacing
    if (current_time_ns < state->next_send_time_ns) {
        return false;
    }
    
    // Token bucket check
    if (params->target_rate_bps > 0) {
        // Refill tokens based on elapsed time
        uint64_t elapsed_ns = current_time_ns - state->last_update_ns;
        uint64_t tokens_to_add = (elapsed_ns * params->target_rate_bps) / (8 * 1000000000ULL); // Convert bps to bytes/ns
        
        state->tokens = std::min(
            state->tokens + tokens_to_add,
            params->max_burst_bytes
        );
        state->last_update_ns = current_time_ns;
        
        // Check if we have enough tokens
        if (state->tokens < packet_size) {
            return false;
        }
    }
    
    return true;
}

void pepper_pacing_update_after_send(PepperPacingState* state, const PepperPacingParams* params,
                                     size_t packet_size, uint64_t current_time_ns) {
    if (!state || !params) return;
    
    // Consume tokens
    if (params->target_rate_bps > 0) {
        if (state->tokens >= packet_size) {
            state->tokens -= packet_size;
        } else {
            state->tokens = 0;
        }
    }
    
    // Update next send time based on rate
    if (params->target_rate_bps > 0) {
        // Calculate interval: packet_size * 8 bits / rate_bps = seconds
        uint64_t interval_ns = (packet_size * 8 * 1000000000ULL) / params->target_rate_bps;
        interval_ns = std::max(interval_ns, params->min_pacing_interval_ns);
        state->next_send_time_ns = current_time_ns + interval_ns;
    } else {
        // No rate limit, just respect min interval
        state->next_send_time_ns = current_time_ns + params->min_pacing_interval_ns;
    }
    
    // Loss-aware backoff
    if (params->loss_aware_backoff && state->loss_rate > 0.1f) { // 10% loss threshold
        // Exponential backoff based on loss rate
        float backoff_factor = 1.0f + (state->loss_rate * 10.0f); // 1x to 11x
        uint64_t backoff_duration_ns = (state->rtt_ns > 0 ? state->rtt_ns : 100000000ULL) * backoff_factor; // Use RTT or 100ms default
        state->backoff_until_ns = current_time_ns + static_cast<uint64_t>(backoff_duration_ns);
        state->in_backoff = true;
    }
}

void pepper_pacing_update_metrics(PepperPacingState* state, float loss_rate, uint64_t rtt_ns) {
    if (!state) return;
    
    // Exponential moving average
    const float alpha = 0.1f; // Smoothing factor
    state->loss_rate = alpha * loss_rate + (1.0f - alpha) * state->loss_rate;
    
    if (rtt_ns > 0) {
        state->rtt_ns = alpha * rtt_ns + (1.0f - alpha) * state->rtt_ns;
    }
}

uint64_t pepper_pacing_get_time_ns() {
    auto now = std::chrono::high_resolution_clock::now();
    auto duration = now.time_since_epoch();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(duration).count();
}
