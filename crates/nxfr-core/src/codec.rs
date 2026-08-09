//! CBOR codec for NXFR control messages per §8.
//!
//! Encoding: builds `BTreeMap<String, ciborium::Value>` for lexicographic key order.
//! Decoding: deserializes to `ciborium::Value`, validates spec rules, extracts fields.

use std::collections::BTreeMap;
use std::io::Cursor;

use ciborium::Value;

use nxfr_common::error::CodecError;
use nxfr_common::limits;
use nxfr_common::types::{DeviceId, Platform, ProtocolVersion, TransferId};

use crate::error_code::ErrorCode;
use crate::messages::*;

// ── Helpers ──────────────────────────────────────────────────────────────

type CborMap = BTreeMap<String, Value>;

fn to_cbor_map(val: Value) -> Result<CborMap, CodecError> {
    match val {
        Value::Map(entries) => {
            let mut map = BTreeMap::new();
            for (k, v) in entries {
                let key = match k {
                    Value::Text(s) => s,
                    _ => return Err(CodecError::NonStringKey),
                };
                map.insert(key, v);
            }
            Ok(map)
        }
        _ => Err(CodecError::NotAMap),
    }
}

fn get_required<'a>(map: &'a CborMap, key: &str) -> Result<&'a Value, CodecError> {
    map.get(key)
        .ok_or_else(|| CodecError::MissingField(key.to_string()))
}

fn get_uint(map: &CborMap, key: &str) -> Result<u64, CodecError> {
    match get_required(map, key)? {
        Value::Integer(i) => {
            let v: i128 = (*i).into();
            if v < 0 {
                return Err(CodecError::WrongType {
                    field: key.to_string(),
                    expected: "unsigned integer",
                });
            }
            Ok(v as u64)
        }
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "unsigned integer",
        }),
    }
}

fn get_uint_opt(map: &CborMap, key: &str) -> Result<Option<u64>, CodecError> {
    match map.get(key) {
        None => Ok(None),
        Some(Value::Integer(i)) => {
            let v: i128 = (*i).into();
            if v < 0 {
                return Err(CodecError::WrongType {
                    field: key.to_string(),
                    expected: "unsigned integer",
                });
            }
            Ok(Some(v as u64))
        }
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "unsigned integer",
        }),
    }
}

fn get_text(map: &CborMap, key: &str) -> Result<String, CodecError> {
    match get_required(map, key)? {
        Value::Text(s) => Ok(s.clone()),
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "text string",
        }),
    }
}

fn get_text_opt(map: &CborMap, key: &str) -> Result<Option<String>, CodecError> {
    match map.get(key) {
        None => Ok(None),
        Some(Value::Text(s)) => Ok(Some(s.clone())),
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "text string",
        }),
    }
}

fn get_bool(map: &CborMap, key: &str) -> Result<bool, CodecError> {
    match get_required(map, key)? {
        Value::Bool(b) => Ok(*b),
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "boolean",
        }),
    }
}

#[allow(dead_code)]
fn get_bool_opt(map: &CborMap, key: &str) -> Result<Option<bool>, CodecError> {
    match map.get(key) {
        None => Ok(None),
        Some(Value::Bool(b)) => Ok(Some(*b)),
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "boolean",
        }),
    }
}

fn get_bytes(map: &CborMap, key: &str, expected_len: usize) -> Result<Vec<u8>, CodecError> {
    match get_required(map, key)? {
        Value::Bytes(b) => {
            if b.len() != expected_len {
                return Err(CodecError::WrongType {
                    field: key.to_string(),
                    expected: "bytes of correct length",
                });
            }
            Ok(b.clone())
        }
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "byte string",
        }),
    }
}

fn get_bytes_opt(
    map: &CborMap,
    key: &str,
    expected_len: usize,
) -> Result<Option<Vec<u8>>, CodecError> {
    match map.get(key) {
        None => Ok(None),
        Some(Value::Bytes(b)) => {
            if b.len() != expected_len {
                return Err(CodecError::WrongType {
                    field: key.to_string(),
                    expected: "bytes of correct length",
                });
            }
            Ok(Some(b.clone()))
        }
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "byte string",
        }),
    }
}

fn get_str_array(map: &CborMap, key: &str) -> Result<Vec<String>, CodecError> {
    match map.get(key) {
        None => Ok(vec![]),
        Some(Value::Array(arr)) => {
            let mut result = Vec::with_capacity(arr.len());
            for v in arr {
                match v {
                    Value::Text(s) => result.push(s.clone()),
                    _ => {
                        return Err(CodecError::WrongType {
                            field: key.to_string(),
                            expected: "array of text strings",
                        })
                    }
                }
            }
            Ok(result)
        }
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "array",
        }),
    }
}

fn get_uint_array_opt(map: &CborMap, key: &str) -> Result<Option<Vec<u32>>, CodecError> {
    match map.get(key) {
        None => Ok(None),
        Some(Value::Array(arr)) => {
            let mut result = Vec::with_capacity(arr.len());
            for v in arr {
                match v {
                    Value::Integer(i) => {
                        let val: i128 = (*i).into();
                        result.push(val as u32);
                    }
                    _ => {
                        return Err(CodecError::WrongType {
                            field: key.to_string(),
                            expected: "array of unsigned integers",
                        })
                    }
                }
            }
            Ok(Some(result))
        }
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "array",
        }),
    }
}

fn bytes_to_device_id(b: &[u8]) -> DeviceId {
    let mut arr = [0u8; 32];
    arr.copy_from_slice(b);
    DeviceId(arr)
}

fn bytes_to_transfer_id(b: &[u8]) -> TransferId {
    let mut arr = [0u8; 16];
    arr.copy_from_slice(b);
    TransferId(arr)
}

fn bytes_to_hash(b: &[u8]) -> [u8; 32] {
    let mut arr = [0u8; 32];
    arr.copy_from_slice(b);
    arr
}

fn uint_to_value(v: u64) -> Value {
    Value::Integer(v.into())
}

fn make_version_array(pv: &ProtocolVersion) -> Value {
    Value::Array(vec![
        Value::Integer(pv.major.into()),
        Value::Integer(pv.minor.into()),
    ])
}

fn parse_version_array(map: &CborMap) -> Result<ProtocolVersion, CodecError> {
    let key = "protocol_version";
    match get_required(map, key)? {
        Value::Array(arr) if arr.len() == 2 => {
            let major = match &arr[0] {
                Value::Integer(i) => {
                    let v: i128 = (*i).into();
                    v as u32
                }
                _ => {
                    return Err(CodecError::WrongType {
                        field: key.to_string(),
                        expected: "[uint, uint]",
                    })
                }
            };
            let minor = match &arr[1] {
                Value::Integer(i) => {
                    let v: i128 = (*i).into();
                    v as u32
                }
                _ => {
                    return Err(CodecError::WrongType {
                        field: key.to_string(),
                        expected: "[uint, uint]",
                    })
                }
            };
            Ok(ProtocolVersion { major, minor })
        }
        _ => Err(CodecError::WrongType {
            field: key.to_string(),
            expected: "[uint, uint]",
        }),
    }
}

// ── Nesting depth check ──────────────────────────────────────────────────

fn check_nesting_depth(val: &Value, depth: usize) -> Result<(), CodecError> {
    if depth > limits::MAX_CBOR_NESTING {
        return Err(CodecError::NestingTooDeep(depth));
    }
    match val {
        Value::Map(entries) => {
            for (k, v) in entries {
                check_nesting_depth(k, depth + 1)?;
                check_nesting_depth(v, depth + 1)?;
            }
        }
        Value::Array(items) => {
            for item in items {
                check_nesting_depth(item, depth + 1)?;
            }
        }
        Value::Tag(_, _inner) => {
            return Err(CodecError::TagNotAllowed);
        }
        _ => {}
    }
    Ok(())
}

// ── Decode ───────────────────────────────────────────────────────────────

/// Decode a CBOR control message payload into a `ControlMessage`.
///
/// Validates per §8:
/// - Top-level must be a map
/// - String keys only
/// - No tags
/// - Nesting depth ≤ 4
/// - Unknown keys are ignored
pub fn decode_control(payload: &[u8]) -> Result<ControlMessage, CodecError> {
    let val: Value = ciborium::de::from_reader(Cursor::new(payload))
        .map_err(|e| CodecError::CborDecode(e.to_string()))?;

    // Validate nesting depth and tags
    check_nesting_depth(&val, 0)?;

    let map = to_cbor_map(val)?;
    let type_code = get_uint(&map, "type")?;

    match type_code {
        0x01 => decode_hello(&map),
        0x02 => decode_hello_ack(&map),
        0x03 => decode_pair_request(&map),
        0x04 => Ok(ControlMessage::PairAccept),
        0x05 => {
            let reason = get_text_opt(&map, "reason")?;
            Ok(ControlMessage::PairReject { reason })
        }
        0x06 => {
            let reason = get_text_opt(&map, "reason")?;
            Ok(ControlMessage::SessionClose { reason })
        }
        0x09 => decode_error(&map),
        0x10 => decode_transfer_request(&map),
        0x11 => {
            let transfer_id = bytes_to_transfer_id(&get_bytes(&map, "transfer_id", 16)?);
            Ok(ControlMessage::TransferAccept { transfer_id })
        }
        0x12 => {
            let transfer_id = bytes_to_transfer_id(&get_bytes(&map, "transfer_id", 16)?);
            let reason = get_text_opt(&map, "reason")?;
            Ok(ControlMessage::TransferReject {
                transfer_id,
                reason,
            })
        }
        0x13 => decode_file_metadata(&map),
        0x14 => decode_file_metadata_ack(&map),
        0x15 => decode_chunk_ack(&map),
        0x16 => {
            let transfer_id = bytes_to_transfer_id(&get_bytes(&map, "transfer_id", 16)?);
            Ok(ControlMessage::TransferPause { transfer_id })
        }
        0x17 => {
            let transfer_id = bytes_to_transfer_id(&get_bytes(&map, "transfer_id", 16)?);
            Ok(ControlMessage::TransferResume { transfer_id })
        }
        0x18 => {
            let transfer_id = bytes_to_transfer_id(&get_bytes(&map, "transfer_id", 16)?);
            let reason = get_text_opt(&map, "reason")?;
            Ok(ControlMessage::TransferCancel {
                transfer_id,
                reason,
            })
        }
        0x19 => {
            let transfer_id = bytes_to_transfer_id(&get_bytes(&map, "transfer_id", 16)?);
            Ok(ControlMessage::TransferComplete { transfer_id })
        }
        0x1A => decode_transfer_ack(&map),
        0x20 => decode_resume_query(&map),
        0x21 => decode_resume_status(&map),
        other => Err(CodecError::UnknownMessageType(other)),
    }
}

fn decode_hello(map: &CborMap) -> Result<ControlMessage, CodecError> {
    Ok(ControlMessage::Hello {
        protocol_version: parse_version_array(map)?,
        device_id: bytes_to_device_id(&get_bytes(map, "device_id", 32)?),
        device_name: get_text(map, "device_name")?,
        platform: Platform::from_str_lossy(&get_text(map, "platform")?),
        capabilities: get_str_array(map, "capabilities")?,
        is_paired: get_bool(map, "is_paired")?,
    })
}

fn decode_hello_ack(map: &CborMap) -> Result<ControlMessage, CodecError> {
    Ok(ControlMessage::HelloAck {
        protocol_version: parse_version_array(map)?,
        device_id: bytes_to_device_id(&get_bytes(map, "device_id", 32)?),
        device_name: get_text(map, "device_name")?,
        platform: Platform::from_str_lossy(&get_text(map, "platform")?),
        capabilities: get_str_array(map, "capabilities")?,
        is_paired: get_bool(map, "is_paired")?,
        session_id: get_uint(map, "session_id")? as u32,
    })
}

fn decode_pair_request(map: &CborMap) -> Result<ControlMessage, CodecError> {
    Ok(ControlMessage::PairRequest {
        sas_method: get_text(map, "sas_method")?,
    })
}

fn decode_error(map: &CborMap) -> Result<ControlMessage, CodecError> {
    let code_str = get_text(map, "code")?;
    let code = ErrorCode::from_wire_str(&code_str).ok_or_else(|| CodecError::WrongType {
        field: "code".to_string(),
        expected: "known error code string",
    })?;
    Ok(ControlMessage::Error {
        code,
        message: get_text_opt(map, "message")?,
        fatal: get_bool(map, "fatal")?,
        details: None, // details map is optional and rarely used
    })
}

fn decode_transfer_request(map: &CborMap) -> Result<ControlMessage, CodecError> {
    let transfer_id = bytes_to_transfer_id(&get_bytes(map, "transfer_id", 16)?);
    let tt_str = get_text(map, "transfer_type")?;
    let transfer_type = match tt_str.as_str() {
        "files" => TransferType::Files,
        "directory" => TransferType::Directory,
        _ => {
            return Err(CodecError::WrongType {
                field: "transfer_type".to_string(),
                expected: "\"files\" or \"directory\"",
            })
        }
    };
    let display_name = get_text(map, "display_name")?;
    let total_files = get_uint(map, "total_files")? as u32;
    let total_size = get_uint(map, "total_size")?;

    let manifest_val = get_required(map, "manifest")?;
    let manifest_arr = match manifest_val {
        Value::Array(arr) => arr,
        _ => {
            return Err(CodecError::WrongType {
                field: "manifest".to_string(),
                expected: "array",
            })
        }
    };

    if manifest_arr.len() > limits::MAX_MANIFEST_ENTRIES {
        return Err(CodecError::ManifestTooLarge(manifest_arr.len()));
    }

    let mut manifest = Vec::with_capacity(manifest_arr.len());
    for entry_val in manifest_arr {
        let entry_map = match entry_val {
            Value::Map(entries) => {
                let mut m = BTreeMap::new();
                for (k, v) in entries {
                    let key = match k {
                        Value::Text(s) => s.clone(),
                        _ => return Err(CodecError::NonStringKey),
                    };
                    m.insert(key, v.clone());
                }
                m
            }
            _ => {
                return Err(CodecError::WrongType {
                    field: "manifest[]".to_string(),
                    expected: "map",
                })
            }
        };

        let file_id = get_uint(&entry_map, "file_id")? as u32;
        let relative_path = get_text(&entry_map, "relative_path")?;
        let entry_type_str = get_text_opt(&entry_map, "type")?;
        let entry_type = match entry_type_str.as_deref() {
            Some("dir") => ManifestEntryType::Dir,
            Some("file") | None => ManifestEntryType::File,
            Some(_other) => {
                return Err(CodecError::WrongType {
                    field: "manifest[].type".to_string(),
                    expected: "\"file\" or \"dir\"",
                })
            }
        };

        let (size, sha256) = match entry_type {
            ManifestEntryType::File => {
                let size = Some(get_uint(&entry_map, "size")?);
                let sha256 = Some(bytes_to_hash(&get_bytes(&entry_map, "sha256", 32)?));
                (size, sha256)
            }
            ManifestEntryType::Dir => {
                let size = get_uint_opt(&entry_map, "size")?;
                let sha256 = get_bytes_opt(&entry_map, "sha256", 32)?.map(|b| bytes_to_hash(&b));
                (size, sha256)
            }
        };

        manifest.push(ManifestEntry {
            file_id,
            relative_path,
            size,
            sha256,
            entry_type,
        });
    }

    Ok(ControlMessage::TransferRequest {
        transfer_id,
        transfer_type,
        display_name,
        total_files,
        total_size,
        manifest,
    })
}

fn decode_file_metadata(map: &CborMap) -> Result<ControlMessage, CodecError> {
    Ok(ControlMessage::FileMetadata {
        transfer_id: bytes_to_transfer_id(&get_bytes(map, "transfer_id", 16)?),
        file_id: get_uint(map, "file_id")? as u32,
        stream_id: get_uint(map, "stream_id")? as u32,
        relative_path: get_text(map, "relative_path")?,
        size: get_uint(map, "size")?,
        sha256: bytes_to_hash(&get_bytes(map, "sha256", 32)?),
        mime_type: get_text_opt(map, "mime_type")?,
        modified_time: get_uint_opt(map, "modified_time")?,
    })
}

fn decode_file_metadata_ack(map: &CborMap) -> Result<ControlMessage, CodecError> {
    Ok(ControlMessage::FileMetadataAck {
        transfer_id: bytes_to_transfer_id(&get_bytes(map, "transfer_id", 16)?),
        file_id: get_uint(map, "file_id")? as u32,
        stream_id: get_uint(map, "stream_id")? as u32,
        accepted: get_bool(map, "accepted")?,
    })
}

fn decode_chunk_ack(map: &CborMap) -> Result<ControlMessage, CodecError> {
    Ok(ControlMessage::ChunkAck {
        stream_id: get_uint(map, "stream_id")? as u32,
        message_id: get_uint(map, "message_id")?,
        offset: get_uint(map, "offset")?,
        length: get_uint(map, "length")?,
    })
}

fn decode_transfer_ack(map: &CborMap) -> Result<ControlMessage, CodecError> {
    let transfer_id = bytes_to_transfer_id(&get_bytes(map, "transfer_id", 16)?);
    let status_str = get_text(map, "status")?;
    let status = match status_str.as_str() {
        "success" => TransferAckStatus::Success,
        "partial_failure" => TransferAckStatus::PartialFailure,
        _ => {
            return Err(CodecError::WrongType {
                field: "status".to_string(),
                expected: "\"success\" or \"partial_failure\"",
            })
        }
    };
    let failed_files = get_uint_array_opt(map, "failed_files")?;
    Ok(ControlMessage::TransferAck {
        transfer_id,
        status,
        failed_files,
    })
}

fn decode_resume_query(map: &CborMap) -> Result<ControlMessage, CodecError> {
    let transfer_id = bytes_to_transfer_id(&get_bytes(map, "transfer_id", 16)?);
    let file_ids = get_uint_array_opt(map, "file_ids")?;
    Ok(ControlMessage::ResumeQuery {
        transfer_id,
        file_ids,
    })
}

fn decode_resume_status(map: &CborMap) -> Result<ControlMessage, CodecError> {
    let transfer_id = bytes_to_transfer_id(&get_bytes(map, "transfer_id", 16)?);
    let resumable = get_bool(map, "resumable")?;
    let expiry = get_uint_opt(map, "expiry")?;

    let files = if resumable {
        match map.get("files") {
            Some(Value::Array(arr)) => {
                let mut result = Vec::with_capacity(arr.len());
                for fv in arr {
                    let fm = match fv {
                        Value::Map(entries) => {
                            let mut m = BTreeMap::new();
                            for (k, v) in entries {
                                let key = match k {
                                    Value::Text(s) => s.clone(),
                                    _ => return Err(CodecError::NonStringKey),
                                };
                                m.insert(key, v.clone());
                            }
                            m
                        }
                        _ => {
                            return Err(CodecError::WrongType {
                                field: "files[]".to_string(),
                                expected: "map",
                            })
                        }
                    };
                    let file_id = get_uint(&fm, "file_id")? as u32;
                    let received_bytes = get_uint(&fm, "received_bytes")?;

                    // Parse received_ranges: [[offset, length], ...]
                    let ranges_val = get_required(&fm, "received_ranges")?;
                    let ranges = match ranges_val {
                        Value::Array(range_arr) => {
                            let mut r = Vec::new();
                            for rv in range_arr {
                                match rv {
                                    Value::Array(pair) if pair.len() == 2 => {
                                        let offset = match &pair[0] {
                                            Value::Integer(i) => {
                                                let v: i128 = (*i).into();
                                                v as u64
                                            }
                                            _ => {
                                                return Err(CodecError::WrongType {
                                                    field: "received_ranges".to_string(),
                                                    expected: "[uint, uint]",
                                                })
                                            }
                                        };
                                        let length = match &pair[1] {
                                            Value::Integer(i) => {
                                                let v: i128 = (*i).into();
                                                v as u64
                                            }
                                            _ => {
                                                return Err(CodecError::WrongType {
                                                    field: "received_ranges".to_string(),
                                                    expected: "[uint, uint]",
                                                })
                                            }
                                        };
                                        r.push((offset, length));
                                    }
                                    _ => {
                                        return Err(CodecError::WrongType {
                                            field: "received_ranges".to_string(),
                                            expected: "array of [uint, uint]",
                                        })
                                    }
                                }
                            }
                            r
                        }
                        _ => {
                            return Err(CodecError::WrongType {
                                field: "received_ranges".to_string(),
                                expected: "array",
                            })
                        }
                    };

                    let partial_sha256 =
                        get_bytes_opt(&fm, "partial_sha256", 32)?.map(|b| bytes_to_hash(&b));

                    result.push(ResumeFileStatus {
                        file_id,
                        received_bytes,
                        received_ranges: ranges,
                        partial_sha256,
                    });
                }
                Some(result)
            }
            _ => None,
        }
    } else {
        None
    };

    Ok(ControlMessage::ResumeStatus {
        transfer_id,
        resumable,
        files,
        expiry,
    })
}

// ── Encode ───────────────────────────────────────────────────────────────

/// Encode a `ControlMessage` to CBOR bytes.
///
/// Keys are sorted lexicographically (BTreeMap natural order).
pub fn encode_control(msg: &ControlMessage) -> Result<Vec<u8>, CodecError> {
    let map = encode_to_map(msg)?;
    let value = map_to_value(map);
    let mut buf = Vec::new();
    ciborium::ser::into_writer(&value, &mut buf)
        .map_err(|e| CodecError::CborEncode(e.to_string()))?;

    if buf.len() > limits::CONTROL_PAYLOAD_MAX as usize {
        return Err(CodecError::EncodedTooLarge);
    }

    Ok(buf)
}

fn map_to_value(map: CborMap) -> Value {
    // BTreeMap iteration is in sorted order, which gives us lexicographic key ordering.
    Value::Map(map.into_iter().map(|(k, v)| (Value::Text(k), v)).collect())
}

fn encode_to_map(msg: &ControlMessage) -> Result<CborMap, CodecError> {
    let mut map = BTreeMap::new();

    match msg {
        ControlMessage::Hello {
            protocol_version,
            device_id,
            device_name,
            platform,
            capabilities,
            is_paired,
        } => {
            map.insert(
                "capabilities".into(),
                Value::Array(
                    capabilities
                        .iter()
                        .map(|s| Value::Text(s.clone()))
                        .collect(),
                ),
            );
            map.insert("device_id".into(), Value::Bytes(device_id.0.to_vec()));
            map.insert("device_name".into(), Value::Text(device_name.clone()));
            map.insert("is_paired".into(), Value::Bool(*is_paired));
            map.insert(
                "platform".into(),
                Value::Text(platform.as_str().to_string()),
            );
            map.insert(
                "protocol_version".into(),
                make_version_array(protocol_version),
            );
            map.insert("type".into(), uint_to_value(0x01));
        }

        ControlMessage::HelloAck {
            protocol_version,
            device_id,
            device_name,
            platform,
            capabilities,
            is_paired,
            session_id,
        } => {
            map.insert(
                "capabilities".into(),
                Value::Array(
                    capabilities
                        .iter()
                        .map(|s| Value::Text(s.clone()))
                        .collect(),
                ),
            );
            map.insert("device_id".into(), Value::Bytes(device_id.0.to_vec()));
            map.insert("device_name".into(), Value::Text(device_name.clone()));
            map.insert("is_paired".into(), Value::Bool(*is_paired));
            map.insert(
                "platform".into(),
                Value::Text(platform.as_str().to_string()),
            );
            map.insert(
                "protocol_version".into(),
                make_version_array(protocol_version),
            );
            map.insert("session_id".into(), uint_to_value(*session_id as u64));
            map.insert("type".into(), uint_to_value(0x02));
        }

        ControlMessage::PairRequest { sas_method } => {
            map.insert("sas_method".into(), Value::Text(sas_method.clone()));
            map.insert("type".into(), uint_to_value(0x03));
        }

        ControlMessage::PairAccept => {
            map.insert("type".into(), uint_to_value(0x04));
        }

        ControlMessage::PairReject { reason } => {
            if let Some(r) = reason {
                map.insert("reason".into(), Value::Text(r.clone()));
            }
            map.insert("type".into(), uint_to_value(0x05));
        }

        ControlMessage::SessionClose { reason } => {
            if let Some(r) = reason {
                map.insert("reason".into(), Value::Text(r.clone()));
            }
            map.insert("type".into(), uint_to_value(0x06));
        }

        ControlMessage::Error {
            code,
            message,
            fatal,
            details: _,
        } => {
            map.insert("code".into(), Value::Text(code.as_str().to_string()));
            map.insert("fatal".into(), Value::Bool(*fatal));
            if let Some(m) = message {
                map.insert("message".into(), Value::Text(m.clone()));
            }
            map.insert("type".into(), uint_to_value(0x09));
        }

        ControlMessage::TransferRequest {
            transfer_id,
            transfer_type,
            display_name,
            total_files,
            total_size,
            manifest,
        } => {
            if manifest.len() > limits::MAX_MANIFEST_ENTRIES {
                return Err(CodecError::ManifestTooLarge(manifest.len()));
            }
            map.insert("display_name".into(), Value::Text(display_name.clone()));
            let manifest_val = Value::Array(
                manifest
                    .iter()
                    .map(|e| {
                        let mut em = BTreeMap::new();
                        em.insert("file_id".to_string(), uint_to_value(e.file_id as u64));
                        em.insert(
                            "relative_path".to_string(),
                            Value::Text(e.relative_path.clone()),
                        );
                        if let Some(sha) = &e.sha256 {
                            em.insert("sha256".to_string(), Value::Bytes(sha.to_vec()));
                        }
                        if let Some(s) = e.size {
                            em.insert("size".to_string(), uint_to_value(s));
                        }
                        match e.entry_type {
                            ManifestEntryType::File => {
                                // "type": "file" is optional (default), but we include
                                // it for explicitness when the manifest has mixed types
                                if manifest
                                    .iter()
                                    .any(|x| x.entry_type == ManifestEntryType::Dir)
                                {
                                    em.insert("type".to_string(), Value::Text("file".into()));
                                }
                            }
                            ManifestEntryType::Dir => {
                                em.insert("type".to_string(), Value::Text("dir".into()));
                            }
                        }
                        map_to_value(em)
                    })
                    .collect(),
            );
            map.insert("manifest".into(), manifest_val);
            map.insert("total_files".into(), uint_to_value(*total_files as u64));
            map.insert("total_size".into(), uint_to_value(*total_size));
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert(
                "transfer_type".into(),
                Value::Text(match transfer_type {
                    TransferType::Files => "files".into(),
                    TransferType::Directory => "directory".into(),
                }),
            );
            map.insert("type".into(), uint_to_value(0x10));
        }

        ControlMessage::TransferAccept { transfer_id } => {
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x11));
        }

        ControlMessage::TransferReject {
            transfer_id,
            reason,
        } => {
            if let Some(r) = reason {
                map.insert("reason".into(), Value::Text(r.clone()));
            }
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x12));
        }

        ControlMessage::FileMetadata {
            transfer_id,
            file_id,
            stream_id,
            relative_path,
            size,
            sha256,
            mime_type,
            modified_time,
        } => {
            map.insert("file_id".into(), uint_to_value(*file_id as u64));
            if let Some(mt) = mime_type {
                map.insert("mime_type".into(), Value::Text(mt.clone()));
            }
            if let Some(t) = modified_time {
                map.insert("modified_time".into(), uint_to_value(*t));
            }
            map.insert("relative_path".into(), Value::Text(relative_path.clone()));
            map.insert("sha256".into(), Value::Bytes(sha256.to_vec()));
            map.insert("size".into(), uint_to_value(*size));
            map.insert("stream_id".into(), uint_to_value(*stream_id as u64));
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x13));
        }

        ControlMessage::FileMetadataAck {
            transfer_id,
            file_id,
            stream_id,
            accepted,
        } => {
            map.insert("accepted".into(), Value::Bool(*accepted));
            map.insert("file_id".into(), uint_to_value(*file_id as u64));
            map.insert("stream_id".into(), uint_to_value(*stream_id as u64));
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x14));
        }

        ControlMessage::ChunkAck {
            stream_id,
            message_id,
            offset,
            length,
        } => {
            map.insert("length".into(), uint_to_value(*length));
            map.insert("message_id".into(), uint_to_value(*message_id));
            map.insert("offset".into(), uint_to_value(*offset));
            map.insert("stream_id".into(), uint_to_value(*stream_id as u64));
            map.insert("type".into(), uint_to_value(0x15));
        }

        ControlMessage::TransferPause { transfer_id } => {
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x16));
        }

        ControlMessage::TransferResume { transfer_id } => {
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x17));
        }

        ControlMessage::TransferCancel {
            transfer_id,
            reason,
        } => {
            if let Some(r) = reason {
                map.insert("reason".into(), Value::Text(r.clone()));
            }
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x18));
        }

        ControlMessage::TransferComplete { transfer_id } => {
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x19));
        }

        ControlMessage::TransferAck {
            transfer_id,
            status,
            failed_files,
        } => {
            if let Some(ff) = failed_files {
                map.insert(
                    "failed_files".into(),
                    Value::Array(ff.iter().map(|id| uint_to_value(*id as u64)).collect()),
                );
            }
            map.insert(
                "status".into(),
                Value::Text(match status {
                    TransferAckStatus::Success => "success".into(),
                    TransferAckStatus::PartialFailure => "partial_failure".into(),
                }),
            );
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x1A));
        }

        ControlMessage::ResumeQuery {
            transfer_id,
            file_ids,
        } => {
            if let Some(ids) = file_ids {
                map.insert(
                    "file_ids".into(),
                    Value::Array(ids.iter().map(|id| uint_to_value(*id as u64)).collect()),
                );
            }
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x20));
        }

        ControlMessage::ResumeStatus {
            transfer_id,
            resumable,
            files,
            expiry,
        } => {
            if let Some(exp) = expiry {
                map.insert("expiry".into(), uint_to_value(*exp));
            }
            if let Some(file_list) = files {
                let files_val = Value::Array(
                    file_list
                        .iter()
                        .map(|f| {
                            let mut fm = BTreeMap::new();
                            fm.insert("file_id".to_string(), uint_to_value(f.file_id as u64));
                            if let Some(ps) = &f.partial_sha256 {
                                fm.insert("partial_sha256".to_string(), Value::Bytes(ps.to_vec()));
                            }
                            fm.insert(
                                "received_bytes".to_string(),
                                uint_to_value(f.received_bytes),
                            );
                            fm.insert(
                                "received_ranges".to_string(),
                                Value::Array(
                                    f.received_ranges
                                        .iter()
                                        .map(|(o, l)| {
                                            Value::Array(vec![uint_to_value(*o), uint_to_value(*l)])
                                        })
                                        .collect(),
                                ),
                            );
                            map_to_value(fm)
                        })
                        .collect(),
                );
                map.insert("files".into(), files_val);
            }
            map.insert("resumable".into(), Value::Bool(*resumable));
            map.insert("transfer_id".into(), Value::Bytes(transfer_id.0.to_vec()));
            map.insert("type".into(), uint_to_value(0x21));
        }
    }

    Ok(map)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_hello() {
        let msg = ControlMessage::Hello {
            protocol_version: ProtocolVersion::V0_1,
            device_id: DeviceId([0xAA; 32]),
            device_name: "Test".to_string(),
            platform: Platform::Linux,
            capabilities: vec![],
            is_paired: false,
        };
        let encoded = encode_control(&msg).unwrap();
        let decoded = decode_control(&encoded).unwrap();
        assert_eq!(msg, decoded);
    }

    #[test]
    fn round_trip_transfer_request() {
        let msg = ControlMessage::TransferRequest {
            transfer_id: TransferId([0xAA; 16]),
            transfer_type: TransferType::Files,
            display_name: "test.bin".to_string(),
            total_files: 1,
            total_size: 16,
            manifest: vec![ManifestEntry {
                file_id: 1,
                relative_path: "test.bin".to_string(),
                size: Some(16),
                sha256: Some([0xBB; 32]),
                entry_type: ManifestEntryType::File,
            }],
        };
        let encoded = encode_control(&msg).unwrap();
        let decoded = decode_control(&encoded).unwrap();
        assert_eq!(msg, decoded);
    }

    #[test]
    fn round_trip_error() {
        let msg = ControlMessage::Error {
            code: ErrorCode::ChecksumMismatch,
            message: Some("Hash verification failed".to_string()),
            fatal: false,
            details: None,
        };
        let encoded = encode_control(&msg).unwrap();
        let decoded = decode_control(&encoded).unwrap();
        assert_eq!(msg, decoded);
    }

    #[test]
    fn unknown_type_rejected() {
        // Encode a map with type=255
        let map = vec![(Value::Text("type".into()), Value::Integer(255.into()))];
        let mut buf = Vec::new();
        ciborium::ser::into_writer(&Value::Map(map), &mut buf).unwrap();
        let err = decode_control(&buf).unwrap_err();
        assert!(matches!(err, CodecError::UnknownMessageType(255)));
    }

    #[test]
    fn non_string_key_rejected() {
        // Encode a map with integer key
        let map = vec![(Value::Integer(1.into()), Value::Integer(1.into()))];
        let mut buf = Vec::new();
        ciborium::ser::into_writer(&Value::Map(map), &mut buf).unwrap();
        let err = decode_control(&buf).unwrap_err();
        assert!(matches!(err, CodecError::NonStringKey));
    }

    #[test]
    fn not_a_map_rejected() {
        // Encode an array instead of a map
        let mut buf = Vec::new();
        ciborium::ser::into_writer(&Value::Array(vec![]), &mut buf).unwrap();
        let err = decode_control(&buf).unwrap_err();
        assert!(matches!(err, CodecError::NotAMap));
    }

    #[test]
    fn unknown_keys_ignored() {
        let msg = ControlMessage::PairAccept;
        let _encoded = encode_control(&msg).unwrap();

        // Manually add an unknown key by encoding with extra field
        let mut map = BTreeMap::new();
        map.insert("type".to_string(), uint_to_value(0x04));
        map.insert(
            "unknown_future_field".to_string(),
            Value::Text("ignored".into()),
        );
        let value = map_to_value(map);
        let mut buf = Vec::new();
        ciborium::ser::into_writer(&value, &mut buf).unwrap();

        let decoded = decode_control(&buf).unwrap();
        assert_eq!(decoded, ControlMessage::PairAccept);
    }
}
