/*
 * Performance Network Module (Rust Implementation)
 * High-performance networking optimizations
 */

mod cpu_affinity;
mod zero_copy;
mod connection_pool;
mod crypto_accel;
mod epoll_loop;
mod kernel_pacing;
mod mtu_tuning;
mod quic_optimizer;
mod ring_buffer;
mod jit_warmup;
mod readahead;
mod tcp_fastopen;
mod qos;
mod mmap_batch;
mod tls_session;
mod tls_evasion;
mod tls_keylog;
mod tls_handshake;
mod cert_verifier;
mod quic_handshake;
mod jni_bridge;

// Re-export modules for JNI
pub use cpu_affinity::*;
pub use zero_copy::*;
pub use connection_pool::*;
pub use crypto_accel::*;
pub use epoll_loop::*;
pub use kernel_pacing::*;
pub use mtu_tuning::*;
pub use quic_optimizer::*;
pub use ring_buffer::*;
pub use jit_warmup::*;
pub use readahead::*;
pub use tcp_fastopen::*;
pub use qos::*;
pub use mmap_batch::*;
pub use tls_session::*;
pub use tls_evasion::*;
pub use tls_keylog::*;
pub use tls_handshake::*;
pub use cert_verifier::*;
pub use quic_handshake::*;
pub use jni_bridge::*;

