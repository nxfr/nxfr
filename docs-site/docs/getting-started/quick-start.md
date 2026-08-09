# Quick Start

This guide will quickly walk you through sending and receiving files with NXFR.

Before starting, ensure you have successfully installed NXFR by following the [Installation Guide](installation.md).

## Step 1: Start the Daemon

If you haven't already started the NXFR daemon (or set it up as a systemd service), run it in the background:

```bash
nxfr-daemon &
```
*(Note: Using the systemd service as described in the installation guide is the recommended approach for daily use).*

## Step 2: Check Status

Verify that the daemon is running properly and check your device's identity by running the status command:

```bash
nxfr status
```

**Example Output:**
```
NXFR Daemon Status: Online
Device Name: My-Laptop
Device ID: e4f1b3c9a2...
Uptime: 00:15:30
Active Transfers: 0
Receiving Enabled: true
```

## Step 3: Enable Receiving

To allow other devices to find you and send you files, you must explicitly enable receiving. This publishes your device via mDNS and starts listening for incoming connections.

```bash
nxfr receive --enable
```
*(You can disable this at any time with `nxfr receive --disable` to become completely invisible on the network).*

## Step 4: Send a File

To send a file to a device on your local network, use the `send` command. You can target the device by name or ID using the `--to` flag.

```bash
nxfr send photo.jpg --to "Friend's Laptop"
```

If you don't specify the `--to` flag, the CLI will open an interactive TUI (Text User Interface) for you to browse and select a nearby device.

**Example Output:**
```
Discovering devices...
Found "Friend's Laptop" (IP: 192.168.1.105)
Initiating transfer of 'photo.jpg' (2.4 MB)...
[========================================] 100%
Transfer complete!
```

## Step 5: Receive Files

When another device attempts to send you a file, you need to monitor for incoming requests and accept them. You can use the `watch` command to interactively accept or reject incoming transfers:

```bash
nxfr watch
```

**Example Output:**
```
Waiting for incoming transfers...

Incoming transfer request:
From: Friend's Laptop
File: meeting-notes.pdf (1.2 MB)
Action: [A]ccept / [R]eject? A

Accepting transfer...
[========================================] 100%
File saved to ~/Downloads/NXFR/meeting-notes.pdf
```

## Step 6: Pair Devices for Trust

To avoid having to manually verify a connection's security every time, you can pair devices. Pairing involves verifying a Short Authentication String (SAS).

Initiate a pairing request:
```bash
nxfr pair "Friend's Laptop"
```

Both devices will display a 4-digit code. If they match, confirm the pairing:
```
SAS Code: 4892
Does this match the code on "Friend's Laptop"? [y/N]: y
Pairing successful. Device added to trusted database.
```

## Tips for Power Users

- **Self-send for testing:** You can test the daemon's loopback interface by sending a file to yourself using `nxfr send ./test.txt --to self`.
- **Direct IP connection:** If mDNS isn't working on your network, you can bypass discovery and connect directly via IP using the `--addr` flag: `nxfr send ./test.txt --addr 192.168.1.105:17394`.
- **Resume interrupted transfers:** By default, NXFR supports resumable transfers. If a transfer fails midway, simply running the exact same `nxfr send` command again will resume using the `--retry` mechanism.
