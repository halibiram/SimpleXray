/*
 * Map/Unmap Batching (Rust Implementation)
 * Reduces syscall overhead by batching memory operations
 */

use jni::JNIEnv;
use jni::objects::{JClass, JLongArray};
use jni::sys::{jint, jlong};
use log::debug;
use nix::sys::mman::munmap;
use parking_lot::Mutex;
use std::collections::HashMap;

struct MappedRegion {
    ptr: *mut libc::c_void,
    size: usize,
}

struct MMapBatch {
    mapped_regions: Mutex<HashMap<*mut libc::c_void, usize>>,
    total_mapped: Mutex<usize>,
}

/// Initialize batch mapper
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeInitBatchMapper(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let batch = Box::new(MMapBatch {
        mapped_regions: Mutex::new(HashMap::new()),
        total_mapped: Mutex::new(0),
    });

    debug!("Batch mapper initialized");
    Box::into_raw(batch) as jlong
}

/// Batch map memory regions
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeBatchMap(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    size: jlong,
) -> jlong {
    if handle == 0 || size <= 0 {
        return 0;
    }

    let batch = unsafe { &*(handle as *const MMapBatch) };
    let size = size as usize;

    // Map memory
    
    // Use libc::mmap directly for anonymous mapping since nix 0.28 requires AsFd
    let ptr = unsafe {
        let addr = libc::mmap(
            std::ptr::null_mut(),
            size,
            libc::PROT_READ | libc::PROT_WRITE,
            libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
            -1,
            0,
        );
        if addr == libc::MAP_FAILED {
            Err(nix::errno::Errno::last())
        } else {
            Ok(std::ptr::NonNull::new(addr as *mut u8).unwrap())
        }
    };

    match ptr {
        Ok(addr) => {
            let mut regions = batch.mapped_regions.lock();
            let mut total = batch.total_mapped.lock();
            // Convert NonNull to *mut for storage
            let addr_ptr = addr.as_ptr() as *mut libc::c_void;
            regions.insert(addr_ptr, size);
            *total += size;
            debug!("Mapped {} bytes, total: {}", size, *total);
            addr_ptr as jlong
        }
        Err(_) => 0,
    }
}

/// Batch unmap memory regions
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeBatchUnmap(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    addresses: JLongArray,
    sizes: JLongArray,
) -> jint {
    if handle == 0 {
        return -1;
    }

    let batch = unsafe { &*(handle as *const MMapBatch) };

    let addr_len = match env.get_array_length(&addresses) {
        Ok(len) => len,
        Err(_) => return -1,
    };

    let size_len = match env.get_array_length(&sizes) {
        Ok(len) => len,
        Err(_) => return -1,
    };

    if addr_len != size_len {
        return -1;
    }

    let addrs = unsafe {
        match env.get_array_elements(&addresses, jni::objects::ReleaseMode::NoCopyBack) {
            Ok(arr) => arr,
            Err(_) => return -1,
        }
    };

    let lens = unsafe {
        match env.get_array_elements(&sizes, jni::objects::ReleaseMode::NoCopyBack) {
            Ok(arr) => arr,
            Err(_) => {
                drop(addrs);
                return -1;
            }
        }
    };

    let mut unmapped = 0;
    let mut regions = batch.mapped_regions.lock();
    let mut total = batch.total_mapped.lock();

    for i in 0..addr_len {
        let ptr_val = unsafe { *addrs.get_unchecked(i as usize) };
        let ptr = ptr_val as *mut libc::c_void;
        let len_val = unsafe { *lens.get_unchecked(i as usize) };
        let len = len_val as usize;

        // Convert *mut to NonNull for munmap
        if let Some(ptr_nonnull) = std::ptr::NonNull::new(ptr) {
            if let Ok(_) = unsafe { munmap(ptr_nonnull, len) } {
                unmapped += 1;
                if let Some(size) = regions.remove(&ptr) {
                    *total -= size;
                }
            }
        }
    }

    drop(addrs);
    drop(lens);

    debug!("Unmapped {} regions", unmapped);
    unmapped
}

/// Destroy batch mapper and unmap all
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeDestroyBatchMapper(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }

    let batch = unsafe { Box::from_raw(handle as *mut MMapBatch) };
    let regions = batch.mapped_regions.lock();

    // Unmap all remaining regions
    for (&ptr, &size) in regions.iter() {
        if let Some(ptr_nonnull) = std::ptr::NonNull::new(ptr) {
            let _ = unsafe { munmap(ptr_nonnull, size) };
        }
    }

    drop(regions);
    *batch.total_mapped.lock() = 0;

    debug!("Batch mapper destroyed");
}




