package com.example.model

data class TelemetryStats(
    val instantaneousMbps: Double = 0.0,
    val bytesRelayedSession: Long = 0L,
    val activeConnections: Int = 0,
    val cellularRttMs: Int = 0,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val thermalStatus: String = "NOMINAL",
    val quotaRemainingBytes: Long = 0L,
    val sessionDurationSeconds: Long = 0L,
    val stateReason: String = "Ready"
)
