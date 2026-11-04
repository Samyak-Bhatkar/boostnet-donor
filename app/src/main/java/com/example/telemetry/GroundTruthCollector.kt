package com.example.telemetry

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicReference

data class LastSocketError(
    val streamId: Long,
    val exceptionClass: String,
    val errnoMessage: String,
    val monoNs: Long
)

/**
 * Gathers deterministic Android OS ground truth to disaggregate physical stalls
 * across Local Link, Phone OS, and Cellular Path.
 */
class GroundTruthCollector(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val lastError = AtomicReference<LastSocketError?>()

    fun recordError(streamId: Long, error: Throwable) {
        lastError.set(
            LastSocketError(
                streamId = streamId,
                exceptionClass = error.javaClass.simpleName,
                errnoMessage = error.message ?: "Unknown",
                monoNs = SystemClock.elapsedRealtimeNanos()
            )
        )
    }

    fun snapshot(cellular: Network?): JSONObject {
        val caps = cellular?.let { cm?.getNetworkCapabilities(it) }
        val lp = cellular?.let { cm?.getLinkProperties(it) }

        val restrictBg = try {
            when (cm?.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "ENABLED"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "WHITELISTED"
                else -> "DISABLED"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }

        val err = lastError.get()

        return JSONObject().apply {
            put("phone_mono_ns", SystemClock.elapsedRealtimeNanos())
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("sdk_int", Build.VERSION.SDK_INT)
            })
            put("app", JSONObject().apply {
                put("ignoring_battery_opt", pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false)
            })
            put("cellular", JSONObject().apply {
                put("net_handle_held", cellular != null)
                put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
                put("captive_portal", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ?: false)
                put("not_vpn", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ?: true)
                put("has_ipv4", lp?.linkAddresses?.any { it.address is Inet4Address } ?: false)
                put("has_ipv6", lp?.linkAddresses?.any { it.address is Inet6Address } ?: false)
                put("restrict_background", restrictBg)
            })
            if (err != null) {
                put("last_error", JSONObject().apply {
                    put("stream_id", err.streamId)
                    put("exception", err.exceptionClass)
                    put("errno", err.errnoMessage)
                    put("at_mono_ns", err.monoNs)
                })
            }
        }
    }
}
