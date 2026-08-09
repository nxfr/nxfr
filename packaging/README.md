# NXFR Packaging

## Build from Source

You can install the NXFR daemon and CLI tools using cargo:

```bash
cargo install --path crates/nxfr-daemon
cargo install --path crates/nxfr-cli
```

By default, cargo installs binaries into `~/.cargo/bin/`.

## systemd User Service Setup

To run the NXFR daemon automatically as a user service:

```bash
mkdir -p ~/.config/systemd/user/
cp packaging/nxfr.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now nxfr
```

Note: Distro packages should override `ExecStart` if installing to a global prefix (e.g., `/usr/bin/nxfr-daemon`).

## Directory Table

| Path | Purpose |
| --- | --- |
| `~/.local/share/nxfr/` | Data directory (keys, config data) |
| `~/.config/nxfr/` | Configuration directory |

## Uninstall

To uninstall the tools and service:

```bash
systemctl --user disable --now nxfr
rm ~/.config/systemd/user/nxfr.service
cargo uninstall nxfr-daemon nxfr-cli
```
