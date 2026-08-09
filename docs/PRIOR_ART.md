# Prior Art & Competitive Analysis

This document analyzes existing file transfer protocols and applications, highlighting their strengths, weaknesses, and how they influenced the design of the NXFR protocol.

## 1. Apple AirDrop

### How it works
AirDrop uses Bluetooth Low Energy (BLE) for advertisement and discovery, and Apple Wireless Direct Link (AWDL) for peer-to-peer Wi-Fi data transfer. It relies on a Public Key Infrastructure (PKI) tied to Apple IDs and iCloud contacts to verify sender and receiver identities. Data is encrypted using TLS over the peer-to-peer Wi-Fi connection.

### Strengths
- **Ubiquity:** Built into billions of Apple devices with zero installation required.
- **Seamless UX:** Extremely fast discovery and transfer without manual pairing.
- **Privacy:** Can restrict discovery to "Contacts Only" using iCloud PKI.

### Weaknesses relevant to our use case
- **Ecosystem Lock-in:** Only works on Apple hardware (macOS, iOS, iPadOS).
- **Proprietary:** Closed-source protocol, preventing third-party implementations.
- **Cloud Dependency:** Requires an Apple ID and internet connectivity for contact resolution and PKI.
- **Hardware Dependent:** Relies heavily on specific Apple-controlled Wi-Fi and Bluetooth hardware behaviors.

### What NXFR borrows
- The one-tap "accept/reject" user consent model for incoming transfers.
- The concept of advertising only when the user explicitly opens a "receive" interface, maximizing privacy.

### What NXFR explicitly rejects and why
- **PKI and Accounts:** AirDrop's reliance on a central cloud provider for identity is contrary to NXFR's decentralized, offline-first philosophy. NXFR uses TOFU + SAS instead.
- **Custom Hardware Layers:** NXFR rejects proprietary link-layer protocols like AWDL in favor of standard TCP/IP to ensure true cross-platform compatibility.

---

## 2. Google Quick Share (formerly Nearby Share)

### How it works
Quick Share utilizes BLE for nearby device discovery and negotiates the best available transport for data transfer, which can be Wi-Fi Direct, Wi-Fi Aware, Bluetooth, or WebRTC via Google servers. It integrates deeply with Google accounts for contact-based sharing and mutual authentication.

### Strengths
- **Cross-platform support:** Available on Android, Windows, and Chrome OS.
- **Adaptive transport:** Seamlessly switches between offline (Wi-Fi Direct) and online (WebRTC) routes depending on network conditions.
- **Deep OS integration:** Built directly into Android's sharing menu.

### Weaknesses relevant to our use case
- **Proprietary and complex:** Closed protocol with a highly complex state machine spanning multiple radio technologies.
- **Google Account requirement:** Requires signing in to a Google account for contact sharing and best performance.
- **Not truly open:** While Windows apps exist, there is no official Linux or macOS support, and the protocol is not openly specified for third parties.

### What NXFR borrows
- The visibility controls (e.g., "Hidden", "Contacts", "Everyone"), which NXFR adapts into "Unpaired" and "Paired" trust levels.
- The use of robust chunking for handling large files over potentially unstable wireless links.

### What NXFR explicitly rejects and why
- **WebRTC and Cloud Relays:** NXFR rejects routing data through cloud servers, ensuring data never leaves the LAN.
- **Account Dependency:** NXFR requires zero accounts, preventing vendor lock-in and tracking.

---

## 3. KDE Connect

### How it works
KDE Connect establishes a TCP connection secured by TLS using self-signed certificates. Devices discover each other via UDP broadcasts or mDNS. Once paired (requiring user confirmation on both ends), devices exchange JSON-based control messages. It features a plugin architecture for file sharing, clipboard syncing, and remote input.

### Strengths
- **Open Source:** Fully open protocol and implementation.
- **Feature-rich:** Does much more than file transfer (notifications, remote control, media sync).
- **Strong Linux/Android support:** Excellent integration into desktop Linux environments.

### Weaknesses relevant to our use case
- **Heavyweight:** The plugin architecture makes it a complex, persistent daemon rather than a simple transfer tool.
- **Poor Windows/macOS experience:** Ports exist but often suffer from integration issues and instability.
- **Protocol overhead:** JSON over TLS is less efficient for large bulk transfers compared to binary framing.

### What NXFR borrows
- The use of self-signed TLS certificates for device identity.
- The concept of long-term pairing to avoid repeated authorization prompts for trusted devices.

### What NXFR explicitly rejects and why
- **JSON for control messages:** NXFR uses CBOR and binary framing for better parsing performance and lower overhead.
- **Daemon architecture:** NXFR is designed as an on-demand protocol, not a persistent background service, improving battery life and privacy.

---

## 4. LocalSend

### How it works
LocalSend is a Flutter-based cross-platform application that uses multicast UDP for discovery and standard TCP with HTTPS (REST APIs) for data transfer. It requires no internet connection, no accounts, and no pairing. Devices generate temporary SSL certificates for encryption.

### Strengths
- **True cross-platform:** Works on Windows, macOS, Linux, Android, and iOS due to Flutter.
- **Zero setup:** No pairing or accounts needed; works out of the box on LAN.
- **Open Source:** Completely free and open-source software.

### Weaknesses relevant to our use case
- **No persistent identity:** Lacks a pairing mechanism, meaning users must approve every single transfer, even from known devices.
- **Security model:** The lack of pinning or authentication makes it vulnerable to local Man-in-the-Middle (MITM) attacks.
- **HTTP Overhead:** Using HTTP/REST for large file transfers introduces unnecessary overhead compared to raw TCP streams.

### What NXFR borrows
- The strict "LAN-only, no internet required" philosophy.
- The cross-platform, standard-library-friendly approach to network programming.

### What NXFR explicitly rejects and why
- **HTTP as a transport:** NXFR uses a custom binary protocol over TCP to maximize throughput and enable efficient multiplexing/resuming.
- **Lack of authentication:** NXFR requires TOFU + SAS pairing to establish long-term, secure relationships between devices, which LocalSend lacks.

---

## 5. Syncthing

### How it works
Syncthing uses the Block Exchange Protocol (BEP) to continuously synchronize directories between devices. It uses TLS for mutual authentication and encryption, deriving device IDs from certificate hashes. It relies on global discovery servers and relay servers for NAT traversal when direct connections fail.

### Strengths
- **Robustness:** Incredible resume capabilities and delta-syncing for modified files.
- **Strong security:** TLS mutual authentication with cryptographic device IDs.
- **Decentralized:** No central accounts or storage; fully peer-to-peer.

### Weaknesses relevant to our use case
- **Continuous Sync, not Point-in-Time:** Syncthing is designed to keep folders mirrored, not to send a specific file to a friend once.
- **Complex setup:** Requires configuring shared folders, ignoring patterns, and managing background services.
- **Internet dependency (often):** While it works on LAN, it often relies on public discovery and relay servers to find peers.

### What NXFR borrows
- The cryptographic identity model: deriving the `device_id` from the SHA-256 hash of the TLS certificate's public key.
- The mutual TLS (mTLS) approach for securing the connection and authenticating both parties simultaneously.

### What NXFR explicitly rejects and why
- **Continuous synchronization:** NXFR is explicitly a point-in-time transfer protocol. State machines for continuous sync are vastly more complex.
- **Relay servers:** NXFR rejects external relays to ensure strict local-network privacy and simplify the protocol boundary.

---

## 6. Magic Wormhole

### How it works
Magic Wormhole uses a Password-Authenticated Key Exchange (PAKE) to securely connect two devices. Users exchange a short, one-time code (e.g., "7-crossover-clockwork"). A rendezvous server helps devices find each other and establish a direct TCP connection or fall back to a relay.

### Strengths
- **Extremely secure:** PAKE ensures that even the rendezvous server cannot intercept the transfer.
- **Easy human verification:** Short, dictionary-based codes are easy to read over the phone or across a room.
- **No permanent state:** No pairing or long-term keys to manage.

### Weaknesses relevant to our use case
- **Requires infrastructure:** Depends on a publicly available rendezvous server (and often a transit relay) to function.
- **No discovery:** Users must manually communicate the code out-of-band for every transfer.
- **No persistent trust:** Cannot seamlessly send files to frequently used devices without exchanging a new code every time.

### What NXFR borrows
- The use of short, human-readable codes for cryptographic verification (adapted into NXFR's 6-digit SAS).
- The focus on strong cryptographic guarantees without relying on central certificate authorities.

### What NXFR explicitly rejects and why
- **Rendezvous servers:** NXFR uses mDNS for zero-configuration local discovery instead of relying on external servers.
- **One-time codes for every transfer:** NXFR uses the code (SAS) only once during initial TOFU pairing, pinning the identity for future seamless transfers.

---

## 7. Snapdrop / PairDrop

### How it works
Snapdrop is a web-based application that uses WebRTC data channels for peer-to-peer file transfer directly within the browser. It uses a shared signaling server (usually hosted publicly) to discover other devices on the same public IP address or local network.

### Strengths
- **Zero installation:** Runs entirely in the web browser.
- **Frictionless UI:** Instantly see other devices on the network upon opening the page.
- **Cross-platform:** Works on literally any device with a modern web browser.

### Weaknesses relevant to our use case
- **Requires internet for signaling:** Even though the transfer is local, the devices must connect to the public signaling server to discover each other.
- **Performance limitations:** WebRTC data channels in browsers can struggle with very large files or thousands of small files.
- **No background transfers:** The browser tab must remain open and active for the transfer to complete.

### What NXFR borrows
- The visual simplicity of seeing available nearby devices instantly.
- The requirement for explicit user consent before a file begins downloading.

### What NXFR explicitly rejects and why
- **WebRTC and Signaling Servers:** NXFR uses pure TCP and mDNS to eliminate the need for any external internet connection or signaling infrastructure.
- **Browser-only constraints:** NXFR is designed as a native protocol to fully utilize the host OS's filesystem and networking performance.

---

## 8. Warpinator (Linux Mint)

### How it works
Warpinator is a file transfer tool developed for Linux Mint. It uses zeroconf/Avahi (mDNS) for local network discovery and gRPC over TLS for the data transport. Devices must be on the same network and share a common "group code" to see each other.

### Strengths
- **Reliable local discovery:** Excellent use of mDNS for zero-config networking.
- **Modern transport:** Uses gRPC for structured, reliable control messaging.
- **Simple setup:** Works immediately on Linux Mint without configuration.

### Weaknesses relevant to our use case
- **Linux-centric:** Originally designed specifically for Linux desktop environments; ports to other platforms are unofficial and sometimes flaky.
- **Security model:** Relies on a shared plaintext "group code" for isolation rather than strong cryptographic pairing between individual devices.
- **Heavy dependencies:** gRPC pulls in significant dependencies, making lightweight embedded or mobile implementations difficult.

### What NXFR borrows
- The exclusive use of mDNS/DNS-SD for local network discovery.
- The separation of control messages from raw file data chunks.

### What NXFR explicitly rejects and why
- **gRPC:** NXFR uses a custom binary framing layer with CBOR to dramatically reduce dependency bloat and simplify implementation in constrained environments.
- **Group Codes:** NXFR uses explicit cryptographic identity (ECDSA P-256) and per-device pairing instead of shared network passwords.

---

## Comparison Table

| Feature | NXFR | AirDrop | Quick Share | KDE Connect | LocalSend | Syncthing | Magic Wormhole | Snapdrop | Warpinator |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Open protocol** | **Yes** | No | No | Yes | Yes | Yes | Yes | Yes | Yes |
| **Discovery method** | **mDNS** | BLE | BLE/mDNS | UDP/mDNS | UDP | Global/Local | Server | Server | mDNS |
| **Transport** | **TCP** | AWDL | Wi-Fi/WebRTC | TCP | HTTP/TCP | TCP/QUIC | TCP/WebRTC | WebRTC | gRPC/TCP |
| **Authentication** | **TOFU + SAS** | PKI (Apple) | Google Acct | TOFU | None | mTLS Hash | PAKE | None | Group Code |
| **Encryption** | **TLS 1.3** | TLS | TLS/DTLS | TLS | HTTPS | TLS | NaCl | DTLS | TLS |
| **Resume support** | **Yes (Built-in)** | Yes | Yes | No | No | Yes (Delta) | No | No | No |
| **Cross-platform** | **Yes (Design)** | Apple Only | Android/Win | Linux/Android | Yes | Yes | Yes (CLI) | Yes (Web) | Linux focus |
| **Requires internet** | **No** | For PKI | For Accounts | No | No | Often | Yes | Yes | No |
| **Requires account** | **No** | Yes | Yes | No | No | No | No | No | No |

---

## Why NXFR is Distinct

NXFR occupies a unique space in the file transfer ecosystem by combining the security of cryptographic pairing with the simplicity of local-only discovery, without compromising on performance or cross-platform compatibility.

Unlike proprietary solutions like AirDrop and Quick Share, NXFR is an **open protocol specification**, meaning it is not tied to any single corporate ecosystem. It is platform-neutral by design, avoiding the Linux-first biases of KDE Connect or Warpinator, and the Apple-only lock-in of AirDrop.

Crucially, NXFR is entirely decentralized. It requires **no cloud services, no user accounts, and no relay servers**. All discovery and data transfer happen strictly on the local network. Security is handled via **TOFU + SAS pairing** (Trust On First Use with a Short Authentication String), which provides vastly better security against MITM attacks than zero-auth systems like LocalSend, while remaining significantly simpler for users than managing certificates or PKI.

From a technical standpoint, NXFR utilizes **binary framing with CBOR**, making it vastly more efficient than HTTP/REST or JSON-based protocols, allowing it to saturate gigabit LAN links easily. **Resume support is built into the protocol from day one** at the chunk level, ensuring large transfers survive network blips. Furthermore, NXFR enforces an **explicit user consent model** by default, preventing spam and preserving privacy.

Finally, NXFR is designed to be implementable using standard library primitives—requiring only standard TCP, TLS 1.3, and mDNS—avoiding heavy dependencies like gRPC or WebRTC, making it ideal for everything from high-end desktops to constrained mobile devices.
