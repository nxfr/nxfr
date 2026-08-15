# NXFR Architecture Reference

## 1. Overview

The Nearby Xfer Protocol (NXFR) architecture is
designed around a philosophy of strict separation
of concerns. To achieve true cross-platform
compatibility while maintaining high performance
and security, the architecture isolates the core
protocol logic from all platform-specific I/O,
storage, and UI concerns.
At the center of any implementation is the
platform-agnostic core. This core is implemented
as a set of pure state machines. It handles frame
parsing, CBOR serialization, cryptographic state
tracking (like SAS derivation and signature
verification), and transfer state management. By
keeping this core pure and deterministic, it can
be extensively unit-tested and fuzz-tested without
mocking network interfaces or filesystems.
Surrounding the core is the platform-specific
integration layer. This layer is responsible for
interfacing with the operating system's native
capabilities. It handles TCP socket creation, TLS
handshakes (via platform-native TLS stacks where
required), mDNS service registration and
discovery, and file I/O. This layer also
implements the secure storage of the long-term
identity keys, relying on OS-provided keystores or
secret services.
Finally, the top layer is the platform UI and
application layer. This layer manages user
interactions, consent dialogues, share sheet
integrations, and background service lifecycles.
It translates the abstract state changes emitted
by the core into user-visible notifications or UI
updates. By strictly enforcing these boundaries,
NXFR ensures that the complex protocol logic is
written once and proven correct, while each
platform retains a native look, feel, and
performance profile.
The architecture enforces strict security
boundaries. The protocol core does not trust the
network transport; the transport does not trust
the network interface; the application does not
trust the peer. All interactions cross clearly
defined, validated boundaries, reducing the attack
surface.

## 2. Linux Architecture

The Linux architecture for NXFR is built around a
robust, headless daemon that runs in the
background, providing the core networking and
protocol services, while separate CLI and GUI
clients interact with it.

### 2.1 nxfr-daemon

The `nxfr-daemon` is a systemd user service
(`systemd --user`). It is the heart of the Linux
implementation, responsible for all network and
disk I/O.
- **Component breakdown:**
  - **mDNS module:** Uses Avahi D-Bus bindings by
default for native OS integration. As a fallback
(e.g., in minimal containers), it uses the pure-
Rust `zeroconf` crate for service advertisement
and discovery. This ensures maximum compatibility
across different Linux distributions.
  - **TLS server:** Uses `rustls` for mTLS,
configured strictly for TLS 1.3 with the required
cipher suites. This provides a modern, memory-safe
TLS stack.
  - **Protocol state machine:** Embeds the `nxfr-
core` state machines to handle sessions and
transfers. The state machine operates
independently of the transport, communicating via
channel-based message passing.
  - **File I/O:** Uses `tokio` asynchronous I/O
for reading and writing file chunks efficiently,
employing `tokio::fs` to minimize thread blocking.
A dedicated thread pool handles CPU-intensive
hashing (SHA-256 or BLAKE3).
  - **IPC:** Exposes an API via D-Bus (using
`zbus`) or a Unix domain socket using JSON-RPC,
allowing multiple clients (CLI, GUI, extensions)
to control the daemon simultaneously. D-Bus is
preferred for GUI integration, while Unix sockets
provide a robust fallback.
- **Lifecycle:** Managed by systemd. Configured to
auto-start on user login via
`~/.config/systemd/user/nxfr.service`. It supports
graceful shutdown via SIGTERM or a specific IPC
command, ensuring active transfers are cleanly
paused or cancelled and state is synced to disk.
- **Configuration:** Stores configuration in
`~/.config/nxfr/config.toml`. Key settings include
the default receive directory (usually
`~/Downloads/NXFR`), the device's display name,
and auto-accept rules. The paired device database
is stored in
`~/.local/share/nxfr/paired_devices.db`, using a
lightweight SQLite database for persistence.

### 2.2 nxfr-cli

The `nxfr-cli` provides a comprehensive command-
line interface for power users and scripting. It
communicates with the daemon via IPC.
- `nxfr send <file/dir> [--to <device>]`
  - **Description:** Initiates a file or directory
transfer to a specific device. If `--to` is
omitted, it interactively browses for nearby
devices using a text-based UI (TUI).
  - **Arguments:**
    - `<file/dir>`: Path to the local file or
directory.
    - `[--to <device>]`: The destination device's
name or truncated ID.
  - **Examples:**
    - `nxfr send ./report.pdf`
    - `nxfr send Documents/ --to "Alice's Phone"`
- `nxfr devices [--json]`
  - **Description:** Lists all paired devices from
the database and currently discovered nearby
devices on the LAN.
  - **Arguments:**
    - `[--json]`: Output the list in a machine-
readable JSON format for scripting.
  - **Examples:**
    - `nxfr devices`
    - `nxfr devices --json | jq .`
- `nxfr pair <device>`
  - **Description:** Initiates a pairing request
with a discovered, unpaired device. Displays the
SAS code in the terminal and waits for user
confirmation.
  - **Arguments:**
    - `<device>`: The target device's name or ID.
  - **Examples:**
    - `nxfr pair "Bob's Laptop"`
- `nxfr unpair <device>`
  - **Description:** Removes a device from the
paired database, revoking trust. Subsequent
transfers will require re-pairing or manual
confirmation.
  - **Arguments:**
    - `<device>`: The target device's name or ID.
  - **Examples:**
    - `nxfr unpair e4f1b3c9...`
- `nxfr status`
  - **Description:** Shows active transfers,
current transfer speed, estimated time remaining,
and overall daemon health metrics.
  - **Arguments:** None.
  - **Examples:**
    - `nxfr status`
- `nxfr config [key] [value]`
  - **Description:** Gets or sets daemon
configuration options. Modifies `config.toml` and
signals the daemon to reload.
  - **Arguments:**
    - `[key]`: The configuration key to read or
write.
    - `[value]`: The value to write (if setting a
configuration).
  - **Examples:**
    - `nxfr config receive_dir ~/Desktop/Incoming`
- `nxfr receive [--enable|--disable]`
  - **Description:** Toggles mDNS advertisement
and receiving capabilities. When disabled, the
daemon stops listening and becomes invisible on
the network.
  - **Arguments:**
    - `[--enable]`: Start advertising and
listening.
    - `[--disable]`: Stop advertising and
listening.
  - **Examples:**
    - `nxfr receive --enable`

### 2.3 nxfr-gui

The `nxfr-gui` is a planned graphical client built
with GTK4 and libadwaita for modern GNOME
desktops.
- **System Tray:** Provides a persistent indicator
when receiving is enabled, allowing quick toggles
and status checks.
- **Drag-and-Drop:** Supports dragging files
directly onto the app window or tray icon to
initiate a transfer.
- **Desktop Notifications:** Rich notifications
via `libnotify` for incoming transfers, pairing
requests (displaying the SAS), and transfer
completion.
- **Nautilus Extension:** Integrates directly into
the file manager context menu, offering a native
"Send via NXFR" option.

### 2.4 Crate Layout

The Rust implementation is meticulously organized
into modular crates:
- **nxfr-core:** The pure protocol logic. Contains
session state machines, transfer state machines,
message types, and CBOR serialization. Absolutely
NO file I/O or network sockets. Extensively unit-
testable and fuzz-tested.
- **nxfr-transport:** The asynchronous I/O layer.
Wraps TCP streams (via `tokio`), manages TLS
encryption (via `rustls`), and handles connection
pooling, multiplexing, and framing.
- **nxfr-discovery:** Abstraction over mDNS/DNS-
SD. Pluggable backends for the `mdns-sd` crate or
Avahi D-Bus bindings.
- **nxfr-crypto:** Cryptographic primitives.
Manages ECDSA P-256 key generation, certificate
generation for TLS, SHA-256 hashing for chunks,
and SAS derivation via HKDF.
- **nxfr-storage:** Persistence layer. Manages the
paired device database (SQLite), configuration
parsing, and the resume state journal for
interrupted transfers.
- **nxfr-daemon:** The main executable for the
background service. Glues the crates together,
implements the D-Bus/JSON-RPC IPC API, and handles
systemd integration.
- **nxfr-cli:** The command-line executable. Uses
`clap` for argument parsing and acts as an IPC
client to the daemon.
- **nxfr-common:** Shared types, errors, and
utility functions used across the workspace.

## 3. Android Architecture

The Android architecture requires careful
navigation of the platform's strict lifecycle
constraints. It is fundamentally different from
the Linux daemon model and must respect the OS's
limitations.

### 3.1 Background Execution Reality (Android 14/15+)

Android aggressively restricts background
execution to preserve battery life and system
resources. Period. A daemon that survives a force-
stop or runs indefinitely in the background is NOT
possible. The architecture must explicitly
acknowledge and design around this reality.
- **Foreground Services:** Any sustained
background networking requires a Foreground
Service. Specifically, it requires the
`FOREGROUND_SERVICE_DATA_SYNC` permission and
`foregroundServiceType="dataSync"`. Without this,
the OS will silently kill network sockets.
- **Time Limits:** Android 15 imposes a strict
6-hour collective limit per 24 hours on foreground
services of type `dataSync`. The system will
invoke the `onTimeout()` callback and kill the
service if this limit is exceeded.
- **UIDT:** For very long transfers, the
application must transition to User-Initiated Data
Transfer (UIDT) Jobs via the `JobScheduler` API,
which have different constraints and require
explicit user initiation.
- **mDNS Limitations:** mDNS discovery CANNOT run
reliably in the background without an active
foreground service holding a `MulticastLock`. Even
then, some devices may suspend multicast traffic
when the screen is off.

### 3.2 Component breakdown

The Android implementation uses a modular
architecture within a single APK:
- **NxfrService:** A Foreground Service
(type=dataSync) that hosts the core protocol
engine. It manages the TCP sockets, TLS contexts,
and orchestrates the Rust core library via JNI.
- **ShareReceiverActivity:** An Activity
registered as a Share Sheet target. It handles
`ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents
from other apps, parsing URIs into file
descriptors.
- **DevicePickerActivity:** An Activity that
displays mDNS-powered discovery results, allowing
the user to select a target device for an outbound
transfer.
- **TransferNotificationManager:** Manages rich
system notifications, displaying progress bars,
Accept/Reject action buttons for incoming
transfers, and completion states.
- **QuickSettingsTile:** A tile in the Android
Quick Settings panel (`TileService`) to quickly
toggle receiving mode on or off without opening
the app.
- **NxfrCore (Kotlin module):** A Kotlin
multiplatform or JNI wrapper around the Rust
`nxfr-core` library. It bridges the pure protocol
logic with the Android framework APIs.
- **KeystoreManager:** Interfaces with the
hardware-backed Android Keystore system
(`AndroidKeyStore`) for secure generation,
storage, and use of the ECDSA P-256 identity key,
preventing extraction.
- **NsdManager:** Wrapper around Android's built-
in Network Service Discovery API
(`android.net.nsd.NsdManager`) for mDNS
advertisement and scanning.

### 3.3 Receive flow (step by step)

1. **User enables receiving:** The user taps the
Quick Settings tile or toggles a switch in the
main app UI.
2. **Service starts:** The app starts
`NxfrService` as a foreground service and posts a
persistent, low-priority notification stating
"Ready to receive".
3. **Advertisement begins:** `NxfrService`
acquires a `WifiManager.MulticastLock` and
registers the `_nxfr._tcp` service via
`NsdManager`.
4. **Incoming connection:** A sender discovers the
device and connects. The service validates the TLS
handshake and HELLO frame, then triggers the
`TransferNotificationManager`.
5. **Consent UI:** A high-priority notification
(with sound/vibration) appears with "Accept" and
"Reject" actions, detailing the file(s), total
size, and sender name.
6. **Transfer proceeds:** Upon user tapping
"Accept", the service replies with
`TRANSFER_ACCEPT`. It handles incoming chunks,
writing them to a `.part` file in the chosen
directory, and continuously updates the
notification progress bar.
7. **Completion:** When finished, the `.part` file
is renamed. The notification updates to a
"Transfer Complete" state with an "Open file"
action, utilizing `FileProvider` to grant read
access to other apps.
8. **Disable:** When the user disables receiving,
the service stops, the `MulticastLock` is
released, mDNS is un-published, and the app goes
completely idle.

### 3.4 Send flow (step by step)

1. **User shares:** The user selects file(s) in a
file manager or gallery and taps "Share",
selecting NXFR from the system Share Sheet.
2. **Picker opens:** `ShareReceiverActivity`
launches, resolves the shared URIs to determine
file sizes and names, and opens
`DevicePickerActivity`, which starts scanning
mDNS.
3. **Target selected:** The user taps a discovered
device in the list.
4. **Service starts:** The app starts
`NxfrService` as a foreground service to manage
the outbound transfer.
5. **Transfer proceeds:** The service connects,
authenticates (potentially prompting for pairing),
and sends the data, showing an ongoing progress
notification.
6. **Completion:** The service stops itself
automatically when the transfer successfully
completes, fails, or is cancelled.

### 3.5 Honest Limitations

The Android platform imposes several unavoidable
limitations on NXFR that users and developers must
understand:
- **Active Receiving Required:** Receiving
requires the user to actively enable it via the UI
or Quick Settings. There is no passive background
daemon listening at all times like on Linux.
- **Force Stop Kills All:** If the user or system
force-stops the app from settings, receiving stops
immediately. There is no workaround,
BroadcastReceiver fallback, or auto-restart
mechanism.
- **Discovery Window:** mDNS discovery
(advertising and scanning) is only active while
the foreground service runs. The device is
invisible otherwise.
- **Battery Optimization (Doze):** Aggressive
battery optimization (Doze mode) on some OEM
devices (e.g., MIUI, ColorOS) may still kill the
foreground service if the user doesn't manually
exempt the app in settings.
- **Large Transfers:** Large transfers (multi-
hour) may be interrupted by Android 15's 6-hour
foreground service timeout. The app must handle
`onTimeout()` gracefully and guide the user to
resume the transfer.
- **mDNS Quirks:** mDNS on Android requires
acquiring a `MulticastLock`, which drains battery,
and is heavily affected by Wi-Fi sleep policies.
Some routers actively filter multicast traffic.
- **NsdManager Reliability:** `NsdManager` has
known reliability issues and stale caching on some
OEMs. The implementation may need to bundle a
pure-Java mDNS library (like JmDNS) as a fallback
if the native API hangs.

## 4. Windows Architecture

The Windows implementation is planned for a future
release, designed to integrate deeply with the
modern Windows shell and APIs.

### 4.1 Implementation Plan

- **Options:** The core will either be a
C-compatible DLL derived from the `nxfr-core` Rust
crate, or rewritten in C#/.NET for deeper, more
idiomatic native integration with Windows APIs.
- **UI Framework:** The client will be a system
tray application built using WinUI 3 or WPF,
providing a modern Fluent Design interface that
matches Windows 11 aesthetics.
- **Notifications:** It will heavily utilize the
Windows Notification Center for consent dialogues,
pairing prompts, and progress tracking, providing
actionable toasts.
- **Discovery:** mDNS will be handled primarily
via the native
`Windows.Networking.ServiceDiscovery` API. If
found lacking, a bundled library like Bonjour SDK
or a pure-Rust responder will be utilized.
- **Security:** TLS and cryptography will ideally
leverage SChannel and the Windows Certificate
Store/DPAPI to ensure enterprise compliance, FIPS
readiness, and secure key storage without bundling
custom crypto stacks.
- **Shell Extension:** An Explorer context menu
extension (COM object) will provide a seamless
"Send via NXFR" right-click option on files and
folders.
- **Firewall:** The MSIX installer must gracefully
handle Windows Defender Firewall rule
configuration to allow incoming TCP connections on
the designated port (17394), prompting the user
appropriately.

## 5. Cross-Platform Module Boundaries

The NXFR architecture relies on strict boundaries
to ensure the core protocol is write-once, run-
anywhere, maximizing code reuse and security.

```
┌────────────────────────────────┐
│        Platform UI Layer       │  (GTK4 /
Android / WinUI / CLI)
├────────────────────────────────┤
│    Platform Integration Layer  │  (D-Bus /
Keystore / SChannel / Intents)
├────────────────────────────────┤
│      Protocol Core (pure)      │  (State
machines, CBOR, Framing, Auth)
├────────────────────────────────┤
│     Transport Layer (async)    │  (TLS 1.3, TCP
Sockets)
├────────────────────────────────┤
│       Discovery Layer          │  (mDNS/DNS-SD,
Avahi, NsdManager)
└────────────────────────────────┘
```
The Protocol Core is entirely pure. It takes
incoming byte streams and timer events as input,
and emits state changes, outgoing byte streams,
and UI directives as output. It knows nothing
about sockets, disks, or screens. This
architectural purity allows the core to be wrapped
in JNI for Android, exposed via FFI for Windows,
or compiled directly into the Linux daemon,
guaranteeing identical protocol behavior across
all platforms.

## 6. Data Storage

Data storage requirements are minimal but critical
for maintaining trust, configuration, and
supporting resumable transfers.

- **Paired device database:** Stores trusted
peers. The schema includes:
  - `device_id` (primary key, SHA-256 string)
  - `name` (last known device name)
  - `public_key` (DER encoded P-256 key)
  - `first_seen` (timestamp of initial pairing)
  - `last_seen` (timestamp of last successful
connection)
  - `trust_level` (enum: verified, blocked)
  - `auto_accept_policy` (rules for accepting
files without prompts, e.g., "always", "never",
"only images").
- **Configuration:** Stores user preferences,
including the default receive directory,
customized device display name, preferred chunk
size, and global auto-accept rules.
- **Resume state:** Active transfers use `.part`
files for the actual data to avoid corrupting
existing files. Metadata is stored in a state
journal (JSON or CBOR format) that records the
`transfer_id`, manifest, and ranges of
successfully received bytes. The fsync strategy
requires syncing the journal only after a complete
chunk is acknowledged to survive power loss or
sudden crashes.
- **Key storage:** Private identity keys are never
stored in plain text. They are stored per-platform
as specified in §6.3.3 of PROTOCOL.md (Secret
Service API on Linux, hardware-backed Android
Keystore, DPAPI on Windows), ensuring keys cannot
be easily extracted by malware.

## 7. Deployment & Packaging

NXFR is designed to be easily distributed across
multiple ecosystems, utilizing standard packaging
tools.

- **Linux:** Primary distribution via Flatpak on
Flathub, providing sandboxing and cross-
distribution support. Fallback RPM/DEB packages
will be provided for system-level installation.
Developers and power users can use `cargo install
nxfr-cli` to build from source.
- **Android:** Distributed primarily via the
Google Play Store. An F-Droid build will be
available for open-source purists, alongside
direct APK sideloads provided on the project's
GitHub releases page.
- **Windows:** Distributed as an MSIX package via
the Microsoft Store for clean installs, seamless
updates, and uninstalls. A standalone portable
`.exe` will be available for enterprise deployment
or users avoiding the Store.
