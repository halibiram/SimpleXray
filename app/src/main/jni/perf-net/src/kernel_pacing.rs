/*
 * Kernel Pacing (Rust Implementation)
 * SO_MAX_PACING_RATE and packet pacing using socket2
 */

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray};
use jni::sys::{jint, jlong};
use std::sync::Arc;
use parking_lot::Mutex;
use std::collections::{VecDeque, HashMap};
use std::thread;
use std::time::{Duration, Instant};
use log::{debug, error};

struct PacingPacket {
    data: Vec<u8>,
    fd: i32,
    timestamp: Instant,
}

struct PacingFIFO {
    queue: VecDeque<PacingPacket>,
    max_size: usize,
    running: Arc<std::sync::atomic::AtomicBool>,
}

static PACING_FIFOS: Mutex<HashMap<u64, Arc<Mutex<PacingFIFO>>>> = Mutex::new(std::collections::HashMap::new());
static NEXT_FIFO_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

/// Initialize internal pacing FIFO
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeInitPacingFIFO(
    _env: JNIEnv,
    _class: JClass,
    max_size: jint,
) -> jlong {
    let fifo_id = NEXT_FIFO_ID.fetch_add(1, std::sync::atomic::Ordering::AcqRel);
    let fifo = Arc::new(Mutex::new(PacingFIFO {
        queue: VecDeque::new(),
        max_size: max_size as usize,
        running: Arc::new(std::sync::atomic::AtomicBool::new(false)),
    }));

    let mut fifos = PACING_FIFOS.lock();
    fifos.insert(fifo_id, fifo.clone());

    debug!("Pacing FIFO initialized, max_size={}, id={}", max_size, fifo_id);
    fifo_id as jlong
}

/// Enqueue packet for pacing
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeEnqueuePacket(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    fd: jint,
    data: JByteArray,
    offset: jint,
    length: jint,
) -> jint {
    if handle == 0 || fd < 0 || length < 0 || offset < 0 {
        return -1;
    }

    let fifos = PACING_FIFOS.lock();
    let fifo = match fifos.get(&(handle as u64)) {
        Some(f) => f.clone(),
        None => return -1,
    };

    let mut fifo = fifo.lock();
    if fifo.queue.len() >= fifo.max_size {
        return -1; // Queue full
    }

    let array_length = match env.get_array_length(&data) {
        Ok(len) => len,
        Err(_) => return -1,
    };

    if offset + length > array_length {
        return -1;
    }

    let mut packet_data = vec![0u8; length as usize];
    let src = match env.get_array_elements(&data, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(elems) => elems,
        Err(_) => return -1,
    };

    unsafe {
        std::ptr::copy_nonoverlapping(
            src.as_ptr().add(offset as usize),
            packet_data.as_mut_ptr(),
            length as usize,
        );
    }

    drop(src);

    fifo.queue.push_back(PacingPacket {
        data: packet_data,
        fd,
        timestamp: Instant::now(),
    });

    0
}

/// Start pacing worker thread
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeStartPacing(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return -1;
    }

    let fifos = PACING_FIFOS.lock();
    let fifo = match fifos.get(&(handle as u64)) {
        Some(f) => f.clone(),
        None => return -1,
    };

    let running = fifo.lock().running.clone();
    if running.swap(true, std::sync::atomic::Ordering::AcqRel) {
        return 0; // Already running
    }

    let fifo_clone = fifo.clone();
    thread::spawn(move || {
        const BATCH_SIZE: usize = 16;
        const INTERVAL_MS: u64 = 1;

        while running.load(std::sync::atomic::Ordering::Acquire) {
            let mut batch = Vec::new();
            {
                let mut fifo = fifo_clone.lock();
                for _ in 0..BATCH_SIZE.min(fifo.queue.len()) {
                    if let Some(packet) = fifo.queue.pop_front() {
                        batch.push(packet);
                    }
                }
            }

            // Process batch
            for packet in batch {
                use nix::sys::socket::send;
                use nix::sys::socket::MsgFlags;
                let _ = send(
                    packet.fd as std::os::unix::io::RawFd,
                    &packet.data,
                    MsgFlags::MSG_DONTWAIT | MsgFlags::MSG_NOSIGNAL,
                );
            }

            thread::sleep(Duration::from_millis(INTERVAL_MS));
        }
    });

    debug!("Pacing worker started");
    0
}

/// Destroy pacing FIFO
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeDestroyPacingFIFO(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }

    let mut fifos = PACING_FIFOS.lock();
    if let Some(fifo) = fifos.remove(&(handle as u64)) {
        fifo.lock().running.store(false, std::sync::atomic::Ordering::Release);
        debug!("Pacing FIFO destroyed");
    }
}
