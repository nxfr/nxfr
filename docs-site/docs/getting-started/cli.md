# CLI Reference

The `nxfr-cli` executable (`nxfr`) acts as the primary interface for interacting with the NXFR background daemon.

## Command Overview

All commands follow the standard syntax:
`nxfr <command> [options]`

---

## `status`

Displays the current health, identity, and activity of the NXFR daemon.

**Syntax:** `nxfr status`

| Option | Description |
|--------|-------------|
| `--json` | Outputs the status information in JSON format for scripting. |

**Example:**
=== "Human Readable"
    ```bash
    $ nxfr status
    NXFR Daemon Status: Online
    Device Name: My-Laptop
    Device ID: e4f1b3c9a2...
    Uptime: 00:15:30
    Active Transfers: 0
    Receiving Enabled: true
    ```
=== "JSON"
    ```bash
    $ nxfr status --json
    {"status":"online","device_name":"My-Laptop","device_id":"e4f1b3c9a2...","uptime":930,"active_transfers":0,"receiving_enabled":true}
    ```

---

## `send`

Initiates a file or directory transfer to a specific device.

**Syntax:** `nxfr send <path> [options]`

| Option | Description |
|--------|-------------|
| `--to <name>` | Target device name or ID. If omitted, opens an interactive device picker. |
| `--addr <ip>` | Bypass mDNS and connect directly to an IP address and port. |
| `--retry` | Attempt to resume a previously interrupted transfer. |

**Example:**
```bash
$ nxfr send report.pdf --to "Alice's Phone"
Discovering devices...
Found "Alice's Phone" (IP: 192.168.1.42)
Initiating transfer of 'report.pdf' (1.4 MB)...
[========================================] 100%
Transfer complete!
```

---

## `watch`

Connects to the daemon's event stream and listens for incoming transfer requests, prompting interactively for user consent.

**Syntax:** `nxfr watch`

| Option | Description |
|--------|-------------|
| `--auto-accept` | Automatically accept all incoming transfers (DANGEROUS). |

**Example:**
```bash
$ nxfr watch
Waiting for incoming transfers...

Incoming transfer request:
From: Bob's Desktop
File: project-files.zip (450 MB)
Action: [A]ccept / [R]eject? A
```

---

## `accept` & `reject`

Manually accepts or rejects a specific pending transfer by its ID. Typically used in scripts where `watch` is not appropriate.

**Syntax:** `nxfr accept <transfer_id>` or `nxfr reject <transfer_id>`

| Option | Description |
|--------|-------------|
| None | N/A |

**Example:**
```bash
$ nxfr accept tx-9f8a72b
Transfer tx-9f8a72b accepted.
```

---

## `devices`

Lists all paired devices from the database as well as currently discovered nearby devices on the LAN.

**Syntax:** `nxfr devices [options]`

| Option | Description |
|--------|-------------|
| `--json` | Outputs the list in JSON format. |
| `--paired-only`| Only list devices that are in the local trusted database. |

**Example:**
```bash
$ nxfr devices
Discovered Devices:
- "Alice's Phone" (192.168.1.42) - Unpaired
- "Living Room TV" (192.168.1.100) - Unpaired

Paired Devices:
- "Bob's Desktop" (Last seen: 2 hours ago)
```

---

## `pair`

Initiates a pairing request with a device to establish trust. Displays a Short Authentication String (SAS) code that must match on both screens.

**Syntax:** `nxfr pair <device>`

| Option | Description |
|--------|-------------|
| None | N/A |

**Example:**
```bash
$ nxfr pair "Alice's Phone"
Initiating pairing with "Alice's Phone"...
SAS Code: 8391
Does this match the code on "Alice's Phone"? [y/N]: y
Pairing successful.
```

---

## `receive`

Toggles the daemon's mDNS advertisement and receiving capabilities.

**Syntax:** `nxfr receive [options]`

| Option | Description |
|--------|-------------|
| `--enable` | Starts advertising via mDNS and listens for incoming connections. |
| `--disable`| Stops advertising and drops incoming connection capabilities. |

**Example:**
```bash
$ nxfr receive --enable
Receiving enabled. Device is now discoverable as "My-Laptop".
```
