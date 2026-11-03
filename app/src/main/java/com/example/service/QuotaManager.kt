package com.example.service

import android.content.Context
import com.example.model.DonorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class QuotaManager(
    private val context: Context,
    private val onStateTransition: (DonorState, String) -> Unit,
    private val onForceCloseSockets: () -> Unit,
    private val onLog: (String, String) -> Unit
) {
    private val PREFS_NAME = "boostnet_quota"
    private val KEY_BILLED_BYTES = "billed_bytes"

    private val rxRawBytes = AtomicLong(0L)
    private val txRawBytes = AtomicLong(0L)
    private val billedBytes = AtomicLong(0L)
    private val lastPersistedBytes = AtomicLong(0L)

    var quotaLimitBytes: Long = 2L * 1024 * 1024 * 1024 // 2 GB default
        private set

    private var drainStartTime = 0L
    private val DRAIN_DEADLINE_MS = 60000L // 60s hard deadline
    private var drainMonitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        // Restore persisted usage monotonically
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getLong(KEY_BILLED_BYTES, 0L)
        billedBytes.set(saved)
        lastPersistedBytes.set(saved)
    }

    fun updateQuotaLimit(limitBytes: Long) {
        quotaLimitBytes = limitBytes
        checkQuotaState()
    }

    fun recordRx(bytes: Long) {
        rxRawBytes.addAndGet(bytes)
        recomputeAndCheck(bytes)
    }

    fun recordTx(bytes: Long) {
        txRawBytes.addAndGet(bytes)
        recomputeAndCheck(bytes)
    }

    private fun recomputeAndCheck(addedBytes: Long) {
        // Add 5% overhead margin for IP packet framing
        val overhead = (addedBytes * 0.05).toLong().coerceAtLeast(1L)
        val current = billedBytes.addAndGet(addedBytes + overhead)

        // Incremental persistence every 5 MB
        if (current - lastPersistedBytes.get() >= 5L * 1024 * 1024) {
            persistUsage()
        }

        checkQuotaState()
    }

    private fun checkQuotaState() {
        val current = billedBytes.get()
        val hardCap = (quotaLimitBytes * 1.05).toLong()

        if (current >= hardCap) {
            onLog("QuotaManager", "Quota hard cap reached ($current / $hardCap bytes). Terminating all sockets.")
            persistUsage()
            onForceCloseSockets()
            onStateTransition(DonorState.STOPPED, "Quota hard cap reached (+5% margin)")
            return
        }

        if (current >= quotaLimitBytes) {
            if (drainStartTime == 0L) {
                drainStartTime = System.currentTimeMillis()
                onLog("QuotaManager", "Quota limit reached ($current / $quotaLimitBytes). Transitioning to DRAINING (60s deadline).")
                persistUsage()
                onStateTransition(DonorState.DRAINING, "Quota limit reached; draining active transfers")
                startDrainWatchdog()
            }
        }
    }

    private fun startDrainWatchdog() {
        drainMonitorJob?.cancel()
        drainMonitorJob = scope.launch {
            delay(DRAIN_DEADLINE_MS)
            if (isActive) {
                onLog("QuotaManager", "Drain deadline expired (60s). Force-closing sockets.")
                onForceCloseSockets()
                onStateTransition(DonorState.STOPPED, "Drain deadline expired")
            }
        }
    }

    fun persistUsage() {
        val current = billedBytes.get()
        lastPersistedBytes.set(current)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_BILLED_BYTES, current).apply()
    }

    fun resetUsage() {
        billedBytes.set(0L)
        rxRawBytes.set(0L)
        txRawBytes.set(0L)
        lastPersistedBytes.set(0L)
        drainStartTime = 0L
        drainMonitorJob?.cancel()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_BILLED_BYTES, 0L).apply()
    }

    fun getUsedBytes(): Long = billedBytes.get()

    fun getRemainingBytes(): Long {
        val remaining = quotaLimitBytes - billedBytes.get()
        return if (remaining < 0) 0L else remaining
    }
}
