package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.BoostNetRepository
import com.example.MainActivity
import com.example.R
import com.example.control.ControlChannelServer
import com.example.discovery.HeartbeatBroadcaster
import com.example.model.DonorState
import com.example.model.LogLevel
import com.example.model.TelemetryStats
import com.example.network.CellularNetworkBinder
import com.example.network.WifiNetworkMonitor
import com.example.proxy.Socks5ProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress

class BoostNetForegroundService : Service() {

    private lateinit var repository: BoostNetRepository
    private lateinit var quotaManager: QuotaManager
    private lateinit var thermalBatteryGuard: ThermalBatteryGuard
    private lateinit var cellularBinder: CellularNetworkBinder
    private lateinit var wifiMonitor: WifiNetworkMonitor
    private lateinit var socksProxyServer: Socks5ProxyServer
    private lateinit var controlServer: ControlChannelServer
    private lateinit var heartbeatBroadcaster: HeartbeatBroadcaster

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var throughputSamplerJob: Job? = null
    private var sessionStartTime = 0L
    private var lastSampledBytes = 0L
    private var currentWifiIp: InetAddress? = null

    override fun onCreate() {
        super.onCreate()
        repository = BoostNetRepository.getInstance(applicationContext)

        // Strict FGS Timing: Immediately post notification & call startForeground() within <5s
        createNotificationChannel()
        val initialNotification = buildNotification("Initializing BoostNet Donor node...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        acquireLocks()
        initializeComponents()
    }

    private fun initializeComponents() {
        quotaManager = QuotaManager(
            context = applicationContext,
            onStateTransition = { newState, reason -> handleStateTransition(newState, reason) },
            onForceCloseSockets = { socksProxyServer.closeAllActiveSockets() },
            onLog = { tag, msg -> repository.addLog(tag, msg, LogLevel.INFO) }
        )

        thermalBatteryGuard = ThermalBatteryGuard(
            context = applicationContext,
            onStateTransition = { newState, reason -> handleStateTransition(newState, reason) },
            onLog = { tag, msg -> repository.addLog(tag, msg, LogLevel.INFO) }
        )

        val groundTruthCollector = GroundTruthCollector(applicationContext)

        cellularBinder = CellularNetworkBinder(
            context = applicationContext,
            onStateTransition = { newState, reason -> handleStateTransition(newState, reason) },
            onUrgentAlert = { alert -> controlServer.broadcastUrgentAlert(alert) },
            onLog = { tag, msg -> repository.addLog(tag, msg, LogLevel.INFO) }
        )

        socksProxyServer = Socks5ProxyServer(
            cellularBinder = cellularBinder,
            quotaManager = quotaManager,
            groundTruthCollector = groundTruthCollector,
            getSessionSecret = { repository.sessionSecret.value },
            getCurrentState = { repository.donorState.value },
            getBlocklist = { repository.donorConfig.value.blocklist },
            onLog = { tag, msg -> repository.addLog(tag, msg, LogLevel.INFO) }
        )

        controlServer = ControlChannelServer(
            getSessionSecret = { repository.sessionSecret.value },
            getCurrentState = { repository.donorState.value },
            getTelemetryStats = { repository.telemetryStats.value },
            getGroundTruth = { groundTruthCollector.snapshot(cellularBinder.activeCellularNetwork.get()) },
            onCommandReceived = { cmd -> handleControlCommand(cmd) },
            onStateTransition = { newState, reason -> handleStateTransition(newState, reason) },
            onLog = { tag, msg -> repository.addLog(tag, msg, LogLevel.INFO) }
        )

        heartbeatBroadcaster = HeartbeatBroadcaster(
            context = applicationContext,
            getNodeId = { repository.donorConfig.value.nodeId },
            getSessionSecret = { repository.sessionSecret.value },
            getCurrentState = { repository.donorState.value },
            getSocksPort = { repository.donorConfig.value.socksPort },
            getCtrlPort = { repository.donorConfig.value.ctrlPort },
            getBroadcastPort = { repository.donorConfig.value.broadcastPort },
            onLog = { tag, msg -> repository.addLog(tag, msg, LogLevel.INFO) }
        )

        wifiMonitor = WifiNetworkMonitor(applicationContext) { newIp ->
            handleWifiIpChange(newIp)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startDonorRelay()
            ACTION_STOP -> stopDonorRelay()
            ACTION_PAUSE_DRAIN -> handleStateTransition(DonorState.DRAINING, "User paused node")
            ACTION_RESUME -> handleStateTransition(DonorState.ACTIVE, "User resumed node")
        }
        return START_STICKY
    }

    private fun startDonorRelay() {
        sessionStartTime = System.currentTimeMillis()
        repository.updateState(DonorState.ACTIVE, "Starting cellular routing")
        repository.addLog("Service", "BoostNet Foreground Service starting...", LogLevel.INFO)

        thermalBatteryGuard.start()
        cellularBinder.start()
        wifiMonitor.start()

        // Bind proxy & control servers if Wi-Fi IP already available
        val initialWifiIp = WifiNetworkMonitor.getWifiIpAddress(applicationContext)
        handleWifiIpChange(initialWifiIp)

        startThroughputSampler()
    }

    private fun stopDonorRelay() {
        repository.addLog("Service", "Stopping BoostNet Foreground Service...", LogLevel.INFO)
        handleStateTransition(DonorState.STOPPED, "Service stopped")
        teardownAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleStateTransition(newState: DonorState, reason: String) {
        val oldState = repository.donorState.value
        if (oldState == newState) return

        repository.updateState(newState, reason)

        // React to state changes
        when (newState) {
            DonorState.ACTIVE -> {
                heartbeatBroadcaster.start()
            }
            DonorState.THROTTLED -> {
                heartbeatBroadcaster.start()
            }
            DonorState.DRAINING -> {
                heartbeatBroadcaster.start()
            }
            DonorState.STOPPED -> {
                heartbeatBroadcaster.stop()
                socksProxyServer.closeAllActiveSockets()
            }
        }

        updateNotification()
    }

    private fun handleControlCommand(command: String) {
        when (command) {
            "PAUSE", "DRAIN" -> handleStateTransition(DonorState.DRAINING, "Command from laptop: $command")
            "RESUME" -> {
                if (quotaManager.getRemainingBytes() > 0 && thermalBatteryGuard.batteryPercent > 15) {
                    handleStateTransition(DonorState.ACTIVE, "Command from laptop: RESUME")
                }
            }
        }
    }

    private fun handleWifiIpChange(newIp: InetAddress?) {
        if (newIp == null) {
            repository.addLog("WifiMonitor", "No Wi-Fi IP assigned. Pausing proxy listeners.", LogLevel.WARN)
            currentWifiIp = null
            socksProxyServer.stop()
            controlServer.stop()
            heartbeatBroadcaster.stop()
            return
        }

        if (newIp != currentWifiIp) {
            repository.addLog("WifiMonitor", "Wi-Fi IP updated to $newIp. Binding proxy & control listeners.", LogLevel.INFO)
            currentWifiIp = newIp
            val config = repository.donorConfig.value
            socksProxyServer.start(newIp, config.socksPort)
            controlServer.start(newIp, config.ctrlPort)
            heartbeatBroadcaster.start()
        }
    }

    private fun startThroughputSampler() {
        throughputSamplerJob?.cancel()
        lastSampledBytes = quotaManager.getUsedBytes()
        throughputSamplerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L) // 1 Hz sample rate
                val currentBytes = quotaManager.getUsedBytes()
                val deltaBytes = (currentBytes - lastSampledBytes).coerceAtLeast(0L)
                lastSampledBytes = currentBytes

                val mbps = (deltaBytes * 8.0) / 1_000_000.0
                val durationSec = if (sessionStartTime > 0) (System.currentTimeMillis() - sessionStartTime) / 1000 else 0

                val stats = TelemetryStats(
                    instantaneousMbps = mbps,
                    bytesRelayedSession = currentBytes,
                    activeConnections = socksProxyServer.activeConnections.get(),
                    cellularRttMs = 38, // base telemetry probe
                    batteryPercent = thermalBatteryGuard.batteryPercent,
                    isCharging = thermalBatteryGuard.isCharging,
                    thermalStatus = thermalBatteryGuard.thermalStatus,
                    quotaRemainingBytes = quotaManager.getRemainingBytes(),
                    sessionDurationSeconds = durationSec,
                    stateReason = repository.telemetryStats.value.stateReason
                )
                repository.updateTelemetry(stats)
                updateNotification()
            }
        }
    }

    /**
     * Android 15+ 6-hour background cap handler for dataSync foreground service type
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            repository.addLog("Service", "Android 15+ onTimeout(startId=$startId, fgsType=$fgsType) fired. Cleanly shutting down.", LogLevel.WARN)
            handleStateTransition(DonorState.STOPPED, "Android 15 6h background limit reached")
            controlServer.broadcastUrgentAlert("ALERT_FGS_TIMEOUT")
            teardownAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun teardownAll() {
        throughputSamplerJob?.cancel()
        throughputSamplerJob = null

        socksProxyServer.stop()
        controlServer.stop()
        heartbeatBroadcaster.stop()
        cellularBinder.stop()
        wifiMonitor.stop()
        thermalBatteryGuard.stop()
        quotaManager.persistUsage()

        releaseLocks()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoostNet:DonorWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours max safe acquire
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(lockMode, "BoostNet:DonorWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            repository.addLog("Service", "Error acquiring Wake/Wifi locks: ${e.message}", LogLevel.WARN)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BoostNet Donor Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time cellular multipath relay status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BoostNetForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, BoostNetForegroundService::class.java).apply { action = ACTION_PAUSE_DRAIN },
            PendingIntent.FLAG_IMMUTABLE
        )

        val carrier = repository.donorConfig.value.carrier
        val state = repository.donorState.value
        val speed = "%.1f".format(repository.telemetryStats.value.instantaneousMbps)
        val mbRelayed = repository.telemetryStats.value.bytesRelayedSession / (1024 * 1024)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BoostNet Donor ($carrier 4G/5G): ${state.label}")
            .setContentText(statusText.ifEmpty { "$speed Mbps • ${mbRelayed} MB relayed" })
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(""))
    }

    override fun onDestroy() {
        teardownAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "boostnet_donor_channel"

        const val ACTION_START = "com.example.boostnet.START"
        const val ACTION_STOP = "com.example.boostnet.STOP"
        const val ACTION_PAUSE_DRAIN = "com.example.boostnet.PAUSE"
        const val ACTION_RESUME = "com.example.boostnet.RESUME"
    }
}
