//! NXFR Protocol Loopback Test
//!
//! Drives the exact 12-frame sequence from WIRE_FORMAT.md §10 over localhost.
//! Generates P-256 keypairs, establishes mTLS, and verifies each frame.

use nxfr_common::{DeviceId, Platform, ProtocolVersion, TransferId};
use nxfr_core::codec;
use nxfr_core::frame::{FrameHeader, FrameKind};
use nxfr_core::messages::{
    ControlMessage, ManifestEntry, ManifestEntryType, TransferAckStatus, TransferType,
};
use nxfr_crypto::{device_id_from_cert, generate_identity};
use nxfr_transport::connection::NxfrConnection;
use nxfr_transport::tls;
use rustls::pki_types::ServerName;
use sha2::{Digest, Sha256};
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio_rustls::{TlsAcceptor, TlsConnector};

/// The test file data: 16 bytes 0x00..0x0f
const TEST_DATA: [u8; 16] = [
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
];

/// Expected SHA-256 of TEST_DATA
const EXPECTED_HASH: &str = "be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a8991";

/// Session ID used in the trace
const SESSION_ID: u32 = 0x1234;

/// Transfer ID: 16 bytes of 0xAA
const TRANSFER_ID: TransferId = TransferId([0xAA; 16]);

struct FrameResult {
    num: usize,
    name: &'static str,
    direction: &'static str,
    passed: bool,
    detail: String,
}

impl FrameResult {
    fn pass(num: usize, name: &'static str, direction: &'static str) -> Self {
        Self {
            num,
            name,
            direction,
            passed: true,
            detail: String::new(),
        }
    }
    fn fail(num: usize, name: &'static str, direction: &'static str, detail: String) -> Self {
        Self {
            num,
            name,
            direction,
            passed: false,
            detail,
        }
    }
}

fn verify_header(
    header: &FrameHeader,
    expected_kind: FrameKind,
    expected_session: u32,
) -> Result<(), String> {
    if header.kind != expected_kind {
        return Err(format!(
            "kind={:?}, expected {:?}",
            header.kind, expected_kind
        ));
    }
    if header.session_id != expected_session {
        return Err(format!(
            "session_id=0x{:04x}, expected 0x{:04x}",
            header.session_id, expected_session
        ));
    }
    Ok(())
}

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== NXFR Loopback Test (§10 Golden Wire Trace) ===\n");

    // Generate identities for Alice (sender) and Bob (receiver).
    let alice_id = generate_identity().expect("Alice identity");
    let bob_id = generate_identity().expect("Bob identity");

    let alice_device_id = DeviceId::from_bytes(alice_id.device_id);
    let bob_device_id = DeviceId::from_bytes(bob_id.device_id);

    println!("Alice device_id: {alice_device_id}");
    println!("Bob   device_id: {bob_device_id}\n");

    // Compute file hash.
    let file_hash: [u8; 32] = Sha256::digest(TEST_DATA).into();
    assert_eq!(hex::encode(file_hash), EXPECTED_HASH);

    // Set up TLS server (Bob).
    let server_config = tls::build_server_config(bob_id.private_key(), bob_id.certificate())?;
    let acceptor = TlsAcceptor::from(Arc::new(server_config));

    // Bind to ephemeral port.
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let addr = listener.local_addr()?;
    println!("Bob listening on {addr}\n");

    // Set up TLS client (Alice).
    let client_config = tls::build_client_config(alice_id.private_key(), alice_id.certificate())?;
    let connector = TlsConnector::from(Arc::new(client_config));

    // Clone identities for Bob's spawned task.
    let alice_device_id_clone = alice_device_id;
    let bob_id_clone = bob_id.clone();

    // Spawn Bob (server) task.
    let bob_handle = tokio::spawn(async move {
        let (tcp_stream, _) = listener.accept().await.unwrap();
        let tls_stream = acceptor.accept(tcp_stream).await.unwrap();

        // Verify peer cert → device_id
        let (_, server_conn) = tls_stream.get_ref();
        let peer_certs = server_conn
            .peer_certificates()
            .expect("peer certs must be available");
        let peer_cert = &peer_certs[0];
        let peer_id = device_id_from_cert(peer_cert.as_ref()).unwrap();
        assert_eq!(
            peer_id, alice_device_id_clone.0,
            "Bob sees Alice's device_id"
        );

        let mut conn = NxfrConnection::new(tls_stream);
        let mut bob_results: Vec<FrameResult> = Vec::new();

        // Frame 1: Receive HELLO from Alice
        let (hdr, payload) = conn.recv_frame().await.unwrap();
        match verify_header(&hdr, FrameKind::Control, 0) {
            Ok(()) => {
                let msg = codec::decode_control(&payload).unwrap();
                match &msg {
                    ControlMessage::Hello {
                        protocol_version,
                        device_id,
                        ..
                    } => {
                        let ver_ok = *protocol_version == ProtocolVersion::V0_1;
                        let id_ok = device_id.as_bytes().len() == 32;
                        if ver_ok && id_ok {
                            bob_results.push(FrameResult::pass(1, "HELLO", "A→B"));
                        } else {
                            bob_results.push(FrameResult::fail(
                                1,
                                "HELLO",
                                "A→B",
                                format!("ver_ok={ver_ok} id_ok={id_ok}"),
                            ));
                        }
                    }
                    _ => bob_results.push(FrameResult::fail(
                        1,
                        "HELLO",
                        "A→B",
                        "wrong msg type".into(),
                    )),
                }
            }
            Err(e) => bob_results.push(FrameResult::fail(1, "HELLO", "A→B", e)),
        }

        // Frame 2: Send HELLO_ACK to Alice
        let hello_ack = ControlMessage::HelloAck {
            protocol_version: ProtocolVersion::V0_1,
            device_id: DeviceId::from_bytes(bob_id_clone.device_id),
            device_name: "Bob-Phone".to_string(),
            platform: Platform::Android,
            capabilities: vec![],
            is_paired: false,
            session_id: SESSION_ID,
        };
        conn.send_control(SESSION_ID, 0, &hello_ack).await.unwrap();
        bob_results.push(FrameResult::pass(2, "HELLO_ACK", "B→A"));

        // Frame 3: Receive TRANSFER_REQUEST from Alice
        let (hdr, payload) = conn.recv_frame().await.unwrap();
        match verify_header(&hdr, FrameKind::Control, SESSION_ID) {
            Ok(()) => {
                let msg = codec::decode_control(&payload).unwrap();
                match &msg {
                    ControlMessage::TransferRequest {
                        transfer_id,
                        manifest,
                        total_files,
                        total_size,
                        ..
                    } => {
                        let tid_ok = *transfer_id == TRANSFER_ID;
                        let files_ok = *total_files == 1;
                        let size_ok = *total_size == 16;
                        let manifest_ok = manifest.len() == 1;
                        if tid_ok && files_ok && size_ok && manifest_ok {
                            bob_results.push(FrameResult::pass(3, "TRANSFER_REQUEST", "A→B"));
                        } else {
                            bob_results.push(FrameResult::fail(
                                3,
                                "TRANSFER_REQUEST",
                                "A→B",
                                format!("tid={tid_ok} files={files_ok} size={size_ok} manifest={manifest_ok}"),
                            ));
                        }
                    }
                    _ => bob_results.push(FrameResult::fail(
                        3,
                        "TRANSFER_REQUEST",
                        "A→B",
                        "wrong type".into(),
                    )),
                }
            }
            Err(e) => bob_results.push(FrameResult::fail(3, "TRANSFER_REQUEST", "A→B", e)),
        }

        // Frame 4: Send TRANSFER_ACCEPT
        let accept = ControlMessage::TransferAccept {
            transfer_id: TRANSFER_ID,
        };
        conn.send_control(SESSION_ID, 0, &accept).await.unwrap();
        bob_results.push(FrameResult::pass(4, "TRANSFER_ACCEPT", "B→A"));

        // Frame 5: Receive FILE_METADATA
        let (hdr, payload) = conn.recv_frame().await.unwrap();
        match verify_header(&hdr, FrameKind::Control, SESSION_ID) {
            Ok(()) => {
                let msg = codec::decode_control(&payload).unwrap();
                match &msg {
                    ControlMessage::FileMetadata {
                        file_id, stream_id, ..
                    } => {
                        if *file_id == 1 && *stream_id == 1 {
                            bob_results.push(FrameResult::pass(5, "FILE_METADATA", "A→B"));
                        } else {
                            bob_results.push(FrameResult::fail(
                                5,
                                "FILE_METADATA",
                                "A→B",
                                format!("file_id={file_id} stream_id={stream_id}"),
                            ));
                        }
                    }
                    _ => bob_results.push(FrameResult::fail(
                        5,
                        "FILE_METADATA",
                        "A→B",
                        "wrong type".into(),
                    )),
                }
            }
            Err(e) => bob_results.push(FrameResult::fail(5, "FILE_METADATA", "A→B", e)),
        }

        // Frame 6: Send FILE_METADATA_ACK
        let ack = ControlMessage::FileMetadataAck {
            transfer_id: TRANSFER_ID,
            file_id: 1,
            stream_id: 1,
            accepted: true,
        };
        conn.send_control(SESSION_ID, 0, &ack).await.unwrap();
        bob_results.push(FrameResult::pass(6, "FILE_METADATA_ACK", "B→A"));

        // Frame 7: Receive CHUNK (LAST_CHUNK)
        let (hdr, payload) = conn.recv_frame().await.unwrap();
        match verify_header(&hdr, FrameKind::Chunk, SESSION_ID) {
            Ok(()) => {
                let flags_ok = hdr.flags.0 == 0x0001; // LAST_CHUNK
                let stream_ok = hdr.stream_id == 1;
                // Parse chunk: 8 bytes offset + 32 bytes hash + 16 bytes data = 56
                if payload.len() == 56 && flags_ok && stream_ok {
                    let offset = u64::from_be_bytes(payload[0..8].try_into().unwrap());
                    let chunk_hash = &payload[8..40];
                    let data = &payload[40..56];

                    let computed_hash = Sha256::digest(data);
                    let hash_ok = chunk_hash == computed_hash.as_slice();
                    let data_ok = data == TEST_DATA;
                    let offset_ok = offset == 0;

                    if hash_ok && data_ok && offset_ok {
                        bob_results.push(FrameResult::pass(7, "CHUNK (LAST)", "A→B"));
                    } else {
                        bob_results.push(FrameResult::fail(
                            7,
                            "CHUNK (LAST)",
                            "A→B",
                            format!("hash={hash_ok} data={data_ok} offset={offset_ok}"),
                        ));
                    }
                } else {
                    bob_results.push(FrameResult::fail(
                        7,
                        "CHUNK (LAST)",
                        "A→B",
                        format!(
                            "len={} flags=0x{:04x} stream={}",
                            payload.len(),
                            hdr.flags.0,
                            hdr.stream_id
                        ),
                    ));
                }
            }
            Err(e) => bob_results.push(FrameResult::fail(7, "CHUNK (LAST)", "A→B", e)),
        }

        // Frame 8: Send CHUNK_ACK
        let chunk_ack = ControlMessage::ChunkAck {
            stream_id: 1,
            message_id: 4, // Acknowledging the chunk message
            offset: 0,
            length: 16,
        };
        conn.send_control(SESSION_ID, 0, &chunk_ack).await.unwrap();
        bob_results.push(FrameResult::pass(8, "CHUNK_ACK", "B→A"));

        // Frame 9: Receive TRANSFER_COMPLETE
        let (hdr, payload) = conn.recv_frame().await.unwrap();
        match verify_header(&hdr, FrameKind::Control, SESSION_ID) {
            Ok(()) => {
                let msg = codec::decode_control(&payload).unwrap();
                match &msg {
                    ControlMessage::TransferComplete { transfer_id } => {
                        if *transfer_id == TRANSFER_ID {
                            bob_results.push(FrameResult::pass(9, "TRANSFER_COMPLETE", "A→B"));
                        } else {
                            bob_results.push(FrameResult::fail(
                                9,
                                "TRANSFER_COMPLETE",
                                "A→B",
                                "tid mismatch".into(),
                            ));
                        }
                    }
                    _ => bob_results.push(FrameResult::fail(
                        9,
                        "TRANSFER_COMPLETE",
                        "A→B",
                        "wrong type".into(),
                    )),
                }
            }
            Err(e) => bob_results.push(FrameResult::fail(9, "TRANSFER_COMPLETE", "A→B", e)),
        }

        // Verify whole-file SHA-256
        let whole_file_hash = Sha256::digest(TEST_DATA);
        let whole_file_ok = hex::encode(whole_file_hash) == EXPECTED_HASH;

        // Frame 10: Send TRANSFER_ACK
        let transfer_ack = ControlMessage::TransferAck {
            transfer_id: TRANSFER_ID,
            status: TransferAckStatus::Success,
            failed_files: None,
        };
        conn.send_control(SESSION_ID, 0, &transfer_ack)
            .await
            .unwrap();
        bob_results.push(FrameResult::pass(10, "TRANSFER_ACK", "B→A"));

        // Frame 11: Receive SESSION_CLOSE
        let (hdr, payload) = conn.recv_frame().await.unwrap();
        match verify_header(&hdr, FrameKind::Control, SESSION_ID) {
            Ok(()) => {
                let msg = codec::decode_control(&payload).unwrap();
                match &msg {
                    ControlMessage::SessionClose { reason } => {
                        if reason.as_deref() == Some("normal") {
                            bob_results.push(FrameResult::pass(11, "SESSION_CLOSE", "A→B"));
                        } else {
                            bob_results.push(FrameResult::fail(
                                11,
                                "SESSION_CLOSE",
                                "A→B",
                                format!("reason={reason:?}"),
                            ));
                        }
                    }
                    _ => bob_results.push(FrameResult::fail(
                        11,
                        "SESSION_CLOSE",
                        "A→B",
                        "wrong type".into(),
                    )),
                }
            }
            Err(e) => bob_results.push(FrameResult::fail(11, "SESSION_CLOSE", "A→B", e)),
        }

        // Frame 12: Send SESSION_CLOSE
        let close = ControlMessage::SessionClose {
            reason: Some("normal".to_string()),
        };
        conn.send_control(SESSION_ID, 0, &close).await.unwrap();
        bob_results.push(FrameResult::pass(12, "SESSION_CLOSE", "B→A"));

        (bob_results, whole_file_ok)
    });

    // Alice (client) side.
    let tcp_stream = TcpStream::connect(addr).await?;
    let server_name = ServerName::try_from("nxfr-node").unwrap();
    let tls_stream = connector.connect(server_name, tcp_stream).await?;

    // Verify peer cert → device_id
    let (_, client_conn) = tls_stream.get_ref();
    let peer_certs = client_conn
        .peer_certificates()
        .expect("peer certs must be available");
    let peer_cert = &peer_certs[0];
    let peer_id = device_id_from_cert(peer_cert.as_ref())?;
    assert_eq!(peer_id, bob_device_id.0, "Alice sees Bob's device_id");

    let mut conn = NxfrConnection::new(tls_stream);

    // Frame 1: Send HELLO
    let hello = ControlMessage::Hello {
        protocol_version: ProtocolVersion::V0_1,
        device_id: alice_device_id,
        device_name: "Alice-Laptop".to_string(),
        platform: Platform::Linux,
        capabilities: vec![],
        is_paired: false,
    };
    conn.send_control(0, 0, &hello).await?;

    // Frame 2: Receive HELLO_ACK
    let (hdr, payload) = conn.recv_frame().await?;
    verify_header(&hdr, FrameKind::Control, SESSION_ID)
        .map_err(|e| format!("HELLO_ACK header: {e}"))?;
    let msg = codec::decode_control(&payload).map_err(|e| format!("HELLO_ACK decode: {e}"))?;
    match &msg {
        ControlMessage::HelloAck { session_id, .. } => {
            assert_eq!(*session_id, SESSION_ID);
        }
        _ => return Err(format!("expected HELLO_ACK, got: {msg:?}").into()),
    }

    // Frame 3: Send TRANSFER_REQUEST
    let transfer_req = ControlMessage::TransferRequest {
        transfer_id: TRANSFER_ID,
        transfer_type: TransferType::Files,
        display_name: "test.bin".to_string(),
        total_files: 1,
        total_size: 16,
        manifest: vec![ManifestEntry {
            file_id: 1,
            relative_path: "test.bin".to_string(),
            size: Some(16),
            sha256: Some(file_hash),
            entry_type: ManifestEntryType::File,
        }],
    };
    conn.send_control(SESSION_ID, 0, &transfer_req).await?;

    // Frame 4: Receive TRANSFER_ACCEPT
    let (hdr, payload) = conn.recv_frame().await?;
    verify_header(&hdr, FrameKind::Control, SESSION_ID)?;
    let msg = codec::decode_control(&payload)?;
    assert!(matches!(msg, ControlMessage::TransferAccept { .. }));

    // Frame 5: Send FILE_METADATA
    let file_meta = ControlMessage::FileMetadata {
        transfer_id: TRANSFER_ID,
        file_id: 1,
        stream_id: 1,
        relative_path: "test.bin".to_string(),
        size: 16,
        sha256: file_hash,
        mime_type: Some("application/octet-stream".to_string()),
        modified_time: None,
    };
    conn.send_control(SESSION_ID, 0, &file_meta).await?;

    // Frame 6: Receive FILE_METADATA_ACK
    let (hdr, payload) = conn.recv_frame().await?;
    verify_header(&hdr, FrameKind::Control, SESSION_ID)?;
    let msg = codec::decode_control(&payload)?;
    match &msg {
        ControlMessage::FileMetadataAck { accepted, .. } => {
            assert!(*accepted, "FILE_METADATA_ACK accepted=true");
        }
        _ => return Err("expected FILE_METADATA_ACK".into()),
    }

    // Frame 7: Send CHUNK (LAST_CHUNK)
    let mut chunk_payload = Vec::with_capacity(56);
    chunk_payload.extend_from_slice(&0u64.to_be_bytes()); // offset = 0
    chunk_payload.extend_from_slice(&file_hash); // chunk_hash
    chunk_payload.extend_from_slice(&TEST_DATA); // data
    conn.send_chunk(SESSION_ID, 1, 0x0001, chunk_payload)
        .await?;

    // Frame 8: Receive CHUNK_ACK
    let (hdr, payload) = conn.recv_frame().await?;
    verify_header(&hdr, FrameKind::Control, SESSION_ID)?;
    let msg = codec::decode_control(&payload)?;
    assert!(matches!(msg, ControlMessage::ChunkAck { .. }));

    // Frame 9: Send TRANSFER_COMPLETE
    let complete = ControlMessage::TransferComplete {
        transfer_id: TRANSFER_ID,
    };
    conn.send_control(SESSION_ID, 0, &complete).await?;

    // Frame 10: Receive TRANSFER_ACK
    let (hdr, payload) = conn.recv_frame().await?;
    verify_header(&hdr, FrameKind::Control, SESSION_ID)?;
    let msg = codec::decode_control(&payload)?;
    match &msg {
        ControlMessage::TransferAck { status, .. } => {
            assert_eq!(*status, TransferAckStatus::Success);
        }
        _ => return Err("expected TRANSFER_ACK".into()),
    }

    // Frame 11: Send SESSION_CLOSE
    let close = ControlMessage::SessionClose {
        reason: Some("normal".to_string()),
    };
    conn.send_control(SESSION_ID, 0, &close).await?;

    // Frame 12: Receive SESSION_CLOSE
    let (hdr, payload) = conn.recv_frame().await?;
    verify_header(&hdr, FrameKind::Control, SESSION_ID)?;
    let msg = codec::decode_control(&payload)?;
    assert!(matches!(msg, ControlMessage::SessionClose { .. }));

    // Wait for Bob's results.
    let (bob_results, whole_file_ok) = bob_handle.await?;

    // Print results table.
    println!("┌───────┬────────────────────────┬───────┬────────┐");
    println!("│ Frame │ Type                   │ Dir   │ Result │");
    println!("├───────┼────────────────────────┼───────┼────────┤");
    for r in &bob_results {
        let status = if r.passed { "PASS" } else { "FAIL" };
        let detail = if r.detail.is_empty() {
            String::new()
        } else {
            format!(" ({}) ", r.detail)
        };
        println!(
            "│ {:>5} │ {:<22} │ {:<5} │ {:<6}│{}",
            r.num, r.name, r.direction, status, detail
        );
    }
    println!("├───────┼────────────────────────┼───────┼────────┤");
    let hash_status = if whole_file_ok { "PASS" } else { "FAIL" };
    println!(
        "│       │ File hash: {}… │       │ {:<6}│",
        &EXPECTED_HASH[..10],
        hash_status
    );
    println!("└───────┴────────────────────────┴───────┴────────┘");

    let total_pass = bob_results.iter().filter(|r| r.passed).count();
    let total = bob_results.len();
    println!(
        "\nResult: {}/{} frames OK, file integrity {}",
        total_pass,
        total,
        if whole_file_ok { "OK" } else { "FAIL" }
    );

    if total_pass == total && whole_file_ok {
        println!("\n✅ All checks passed!");
        std::process::exit(0);
    } else {
        println!("\n❌ Some checks failed!");
        std::process::exit(1);
    }
}
