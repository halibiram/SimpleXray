# Tunneling Chain Module

This module implements a full client-side tunneling stack with multiple layers:

## Architecture

```
Mobile App
    ↓
Reality SOCKS (TLS mimic via Xray REALITY)
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

### 2. PepperShaper (`chain/pepper/`)

Traffic shaping module with burst-friendly streaming and loss-aware backoff.

- **PepperShaper**: Kotlin API + JNI bridge
- **PepperParams**: Shaping parameters (mode, rate, queue discipline)
- Native implementation: Lock-free ring buffers, high-res pacing

### 3. Chain Supervisor (`chain/supervisor/`)

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
```

## Implementation Status

- ✅ Core structure and APIs
- ✅ State machine and orchestration
- ✅ UI components
- ⏳ Reality SOCKS implementation (Xray REALITY integration)
- ⏳ PepperShaper native implementation (lock-free queues, pacing)
- ⏳ BoringSSL mode for Xray-core
- ⏳ End-to-end tests

## Next Steps

1. Implement Reality SOCKS with Xray REALITY backend
2. Complete PepperShaper native implementation
4. Add BoringSSL mode toggle for Xray-core
5. Create comprehensive test suite
6. Add CI/CD pipeline

