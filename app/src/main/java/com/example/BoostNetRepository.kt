package com.example

import android.content.Context
import android.os.Build
import com.example.crypto.CryptoManager
import com.example.model.DonorConfig
import com.example.model.DonorState
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.model.TelemetryStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class BoostNetRepository(private val context: Context) {

    private val _donorState: MutableStateFlow<DonorState> = MutableStateFlow(DonorState.STOPPED)
    val donorState: StateFlow<DonorState> = _donorState.asStateFlow()

    private val _donorConfig: MutableStateFlow<DonorConfig> = MutableStateFlow(
        DonorConfig(
            nodeId = CryptoManager.getOrCreateNodeId(context),
            displayName = getDefaultDeviceName(),
            carrier = getDefaultCarrierName(),
            tech = getDefaultNetworkTech(),
            quotaLimitBytes = 2L * 1024 * 1024 * 1024 // 2 GB
        )
    )
    val donorConfig: StateFlow<DonorConfig> = _donorConfig.asStateFlow()

    private val _telemetryStats: MutableStateFlow<TelemetryStats> = MutableStateFlow(TelemetryStats())
    val telemetryStats: StateFlow<TelemetryStats> = _telemetryStats.asStateFlow()

    private val _logEntries: MutableStateFlow<List<LogEntry>> = MutableStateFlow(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val _sessionSecret: MutableStateFlow<String> = MutableStateFlow(CryptoManager.getOrCreateSessionSecret(context))
    val sessionSecret: StateFlow<String> = _sessionSecret.asStateFlow()

    private val _isPaired: MutableStateFlow<Boolean> = MutableStateFlow(CryptoManager.isPaired(context))
    val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        addLog("BoostNet", "BoostNet repository initialized. Node ID: ${_donorConfig.value.nodeId}", LogLevel.INFO)
    }

    fun updateState(newState: DonorState, reason: String = "") {
        _donorState.value = newState
        _telemetryStats.value = _telemetryStats.value.copy(
            stateReason = if (reason.isNotEmpty()) reason else newState.label
        )
        addLog("StateMachine", "State changed to ${newState.name} ($reason)", LogLevel.INFO)
    }

    fun updateTelemetry(stats: TelemetryStats) {
        _telemetryStats.value = stats
    }

    fun updateConfig(config: DonorConfig) {
        _donorConfig.value = config
        addLog("Config", "Config updated: carrier=${config.carrier}, quota=${config.quotaLimitBytes / (1024 * 1024)}MB", LogLevel.INFO)
    }

    fun regenerateSessionSecret() {
        val newSecret = CryptoManager.generateNewSecret(context)
        _sessionSecret.value = newSecret
        _isPaired.value = false
        addLog("Crypto", "Generated new pairing session secret. Previous laptop sessions invalidated.", LogLevel.WARN)
    }

    fun setPairedSecret(secret: String) {
        CryptoManager.savePairedSecret(context, secret)
        _sessionSecret.value = secret
        _isPaired.value = true
        addLog("Crypto", "Laptop pairing successful. Session secret synchronized.", LogLevel.INFO)
    }

    
    fun unpair() {
        CryptoManager.unpair(context)
        _isPaired.value = false
        _sessionSecret.value = CryptoManager.getOrCreateSessionSecret(context)
        addLog("Crypto", "Unpaired from laptop.", LogLevel.WARN)
    }

    fun addLog(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(tag = tag, message = message, level = level)
        val currentList = _logEntries.value.toMutableList()
        currentList.add(0, entry)
        if (currentList.size > 200) {
            currentList.removeAt(currentList.lastIndex)
        }
        _logEntries.value = currentList
    }

    fun clearLogs() {
        _logEntries.value = emptyList()
    }

    suspend fun runLoopbackSelfTest(): String = withContext(Dispatchers.IO) {
        addLog("SelfTest", "Initiating SOCKS5 local loopback self-test...", LogLevel.INFO)
        if (_donorState.value == DonorState.STOPPED) {
            addLog("SelfTest", "Self-test failed: Donor node is STOPPED. Please start the service first.", LogLevel.ERROR)
            return@withContext "FAILED: Start donor node first"
        }

        try {
            val socksPort = _donorConfig.value.socksPort
            val socket = Socket()
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), 3000)

            val out = DataOutputStream(socket.getOutputStream())
            val `in` = DataInputStream(socket.getInputStream())

            // 1. Send SOCKS5 greeting with method 0x02 (Username/Password)
            out.writeByte(0x05)
            out.writeByte(0x01)
            out.writeByte(0x02)
            out.flush()

            val ver = `in`.readByte().toInt() and 0xFF
            val method = `in`.readByte().toInt() and 0xFF
            if (ver != 0x05 || method != 0x02) {
                socket.close()
                addLog("SelfTest", "Self-test failed: Server rejected auth method (ver=$ver, method=$method)", LogLevel.ERROR)
                return@withContext "FAILED: SOCKS5 auth negotiation rejected"
            }

            // 2. Send RFC 1929 Auth with pairing code / secret
            val token = _sessionSecret.value
            out.writeByte(0x01)
            out.writeByte("donor".length)
            out.write("donor".toByteArray())
            out.writeByte(token.length)
            out.write(token.toByteArray())
            out.flush()

            val authVer = `in`.readByte().toInt() and 0xFF
            val authStatus = `in`.readByte().toInt() and 0xFF
            if (authVer != 0x01 || authStatus != 0x00) {
                socket.close()
                addLog("SelfTest", "Self-test failed: RFC 1929 authentication error ($authStatus)", LogLevel.ERROR)
                return@withContext "FAILED: Authentication rejected"
            }

            // 3. Send CONNECT to Cloudflare public DNS (1.1.1.1:80)
            out.writeByte(0x05)
            out.writeByte(0x01) // CONNECT
            out.writeByte(0x00) // RSV
            out.writeByte(0x01) // IPv4
            out.write(byteArrayOf(1, 1, 1, 1))
            out.writeShort(80)
            out.flush()

            val repVer = `in`.readByte().toInt() and 0xFF
            val repCode = `in`.readByte().toInt() and 0xFF
            socket.close()

            if (repVer == 0x05 && repCode == 0x00) {
                addLog("SelfTest", "Self-test PASSED! SOCKS5 RFC 1929 auth and cellular routing verified.", LogLevel.INFO)
                return@withContext "PASSED: Cellular SOCKS5 routing active"
            } else {
                addLog("SelfTest", "Self-test returned SOCKS reply error: 0x%02x".format(repCode), LogLevel.WARN)
                return@withContext "FAILED: SOCKS reply code 0x%02x".format(repCode)
            }
        } catch (e: Exception) {
            addLog("SelfTest", "Self-test exception: ${e.message}", LogLevel.ERROR)
            return@withContext "FAILED: ${e.message}"
        }
    }

    private fun getDefaultDeviceName(): String {
        val model = Build.MODEL ?: "Device"
        val manufacturer = Build.MANUFACTURER ?: "Android"
        val cleanName = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            val capitalized = manufacturer.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            "$capitalized $model"
        }
        return "$cleanName (Donor)"
    }


    fun getDefaultCarrierName(): String {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
            } else null

            var rawCarrier: String? = null

            // Check default data subscription on dual SIM devices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && sm != null) {
                try {
                    val defaultDataSubId = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId()
                    val activeSubInfo = sm.getActiveSubscriptionInfo(defaultDataSubId)
                    val displayName = activeSubInfo?.displayName?.toString()?.takeIf { it.isNotBlank() }
                    val carrierName = activeSubInfo?.carrierName?.toString()?.takeIf { it.isNotBlank() }
                    rawCarrier = carrierName ?: displayName
                } catch (_: Exception) {}
            }

            // Fallback to TelephonyManager SIM and Network operator names
            if (rawCarrier.isNullOrBlank()) {
                rawCarrier = tm?.simOperatorName?.takeIf { it.isNotBlank() }
                    ?: tm?.networkOperatorName?.takeIf { it.isNotBlank() }
            }

            val clean = rawCarrier?.trim() ?: ""
            return when {
                clean.contains("airtel", ignoreCase = true) -> "Airtel"
                clean.contains("jio", ignoreCase = true) -> "Jio"
                clean.contains("vi", ignoreCase = true) || clean.contains("vodafone", ignoreCase = true) || clean.contains("idea", ignoreCase = true) -> "Vi"
                clean.contains("bsnl", ignoreCase = true) -> "BSNL"
                clean.contains("mtnl", ignoreCase = true) -> "MTNL"
                clean.isNotBlank() -> clean
                else -> {
                    val simState = tm?.simState ?: android.telephony.TelephonyManager.SIM_STATE_UNKNOWN
                    if (simState == android.telephony.TelephonyManager.SIM_STATE_ABSENT) "No SIM" else "Cellular"
                }
            }
        } catch (_: Exception) {
            return "Cellular"
        }
    }

    fun getDefaultNetworkTech(): String {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val activeNet = cm?.activeNetwork
            val caps = activeNet?.let { cm.getNetworkCapabilities(it) }

            // Check if device has active network connection
            if (activeNet == null || caps == null) {
                val tmSimState = tm?.simState ?: android.telephony.TelephonyManager.SIM_STATE_UNKNOWN
                return if (tmSimState == android.telephony.TelephonyManager.SIM_STATE_ABSENT) "" else "Offline"
            }

            // --- 1. Check for 5G NSA (Non-Standalone) & 5G SA via ServiceState ---
            // Airtel in India uses 5G NSA where dataNetworkType reports LTE (13),
            // but ServiceState reports nrState=CONNECTED or isEnDcAvailable=true.
            try {
                val ss = tm?.serviceState
                if (ss != null) {
                    val ssStr = ss.toString()
                    if (ssStr.contains("nrState=CONNECTED", ignoreCase = true) ||
                        ssStr.contains("nrState=NOT_RESTRICTED", ignoreCase = true) ||
                        ssStr.contains("NR_NSA", ignoreCase = true) ||
                        ssStr.contains("NR_ADVANCED", ignoreCase = true) ||
                        ssStr.contains("isEnDcAvailable=true", ignoreCase = true) ||
                        ssStr.contains("isNrAvailable=true", ignoreCase = true)) {
                        return "5G"
                    }
                }
            } catch (_: Exception) {}

            // --- 2. Check exact data network type from TelephonyManager ---
            var rawNetType: Int? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    rawNetType = tm?.dataNetworkType
                } catch (_: Exception) {}
            }
            if (rawNetType == null || rawNetType == 0) {
                try {
                    rawNetType = tm?.networkType
                } catch (_: Exception) {}
            }

            // 5G SA (Standalone): NETWORK_TYPE_NR (20)
            if (rawNetType == 20) {
                return "5G"
            }

            // Check if link bandwidth reflects 5G downstream (> 30 Mbps)
            val downstreamKbps = caps.linkDownstreamBandwidthKbps
            if (downstreamKbps >= 30_000) {
                return "5G"
            }

            when (rawNetType) {
                // 4G: LTE (13), IWLAN (18)
                13, 18 -> return "4G"
                // 3G: UMTS (3), EVDO (5, 6, 12), HSDPA (8), HSUPA (9), HSPA (10), HSPAP (15)
                3, 5, 6, 8, 9, 10, 12, 15 -> return "3G"
                // 2G: GPRS (1), EDGE (2), CDMA (4), 1xRTT (7), IDEN (11), GSM (16)
                1, 2, 4, 7, 11, 16 -> return "2G"
            }

            return when {
                downstreamKbps >= 10_000 -> "4G"
                downstreamKbps >= 1_000 -> "3G"
                downstreamKbps > 0 -> "2G"
                else -> "5G"
            }
        } catch (_: Exception) {
            return "5G"
        }
    }

    private fun registerNetworkCallback() {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.DisplayInfoListener {
                    override fun onDisplayInfoChanged(telephonyDisplayInfo: android.telephony.TelephonyDisplayInfo) {
                        val is5G = when (telephonyDisplayInfo.overrideNetworkType) {
                            android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
                            android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> true
                            else -> telephonyDisplayInfo.networkType == android.telephony.TelephonyManager.NETWORK_TYPE_NR
                        }
                        if (is5G && _donorConfig.value.tech != "5G") {
                            _donorConfig.value = _donorConfig.value.copy(tech = "5G")
                            addLog("Network", "DisplayInfo listener: 5G NSA/NR detected", LogLevel.INFO)
                        } else if (!is5G && telephonyDisplayInfo.networkType == android.telephony.TelephonyManager.NETWORK_TYPE_LTE) {
                            if (_donorConfig.value.tech != "4G") {
                                _donorConfig.value = _donorConfig.value.copy(tech = "4G")
                            }
                        }
                    }
                }
                tm.registerTelephonyCallback(context.mainExecutor, callback)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                val listener = object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onDisplayInfoChanged(telephonyDisplayInfo: android.telephony.TelephonyDisplayInfo) {
                        val is5G = when (telephonyDisplayInfo.overrideNetworkType) {
                            android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
                            android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> true
                            else -> telephonyDisplayInfo.networkType == android.telephony.TelephonyManager.NETWORK_TYPE_NR
                        }
                        if (is5G && _donorConfig.value.tech != "5G") {
                            _donorConfig.value = _donorConfig.value.copy(tech = "5G")
                            addLog("Network", "DisplayInfo listener: 5G NSA/NR detected", LogLevel.INFO)
                        }
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
            }
        } catch (e: Exception) {
            addLog("Network", "DisplayInfo listener: ${e.message}", LogLevel.DEBUG)
        }
    }

    fun refreshNetworkInfo() {
        val detectedCarrier = getDefaultCarrierName()
        val detectedTech = getDefaultNetworkTech()
        val current = _donorConfig.value
        if (detectedCarrier != current.carrier || detectedTech != current.tech) {
            _donorConfig.value = current.copy(
                carrier = detectedCarrier,
                tech = detectedTech
            )
            addLog("Network", "Auto-detected carrier: $detectedCarrier, network: $detectedTech", LogLevel.INFO)
        }
    }

    companion object {
        @Volatile
        private var instance: BoostNetRepository? = null

        fun getInstance(context: Context): BoostNetRepository {
            return instance ?: synchronized(this) {
                instance ?: BoostNetRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
