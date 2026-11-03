package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.model.DonorState

class ThermalBatteryGuard(
    private val context: Context,
    private val onStateTransition: (DonorState, String) -> Unit,
    private val onLog: (String, String) -> Unit
) {
    var batteryPercent: Int = 100
        private set
    var isCharging: Boolean = false
        private set
    var thermalStatus: String = "NOMINAL"
        private set

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var thermalListener: Any? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100) / scale
            }
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            evaluatePowerState()
        }
    }

    fun start() {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                thermalStatus = when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> "NOMINAL"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "UNKNOWN"
                }
                onLog("ThermalGuard", "Thermal status changed: $thermalStatus ($status)")
                evaluatePowerState()
            }
            thermalListener = listener
            powerManager.addThermalStatusListener(listener)
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // ignore
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                powerManager.removeThermalStatusListener(thermalListener as PowerManager.OnThermalStatusChangedListener)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun evaluatePowerState() {
        // Critical conditions -> DRAINING
        if (!isCharging && batteryPercent <= 15) {
            onLog("ThermalGuard", "Low battery ($batteryPercent%) unplugged -> DRAINING")
            onStateTransition(DonorState.DRAINING, "Battery critical (<= 15% unplugged)")
            return
        }

        if (thermalStatus == "SEVERE" || thermalStatus == "CRITICAL" || thermalStatus == "EMERGENCY") {
            onLog("ThermalGuard", "Thermal severe ($thermalStatus) -> DRAINING")
            onStateTransition(DonorState.DRAINING, "Thermal throttling ($thermalStatus)")
            return
        }

        // Moderate conditions -> THROTTLED
        if (!isCharging && batteryPercent <= 20) {
            onLog("ThermalGuard", "Low battery ($batteryPercent%) unplugged -> THROTTLED")
            onStateTransition(DonorState.THROTTLED, "Battery low (<= 20% unplugged)")
            return
        }

        if (thermalStatus == "MODERATE") {
            onLog("ThermalGuard", "Thermal moderate ($thermalStatus) -> THROTTLED")
            onStateTransition(DonorState.THROTTLED, "Thermal elevated (MODERATE)")
        }
    }
}
