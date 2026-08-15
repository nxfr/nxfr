!!! warning "Draft Specification"
    This is the v0.1 draft specification. The protocol is actively evolving.
    Refer to the [`docs/`](https://github.com/nxfr/nxfr/tree/main/docs) directory for the raw normative text.

# Privacy & mDNS

The use of mDNS for zero-configuration discovery introduces specific privacy considerations. NXFR is designed to minimize the leakage of identifying information to the local network.

## Hidden by Default

To mitigate mDNS privacy risks, NXFR mandates that devices MUST NOT advertise their presence by default. A device is completely silent on the network until the user explicitly enables receiving mode in the UI.

When receiving mode is active, the device broadcasts:
- Service instance name
- IP address
- Platform type
- Truncated `device_id` prefix

## Rotating Advertised IDs

If the mDNS TXT record contained a static, long-term identifier, it would allow a passive observer to track the user's presence over time across different networks (e.g., tracking a phone moving between coffee shops).

To prevent this, implementations SHOULD rotate the `id` value advertised in the DNS-SD TXT record daily:

```rust
advertised_id = first_16_hex_chars( SHA-256(device_id || "YYYY-MM-DD") )
```

- Unpaired observers see only a rotating 8-byte prefix and cannot correlate the device across different days.
- Paired peers (who know the pinned `device_id`) can pre-compute today's `advertised_id` to identify known devices immediately.

## No PII in Discovery

Unlike older iterations of peer-to-peer protocols that leaked partial phone numbers or email hashes during discovery, NXFR never includes any Personally Identifiable Information (PII) during discovery. The device name is only visible when the user chooses to be discoverable, and the `device_id` is tied to an ephemeral cryptographic keypair, not real-world identity.

Furthermore, NXFR operates purely peer-to-peer over the LAN and performs **no cloud telemetry** or tracking whatsoever.
