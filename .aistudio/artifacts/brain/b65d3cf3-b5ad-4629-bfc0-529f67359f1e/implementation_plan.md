# BoostNet 2.0 — Zero-Cost Local Multipath Architecture & Master Implementation Plan

**Objective**: Deliver a 100% free, zero-cloud-cost multipath aggregation system that bonds multiple Android cellular data connections (Jio, Airtel, Vi, BSNL) for Windows/Mac laptops with a seamless, non-technical user experience.

---

## 1. System Vision & Architecture Synthesis

BoostNet 2.0 operates strictly peer-to-peer over your local hotspot link ($0.00 cloud infrastructure) and introduces two critical breakthroughs:

1. **SNI-Sticky Session Table (Solves the Multi-SIM Banking / CAPTCHA Bug)**:
   - When browsing normal websites, the laptop engine inspects the **Server Name Indication (SNI)** in TLS `ClientHello` packets and `HTTP CONNECT` headers without decrypting user data.
   - The engine computes a consistent hash of the root domain (e.g., `Hash("chase.com") % Active_Donors`).
   - All connections to `*.chase.com`, `*.bankofamerica.com`, or `*.google.com` are strictly pinned to a single phone's cellular IP, eliminating IP-flapping and fraud lockouts.
   - Heavy media and CDN domains (`*.googlevideo.com`, `*.fbcdn.net`, `*.akamai.net`) are concurrently balanced across all available donors.

2. **Multi-SIM Download Accelerator Engine (True Speed Multiplication)**:
   - Rather than relying on broken TLS-interception hacks, BoostNet provides a clean Download Manager panel (with an optional 1-click browser extension).
   - The engine issues an upstream `HEAD` probe, verifies `Accept-Ranges: bytes`, splits the file into dynamic chunks (4 MB to 16 MB), and streams them concurrently across all connected phones using work-stealing and EWMA rate adaptation.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 WINDOWS LAPTOP CLIENT (Go)                             │
│                                                                                        │
│   ┌────────────────────────────────────────────────────────────────────────────────┐   │
│   │                        System Tray UI & Native Dashboard                       │   │
│   │    - Status: "Bonded: Jio 5G (95 Mbps) + Airtel 5G (85 Mbps) -> 180 Mbps"      │   │
│   │    - Mode Selector: [ Boost Mode (Browsing) ] | [ Download Accelerator ]       │   │
│   │    - Pairing Screen: Renders Laptop QR Code + 6-digit PAKE numeric fallback    │   │
│   └───────────────────────────────────────┬────────────────────────────────────────┘   │
│                                           │                                            │
│   ┌───────────────────────────────────────┴────────────────────────────────────────┐   │
│   │                 Local Front-End Layer (Listens on 127.0.0.1)                   │   │
│   │   - HTTP/HTTPS Proxy (:8080)             - SOCKS5 Transparent Proxy (:10800)   │   │
│   │   - Crash-Proof Windows Registry & WinInet Proxy Manager with Startup Self-Heal │   │
│   │   - Bypass List: <local>; 127.*; 10.*; 172.16.*; 192.168.*                     │   │
│   └───────────────────┬────────────────────────────────────────┬───────────────────┘   │
│                       │                                        │                       │
│   ┌───────────────────▼──────────────────┐ ┌───────────────────▼───────────────────┐   │
│   │    SNI-Sticky Connection Balancer    │ │     Multi-SIM Download Accelerator    │   │
│   │  - Inspects plain-text SNI headers   │ │  - HEAD probe & Accept-Ranges check   │   │
│   │  - Consistent-hash root domain table │ │  - Parallel Range chunking (4-16 MB)  │   │
│   │  - Bank / Auth Session Protection    │ │  - Work-stealing & EWMA rate matching │   │
│   │  - CDN / Media multi-phone spread    │ │  - ETag/If-Range resume verification  │   │
│   └───────────────────┬──────────────────┘ └───────────────────┬───────────────────┘   │
│                       │                                        │                       │
│   ┌───────────────────▼────────────────────────────────────────▼───────────────────┐   │
│   │                     Multipath Donor Dispatcher Pool                            │   │
│   │   - UDP Beacon Listener (:9999) & mDNS Auto-Discovery                          │   │
│   │   - RFC 1929 Authenticated SOCKS5 Client Pool (Direct to Phone :1080)          │   │
│   │   - Control Plane Client (:9998) + Telemetry Aggregator                        │   │
│   │   - Sub-50ms Health Monitor & Fast Fallback to Direct Internet                 │   │
│   └───────────────────────────────┬────────────────────────┬───────────────────────┘   │
└───────────────────────────────────┼────────────────────────┼───────────────────────────┘
                                    │ Local Wi-Fi (SoftAP)   │ Local Wi-Fi (SoftAP)
                                    ▼ 192.168.137.10         ▼ 192.168.137.11
┌───────────────────────────────────────────────────┐  ┌───────────────────────────────────────────────────┐
│              ANDROID DONOR NODE 1 (Jio)           │  │             ANDROID DONOR NODE 2 (Airtel)         │
│                                                   │  │                                                   │
│  - CameraX + ML Kit QR Scanner (Scans Laptop)     │  │  - CameraX + ML Kit QR Scanner (Scans Laptop)     │
│  - Android Keystore Encrypted Secrets             │  │  - Android Keystore Encrypted Secrets             │
│  - Hardened SOCKS5 Server on Wi-Fi IP :1080       │  │  - Hardened SOCKS5 Server on Wi-Fi IP :1080       │
│  - RFC 1929 Fail-Closed Authentication            │  │  - RFC 1929 Fail-Closed Authentication            │
│  - Unified SSRF, Bogon & NAT64 Filter             │  │  - Unified SSRF, Bogon & NAT64 Filter             │
│  - Directed Subnet UDP Heartbeat Beacon (:9999)   │  │  - Directed Subnet UDP Heartbeat Beacon (:9999)   │
│  - Strict Hardware Cellular Socket Binding:       │  │  - Strict Hardware Cellular Socket Binding:       │
│    ConnectivityManager.requestNetwork()           │  │    ConnectivityManager.requestNetwork()           │
│    cellularNetwork.socketFactory.createSocket()   │  │    cellularNetwork.socketFactory.createSocket()   │
│  - Indestructible Foreground Service (dataSync)   │  │  - Indestructible Foreground Service (dataSync)   │
└─────────────────────────┬─────────────────────────┘  └─────────────────────────┬─────────────────────────┘
                          │ Cellular Modem Only                                  │ Cellular Modem Only
                          ▼ (LTE / 5G)                                           ▼ (LTE / 5G)
                    [ Jio 4G / 5G ]                                        [ Airtel 4G / 5G ]
                          │                                                      │
                          └──────────────────────────┬───────────────────────────┘
                                                     ▼
                                            [ THE INTERNET ]
                              (Combined Speeds on Downloads, Streaming, & Web)
```

---

## 2. Inverted SoftAP Topology & Socket Binding

- **Hardware Topology**: The laptop runs a standard Windows Mobile Hotspot (SoftAP) on subnet `192.168.137.0/24`. All donor phones connect to the laptop's hotspot over Wi-Fi.
  - **Hotspot Bootstrap**: Use Win32 Wi-Fi Direct APIs to initialize SoftAP without requiring an active upstream internet connection on Windows (or secondary bootstrap via a temporary 5-second USB tethering connection from Phone 1).
- **Android Kernel Routing Bypass & Cellular DNS**:
  - The Android app requests a dedicated cellular network bearer via `ConnectivityManager.requestNetwork` (`TRANSPORT_CELLULAR`, `NET_CAPABILITY_INTERNET`).
  - SOCKS5 outbound sockets are instantiated directly through `cellularNetwork.socketFactory.createSocket()`, ensuring sockets are natively bound to the cellular modem descriptor at creation time (avoiding `Operation not permitted` exceptions on Xiaomi HyperOS and Samsung OneUI).
  - **Cellular DNS Enforcement**: All domain resolution inside the Android SOCKS5 daemon MUST call `cellularNetwork.getAllByName(host)` to prevent DNS timeouts on the local Wi-Fi interface.
  - Incoming requests arrive over local Wi-Fi, but all outbound internet traffic exits strictly through the SIM card.
- **Directed Subnet UDP Discovery**:
  - Because Android hotspot drivers filter out global `255.255.255.255` broadcasts, the Android donor dynamically calculates the directed subnet broadcast address (e.g. `192.168.137.255`) from `LinkProperties` and emits 1 Hz signed beacons to port 9999.

---

## 3. Reverse-QR Pairing & PAKE Handshake

1. **Primary Reverse-QR Flow**:
   - Laptop generates a cryptographically random 256-bit session secret $S$ via `crypto/rand`.
   - Laptop displays a high-contrast QR code on its screen.
   - The user opens the BoostNet Donor app on their phone, taps **"Scan Laptop QR"**, and points their rear phone camera (CameraX + ML Kit) at the laptop screen.
   - The phone captures the secret, stores it securely in Android Keystore, and derives distinct HKDF-SHA256 subkeys:
     - `boostnet-socks-auth-v1` (SOCKS5 credentials)
     - `boostnet-discovery-hmac-v1` (UDP beacon signature)
     - `boostnet-control-auth-v1` (TCP control channel)
   - The phone switches its beacon from `UNPAIRED` to `PAIRED`.
2. **Fallback 6-Digit PAKE Flow**:
   - If the camera is unavailable, a 6-digit numeric PIN is negotiated using SPAKE2 over the local Wi-Fi control channel.
   - Rate-limited to 5 attempts before a 5-minute lockout.
3. **Zero-Prompt Auto-Reconnect**:
   - Once paired, devices reconnect automatically whenever the phone joins the hotspot. No IP addresses or ports are ever displayed to the user.

---

## 4. Operating Modes & Engine Logic

### Mode 1: Boost Mode (Browsing & SNI-Sticky Balancing)
- **Windows OS Proxy Automation**:
  - Calls `InternetSetOption` (`INTERNET_OPTION_PER_CONNECTION_OPTION`) to configure `127.0.0.1:8080`.
  - Configures the bypass list: `<local>;127.*;10.*;172.16.*;192.168.*`.
  - **Startup Self-Healing**: Checks the Windows registry on boot. If a prior ungraceful shutdown left proxy settings enabled, it immediately cleans them up.
  - **Direct Internet Fallback**: If all phones disconnect, traffic automatically falls back to direct Wi-Fi/Ethernet without dropping connections.
- **SNI Session Table**:
  - Inspects the SNI from TLS `ClientHello` packets.
  - Consistent-hashes the root domain: `Hash(rootDomain) % len(donors)`.
  - **Public Suffix List (PSL) Enforcement**: Registrable domain extraction MUST use the Public Suffix List (PSL via `golang.org/x/net/publicsuffix`) to correctly parse multi-part TLDs (`.co.uk`, `.com.au`, `.gov.in`), preventing all regional sites from hashing to the same donor.
  - Preserves bank, Google, and identity sessions on a single cellular IP while spreading heavy CDN/video traffic across all phones.

### Mode 2: Download Accelerator Mode (Multi-SIM Speed Multiplication)
- **Probing & Fallback**:
  - Executes an HTTP `HEAD` probe to verify `Accept-Ranges: bytes` and get `Content-Length`.
  - **Probe Fallback**: If HTTP `HEAD` yields non-200 (many CDNs and pre-signed S3 links reject `HEAD`), immediately issue a `GET Range: bytes=0-0` probe and inspect `206 Partial Content` with `Content-Range: bytes 0-0/<total>` to confirm range support and extract total file size.
- **Browser Extension IPC & Header Handoff**:
  - Download handoff from the browser extension must transmit `URL`, `Cookie`, `User-Agent`, and `Referer` headers over local loopback RPC (`http://127.0.0.1:10801/rpc` with an ephemeral auth token) so protected downloads (Google Drive, GitHub releases, Mega) succeed without authentication errors.
- **Chunk Partitioning & Work-Stealing**:
  - Divides the download into 4 MB to 16 MB chunks.
  - Distributes chunk requests concurrently across all phone SOCKS5 proxies (`donor1:1080`, `donor2:1080`).
  - If a cellular link experiences jitter or pauses, healthy links steal pending chunks to eliminate stragglers.
  - Verifies `If-Range` and `ETag` to ensure byte integrity with pause/resume support.

---

## 5. Master Implementation Roadmap

### Phase 1: Android Donor APK Updates
1. **CameraX & ML Kit Barcode Scanner**:
   - Add CameraX and ML Kit dependencies to `app/build.gradle.kts`.
   - Implement `PairingScannerScreen.kt` with live preview and instant QR parsing.
2. **Reverse Pairing State Management**:
   - Update `CryptoManager.kt` to import laptop-generated pairing secrets.
   - Update `HeartbeatBroadcaster.kt` to broadcast `status: "available_to_pair"` when unpaired and signed HMAC beacons when paired.
3. **One-Tap Unpair**: Add an "Unpair Laptop" button in the Settings Sheet.

### Phase 2: Windows Desktop Client (Single Go Binary)
1. **Repository Layout (`client/`)**:
   - `client/cmd/boostnet-client/`: Main entry point and native Windows system tray icon (`systray`).
   - `client/pkg/discovery/`: Directed UDP beacon listener (:9999) and mDNS auto-resolver.
   - `client/pkg/proxy/`: Local HTTP (:8080) and SOCKS5 (:10800) proxy front-end.
   - `client/pkg/sysproxy/`: Windows registry and `wininet.dll` proxy manager with startup crash recovery.
   - `client/pkg/balancer/`: SNI-sticky domain balancer with PSL parsing and consistent hashing for Boost Mode.
   - `client/pkg/downloader/`: Multi-SIM HTTP Range downloader with `Range: bytes=0-0` fallback and work-stealing for Download Accelerator Mode.
   - `client/pkg/pairing/`: CSPRNG QR code generator and PAKE fallback.
   - `client/pkg/ipc/`: Local loopback RPC server (`127.0.0.1:10801/rpc`) for browser extension header handoff.
2. **System Tray UI**:
   - Real-time aggregated throughput display, mode switcher, QR pairing window, and clean exit handler.

### Phase 3: Verification & Integration Testing
1. **Unit & Loopback Tests**: Verify SNI root-domain parsing with PSL, consistent hashing stability, and registry self-healing.
2. **Download Integrity Test**: Verify multi-chunk download against a range-supporting test file with SHA-256 validation.
3. **End-to-End Hotspot Test**: Verify reverse-QR pairing, SOCKS5 socket factory binding, cellular DNS resolution via `getAllByName`, and concurrent routing over cellular.
