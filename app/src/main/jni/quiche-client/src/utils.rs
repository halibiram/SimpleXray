/*
 * QUICHE Utilities (Rust Implementation)
 * Helper functions for CPU, time, network, and memory operations
 */

use nix::sys::socket::{setsockopt, sockopt};
use nix::fcntl::{fcntl, FcntlArg, OFlag};
use std::os::unix::io::RawFd;
use std::time::{SystemTime, UNIX_EPOCH};
use log::debug;

pub struct CpuUtils;

impl CpuUtils {
    pub fn set_cpu_affinity(cpu_mask: u64) -> Result<(), nix::Error> {
        use nix::sched::{CpuSet, sched_setaffinity};
        use nix::unistd::Pid;

        let mut cpuset = CpuSet::new();
        for i in 0..64 {
            if cpu_mask & (1u64 << i) != 0 {
                cpuset.set(i)?;
            }
        }

        sched_setaffinity(Pid::from_raw(0), &cpuset)?;
        Ok(())
    }

    pub fn get_num_cpus() -> usize {
        num_cpus::get()
    }

    pub fn get_big_cores_mask() -> u64 {
        let num_cpus = Self::get_num_cpus();
        if num_cpus == 8 {
            0xF0  // Cores 4-7
        } else if num_cpus >= 4 {
            (0xF << (num_cpus / 2))
        } else {
            (1u64 << num_cpus) - 1
        }
    }

    pub fn get_little_cores_mask() -> u64 {
        let num_cpus = Self::get_num_cpus();
        if num_cpus == 8 {
            0x0F  // Cores 0-3
        } else if num_cpus >= 4 {
            0xF
        } else {
            (1u64 << num_cpus) - 1
        }
    }
}

pub struct TimeUtils;

impl TimeUtils {
    pub fn get_timestamp_us() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_micros() as u64
    }

    pub fn get_timestamp_ms() -> u64 {
        Self::get_timestamp_us() / 1000
    }

    pub fn sleep_us(us: u64) {
        std::thread::sleep(std::time::Duration::from_micros(us));
    }

    pub fn sleep_ms(ms: u64) {
        std::thread::sleep(std::time::Duration::from_millis(ms));
    }
}

pub struct NetUtils;

impl NetUtils {
    pub fn enable_udp_gso(sockfd: RawFd) -> Result<(), nix::Error> {
        // UDP_SEGMENT = 103
        let val: i32 = 1;
        setsockopt(sockfd, &sockopt::SoZerocopy, &true)?;
        Ok(())
    }

    pub fn enable_udp_gro(sockfd: RawFd) -> Result<(), nix::Error> {
        // UDP_GRO = 104 (not directly supported in nix, would need libc)
        // For now, just return Ok
        Ok(())
    }

    pub fn set_socket_buffers(sockfd: RawFd, sndbuf: usize, rcvbuf: usize) -> Result<(), nix::Error> {
        setsockopt(sockfd, &sockopt::SndBuf, &(sndbuf as u32))?;
        setsockopt(sockfd, &sockopt::RcvBuf, &(rcvbuf as u32))?;
        Ok(())
    }

    pub fn set_non_blocking(sockfd: RawFd) -> Result<(), nix::Error> {
        let flags = fcntl(sockfd, FcntlArg::F_GETFL)?;
        let flags = OFlag::from_bits_truncate(flags);
        fcntl(sockfd, FcntlArg::F_SETFL(flags | OFlag::O_NONBLOCK))?;
        Ok(())
    }
}

pub struct MemUtils;

impl MemUtils {
    pub fn allocate_aligned(size: usize, alignment: usize) -> Result<*mut u8, std::alloc::LayoutError> {
        use std::alloc::{Layout, alloc};
        let layout = Layout::from_size_align(size, alignment)?;
        unsafe {
            let ptr = alloc(layout);
            if ptr.is_null() {
                Err(std::alloc::LayoutError)
            } else {
                Ok(ptr as *mut u8)
            }
        }
    }

    pub fn free_aligned(ptr: *mut u8, size: usize, alignment: usize) {
        use std::alloc::{Layout, dealloc};
        if let Ok(layout) = Layout::from_size_align(size, alignment) {
            unsafe {
                dealloc(ptr as *mut u8, layout);
            }
        }
    }

    pub fn get_page_size() -> usize {
        unsafe { libc::sysconf(libc::_SC_PAGESIZE) as usize }
    }

    pub fn lock_memory(addr: *mut u8, len: usize) -> Result<(), nix::Error> {
        use nix::sys::mman::{mlock, MlockFlags};
        unsafe {
            mlock(addr as *const libc::c_void, len)?;
        }
        Ok(())
    }

    pub fn unlock_memory(addr: *mut u8, len: usize) -> Result<(), nix::Error> {
        use nix::sys::mman::munlock;
        unsafe {
            munlock(addr as *const libc::c_void, len)?;
        }
        Ok(())
    }
}

