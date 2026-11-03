package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.model.DonorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CellularNetworkBinder(
    private val context: Context,
    private val onStateTransition: (DonorState, String) -> Unit,
    private val onUrgentAlert: (String) -> Unit,
    private val onLog: (String, String) -> Unit
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeCellularNetwork = AtomicReference<Network?>()
    val consecutiveConnectFailures = AtomicInteger(0)

    val capabilityGuard = CapabilityGuard { state, reason ->
        onStateTransition(state, reason)
    }

    private var isRegistered = false
    private var livenessProbeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onLog("CellularBinder", "Cellular network available: $network")
            activeCellularNetwork.set(network)
            consecutiveConnectFailures.set(0)
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps != null) {
                capabilityGuard.evaluate(caps, DonorState.THROTTLED)
            }
            onStateTransition(DonorState.ACTIVE, "Cellular connection bound")
        }

        override fun onLost(network: Network) {
            if (activeCellularNetwork.get() == network) {
                onLog("CellularBinder", "Cellular network lost: $network")
                handleCellularLoss("Cellular interface disconnected")
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (activeCellularNetwork.get() == network) {
                capabilityGuard.evaluate(networkCapabilities, DonorState.ACTIVE)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            if (activeCellularNetwork.get() == network) {
                onLog("CellularBinder", "Cellular LinkProperties updated: ${linkProperties.interfaceName}")
            }
        }

        override fun onUnavailable() {
            onLog("CellularBinder", "Cellular network unavailable")
            handleCellularLoss("Cellular network unavailable")
        }
    }

    fun start() {
        if (isRegistered) return
        onLog("CellularBinder", "Requesting dedicated cellular network...")
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.requestNetwork(request, networkCallback)
            isRegistered = true
        } catch (e: Exception) {
            onLog("CellularBinder", "Error requesting cellular network: ${e.message}")
            onStateTransition(DonorState.STOPPED, "Failed to request cellular network: ${e.message}")
            return
        }

        startLivenessProbe()
    }

    fun stop() {
        livenessProbeJob?.cancel()
        livenessProbeJob = null
        if (isRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                // ignore
            }
            isRegistered = false
        }
        activeCellularNetwork.set(null)
        capabilityGuard.reset()
        consecutiveConnectFailures.set(0)
    }

    fun recordConnectionSuccess() {
        consecutiveConnectFailures.set(0)
    }

    fun recordConnectionFailure(error: Throwable) {
        val count = consecutiveConnectFailures.incrementAndGet()
        onLog("CellularBinder", "Cellular connection failed ($count/5): ${error.message}")
        if (count >= 5) {
            onLog("CellularBinder", "Fast circuit breaker triggered: 5 consecutive failures")
            handleCellularLoss("Fast circuit breaker: 5 consecutive cellular connect failures")
        }
    }

    private fun handleCellularLoss(reason: String) {
        activeCellularNetwork.set(null)
        consecutiveConnectFailures.set(0)
        capabilityGuard.reset()
        onUrgentAlert("ALERT_NETWORK_LOST")
        onStateTransition(DonorState.STOPPED, reason)
    }

    /**
     * Resolves domain names strictly using the cellular network handle (preventing Wi-Fi DNS leak).
     */
    fun resolveDomainOnCellular(domain: String): Array<InetAddress> {
        val network = activeCellularNetwork.get()
            ?: throw IOException("No active cellular network handle available for DNS resolution")
        return network.getAllByName(domain)
    }

    /**
     * Periodic 30s liveness probe to detect silent cellular suspensions by aggressive OEMs during screen-off.
     */
    private fun startLivenessProbe() {
        livenessProbeJob?.cancel()
        livenessProbeJob = scope.launch {
            while (isActive) {
                delay(30000L) // 30 seconds
                val network = activeCellularNetwork.get() ?: continue
                try {
                    // Quick probe: open and immediately close a test socket via cellular socket factory
                    val testSocket: Socket = network.socketFactory.createSocket()
                    testSocket.soTimeout = 4000
                    // Connect to Cloudflare DNS IP on port 53 with short timeout
                    testSocket.connect(InetSocketAddress("1.1.1.1", 53), 4000)
                    testSocket.close()
                    consecutiveConnectFailures.set(0)
                } catch (e: Exception) {
                    onLog("CellularBinder", "Periodic liveness probe warning: ${e.message}")
                    recordConnectionFailure(e)
                }
            }
        }
    }
}
