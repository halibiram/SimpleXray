# SimpleXray Rust Geçiş Planı

**Tarih:** 8 Kasım 2025
**Versiyon:** 1.0
**Mevcut Proje Durumu:** v1.10.192 (Kotlin + C++ + Native Binary)

---

## İçindekiler

1. [Yönetici Özeti](#yönetici-özeti)
2. [Mevcut Durum Analizi](#mevcut-durum-analizi)
3. [Rust Geçiş Stratejisi](#rust-geçiş-stratejisi)
4. [Teknik Yaklaşım](#teknik-yaklaşım)
5. [Aşamalı Geçiş Planı](#aşamalı-geçiş-planı)
6. [Araçlar ve Kütüphaneler](#araçlar-ve-kütüphaneler)
7. [Risk Analizi](#risk-analizi)
8. [Zaman ve Kaynak Tahmini](#zaman-ve-kaynak-tahmini)
9. [Başarı Kriterleri](#başarı-kriterleri)

---

## Yönetici Özeti

SimpleXray, şu anda **%100 Kotlin** (252 dosya) ve **29 C++ JNI dosyası** ile yazılmış karmaşık bir Android proxy uygulamasıdır. Bu plan, **aşamalı ve risk-minimizeli** bir Rust geçişi önerir:

### Geçiş Stratejisi Önerisi: **Hibrit Yaklaşım (3 Faz)**

1. **Faz 1 (0-3 ay):** Native C++ katmanını Rust'a çevirme ✅ **En Yüksek ROI**
2. **Faz 2 (3-8 ay):** Core business logic'i Rust'a taşıma (Kotlin FFI ile)
3. **Faz 3 (8-12 ay):** Tam Rust uygulaması (UniFFI + Jetpack Compose UI)

### Neden Rust?

| Kriter | C++ | Kotlin | Rust |
|--------|-----|--------|------|
| **Memory Safety** | ❌ Manuel | ✅ GC | ✅ Compile-time |
| **Performance** | ✅✅ | ✅ | ✅✅ |
| **Concurrency** | ⚠️ Karmaşık | ✅ Coroutines | ✅ Fearless |
| **Zero-cost Abstractions** | ⚠️ Kısmi | ❌ | ✅ |
| **Android NDK Support** | ✅ | ❌ | ✅ |
| **Ecosystem (Networking)** | ✅ | ✅ | ✅✅ (Tokio, QUIC) |
| **Build Time** | ⚠️ Yavaş | ✅ | ⚠️ Orta |

**Tahmini İyileştirmeler:**
- ⚡ %15-30 daha iyi performans (zero-cost abstractions + SIMD)
- 🐛 %80+ crash azalması (memory safety)
- 🔒 %99 güvenli kod (unsafe sadece FFI'da)
- 📦 %20 daha küçük binary (link-time optimization)

---

## Mevcut Durum Analizi

### Proje İstatistikleri

```
Toplam Kod Satırı:      ~100,000+ LOC
Kotlin Dosyaları:       252 dosya (~85,000 LOC)
C++ JNI Dosyaları:      29 dosya (~15,000 LOC)
Test Dosyaları:         35+ test suite
Desteklenen ABI:        arm64-v8a, x86_64
Min Android SDK:        29 (Android 9)
Target SDK:             35 (Android 15)
```

### Kritik Bileşenler

#### A. Native C++ Modüller (En Yüksek Öncelik)

```
1. perf-net/ (18 dosya)
   ├── cpu_affinity.cpp          → Rust: libc, nix
   ├── zero_copy_io.cpp          → Rust: tokio::io::AsyncRead/Write
   ├── connection_pool.cpp       → Rust: deadpool, bb8
   ├── crypto_accel.cpp          → Rust: ring, rustls
   ├── epoll_loop.cpp            → Rust: mio, tokio
   ├── kernel_pacing.cpp         → Rust: libc::setsockopt
   ├── mtu_tuning.cpp            → Rust: socket2
   ├── quic_optimizer.cpp        → Rust: quinn, s2n-quic
   └── ring_buffer.cpp           → Rust: ringbuffer, crossbeam

2. quiche-client/ (5 dosya)
   └── (Zaten Rust! - Sadece JNI wrapper yenilenmeli)

3. pepper-shaper/ (3 dosya)
   ├── traffic_shaper.cpp        → Rust: token-bucket, governor
   ├── queue_discipline.cpp      → Rust: Custom impl
   └── kernel_pacing.cpp         → Rust: nix::sys::socket

4. xray-signal-handler/ (1 dosya)
   └── signal_handler.cpp        → Rust: signal-hook, nix::sys::signal
```

#### B. Kotlin Business Logic (Orta Öncelik)

```
Core Modules (Rust'a taşınabilir):
├── chain/supervisor/         (8 dosya, ~2000 LOC)
├── xray/                     (12 dosya, ~3500 LOC)
├── performance/              (45 dosya, ~12000 LOC)
├── stats/                    (8 dosya, ~2000 LOC)
├── grpc/                     (4 dosya, ~800 LOC)
├── config/                   (15 dosya, ~4000 LOC)
└── network/                  (10 dosya, ~2500 LOC)

UI Layer (Kotlin'de kalacak):
├── ui/                       (40+ screens, Jetpack Compose)
├── viewmodel/                (26 ViewModels)
└── activity/                 (MainActivity)
```

#### C. Mevcut Rust Bileşenleri

```
✅ QUICHE (Cloudflare):       Pre-built static library
✅ BoringSSL:                 C library (Rust binding mevcut: boring)
✅ hev-socks5-tunnel:         C (Rust'a çevrilebilir)
```

---

## Rust Geçiş Stratejisi

### Seçenek 1: **Aşamalı Hibrit Yaklaşım** ⭐ **ÖNERİLEN**

**Avantajlar:**
- ✅ Minimum risk (her faz test edilebilir)
- ✅ Hızlı ROI (native kod hemen iyileşir)
- ✅ Ekip öğrenme süresi
- ✅ Geriye uyumlu

**Dezavantajlar:**
- ⚠️ Uzun geçiş süresi (12 ay)
- ⚠️ FFI overhead (geçici)

### Seçenek 2: **Büyük Yeniden Yazım** (Big Rewrite)

**Avantajlar:**
- ✅ Temiz mimari
- ✅ Tutarlı kod tabanı

**Dezavantajlar:**
- ❌ Yüksek risk
- ❌ Uzun geliştirme dönemi (18+ ay)
- ❌ Paralel bakım yükü
- ❌ **ÖNERİLMEZ**

---

## Teknik Yaklaşım

### Faz 1: Native C++ → Rust (0-3 ay)

#### 1.1. Build System Entegrasyonu

**Araçlar:**
- `cargo-ndk`: Android NDK için Rust build
- `android_gradle_build.rs`: Gradle entegrasyonu
- `uniffi-bindgen`: JNI binding üretimi (opsiyonel)

**Gradle Yapılandırması:**

```gradle
// app/build.gradle.kts
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
}

// Rust build task
tasks.register("buildRustNative") {
    doLast {
        exec {
            workingDir = file("../rust")
            commandLine = listOf(
                "cargo", "ndk",
                "-t", "arm64-v8a",
                "-t", "x86_64",
                "-o", "../app/src/main/jniLibs",
                "build", "--release"
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("buildRustNative")
}
```

#### 1.2. Rust Proje Yapısı

```
SimpleXray/
├── app/                          # Android app (Kotlin + Compose)
├── rust/                         # Yeni Rust workspace
│   ├── Cargo.toml               # Workspace manifest
│   ├── simplexray-native/       # JNI FFI layer
│   │   ├── Cargo.toml
│   │   ├── src/
│   │   │   ├── lib.rs
│   │   │   ├── jni_bridge.rs   # JNI exports
│   │   │   └── android.rs      # Android-specific
│   ├── simplexray-core/         # Platform-agnostic core
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs
│   │       ├── perf_net/       # C++ perf-net portü
│   │       ├── quiche_client/  # QUICHE wrapper
│   │       ├── pepper_shaper/  # Traffic shaper
│   │       └── chain/          # Chain supervisor
│   ├── simplexray-ffi/          # UniFFI bindings (Faz 2)
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.udl         # Interface definition
│   │       └── lib.rs
│   └── tests/
│       └── integration/
```

#### 1.3. Performance Network (perf-net) Portü

**Örnek: CPU Affinity (C++ → Rust)**

**Mevcut C++ (cpu_affinity.cpp):**
```cpp
#include <sched.h>
#include <pthread.h>

void set_cpu_affinity(int core_id) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(core_id, &cpuset);
    pthread_setaffinity_np(pthread_self(), sizeof(cpu_set_t), &cpuset);
}
```

**Rust Eşdeğeri:**
```rust
// rust/simplexray-core/src/perf_net/cpu_affinity.rs
use nix::sched::{sched_setaffinity, CpuSet};
use nix::unistd::Pid;
use anyhow::Result;

pub fn set_cpu_affinity(core_id: usize) -> Result<()> {
    let mut cpuset = CpuSet::new();
    cpuset.set(core_id)?;
    sched_setaffinity(Pid::from_raw(0), &cpuset)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_set_affinity() {
        // Core 0 her zaman mevcut olmalı
        assert!(set_cpu_affinity(0).is_ok());
    }
}
```

**JNI Bridge:**
```rust
// rust/simplexray-native/src/jni_bridge.rs
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;
use simplexray_core::perf_net::cpu_affinity::set_cpu_affinity;

#[no_mangle]
pub extern "C" fn Java_com_simplexray_an_performance_NativeCpuAffinity_setCpuAffinity(
    _env: JNIEnv,
    _class: JClass,
    core_id: jint,
) -> jint {
    match set_cpu_affinity(core_id as usize) {
        Ok(_) => 0,
        Err(e) => {
            log::error!("Failed to set CPU affinity: {}", e);
            -1
        }
    }
}
```

**Kotlin Kullanımı:**
```kotlin
// app/src/main/kotlin/com/simplexray/an/performance/NativeCpuAffinity.kt
object NativeCpuAffinity {
    init {
        System.loadLibrary("simplexray_native")
    }

    external fun setCpuAffinity(coreId: Int): Int
}

// Kullanım
NativeCpuAffinity.setCpuAffinity(2) // Core 2'ye pin'le
```

#### 1.4. Zero-Copy I/O

**Rust Implementation:**
```rust
// rust/simplexray-core/src/perf_net/zero_copy_io.rs
use tokio::io::{AsyncRead, AsyncWrite};
use std::io::IoSlice;
use std::pin::Pin;
use std::task::{Context, Poll};

pub struct ZeroCopyStream<T> {
    inner: T,
    buffer_pool: BufferPool,
}

impl<T: AsyncRead + AsyncWrite + Unpin> ZeroCopyStream<T> {
    pub async fn splice(&mut self, other: &mut Self, len: usize) -> std::io::Result<u64> {
        // Linux splice() syscall kullanarak kernel-space'de kopyalama
        #[cfg(target_os = "android")]
        {
            use nix::fcntl::{splice, SpliceFFlags};
            // Zero-copy splice implementation
            todo!("Implement splice")
        }

        #[cfg(not(target_os = "android"))]
        {
            tokio::io::copy(self, other).await
        }
    }
}
```

#### 1.5. QUIC Optimizer (quinn kullanarak)

**Rust Implementation:**
```rust
// rust/simplexray-core/src/perf_net/quic_optimizer.rs
use quinn::{ClientConfig, Endpoint, TransportConfig};
use std::sync::Arc;
use std::time::Duration;

pub struct QuicOptimizer {
    config: ClientConfig,
}

impl QuicOptimizer {
    pub fn new() -> Self {
        let mut transport = TransportConfig::default();

        // Aggressive settings
        transport.max_concurrent_bidi_streams(100u32.into());
        transport.max_concurrent_uni_streams(100u32.into());
        transport.keep_alive_interval(Some(Duration::from_secs(5)));
        transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().unwrap()));

        // 0-RTT support
        transport.initial_rtt(Duration::from_millis(100));

        let mut config = ClientConfig::new(Arc::new(rustls::ClientConfig::builder()
            .with_safe_defaults()
            .with_custom_certificate_verifier(Arc::new(SkipServerVerification))
            .with_no_client_auth()));

        config.transport_config(Arc::new(transport));

        Self { config }
    }

    pub async fn connect(&self, addr: &str) -> anyhow::Result<quinn::Connection> {
        let endpoint = Endpoint::client("0.0.0.0:0".parse()?)?;
        let conn = endpoint.connect_with(self.config.clone(), addr.parse()?, "server")?.await?;
        Ok(conn)
    }
}
```

#### 1.6. Crypto Acceleration

**Rust (ring + rustls):**
```rust
// rust/simplexray-core/src/perf_net/crypto_accel.rs
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM};
use ring::rand::{SecureRandom, SystemRandom};

pub struct CryptoAccelerator {
    key: LessSafeKey,
    rng: SystemRandom,
}

impl CryptoAccelerator {
    pub fn new(key_bytes: &[u8; 32]) -> Self {
        let unbound_key = UnboundKey::new(&AES_256_GCM, key_bytes).unwrap();
        let key = LessSafeKey::new(unbound_key);

        Self {
            key,
            rng: SystemRandom::new(),
        }
    }

    pub fn encrypt(&self, plaintext: &[u8]) -> Vec<u8> {
        let mut nonce_bytes = [0u8; 12];
        self.rng.fill(&mut nonce_bytes).unwrap();
        let nonce = Nonce::assume_unique_for_key(nonce_bytes);

        let mut in_out = plaintext.to_vec();
        self.key
            .seal_in_place_append_tag(nonce, Aad::empty(), &mut in_out)
            .unwrap();

        let mut result = nonce_bytes.to_vec();
        result.extend_from_slice(&in_out);
        result
    }
}
```

---

### Faz 2: Core Business Logic → Rust (3-8 ay)

#### 2.1. UniFFI ile Kotlin Binding

**UniFFI Interface Definition (.udl):**
```idl
// rust/simplexray-ffi/src/lib.udl
namespace simplexray {
    ChainSupervisor create_chain_supervisor(ChainConfig config);
};

dictionary ChainConfig {
    boolean reality_enabled;
    boolean hysteria2_enabled;
    boolean pepper_enabled;
    string xray_config_path;
};

enum ChainStatus {
    "Idle",
    "Starting",
    "Running",
    "Stopping",
    "Error",
};

interface ChainSupervisor {
    constructor(ChainConfig config);

    [Throws=ChainError]
    void start();

    [Throws=ChainError]
    void stop();

    ChainStatus get_status();

    [Throws=ChainError]
    ChainMetrics get_metrics();
};

dictionary ChainMetrics {
    u64 bytes_sent;
    u64 bytes_received;
    u32 active_connections;
    f64 latency_ms;
};

[Error]
enum ChainError {
    "ConfigError",
    "StartupError",
    "RuntimeError",
};
```

**Rust Implementation:**
```rust
// rust/simplexray-ffi/src/lib.rs
use std::sync::Arc;
use tokio::sync::RwLock;

uniffi::include_scaffolding!("lib");

pub struct ChainSupervisor {
    config: ChainConfig,
    status: Arc<RwLock<ChainStatus>>,
    runtime: tokio::runtime::Runtime,
}

impl ChainSupervisor {
    pub fn new(config: ChainConfig) -> Self {
        Self {
            config,
            status: Arc::new(RwLock::new(ChainStatus::Idle)),
            runtime: tokio::runtime::Runtime::new().unwrap(),
        }
    }

    pub fn start(&self) -> Result<(), ChainError> {
        self.runtime.block_on(async {
            let mut status = self.status.write().await;
            *status = ChainStatus::Starting;

            // Start chain components
            if self.config.reality_enabled {
                // Start Reality
            }
            if self.config.hysteria2_enabled {
                // Start Hysteria2
            }

            *status = ChainStatus::Running;
            Ok(())
        })
    }

    pub fn get_status(&self) -> ChainStatus {
        self.runtime.block_on(async {
            *self.status.read().await
        })
    }

    pub fn get_metrics(&self) -> Result<ChainMetrics, ChainError> {
        Ok(ChainMetrics {
            bytes_sent: 1024,
            bytes_received: 2048,
            active_connections: 5,
            latency_ms: 12.5,
        })
    }

    pub fn stop(&self) -> Result<(), ChainError> {
        self.runtime.block_on(async {
            let mut status = self.status.write().await;
            *status = ChainStatus::Stopping;

            // Stop all components

            *status = ChainStatus::Idle;
            Ok(())
        })
    }
}
```

**Auto-generated Kotlin Code (uniffi-bindgen):**
```kotlin
// Auto-generated: app/src/main/kotlin/uniffi/simplexray/simplexray.kt
package uniffi.simplexray

data class ChainConfig(
    val realityEnabled: Boolean,
    val hysteria2Enabled: Boolean,
    val pepperEnabled: Boolean,
    val xrayConfigPath: String
)

enum class ChainStatus {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

sealed class ChainError : Exception() {
    class ConfigError : ChainError()
    class StartupError : ChainError()
    class RuntimeError : ChainError()
}

class ChainSupervisor(config: ChainConfig) {
    fun start() // throws ChainError
    fun stop() // throws ChainError
    fun getStatus(): ChainStatus
    fun getMetrics(): ChainMetrics // throws ChainError
}
```

**Kotlin Kullanımı:**
```kotlin
// app/src/main/kotlin/com/simplexray/an/chain/RustChainSupervisor.kt
import uniffi.simplexray.*

class RustChainSupervisor {
    private val supervisor: ChainSupervisor

    init {
        System.loadLibrary("simplexray_ffi")

        val config = ChainConfig(
            realityEnabled = true,
            hysteria2Enabled = true,
            pepperEnabled = true,
            xrayConfigPath = "/data/user/0/com.simplexray.an/files/config.json"
        )

        supervisor = ChainSupervisor(config)
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            supervisor.start()
        } catch (e: ChainError) {
            Log.e("Chain", "Failed to start: $e")
        }
    }

    fun getMetrics(): ChainMetrics? {
        return try {
            supervisor.getMetrics()
        } catch (e: ChainError) {
            null
        }
    }
}
```

#### 2.2. XRay Core Launcher (Rust Port)

**Rust Implementation:**
```rust
// rust/simplexray-core/src/xray/launcher.rs
use tokio::process::Command;
use tokio::sync::mpsc;
use std::path::PathBuf;

pub struct XrayCoreLauncher {
    binary_path: PathBuf,
    config_path: PathBuf,
    process_handle: Option<tokio::process::Child>,
}

impl XrayCoreLauncher {
    pub fn new(binary_path: PathBuf, config_path: PathBuf) -> Self {
        Self {
            binary_path,
            config_path,
            process_handle: None,
        }
    }

    pub async fn start(&mut self) -> anyhow::Result<()> {
        let mut cmd = Command::new(&self.binary_path);
        cmd.arg("run")
           .arg("-config")
           .arg(&self.config_path);

        let child = cmd.spawn()?;
        self.process_handle = Some(child);

        Ok(())
    }

    pub async fn stop(&mut self) -> anyhow::Result<()> {
        if let Some(mut child) = self.process_handle.take() {
            child.kill().await?;
        }
        Ok(())
    }

    pub async fn monitor(&mut self, tx: mpsc::Sender<ProcessEvent>) {
        if let Some(child) = &mut self.process_handle {
            tokio::select! {
                status = child.wait() => {
                    match status {
                        Ok(exit) => tx.send(ProcessEvent::Exited(exit.code())).await.ok(),
                        Err(e) => tx.send(ProcessEvent::Error(e.to_string())).await.ok(),
                    };
                }
            }
        }
    }
}

pub enum ProcessEvent {
    Exited(Option<i32>),
    Error(String),
}
```

#### 2.3. gRPC Stats Client (tonic)

**Rust Implementation:**
```rust
// rust/simplexray-core/src/grpc/stats_client.rs
use tonic::transport::Channel;
use xray_proto::command::stats_service_client::StatsServiceClient;
use xray_proto::command::{QueryStatsRequest, StatObject};

pub mod xray_proto {
    tonic::include_proto!("xray.app.stats.command");
}

pub struct XrayStatsClient {
    client: StatsServiceClient<Channel>,
}

impl XrayStatsClient {
    pub async fn connect(addr: &str) -> anyhow::Result<Self> {
        let client = StatsServiceClient::connect(addr.to_string()).await?;
        Ok(Self { client })
    }

    pub async fn get_stats(&mut self, pattern: &str) -> anyhow::Result<Vec<StatObject>> {
        let request = tonic::Request::new(QueryStatsRequest {
            pattern: pattern.to_string(),
            reset: false,
        });

        let response = self.client.query_stats(request).await?;
        Ok(response.into_inner().stat)
    }

    pub async fn get_uplink(&mut self) -> anyhow::Result<i64> {
        let stats = self.get_stats("outbound>>>traffic>>>uplink").await?;
        Ok(stats.first().map(|s| s.value).unwrap_or(0))
    }

    pub async fn get_downlink(&mut self) -> anyhow::Result<i64> {
        let stats = self.get_stats("outbound>>>traffic>>>downlink").await?;
        Ok(stats.first().map(|s| s.value).unwrap_or(0))
    }
}
```

---

### Faz 3: Full Rust Application (8-12 ay)

Bu faz opsiyoneldir ve şunları içerir:

1. **Rust-based Android Service** (using `android-activity` crate)
2. **Jetpack Compose UI Kotlin'de kalır** (best practice)
3. **Tüm business logic Rust'ta**

**Mimari:**
```
┌─────────────────────────────────────┐
│   Jetpack Compose UI (Kotlin)       │
│   - Screens, ViewModels, Navigation │
└─────────────────────────────────────┘
              ↕ UniFFI
┌─────────────────────────────────────┐
│   Rust Core Library                 │
│   - ChainSupervisor                 │
│   - PerformanceManager              │
│   - TrafficMonitor                  │
│   - ConfigManager                   │
│   - XrayLauncher                    │
└─────────────────────────────────────┘
              ↕
┌─────────────────────────────────────┐
│   Rust Native Layer                 │
│   - perf-net                        │
│   - quiche-client                   │
│   - pepper-shaper                   │
└─────────────────────────────────────┘
```

---

## Aşamalı Geçiş Planı

### **Faz 1: Native Layer (3 Ay)** 🎯 Öncelik: YÜKSEK

#### Ay 1: Setup & perf-net Port

**Hafta 1-2: Infrastructure**
- [ ] Rust workspace oluşturma (`rust/` dizini)
- [ ] Cargo.toml yapılandırması
- [ ] cargo-ndk kurulumu ve test
- [ ] Gradle build entegrasyonu
- [ ] CI/CD pipeline güncelleme (Rust build adımları)
- [ ] JNI bridge template oluşturma

**Hafta 3-4: perf-net Core Modules**
- [ ] `cpu_affinity.rs` (C++ → Rust port)
- [ ] `zero_copy_io.rs` (tokio + splice)
- [ ] `connection_pool.rs` (deadpool)
- [ ] Unit test yazma (code coverage >80%)
- [ ] Benchmark testleri (criterion.rs)

#### Ay 2: Advanced Performance & Crypto

**Hafta 5-6: Networking Optimizations**
- [ ] `epoll_loop.rs` (mio/tokio)
- [ ] `kernel_pacing.rs` (libc socket options)
- [ ] `mtu_tuning.rs` (socket2)
- [ ] `tcp_fast_open.rs`

**Hafta 7-8: Crypto & QUIC**
- [ ] `crypto_accel.rs` (ring + rustls)
- [ ] `quic_optimizer.rs` (quinn)
- [ ] `ring_buffer.rs` (crossbeam)
- [ ] Integration tests

#### Ay 3: Remaining Components & Testing

**Hafta 9-10: Other Native Modules**
- [ ] `pepper-shaper` port (traffic_shaper.rs)
- [ ] `signal-handler` port (signal-hook)
- [ ] QUICHE JNI wrapper güncelleme

**Hafta 11-12: Testing & Optimization**
- [ ] Comprehensive integration tests
- [ ] Performance benchmarks (C++ vs Rust)
- [ ] Memory leak tests (valgrind, heaptrack)
- [ ] Android device tests (arm64-v8a)
- [ ] Release build optimizations
- [ ] Dokümantasyon

**Teslim Edilen:**
✅ Tamamen çalışan Rust native library
✅ Kotlin FFI bindings
✅ Test suite (unit + integration)
✅ Performance reports
✅ Migration guide

---

### **Faz 2: Core Business Logic (5 Ay)** 🎯 Öncelik: ORTA

#### Ay 4-5: UniFFI Setup & Chain System

**Hafta 13-16: UniFFI Infrastructure**
- [ ] UniFFI workspace modülü (`simplexray-ffi/`)
- [ ] Interface definition (`.udl` dosyaları)
- [ ] Auto-generated Kotlin bindings test
- [ ] Error handling strategy

**Hafta 17-20: Chain Supervisor Port**
- [ ] `ChainSupervisor` core logic (Rust)
- [ ] Reality integration
- [ ] Hysteria2 integration
- [ ] PepperShaper integration
- [ ] State management (Arc<RwLock>)

#### Ay 6-7: XRay & Performance

**Hafta 21-24: XRay Core Management**
- [ ] `XrayCoreLauncher` port
- [ ] Process monitoring
- [ ] Config builder (serde_json)
- [ ] Assets installer

**Hafta 25-28: Performance System**
- [ ] `PerformanceManager` port
- [ ] Adaptive tuner
- [ ] Smart connection manager
- [ ] Traffic shaper
- [ ] Performance profiles

#### Ay 8: Stats, Config & Testing

**Hafta 29-32: Remaining Core Modules**
- [ ] gRPC stats client (tonic)
- [ ] Traffic observer
- [ ] Config format converter
- [ ] Domain resolver

**Hafta 33-36: Integration & Testing**
- [ ] End-to-end tests
- [ ] UI integration tests
- [ ] Performance regression tests
- [ ] Beta release

**Teslim Edilen:**
✅ Rust core library with UniFFI
✅ Kotlin UI integration
✅ All core features migrated
✅ Beta Android app

---

### **Faz 3: Polish & Full Migration (4 Ay)** 🎯 Öncelik: DÜŞÜK

#### Ay 9-10: Remaining Modules

- [ ] Network utilities port
- [ ] Domain classifier
- [ ] Telemetry system
- [ ] Update mechanism
- [ ] Widget backend logic

#### Ay 11-12: Optimization & Release

- [ ] Binary size optimization (strip, LTO)
- [ ] Startup time optimization
- [ ] Memory footprint optimization
- [ ] Final performance tuning
- [ ] Security audit
- [ ] Production release

**Teslim Edilen:**
✅ 100% Rust core
✅ Production-ready app
✅ Performance improvements validated
✅ Full documentation

---

## Araçlar ve Kütüphaneler

### Rust Toolchain

```toml
# rust/Cargo.toml
[workspace]
members = [
    "simplexray-native",
    "simplexray-core",
    "simplexray-ffi",
]

[workspace.package]
edition = "2021"
rust-version = "1.75"

[workspace.dependencies]
# Async Runtime
tokio = { version = "1.35", features = ["full"] }
tokio-util = "0.7"

# Networking
quinn = "0.11"                      # QUIC
mio = "0.8"                         # Low-level I/O
socket2 = "0.5"                     # Socket utilities
hyper = "1.0"                       # HTTP

# Crypto
ring = "0.17"                       # Crypto primitives
rustls = "0.22"                     # TLS
boring = "4.0"                      # BoringSSL bindings

# System
nix = { version = "0.27", features = ["process", "sched", "socket"] }
libc = "0.2"

# FFI
jni = "0.21"                        # JNI bindings
uniffi = "0.26"                     # UniFFI framework

# gRPC
tonic = "0.11"                      # gRPC client/server
prost = "0.12"                      # Protobuf

# Serialization
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"

# Utilities
anyhow = "1.0"                      # Error handling
thiserror = "1.0"                   # Custom errors
log = "0.4"                         # Logging
tracing = "0.1"                     # Structured logging

# Concurrency
crossbeam = "0.8"                   # Concurrent data structures
parking_lot = "0.12"                # Fast locks
dashmap = "5.5"                     # Concurrent HashMap

# Data Structures
bytes = "1.5"                       # Byte buffers
smallvec = "1.11"                   # Stack-allocated vectors

# Testing
criterion = "0.5"                   # Benchmarking
proptest = "1.4"                    # Property testing
mockall = "0.12"                    # Mocking

# Android-specific
android-activity = "0.5"            # Android integration
android_logger = "0.13"             # Android logging
```

### Build Tools

```bash
# Rust toolchain
rustup target add aarch64-linux-android
rustup target add x86_64-linux-android

# Cargo tools
cargo install cargo-ndk            # NDK build support
cargo install cargo-udeps          # Unused dependency check
cargo install cargo-audit          # Security audit
cargo install cargo-bloat          # Binary size analysis
cargo install cargo-criterion      # Benchmark runner
```

### Android Gradle Configuration

```kotlin
// app/build.gradle.kts
android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packagingOptions {
        jniLibs {
            // Rust libraries
            pickFirsts += listOf(
                "lib/arm64-v8a/libsimplexray_native.so",
                "lib/arm64-v8a/libsimplexray_ffi.so"
            )
        }
    }
}

dependencies {
    // UniFFI generated library
    implementation(files("libs/simplexray-ffi.jar"))
}
```

---

## Risk Analizi

### Yüksek Riskler

| Risk | Olasılık | Etki | Azaltma Stratejisi |
|------|----------|------|-------------------|
| **Performans regresyonu** | Orta | Yüksek | Erken benchmark, A/B testing |
| **FFI overhead** | Düşük | Orta | Zero-copy design, profiling |
| **Build time artışı** | Yüksek | Orta | Incremental builds, sccache |
| **Ekip öğrenme eğrisi** | Yüksek | Orta | Training, pair programming |
| **Android compat issues** | Orta | Yüksek | Extensive device testing |
| **Binary size artışı** | Orta | Orta | LTO, strip, cargo-bloat |

### Orta Riskler

| Risk | Olasılık | Etki | Azaltma Stratejisi |
|------|----------|------|-------------------|
| **Third-party crate API changes** | Orta | Orta | Pin versions, vendor crates |
| **NDK API değişiklikleri** | Düşük | Orta | Use stable APIs |
| **Memory leak (Arc cycles)** | Orta | Orta | Weak references, testing |

### Düşük Riskler

| Risk | Olasılık | Etki | Azaltma Stratejisi |
|------|----------|------|-------------------|
| **Rust compiler bugs** | Düşük | Düşük | Use stable channel |
| **License issues** | Düşük | Orta | Audit dependencies |

---

## Zaman ve Kaynak Tahmini

### Zaman Çizelgesi

```
Faz 1: Native Layer          │████████████│ 3 ay
Faz 2: Core Business Logic   │────────────────████████████████│ 5 ay (Ay 4-8)
Faz 3: Full Migration        │────────────────────────────────────████████│ 4 ay (Ay 9-12)
                             └─────────────────────────────────────────────┘
                             0    3    6    9    12 ay
```

### Kaynak Gereksinimleri

**Ekip:**
- 1x Rust Developer (senior) - Full-time
- 1x Android Developer (Kotlin) - Part-time (50%)
- 1x QA Engineer - Part-time (25%)
- 1x DevOps Engineer - Part-time (10%)

**Altyapı:**
- CI/CD pipeline (GitHub Actions - mevcut)
- Android test devices (arm64-v8a)
- Monitoring tools (Sentry, Firebase)

**Eğitim:**
- Rust training (2 hafta)
- UniFFI workshop (1 hafta)
- Code review sessions (ongoing)

### Maliyet Tahmini

| Kategori | Faz 1 | Faz 2 | Faz 3 | Toplam |
|----------|-------|-------|-------|--------|
| **Development** | 3 ay × $X | 5 ay × $X | 4 ay × $X | 12 ay × $X |
| **QA/Testing** | $X/4 | $X/4 | $X/4 | $X × 3/4 |
| **DevOps** | $X/10 | $X/10 | $X/10 | $X × 3/10 |
| **Training** | $5K | - | - | $5K |
| **Tools** | $2K | - | - | $2K |

---

## Başarı Kriterleri

### Performans Metrikleri

| Metrik | Baseline (C++) | Hedef (Rust) | Ölçüm Yöntemi |
|--------|---------------|--------------|---------------|
| **Throughput** | 100 MB/s | ≥115 MB/s (+15%) | iperf3 |
| **Latency** | 5ms p50 | ≤4ms p50 (-20%) | ping, custom probe |
| **Memory Usage** | 80 MB | ≤70 MB (-12%) | Android Profiler |
| **CPU Usage** | 15% avg | ≤12% avg (-20%) | top, systrace |
| **Crash Rate** | 0.5% | ≤0.1% (-80%) | Firebase Crashlytics |
| **Binary Size** | 12 MB | ≤10 MB (-16%) | APK Analyzer |
| **Startup Time** | 800ms | ≤700ms (-12%) | Android Vitals |
| **Battery Drain** | 5%/hr | ≤4%/hr (-20%) | Battery Historian |

### Kalite Metrikleri

- ✅ Code coverage ≥80%
- ✅ Zero unsafe code violations (clippy)
- ✅ All tests passing
- ✅ No known security vulnerabilities (cargo-audit)
- ✅ Documentation coverage ≥70%

### İş Hedefleri

- ✅ Feature parity (tüm mevcut özellikler çalışıyor)
- ✅ Backward compatibility (eski configler çalışıyor)
- ✅ User satisfaction ≥4.5/5 (beta testers)
- ✅ Successful production rollout (>100K users)

---

## Sonraki Adımlar

### Hemen Yapılacaklar (1 Hafta)

1. **Karar Toplantısı**
   - [ ] Stakeholder approval
   - [ ] Budget confirmation
   - [ ] Team assignment

2. **Setup**
   - [ ] Rust toolchain kurulumu (tüm dev machines)
   - [ ] Workspace template oluşturma
   - [ ] CI/CD pipeline başlangıç konfigürasyonu

3. **PoC (Proof of Concept)**
   - [ ] Basit JNI bridge test (hello world)
   - [ ] Benchmark harness kurulumu
   - [ ] Performans baseline ölçümü

### Ay 1 Hedefleri

- [ ] Rust workspace fully functional
- [ ] First module ported (cpu_affinity)
- [ ] Benchmarks showing improvement
- [ ] Team trained on Rust basics

---

## Ekler

### A. Referans Projeler

**Başarılı Rust Android Projetleri:**
- Mozilla Firefox (GeckoView - Rust components)
- Signal (libsignal - crypto in Rust)
- 1Password (core logic in Rust)
- Mullvad VPN (WireGuard in Rust)

### B. Learning Resources

**Rust Öğrenme:**
- The Rust Book (https://doc.rust-lang.org/book/)
- Rust for Android (https://source.android.com/docs/setup/build/rust)
- UniFFI Tutorial (https://mozilla.github.io/uniffi-rs/)

**Async Rust:**
- Tokio Tutorial (https://tokio.rs/tokio/tutorial)
- Async Book (https://rust-lang.github.io/async-book/)

### C. Benchmarking Template

```rust
// rust/benches/perf_net_benchmark.rs
use criterion::{black_box, criterion_group, criterion_main, Criterion, Throughput};
use simplexray_core::perf_net::zero_copy_io::ZeroCopyStream;

fn benchmark_zero_copy(c: &mut Criterion) {
    let mut group = c.benchmark_group("zero_copy_throughput");
    group.throughput(Throughput::Bytes(1024 * 1024)); // 1 MB

    group.bench_function("rust_impl", |b| {
        b.iter(|| {
            // Benchmark Rust implementation
            black_box(zero_copy_transfer(1024 * 1024));
        });
    });

    group.finish();
}

criterion_group!(benches, benchmark_zero_copy);
criterion_main!(benches);
```

---

## Sonuç

Bu Rust geçiş planı, **aşamalı, risk-minimizeli ve ölçülebilir** bir yaklaşım sunar.

**Önerilen başlangıç:** Faz 1 (Native Layer) ile başlayın - 3 ay içinde somut performans iyileştirmeleri göreceksiniz ve ekip Rust'a alışacaktır.

**Karar noktası:** Faz 1 sonrası, Faz 2'ye devam edilip edilmeyeceği metriklerle değerlendirilebilir.

**Son hedef:** 12 ay içinde production-ready, full Rust core Android uygulaması.

---

**Hazırlayan:** Claude (Anthropic)
**Tarih:** 8 Kasım 2025
**Versiyon:** 1.0
