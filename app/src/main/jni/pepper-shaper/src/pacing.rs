/*
 * High-resolution pacing implementation for PepperShaper
 * Token bucket with loss-aware backoff
 */

use std::time::{SystemTime, UNIX_EPOCH};

/// Pacing parameters
#[derive(Clone)]
pub struct PepperPacingParams {
    #[allow(dead_code)]
    pub target_rate_bps: u64,      // Target rate in bits per second (0 = unlimited)
    pub max_burst_bytes: u64,      // Maximum burst size in bytes
    #[allow(dead_code)]
    pub loss_aware_backoff: bool,   // Enable loss-aware backoff
    #[allow(dead_code)]
    pub enable_pacing: bool,        // Enable pacing
    #[allow(dead_code)]
    pub min_pacing_interval_ns: u64, // Minimum interval between packets (nanoseconds)
}

/// Pacing state
#[allow(dead_code)]
pub struct PepperPacingState {
    next_send_time_ns: u64,    // Next allowed send time
    tokens: u64,                // Token bucket tokens
    last_update_ns: u64,        // Last token bucket update
    loss_rate: f32,             // Current loss rate estimate
    rtt_ns: u64,                // Current RTT estimate
    in_backoff: bool,           // Currently in backoff mode
    backoff_until_ns: u64,      // Backoff end time
}

impl PepperPacingState {
    pub fn new(params: &PepperPacingParams) -> Self {
        let now_ns = get_time_ns();
        Self {
            next_send_time_ns: now_ns,
            tokens: params.max_burst_bytes,
            last_update_ns: now_ns,
            loss_rate: 0.0,
            rtt_ns: 0,
            in_backoff: false,
            backoff_until_ns: 0,
        }
    }
}

/// Check if packet can be sent now (pacing gate)
#[allow(dead_code)]
pub fn can_send(
    state: &mut PepperPacingState,
    params: &PepperPacingParams,
    packet_size: usize,
    current_time_ns: u64,
) -> bool {
    if !params.enable_pacing {
        return true; // Pacing disabled
    }

    // Check backoff
    if state.in_backoff && current_time_ns < state.backoff_until_ns {
        return false;
    }
    state.in_backoff = false;

    // Check time-based pacing
    if current_time_ns < state.next_send_time_ns {
        return false;
    }

    // Token bucket check
    if params.target_rate_bps > 0 {
        // Refill tokens based on elapsed time
        let elapsed_ns = current_time_ns.saturating_sub(state.last_update_ns);
        let tokens_to_add = (elapsed_ns * params.target_rate_bps) / (8 * 1_000_000_000);
        
        state.tokens = state.tokens
            .saturating_add(tokens_to_add)
            .min(params.max_burst_bytes);
        state.last_update_ns = current_time_ns;

        // Check if we have enough tokens
        if state.tokens < packet_size as u64 {
            return false;
        }
    }

    true
}

/// Update pacing state after sending
#[allow(dead_code)]
pub fn update_after_send(
    state: &mut PepperPacingState,
    params: &PepperPacingParams,
    packet_size: usize,
    current_time_ns: u64,
) {
    // Consume tokens
    if params.target_rate_bps > 0 {
        if state.tokens >= packet_size as u64 {
            state.tokens -= packet_size as u64;
        } else {
            state.tokens = 0;
        }
    }

    // Update next send time based on rate
    if params.target_rate_bps > 0 {
        // Calculate interval: packet_size * 8 bits / rate_bps = seconds
        let interval_ns = ((packet_size as u64) * 8 * 1_000_000_000) / params.target_rate_bps;
        state.next_send_time_ns = current_time_ns + interval_ns.max(params.min_pacing_interval_ns);
    } else {
        // No rate limit, just respect min interval
        state.next_send_time_ns = current_time_ns + params.min_pacing_interval_ns;
    }

    // Loss-aware backoff
    if params.loss_aware_backoff && state.loss_rate > 0.1 {
        // Exponential backoff based on loss rate
        let backoff_factor = 1.0 + (state.loss_rate * 10.0);
        let rtt = if state.rtt_ns > 0 { state.rtt_ns } else { 100_000_000 }; // Use RTT or 100ms default
        let backoff_duration_ns = (rtt as f32 * backoff_factor) as u64;
        state.backoff_until_ns = current_time_ns + backoff_duration_ns;
        state.in_backoff = true;
    }
}

/// Update loss and RTT estimates
#[allow(dead_code)]
pub fn update_metrics(state: &mut PepperPacingState, loss_rate: f32, rtt_ns: u64) {
    state.loss_rate = loss_rate;
    state.rtt_ns = rtt_ns;
}

/// Get high-resolution timestamp (nanoseconds)
pub fn get_time_ns() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos() as u64
}

