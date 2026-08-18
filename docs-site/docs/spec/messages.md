!!! info "Protocol v1.0"
    This is the v1.0 specification. For the normative text, see the [`docs/`](https://github.com/nxfr/nxfr/tree/main/docs) directory.

# Messages & State Machines

NXFR uses CBOR-encoded control messages to drive its state machine. Every CONTROL frame payload MUST contain a `"type"` key with an unsigned integer identifying the message.

## Message Type Codes

| Code | Name | Direction | Description |
|------|------|-----------|-------------|
| `0x01` | HELLO | Initiator → Responder | Session initiation |
| `0x02` | HELLO_ACK | Responder → Initiator | Session acceptance and session_id assignment |
| `0x03` | PAIR_REQUEST | Either → Either | Initiate pairing |
| `0x04` | PAIR_ACCEPT | Either → Either | Confirm pairing (SAS matched) |
| `0x05` | PAIR_REJECT | Either → Either | Reject pairing |
| `0x06` | SESSION_CLOSE | Either → Either | Graceful session termination |
| `0x09` | ERROR | Either → Either | Error notification |
| `0x10` | TRANSFER_REQUEST | Sender → Receiver | Propose a file transfer |
| `0x11` | TRANSFER_ACCEPT | Receiver → Sender | Accept proposed transfer |
| `0x12` | TRANSFER_REJECT | Receiver → Sender | Reject proposed transfer |
| `0x13` | FILE_METADATA | Sender → Receiver | Per-file metadata before streaming |
| `0x14` | FILE_METADATA_ACK | Receiver → Sender | Acknowledge file metadata |
| `0x15` | CHUNK_ACK | Receiver → Sender | Acknowledge received chunk |
| `0x16` | TRANSFER_PAUSE | Either → Either | Pause active transfer |
| `0x17` | TRANSFER_RESUME | Either → Either | Resume paused transfer |
| `0x18` | TRANSFER_CANCEL | Either → Either | Cancel transfer |
| `0x19` | TRANSFER_COMPLETE | Sender → Receiver | All data sent |
| `0x1A` | TRANSFER_ACK | Receiver → Sender | Final transfer acknowledgment |
| `0x20` | RESUME_QUERY | Sender → Receiver | Query for resumable state |
| `0x21` | RESUME_STATUS | Receiver → Sender | Report resumable state |

## HELLO Exchange

The session starts with the initiator sending a `HELLO` message containing its protocol version, identity, and device info. The responder answers with `HELLO_ACK`, assigning a 32-bit `session_id` which must be used in all subsequent frames.

## Pairing Flow

If devices are not paired, either device may initiate a `PAIR_REQUEST` specifying a SAS method (e.g., `"numeric-6"`).
Both devices derive a 6-digit SAS code using a TLS-Exporter. If the users confirm the codes match on their screens, a `PAIR_ACCEPT` is exchanged, and the peer's `device_id` is pinned for future sessions.

```cbor-diag
{
  "type":       3,
  "sas_method": "numeric-6"
}
```

## State Machine Overview

The state machine is cleanly separated into Session and Transfer layers. The session orchestrates HELLO and Pairing, then allows concurrent transfers. Each transfer operates its own state machine: Request → Accept → File Metadata → Chunks → Complete → Ack.
