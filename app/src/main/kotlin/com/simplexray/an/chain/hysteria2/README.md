# Hysteria2 QUIC Accelerator

Hysteria2 is a QUIC-based proxy protocol that provides high-performance acceleration with congestion control.

## Implementation Status

### ✅ Completed
- `Hy2Config` - Configuration data class
- `Hy2Metrics` - Metrics tracking
- `Hy2ConfigBuilder` - JSON config builder
- `Hysteria2` - Main API with Flow-based metrics
- Process lifecycle management (stub)

### ⏳ TODO
- **Binary Integration**: Build Hysteria2 binary for Android ABIs
  - Option A: Build from source using Go cross-compilation
  - Option B: Use prebuilt binaries if available
  - Option C: Use gomobile bindings (if stable)
  
- **Process Launch**: Implement `launchBinary()` method
  - Find binary in assets or native libs
  - Copy to filesDir and make executable
  - Launch with ProcessBuilder
  - Monitor process health
  
- **Log Parsing**: Implement `parseHy2Log()` for metrics
  - Parse RTT, loss, bandwidth from logs
  - Extract 0-RTT hit counts
  - Update metrics flow
  
- **Stats API**: Query Hysteria2 stats if available
  - Similar to Xray's gRPC stats API
  - Real-time metrics collection
  
- **Upstream Chaining**: Verify SOCKS5 upstream integration
  - Test chaining to Reality SOCKS
  - Verify traffic flow

## Configuration

Hysteria2 uses a JSON config format:

```json
{
  "logLevel": "info",
  "server": "example.com:443",
  "auth": "auth-string",
  "bandwidth": {
    "up": "100Mbps",
    "down": "500Mbps"
  },
  "alpn": "h3",
  "socks5": {
    "listen": "127.0.0.1:0"
  },
  "proxy": {
    "url": "socks5://127.0.0.1:10808"
  },
  "quic": {
    "initStreamReceiveWindow": 8388608,
    "maxStreamReceiveWindow": 8388608,
    "initConnReceiveWindow": 20971520,
    "maxConnReceiveWindow": 20971520,
    "maxIdleTimeout": 30,
    "maxIncomingStreams": 1024
  },
  "fastOpen": true,
  "bandwidthProbe": true
}
```

## Integration Approach

### Current: Process-based (Similar to Xray)

1. Build Hysteria2 binary for Android ABIs (arm64-v8a, armeabi-v7a, x86_64)
2. Include in app assets or native libs
3. Launch via ProcessBuilder with JSON config
4. Monitor process health and logs

### Alternative: Gomobile Bindings

If gomobile bindings prove stable:
1. Create Go wrapper package
2. Generate AAR/JNI bindings
3. Call directly from Kotlin

## Usage

```kotlin
Hysteria2.init(context)

val config = Hy2Config(
    server = "example.com",
    port = 443,
    auth = "auth-string",
    upstreamSocksAddr = InetSocketAddress("127.0.0.1", 10808)
)

Hysteria2.start(config, upstreamSocksAddr)

// Monitor metrics
Hysteria2.metrics.collect { metrics ->
    println("RTT: ${metrics.rtt}ms, Loss: ${metrics.loss}")
}
```

## Next Steps

1. Build Hysteria2 binary for Android
2. Integrate binary launch
3. Implement log parsing
4. Add stats API support
5. Test upstream chaining

