package com.example.network

import android.net.NetworkCapabilities
import com.example.model.DonorState

class CapabilityGuard(
    private val onTransition: (DonorState, String) -> Unit
) {
    private var recoveryStartTimestamp = 0L
    val recoveryHoldDownMs = 8000L // 8 seconds continuous stability required

    @Synchronized
    fun evaluate(capabilities: NetworkCapabilities, currentState: DonorState) {
        val isRoaming = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isDegraded = isRoaming || !isValidated

        if (isDegraded) {
            recoveryStartTimestamp = 0L // Reset recovery timer immediately
            if (currentState == DonorState.ACTIVE) {
                val reason = when {
                    isRoaming && !isValidated -> "Roaming and unvalidated cellular connection"
                    isRoaming -> "Cellular roaming detected"
                    else -> "Cellular connection unvalidated"
                }
                onTransition(DonorState.THROTTLED, reason)
            }
        } else {
            // Stable conditions detected
            if (currentState == DonorState.THROTTLED) {
                val now = System.currentTimeMillis()
                if (recoveryStartTimestamp == 0L) {
                    recoveryStartTimestamp = now // Start hold-down clock
                } else if (now - recoveryStartTimestamp >= recoveryHoldDownMs) {
                    onTransition(DonorState.ACTIVE, "Network capabilities stabilized for 8s")
                    recoveryStartTimestamp = 0L
                }
            } else {
                recoveryStartTimestamp = 0L
            }
        }
    }

    @Synchronized
    fun reset() {
        recoveryStartTimestamp = 0L
    }
}
