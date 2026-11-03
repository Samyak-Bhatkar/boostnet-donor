# BoostNet Donor - Native Android Cellular Proxy & Multipath Node

[![Platform](https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-brightgreen.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin_1.9+-blue.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack_Compose_M3-purple.svg)](https://developer.android.com/jetpack/compose)
[![Proxy](https://img.shields.io/badge/Proxy-RFC_1928_/_RFC_1929_SOCKS5-orange.svg)](https://www.rfc-editor.org/rfc/rfc1929)

**BoostNet Donor** is a high-performance native Android application that turns any Android smartphone into a dedicated cellular data node for laptop multipath aggregation.

When your phone is connected to a laptop's local Wi-Fi hotspot, BoostNet Donor uses low-level Android connectivity APIs to route all incoming proxy connections **strictly and exclusively out of the phone's 4G/5G cellular modem (Jio, Airtel, Vi, BSNL)**, bypassing the Wi-Fi uplink entirely.

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────┐
│             LAPTOP / MULTIPATH CLIENT                 │
│                                                        │
│  - Connects to Phone via Local Wi-Fi Hotspot          │
│  - Receives 1 Hz UDP Discovery Beacons on :9999       │
│  - Pulls 1 Hz Telemetry & Control on :9998            │
│  - Routes Upstream Traffic to SOCKS5 on :1080         │
└───────────────────────────┬────────────────────────────┘
                            │
               Local Wi-Fi Subnet (192.168.43.x)
                            │
┌───────────────────────────▼────────────────────────────┐
│               BOOSTNET DONOR (ANDROID)                 │
│                                                        │
│  ┌──────────────────────────────────────────────────┐  │
│  │              Local Listeners (Wi-Fi)             │  │
│  │  - SOCKS5 Proxy Server (:1080)                   │  │
│  │  - Authenticated TCP Control Channel (:9998)     │  │
│  │  - Directed Subnet UDP Heartbeat Beacon (:9999)  │  │
│  └──────────────────────────┬───────────────────────┘  │
│                             │                          │
│  ┌──────────────────────────▼───────────────────────┐  │
│  │             Security & Filtering Core            │  │
│  │  - Fail-Closed RFC 1929 Authentication           │  │
│  │  - HKDF-SHA256 Cryptographic Key Separation      │  │
│  │  - Unified SSRF, Bogon, and NAT64 Defense        │  │
│  └──────────────────────────┬───────────────────────┘  │
│                             │                          │
│  ┌──────────────────────────▼───────────────────────┐  │
│  │             Cellular Binding Engine              │  │
│  │  - ConnectivityManager.requestNetwork()          │  │
│  │  - Network.socketFactory.createSocket()          │  │
│  │  - 30s Liveness Probe & Fast Circuit Breaker     │  │
│  └──────────────────────────┬───────────────────────┘  │
└─────────────────────────────┼──────────────────────────┘
                              │
                    Cellular Modem Only
                              ▼
           [ Jio / Airtel / Vi / BSNL 4G/5G ]
                              │
                              ▼
                        [ INTERNET ]
```

---

## Key Features

### 1. Hardware Cellular Socket Binding
- Directly acquires the cellular bearer via `ConnectivityManager.requestNetwork` (`TRANSPORT_CELLULAR`, `NET_CAPABILITY_INTERNET`).
- Upstream sockets are created directly through `cellularNetwork.socketFactory.createSocket()` and connected to the target server, forcing traffic over the cellular radio even though default Android routing prefers Wi-Fi.
- Includes a 30-second cellular liveness probe to detect and recover from silent mobile carrier link degradation.

### 2. Hardened RFC 1928 / RFC 1929 SOCKS5 Server
- Binds to `Wi-Fi IP:1080` with a maximum concurrency limit of 32 simultaneous streams.
- **Fail-Closed Authentication**: Rejects unauthenticated connections (`0x00`) with `0xFF` (No acceptable methods). Authenticates RFC 1929 credentials using constant-time comparison (`MessageDigest.isEqual`) against the derived HKDF SOCKS5 subkey.
- **Bi-directional Half-Close**: Implements `shutdownOutput()` on socket EOF to prevent lingering `CLOSE_WAIT` sockets.

### 3. Unified SSRF, Bogon & NAT64 Defense
- Evaluates literal IPv4 (`0x01`), literal IPv6 (`0x04`), and cellular-resolved hostnames (`0x03`).
- **NAT64 Extraction**: Decodes IPv4 addresses embedded in the Well-Known Prefix (`64:ff9b::/96`) and evaluates underlying addresses.
- **IPv4-Mapped IPv6**: Unpacks `::ffff:0:0/96` to block hidden private IPs.
- Blocks loopback, private RFC 1918, link-local, carrier CGNAT (`100.64.0.0/10`), ULA, multicast, broadcast, and reserved bogon networks with an immediate `0x02` (Connection not allowed) reply.

### 4. Authenticated Control Plane & Telemetry
- Exposes a dedicated TCP control server on `Wi-Fi IP:9998`.
- Nonce challenge-response authentication.
- Streams 1 Hz JSON telemetry: uplink/downlink speeds, cellular latency (RTT), carrier name, signal level, battery level, thermal state, and remaining data quota.
- 15-second heartbeat watchdog automatically steps down to `DRAINING` if the aggregation engine crashes or disconnects.

### 5. Directed Subnet UDP Discovery Beacon
- Periodically broadcasts 1 Hz UDP discovery beacons on port 9999.
- Dynamically calculates the directed subnet broadcast address (e.g., `192.168.43.255`), avoiding silent drops from Android's Wi-Fi hotspot broadcast filtering.
- Includes monotonic sequence numbers (`seq`) and HMAC-SHA256 signatures.

### 6. 4-State Machine with Asymmetric Flap Hysteresis
- States: `STOPPED`, `ACTIVE`, `THROTTLED`, `DRAINING`.
- **Asymmetric Flap Protection (`CapabilityGuard`)**: Degraded conditions (unvalidated network, roaming, thermal throttle) immediately demote the state to `THROTTLED` (0 ms delay). Recovery back to `ACTIVE` requires a continuous 8-second verified stability window.

### 7. Indestructible Foreground Service
- Runs a foreground service with `foregroundServiceType="dataSync"`.
- Synchronously calls `startForeground()` within `onCreate()` to eliminate Android 14+ startup crashes.
- Holds a `PARTIAL_WAKE_LOCK` and a low-latency Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY`).
- Handles Android 15 `onTimeout(startId, fgsType)` gracefully.
- Includes OEM autostart helpers for Xiaomi (MIUI/HyperOS), Samsung (OneUI), Oppo/Realme (ColorOS), and Vivo (FuntouchOS).

### 8. AMOLED Jetpack Compose UI
- High-contrast, dark-mode-first Material 3 design.
- Real-time animated **Quota Ring** with visual fill and threshold warnings.
- Dynamic dual-series **Speed Sparkline** showing live uplink/downlink rates.
- **Carrier Badge** displaying active SIM operator and cellular signal bars.
- **Diagnostics Screen** with live event logs and an on-device SOCKS5 loopback self-test button.
- **Pairing Dialog** displaying a deterministic canvas-rendered QR code and 6-character human-readable pairing code.

---

## Project Structure

```
app/src/main/java/com/example/
├── MainActivity.kt                  # Startup, permissions, and edge-to-edge window setup
├── BoostNetViewModel.kt             # UI state bridge & telemetry flows
├── BoostNetRepository.kt            # Core coordinator & SOCKS5 loopback self-test
│
├── model/                           # Data models & state definitions
│   ├── DonorState.kt                # STOPPED, ACTIVE, THROTTLED, DRAINING
│   ├── DonorConfig.kt               # Hotspot IP, port bindings, and quota budgets
│   ├── TelemetryStats.kt            # Live throughput, RTT, battery, and cellular info
│   └── LogEntry.kt                  # Timestamped diagnostics log entry
│
├── crypto/                          # Cryptographic engine
│   └── CryptoManager.kt             # HKDF-SHA256, constant-time compare, pairing secrets
│
├── network/                         # Low-level networking & socket bindings
│   ├── CellularNetworkBinder.kt     # Cellular requestNetwork and socketFactory binding
│   ├── CapabilityGuard.kt           # Asymmetric hysteresis & stability timer
│   ├── SsrfFilter.kt                # RFC 1918, CGNAT, bogon, and NAT64 filter
│   └── WifiNetworkMonitor.kt        # Dual-stack Wi-Fi extraction & directed broadcast
│
├── proxy/                           # SOCKS5 proxy server
│   └── Socks5ProxyServer.kt         # RFC 1928 / RFC 1929 authenticated TCP proxy (:1080)
│
├── control/                         # Control plane
│   └── ControlChannelServer.kt      # Challenge-response TCP control server (:9998)
│
├── discovery/                       # Local discovery
│   └── HeartbeatBroadcaster.kt      # Directed UDP broadcast beacon (:9999)
│
├── service/                         # Android system integration
│   ├── BoostNetForegroundService.kt # Foreground service, wake locks, Android 15 onTimeout
│   ├── QuotaManager.kt              # Persistent data accounting with 5% IP overhead
│   ├── ThermalBatteryGuard.kt       # PowerManager thermal listener & battery receiver
│   └── OemAutostartHelper.kt        # Deep links for Xiaomi, Samsung, Oppo, Vivo settings
│
└── ui/                              # Jetpack Compose UI layer
    ├── MainScreen.kt                # Bottom navigation & screen switcher
    ├── HomeScreen.kt                # Primary donor dashboard & power toggle
    ├── DiagnosticsScreen.kt         # Live scrolling log viewer & SOCKS5 self-test
    ├── PairingDialog.kt             # Canvas QR code & pairing PIN
    ├── SettingsSheet.kt             # Quota and network configuration
    ├── components/                  # QuotaRing, SpeedSparkline, CarrierBadge
    └── theme/                       # AMOLED dark theme, color palette, typography
```

---

## Getting Started

### Prerequisites
- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17**
- Android device running **Android 8.0 (API 26)** or higher with an active cellular SIM (Jio, Airtel, Vi, etc.)

### Build & Run
1. Clone the repository:
   ```bash
   git clone https://github.com/Samyak-Bhatkar/boostnet-donor.git
   ```
2. Open the project in Android Studio.
3. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
4. Install and run on your test device.

### Quick Verification via Laptop Hotspot
1. Turn on Mobile Hotspot on your laptop (e.g. `192.168.137.1` or `192.168.43.1`).
2. Connect your Android phone to the laptop hotspot.
3. Launch **BoostNet Donor** and tap the main power toggle to enter `ACTIVE` state.
4. On your laptop, test the SOCKS5 proxy via `curl`:
   ```bash
   # Replace <PHONE_WIFI_IP> and <PASSWORD> with values from the app's Pairing Dialog
   curl -x socks5h://boostnet:<PASSWORD>@<PHONE_WIFI_IP>:1080 https://ipinfo.io
   ```
5. The returned IP address will match your phone's cellular provider IP, independent of your laptop's Wi-Fi internet uplink.

---

## Security Model

- **Session Secret Isolation**: The 256-bit session secret is generated on-device via `SecureRandom` and never stored in plaintext.
- **Key Separation**: Subkeys for SOCKS5 authentication, control plane authentication, and UDP beacon HMAC signing are derived independently via HKDF-SHA256 with strict domain separation strings.
- **Fail-Closed Networking**: The SOCKS5 proxy strictly denies unauthenticated requests with `0xFF` and immediately drops attempts targeting private or reserved addresses with `0x02`.
- **Zero Cloud Middlemen**: Operates strictly peer-to-peer over your local hotspot connection. No external tunnels or third-party servers required.

---

## License

This project is licensed under the Apache License 2.0.
