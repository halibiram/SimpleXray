# Potential Bug Report for SimpleXray

This document summarizes the issues identified while reviewing the project.

## 1. Smart connection failover never selects a new server
* **Location:** `SmartConnectionManager.findBestServer`
* **Problem:** `findBestServer` always returns `null` because it never resolves the best server ID back to a `ServerConfig`. As soon as failover is triggered the manager reports "No healthy servers available", even when `_serverHealthMap` contains healthy alternatives. This makes automatic failover unusable.
* **Suggested fix:** Persist the original `ServerConfig` list (or a lookup map) so the method can return the matching configuration for the selected server ID.

## 2. Monitoring update interval cannot be changed at runtime
* **Location:** `PerformanceMonitor`
* **Problem:** The constructor stores `updateInterval` as an immutable `val`. `setUpdateInterval()` attempts to restart the monitor when a new interval is provided, but it never updates the stored interval. After restart, the coroutine still delays with the original value, so the interval setting has no effect.
* **Suggested fix:** Store the interval in a mutable property and assign the new value before restarting the monitor.

## 3. VPN core cannot restart after stopping
* **Location:** `TProxyService.stopXray`
* **Problem:** `stopXray()` cancels the `serviceScope` (`CoroutineScope(Dispatchers.IO + SupervisorJob())`) without recreating it. Once cancelled, launching new coroutines on the scope immediately completes with cancellation, so subsequent attempts to `startXray()` never run `runXrayProcess()`. The service therefore cannot be restarted after a disconnect.
* **Suggested fix:** Avoid cancelling the shared scope, or recreate a new `SupervisorJob`/`CoroutineScope` before launching additional work.

