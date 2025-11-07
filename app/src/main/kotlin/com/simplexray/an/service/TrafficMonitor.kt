package com.simplexray.an.service

import android.os.SystemClock
import com.simplexray.an.viewmodel.TrafficState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Traffic monitor that tracks VPN traffic without requiring Xray API
 */
class TrafficMonitor {
    private val _trafficState = MutableStateFlow(TrafficState(0, 0))
    val trafficState: StateFlow<TrafficState> = _trafficState.asStateFlow()

    private val uplinkBytes = AtomicLong(0)
    private val downlinkBytes = AtomicLong(0)
    private val startTime = AtomicLong(0)

    fun start() {
        startTime.set(SystemClock.elapsedRealtime())
        uplinkBytes.set(0)
        downlinkBytes.set(0)
        updateState()
    }

    fun addUplink(bytes: Long) {
        if (bytes > 0) {
            uplinkBytes.addAndGet(bytes)
            updateState()
        }
    }

    fun addDownlink(bytes: Long) {
        if (bytes > 0) {
            downlinkBytes.addAndGet(bytes)
            updateState()
        }
    }

    fun getUptime(): Long {
        val start = startTime.get()
        return if (start > 0) {
            (SystemClock.elapsedRealtime() - start) / 1000
        } else {
            0
        }
    }

    fun reset() {
        uplinkBytes.set(0)
        downlinkBytes.set(0)
        startTime.set(0)
        updateState()
    }

    private fun updateState() {
        _trafficState.value = TrafficState(
            uplink = uplinkBytes.get(),
            downlink = downlinkBytes.get()
        )
    }

    fun getCurrentStats(): TrafficState {
        return TrafficState(
            uplink = uplinkBytes.get(),
            downlink = downlinkBytes.get()
        )
    }
}
