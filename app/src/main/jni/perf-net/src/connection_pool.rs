/*
 * Connection Pool (Rust Implementation)
 * Pre-allocated persistent sockets for zero handshake overhead
 */

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jint;
use parking_lot::Mutex;
use nix::sys::socket::{socket, AddressFamily, SockType, SockFlag, SockProtocol, connect, setsockopt};
use nix::sys::socket::sockopt::{ReuseAddr, KeepAlive};
use nix::unistd::close;
use std::os::unix::io::RawFd;
use std::os::fd::{AsRawFd, BorrowedFd};
use std::net::Ipv4Addr;
use log::{debug, error};

const MAX_POOL_SIZE: usize = 16;
const DEFAULT_POOL_SIZE: usize = 8;
const MIN_POOL_SIZE: usize = 4;

#[derive(Clone, Copy, PartialEq, Eq)]
enum PoolType {
    H2Stream = 0,
    Vision = 1,
    Reserve = 2,
}

impl From<jint> for PoolType {
    fn from(value: jint) -> Self {
        match value {
            0 => PoolType::H2Stream,
            1 => PoolType::Vision,
            2 => PoolType::Reserve,
            _ => PoolType::H2Stream,
        }
    }
}

struct ConnectionSlot {
    fd: Option<RawFd>,
    in_use: bool,
    connected: bool,
    remote_addr: String,
    remote_port: u16,
    pool_type: PoolType,
}

struct ConnectionPool {
    slots: Vec<ConnectionSlot>,
    initialized: bool,
}

impl ConnectionPool {
    fn new(size: usize, pool_type: PoolType) -> Self {
        let mut slots = Vec::with_capacity(size);
        for _ in 0..size {
            slots.push(ConnectionSlot {
                fd: None,
                in_use: false,
                connected: false,
                remote_addr: String::new(),
                remote_port: 0,
                pool_type,
            });
        }
        Self {
            slots,
            initialized: true,
        }
    }

    fn create_socket() -> Result<RawFd, nix::Error> {
        let fd = socket(
            AddressFamily::Inet,
            SockType::Stream,
            SockFlag::empty(),
            SockProtocol::Tcp,
        )?;

        // Set non-blocking
        // Set non-blocking using libc (fcntl may not be in nix 0.28 without fs feature)
        #[cfg(target_os = "android")]
        {
            use libc::{fcntl, F_GETFL, F_SETFL, O_NONBLOCK};
            let raw_fd = fd.as_raw_fd();
            let flags = unsafe { fcntl(raw_fd, F_GETFL) };
            if flags >= 0 {
                let _ = unsafe { fcntl(raw_fd, F_SETFL, flags | O_NONBLOCK) };
            }
        }
        #[cfg(not(target_os = "android"))]
        {
            use nix::fcntl::{fcntl, FcntlArg, OFlag};
            if let Ok(flags) = fcntl(fd, FcntlArg::F_GETFL) {
                let flags = OFlag::from_bits_truncate(flags);
                let _ = fcntl(fd, FcntlArg::F_SETFL(flags | OFlag::O_NONBLOCK));
            }
        }

        // Set socket options
        let _ = setsockopt(&fd, ReuseAddr, &true);
        // TCP_NODELAY using libc (TcpNoDelay may not be in nix 0.28)
        #[cfg(target_os = "android")]
        {
            use libc::{IPPROTO_TCP, TCP_NODELAY};
            let optval: i32 = 1;
            let raw_fd = fd.as_raw_fd();
            let _ = unsafe {
                libc::setsockopt(raw_fd, IPPROTO_TCP, TCP_NODELAY, &optval as *const _ as *const libc::c_void, std::mem::size_of::<i32>() as libc::socklen_t)
            };
        }
        #[cfg(not(target_os = "android"))]
        {
            use nix::sys::socket::sockopt::TcpNoDelay;
            let _ = setsockopt(&fd, TcpNoDelay, &true);
        }
        let _ = setsockopt(&fd, KeepAlive, &true);

        Ok(fd.as_raw_fd())
    }

    fn find_slot_by_fd(&self, fd: RawFd) -> Option<usize> {
        self.slots.iter().position(|s| s.fd == Some(fd))
    }
}

static POOLS: Mutex<[Option<ConnectionPool>; 3]> = Mutex::new([None, None, None]);
static POOL_SIZE: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(DEFAULT_POOL_SIZE);

/// Initialize connection pool
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeInitConnectionPool(
    _env: JNIEnv,
    _class: JClass,
    pool_size_per_type: jint,
) -> jint {
    let pool_size = pool_size_per_type as usize;
    let pool_size = pool_size.max(MIN_POOL_SIZE).min(MAX_POOL_SIZE);
    
    POOL_SIZE.store(pool_size, std::sync::atomic::Ordering::Release);

    // Distribute pool size: H2=40%, Vision=35%, Reserve=25%
    let h2_size = ((pool_size * 40) + 50) / 100;
    let vision_size = ((pool_size * 35) + 50) / 100;
    let reserve_size = pool_size - h2_size - vision_size;

    let pool_sizes = [h2_size.max(1), vision_size.max(1), reserve_size.max(1)];

    let mut pools = POOLS.lock();
    for (i, size) in pool_sizes.iter().enumerate() {
        let pool_type = match i {
            0 => PoolType::H2Stream,
            1 => PoolType::Vision,
            2 => PoolType::Reserve,
            _ => continue,
        };
        pools[i] = Some(ConnectionPool::new(*size, pool_type));
        debug!("Pool {} initialized with {} slots", i, size);
    }

    0
}

/// Get a socket from pool
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeGetPooledSocket(
    _env: JNIEnv,
    _class: JClass,
    pool_type: jint,
) -> jint {
    if pool_type < 0 || pool_type >= 3 {
        error!("Invalid pool type: {}", pool_type);
        return -1;
    }

    let mut pools = POOLS.lock();
    let pool = match &mut pools[pool_type as usize] {
        Some(p) => p,
        None => {
            error!("Pool {} not initialized", pool_type);
            return -1;
        }
    };

    // Find available slot
    for slot in &mut pool.slots {
        if !slot.in_use {
            if slot.fd.is_none() {
                match ConnectionPool::create_socket() {
                    Ok(fd) => slot.fd = Some(fd),
                    Err(e) => {
                        error!("Failed to create socket: {}", e);
                        return -1;
                    }
                }
            }

            slot.in_use = true;
            slot.connected = false;
            debug!("Got socket from pool {}, fd={:?}", pool_type, slot.fd);
            return slot.fd.unwrap() as jint;
        }
    }

    error!("Pool {} exhausted", pool_type);
    -1
}

/// Get slot index for a given file descriptor
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeGetPooledSocketSlotIndex(
    _env: JNIEnv,
    _class: JClass,
    pool_type: jint,
    fd: jint,
) -> jint {
    if pool_type < 0 || pool_type >= 3 || fd < 0 {
        return -1;
    }

    let pools = POOLS.lock();
    let pool = match &pools[pool_type as usize] {
        Some(p) => p,
        None => return -1,
    };

    match pool.find_slot_by_fd(fd as RawFd) {
        Some(idx) => idx as jint,
        None => -1,
    }
}

/// Connect pooled socket
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeConnectPooledSocket(
    env: JNIEnv,
    _class: JClass,
    pool_type: jint,
    slot_index: jint,
    host: JString,
    port: jint,
) -> jint {
    if pool_type < 0 || pool_type >= 3 || slot_index < 0 || port < 0 || port > 65535 {
        error!("Invalid parameters");
        return -1;
    }

    let host_str = match env.get_string(&host) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => {
            error!("Failed to get host string");
            return -1;
        }
    };

    if host_str.is_empty() || host_str.len() > 255 {
        error!("Invalid host string length: {}", host_str.len());
        return -1;
    }

    let mut pools = POOLS.lock();
    let pool = match &mut pools[pool_type as usize] {
        Some(p) => p,
        None => {
            error!("Pool {} not initialized", pool_type);
            return -1;
        }
    };

    if slot_index as usize >= pool.slots.len() {
        error!("Invalid slot index: {}", slot_index);
        return -1;
    }

    let slot = &mut pool.slots[slot_index as usize];
    let fd = match slot.fd {
        Some(f) if slot.in_use => f,
        _ => {
            error!("Slot {} not in use or invalid fd", slot_index);
            return -1;
        }
    };

    // Check if already connected to same host:port
    if slot.connected && slot.remote_addr == host_str && slot.remote_port == port as u16 {
        debug!("Socket already connected to {}:{}, reusing", host_str, port);
        return 0;
    }

    // Disconnect if connected to different host
    if slot.connected {
        let _ = nix::sys::socket::shutdown(fd, nix::sys::socket::Shutdown::Both);
        slot.connected = false;
    }

    // Connect - resolve hostname to IP
    let ip_addr = match host_str.parse::<Ipv4Addr>() {
        Ok(ip) => ip,
        Err(_) => {
            // Try DNS resolution (simplified - in production use proper DNS)
            error!("DNS resolution not implemented, use IP address: {}", host_str);
            return -1;
        }
    };

    // Convert to nix::SockaddrIn for connect
    use nix::sys::socket::SockaddrIn;
    let octets = ip_addr.octets();
    let sockaddr = SockaddrIn::new(octets[0], octets[1], octets[2], octets[3], port as u16);

    // connect expects RawFd
    let borrowed_fd = unsafe { BorrowedFd::borrow_raw(fd) };
    match connect(borrowed_fd.as_raw_fd(), &sockaddr) {
        Ok(_) => {
            slot.connected = true;
            slot.remote_addr = host_str;
            slot.remote_port = port as u16;
            debug!("Pooled socket connected: {}:{}", slot.remote_addr, port);
            0
        }
        Err(nix::errno::Errno::EINPROGRESS) => {
            slot.connected = false;
            slot.remote_addr = host_str;
            slot.remote_port = port as u16;
            debug!("Pooled socket connecting (non-blocking): {}:{}", host_str, port);
            0
        }
        Err(e) => {
            error!("Connect failed for {}:{}: {}", host_str, port, e);
            -1
        }
    }
}

/// Connect pooled socket by file descriptor
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeConnectPooledSocketByFd(
    env: JNIEnv,
    _class: JClass,
    pool_type: jint,
    fd: jint,
    host: JString,
    port: jint,
) -> jint {
    if pool_type < 0 || pool_type >= 3 || fd < 0 || port < 0 || port > 65535 {
        return -1;
    }

    let host_str = match env.get_string(&host) {
        Ok(s) => s.to_string_lossy().to_string(),
        Err(_) => return -1,
    };

    let mut pools = POOLS.lock();
    let pool = match &mut pools[pool_type as usize] {
        Some(p) => p,
        None => return -1,
    };

    let slot_idx = match pool.find_slot_by_fd(fd as RawFd) {
        Some(idx) => idx,
        None => return -1,
    };

    let slot = &mut pool.slots[slot_idx];
    if !slot.in_use {
        return -1;
    }

    if slot.connected && slot.remote_addr == host_str && slot.remote_port == port as u16 {
        return 0;
    }

    let ip_addr = match host_str.parse::<Ipv4Addr>() {
        Ok(ip) => ip,
        Err(_) => return -1,
    };

    // Convert to nix::SockaddrIn for connect
    use nix::sys::socket::SockaddrIn;
    let octets = ip_addr.octets();
    let sockaddr = SockaddrIn::new(octets[0], octets[1], octets[2], octets[3], port as u16);

    // Convert RawFd to BorrowedFd for connect
    let borrowed_fd = unsafe { BorrowedFd::borrow_raw(fd as RawFd) };
    match connect(borrowed_fd.as_raw_fd(), &sockaddr) {
        Ok(_) => {
            slot.connected = true;
            slot.remote_addr = host_str;
            slot.remote_port = port as u16;
            0
        }
        Err(nix::errno::Errno::EINPROGRESS) => {
            slot.connected = false;
            slot.remote_addr = host_str;
            slot.remote_port = port as u16;
            0
        }
        Err(_) => -1,
    }
}

/// Return socket to pool
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeReturnPooledSocket(
    _env: JNIEnv,
    _class: JClass,
    pool_type: jint,
    slot_index: jint,
) {
    if pool_type < 0 || pool_type >= 3 || slot_index < 0 {
        return;
    }

    let mut pools = POOLS.lock();
    let pool = match &mut pools[pool_type as usize] {
        Some(p) => p,
        None => return,
    };

    if (slot_index as usize) < pool.slots.len() {
        let slot = &mut pool.slots[slot_index as usize];
        slot.in_use = false;
        debug!("Returned socket to pool {}, slot {}", pool_type, slot_index);
    }
}

/// Return socket to pool by file descriptor
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeReturnPooledSocketByFd(
    _env: JNIEnv,
    _class: JClass,
    pool_type: jint,
    fd: jint,
) {
    if pool_type < 0 || pool_type >= 3 || fd < 0 {
        return;
    }

    let mut pools = POOLS.lock();
    let pool = match &mut pools[pool_type as usize] {
        Some(p) => p,
        None => return,
    };

    if let Some(slot_idx) = pool.find_slot_by_fd(fd as RawFd) {
        pool.slots[slot_idx].in_use = false;
        debug!("Returned socket to pool {} by fd {}", pool_type, fd);
    }
}

/// Destroy connection pool
#[no_mangle]
pub extern "system" fn Java_com_simplexray_an_performance_PerformanceManager_nativeDestroyConnectionPool(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut pools = POOLS.lock();
    for (i, pool) in pools.iter_mut().enumerate() {
        if let Some(p) = pool.take() {
            for slot in p.slots {
                if let Some(fd) = slot.fd {
                    let _ = close(fd);
                }
            }
            debug!("Pool {} destroyed", i);
        }
    }
}
