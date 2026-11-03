package com.example

import android.content.Context
import com.example.crypto.CryptoManager
import com.example.model.DonorConfig
import com.example.model.DonorState
import com.example.model.LogEntry
import com.example.model.LogLevel
import com.example.model.TelemetryStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class BoostNetRepository(private val context: Context) {

    private val _donorState = MutableStateFlow(DonorState.STOPPED)
    val donorState = _donorState.asStateFlow()

    private val _donorConfig = MutableStateFlow(
        DonorConfig(
            nodeId = CryptoManager.getOrCreateNodeId(context),
            displayName = "Pixel 8 Pro (Donor)",
            carrier = "Jio",
            tech = "4G",
            quotaLimitBytes = 2L * 1024 * 1024 * 1024 // 2 GB
        )
    )
    val donorConfig = _donorConfig.asStateFlow()

    private val _telemetryStats = MutableStateFlow(TelemetryStats())
    val telemetryStats = _telemetryStats.asStateFlow()

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries = _logEntries.asStateFlow()

    private val _sessionSecret = MutableStateFlow(CryptoManager.getOrCreateSessionSecret(context))
    val sessionSecret = _sessionSecret.asStateFlow()

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
        addLog("Crypto", "Generated new pairing session secret. Previous laptop sessions invalidated.", LogLevel.WARN)
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
