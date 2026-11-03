package com.example.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.crypto.CryptoManager
import com.example.model.DonorState
import com.example.network.WifiNetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicLong

class HeartbeatBroadcaster(
    private val context: Context,
    private val getNodeId: () -> String,
    private val getSessionSecret: () -> String,
    private val getCurrentState: () -> DonorState,
    private val getSocksPort: () -> Int = { 1080 },
    private val getCtrlPort: () -> Int = { 9998 },
    private val getBroadcastPort: () -> Int = { 9999 },
    private val onLog: (String, String) -> Unit
) {
    private var broadcastJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val monotonicSeq = AtomicLong(1L)
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start() {
        stop()
        val currentState = getCurrentState()
        if (currentState == DonorState.STOPPED) {
            onLog("Heartbeat", "Node is STOPPED. UDP beacon is idle.")
            return
        }

        onLog("Heartbeat", "Starting UDP discovery beacon & mDNS on subnet...")
        registerMdns()

        broadcastJob = scope.launch {
            while (isActive) {
                val state = getCurrentState()
                if (state == DonorState.STOPPED) {
                    onLog("Heartbeat", "State shifted to STOPPED; stopping beacon.")
                    break
                }

                sendBroadcastPacket(state)
                delay(2000L) // 2.0s heartbeat
            }
        }
    }

    @Synchronized
    fun stop() {
        broadcastJob?.cancel()
        broadcastJob = null
        unregisterMdns()
    }

    private fun sendBroadcastPacket(state: DonorState) {
        try {
            val nodeId = getNodeId()
            val masterSecret = getSessionSecret()
            val discoveryKey = CryptoManager.getDiscoveryKey(masterSecret)
            val seq = monotonicSeq.incrementAndGet()
            val socksPort = getSocksPort()
            val ctrlPort = getCtrlPort()
            val broadcastPort = getBroadcastPort()

            // HMAC payload
            val messageToSign = "1|$nodeId|$seq|$socksPort|$ctrlPort|${state.name}"
            val hmac = CryptoManager.hmacSha256(discoveryKey, messageToSign)

            val payload = JSONObject().apply {
                put("v", 1)
                put("type", "hello")
                put("id", nodeId)
                put("seq", seq)
                put("port", socksPort)
                put("ctrlPort", ctrlPort)
                put("state", state.name)
                put("hmac", hmac)
                put("ts", System.currentTimeMillis())
            }.toString()

            val broadcastAddress = WifiNetworkMonitor.calculateBroadcastAddress(context)
            val bytes = payload.toByteArray(Charsets.UTF_8)

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.reuseAddress = true
                val packet = DatagramPacket(bytes, bytes.size, broadcastAddress, broadcastPort)
                socket.send(packet)
            }
        } catch (e: Exception) {
            onLog("Heartbeat", "Broadcast send error: ${e.message}")
        }
    }

    private fun registerMdns() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "BoostNet_${getNodeId()}"
                serviceType = "_boostnet._tcp"
                port = getCtrlPort()
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    onLog("mDNS", "mDNS service registered: ${NsdServiceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    onLog("mDNS", "mDNS registration failed: $errorCode")
                }
                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    onLog("mDNS", "mDNS service unregistered.")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    onLog("mDNS", "mDNS unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            onLog("mDNS", "mDNS initialization exception: ${e.message}")
        }
    }

    private fun unregisterMdns() {
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager?.unregisterService(registrationListener)
            } catch (e: Exception) {
                // ignore
            }
            registrationListener = null
        }
    }
}
