# Installation

Welcome to the NXFR (Nearby Xfer Protocol) installation guide. This guide will walk you through setting up NXFR on your Linux system, primarily focusing on compiling from source and running the background daemon via `systemd`.

## Prerequisites

Before installing NXFR, ensure your system meets the following requirements:

1. **Rust Toolchain**: You will need the Rust compiler and Cargo to build the project from source.
   Install Rust via [rustup](https://rustup.rs/):
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   ```
2. **Linux with systemd**: The daemon relies heavily on `systemd` user services for its lifecycle management. (Other init systems are possible but not officially documented here).
3. **mDNS Support**: NXFR uses mDNS to discover other devices on the local network. Most modern Linux distributions come with Avahi pre-installed. Make sure the Avahi daemon is running:
   ```bash
   systemctl status avahi-daemon
   ```

## Building from Source

First, clone the official repository and build the workspace:

```bash
git clone https://github.com/nxfr/nxfr.git
cd nxfr
cargo build --workspace --release
```

This process might take a few minutes as Cargo downloads and compiles all dependencies.

## Installing the Binaries

Once the build is complete, you can install the compiled binaries to your local Cargo bin directory (usually `~/.cargo/bin`):

```bash
cargo install --path crates/nxfr-daemon
cargo install --path crates/nxfr-cli
```

Verify the installation by checking that `nxfr` is available in your PATH:

```bash
nxfr --version
```

## Setting up the systemd User Service

NXFR relies on a background daemon (`nxfr-daemon`) to handle network discovery, incoming connections, and background transfers. Setting this up as a `systemd` user service is highly recommended.

Create a new service unit file at `~/.config/systemd/user/nxfr.service` with the following content:

```ini
[Unit]
Description=NXFR Daemon - Nearby File Transfer
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=%h/.cargo/bin/nxfr-daemon
Restart=on-failure
RestartSec=5
Environment=RUST_LOG=info

[Install]
WantedBy=default.target
```

### Enable and Start the Service

Reload the systemd user daemon, enable the service to start on login, and start it immediately:

```bash
systemctl --user daemon-reload
systemctl --user enable --now nxfr
```

### Verification

Ensure the daemon is running smoothly by checking the status using the NXFR CLI:

```bash
nxfr status
```

This command should output your device's ID, the daemon's uptime, and any active transfers.

## Configuration File

The NXFR daemon uses a TOML configuration file located at `~/.config/nxfr/config.toml`. If the file does not exist, the daemon will use default values.

Example `config.toml`:

```toml
device_name = "My-Laptop"
receive_dir = "/home/user/Downloads/NXFR"
receiving_enabled = true
```

## File Locations

NXFR stores various keys, databases, and configuration files in standard XDG directories on Linux:

| Path | Purpose |
|------|---------|
| `~/.local/share/nxfr/identity.der` | Private key (PKCS#8 DER) |
| `~/.local/share/nxfr/identity.crt` | Self-signed certificate (X.509 DER) |
| `~/.local/share/nxfr/paired.db` | SQLite database of paired devices |
| `~/.config/nxfr/config.toml` | User configuration |
| `~/.local/state/nxfr/nxfr.sock` | IPC Unix socket |
| `~/.local/share/nxfr/resume/` | Transfer resume journals |

## Troubleshooting

### Port in Use
If the daemon fails to start due to port 17394 being in use, verify that no other instance of `nxfr-daemon` is running. You can check which process is using the port with:
```bash
lsof -i :17394
```

### mDNS Not Working
If devices fail to discover each other, ensure that:
1. Both devices are on the same local subnet.
2. The `avahi-daemon` is active and running on both machines.
3. Your firewall is not blocking multicast traffic (UDP port 5353).
