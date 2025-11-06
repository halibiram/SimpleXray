# Tunneling Chain Module

This module implements a full client-side tunneling stack with multiple layers:

## Architecture

```
Mobile App
    ↓
Reality SOCKS (TLS mimic via Xray REALITY)
    ↓
Hysteria2 QUIC accelerator
    ↓
PepperShaper (traffic shaping)
    ↓
Xray-core routing engine (BoringSSL-backed TLS)
```

## Components

### 1. Reality SOCKS (`chain/reality/`)

Local SOCKS5 server that forwards traffic via Xray REALITY to remote server.

- **RealitySocks**: Main API for starting/stopping SOCKS5 server
- **RealityConfig**: Configuration (server, port, shortId, publicKey, etc.)
- **RealityStatus**: Status and metrics

### 2. Hysteria2 (`chain/hysteria2/`)

QUIC accelerator client that chains to upstream SOCKS.

- **Hysteria2**: Main API for QUIC connection
- **Hy2Config**: Configuration (server, auth, rate limits, etc.)
- **Hy2Metrics**: Connection metrics (RTT, loss, bandwidth, 0-RTT hits)

### 3. PepperShaper (`chain/pepper/`)

Traffic shaping module with burst-friendly streaming and loss-aware backoff.

- **PepperShaper**: Kotlin API + JNI bridge
- **PepperParams**: Shaping parameters (mode, rate, queue discipline)
- Native implementation: Lock-free ring buffers, high-res pacing

### 4. Chain Supervisor (`chain/supervisor/`)

Orchestrates all layers with state machine and recovery.

- **ChainSupervisor**: Main orchestrator
- **ChainConfig**: Complete chain configuration
- **ChainState**: State machine (STOPPED → STARTING → RUNNING → DEGRADED)
- **ChainStatus**: Overall status with layer health

## Usage

```kotlin
val supervisor = ChainSupervisor(context)

val config = ChainConfig(
    name = "My Profile",
    realityConfig = RealityConfig(...),
    hysteria2Config = Hy2Config(...),
    pepperParams = PepperParams(...),
    xrayConfigPath = "xray.json",
    tlsMode = ChainConfig.TlsMode.BORINGSSL
)

supervisor.start(config)
```

## Status Monitoring

All components expose Flow-based status/metrics:

```kotlin
supervisor.status.collect { status ->
    // Monitor chain state and layer health
}

RealitySocks.status.collect { status ->
    // Monitor SOCKS server
}

Hysteria2.metrics.collect { metrics ->
    // Monitor QUIC connection
}
```

## Implementation Status

- ✅ Core structure and APIs
- ✅ State machine and orchestration
- ✅ UI components
- ⏳ Reality SOCKS implementation (Xray REALITY integration)
- ⏳ Hysteria2 client (gomobile or native helper)
- ⏳ PepperShaper native implementation (lock-free queues, pacing)
- ⏳ BoringSSL mode for Xray-core
- ⏳ End-to-end tests

## Next Steps

1. Implement Reality SOCKS with Xray REALITY backend
2. Integrate Hysteria2 client (prefer gomobile bindings)
3. Complete PepperShaper native implementation
4. Add BoringSSL mode toggle for Xray-core
5. Create comprehensive test suite
6. Add CI/CD pipeline

