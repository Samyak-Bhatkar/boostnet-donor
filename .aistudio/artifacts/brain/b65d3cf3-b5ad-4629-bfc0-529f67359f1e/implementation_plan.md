# BoostNet Ecosystem - End-to-End Non-Technical Architecture & Implementation Plan

This implementation plan defines the complete production architecture for **BoostNet**, tailored specifically for non-technical users on Windows/Mac laptops aggregating bandwidth across multiple Android phones without terminal commands, IP addresses, or manual proxy configurations.

---

## 1. System Vision & User Experience

### The Non-Technical User Journey
1. **Zero-Configuration Setup**:
   - User turns on their Windows laptop's Mobile Hotspot.
   - User connects their Android phone(s) to the laptop's Wi-Fi hotspot.
   - User opens the **BoostNet Donor** app on the phone and taps "Start Node".
2. **Effortless Reverse-QR Pairing**:
   - Instead of the awkward flow of a laptop webcam scanning a phone, the **Laptop App displays a high-contrast QR code** (containing a cryptographically random 256-bit pairing secret).
   - The user taps **"Scan Laptop QR"** inside the Android BoostNet Donor app and points their rear phone camera at the laptop screen.
   - Pairing is instantaneous: the phone captures the secret, derives subkeys via HKDF-SHA256, and immediately switches its discovery beacon from `UNPAIRED` to `PAIRED`.
   - *Alternative / Fallback*: A short 6-digit code with PAKE (Password-Authenticated Key Exchange) and a 2-minute pairing window with rate-limiting (lockout after 5 failed attempts).
3. **One-Click Operation on Laptop**:
   - The laptop client runs as a single lightweight binary sitting in the Windows System Tray.
   - Auto-discovers paired phones via mDNS and directed UDP beacons.
   - Displays friendly status: `"2 Phones Connected (Jio 5G + Airtel 5G) - Combined: 175 Mbps"`. No IP addresses or port numbers are ever shown.
4. **Two Honest Operating Modes**:
   - **Mode 1: Boost Mode (General Browsing)**:
     - Automatically configures the Windows OS system proxy to point to the local front-end (`127.0.0.1:8080`).
     - Load-balances new outbound connections across all active phones proportional to their measured throughput.
     - Protects system stability with an automated bypass list (`localhost; 127.*; 192.168.*`).
     - **Fail-Safe Crash Recovery**: Reverts the OS proxy on clean exit, and automatically detects/cleans up lingering proxy settings on startup if a prior crash occurred.
   - **Mode 2: Download Accelerator Mode (Multiplied Speeds)**:
     - A clean download panel where users paste large file links (or pass them via browser extension).
     - Engine performs an HTTP `HEAD` probe, verifies `Accept-Ranges`, divides the file into chunks, and streams ranges concurrently across all connected phones using work-stealing and EWMA rate adaptation.

---

## 2. Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                   WINDOWS DESKTOP APPLICATION (Go)                    │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │               System Tray UI & Dashboard (Win32)               │   │
│   │    - "2 Phones Connected: Jio 5G (85 Mbps) + Airtel (90 Mbps)" │   │
│   │    - Mode Selector: [ Boost Mode ] | [ Download Accelerator ]   │   │
│   │    - Pairing Screen: Displays Laptop QR + 6-digit PAKE code    │   │
│   └────────────────────────────────┬───────────────────────────────┘   │
│                                    │                                   │
│   ┌────────────────────────────────┴───────────────────────────────┐   │
│   │                    Local Proxy Front-End                      │   │
│   │                   (Listens on 127.0.0.1)                      │   │
│   │   - HTTP Proxy: :8080      - SOCKS5 Proxy: :10800             │   │
│   │   - OS Proxy Manager (Auto-configure, Bypass List, Crash-Fix) │   │
│   └─────────────────┬──────────────────────────────┬──────────────┘   │
│                     │                              │                   │
│   ┌─────────────────▼───────────┐  ┌───────────────▼──────────────┐   │
│   │   Boost Mode Load Balancer  │  │ Download Accelerator Engine  │   │
│   │ - Per-connection round-robin│  │ - HTTP Range chunking        │   │
│   │ - EWMA throughput weighting │  │ - Multi-phone work-stealing  │   │
│   │ - Auto-fallback to direct   │  │ - ETag/If-Range resume state │   │
│   └─────────────────┬───────────┘  └───────────────┬──────────────┘   │
│                     │                              │                   │
│   ┌─────────────────▼──────────────────────────────▼──────────────┐   │
│   │            Discovery & Authenticated Donor Pool               │   │
│   │   - UDP Beacon Listener (:9999) + mDNS Resolver               │   │
│   │   - Control Plane Client (:9998) + Telemetry Aggregator       │   │
│   │   - SOCKS5 Client Pool (RFC 1929 Auth, Session Secret Subkeys)│   │
│   └─────────────────┬──────────────────────────────┬──────────────┘   │
└─────────────────────┼──────────────────────────────┼───────────────────┘
                      │ Local Wi-Fi Subnet           │ Local Wi-Fi Subnet
                      ▼ (192.168.137.x)              ▼ (192.168.137.y)
┌───────────────────────────────────┐  ┌───────────────────────────────────┐
│     ANDROID DONOR NODE 1 (Jio)    │  │    ANDROID DONOR NODE 2 (Airtel)  │
│                                   │  │                                   │
│  - Camera QR Scanner for pairing  │  │  - Camera QR Scanner for pairing  │
│  - SOCKS5 Server (:1080)          │  │  - SOCKS5 Server (:1080)          │
│  - Control Plane Server (:9998)   │  │  - Control Plane Server (:9998)   │
│  - UDP Heartbeat Beacon (:9999)   │  │  - UDP Heartbeat Beacon (:9999)   │
│  - Network.socketFactory Binding  │  │  - Network.socketFactory Binding  │
│  - SSRF & Bogon NAT64 Filter      │  │  - SSRF & Bogon NAT64 Filter      │
└─────────────────┬─────────────────┘  └─────────────────┬─────────────────┘
                  │ Cellular Modem                       │ Cellular Modem
                  ▼ (Strictly Cellular)                  ▼ (Strictly Cellular)
            [ Jio 4G/5G ]                          [ Airtel 4G/5G ]
                  │                                      │
                  └───────────────────┬──────────────────┘
                                      ▼
                                [ INTERNET ]
```

---

## 3. Pairing & Security Architecture

### A. Reverse-QR Pairing (Primary Flow)
1. **Laptop Generates Ephemeral Pair Session**:
   - Laptop client generates a cryptographically random 256-bit pairing secret $S$ using a CSPRNG (`crypto/rand`).
   - Laptop encodes a JSON payload: `{"id": "laptop-unique-id", "secret": "<base64>", "ts": 1730590000}`.
   - Laptop renders this as a QR code in a clean desktop window.
2. **Phone Scans Laptop Screen**:
   - Android BoostNet app provides a "Pair with Laptop" screen utilizing CameraX + ML Kit Barcode Scanning.
   - Phone camera scans the QR code on the laptop screen.
   - Phone validates the timestamp (rejects if older than 5 minutes).
   - Phone stores $S$ in Android Keystore / encrypted preferences.
   - Phone immediately derives subkeys using HKDF-SHA256 (`boostnet-socks-auth-v1`, `boostnet-discovery-hmac-v1`, `boostnet-control-auth-v1`).
   - Phone transitions beacon state from `UNPAIRED` to `PAIRED`.
3. **Laptop Verifies First Authenticated Beacon**:
   - Laptop verifies incoming UDP beacon on port 9999 signed with the derived `boostnet-discovery-hmac-v1` key.
   - Both devices display a matching 2-digit confirmation token for visual verification.
   - Laptop stores the paired secret in Windows Credential Manager / DPAPI for future zero-interaction auto-connect.

### B. Fallback 6-Digit Code (PAKE Flow)
- If the phone camera cannot be used (e.g., lens scratch), the laptop displays a 6-digit numeric pairing PIN.
- The devices execute a SPAKE2 / PAKE handshake over the local Wi-Fi control channel.
- The 6-digit PIN is strictly an authentication verifier during the handshake; the actual session key is negotiated cryptographically.
- Rate-limiting: maximum 5 failed pairing attempts followed by a 5-minute lockout.

### C. Zero-Configuration Auto-Reconnect
- Once paired, whenever the phone joins the laptop's hotspot, the laptop immediately authenticates the phone's beacon and adds it to the active multipath pool without user prompts.
- A one-tap "Revoke / Unpair" action is provided on both the phone and laptop.

---

## 4. Operating Modes Detailed Design

### Mode 1: Boost Mode (General Browsing)
- **Windows OS Proxy Automation**:
  - The Go client calls `InternetSetOption` (`INTERNET_OPTION_PER_CONNECTION_OPTION`) to set `127.0.0.1:8080` as the HTTP proxy.
  - Proxy Bypass String: `<local>;127.*;10.*;172.16.*;192.168.*`. This is essential so traffic destined for the laptop itself or the phone's local hotspot IP does not loop.
- **Fail-Safe Crash Recovery**:
  - On startup, the client checks if the Windows registry still points to `127.0.0.1:8080` from an ungraceful shutdown. If detected, it immediately clears the proxy before starting.
  - On clean shutdown (SIGINT, SIGTERM, System Tray Exit), a defer/hook restores the original proxy state.
  - If all phones disconnect, traffic automatically falls back to direct internet without disconnecting the user.
- **Per-Connection Load Balancing**:
  - Outbound TCP connections from browser tabs are distributed across healthy donors weighted by EWMA (Exponentially Weighted Moving Average) RTT and throughput.

### Mode 2: Download Accelerator Mode (Range Splitting)
- **URL Ingestion**: User pastes a direct download URL or clicks "Download with BoostNet" (via browser extension).
- **Probing**: The engine issues a lightweight `HEAD` request to check `Accept-Ranges: bytes` and get `Content-Length`.
- **Chunk Partitioning**:
  - Partitions the file into dynamic chunks (e.g., 4 MB to 16 MB).
  - Assigns chunks concurrently across all paired phones via their individual SOCKS5 proxies (`donor1:1080`, `donor2:1080`).
- **Work-Stealing & Fast Fallback**:
  - If Donor 2 slows down (e.g., cell tower congestion), Donor 1 steals pending chunks.
  - Validates `ETag` and `If-Range` headers to ensure byte-perfect reconstruction.
  - Saves a `.boostnet.part` metadata file enabling pause and resume.

---

## 5. Implementation Roadmap

### Phase 1: Android Donor App Enhancements
1. **Camera QR Scanner**:
   - Add CameraX and Google ML Kit Barcode Scanning dependencies.
   - Implement `PairingScannerScreen.kt` with live camera preview and instant QR parsing.
2. **Reverse Pairing State Management**:
   - Store paired laptop secret in `CryptoManager.kt`.
   - Update `HeartbeatBroadcaster.kt` to broadcast `status: "available_to_pair"` when unpaired, and signed HMAC beacons when paired.
   - Add 5-attempt rate-limiting and pairing timeout in `ControlChannelServer.kt`.
3. **One-Tap Unpair**: Add an "Unpair Laptop" button in the Settings Sheet.

### Phase 2: Windows Desktop Client (Single Go Binary)
1. **Directory Structure**:
   - `client/cmd/boostnet-client/`: Main entry point with Windows system tray icon (`systray`).
   - `client/pkg/discovery/`: UDP broadcast listener on port 9999 + mDNS resolver.
   - `client/pkg/proxy/`: Local HTTP and SOCKS5 proxy server on `127.0.0.1:8080` and `127.0.0.1:10800`.
   - `client/pkg/sysproxy/`: Windows Registry and `wininet.dll` system proxy manager with crash-recovery.
   - `client/pkg/balancer/`: Weighted multi-donor connection balancer for Boost Mode.
   - `client/pkg/downloader/`: Multi-part HTTP Range download engine with work-stealing for Download Accelerator Mode.
   - `client/pkg/pairing/`: CSPRNG QR code generator and PAKE handshake handler.
2. **System Tray UI**:
   - Tray menu: Status display, Toggle Boost Mode, Open Download Accelerator, Pair New Phone, Settings, Exit.

### Phase 3: Verification & Integration Testing
1. **Loopback & Mock Testing**: Verify Windows OS proxy toggle and automated recovery on abnormal termination.
2. **Range Downloader Verification**: Test multi-chunk download against a public range-supporting test file, validating MD5/SHA256 checksum integrity.
3. **End-to-End Mobile Hotspot Test**: Verify phone scanning laptop screen, instant pairing, beacon verification, and concurrent routing over cellular.
