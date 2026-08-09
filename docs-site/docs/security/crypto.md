!!! warning "Draft Specification"
    This is the v0.1 draft specification. The protocol is actively evolving.
    Refer to the [`docs/`](https://github.com/nxfr/nxfr/tree/main/docs) directory for the raw normative text.

# Cryptography & Pairing

## Cryptographic Primitives

- **Identity Keys**: ECDSA P-256 (secp256r1)
- **Transport**: TLS 1.3
- **Cipher Suites**: `TLS_AES_256_GCM_SHA384`, `TLS_AES_128_GCM_SHA256`, `TLS_CHACHA20_POLY1305_SHA256`
- **Key Exchange**: X25519 or secp256r1
- **Hashing**: SHA-256

*Rationale for P-256 over Ed25519:* While Ed25519 has superior theoretical properties, Windows SChannel has limited support for Ed25519 in TLS certificate authentication. P-256 ensures cross-platform compatibility natively.

## Certificates & SPKI Pinning

Each device presents a self-signed X.509 certificate during the TLS handshake. Certificate validation in NXFR does NOT use a CA trust chain. Instead, trust is anchored on the `device_id`:

1. Extract the peer's SubjectPublicKeyInfo (SPKI) from the presented certificate.
2. Compute `peer_device_id = SHA-256(SPKI DER)`.
3. If paired, verify `peer_device_id` matches the pinned identity in the local database.
4. If unpaired, accept the identity temporarily (TOFU) and offer pairing.

If a device's long-term key changes (e.g., app reinstalled), the `device_id` changes. Implementations MUST detect this mismatch and drop connections to previously paired peers until they re-pair.

## Pairing & SAS Derivation

The NXFR pairing protocol relies on a Trust On First Use (TOFU) model supplemented by a Short Authentication String (SAS). This ensures protection against active MITM attackers during the initial connection.

Both sides independently compute the SAS using the `numeric-6` method:

1. Compute `context = sort(device_id_a, device_id_b)` (lexicographic ordering of the raw 32-byte values).
2. Derive keying material from the TLS session:
   ```rust
   sas_bytes = TLS-Exporter("NXFR-SAS-v0", context, 4)
   ```
3. Compute the 6-digit display value:
   ```rust
   sas_value = BigEndian_u32(sas_bytes) mod 1000000
   ```
4. Display as a zero-padded 6-digit decimal number (e.g., `"042857"`).

Because the SAS is derived using `TLS-Exporter`, which intrinsically binds the exported material to the specific TLS master secret of the session, an attacker attempting a MITM attack will generate different SAS codes on each side, alerting the users.
