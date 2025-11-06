#ifndef PEPPER_PACING_H
#define PEPPER_PACING_H

#include <cstdint>
#include <chrono>

/**
 * Pacing parameters
 */
struct PepperPacingParams {
    uint64_t target_rate_bps;      // Target rate in bits per second (0 = unlimited)
    uint64_t max_burst_bytes;      // Maximum burst size in bytes
    bool loss_aware_backoff;       // Enable loss-aware backoff
    bool enable_pacing;            // Enable pacing
    uint64_t min_pacing_interval_ns; // Minimum interval between packets (nanoseconds)
};

/**
 * Pacing state
 */
struct PepperPacingState {
    uint64_t next_send_time_ns;    // Next allowed send time
    uint64_t tokens;                // Token bucket tokens
    uint64_t last_update_ns;        // Last token bucket update
    float loss_rate;                // Current loss rate estimate
    uint64_t rtt_ns;                // Current RTT estimate
    bool in_backoff;                // Currently in backoff mode
    uint64_t backoff_until_ns;      // Backoff end time
};

/**
 * Initialize pacing state
 */
void pepper_pacing_init(PepperPacingState* state, const PepperPacingParams* params);

/**
 * Check if packet can be sent now (pacing gate)
 * Returns true if packet can be sent, false if should wait
 */
bool pepper_pacing_can_send(PepperPacingState* state, const PepperPacingParams* params, 
                            size_t packet_size, uint64_t current_time_ns);

/**
 * Update pacing state after sending
 */
void pepper_pacing_update_after_send(PepperPacingState* state, const PepperPacingParams* params,
                                     size_t packet_size, uint64_t current_time_ns);

/**
 * Update loss and RTT estimates
 */
void pepper_pacing_update_metrics(PepperPacingState* state, float loss_rate, uint64_t rtt_ns);

/**
 * Get high-resolution timestamp (nanoseconds)
 */
uint64_t pepper_pacing_get_time_ns();

#endif // PEPPER_PACING_H
