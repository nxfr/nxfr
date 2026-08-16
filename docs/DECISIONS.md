# NXFR Design Decisions Log

This document records the architectural and
protocol design decisions for the NXFR protocol,
providing in-depth rationale and alternatives
considered for each choice. These decisions form
the foundation of the protocol specification and
implementation guidelines.

## Locked Decisions

### D-01: Network Transport (TCP vs UDP/QUIC)
**Status:** Locked (v1.0)
**Decision:** Use TCP as the underlying network
transport.
**Rationale:** TCP provides reliable, ordered byte
streams out-of-the-box, which significantly
simplifies the framing and transfer state
machines. On local area networks, TCP performance
is well-understood, mature, and generally highly
capable of saturating gigabit Ethernet and modern
Wi-Fi links with proper window sizing. While QUIC
offers theoretical benefits for multiplexing
without head-of-line blocking, the maturity of
cross-platform QUIC libraries (especially native
OS support) is currently lacking compared to TCP.
Using TCP ensures maximum compatibility across all
target platforms without bloating the binary size
with custom transport stacks.
**Alternatives Considered:**
- **QUIC:** Rejected because deploying a robust
QUIC stack across Linux, Android, and Windows
without heavily inflating binary size or relying
on immature third-party libraries is currently
unfeasible for a v0.1 release.
- **UDP (Custom reliability):** Rejected due to
the immense complexity of implementing congestion
control, packet ordering, and reliable delivery,
effectively reinventing TCP poorly and introducing
a massive testing burden.
**References:** RFC 793 (TCP)

### D-02: Security Layer (TLS 1.3 vs Noise Protocol)
**Status:** Locked (v1.0)
**Decision:** Use TLS 1.3 in mutual authentication
(mTLS) mode.
**Rationale:** TLS 1.3 is a mature, widely vetted
standard for secure transport that provides
forward secrecy and strong encryption. Crucially,
major operating systems provide native, optimized,
and often hardware-accelerated TLS stacks (e.g.,
SChannel on Windows, Android Keystore integration
for BoringSSL). Using TLS 1.3 avoids the "roll
your own crypto" pitfall, offloads complex
handshake logic to proven libraries, and satisfies
enterprise compliance requirements (like FIPS
140-2) much more easily than custom cryptographic
frameworks.
**Alternatives Considered:**
- **Noise Protocol Framework:** Rejected despite
its elegance and small footprint. Noise lacks
native OS integrations, meaning apps would need to
bundle their own crypto implementations,
complicating FIPS compliance and preventing the
use of hardware-backed keystores.
- **TLS 1.2:** Rejected because it allows
outdated, vulnerable cipher suites and has a
slower handshake. TLS 1.3 enforces modern, secure
defaults.
**References:** RFC 8446 (TLS 1.3)

### D-03: Discovery Mechanism (mDNS vs BLE)
**Status:** Locked (v1.0)
**Decision:** Use mDNS/DNS-SD for local network
discovery.
**Rationale:** mDNS (Multicast DNS) combined with
DNS-SD (Service Discovery) is the industry
standard for zero-configuration networking on
LANs. It is supported natively by Android
(NsdManager), Linux (Avahi), macOS (Bonjour), and
Windows. It works seamlessly over standard Wi-Fi
and Ethernet without requiring special hardware
permissions beyond local network access, making it
highly reliable for LAN environments.
**Alternatives Considered:**
- **Bluetooth Low Energy (BLE):** Rejected as the
primary discovery mechanism due to severe
permission restrictions on modern OSes (often
requiring Location permissions on older Android
versions, which confuses users), flakiness in
cross-platform pairing, and lack of support on
wired desktop machines.
- **Custom UDP Broadcast:** Rejected because it
reinvents DNS-SD, often runs into firewall issues
on enterprise networks, and lacks the structured
metadata capabilities of DNS-SD TXT records.
**References:** RFC 6762 (mDNS), RFC 6763 (DNS-SD)

### D-04: Framing Format (Custom binary vs HTTP/2)
**Status:** Locked (v1.0)
**Decision:** Use a custom binary framing protocol
with a fixed 28-byte header over TLS.
**Rationale:** A custom binary frame minimizes
protocol overhead and allows fine-grained control
over chunk sizes and multiplexing. HTTP/2 or
HTTP/3, while highly capable, pull in massive
dependencies and complexity for features NXFR
doesn't need (e.g., HPACK header compression,
complex state machines, prioritization trees). A
simple 28-byte header provides exactly what is
needed: message typing, stream identification,
sequence numbers, and payload sizing, keeping the
parser simple and fast.
**Alternatives Considered:**
- **HTTP/2:** Rejected due to implementation
complexity, dependency bloat, and unnecessary
overhead for a purely local peer-to-peer protocol.
- **WebSockets:** Rejected because the HTTP
upgrade dance adds unnecessary latency and parsing
overhead for a purely local protocol that already
has a TCP/TLS connection.
**References:** NXFR PROTOCOL.md §7

### D-05: Identity Keys (ECDSA P-256 vs Ed25519)
**Status:** Locked (v1.0)
**Decision:** Use ECDSA P-256 (secp256r1) for
device identity keys.
**Rationale:** While Ed25519 offers better
theoretical properties (deterministic signatures,
faster verification, immunity to certain side-
channel attacks), it suffers from poor support in
native platform TLS stacks, particularly Windows
SChannel. P-256 ensures maximum cross-platform
compatibility, allowing NXFR to use OS-native TLS
and hardware keystores without bundling heavy
crypto libraries everywhere. This decision is
purely pragmatic.

| Algorithm | rustls | BoringSSL/Android | SChannel/Windows | FIPS |
|-----------|--------|-------------------|-------------------|------|
| ECDSA P-256 | Full | Full | Full | Yes |
| Ed25519 | Full | Full | Limited/None | No |
| RSA-2048 | Full | Full | Full | Yes |

**Alternatives Considered:**
- **Ed25519:** Rejected due to the SChannel
limitations shown in the table, preventing
seamless Windows native integration.
- **RSA-2048/4096:** Rejected because key sizes
are too large and generation is too slow for
mobile devices compared to elliptic curves.
**References:** FIPS 186-4

### D-06: Control Message Encoding (CBOR vs JSON/Protobuf)
**Status:** Locked (v1.0)
**Decision:** Use CBOR (Concise Binary Object
Representation) for control payloads.
**Rationale:** CBOR is a self-describing binary
format that is significantly faster to parse and
smaller on the wire than JSON. Unlike Protocol
Buffers, it does not require a code generation
step or pre-shared schemas, allowing dynamic map
inspection and easier forward compatibility (by
simply ignoring unknown keys). It is natively
suited for embedding raw binary data (like hashes
and IDs) without base64 overhead.
**Alternatives Considered:**
- **JSON:** Rejected because encoding/decoding
binary data (hashes, keys) requires Base64
encoding, which inflates message size and CPU
usage.
- **Protocol Buffers:** Rejected due to the
friction of `protoc` toolchains in some cross-
platform build systems and the rigid schema
requirements.
**References:** RFC 8949 (CBOR)

### D-07: Chunk Integrity (SHA-256 per chunk vs whole file only)
**Status:** Locked (v1.0)
**Decision:** Compute and send a SHA-256 hash for
every data chunk, in addition to a whole-file
hash.
**Rationale:** LAN transfers can suffer from
silent data corruption due to bad network
switches, faulty Wi-Fi drivers, or RAM bit flips.
Validating integrity at the chunk level allows
immediate detection of corruption, triggering a
quick retransmission of only the failed chunk. If
only a whole-file hash was used, a corruption in a
10 GB file would only be caught at the very end,
wasting immense time and network bandwidth.
**Alternatives Considered:**
- **Whole-file hash only:** Rejected; wastes
bandwidth and user time if corruption occurs early
in a large file.
- **CRC32 for chunks:** Rejected; not
cryptographically secure against malicious
modification, and SHA-256 hardware acceleration on
modern CPUs makes the performance difference
negligible.
**References:** FIPS 180-4

### D-08: Transfer Multiplexing (Single connection vs multiplexed session)
**Status:** Locked (v1.0)
**Decision:** Multiplex multiple transfers and
streams over a single TCP/TLS session.
**Rationale:** Opening a new TCP connection and
performing a full TLS handshake for every file or
transfer incurs significant latency and CPU
overhead (especially on mobile devices).
Multiplexing via `stream_id` and `transfer_id`
allows a single long-lived session to efficiently
handle concurrent transfers and control messages
(like PAUSE or CANCEL) without head-of-line
blocking for critical control data.
**Alternatives Considered:**
- **One connection per file:** Rejected due to
massive TLS handshake overhead when transferring
directories with thousands of small files.
- **One connection per transfer:** Rejected as it
complicates the architecture and connection
tracking when users start multiple concurrent
transfers to the same device.
**References:** HTTP/2 Multiplexing concepts

### D-09: Directory Structure Preservation (Relative paths vs TAR/ZIP)
**Status:** Locked (v1.0)
**Decision:** Represent directories as a flat
manifest of files with relative paths, rather than
streaming an archive format.
**Rationale:** Sending a TAR or ZIP stream
requires the receiver to unpack it on the fly,
which complicates resume logic and makes partial
failure handling difficult. By listing relative
paths in a manifest and streaming files
sequentially, the receiver can reconstruct the
directory natively, write directly to the
filesystem, and resume gracefully if the transfer
is interrupted.
**Alternatives Considered:**
- **TAR streaming:** Rejected because seeking
within a TAR stream to resume an interrupted
transfer is complex and error-prone.
- **ZIP streaming:** Rejected due to CPU overhead
of compression and complexity of managing the
central directory structure.
**References:** NXFR PROTOCOL.md §14

### D-10: Trust On First Use (TOFU) Pairing (SAS code vs QR)
**Status:** Locked (v1.0)
**Decision:** Use TOFU with a 6-digit Short
Authentication String (SAS) derived via TLS key
exporter.
**Rationale:** NXFR targets devices that may not
have cameras (desktops, headless servers). A
6-digit numeric SAS is easy to verify visually
across any two screens and provides strong
protection against active Man-In-The-Middle (MITM)
attacks during initial pairing. Deriving it via
the TLS exporter securely binds the SAS to the
specific TLS session context.
**Alternatives Considered:**
- **QR Codes:** Rejected as a mandatory primary
mechanism because desktops/laptops often cannot
easily scan them, though they could be a future
capability.
- **No pairing (always prompt):** Rejected because
it causes user fatigue for frequently
communicating devices, leading to reflexive
clicking and reduced security.
**References:** RFC 5869 (HKDF)

### D-11: Resume Strategy (Receiver-driven partial state)
**Status:** Locked (v1.0)
**Decision:** The receiver persists partial state;
the sender queries it via RESUME_QUERY before
sending data.
**Rationale:** The receiver is the ultimate source
of truth for what has actually been written to
persistent disk. If the sender maintained the
state, it might send data the receiver already has
(if ACKs were lost in transit) or skip data the
receiver failed to write (if a disk error occurred
after an ACK). Querying the receiver ensures
exact, safe synchronization.
**Alternatives Considered:**
- **Sender-driven state:** Rejected because it
cannot reliably handle receiver-side data loss,
app crashes, or disk failures.
- **No resume support:** Rejected because bulk
transfers over Wi-Fi are frequently interrupted,
and resuming is a highly requested feature.
**References:** NXFR PROTOCOL.md §13

### D-12: Privacy and Advertisement (Opt-in receiving)
**Status:** Locked (v1.0)
**Decision:** Devices MUST NOT advertise
themselves on mDNS unless the user explicitly
enables receiving mode.
**Rationale:** Always-on mDNS advertisement leaks
device presence, identity, platform details, and
location to anyone on the network, creating a
significant privacy risk (e.g., tracking devices
across public Wi-Fi networks). Requiring explicit
opt-in ensures the user is aware they are
discoverable and in control of their privacy.
**Alternatives Considered:**
- **Always-on background advertising:** Rejected
due to severe privacy implications and battery
drain on mobile devices holding multicast locks.
**References:** GDPR Privacy by Default principles

### D-13: Flow Control (Chunk ACKs)
**Status:** Locked (v1.0)
**Decision:** Implement application-level flow
control using an in-flight window of 8 chunks and
explicit CHUNK_ACK messages.
**Rationale:** While TCP provides network-level
flow control, application-level ACKs are necessary
to ensure data is actually hashed, verified, and
written to disk safely. If a mobile device writes
to slow flash storage slower than the network can
receive data, relying only on TCP could lead to
massive memory bloat in the application buffer,
eventually causing an OOM crash.
**Alternatives Considered:**
- **TCP backpressure only:** Rejected because it
doesn't guarantee disk persistence and risks
application OOM errors if OS buffers are large or
app buffers aren't managed perfectly.
**References:** NXFR PROTOCOL.md §9.2.13

### D-14: File Metadata (Pre-transfer manifest vs in-stream)
**Status:** Locked (v1.0)
**Decision:** Send a complete manifest of all
files in the TRANSFER_REQUEST before data transfer
begins.
**Rationale:** A complete manifest allows the
receiver to display accurate consent UIs (total
size, file count, preview) before the user accepts
any data. It also allows the receiver to pre-
allocate disk space and detect insufficient
storage immediately, rather than failing halfway
through a long transfer.
**Alternatives Considered:**
- **In-stream headers:** Rejected because the user
cannot make an informed consent decision if they
don't know the total scope and size of the
transfer upfront.
**References:** NXFR PROTOCOL.md §9.2.8

### D-15: Path Sanitization (Strict allow-list)
**Status:** Locked (v1.0)
**Decision:** Enforce a strict set of path
validation rules (no `..`, no absolute paths, no
symlinks, standard characters only) on the
receiver.
**Rationale:** Directory traversal vulnerabilities
are the most common critical flaw in file transfer
applications. Relying on the sender to provide
safe paths is a fatal security assumption, as
senders may be compromised. The receiver must
rigorously sanitize all paths to prevent arbitrary
file overwrite attacks.
**Alternatives Considered:**
- **OS native path resolution:** Rejected because
native APIs often subtly allow traversal or
environment variable expansion if not used
perfectly, differing wildly across platforms.
**References:** NXFR PROTOCOL.md §18

### D-16: Capabilities Negotiation (HELLO intersection)
**Status:** Locked (v1.0)
**Decision:** Negotiate protocol capabilities
(e.g., compression, alternative hashes) via
intersection of advertised string arrays in the
HELLO exchange.
**Rationale:** This allows the protocol to evolve
without breaking backwards compatibility. If v0.2
adds `zstd` compression, older v0.1 clients will
simply not advertise `zstd`, and the intersection
will cleanly fall back to uncompressed transfer,
ensuring graceful degradation.
**Alternatives Considered:**
- **TLS ALPN for capabilities:** Rejected because
ALPN is meant for top-level application protocol
selection (`nxfr/0`), not fine-grained
application-level feature flags.
**References:** NXFR PROTOCOL.md §16.3

### D-17: TLS Handshake Signature Verification in Custom TOFU Verifiers
**Status:** Locked (v1.0)
**Decision:** Custom TLS certificate verifiers
(`NoServerVerifier`, `NoClientVerifier`) that bypass
public CA chain validation MUST explicitly verify
handshake signatures using standard cryptographic
primitives (`rustls::crypto::verify_tls13_signature`).
**Rationale:** Treating certificate chain validation
and handshake signature verification as equivalent
is a fatal security flaw. In a self-signed TOFU
model, certificate chain validation is bypassed so
that arbitrary self-signed keys can be pinned at the
application layer. However, signature verification
must be executed to cryptographically prove that the
peer possesses the private key corresponding to the
presented certificate. Without this, an adversary
presenting an intercepted public certificate could
complete the handshake without possessing the private
key.
**Alternatives Considered:**
- **No-op assertion verifiers:** Rejected because
returning an unconditional validation assertion
bypasses proof of key possession.
**References:** RFC 8446 §4.4.3, NXFR SECURITY.md §8.8

### D-18: Adaptive Beacon Frequency Ladder for Mobile Discovery
**Status:** Locked (v1.0)
**Decision:** Implement a dynamic 3-state UDP beacon
broadcasting interval for mobile devices:
- `ACTIVE` (1,000ms): UI in foreground or Device
  Picker actively displayed.
- `BACKGROUND` (5,000ms): Foreground service running
  with an active file transfer in progress.
- `LOW_POWER` (30,000ms): Deep background idle state,
  falling back on passive mDNS browsing.
**Rationale:** A static 1-second beacon interval
keeps the Wi-Fi radio awake continuously, leading to
significant battery drain and OS-enforced background
throttling. Stepping down broadcast intervals based on
user context preserves snappy local discovery when the
user is actively looking to connect while maintaining
negligible battery impact when idle.
**Alternatives Considered:**
- **Static 1-second beacon:** Rejected due to high
power consumption.
- **Pure mDNS on mobile:** Rejected because local Wi-Fi
hotspots frequently block multicast traffic, making
direct UDP unicast/broadcast essential for Tier-0
discovery.
**References:** NXFR IMPLEMENTATION_NOTES.md §4.10

### D-19: Listener Backoff & Concurrency Limits under Resource Starvation
**Status:** Locked (v1.0)
**Decision:** Bound concurrent TLS handshakes with
counting semaphores, enforce a 10-second handshake
timeout, and apply a 50ms sleep delay on all TCP
`accept()` errors before retrying.
**Rationale:** When a process reaches system resource
limits (e.g. `EMFILE`/`ENFILE`), immediate unthrottled
retry of `accept()` results in a tight busy-loop that
burns 100% CPU. Similarly, Slowloris attacks that open
thousands of unauthenticated TCP connections and stall
the TLS handshake exhaust memory and file descriptors.
Bounding concurrency and enforcing handshake timeouts
mitigates resource exhaustion deterministically.
**Alternatives Considered:**
- **Fatal exit on accept error:** Rejected because
transient file descriptor spikes should not crash
the daemon or mobile background service.
**References:** NXFR SECURITY.md §7.7, §7.8

---

## Open Questions

### Q-01: How should we handle symlinks in directory transfers?
**Impact if unresolved:** Users attempting to
transfer Linux/macOS directories containing
symlinks will encounter errors, skipped files, or
unexpected duplicated data, potentially breaking
development workflows.
**Recommendation:** For v0.1, we strictly skip
symlinks. For v0.2, we should introduce a
capability flag (`symlinks`). If both sides
support it, the manifest can include a new `type:
"symlink"` and `target: "path"` field, allowing
safe recreation of relative symlinks.

### Q-02: Should we support parallel file streaming within a directory?
**Impact if unresolved:** Transferring directories
with thousands of very small files will suffer
from high latency due to sequential FILE_METADATA
round-trips and TCP slow start on each new stream.
**Recommendation:** Stay sequential for v0.1 to
keep the state machine simple, deterministic, and
resume logic robust. Investigate parallel stream
multiplexing for v0.2, taking care to manage disk
I/O contention on spinning platters.

### Q-03: How do we prevent battery drain from mDNS MulticastLocks on Android?
**Impact if unresolved:** Android users who leave
receiving enabled may experience severe battery
drain due to the lock preventing the device from
sleeping, leading to app uninstalls and bad
reviews.
**Recommendation:** Implement a strict auto-
timeout for receiving mode on mobile (e.g., 15
minutes). Remind the user via persistent
notification, and automatically drop the
MulticastLock and stop the foreground service if
no transfer occurs.
**Resolution:** Resolved — adaptive 3-tier beacon power ladder (ACTIVE 1s / BACKGROUND 5s / LOW_POWER 30s)

### Q-04: Should we bundle a standalone mDNS implementation for Windows?
**Impact if unresolved:** Windows native
`Windows.Networking.ServiceDiscovery` has
historically been unreliable across different
network configurations, subnets, and VPNs, causing
discovery failures.
**Recommendation:** Start with the native API for
binary size reasons. If beta testing shows >10%
failure rates for discovery, pivot to bundling a
lightweight, pure-Rust mDNS responder directly
into the core library.

### Q-05: How should we represent empty directories in a transfer?
**Impact if unresolved:** Currently, empty
directories are omitted from the manifest and lost
during transfer, which breaks tools that rely on
specific directory structures (like git
repositories or build environments).
**Recommendation:** Add support for a `type:
"dir"` entry in the manifest. This allows explicit
creation of empty directories on the receiver
without associating any CHUNK frames.
**Resolution:** Resolved — `type: "dir"` manifest entries

### Q-06: What is the optimal default chunk size for Wi-Fi 6 vs older networks?
**Impact if unresolved:** A 1 MiB chunk size might
be too large for lossy 2.4GHz networks (causing
massive retransmissions) or too small for pristine
Wi-Fi 6 (causing excessive ACK overhead).
**Recommendation:** Stick to 1 MiB for v0.1 for
predictability. In the future, implement dynamic
chunk sizing based on RTT and packet loss metrics
derived from KEEPALIVE frames.

### Q-07: Should we support hardware-accelerated BLAKE3 instead of SHA-256?
**Impact if unresolved:** SHA-256 on older low-end
ARM devices without hardware cryptography
extensions may bottleneck transfer speeds below
500 Mbps, frustrating users on gigabit networks.
**Recommendation:** Standardize on SHA-256 for
baseline compatibility and FIPS requirement. Add
`blake3` as an optional negotiated capability to
dramatically speed up hashing on supported
devices.

### Q-08: How do we handle file permissions and executability bits?
**Impact if unresolved:** Transferring bash
scripts or binaries across Linux machines will
lose the `+x` bit, frustrating developer workflows
and requiring manual intervention.
**Recommendation:** Add an optional `posix_mode:
0755` integer field to the `FILE_METADATA`
payload. If the receiver is on a POSIX system, it
should safely apply the mode via `chmod` after
successful verification.

### Q-09: Should we support resuming transfers across different IP addresses?
**Impact if unresolved:** If a user moves from Wi-
Fi to Ethernet halfway through a large transfer,
the TCP connection drops. Users will want the new
connection to resume the old transfer seamlessly.
**Recommendation:** Yes, the protocol design
inherently supports this. `transfer_id` is
globally unique. As long as the peer authenticates
via mTLS with the exact same `device_id`, the
receiver should permit a `RESUME_QUERY` from the
new IP.

### Q-10: How do we prevent storage exhaustion attacks?
**Impact if unresolved:** A malicious or
compromised paired device could send a 1 TB file,
filling the receiver's disk, crashing the OS, and
causing data loss.
**Recommendation:** The receiver must check
available disk space against the manifest's
`total_size` before showing the consent UI. If
space is insufficient, automatically send
TRANSFER_REJECT with reason `disk_full`.
**Resolution:** Resolved — manifest total size pre-check

### Q-11: Should the UI support "Auto-Accept" for specific paired devices?
**Impact if unresolved:** Frequent transfers
between a user's own laptop and phone become
incredibly tedious if explicit consent is required
every single time on the receiving device.
**Recommendation:** Implement an auto-accept
policy in the paired device database. Allow users
to configure "Always accept from this device" in
the UI, bypassing the PENDING state and going
straight to NEGOTIATING.
**Resolution:** Resolved — `PairedDeviceDb` auto-accept policies

### Q-12: What happens if a file is modified on the sender during a transfer?
**Impact if unresolved:** The chunk hashes will
change, the final SHA-256 will fail, and the
receiver will reject the file, wasting bandwidth
and silently failing from the user's perspective.
**Recommendation:** The sender should ideally
acquire a read lock or filesystem snapshot. If
impossible, the sender must detect the change
(e.g., via mtime or size mismatch during read) and
send TRANSFER_CANCEL with reason `file_modified`.

### Q-13: How do we handle network interface switching during discovery?
**Impact if unresolved:** If a user connects to a
VPN or switches Wi-Fi networks, the mDNS
advertisements might be stuck on the old
interface, rendering the device undiscoverable.
**Recommendation:** The platform integration layer
must actively listen for OS-level network change
events and aggressively unpublish/republish the
mDNS service on the newly active interfaces.

### Q-14: Should we compress files before transfer?
**Impact if unresolved:** Transferring highly
compressible data (like large text log files or
uncompressed bitmaps) will waste significant time
and bandwidth on the LAN.
**Recommendation:** Do not compress by default to
save CPU and battery life. Introduce `zstd` as an
optional capability. If negotiated, chunks can be
compressed, but the chunk hash must always reflect
the uncompressed data for integrity.

### Q-15: How should the GUI represent a large directory structure in the consent dialog?
**Impact if unresolved:** A flat list of 10,000
files is overwhelming, unusable in a mobile
notification, and may crash the UI thread if not
rendered properly.
**Recommendation:** The UI should only display the
top-level `display_name` (the root directory), the
`total_files` count, and `total_size`. An "Expand"
or "Details" button can show a tree view, but it
must be lazy-loaded to preserve performance.

### Q-16: Should we support an anonymous "drop" mode for unauthenticated transfers?
**Impact if unresolved:** Users cannot easily
receive files from strangers at a conference or
coffee shop without going through the 6-digit SAS
pairing flow first.
**Recommendation:** Add a strictly temporary
"Receive from anyone" mode that automatically
expires after 10 minutes. Transfers in this mode
bypass pairing but always require explicit user
consent for every file, mitigating spam.
**Resolution:** Resolved — Web portal on port 17396
