/*
 * Lock-free ring buffer implementation for PepperShaper
 */

use std::sync::atomic::{AtomicU64, AtomicU32, Ordering};
use std::ptr;
use std::alloc::{Layout, alloc, dealloc};

const CACHE_LINE_SIZE: usize = 64;

/// Lock-free ring buffer with cache locality
pub struct PepperRingBuffer {
    write_pos: AtomicU64,
    write_seq: AtomicU32,
    read_pos: AtomicU64,
    read_seq: AtomicU32,
    capacity: usize,
    data: *mut u8,
}

impl PepperRingBuffer {
    /// Create a new ring buffer
    pub fn new(capacity: usize) -> Self {
        if capacity == 0 || capacity > 64 * 1024 * 1024 {
            panic!("Invalid capacity: {} (must be 1-67108864)", capacity);
        }

        // Allocate aligned memory
        let layout = Layout::from_size_align(capacity, CACHE_LINE_SIZE)
            .expect("Invalid layout");
        let data = unsafe { alloc(layout) };
        if data.is_null() {
            panic!("Failed to allocate ring buffer data");
        }

        Self {
            write_pos: AtomicU64::new(0),
            write_seq: AtomicU32::new(0),
            read_pos: AtomicU64::new(0),
            read_seq: AtomicU32::new(0),
            capacity,
            data,
        }
    }

    /// Enqueue data (lock-free)
    /// Returns bytes written, 0 if full
    pub fn enqueue(&self, data: &[u8]) -> usize {
        if data.is_empty() {
            return 0;
        }

        let write_pos = self.write_pos.load(Ordering::Relaxed);
        let write_seq = self.write_seq.load(Ordering::Acquire);
        let read_pos = self.read_pos.load(Ordering::Acquire);
        let read_seq = self.read_seq.load(Ordering::Acquire);

        // Calculate available space
        let available = if write_seq == read_seq {
            // Same generation
            if write_pos >= read_pos {
                self.capacity - (write_pos - read_pos) as usize
            } else {
                (read_pos - write_pos) as usize
            }
        } else {
            // Different generation (wrapped)
            self.capacity - (read_pos - (write_pos % self.capacity as u64)) as usize
        };

        // Reserve one byte to distinguish full from empty
        if available <= 1 {
            return 0; // Full
        }

        let to_write = (data.len().min(available - 1)).min(self.capacity - 1);
        let pos = (write_pos % self.capacity as u64) as usize;

        // Write data (may wrap around)
        let first_part = (pos + to_write).min(self.capacity) - pos;
        unsafe {
            ptr::copy_nonoverlapping(
                data.as_ptr(),
                self.data.add(pos),
                first_part,
            );
            if first_part < to_write {
                ptr::copy_nonoverlapping(
                    data.as_ptr().add(first_part),
                    self.data,
                    to_write - first_part,
                );
            }
        }

        // Update write position and sequence
        let new_write_pos = write_pos + to_write as u64;
        self.write_pos.store(new_write_pos, Ordering::Release);
        if new_write_pos / self.capacity as u64 > write_pos / self.capacity as u64 {
            self.write_seq.fetch_add(1, Ordering::Release);
        }

        to_write
    }

    /// Dequeue data (lock-free)
    /// Returns bytes read, 0 if empty
    pub fn dequeue(&self, data: &mut [u8]) -> usize {
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

        to_read
    }

    /// Get available space
    pub fn available(&self) -> usize {
        let write_pos = self.write_pos.load(Ordering::Relaxed);
        let write_seq = self.write_seq.load(Ordering::Acquire);
        let read_pos = self.read_pos.load(Ordering::Acquire);
        let read_seq = self.read_seq.load(Ordering::Acquire);

        let used = if write_seq == read_seq {
            if write_pos >= read_pos {
                (write_pos - read_pos) as usize
            } else {
                0
            }
        } else {
            self.capacity - (read_pos % self.capacity as u64) as usize
        };

        self.capacity - used - 1 // Reserve one byte
    }

    /// Get used space
    pub fn used(&self) -> usize {
        let write_pos = self.write_pos.load(Ordering::Relaxed);
        let write_seq = self.write_seq.load(Ordering::Acquire);
        let read_pos = self.read_pos.load(Ordering::Acquire);
        let read_seq = self.read_seq.load(Ordering::Acquire);

        if write_seq == read_seq {
            if write_pos >= read_pos {
                (write_pos - read_pos) as usize
            } else {
                0
            }
        } else {
            self.capacity - (read_pos % self.capacity as u64) as usize
        }
    }
}

impl Drop for PepperRingBuffer {
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

unsafe impl Send for PepperRingBuffer {}
unsafe impl Sync for PepperRingBuffer {}

