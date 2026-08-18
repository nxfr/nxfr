# Cross-Platform Considerations

The NXFR architecture relies on strict boundaries to ensure the core protocol is write-once, run-anywhere, maximizing code reuse and security.

## Protocol Agnosticism

The NXFR protocol itself is completely platform-agnostic. The `nxfr-core` library acts as a pure state machine, taking incoming byte streams and timer events as input, and emitting state changes and outgoing bytes. It knows nothing about the underlying OS, file systems, or UI toolkits.

## Interoperability Requirements

To guarantee seamless transfers across vastly different operating systems, all implementations must strictly adhere to the protocol specification, including the use of CBOR framing and TLS 1.3 encryption.

## Path Validation

Path validation is a critical security and compatibility feature. To ensure files transferred from a Linux device do not corrupt a Windows filesystem, Windows reserved names (like `CON`, `PRN`, `AUX`, `NUL`, etc.) and invalid characters are strictly rejected on **ALL** platforms during the manifest validation phase.

## Platform-Specific Considerations

| Platform | Background Model | Crypto Storage | UI Integration |
|----------|------------------|----------------|----------------|
| **Linux** | Always-on Daemon | Secret Service | CLI / GTK / D-Bus |
| **Android** | Foreground Service | Android Keystore | Share Sheet / Notifications |
| **Windows** | System Tray App | DPAPI / Cert Store | Explorer Context Menu |
| **macOS** | Launch Agent | Keychain | Finder Extension |

## Future Targets

While Linux and Android are already shipped, future development aims to expand native clients to:
- **Windows**: Utilizing C# or Rust for native WinUI 3 integration.
- **macOS**: Utilizing Swift for native integration.
- **iOS**: Utilizing Swift for native integration.
