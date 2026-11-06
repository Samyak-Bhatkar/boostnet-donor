package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.DonorConfig
import com.example.model.DonorState
import com.example.model.LogEntry
import com.example.model.TelemetryStats
import com.example.service.BoostNetForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BoostNetViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BoostNetRepository.getInstance(application)

    val donorState: StateFlow<DonorState> = repository.donorState
        .stateIn(viewModelScope, SharingStarted.Eagerly, DonorState.STOPPED)

    val donorConfig: StateFlow<DonorConfig> = repository.donorConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.donorConfig.value)

    val telemetryStats: StateFlow<TelemetryStats> = repository.telemetryStats
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.telemetryStats.value)

    val logEntries: StateFlow<List<LogEntry>> = repository.logEntries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sessionSecret: StateFlow<String> = repository.sessionSecret
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.sessionSecret.value)

    val isPaired: StateFlow<Boolean> = repository.isPaired
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.isPaired.value)

    private val _speedHistory = MutableStateFlow<List<Double>>(emptyList())
    val speedHistory: StateFlow<List<Double>> = _speedHistory.asStateFlow()

    private val _selfTestResult = MutableStateFlow<String?>(null)
    val selfTestResult: StateFlow<String?> = _selfTestResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    init {
        refreshNetworkInfo()
        // Collect telemetry throughput to update speed history buffer
        viewModelScope.launch {
            telemetryStats.collect { stats ->
                val current = _speedHistory.value.toMutableList()
                current.add(stats.instantaneousMbps)
                if (current.size > 30) {
                    current.removeAt(0)
                }
                _speedHistory.value = current
            }
        }
    }

    fun toggleDonorService(context: Context) {
        val currentState = donorState.value
        if (currentState == DonorState.STOPPED) {
            val intent = Intent(context, BoostNetForegroundService::class.java).apply {
                action = BoostNetForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        } else {
            val intent = Intent(context, BoostNetForegroundService::class.java).apply {
                action = BoostNetForegroundService.ACTION_STOP
            }
            context.startService(intent)
        }
    }

    fun unpair() {
        repository.unpair()
    }

    fun regeneratePairingSecret() {
        repository.regenerateSessionSecret()
    }

    fun onPairedWithLaptop(secret: String) {
        repository.setPairedSecret(secret)
    }

    fun refreshNetworkInfo() {
        repository.refreshNetworkInfo()
    }

    fun updateConfig(newConfig: DonorConfig) {
        repository.updateConfig(newConfig)
    }

    fun runSelfTest() {
        if (_isTesting.value) return
        _isTesting.value = true
        _selfTestResult.value = "Running loopback test..."
        viewModelScope.launch {
            val result = repository.runLoopbackSelfTest()
            _selfTestResult.value = result
            _isTesting.value = false
        }
    }

    fun clearLogs() {
        repository.clearLogs()
    }
}
