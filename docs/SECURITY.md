# NXFR Protocol Security and Threat Model

**Status:** Draft
**Date:** 2026-08-08
**Authors:** NXFR Protocol Working Group

## Introduction

This document outlines the comprehensive security architecture, threat model, and cryptographic design choices for the Nearby Xfer Protocol (NXFR). It is intended for protocol implementors, security auditors, and system architects. Implementations MUST conform to the security requirements detailed herein to ensure secure operation and user privacy.

The transition from traditional, heavily-centralized cloud file sharing mechanisms to a purely local, peer-to-peer approach introduces a unique set of security challenges. By operating strictly over the Local Area Network (LAN), NXFR eliminates the attack surface of public cloud infrastructure but exposes the protocol to the diverse and often hostile environment of local networks (e.g., public Wi-Fi in cafes, untrusted enterprise subnets, or compromised home IoT environments).

This document serves as the normative reference for security considerations. It does not replace the primary protocol specification but rather extends it with deep analysis of the design rationale and explicit requirements for safe implementation.

## Table of Contents

1. Security Objectives
2. Trust Model
3. Threat Model
4. Pairing Protocol Security Analysis
5. mDNS Privacy Analysis
6. Path Sanitization Security
7. Resource Exhaustion Defenses
8. TLS Configuration Requirements
9. Residual Risks
10. Security Recommendations for Implementors
11. Future Security Enhancements
12. Appendix and Audit Guidelines

---

## 1. Security Objectives

The NXFR protocol is designed with a core set of security objectives that MUST be met by all compliant implementations. These objectives ensure that user data remains secure and private while maintaining usability.

### 1.1 Confidentiality
The protocol MUST ensure that all control messages and file data transferred between devices are completely unreadable to any third-party eavesdropper on the local network.
- **Mechanism:** NXFR achieves this by mandating TLS 1.3 for all communication. Once the TCP connection is established, the entirety of the application-layer traffic is encrypted.
- **Cipher Suites:** We require strong authenticated encryption with associated data (AEAD) cipher suites such as `TLS_AES_256_GCM_SHA384` or `TLS_CHACHA20_POLY1305_SHA256`.
- **Scope:** No application data, not even metadata like filenames or file sizes, is transmitted in the clear.

### 1.2 Integrity
The protocol MUST guarantee that any modification, injection, or corruption of data by an active attacker or network error is reliably detected and rejected.
- **Transport Integrity:** The TLS 1.3 transport layer provides cryptographic integrity for the entire session stream via AEAD MACs.
- **Application Integrity:** The framing layer implements per-chunk SHA-256 verification for file data.
- **Rationale:** This ensures that large files are not corrupted during transit, even if the underlying TLS session were somehow compromised or suffered from subtle hardware-induced bit flips that bypassed the AEAD MAC.

### 1.3 Authentication
Devices MUST be able to cryptographically verify the identity of the peer they are communicating with, preventing impersonation.
- **Certificates:** NXFR uses mutual TLS (mTLS) with self-signed ECDSA P-256 certificates.
- **Pairing:** Authentication is achieved through a Trust On First Use (TOFU) model augmented by an out-of-band Short Authentication String (SAS) verification.
- **Pinning:** The `device_id` is pinned after pairing, ensuring that subsequent connections are strictly authenticated against the known public key.

### 1.4 Authorization
The protocol MUST ensure that no data is transferred without explicit authorization.
- **Default Deny:** NXFR requires explicit user consent for every incoming transfer by default.
- **No Forced Pushes:** A sender cannot force a receiver to accept a file.
- **Review Phase:** The `TRANSFER_REQUEST` control message allows the receiver to review the metadata before sending a `TRANSFER_ACCEPT`.

### 1.5 Privacy
The protocol MUST minimize the leakage of identifying information to the local network when a device is not actively participating in a transfer.
- **Hidden by Default:** NXFR achieves this through a strict policy. Devices MUST NOT advertise their mDNS presence unless the user has explicitly enabled receiving mode.
- **Anti-Tracking:** The protocol recommends regular rotation of the advertised `id` field to prevent long-term tracking.

### 1.6 Consent
The protocol MUST ensure that user consent is informed and unambiguous.
- **Informed UI:** The protocol achieves this by mandating that the `TRANSFER_REQUEST` contains a comprehensive manifest.
- **Details Provided:** This includes total size, file counts, and directory structures.
- **Outcome:** The receiving user can make an informed decision before any file data is actually transmitted over the stream.

---

## 2. Trust Model

The security of the NXFR protocol relies on a specific set of assumptions regarding the operational environment and the capabilities of attackers. This defines the trust model.

### 2.1 What is Trusted

- **The Local Operating System:**
  The host OS running the NXFR implementation is assumed to be secure. If the OS is compromised by malware, the NXFR implementation cannot guarantee security, as the malware could extract private keys or manipulate the UI.
- **The Platform Keystore:**
  The secure key storage mechanisms provided by the platform (e.g., Android Keystore, macOS Keychain, Windows DPAPI, Linux Secret Service) are trusted to securely store the ECDSA P-256 private keys and prevent unauthorized extraction. We assume the hardware backing (if available) is free from side-channel attacks that could leak the key.
- **The User:**
  The user is trusted to accurately verify the Short Authentication String (SAS) during the initial pairing process. The user is also trusted to make sound decisions when prompted to accept or reject incoming file transfers, and to actively review filenames and sizes.
- **Physical Proximity:**
  The protocol assumes that during the pairing phase, the users of the two devices are in physical proximity and can visually compare the SAS codes displayed on their respective screens.

### 2.2 What is NOT Trusted

- **The Local Area Network (LAN):**
  The LAN is considered fully hostile. Any device on the LAN, including routers, switches, and other endpoints, could be compromised or actively malicious. The LAN may be monitored by passive eavesdroppers or manipulated by active attackers.
- **DNS and mDNS Subsystems:**
  The discovery mechanism is completely untrusted. Any device can spoof mDNS advertisements, claim any name, or broadcast forged TXT records. Implementations must treat all discovered peers as untrusted until the TLS handshake and application-level authentication are complete.
- **Unpaired Peers:**
  Any device attempting to connect that has not successfully completed the SAS pairing process is treated as untrusted and MUST be subjected to TOFU and explicit user consent for all operations.
- **The Internet Connection:**
  The protocol does not rely on internet connectivity for any security property. All authentication and encryption are entirely local and peer-to-peer. No cloud CA or PKI is trusted.

---

## 3. Threat Model

This section exhaustively details the anticipated threats to the NXFR protocol, categorized by attack vector, along with the corresponding mitigations and accepted residual risks.

### T1: Passive Eavesdropper on LAN (Packet Sniffing)
- **Description:** An attacker connected to the same local area network passively monitors all network traffic. This could be a malicious user on public Wi-Fi, a compromised IoT device, or a rogue network administrator.
- **Preconditions:** Attacker is on the same broadcast domain or controls a routing node.
- **Execution:** The attacker uses a packet sniffer (like Wireshark or tcpdump) to capture the TCP streams between two NXFR devices during a file transfer.
- **Impact:** If successful, the attacker could read the contents of the transferred files, view the file names, and observe all control messages.
- **Mitigations:**
  - NXFR mandates TLS 1.3 for the entire session.
  - All application data, including the framing layer, CBOR control messages, and file chunks, is encrypted.
  - The passive attacker will only see the initial TLS handshake (with encrypted SNI/ALPN depending on ECH support) and encrypted application data.
- **Residual Risk:** The attacker can still perform traffic analysis. They can observe the source and destination IP addresses, the approximate size of the transfer (based on the total bytes sent), and the exact timing of the connection. This metadata leakage is an accepted risk for a LAN-based protocol.

### T2: Active MITM (ARP Spoofing, Rogue AP, DNS Hijacking)
- **Description:** An attacker actively intercepts and modifies traffic on the LAN.
- **Preconditions:** Attacker has the ability to manipulate layer 2/3 traffic (e.g., ARP spoofing) or controls the Wi-Fi access point.
- **Execution:** The attacker positions themselves between two legitimate devices. When device A attempts to connect to device B, it actually connects to the attacker. The attacker then connects to device B, relaying traffic while attempting to read or modify it.
- **Impact:** Complete compromise of confidentiality and integrity, allowing the attacker to steal files, inject malicious data, or drop packets.
- **Mitigations:**
  - **For paired devices:** The attacker will fail because they do not possess the private key corresponding to the pinned `device_id`. The TLS handshake will succeed, but the application layer will immediately reject the connection when the SPKI hash doesn't match the expected `device_id`.
  - **For first-time pairing (TOFU):** The attacker can attempt a MITM. However, because both the legitimate devices compute the SAS based on the TLS exporter (which incorporates the key exchange of the specific TLS session), the attacker cannot force both sides to derive the same SAS. The users will see different SAS codes and reject the pairing.
- **Residual Risk:** If users ignore the SAS mismatch and click "Accept" anyway during first-time pairing, the MITM attack will succeed. This relies heavily on user education and UX design.

### T3: Malicious Peer (Paired or Unpaired)
- **Description:** A device communicating via NXFR behaves maliciously at the application layer.
- **Preconditions:** Attacker is running a custom, malicious NXFR client.
- **Execution:** An attacker sends a continuous stream of `TRANSFER_REQUEST` messages with offensive filenames to harass a user, or attempts to send malware disguised as legitimate documents or images.
- **Impact:** User harassment, potential malware infection if the user accepts and executes the payload, or social engineering attacks.
- **Mitigations:**
  - Unpaired peers cannot force files onto a device; every transfer requires explicit user consent via the `TRANSFER_ACCEPT` message.
  - Implementations SHOULD include a "Block" feature that ignores all discovery and connection attempts from a specific `device_id`.
  - The UI MUST clearly display the `display_name` and file metadata (size, file count) to allow the user to make an informed decision.
- **Residual Risk:** A user might be socially engineered into accepting a malicious file. NXFR does not perform anti-virus scanning; this is left to the host OS.

### T4: Malicious File Paths (Directory Traversal, Overwrite Attacks)
- **Description:** A malicious sender crafts a `TRANSFER_REQUEST` with malicious `relative_path` values in the manifest.
- **Preconditions:** Attacker successfully initiates a transfer request.
- **Execution:** The attacker sets a file path to `../../../etc/shadow` or `C:\Windows\System32\malware.exe` in an attempt to overwrite critical system files or escape the designated download directory.
- **Impact:** Total system compromise, arbitrary file overwrite, privilege escalation, or destruction of user data.
- **Mitigations:**
  - NXFR requires strict path sanitization.
  - Receivers MUST validate all incoming paths before any file operations occur.
  - Paths MUST NOT be absolute, MUST NOT contain directory traversal sequences (`..`), and MUST NOT contain null bytes or platform-specific reserved names.
  - See Section 6 for exhaustive details on sanitization rules.
- **Residual Risk:** Bugs in the implementation of the path sanitization logic could lead to critical vulnerabilities. This is why the specification provides exhaustive rules for sanitization.

### T5: Memory/Resource Exhaustion (Oversized Payloads, Connection Flooding)
- **Description:** An attacker attempts a Denial of Service (DoS) by exhausting the receiver's resources.
- **Preconditions:** Attacker can route TCP packets to the victim's open NXFR port.
- **Execution:** The attacker opens multiple connections and sends massive `HELLO` frames, or sends a CBOR payload claiming a massive size, or floods the receiver with chunks without waiting for ACKs.
- **Impact:** The application crashes due to Out-Of-Memory (OOM) errors, or the device becomes unresponsive, denying service to legitimate users. Battery drain on mobile devices.
- **Mitigations:**
  - Strict limits are enforced at the framing layer: CONTROL frames are capped at 64 KiB, and CHUNK frames are capped at 4 MiB.
  - The protocol mandates an in-flight window of maximum 8 chunks.
  - Concurrent sessions and transfers are strictly bounded.
- **Residual Risk:** A sophisticated distributed denial of service (DDoS) on a large LAN could still consume network bandwidth, but the application itself should remain stable.

### T6: Transfer Spam / Notification Flooding
- **Description:** An attacker repeatedly sends transfer requests to cause a flood of UI notifications.
- **Preconditions:** Attacker is on the same LAN and knows the victim is accepting connections.
- **Execution:** The attacker connects, sends a `TRANSFER_REQUEST`, immediately disconnects, and repeats this process hundreds of times per second.
- **Impact:** The user's device becomes unusable due to constant notification pop-ups. Severe UX degradation.
- **Mitigations:**
  - Implementations MUST enforce rate limiting on incoming connections and `TRANSFER_REQUEST` messages from unpaired devices.
  - Successive requests from the same `device_id` SHOULD be coalesced or suppressed if the previous request is still pending or was recently rejected.
- **Residual Risk:** A highly motivated attacker could cycle through newly generated `device_id`s, bypassing simple rate limits based on ID. Implementations MAY need to apply IP-based rate limiting as a fallback.

### T7: Downgrade Attacks (Protocol Version, Cipher Suite)
- **Description:** An active MITM attempts to force the devices to use weaker, obsolete cryptographic standards.
- **Preconditions:** Attacker controls the network and can intercept/modify packets in transit.
- **Execution:** The attacker intercepts the TLS ClientHello and modifies it to strip out TLS 1.3 support, forcing a fallback to TLS 1.2 or weaker cipher suites (e.g., RSA key exchange or AES-CBC).
- **Impact:** The attacker exploits known vulnerabilities in older protocols (like POODLE, BEAST, or Lucky13) to break the encryption and read the traffic.
- **Mitigations:**
  - NXFR strictly mandates TLS 1.3. Fallback to TLS 1.2 or earlier MUST NOT be permitted.
  - Only a specific, strong list of AEAD cipher suites is permitted.
  - The application-layer `HELLO` frame also includes version negotiation, which is encrypted and authenticated by TLS 1.3, preventing tampering.
- **Residual Risk:** None identified within the scope of the cryptographic primitives, assuming the underlying TLS library is not vulnerable to implementation flaws.

### T8: Replay Attacks (Replaying Frames/Sessions)
- **Description:** An attacker captures a valid session and replays the packets later.
- **Preconditions:** Attacker has previously recorded a valid, successful transfer session.
- **Execution:** The attacker captures the network traffic of a legitimate file transfer and later replays the exact same TCP packets to the receiver, attempting to overwrite the file again or spam the user.
- **Impact:** Unauthorized file modification, resource consumption, or disruption of user workflow.
- **Mitigations:**
  - TLS 1.3 provides inherent cryptographic protection against replay attacks for the session itself. The server's random nonce prevents replay of the ClientHello.
  - NXFR explicitly forbids TLS 0-RTT data, which is historically vulnerable to replay.
  - Each session uses a unique, randomly generated `session_id`.
  - The `message_id` counter ensures that individual frames cannot be replayed within an active session.
- **Residual Risk:** None. The combination of TLS 1.3 and application-layer sequencing completely mitigates replay attacks.

### T9: Device Tracking via mDNS Advertisement
- **Description:** A passive observer tracks the physical location or presence of a user over time.
- **Preconditions:** Attacker monitors mDNS broadcast traffic on various networks (e.g., multiple coffee shops).
- **Execution:** A retail store or malicious actor monitors mDNS broadcasts to track customers' phones as they move through the store or between locations, correlating the persistent `device_id` in the TXT record.
- **Impact:** Loss of physical privacy and anonymity. Creation of behavioral profiles.
- **Mitigations:**
  - The primary mitigation is the "hidden by default" policy. The device only advertises when explicitly set to receive mode by the user.
  - The `id` field in the mDNS TXT record is a daily-rotating `advertised_id` derived via `SHA-256(device_id || YYYY-MM-DD)` (see Protocol §6.3.4). This breaks long-term correlation across days.
  - The real `device_id` is **never** transmitted in cleartext discovery traffic — it is only revealed inside the encrypted TLS 1.3 session after mutual authentication.
- **Residual Risk:** While in active receiving mode, the device is trackable within a single day via its `advertised_id`. Cross-day correlation requires breaking the SHA-256 pre-image.

### T10: Passive Tracking via UDP Beacon Sniffing
- **Description:** An attacker with a Wi-Fi sniffer records UDP beacon datagrams (port 17395) to track a device's physical movements across different networks over time.
- **Preconditions:** Attacker monitors UDP broadcast traffic on the local network. No TLS or encryption is involved in beacon payloads.
- **Execution:** The attacker captures the `advertised_id` field from UDP beacon JSON payloads at multiple locations (e.g., home, office, café) and attempts to correlate them to track a single device.
- **Impact:** If the `advertised_id` were static (e.g., the real `device_id`), the attacker could build a complete movement profile of the user.
- **Mitigations:**
  - NXFR beacons use a daily-rotating `advertised_id` derived via `SHA-256(device_id || YYYY-MM-DD)`. The beacon payload changes every 24 hours, breaking long-term correlation.
  - The permanent `device_id` is **never** included in beacon payloads. It is only revealed post-TLS handshake to authenticated peers.
  - Beacons are only sent while the send/receive UI is active — not 24/7.
- **Residual Risk:** Within a single calendar day, an attacker who monitors multiple networks can correlate the same `advertised_id`. This window is bounded to ≤24 hours. Implementations MAY reduce this window by rotating more frequently (e.g., hourly) at the cost of paired-peer lookup complexity.

### T11: Unauthorized Access and Brute-Forcing of Web Portal Endpoints
- **Description:** An unauthorized host on the local Wi-Fi or hotspot attempts to access shared files or inject malicious uploads via the ad-hoc Web Portal (port 17396).
- **Preconditions:** Attacker is connected to the same LAN / Wi-Fi subnet while a user has "Share via link" or "Receive via link" active.
- **Execution:** The attacker attempts to guess 4-digit PINs via automated HTTP dictionary queries (`GET /auth`, `GET /dl/:id?t=...`) or upload files with directory traversal paths (`../../etc/shadow`).
- **Impact:** Unauthorized data exposure, unauthorized file creation, or storage exhaustion.
- **Mitigations:**
  - **Fragment Isolation:** When PIN protection is disabled, tokens are passed via URL fragments (`#t=<token>`), which are processed entirely within client browser JavaScript and never transmitted across HTTP request headers or logged in server access logs.
  - **PIN Security & Exponential Lockout:** When PIN protection is enabled, access requires a 4 to 8 digit numeric PIN. An IP-based rate limiter restricts failed attempts: 5 consecutive failed attempts trigger an immediate 5-minute IP block (`403 Forbidden`). This renders automated online dictionary attacks computationally infeasible ($10^4 / 5 \times 5\text{ min} \approx 7\text{ days}$).
  - **Strict Path Sandboxing:** Uploaded filenames are sanitized; dot-traversal sequences (`"."`, `".."` , `"..."`) and empty filenames are rejected and replaced with cryptographically random identifiers (`uploaded_file_<hex>.bin`). All writes are strictly path-jailed within `web-inbox/`.
  - **Automatic Expiry:** Web servers automatically terminate and unbind sockets after 10 minutes of inactivity.
- **Residual Risk:** Users sharing links and PINs in untrusted physical environments must ensure PINs are transmitted out-of-band to intended recipients only.

---

## 4. Pairing Protocol Security Analysis

The NXFR pairing protocol relies on a Trust On First Use (TOFU) model supplemented by a Short Authentication String (SAS). This section analyzes the security properties of this approach in depth.

### 4.1 SAS Entropy Analysis
The NXFR protocol specifies the `numeric-6` SAS method. This method derives a 6-digit decimal number from the TLS 1.3 key exporter.
- The total namespace of a 6-digit number is 1,000,000 (10^6).
- The entropy is approximately `log2(10^6) ≈ 19.93` bits.
While ~20 bits of entropy is completely insufficient against an automated cryptographic brute-force attack (which typically requires 128+ bits), it is highly secure for this specific context because the verification is human-interactive. An active MITM attacker has exactly *one* chance to guess the correct SAS.
- The probability of the attacker guessing the SAS that matches what the other device derived is 1 in 1,000,000.
- This provides a 99.9999% security margin against active MITM during the pairing phase.
- This is vastly superior to the typical security posture of unauthenticated LAN services (e.g., FTP, plain HTTP file servers).

**Informational: Modulo bias.** The SAS computation uses `u32 mod 1000000`. Since
2^32 (4,294,967,296) is not evenly divisible by 1,000,000, a negligible bias exists:
the values 0–296,967,295 are ~0.000023% more likely than 296,967,296–999,999. This
bias is cryptographically irrelevant for a 20-bit interactive verification. Implementations
MAY use rejection sampling to eliminate the bias, but this is NOT REQUIRED.

### 4.2 Comparison with Other SAS Schemes
- **ZRTP (RFC 6189):** ZRTP uses a similar approach for securing VoIP calls, often using 2-word SAS (based on a PGP word list) which provides around 16 bits of entropy. NXFR's 6-digit numeric SAS provides slightly higher entropy and is arguably easier for users to compare visually on a screen than reading words aloud.
- **Signal Safety Numbers:** Signal uses 60-digit numbers for verifying long-term identity keys out-of-band. While highly secure, 60 digits are incredibly cumbersome for everyday LAN file transfers. NXFR strikes a balance by using a short, session-specific SAS for the initial pairing, and then pinning the long-term `device_id` invisibly for future connections.
- **SSH Host Key Fingerprints:** SSH relies on TOFU but typically displays a long hexadecimal or Base64 SHA-256 fingerprint (or visual host key like randomart). Users routinely ignore these due to cognitive overload. The 6-digit SAS is designed to be easily readable and explicitly actionable, reducing "click-through" fatigue.

### 4.3 The TOFU Model
- **Strengths:** The TOFU model requires zero infrastructure. There is no need for a centralized Certificate Authority (CA), Public Key Infrastructure (PKI), or user accounts. This allows NXFR to function entirely offline on an isolated LAN, preserving privacy and resilience.
- **Weaknesses:** The system is vulnerable to a MITM attack *during the very first connection* if the users do not diligently verify the SAS. If the attacker successfully MITMs the first connection and the users click "Accept" without comparing the codes, the attacker's public key becomes pinned on both devices.

### 4.4 Key Change Detection and Re-pairing
If a device's long-term key changes (e.g., the app is reinstalled, the user buys a new phone with the same name, or the keystore is wiped), the `device_id` will change.
- When it attempts to connect to a previously paired peer, the peer's implementation MUST detect the mismatch between the pinned `device_id` and the new SPKI hash.
- The implementation MUST drop the connection and flag the event as a potential security issue (an active MITM or a wiped device).
- The protocol requires that a completely new pairing flow (with explicit SAS verification) must be initiated by the user to re-establish trust.

### 4.5 MITM on First Pairing
If a TLS session is intercepted by an active attacker during the first pairing, the attacker will establish two separate TLS sessions: one with Device A, and one with Device B.
Because the SAS is derived using `TLS-Exporter`, which intrinsically binds the exported material to the specific TLS master secret of the session, the SAS generated on Device A will differ from the SAS generated on Device B. The attacker cannot force them to match, because they cannot force the master secrets of the two distinct TLS sessions to be identical.

### 4.6 SAS Commitment Scheme
Both sides independently compute the SAS context using `sort(device_id_a, device_id_b)`. This ensures that the context is identical regardless of who initiated the connection. The use of the TLS exporter ensures that both sides are committing to the exact same cryptographic transcript before the application-layer `PAIR_REQUEST` is ever sent over the wire.

---

## 5. mDNS Privacy Analysis

The use of mDNS for zero-configuration discovery introduces specific privacy considerations that implementations must manage carefully.

### 5.1 Information Leaked
When a device advertises its presence via mDNS, the following information is broadcast in the clear to the entire local subnet via UDP port 5353:
- The service instance name (often the user's name or device name, e.g., "Alice's iPhone").
- The IP address of the device (A/AAAA records).
- The platform type (e.g., "ios", "linux").
- The truncated `device_id` prefix (in the TXT record).
- Capabilities and pairing status (via TXT records).

### 5.2 Tracking Risk
The most significant privacy risk is the `id` field in the TXT record. If this field represents a static, long-term identifier, it allows any passive observer on any network the user joins to track the user's presence over time. If Alice connects to a coffee shop Wi-Fi and her device broadcasts a static `id`, the coffee shop can log her visits and duration of stay.

### 5.3 AirDrop Comparison
Apple's AirDrop protocol historically suffered from privacy vulnerabilities where it leaked partial SHA-256 hashes of the user's phone number and email address during discovery, allowing attackers to perform dictionary attacks and deanonymize users. NXFR avoids this completely by never including any PII (Personally Identifiable Information) like phone numbers or emails in the discovery phase. The `device_id` is tied to an ephemeral cryptographic keypair, not the user's real-world identity.

### 5.4 Hidden Mode (Default)
To mitigate all mDNS privacy risks, NXFR mandates that devices MUST NOT advertise their presence by default. A device is completely silent on the network until the user explicitly taps a "Receive" button in the UI.

### 5.5 Mitigations and Recommendations
Even when in receiving mode, privacy should be maximized:
- **Rotate ID Prefix:** Implementations SHOULD rotate the advertised `id` value in the mDNS TXT record daily.
  - The advertised value MUST be computed as: `advertised_id = first_16_hex_chars( SHA-256(device_id || "YYYY-MM-DD") )` where `device_id` is the 32-byte identity hash and `YYYY-MM-DD` is the current UTC date.
  - Paired peers that know the pinned `device_id` can independently compute and recognize today's advertised_id.
  - Unpaired observers see only a rotating 8-byte prefix and cannot correlate the device across different days.
  - **Rationale for SHA-256 over HMAC:** Using the private key in an HMAC would prevent paired peers from computing the expected value (they only know the public key / device_id). SHA-256(device_id || date) is computable by anyone who knows the device_id — which is exactly the set of paired peers.
- **Opaque Instance Names:** Implementations SHOULD encourage users to choose opaque or generic device names rather than their full legal names.
- **Minimize TXT Records:** Implementations MUST NOT include any additional metadata in the TXT records beyond what is strictly necessary for the protocol to function.

---

## 6. Path Sanitization Security

Path traversal attacks are one of the most severe threats in file transfer protocols. A compromised or malicious sender could attempt to overwrite critical system files on the receiver. Implementations MUST enforce rigorous path sanitization on the `relative_path` provided in the `TRANSFER_REQUEST` manifest and the `FILE_METADATA` frame.

### 6.1 Concrete Attacks and Defenses

- **Directory Traversal (`../`):**
  - *Attack:* `../../../etc/passwd` or `..\..\windows\system32\cmd.exe`
  - *Defense:* Implementations MUST reject any path containing the sequence `../` or `..\` (on Windows). The path must be strictly relative to the root of the designated download directory. Path canonicalization functions MUST be used carefully to ensure that the canonicalized path remains a strict child of the intended base directory.
  - *Code Example (Python):*
    ```python
    def is_safe_path(base_dir, relative_path):
        resolved_path = os.path.abspath(os.path.join(base_dir, relative_path))
        return resolved_path.startswith(os.path.abspath(base_dir))
    ```

- **Absolute Paths:**
  - *Attack:* `/etc/shadow` or `C:\Windows\System32\config\SAM`
  - *Defense:* Paths MUST NOT start with a slash `/`, a backslash `\`, or a drive letter (e.g., `C:`). Any absolute path MUST result in an immediate rejection of the file and a `TRANSFER_REJECT` or `FILE_METADATA_ACK` with `accepted: false`.

- **Null Byte Injection:**
  - *Attack:* `safe_file.txt\x00malware.exe`
  - *Defense:* Attackers use null bytes to truncate strings in underlying C libraries, bypassing extension checks. Implementations MUST reject any path containing a null byte (`\0`).

- **Windows Reserved Names:**
  - *Attack:* Sending a file named `CON`, `PRN`, `AUX`, `NUL`, `COM1`, or `LPT1`.
  - *Defense:* On Windows, attempting to create these files can cause severe application crashes or unexpected behavior. Windows implementations MUST explicitly check for and reject these reserved device names, regardless of the file extension (e.g., `CON.txt` is also invalid). Non-Windows implementations SHOULD also reject them to maintain cross-platform interoperability of transferred directories.

- **Unicode Normalization Attacks:**
  - *Attack:* An attacker uses different Unicode representations of the same character (e.g., precomposed vs. decomposed forms) to bypass blocklists or overwrite files unexpectedly.
  - *Defense:* Implementations SHOULD normalize all paths to NFC (Normalization Form C) before performing validation and before writing to the filesystem.

- **Very Long Paths:**
  - *Attack:* Sending paths that exceed the operating system's `PATH_MAX` (e.g., 260 characters on older Windows, 4096 on Linux).
  - *Defense:* Implementations MUST check the total length of the resolved absolute path before attempting to create the file. If it exceeds the OS limit, the file MUST be rejected to prevent buffer overflows or filesystem errors.

- **Symlink Races (TOCTOU):**
  - *Attack:* Between the time the implementation checks if a path is safe and the time it opens the file for writing, a local attacker (or a malicious concurrent transfer) replaces a directory in the path with a symlink pointing to `/etc/`.
  - *Defense:* Implementations MUST use secure file creation APIs (e.g., `open()` with `O_NOFOLLOW` | `O_CREAT` | `O_EXCL` on POSIX systems) to prevent following unexpected symlinks during the write operation.

- **Write-to-Temp-Rename Defense:**
  - *Defense Mechanism:* To further mitigate TOCTOU attacks and prevent partial files from being exposed to the user or other applications, implementations MUST write incoming file data to a temporary file in a secure, isolated directory (or with a temporary extension like `.nxfrtmp`).
  - Only after the entire file is received, the SHA-256 hash verified, and the chunk ACKs completed, should the implementation atomically rename the temporary file to its final destination path.

---

## 7. Resource Exhaustion Defenses

NXFR implementations must be resilient against Denial of Service (DoS) attacks attempting to consume memory, CPU, or network connections.

### 7.1 Control Payload Limit
The maximum size for any `CONTROL` frame payload is strictly capped at 64 KiB.
- This prevents an attacker from sending a massive, deeply nested CBOR structure that would consume excessive memory to parse.
- Any frame declaring a larger `payload_len` MUST cause the connection to be immediately dropped.

### 7.2 Chunk Payload Limit
The maximum size for a `CHUNK` frame payload is 4 MiB.
- This bounds the amount of memory required to buffer a single chunk for SHA-256 verification before writing it to disk.

### 7.3 In-flight Window
To prevent memory exhaustion from an attacker who sends data continuously without waiting for acknowledgments, the protocol dictates an in-flight window of exactly 8 chunks.
- This means the sender can only have a maximum of ~32 MiB of unacknowledged data on the wire at any time.
- If the receiver stops sending `CHUNK_ACK` frames, the sender MUST pause transmission, applying natural backpressure.

### 7.4 Concurrent Transfers and Sessions
Implementations MUST enforce hard limits on concurrency to prevent resource starvation.
- **Max Concurrent Sessions:** Implementations SHOULD limit active TLS sessions to a small number (e.g., 8). Additional connection attempts SHOULD be rapidly rejected.
- **Max Concurrent Transfers:** Implementations SHOULD limit active file transfers to a reasonable number (e.g., 4).

### 7.5 Manifest Entry Limit
The `TRANSFER_REQUEST` message contains a manifest of all files to be transferred.
- The protocol sets a hard limit of **500 entries** per manifest.
- Additionally, the **encoded TRANSFER_REQUEST MUST fit within the 64 KiB CONTROL frame payload limit**. At approximately 100 bytes per manifest entry (path + SHA-256 + metadata), 500 entries consume ~50 KiB, leaving headroom for the envelope fields.
- Manifests exceeding either limit MUST be rejected with error code `manifest_too_large`.
- **Manifest paging** (splitting a large directory across multiple TRANSFER_REQUEST messages) is deferred to v0.2.

### 7.6 Connection Rate Limiting
Implementations SHOULD track connection attempts by IP address and `device_id`.
- If a peer rapidly connects and disconnects, or repeatedly sends invalid frames, the implementation MUST apply exponential backoff.
- Alternatively, temporarily block the peer entirely at the TCP layer.

### 7.7 Slowloris and Handshake Timeouts
Attackers may attempt to hold connections open indefinitely by sending data very slowly (Slowloris) or stalling during the TLS handshake.
- **Handshake Timeout**: Implementations MUST wrap the TLS handshake in a strict timeout of at most 10 seconds. If the TLS handshake does not complete within this window, the TCP stream MUST be immediately aborted.
- **Bounded Handshake Concurrency**: Implementations MUST bound the number of concurrent in-flight TLS handshakes using a semaphore or connection pool (e.g. 100 concurrent handshakes) to prevent resource exhaustion of the crypto engine and socket descriptor table.
- **HELLO Message Timeout**: If a valid `HELLO` frame is not completely received and parsed within 5 seconds of the TLS handshake completing, the connection MUST be closed.

### 7.8 File Descriptor Starvation Backoff
When the host process approaches system resource limits (e.g. `EMFILE` or `ENFILE` indicating too many open files), the TCP `accept()` loop will return immediate errors.
- Implementations MUST NOT spin in a busy loop retrying `accept()` with zero delay.
- On any `accept()` error, the listener MUST apply a backoff delay of at least 50ms before attempting the next accept call, preventing 100% CPU starvation.

---

## 8. TLS Configuration Requirements

The security of NXFR relies heavily on the correct configuration of the underlying TLS 1.3 stack. Implementations MUST adhere to the following strict requirements. Failure to do so constitutes a critical security vulnerability.

### 8.1 Required Cipher Suites
Implementations MUST support and prioritize modern AEAD cipher suites.
1. `TLS_AES_256_GCM_SHA384`
2. `TLS_AES_128_GCM_SHA256`
3. `TLS_CHACHA20_POLY1305_SHA256`
Weak ciphers (RC4, 3DES, AES-CBC) MUST be explicitly disabled in the TLS library context.

### 8.2 Certificate Requirements
The protocol mandates the use of self-signed X.509 certificates with ECDSA P-256 (secp256r1) keys.
- Implementations MUST ensure their TLS stack does not attempt to validate the certificate against the system's root CA store, as it will always fail.
- Validation is entirely custom, based on the `device_id` pinning.

### 8.3 ALPN Enforcement
Both the client and server MUST negotiate the Application-Layer Protocol Negotiation (ALPN) extension with the value `nxfr/0`.
- If the peer does not advertise this ALPN token, the connection MUST be aborted immediately.
- This prevents cross-protocol attacks.

### 8.4 No TLS 1.2 or Earlier Fallback
The TLS context MUST be configured to set the minimum protocol version to TLS 1.3.
- Any attempt by a peer to negotiate TLS 1.2, TLS 1.1, or TLS 1.0 MUST be rejected.
- TLS 1.3 removes many vulnerable primitives present in earlier versions.

### 8.5 No 0-RTT
Zero Round Trip Time (0-RTT) early data is explicitly forbidden in NXFR v0.1.
- 0-RTT is inherently vulnerable to replay attacks.
- Implementations MUST configure their TLS stack to reject early data.

### 8.6 No PSK Resumption
Pre-Shared Key (PSK) session resumption MUST NOT be used in v0.1.
- All connections MUST perform a full TLS 1.3 handshake using the ECDSA certificates.

### 8.7 Certificate Pinning via device_id
The core of NXFR authentication is the `device_id`, which is defined as the SHA-256 hash of the SubjectPublicKeyInfo (SPKI) DER encoding of the peer's certificate.
- Implementations MUST extract this SPKI data directly from the verified TLS session state and compute the hash themselves.
- They MUST NOT rely on the `device_id` field in the `HELLO` payload for authentication.
- The `HELLO` payload value is only a claim; the TLS certificate provides the cryptographic proof.

### 8.8 Cryptographic Handshake Signature Verification
When implementing custom certificate verifiers to bypass public CA chain validation (enabling self-signed TOFU certificates), implementations MUST NOT treat certificate validation and handshake signature verification as identical.
- **Mandatory Signature Check**: The TLS verifier MUST invoke standard cryptographic signature verification algorithms over the handshake messages (e.g. `rustls::crypto::verify_tls13_signature`).
- **Possession Proof**: Signature verification mathematically proves that the connecting entity possesses the private key corresponding to the presented public certificate. Bypassing this step allows an attacker with a victim's public certificate to impersonate the victim without having their private key.

### 8.9 Web Portal Security Invariants
For browser-based direct link sharing (`nxfr-web`):
- **Fragment Token Isolation**: Authentication tokens MUST be positioned in URL hash fragments (`/#t=<token>`) rather than path segments or query parameters to prevent token transmission over the wire or retention in intermediate proxy/server request logs.
- **PIN Brute-Force Rate Limiting**: The portal MUST track failed authentication attempts per client IP. Upon reaching 5 consecutive failures, the IP MUST be locked out for at least 5 minutes.
- **DOM-Based XSS Elimination**: Web interfaces displaying user-supplied metadata (e.g. filenames from manifests) MUST render content using safe DOM property assignment (`textContent`) or strict contextual HTML escaping, never unescaped `innerHTML`.
- **Log Hygiene**: Implementation logging frameworks MUST explicitly redact authentication tokens (`token=****`) from all application logs and system logcat sinks.

---

## 9. Residual Risks

No protocol can mitigate all conceivable threats. This section honestly outlines the residual risks that the NXFR protocol explicitly accepts. Deployers and users must be aware of these limitations.

### 9.1 LAN-level Metadata Analysis
NXFR does not attempt to hide metadata from a passive observer on the local network.
- An observer can see the IP addresses communicating.
- They can see the exact time the connection starts and ends.
- They can calculate the total volume of encrypted data transferred.
- In a highly sensitive environment, this could allow an attacker to infer behavior (e.g., "Device A sends a large file to Device B every day at 5 PM").

### 9.2 mDNS Name Leakage Before Pairing
While devices are hidden by default, once a user puts a device into receiving mode, its service name and IP address are broadcast.
- If a user chooses a highly identifiable name (e.g., "John Doe's Secret Laptop"), this information is public on the LAN.

### 9.3 TOFU Vulnerability Window
The Trust On First Use model inherently contains a vulnerability window during the very first connection between two devices.
- If an active MITM attacker is present at the exact moment of the first pairing attempt, and the users are negligent and fail to verify the 6-digit SAS codes, the attacker can successfully insert themselves into the connection permanently.

### 9.4 Physical Proximity Assumption Limitations
The pairing protocol assumes that users are physically close enough to verbally or visually compare the SAS.
- On large enterprise networks or campus Wi-Fi, two users might attempt to pair while in different buildings, communicating out-of-band (e.g., via a chat app).
- If that out-of-band channel is compromised, the SAS verification is also compromised.

### 9.5 Compromised Operating System
NXFR provides no defense if the host OS is compromised by a rootkit or advanced malware.
- Malicious software running with sufficient privileges can read the application's memory, extract the private keys, or bypass the UI to silently accept transfers.

### 9.6 Side Channels
The protocol implementation itself may be vulnerable to side-channel attacks.
- For example, timing analysis of the path sanitization logic or the CBOR parsing could potentially reveal information to an attacker on the same physical machine or network.

### 9.7 Social Engineering
The most significant residual risk remains the user.
- An attacker might use social engineering to convince a user to accept a transfer of malware ("Hey, it's IT support, please accept this urgent update file").
- An attacker might convince them to click "Accept" on a pairing request without actually checking the SAS.

---

## 10. Security Recommendations for Implementors

To achieve the security guarantees outlined in this document, implementors MUST adhere to the following best practices during development:

- **Always validate `device_id` against the SPKI hash:** Never trust the `device_id` sent in the `HELLO` message. You MUST extract the certificate from the TLS stack, parse the SPKI, hash it with SHA-256, and verify it matches the claimed ID.
- **Never skip path sanitization:** Use established, well-tested path manipulation libraries provided by the OS or standard library to resolve paths and ensure they do not escape the intended root directory. Do not attempt to write custom path parsing logic from scratch.
- **Use constant-time comparison:** When comparing the computed SPKI hash against the pinned `device_id` in the database, you MUST use a constant-time memory comparison function (e.g., `CRYPTO_memcmp` or equivalent) to prevent timing attacks that leak bytes of the hash.
- **Log pairing events for audit:** Implementations SHOULD maintain an audit log of all successful and failed pairing attempts, including timestamps and the peer's `device_id`, to assist in post-incident forensic analysis.
- **Clear sensitive key material from memory:** After the `SAS` has been computed and displayed, the intermediate keying material (`sas_bytes` from the TLS exporter) MUST be securely wiped from memory using functions like `explicit_bzero` or `SecureZeroMemory`.
- **Use platform keystore:** Never store the private key in plaintext on disk. Utilize Android Keystore, iOS Keychain, Windows DPAPI, or Linux Secret Service with appropriate access controls.
- **Implement strict rate limiting:** Protect the application and the user from notification spam and resource exhaustion by dropping excessive connections early at the network layer.
- **Validate CBOR strictly:** Reject any CBOR maps with duplicate keys, unknown tags, or nesting depths greater than 6. Do not allocate memory based on untrusted lengths without sanity bounds.

---

## 11. Future Security Enhancements

The NXFR Working Group is actively considering the following security enhancements for future minor or major version iterations (v0.2+ or v1.0).

### 11.1 QR Code Pairing
Replacing the 6-digit SAS with a QR code containing a pre-shared key (PSK) and the expected public key fingerprint.
- This eliminates the vulnerability window of TOFU completely.
- It requires physical line-of-sight for pairing, significantly enhancing resistance to MITM attacks.

### 11.2 Certificate Transparency for Device Keys
Investigating mechanisms for an offline, localized form of Certificate Transparency (CT).
- This would detect if a device's identity has been silently replaced by an attacker over time without requiring cloud access.

### 11.3 Noise Protocol Framework as TLS Alternative
For constrained devices or implementations that find pulling in a full TLS 1.3 stack burdensome, future versions may define an alternative transport profile.
- Utilizing the Noise Protocol Framework (e.g., `Noise_XX_25519_ChaChaPoly_BLAKE2s`).
- Noise is simpler, purpose-built, and easier to audit than TLS.

### 11.4 Hardware-Backed Key Attestation on Android
Leveraging Android's SafetyNet or Key Attestation APIs.
- This would prove to the peer that the private key is genuinely stored in a hardware secure module (TEE/SE) and that the device is not rooted.

### 11.5 Post-Quantum Key Exchange Preparation
Monitoring the NIST PQC standardization process.
- Future versions of NXFR will likely require hybrid key exchange mechanisms (e.g., combining X25519 with Kyber768 or ML-KEM).
- This protects the forward secrecy of LAN transfers against future quantum adversaries storing encrypted traffic.

### 11.6 Verified Boot Chain Integration
Exploring ways to tie the device identity to the platform's verified boot state.
- Ensuring that a compromised OS cannot impersonate the legitimate user or bypass UI consent prompts silently.

---

## 12. Appendix and Audit Guidelines

### 12.1 Implementation Security Checklist
Developers building NXFR clients should use the following checklist prior to release:
- [ ] TLS 1.3 is forced, older versions disabled.
- [ ] Custom certificate verification is correctly implemented.
- [ ] `device_id` derivation strictly follows SHA-256(SPKI DER).
- [ ] SAS computation uses constant-time operations where applicable.
- [ ] Incoming paths are strictly validated against directory traversal.
- [ ] UI correctly distinguishes between paired and unpaired devices.
- [ ] In-flight window and max payload constraints are strictly enforced.

### 12.2 Known Anti-Patterns
- **Ignoring Certificate Errors:** Because certificates are self-signed, naive TLS setups may simply disable verification. This is a fatal flaw in NXFR. Verification MUST happen via the pinned `device_id`.
- **Writing Directly to Final Path:** A transfer should always write to a temporary file first and atomically rename upon complete verification of the SHA-256 hash.

### 12.3 Reference Implementations
When auditing, refer to the official core libraries in Rust, Kotlin/JVM, or C#/.NET, which have been tested against the golden test vectors in WIRE_FORMAT.md. The working group provides test vectors for CBOR parsing, framing logic, and SAS derivation to assist in independent verification.
