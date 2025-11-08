/*
 * Lock-free Ring Buffer with Cache Locality (Rust Implementation)
 * Optimized for L1 cache hits
 */

use jni::JNIEnv;
use jni::objects::{JClass, JByteArray};
use jni::sys::{jint, jlong};
use std::sync::atomic::{AtomicU64, AtomicU32, Ordering};
use std::ptr;
use std::alloc::{Layout, alloc, dealloc};
use log::{debug, error};

const CACHE_LINE_SIZE: usize = 64;

/// Lock-free ring buffer with cache locality
struct RingBuffer {
    write_pos: AtomicU64,
    write_seq: AtomicU32,
    read_pos: AtomicU64,
    read_seq: AtomicU32,
    capacity: usize,
    data: *mut u8,
}

impl RingBuffer {
    fn new(capacity: usize) -> Option<Box<Self>> {
        if capacity <= 0 || capacity > 64 * 1024 * 1024 {
            error!("Invalid capacity: {} (must be 1-67108864)", capacity);
            return None;
        }

        // Allocate aligned memory
        let layout = Layout::from_size_align(capacity, CACHE_LINE_SIZE)
            .ok()?;
        let data = unsafe { alloc(layout) };
        if data.is_null() {
            error!("Failed to allocate ring buffer data: {} bytes", capacity);
            return None;
        }

        Some(Box::new(Self {
            write_pos: AtomicU64::new(0),
            write_seq: AtomicU32::new(0),
            read_pos: AtomicU64::new(0),
            read_seq: AtomicU32::new(0),
            capacity,
            data,
        }))
    }

    fn write(&self, data: &[u8]) -> i32 {
        if data.is_empty() {
            return 0;
        }

        let write_pos = self.write_pos.load(Ordering::Relaxed);
        let write_seq = self.write_seq.load(Ordering::Acquire);
        let read_pos = self.read_pos.load(Ordering::Acquire);
        let read_seq = self.read_seq.load(Ordering::Acquire);

        // Calculate used space with sequence-aware logic
        let used = if write_seq == read_seq {
            // Same generation
            if write_pos >= read_pos {
                (write_pos - read_pos) as usize
            } else {
                // Wrapped within same generation
                self.capacity - (read_pos - write_pos) as usize
            }
        } else {
            // Different generations - buffer has wrapped
            self.capacity - (read_pos - (write_pos % self.capacity as u64)) as usize
        };

        if used > self.capacity {
            return -1;
        }

        let available = self.capacity - used;
        let length = data.len().min(available);

        if length == 0 {
            return 0; // Buffer full
        }

        let pos = (write_pos % self.capacity as u64) as usize;

        // Write data (may wrap around)
        let first_part = (pos + length).min(self.capacity) - pos;
        unsafe {
            ptr::copy_nonoverlapping(
                data.as_ptr(),
                self.data.add(pos),
                first_part,
            );
            if first_part < length {
                ptr::copy_nonoverlapping(
                    data.as_ptr().add(first_part),
                    self.data,
                    length - first_part,
                );
            }
        }

        // Update write position and sequence
        let new_write_pos = write_pos + length as u64;
        self.write_pos.store(new_write_pos, Ordering::Release);
        if new_write_pos / self.capacity as u64 > write_pos / self.capacity as u64 {
            self.write_seq.fetch_add(1, Ordering::Release);
        }

        length as i32
    }

    fn read(&self, data: &mut [u8]) -> i32 {
        if data.is_empty() {
            return 0;
        }

        let read_pos = self.read_pos.load(Ordering::Relaxed);
        let read_seq = self.read_seq.load(Ordering::Acquire);
        let write_pos = self.write_pos.load(Ordering::Acquire);
        let write_seq = self.write_seq.load(Ordering::Acquire);

        // Calculate used space
        let used = if write_seq == read_seq {
            // Same generation
            if write_pos >= read_pos {
                (write_pos - read_pos) as usize
            } else {
                0 // Empty
            }
        } else {
            // Different generation (wrapped)
            self.capacity - (read_pos % self.capacity as u64) as usize
        };

        if used == 0 {
            return 0; // Empty
        }

        let to_read = data.len().min(used);
        let pos = (read_pos % self.capacity as u64) as usize;

        // Read data (may wrap around)
        let first_part = (pos + to_read).min(self.capacity) - pos;
        unsafe {
            ptr::copy_nonoverlapping(
                self.data.add(pos),
                data.as_mut_ptr(),
                first_part,
            );
            if first_part < to_read {
                ptr::copy_nonoverlapping(
                    self.data,
                    data.as_mut_ptr().add(first_part),
                    to_read - first_part,
                );
            }
        }

        // Update read position and sequence
        let new_read_pos = read_pos + to_read as u64;
        self.read_pos.store(new_read_pos, Ordering::Release);
        if new_read_pos / self.capacity as u64 > read_pos / self.capacity as u64 {
            self.read_seq.fetch_add(1, Ordering::Release);
        }

        to_read as i32
    }
}

impl Drop for RingBuffer {
    fn drop(&mut self) {
        if !self.data.is_null() {
            let layout = Layout::from_size_align(self.capacity, CACHE_LINE_SIZE)
                .expect("Invalid layout");
            unsafe {
                dealloc(self.data, layout);
            }
        }
    }
}

/// Create ring buffer
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeCreateRingBuffer(
    _env: JNIEnv,
    _class: JClass,
    capacity: jint,
) -> jlong {
    match RingBuffer::new(capacity as usize) {
        Some(rb) => {
            debug!("Ring buffer created: capacity={}", capacity);
            Box::into_raw(Box::new(rb)) as jlong
        }
        None => 0,
    }
}

/// Write to ring buffer (lock-free)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRingBufferWrite(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    offset: jint,
    length: jint,
) -> jint {
    if handle == 0 || length < 0 || offset < 0 {
        error!("Invalid parameters: handle={}, offset={}, length={}", handle, offset, length);
        return -1;
    }

    let rb = unsafe { &*(handle as *const RingBuffer) };

    let array_length = match env.get_array_length(&data) {
        Ok(len) => len,
        Err(_) => {
            error!("Failed to get array length");
            return -1;
        }
    };

    if offset + length > array_length {
        error!("Array bounds exceeded: offset={}, length={}, array_size={}", 
               offset, length, array_length);
        return -1;
    }

    let mut src = match env.get_array_elements(&data, jni::objects::ReleaseMode::NoCopyBack) {
        Ok(elems) => elems,
        Err(_) => {
            error!("Failed to get byte array elements");
            return -1;
        }
    };

    let result = rb.write(unsafe {
        std::slice::from_raw_parts(
            src.as_ptr().add(offset as usize),
            length as usize,
        )
    });

    drop(src);
    result
}

/// Read from ring buffer (lock-free)
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeRingBufferRead(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    offset: jint,
    length: jint,
) -> jint {
    if handle == 0 || length < 0 || offset < 0 {
        error!("Invalid parameters: handle={}, offset={}, length={}", handle, offset, length);
        return -1;
    }

    let rb = unsafe { &*(handle as *const RingBuffer) };

    let array_length = match env.get_array_length(&data) {
        Ok(len) => len,
        Err(_) => {
            error!("Failed to get array length");
            return -1;
        }
    };

    if offset + length > array_length {
        error!("Array bounds exceeded: offset={}, length={}, array_size={}", 
               offset, length, array_length);
        return -1;
    }

    let mut dst = match env.get_array_elements(&data, jni::objects::ReleaseMode::CopyBack) {
        Ok(elems) => elems,
        Err(_) => {
            error!("Failed to get byte array elements");
            return -1;
        }
    };

    let result = rb.read(unsafe {
        std::slice::from_raw_parts_mut(
            dst.as_ptr().add(offset as usize),
            length as usize,
        )
    });

    drop(dst);
    result
}

/// Destroy ring buffer
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeDestroyRingBuffer(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            let _ = Box::from_raw(handle as *mut RingBuffer);
        }
    }
}




