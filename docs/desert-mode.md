# NXFR Desert Mode: Autonomous Off-Grid Direct Transfers

## 1. Overview & Architecture

Desert Mode enables high-speed, secure, off-grid peer-to-peer file transfers between Android devices in environments with **zero Wi-Fi infrastructure and zero cellular connectivity**. 

The existing NXFR cryptographic transfer protocol (mTLS 1.3 + CBOR framing over TCP port 17394) runs **unmodified** across an ephemeral Wi-Fi Direct (P2P) or SoftAP subnet. **Zero changes to Rust cryptographic crates (`nxfr-crypto`, `nxfr-transport`, `nxfr-core`, `nxfr-ffi`) are required.**

```
   ┌─────────────────────────────────────────────────────────────┐
   │                     NXFR Protocol Stack                     │
   │  TLS 1.3 (mTLS) · SAS / TOFU · Framing · Chunk Streaming   │
   └──────────────────────────────┬──────────────────────────────┘
                                  │ TCP :17394
   ┌──────────────────────────────┴──────────────────────────────┐
   │                  Ephemeral Desert Subnet                    │
   │                                                             │
   │   Tier 1: Wi-Fi Direct (P2P / WFD)                          │
   │     - DNS-SD TXT discovery (`_nxfr._tcp`)                   │
   │     - Autonomous Group Owner (GO: 192.168.49.1)             │
   │     - Fallback: Device Name prefix filtering                │
   │                                                             │
   │   Tier 2: Autonomous SoftAP (Local-Only Hotspot)            │
   │     - Host: `startLocalOnlyHotspot` (192.168.43.1)          │
   │     - Client: `WifiNetworkSpecifier` binding                │
   └─────────────────────────────────────────────────────────────┘
```

---

## 2. Decision Tree & Protocol Flow

### Tier 1: Wi-Fi Direct (P2P) — Primary Path
1. **Service Registration & DNS-SD Discovery**:
   - The station registers a local DNS-SD service (`_nxfr._tcp`) carrying its rotating 8-byte `aid` (`advertised_id`), station name, and protocol port (`17394`).
   - Clients browse for `_nxfr._tcp` services using `WifiP2pDnsSdServiceRequest`.
   - **8-Second Timeout Fallback**: If DNS-SD discovery yields no peers within 8 seconds (e.g. OEM firmware DNS-SD filtering), fallback to legacy `discoverPeers()` filtered by device name containing `NXFR`.
2. **Group Formation & Negotiation**:
   - `WifiP2pManager.connect()` initiates P2P group formation with automatic 2× retry on `BUSY` status.
   - Upon connection, one device becomes Group Owner (`isGO = true`, canonical IP `192.168.49.1`), and the other becomes Client.
3. **Socket Binding & Handshake**:
   - **Host (GO)**: Ensures listener is active on `0.0.0.0:17394` (automatically accessible via `p2p*` network interface).
   - **Client**: Triggers standard manual connection to `192.168.49.1:17394`.
   - Full mTLS 1.3 handshake, TOFU fingerprint verification, Short Authentication String (SAS) derivation, and consent dialog occur identically to standard LAN transfers.

### Tier 2: Autonomous SoftAP — Fallback Path
When Wi-Fi Direct is unsupported or group negotiation fails repeatedly:
1. **Host**: Invokes `WifiManager.startLocalOnlyHotspot()`.
2. **Credentials Card**: Displays the OEM-generated SSID, Passphrase, and AP Host IP (typically `192.168.43.1`).
3. **Client (Android 10+ / API 29+)**: Connects programmatically via `WifiNetworkSpecifier` and binds process network routing using `ConnectivityManager.bindProcessToNetwork(network)`.
4. **Client (Legacy / Android 8-9)**: User joins SSID manually; app connects directly to host IP.

---

## 3. Lifecycle & Power Model

- **Foreground Keep-Alive**: When a Desert session is initiated or active, `NxfrService` sets `_desertSessionActive = true`.
- **Contract Enforcement**:
  $$\text{KeepAlive} = \text{isVisible} \lor \text{isListening} \lor \text{hasActiveTransfer} \lor \text{desertSessionActive}$$
  The background data sync foreground service remains active throughout the direct session.
- **Teardown Contract**:
  - Tapping **[END DIRECT LINK]** closes the P2P group or SoftAP reservation, unbinds network interfaces, resets `desertSessionActive = false`, and re-evaluates the lifecycle contract (stopping the service if Visibility is OFF and no transfers are active).

---

## 4. Performance & Expected Throughput

| Mode | Band / Physical Layer | Expected Throughput | Typical Transfer (1 GB) |
| :--- | :--- | :--- | :--- |
| **Wi-Fi Direct (5 GHz)** | 802.11ac / 802.11ax P2P | **25 – 60 MB/s** | ~18 – 40 seconds |
| **Wi-Fi Direct (2.4 GHz)**| 802.11n / 802.11g P2P | **8 – 18 MB/s** | ~60 – 120 seconds |
| **SoftAP (Local Hotspot)**| 802.11ac / 802.11n | **15 – 35 MB/s** | ~30 – 70 seconds |

---

## 5. Permission & Privacy Model

- **No Required Permissions**: Desert Mode permissions are entirely runtime and optional.
- **API 33+ (Android 13+)**: `android.permission.NEARBY_WIFI_DEVICES` (declared with `android:usesPermissionFlags="neverForLocation"`). Zero location tracking.
- **API ≤ 32**: `android.permission.ACCESS_FINE_LOCATION` (required by Android OS for Wi-Fi P2P scanning).
- **Graceful Degradation**: If permission is denied, Desert Mode explains why it is unavailable and disables the discovery trigger without crashing.
- **Identity Protection**: TXT records broadcast the rotating ephemeral `aid` (`advertised_id`), **never the raw permanent `device_id`**.
