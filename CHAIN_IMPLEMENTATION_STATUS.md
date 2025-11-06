# Tunneling Chain Implementation Status

## Overview

This document tracks the implementation status of the full client-side tunneling stack:

```
Mobile → Reality SOCKS → Hysteria2 QUIC → PepperShaper → Xray-core
```

## ✅ Completed

### 1. Module Structure
- ✅ Created package structure: `chain/reality/`, `chain/hysteria2/`, `chain/pepper/`, `chain/supervisor/`
- ✅ Defined core data classes and interfaces
- ✅ Created README documentation

### 2. Reality SOCKS Layer
- ✅ `RealityConfig` - Configuration data class
- ✅ `RealityStatus` - Status tracking
- ✅ `RealitySocks` - Main API with Flow-based status
- ⏳ **TODO**: Implement actual SOCKS5 server with Xray REALITY backend

### 3. Hysteria2 QUIC Layer
- ✅ `Hy2Config` - Configuration data class
- ✅ `Hy2Metrics` - Metrics tracking
- ✅ `Hysteria2` - Main API with Flow-based metrics
- ⏳ **TODO**: Integrate Hysteria2 client (gomobile bindings or native helper)

### 4. PepperShaper Layer
- ✅ `PepperParams` - Shaping parameters
- ✅ `PepperShaper` - Kotlin API with JNI bridge
- ✅ Native module structure (`jni/pepper-shaper/`)
- ✅ JNI stubs (`pepper_jni.cpp`)
- ⏳ **TODO**: Implement lock-free ring buffers
- ⏳ **TODO**: Implement high-resolution pacing
- ⏳ **TODO**: Implement loss-aware backoff algorithms

### 5. Chain Supervisor
- ✅ `ChainState` - State machine enum
- ✅ `ChainStatus` - Overall status tracking
- ✅ `ChainConfig` - Complete configuration
- ✅ `ChainSupervisor` - Orchestration with state machine
- ✅ Layer lifecycle management
- ✅ Status monitoring and aggregation

### 6. UI Components
- ✅ `ChainViewModel` - ViewModel for chain management
- ✅ `ChainScreen` - Compose UI with status display
- ✅ Layer status cards
- ✅ Start/stop controls
- ⏳ **TODO**: Add telemetry charts
- ⏳ **TODO**: Add profile management UI
- ⏳ **TODO**: Add configuration editor

## ⏳ In Progress / TODO

### 1. Reality SOCKS Implementation
- [ ] Implement local SOCKS5 server (127.0.0.1:PORT)
- [ ] Integrate with Xray REALITY for outbound forwarding
- [ ] Add TLS fingerprint profiles (Chrome, Firefox, Safari, Edge)
- [ ] Implement health check endpoint
- [ ] Add connection metrics tracking

### 2. Hysteria2 Integration
- [ ] Evaluate integration approach:
  - Option A: gomobile bindings (preferred if stable)
  - Option B: Native helper binary with JSON config pipe
  - Option C: JNI wrapper around Go client
- [ ] Implement QUIC connection management
- [ ] Add 0-RTT support
- [ ] Implement bandwidth probing
- [ ] Chain to upstream SOCKS (Reality SOCKS)

### 3. PepperShaper Native Implementation
- [ ] Implement lock-free ring buffer for TX/RX
- [ ] Implement high-resolution pacing (nanosecond precision)
- [ ] Implement burst-friendly streaming mode
- [ ] Implement loss-aware backoff (RTT/loss inference)
- [ ] Implement queue disciplines (FQ, CoDel-lite)
- [ ] Add socket attachment logic (TCP/UDP)

### 4. BoringSSL Mode for Xray-core
- [ ] Add build flag `WITH_BORINGSSL=true|false`
- [ ] Implement TLS mode selection:
  - Option 1: Conscrypt (Java layer)
  - Option 2: Go boringcrypto toolchain
  - Option 3: Direct BoringSSL linkage (if feasible)
- [ ] Add runtime telemetry (TLS impl, cipher suites, handshake time)
- [ ] Update XrayCoreLauncher to support TLS mode

### 5. Logging & Diagnostics
- [ ] Add rotating log files (max size/day)
- [ ] Implement crash-safe dump
- [ ] Add health checks:
  - Local SOCKS accept test
  - QUIC handshake test
  - Egress IP check
- [ ] Create sanitized diagnostic bundle (no secrets)
- [ ] Add "Create Issue" action with logs

### 6. Testing
- [ ] Unit tests for config validators
- [ ] Unit tests for state machine
- [ ] Instrumented tests: start chain on emulator
- [ ] Integration tests: verify traffic traversal
- [ ] CI test servers (Hysteria2 server, echo, httpbin)

### 7. GitHub Actions CI/CD
- [ ] Create workflow: `.github/workflows/android-hy2-reality-pepper.yml`
- [ ] Setup: JDK, Android SDK, NDK r28c, Go 1.22+
- [ ] Cache: Gradle/Go modules
- [ ] Build: AAB/APK for all ABIs (arm64-v8a, armeabi-v7a, x86_64)
- [ ] Test: Unit + instrumented tests
- [ ] Auto-fix: Open PR on build failures
- [ ] Release: Attach artifacts on tag

### 8. Documentation
- [ ] `docs/CHAIN.md` - How the chain works
- [ ] `docs/TLS_MODES.md` - BoringSSL/Conscrypt/boringcrypto
- [ ] `docs/TROUBLESHOOTING.md` - Common issues
- [ ] Sample configs in `scripts/dev/`

## File Structure

```
app/src/main/kotlin/com/simplexray/an/chain/
├── reality/
│   ├── RealityConfig.kt
│   ├── RealityStatus.kt
│   └── RealitySocks.kt
├── hysteria2/
│   ├── Hy2Config.kt
│   ├── Hy2Metrics.kt
│   └── Hysteria2.kt
├── pepper/
│   ├── PepperParams.kt
│   └── PepperShaper.kt
├── supervisor/
│   ├── ChainState.kt
│   ├── ChainConfig.kt
│   └── ChainSupervisor.kt
└── README.md

app/src/main/jni/pepper-shaper/
├── Android.mk
├── src/
│   ├── pepper_jni.cpp
│   ├── pepper_queue.cpp
│   └── pepper_pacing.cpp
└── include/
    ├── pepper_queue.h
    └── pepper_pacing.h

app/src/main/kotlin/com/simplexray/an/
├── viewmodel/
│   └── ChainViewModel.kt
└── ui/chain/
    └── ChainScreen.kt
```

## Next Steps

1. **Immediate**: Implement Reality SOCKS with Xray REALITY backend
2. **Short-term**: Integrate Hysteria2 client (start with gomobile evaluation)
3. **Short-term**: Complete PepperShaper native implementation
4. **Medium-term**: Add BoringSSL mode support
5. **Medium-term**: Create comprehensive test suite
6. **Long-term**: Set up CI/CD pipeline with auto-fix

## Notes

- All components use Flow-based reactive APIs for status/metrics
- State machine ensures clean lifecycle management
- Modular design allows swapping individual layers
- No root required - uses VpnService + tun2socks
- All endpoints configurable by user (no hard-coded targets)

