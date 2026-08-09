# Android Architecture

!!! info "Planned Platform"
    The Android architecture described below is currently planned and has not yet been fully implemented.

The Android architecture for NXFR must navigate the platform's strict lifecycle constraints. Unlike the Linux implementation, Android aggressively restricts background execution to preserve battery life and system resources.

## Design Notes

- **Kotlin Implementation:** The Android app will be written primarily in Kotlin, utilizing a Kotlin multiplatform or JNI wrapper around the Rust `nxfr-core` library. This ensures the pure protocol logic remains perfectly synced across all platforms.
- **Android NSD for mDNS:** Device discovery will leverage the built-in Network Service Discovery API (`android.net.nsd.NsdManager`) to advertise and scan for the `_nxfr._tcp` service.
- **Foreground Service:** Any active network receiving or sending will require a Foreground Service (`foregroundServiceType="dataSync"`). This service prevents Android from killing the network sockets during a transfer.
- **Persistent Notification:** As long as the foreground service is running, a persistent, low-priority notification will be displayed. When receiving is disabled, the service stops entirely to save battery.
- **Notification-Based Consent UI:** Incoming transfers will trigger a high-priority system notification displaying "Accept" and "Reject" buttons, along with file metadata.
- **Android Keystore:** Identity keys (ECDSA P-256) will be securely generated and stored using the hardware-backed Android Keystore system, preventing extraction.
- **Protocol Parity:** The Android client will use the exact same protocol, same CBOR framing, and same TLS 1.3 encryption standards as the Linux client.
