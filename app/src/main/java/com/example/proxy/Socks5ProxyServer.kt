package com.example.proxy

import com.example.crypto.CryptoManager
import com.example.telemetry.GroundTruthCollector
import com.example.model.DonorState
import com.example.network.CellularNetworkBinder
import com.example.network.SsrfFilter
import com.example.service.QuotaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class Socks5ProxyServer(
    private val cellularBinder: CellularNetworkBinder,
    private val quotaManager: QuotaManager,
    private val groundTruthCollector: GroundTruthCollector? = null,
    private val getSessionSecret: () -> String,
    private val getCurrentState: () -> DonorState,
    private val getBlocklist: () -> List<String>,
    private val onLog: (String, String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val streamCounter = java.util.concurrent.atomic.AtomicLong(0L)

    val activeConnections = AtomicInteger(0)
    val maxConcurrency = 32

    // Track active client sockets for graceful teardown
    private val activeClientSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    @Synchronized
    fun start(bindAddress: InetAddress, port: Int = 1080) {
        stop()
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(bindAddress, port))
            serverSocket = ss
            onLog("Socks5Server", "SOCKS5 proxy server bound to $bindAddress:$port (max $maxConcurrency conns)")

            serverJob = scope.launch {
                while (isActive && !ss.isClosed) {
                    try {
                        val clientSocket = ss.accept()
                        handleIncomingClient(clientSocket)
                    } catch (e: Exception) {
                        if (isActive && !ss.isClosed) {
                            onLog("Socks5Server", "Accept error: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onLog("Socks5Server", "Failed to bind SOCKS5 server to $bindAddress:$port: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
        closeAllActiveSockets()
    }

    fun closeAllActiveSockets() {
        val iterator = activeClientSockets.iterator()
        while (iterator.hasNext()) {
            val sock = iterator.next()
            try {
                sock.close()
            } catch (e: Exception) {
                // ignore
            }
            iterator.remove()
        }
        activeConnections.set(0)
    }

    private fun handleIncomingClient(clientSocket: Socket) {
        // Enforce concurrency cap
        val currentCount = activeConnections.incrementAndGet()
        if (currentCount > maxConcurrency) {
            activeConnections.decrementAndGet()
            onLog("Socks5Server", "Concurrency limit reached ($maxConcurrency). Rejecting client ${clientSocket.inetAddress}.")
            try {
                clientSocket.soTimeout = 2000
                sendSocksReply(clientSocket.getOutputStream(), 0x05.toByte()) // 0x05 = Connection refused
                clientSocket.close()
            } catch (e: Exception) {
                // ignore
            }
            return
        }

        activeClientSockets.add(clientSocket)

        scope.launch {
            var remoteSocket: Socket? = null
            val streamId = streamCounter.incrementAndGet()
            try {
                clientSocket.tcpNoDelay = true
                clientSocket.keepAlive = true
                clientSocket.soTimeout = 5000 // 5-second handshake timeout (Slowloris mitigation)

                val clientIn = DataInputStream(clientSocket.getInputStream())
                val clientOut = DataOutputStream(clientSocket.getOutputStream())

                // 1. RFC 1928 Greeting: Version 0x05, NMETHODS, METHODS
                val version = clientIn.readByte().toInt() and 0xFF
                if (version != 0x05) {
                    onLog("Socks5Server", "Invalid SOCKS version: $version from ${clientSocket.inetAddress}")
                    clientSocket.close()
                    return@launch
                }

                val nMethods = clientIn.readByte().toInt() and 0xFF
                val methods = ByteArray(nMethods)
                clientIn.readFully(methods)

                // Fail-Closed Auth: Check if RFC 1929 (0x02 Username/Password) is supported
                var supportsUserPass = false
                for (m in methods) {
                    if ((m.toInt() and 0xFF) == 0x02) {
                        supportsUserPass = true
                        break
                    }
                }

                if (!supportsUserPass) {
                    onLog("Socks5Server", "Client offered no acceptable auth (RFC 1929 required). Rejecting.")
                    clientOut.writeByte(0x05)
                    clientOut.writeByte(0xFF) // 0xFF = No acceptable methods
                    clientOut.flush()
                    clientSocket.close()
                    return@launch
                }

                // Accept RFC 1929 authentication
                clientOut.writeByte(0x05)
                clientOut.writeByte(0x02) // METHOD 0x02
                clientOut.flush()

                // 2. RFC 1929 Subnegotiation
                val authVer = clientIn.readByte().toInt() and 0xFF
                if (authVer != 0x01) {
                    clientSocket.close()
                    return@launch
                }
                val ulen = clientIn.readByte().toInt() and 0xFF
                val unameBytes = ByteArray(ulen)
                clientIn.readFully(unameBytes)

                val plen = clientIn.readByte().toInt() and 0xFF
                val passBytes = ByteArray(plen)
                clientIn.readFully(passBytes)
                val clientToken = String(passBytes, Charsets.UTF_8)

                // Validate token in constant time
                val secret = getSessionSecret()
                val pairingCode = CryptoManager.getPairingCode(secret)
                val isValid = CryptoManager.constantTimeStringEquals(clientToken, secret) ||
                        CryptoManager.constantTimeStringEquals(clientToken, pairingCode)

                if (!isValid) {
                    onLog("Socks5Server", "RFC 1929 auth failed: invalid token from ${clientSocket.inetAddress}")
                    clientOut.writeByte(0x01)
                    clientOut.writeByte(0x01) // 0x01 = Auth failure
                    clientOut.flush()
                    clientSocket.close()
                    return@launch
                }

                // Auth success
                clientOut.writeByte(0x01)
                clientOut.writeByte(0x00) // 0x00 = Auth success
                clientOut.flush()

                // 3. SOCKS5 Request
                val reqVer = clientIn.readByte().toInt() and 0xFF
                val cmd = clientIn.readByte().toInt() and 0xFF
                clientIn.readByte() // RSV
                val atyp = clientIn.readByte().toInt() and 0xFF

                if (reqVer != 0x05 || cmd != 0x01) {
                    // Only 0x01 (CONNECT) is supported. Reject BIND (0x02) and UDP ASSOCIATE (0x03)
                    sendSocksReply(clientOut, 0x07.toByte()) // 0x07 = Command not supported
                    clientSocket.close()
                    return@launch
                }

                // Check state: if DRAINING or STOPPED, reject new CONNECT
                val currentState = getCurrentState()
                if (currentState == DonorState.DRAINING || currentState == DonorState.STOPPED) {
                    onLog("Socks5Server", "Node in $currentState state. Rejecting new CONNECT.")
                    sendSocksReply(clientOut, 0x02.toByte()) // 0x02 = Connection not allowed by ruleset
                    clientSocket.close()
                    return@launch
                }

                // Resolve Target Address & Port
                var targetDomain: String? = null
                val candidateAddresses = mutableListOf<InetAddress>()

                when (atyp) {
                    0x01 -> { // IPv4
                        val ipBytes = ByteArray(4)
                        clientIn.readFully(ipBytes)
                        candidateAddresses.add(InetAddress.getByAddress(ipBytes))
                    }
                    0x03 -> { // Domain name
                        val dlen = clientIn.readByte().toInt() and 0xFF
                        val dbytes = ByteArray(dlen)
                        clientIn.readFully(dbytes)
                        targetDomain = String(dbytes, Charsets.UTF_8)

                        // Check domain blocklist
                        val blocklist = getBlocklist()
                        if (blocklist.any { targetDomain.equals(it, ignoreCase = true) || targetDomain.endsWith(".$it", ignoreCase = true) }) {
                            onLog("Socks5Server", "Domain $targetDomain blocked by user blocklist.")
                            sendSocksReply(clientOut, 0x02.toByte())
                            clientSocket.close()
                            return@launch
                        }

                        // Resolve domain strictly on cellular network
                        try {
                            val resolved = cellularBinder.resolveDomainOnCellular(targetDomain)
                            candidateAddresses.addAll(resolved)
                        } catch (e: Exception) {
                            onLog("Socks5Server", "DNS resolution failed on cellular for $targetDomain: ${e.message}")
                            sendSocksReply(clientOut, 0x04.toByte()) // 0x04 = Host unreachable
                            clientSocket.close()
                            return@launch
                        }
                    }
                    0x04 -> { // IPv6
                        val ipBytes = ByteArray(16)
                        clientIn.readFully(ipBytes)
                        candidateAddresses.add(InetAddress.getByAddress(ipBytes))
                    }
                    else -> {
                        sendSocksReply(clientOut, 0x08.toByte()) // 0x08 = Address type not supported
                        clientSocket.close()
                        return@launch
                    }
                }

                val port1 = clientIn.readByte().toInt() and 0xFF
                val port2 = clientIn.readByte().toInt() and 0xFF
                val targetPort = (port1 shl 8) or port2

                // 4. Post-DNS & Literal SSRF Filtering
                val safeAddresses = candidateAddresses.filter { SsrfFilter.isSafePublicAddress(it) }
                if (safeAddresses.isEmpty()) {
                    onLog("Socks5Server", "SSRF filter blocked target (domain: $targetDomain, candidate IPs: $candidateAddresses)")
                    sendSocksReply(clientOut, 0x02.toByte()) // 0x02 = Connection not allowed by ruleset
                    clientSocket.close()
                    return@launch
                }

                // 5. Connect Outbound Socket via Cellular Network.socketFactory
                val cellularNetwork = cellularBinder.activeCellularNetwork.get()
                if (cellularNetwork == null) {
                    onLog("Socks5Server", "No active cellular network handle for connect.")
                    sendSocksReply(clientOut, 0x03.toByte()) // 0x03 = Network unreachable
                    clientSocket.close()
                    return@launch
                }

                var connected = false
                var connectError: Throwable? = null

                for (safeAddress in safeAddresses) {
                    try {
                        val outbound = cellularNetwork.socketFactory.createSocket()
                        outbound.tcpNoDelay = true
                        outbound.keepAlive = true
                        outbound.connect(InetSocketAddress(safeAddress, targetPort), 10000) // 10s cellular connect timeout
                        remoteSocket = outbound
                        connected = true
                        cellularBinder.recordConnectionSuccess()
                        break
                    } catch (e: Throwable) {
                        connectError = e
                    }
                }

                if (!connected || remoteSocket == null) {
                    if (connectError != null) {
                        cellularBinder.recordConnectionFailure(connectError)
                        groundTruthCollector?.recordError(streamId, connectError)
                    }
                    onLog("Socks5Server", "Failed to connect to ${targetDomain ?: safeAddresses.first()}:$targetPort via cellular: ${connectError?.message}")
                    sendSocksReply(clientOut, 0x05.toByte()) // 0x05 = Connection refused
                    clientSocket.close()
                    return@launch
                }

                // 6. Send SOCKS5 Success Reply (0x00)
                val localAddr = remoteSocket.localAddress
                val localPort = remoteSocket.localPort
                val reply = ByteArray(10)
                reply[0] = 0x05
                reply[1] = 0x00 // Success
                reply[2] = 0x00 // RSV
                reply[3] = 0x01 // ATYP IPv4
                if (localAddr is Inet4Address) {
                    System.arraycopy(localAddr.address, 0, reply, 4, 4)
                }
                reply[8] = ((localPort ushr 8) and 0xFF).toByte()
                reply[9] = (localPort and 0xFF).toByte()
                clientOut.write(reply)
                clientOut.flush()

                // Reset timeout for streaming relay
                clientSocket.soTimeout = 0
                remoteSocket.soTimeout = 0

                // 7. Bi-directional Stream Relay with Half-Close Propagation and Byte Accounting
                relayStreams(clientSocket, remoteSocket)

            } catch (e: Exception) {
                if (e !is SocketTimeoutException && e !is EOFException) {
                    onLog("Socks5Server", "Relay exception: ${e.message}")
                }
            } finally {
                runCatching { clientSocket.close() }
                runCatching { remoteSocket?.close() }
                activeClientSockets.remove(clientSocket)
                activeConnections.decrementAndGet()
            }
        }
    }

    private suspend fun relayStreams(clientSocket: Socket, remoteSocket: Socket) {
        val clientIn = clientSocket.getInputStream()
        val clientOut = clientSocket.getOutputStream()
        val remoteIn = remoteSocket.getInputStream()
        val remoteOut = remoteSocket.getOutputStream()

        val jobClientToRemote = scope.launch {
            pipeStream(clientIn, remoteOut) { bytes ->
                quotaManager.recordTx(bytes)
            }
            runCatching { remoteSocket.shutdownOutput() }
        }

        val jobRemoteToClient = scope.launch {
            pipeStream(remoteIn, clientOut) { bytes ->
                quotaManager.recordRx(bytes)
            }
            runCatching { clientSocket.shutdownOutput() }
        }

        jobClientToRemote.join()
        jobRemoteToClient.join()
    }

    private fun pipeStream(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(65536) // 64 KB buffer
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
                onBytes(read.toLong())
            }
        } catch (e: Exception) {
            // Stream closed or error
        }
    }

    private fun sendSocksReply(out: OutputStream, replyCode: Byte) {
        try {
            val reply = byteArrayOf(
                0x05,      // SOCKS version
                replyCode, // reply code
                0x00,      // RSV
                0x01,      // ATYP IPv4
                0, 0, 0, 0, // BND.ADDR 0.0.0.0
                0, 0       // BND.PORT 0
            )
            out.write(reply)
            out.flush()
        } catch (e: Exception) {
            // ignore
        }
    }
}
