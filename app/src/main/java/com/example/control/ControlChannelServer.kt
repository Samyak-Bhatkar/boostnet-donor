package com.example.control

import com.example.crypto.CryptoManager
import com.example.model.DonorState
import com.example.model.TelemetryStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ControlChannelServer(
    private val getSessionSecret: () -> String,
    private val getCurrentState: () -> DonorState,
    private val getTelemetryStats: () -> TelemetryStats,
    private val getGroundTruth: () -> JSONObject = { JSONObject() },
    private val onCommandReceived: (String) -> Unit,
    private val onStateTransition: (DonorState, String) -> Unit,
    private val onLog: (String, String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var telemetryJob: Job? = null
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private val telemetrySeq = AtomicLong(0L)

    private val authenticatedClients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val clientWriters = ConcurrentHashMap<Socket, PrintWriter>()

    private var drainedByWatchdog = false

    data class BufferedEvent(
        val seq: Long,
        val type: String,
        val monoNs: Long,
        val payload: JSONObject
    )

    private val eventRingBuffer = Collections.synchronizedList(ArrayList<BufferedEvent>())

    fun recordEvent(type: String, payload: JSONObject) {
        val ev = BufferedEvent(
            seq = telemetrySeq.incrementAndGet(),
            type = type,
            monoNs = SystemClock.elapsedRealtimeNanos(),
            payload = payload
        )
        synchronized(eventRingBuffer) {
            if (eventRingBuffer.size >= 50) {
                eventRingBuffer.removeAt(0)
            }
            eventRingBuffer.add(ev)
        }
    }


    @Synchronized
    fun start(bindAddress: InetAddress, port: Int = 9998) {
        stop()
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(bindAddress, port))
            serverSocket = ss
            onLog("ControlChannel", "Control channel server listening on $bindAddress:$port")

            serverJob = scope.launch {
                while (isActive && !ss.isClosed) {
                    try {
                        val clientSocket = ss.accept()
                        handleControlClient(clientSocket)
                    } catch (e: Exception) {
                        if (isActive && !ss.isClosed) {
                            onLog("ControlChannel", "Accept error: ${e.message}")
                        }
                    }
                }
            }

            startTelemetryBroadcast()
        } catch (e: Exception) {
            onLog("ControlChannel", "Failed to start control channel on $bindAddress:$port: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        val clients = authenticatedClients.toList()
        for (client in clients) {
            runCatching { client.close() }
        }
        authenticatedClients.clear()
        clientWriters.clear()

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        drainedByWatchdog = false
    }

    fun broadcastUrgentAlert(alert: String) {
        onLog("ControlChannel", "Broadcasting urgent alert: $alert")
        for ((_, writer) in clientWriters) {
            try {
                writer.println("ALERT $alert")
                writer.flush()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun handleControlClient(clientSocket: Socket) {
        scope.launch {
            try {
                clientSocket.soTimeout = 5000 // 5-second challenge response timeout
                clientSocket.keepAlive = true
                clientSocket.tcpNoDelay = true

                val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val writer = PrintWriter(clientSocket.getOutputStream(), true)

                // 1. Send random 16-byte nonce challenge
                val nonceBytes = ByteArray(16)
                secureRandom.nextBytes(nonceBytes)
                val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
                writer.println("CHALLENGE $nonce")

                // 2. Read client response: AUTH <hmac_hex>
                val authLine = reader.readLine()
                if (authLine == null || !authLine.startsWith("AUTH ")) {
                    writer.println("ERR_AUTH")
                    clientSocket.close()
                    return@launch
                }

                val clientHmac = authLine.substring(5).trim()
                val masterSecret = getSessionSecret()
                val controlKey = CryptoManager.getControlKey(masterSecret)
                val expectedHmac = CryptoManager.hmacSha256(controlKey, nonce)

                // Constant-time check
                if (!CryptoManager.constantTimeStringEquals(clientHmac, expectedHmac)) {
                    onLog("ControlChannel", "Control channel auth failed from ${clientSocket.inetAddress}")
                    writer.println("ERR_AUTH")
                    clientSocket.close()
                    return@launch
                }

                // Authentication succeeded
                writer.println("OK_AUTH")
                onLog("ControlChannel", "Authenticated laptop control channel connected: ${clientSocket.inetAddress}")

                // Cancel watchdog and auto-restore if drained by watchdog
                watchdogJob?.cancel()
                watchdogJob = null
                if (drainedByWatchdog) {
                    drainedByWatchdog = false
                    onLog("ControlChannel", "Laptop reconnected. Auto-restoring ACTIVE state.")
                    onStateTransition(DonorState.ACTIVE, "Laptop control channel reconnected")
                }

                authenticatedClients.add(clientSocket)
                clientWriters[clientSocket] = writer

                // Switch timeout to 0 (blocking read for commands)
                clientSocket.soTimeout = 0

                while (isActive) {
                    val command = reader.readLine() ?: break
                    val trimmed = command.trim()
                    if (trimmed.isEmpty()) continue

                    when {
                        trimmed.equals("PAUSE", ignoreCase = true) -> {
                            onLog("ControlChannel", "Received PAUSE command from laptop")
                            onCommandReceived("PAUSE")
                            writer.println("OK PAUSE")
                        }
                        trimmed.equals("RESUME", ignoreCase = true) -> {
                            onLog("ControlChannel", "Received RESUME command from laptop")
                            onCommandReceived("RESUME")
                            writer.println("OK RESUME")
                        }
                        trimmed.equals("DRAIN", ignoreCase = true) -> {
                            onLog("ControlChannel", "Received DRAIN command from laptop")
                            onCommandReceived("DRAIN")
                            writer.println("OK DRAIN")
                        }
                        trimmed.startsWith("PING", ignoreCase = true) -> {
                            val parts = trimmed.split(" ")
                            val clientMono = if (parts.size > 1) parts[1] else "0"
                            val phoneMono = SystemClock.elapsedRealtimeNanos()
                            writer.println("PONG $clientMono $phoneMono")
                        }
                        trimmed.equals("GET_BUFFERED_EVENTS", ignoreCase = true) -> {
                            val arr = JSONArray()
                            synchronized(eventRingBuffer) {
                                for (ev in eventRingBuffer) {
                                    arr.put(JSONObject().apply {
                                        put("seq", ev.seq)
                                        put("type", ev.type)
                                        put("mono_ns", ev.monoNs)
                                        put("payload", ev.payload)
                                    })
                                }
                            }
                            writer.println("EVENTS $arr")
                        }
                        else -> {
                            writer.println("ERR_UNKNOWN_CMD")
                        }
                    }
                }
            } catch (e: Exception) {
                // Client disconnected
            } finally {
                authenticatedClients.remove(clientSocket)
                clientWriters.remove(clientSocket)
                runCatching { clientSocket.close() }
                onLog("ControlChannel", "Control client disconnected.")

                // If all authenticated clients are gone, trigger 15s watchdog
                if (authenticatedClients.isEmpty() && getCurrentState().isRunning) {
                    startDisconnectWatchdog()
                }
            }
        }
    }

    private fun startDisconnectWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            onLog("ControlChannel", "Laptop disconnected. 15s watchdog started before auto-drain.")
            delay(15000L) // 15 seconds
            if (isActive && authenticatedClients.isEmpty()) {
                val state = getCurrentState()
                if (state == DonorState.ACTIVE || state == DonorState.THROTTLED) {
                    drainedByWatchdog = true
                    onLog("ControlChannel", "15s control watchdog expired. Transitioning to DRAINING to protect mobile data.")
                    onStateTransition(DonorState.DRAINING, "Control channel disconnected (15s watchdog)")
                }
            }
        }
    }

    private fun startTelemetryBroadcast() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            while (isActive) {
                delay(1000L) // 1 Hz telemetry
                if (authenticatedClients.isEmpty()) continue

                val stats = getTelemetryStats()
                val state = getCurrentState()
                val seq = telemetrySeq.incrementAndGet()

                val json = JSONObject().apply {
                    put("type", "telemetry")
                    put("v", 1)
                    put("seq", seq)
                    put("state", state.name)
                    put("mbps", "%.2f".format(stats.instantaneousMbps).toDoubleOrNull() ?: stats.instantaneousMbps)
                    put("relayedBytes", stats.bytesRelayedSession)
                    put("quotaRemaining", stats.quotaRemainingBytes)
                    put("battery", stats.batteryPercent)
                    put("charging", stats.isCharging)
                    put("thermal", stats.thermalStatus)
                    put("activeConns", stats.activeConnections)
                    put("rttMs", stats.cellularRttMs)
                    put("ts", System.currentTimeMillis())
                    put("phone_mono_ns", SystemClock.elapsedRealtimeNanos())
                    put("ground_truth", getGroundTruth())
                }.toString()

                for ((_, writer) in clientWriters) {
                    try {
                        writer.println(json)
                        writer.flush()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }
    }
}
