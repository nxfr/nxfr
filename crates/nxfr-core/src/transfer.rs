//! Transfer state machine per PROTOCOL §11.
//!
//! Pure function: `handle_event(state, event) -> (new_state, Vec<Action>)`.
//! No I/O, no clocks — timeouts arrive as injected `TimerFired` events.

use std::fmt;

use nxfr_common::error::StateError;

/// Transfer states per §11.1.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransferState {
    /// No transfer in progress.
    Idle,
    /// Sender sent TRANSFER_REQUEST, awaiting response.
    Offered,
    /// Receiver received TRANSFER_REQUEST, awaiting user decision.
    Pending,
    /// Transfer accepted, exchanging FILE_METADATA / FILE_METADATA_ACK.
    Negotiating,
    /// Actively sending/receiving CHUNK frames.
    Streaming,
    /// Transfer paused.
    Paused,
    /// All chunks sent, awaiting verification & TRANSFER_ACK.
    Completing,
    /// Verified and acknowledged (terminal).
    Complete,
    /// Cancelled (terminal).
    Cancelled,
    /// Unrecoverable error (terminal).
    Failed,
}

impl TransferState {
    /// Whether this state is terminal (no further transitions possible).
    pub fn is_terminal(&self) -> bool {
        matches!(self, Self::Complete | Self::Cancelled | Self::Failed)
    }

    /// Whether this state is "active" (can be cancelled).
    pub fn is_active(&self) -> bool {
        matches!(
            self,
            Self::Offered
                | Self::Pending
                | Self::Negotiating
                | Self::Streaming
                | Self::Paused
                | Self::Completing
        )
    }
}

impl fmt::Display for TransferState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Idle => "Idle",
            Self::Offered => "Offered",
            Self::Pending => "Pending",
            Self::Negotiating => "Negotiating",
            Self::Streaming => "Streaming",
            Self::Paused => "Paused",
            Self::Completing => "Completing",
            Self::Complete => "Complete",
            Self::Cancelled => "Cancelled",
            Self::Failed => "Failed",
        })
    }
}

/// Events that drive the transfer state machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransferEvent {
    /// Sender initiates: send TRANSFER_REQUEST.
    SendTransferRequest,
    /// Receiver receives TRANSFER_REQUEST.
    ReceiveTransferRequest,
    /// User accepts the transfer (receiver side).
    UserAccept,
    /// User rejects the transfer (receiver side).
    UserReject,
    /// TRANSFER_ACCEPT received (sender side).
    ReceiveTransferAccept,
    /// TRANSFER_REJECT received (sender side).
    ReceiveTransferReject,
    /// All FILE_METADATA_ACK received, at least one accepted.
    AllMetadataAcked,
    /// All files rejected during negotiation.
    AllFilesRejected,
    /// TRANSFER_PAUSE from either side.
    PauseReceived,
    /// TRANSFER_RESUME from either side.
    ResumeReceived,
    /// Last CHUNK sent (LAST_CHUNK flag on final file).
    LastChunkSent,
    /// Receiver: all chunks received (last chunk or TRANSFER_COMPLETE from sender).
    AllChunksReceived,
    /// TRANSFER_CANCEL from either side.
    CancelReceived,
    /// TRANSFER_COMPLETE sent by sender.
    TransferCompleteSent,
    /// Receiver: TRANSFER_ACK sent with status="success" after verification.
    AckSent,
    /// TRANSFER_ACK received with status="success".
    AckSuccess,
    /// TRANSFER_ACK received with status="partial_failure".
    AckPartialFailure,
    /// Checksum mismatch during streaming.
    ChecksumMismatch,
    /// Disk full on receiver.
    DiskFull,
    /// Connection lost.
    ConnectionLost,
    /// Fatal ERROR received.
    FatalError,
    /// Timer expired.
    TimerFired(TransferTimer),
}

impl fmt::Display for TransferEvent {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::SendTransferRequest => write!(f, "SendTransferRequest"),
            Self::ReceiveTransferRequest => write!(f, "ReceiveTransferRequest"),
            Self::UserAccept => write!(f, "UserAccept"),
            Self::UserReject => write!(f, "UserReject"),
            Self::ReceiveTransferAccept => write!(f, "ReceiveTransferAccept"),
            Self::ReceiveTransferReject => write!(f, "ReceiveTransferReject"),
            Self::AllMetadataAcked => write!(f, "AllMetadataAcked"),
            Self::AllFilesRejected => write!(f, "AllFilesRejected"),
            Self::PauseReceived => write!(f, "PauseReceived"),
            Self::ResumeReceived => write!(f, "ResumeReceived"),
            Self::LastChunkSent => write!(f, "LastChunkSent"),
            Self::AllChunksReceived => write!(f, "AllChunksReceived"),
            Self::CancelReceived => write!(f, "CancelReceived"),
            Self::TransferCompleteSent => write!(f, "TransferCompleteSent"),
            Self::AckSent => write!(f, "AckSent"),
            Self::AckSuccess => write!(f, "AckSuccess"),
            Self::AckPartialFailure => write!(f, "AckPartialFailure"),
            Self::ChecksumMismatch => write!(f, "ChecksumMismatch"),
            Self::DiskFull => write!(f, "DiskFull"),
            Self::ConnectionLost => write!(f, "ConnectionLost"),
            Self::FatalError => write!(f, "FatalError"),
            Self::TimerFired(t) => write!(f, "TimerFired({t:?})"),
        }
    }
}

/// Timers for the transfer state machine per §17.2.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransferTimer {
    /// Transfer consent timeout (120s).
    Consent,
    /// File metadata negotiation timeout (30s per file).
    Negotiation,
    /// Chunk ACK timeout (30s).
    ChunkAck,
    /// Pause timeout (300s, 5 min auto-cancel).
    Pause,
    /// Transfer completion timeout (60s).
    Completion,
}

/// Actions emitted by the transfer state machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransferAction {
    /// Send TRANSFER_REQUEST frame.
    SendTransferRequest,
    /// Display consent UI to the user.
    DisplayConsentUi,
    /// Send TRANSFER_ACCEPT.
    SendTransferAccept,
    /// Send TRANSFER_REJECT.
    SendTransferReject,
    /// Begin FILE_METADATA exchange.
    BeginMetadataExchange,
    /// Sender begins sending CHUNK frames.
    BeginChunkTransmission,
    /// Stop sending chunks (pause).
    StopChunkTransmission,
    /// Resume chunk transmission.
    ResumeChunkTransmission,
    /// Send TRANSFER_COMPLETE.
    SendTransferComplete,
    /// Send TRANSFER_ACK (receiver confirms completion).
    SendTransferAck,
    /// Send TRANSFER_CANCEL.
    SendTransferCancel,
    /// Send ERROR.
    SendError { code: &'static str },
    /// Notify user of transfer result.
    NotifyUser { message: String },
    /// Clean up partial files.
    CleanupPartialFiles,
    /// Preserve resume state for later.
    PreserveResumeState,
    /// Start a timer.
    StartTimer(TransferTimer),
    /// Cancel a timer.
    CancelTimer(TransferTimer),
}

/// Pure transfer state transition function per §11.3.
///
/// Returns `(new_state, actions)` or an error if the transition is invalid.
pub fn transfer_handle_event(
    state: &TransferState,
    event: &TransferEvent,
) -> Result<(TransferState, Vec<TransferAction>), StateError> {
    // Terminal states reject all events.
    if state.is_terminal() {
        return Err(StateError::InvalidTransition {
            from: state.to_string(),
            event: event.to_string(),
        });
    }

    // Global: any active state → CANCELLED on CancelReceived.
    if *event == TransferEvent::CancelReceived && state.is_active() {
        return Ok((
            TransferState::Cancelled,
            vec![
                TransferAction::SendTransferCancel,
                TransferAction::CleanupPartialFiles,
                TransferAction::NotifyUser {
                    message: "Transfer cancelled".into(),
                },
            ],
        ));
    }

    // Global: any active state → FAILED on fatal error or connection loss.
    if (*event == TransferEvent::FatalError || *event == TransferEvent::ConnectionLost)
        && state.is_active()
    {
        return Ok((
            TransferState::Failed,
            vec![
                TransferAction::PreserveResumeState,
                TransferAction::NotifyUser {
                    message: "Transfer failed due to error or connection loss".into(),
                },
            ],
        ));
    }

    match (state, event) {
        // IDLE → OFFERED: Sender sends TRANSFER_REQUEST.
        (TransferState::Idle, TransferEvent::SendTransferRequest) => Ok((
            TransferState::Offered,
            vec![
                TransferAction::SendTransferRequest,
                TransferAction::StartTimer(TransferTimer::Consent),
            ],
        )),

        // IDLE → PENDING: Receiver gets TRANSFER_REQUEST.
        (TransferState::Idle, TransferEvent::ReceiveTransferRequest) => Ok((
            TransferState::Pending,
            vec![
                TransferAction::DisplayConsentUi,
                TransferAction::StartTimer(TransferTimer::Consent),
            ],
        )),

        // PENDING → NEGOTIATING: User accepts.
        (TransferState::Pending, TransferEvent::UserAccept) => Ok((
            TransferState::Negotiating,
            vec![
                TransferAction::CancelTimer(TransferTimer::Consent),
                TransferAction::SendTransferAccept,
                TransferAction::BeginMetadataExchange,
                TransferAction::StartTimer(TransferTimer::Negotiation),
            ],
        )),

        // PENDING → CANCELLED: User rejects.
        (TransferState::Pending, TransferEvent::UserReject) => Ok((
            TransferState::Cancelled,
            vec![
                TransferAction::CancelTimer(TransferTimer::Consent),
                TransferAction::SendTransferReject,
                TransferAction::NotifyUser {
                    message: "Transfer rejected by user".into(),
                },
            ],
        )),

        // OFFERED → NEGOTIATING: Receive TRANSFER_ACCEPT.
        (TransferState::Offered, TransferEvent::ReceiveTransferAccept) => Ok((
            TransferState::Negotiating,
            vec![
                TransferAction::CancelTimer(TransferTimer::Consent),
                TransferAction::BeginMetadataExchange,
                TransferAction::StartTimer(TransferTimer::Negotiation),
            ],
        )),

        // OFFERED → CANCELLED: Receive TRANSFER_REJECT.
        (TransferState::Offered, TransferEvent::ReceiveTransferReject) => Ok((
            TransferState::Cancelled,
            vec![
                TransferAction::CancelTimer(TransferTimer::Consent),
                TransferAction::NotifyUser {
                    message: "Transfer rejected by peer".into(),
                },
            ],
        )),

        // OFFERED → FAILED: Consent timeout (120s).
        (TransferState::Offered, TransferEvent::TimerFired(TransferTimer::Consent)) => Ok((
            TransferState::Failed,
            vec![TransferAction::NotifyUser {
                message: "Transfer consent timed out".into(),
            }],
        )),

        // PENDING → FAILED: Consent timeout (120s).
        (TransferState::Pending, TransferEvent::TimerFired(TransferTimer::Consent)) => Ok((
            TransferState::Failed,
            vec![
                TransferAction::SendTransferReject,
                TransferAction::NotifyUser {
                    message: "Transfer consent timed out".into(),
                },
            ],
        )),

        // NEGOTIATING → STREAMING: All FILE_METADATA_ACK received, at least one accepted.
        (TransferState::Negotiating, TransferEvent::AllMetadataAcked) => Ok((
            TransferState::Streaming,
            vec![
                TransferAction::CancelTimer(TransferTimer::Negotiation),
                TransferAction::BeginChunkTransmission,
                TransferAction::StartTimer(TransferTimer::ChunkAck),
            ],
        )),

        // NEGOTIATING → FAILED: All files rejected or timeout.
        (TransferState::Negotiating, TransferEvent::AllFilesRejected) => Ok((
            TransferState::Failed,
            vec![
                TransferAction::CancelTimer(TransferTimer::Negotiation),
                TransferAction::NotifyUser {
                    message: "All files rejected during negotiation".into(),
                },
            ],
        )),

        (TransferState::Negotiating, TransferEvent::TimerFired(TransferTimer::Negotiation)) => {
            Ok((
                TransferState::Failed,
                vec![TransferAction::NotifyUser {
                    message: "Metadata negotiation timed out".into(),
                }],
            ))
        }

        // STREAMING → PAUSED: TRANSFER_PAUSE.
        (TransferState::Streaming, TransferEvent::PauseReceived) => Ok((
            TransferState::Paused,
            vec![
                TransferAction::CancelTimer(TransferTimer::ChunkAck),
                TransferAction::StopChunkTransmission,
                TransferAction::StartTimer(TransferTimer::Pause),
            ],
        )),

        // STREAMING → COMPLETING: Last CHUNK sent.
        (TransferState::Streaming, TransferEvent::LastChunkSent) => Ok((
            TransferState::Completing,
            vec![
                TransferAction::CancelTimer(TransferTimer::ChunkAck),
                TransferAction::SendTransferComplete,
                TransferAction::StartTimer(TransferTimer::Completion),
            ],
        )),

        // STREAMING → COMPLETING: Receiver received all chunks / TRANSFER_COMPLETE from sender.
        (TransferState::Streaming, TransferEvent::AllChunksReceived) => Ok((
            TransferState::Completing,
            vec![
                TransferAction::CancelTimer(TransferTimer::ChunkAck),
                TransferAction::SendTransferAck,
                TransferAction::StartTimer(TransferTimer::Completion),
            ],
        )),

        // STREAMING → FAILED: Checksum mismatch.
        (TransferState::Streaming, TransferEvent::ChecksumMismatch) => Ok((
            TransferState::Failed,
            vec![
                TransferAction::CancelTimer(TransferTimer::ChunkAck),
                TransferAction::SendError {
                    code: "checksum_mismatch",
                },
                TransferAction::CleanupPartialFiles,
                TransferAction::PreserveResumeState,
            ],
        )),

        // STREAMING → FAILED: Disk full.
        (TransferState::Streaming, TransferEvent::DiskFull) => Ok((
            TransferState::Failed,
            vec![
                TransferAction::CancelTimer(TransferTimer::ChunkAck),
                TransferAction::SendError { code: "disk_full" },
                TransferAction::PreserveResumeState,
            ],
        )),

        // STREAMING → FAILED: Chunk ACK timeout.
        (TransferState::Streaming, TransferEvent::TimerFired(TransferTimer::ChunkAck)) => Ok((
            TransferState::Failed,
            vec![
                TransferAction::NotifyUser {
                    message: "Chunk ACK timed out".into(),
                },
                TransferAction::PreserveResumeState,
            ],
        )),

        // PAUSED → STREAMING: TRANSFER_RESUME.
        (TransferState::Paused, TransferEvent::ResumeReceived) => Ok((
            TransferState::Streaming,
            vec![
                TransferAction::CancelTimer(TransferTimer::Pause),
                TransferAction::ResumeChunkTransmission,
                TransferAction::StartTimer(TransferTimer::ChunkAck),
            ],
        )),

        // PAUSED → FAILED: Pause timeout (300s).
        (TransferState::Paused, TransferEvent::TimerFired(TransferTimer::Pause)) => Ok((
            TransferState::Failed,
            vec![
                TransferAction::SendTransferCancel,
                TransferAction::NotifyUser {
                    message: "Transfer auto-cancelled after pause timeout".into(),
                },
                TransferAction::PreserveResumeState,
            ],
        )),

        // COMPLETING → COMPLETE: TRANSFER_ACK with status="success".
        (TransferState::Completing, TransferEvent::AckSuccess) => Ok((
            TransferState::Complete,
            vec![
                TransferAction::CancelTimer(TransferTimer::Completion),
                TransferAction::NotifyUser {
                    message: "Transfer completed successfully".into(),
                },
            ],
        )),

        // COMPLETING → COMPLETE: Receiver sent TRANSFER_ACK (verification passed).
        (TransferState::Completing, TransferEvent::AckSent) => Ok((
            TransferState::Complete,
            vec![
                TransferAction::CancelTimer(TransferTimer::Completion),
                TransferAction::NotifyUser {
                    message: "Transfer received successfully".into(),
                },
            ],
        )),

        // COMPLETING → FAILED: TRANSFER_ACK with partial_failure or checksum fails.
        (TransferState::Completing, TransferEvent::AckPartialFailure) => Ok((
            TransferState::Failed,
            vec![
                TransferAction::CancelTimer(TransferTimer::Completion),
                TransferAction::NotifyUser {
                    message: "Transfer completed with partial failures".into(),
                },
            ],
        )),

        (TransferState::Completing, TransferEvent::ChecksumMismatch) => Ok((
            TransferState::Failed,
            vec![
                TransferAction::CancelTimer(TransferTimer::Completion),
                TransferAction::SendError {
                    code: "checksum_mismatch",
                },
                TransferAction::NotifyUser {
                    message: "Final checksum verification failed".into(),
                },
            ],
        )),

        // COMPLETING → FAILED: Completion timeout (60s).
        (TransferState::Completing, TransferEvent::TimerFired(TransferTimer::Completion)) => Ok((
            TransferState::Failed,
            vec![TransferAction::NotifyUser {
                message: "Transfer completion timed out".into(),
            }],
        )),

        // Invalid transition.
        _ => Err(StateError::InvalidTransition {
            from: state.to_string(),
            event: event.to_string(),
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Happy path ────────────────────────────────────────────────────

    #[test]
    fn idle_to_offered() {
        let (s, a) =
            transfer_handle_event(&TransferState::Idle, &TransferEvent::SendTransferRequest)
                .unwrap();
        assert_eq!(s, TransferState::Offered);
        assert!(a.contains(&TransferAction::SendTransferRequest));
        assert!(a.contains(&TransferAction::StartTimer(TransferTimer::Consent)));
    }

    #[test]
    fn idle_to_pending() {
        let (s, a) =
            transfer_handle_event(&TransferState::Idle, &TransferEvent::ReceiveTransferRequest)
                .unwrap();
        assert_eq!(s, TransferState::Pending);
        assert!(a.contains(&TransferAction::DisplayConsentUi));
    }

    #[test]
    fn pending_to_negotiating() {
        let (s, a) =
            transfer_handle_event(&TransferState::Pending, &TransferEvent::UserAccept).unwrap();
        assert_eq!(s, TransferState::Negotiating);
        assert!(a.contains(&TransferAction::SendTransferAccept));
        assert!(a.contains(&TransferAction::BeginMetadataExchange));
    }

    #[test]
    fn pending_to_cancelled() {
        let (s, _) =
            transfer_handle_event(&TransferState::Pending, &TransferEvent::UserReject).unwrap();
        assert_eq!(s, TransferState::Cancelled);
    }

    #[test]
    fn offered_to_negotiating() {
        let (s, _) = transfer_handle_event(
            &TransferState::Offered,
            &TransferEvent::ReceiveTransferAccept,
        )
        .unwrap();
        assert_eq!(s, TransferState::Negotiating);
    }

    #[test]
    fn offered_to_cancelled() {
        let (s, _) = transfer_handle_event(
            &TransferState::Offered,
            &TransferEvent::ReceiveTransferReject,
        )
        .unwrap();
        assert_eq!(s, TransferState::Cancelled);
    }

    #[test]
    fn negotiating_to_streaming() {
        let (s, a) = transfer_handle_event(
            &TransferState::Negotiating,
            &TransferEvent::AllMetadataAcked,
        )
        .unwrap();
        assert_eq!(s, TransferState::Streaming);
        assert!(a.contains(&TransferAction::BeginChunkTransmission));
    }

    #[test]
    fn negotiating_all_rejected() {
        let (s, _) = transfer_handle_event(
            &TransferState::Negotiating,
            &TransferEvent::AllFilesRejected,
        )
        .unwrap();
        assert_eq!(s, TransferState::Failed);
    }

    #[test]
    fn streaming_to_paused() {
        let (s, a) =
            transfer_handle_event(&TransferState::Streaming, &TransferEvent::PauseReceived)
                .unwrap();
        assert_eq!(s, TransferState::Paused);
        assert!(a.contains(&TransferAction::StopChunkTransmission));
    }

    #[test]
    fn streaming_to_completing() {
        let (s, a) =
            transfer_handle_event(&TransferState::Streaming, &TransferEvent::LastChunkSent)
                .unwrap();
        assert_eq!(s, TransferState::Completing);
        assert!(a.contains(&TransferAction::SendTransferComplete));
    }

    #[test]
    fn paused_to_streaming() {
        let (s, a) =
            transfer_handle_event(&TransferState::Paused, &TransferEvent::ResumeReceived).unwrap();
        assert_eq!(s, TransferState::Streaming);
        assert!(a.contains(&TransferAction::ResumeChunkTransmission));
    }

    #[test]
    fn completing_to_complete() {
        let (s, _) =
            transfer_handle_event(&TransferState::Completing, &TransferEvent::AckSuccess).unwrap();
        assert_eq!(s, TransferState::Complete);
    }

    #[test]
    fn completing_partial_failure() {
        let (s, _) = transfer_handle_event(
            &TransferState::Completing,
            &TransferEvent::AckPartialFailure,
        )
        .unwrap();
        assert_eq!(s, TransferState::Failed);
    }

    // ── Timeouts ──────────────────────────────────────────────────────

    #[test]
    fn offered_consent_timeout() {
        let (s, _) = transfer_handle_event(
            &TransferState::Offered,
            &TransferEvent::TimerFired(TransferTimer::Consent),
        )
        .unwrap();
        assert_eq!(s, TransferState::Failed);
    }

    #[test]
    fn pending_consent_timeout() {
        let (s, a) = transfer_handle_event(
            &TransferState::Pending,
            &TransferEvent::TimerFired(TransferTimer::Consent),
        )
        .unwrap();
        assert_eq!(s, TransferState::Failed);
        assert!(a.contains(&TransferAction::SendTransferReject));
    }

    #[test]
    fn negotiating_timeout() {
        let (s, _) = transfer_handle_event(
            &TransferState::Negotiating,
            &TransferEvent::TimerFired(TransferTimer::Negotiation),
        )
        .unwrap();
        assert_eq!(s, TransferState::Failed);
    }

    #[test]
    fn streaming_chunk_ack_timeout() {
        let (s, a) = transfer_handle_event(
            &TransferState::Streaming,
            &TransferEvent::TimerFired(TransferTimer::ChunkAck),
        )
        .unwrap();
        assert_eq!(s, TransferState::Failed);
        assert!(a.contains(&TransferAction::PreserveResumeState));
    }

    #[test]
    fn paused_timeout() {
        let (s, a) = transfer_handle_event(
            &TransferState::Paused,
            &TransferEvent::TimerFired(TransferTimer::Pause),
        )
        .unwrap();
        assert_eq!(s, TransferState::Failed);
        assert!(a.contains(&TransferAction::SendTransferCancel));
    }

    #[test]
    fn completing_timeout() {
        let (s, _) = transfer_handle_event(
            &TransferState::Completing,
            &TransferEvent::TimerFired(TransferTimer::Completion),
        )
        .unwrap();
        assert_eq!(s, TransferState::Failed);
    }

    // ── Error transitions ─────────────────────────────────────────────

    #[test]
    fn streaming_checksum_mismatch() {
        let (s, a) =
            transfer_handle_event(&TransferState::Streaming, &TransferEvent::ChecksumMismatch)
                .unwrap();
        assert_eq!(s, TransferState::Failed);
        assert!(a.iter().any(|a| matches!(
            a,
            TransferAction::SendError {
                code: "checksum_mismatch"
            }
        )));
    }

    #[test]
    fn streaming_disk_full() {
        let (s, _) =
            transfer_handle_event(&TransferState::Streaming, &TransferEvent::DiskFull).unwrap();
        assert_eq!(s, TransferState::Failed);
    }

    // ── Global cancel ─────────────────────────────────────────────────

    #[test]
    fn cancel_from_any_active_state() {
        let active_states = [
            TransferState::Offered,
            TransferState::Pending,
            TransferState::Negotiating,
            TransferState::Streaming,
            TransferState::Paused,
            TransferState::Completing,
        ];
        for state in &active_states {
            let (s, _) = transfer_handle_event(state, &TransferEvent::CancelReceived).unwrap();
            assert_eq!(s, TransferState::Cancelled, "cancel from {state}");
        }
    }

    // ── Global fatal/connection loss ──────────────────────────────────

    #[test]
    fn fatal_error_from_active() {
        let (s, a) =
            transfer_handle_event(&TransferState::Streaming, &TransferEvent::FatalError).unwrap();
        assert_eq!(s, TransferState::Failed);
        assert!(a.contains(&TransferAction::PreserveResumeState));
    }

    #[test]
    fn connection_lost_from_active() {
        let (s, _) =
            transfer_handle_event(&TransferState::Negotiating, &TransferEvent::ConnectionLost)
                .unwrap();
        assert_eq!(s, TransferState::Failed);
    }

    // ── Terminal states reject events ─────────────────────────────────

    #[test]
    fn complete_rejects_events() {
        let result =
            transfer_handle_event(&TransferState::Complete, &TransferEvent::CancelReceived);
        assert!(result.is_err());
    }

    #[test]
    fn cancelled_rejects_events() {
        let result =
            transfer_handle_event(&TransferState::Cancelled, &TransferEvent::ResumeReceived);
        assert!(result.is_err());
    }

    #[test]
    fn failed_rejects_events() {
        let result = transfer_handle_event(&TransferState::Failed, &TransferEvent::UserAccept);
        assert!(result.is_err());
    }

    // ── Invalid transitions ───────────────────────────────────────────

    #[test]
    fn idle_rejects_accept() {
        let result = transfer_handle_event(&TransferState::Idle, &TransferEvent::UserAccept);
        assert!(result.is_err());
    }

    #[test]
    fn offered_rejects_user_accept() {
        let result = transfer_handle_event(&TransferState::Offered, &TransferEvent::UserAccept);
        assert!(result.is_err());
    }

    #[test]
    fn pending_rejects_ack() {
        let result = transfer_handle_event(&TransferState::Pending, &TransferEvent::AckSuccess);
        assert!(result.is_err());
    }

    // ── Receiver-side transitions ────────────────────────────────────

    #[test]
    fn receiver_streaming_to_completing_on_all_chunks_received() {
        let (s, a) =
            transfer_handle_event(&TransferState::Streaming, &TransferEvent::AllChunksReceived)
                .unwrap();
        assert_eq!(s, TransferState::Completing);
        assert!(a.contains(&TransferAction::SendTransferAck));
        assert!(a.contains(&TransferAction::CancelTimer(TransferTimer::ChunkAck)));
        assert!(a.contains(&TransferAction::StartTimer(TransferTimer::Completion)));
    }

    #[test]
    fn receiver_completing_to_complete_on_ack_sent() {
        let (s, a) =
            transfer_handle_event(&TransferState::Completing, &TransferEvent::AckSent).unwrap();
        assert_eq!(s, TransferState::Complete);
        assert!(a.contains(&TransferAction::CancelTimer(TransferTimer::Completion)));
    }

    #[test]
    fn receiver_full_happy_path() {
        // Idle → Pending
        let (s, _) =
            transfer_handle_event(&TransferState::Idle, &TransferEvent::ReceiveTransferRequest)
                .unwrap();
        assert_eq!(s, TransferState::Pending);

        // Pending → Negotiating
        let (s, _) = transfer_handle_event(&s, &TransferEvent::UserAccept).unwrap();
        assert_eq!(s, TransferState::Negotiating);

        // Negotiating → Streaming
        let (s, _) = transfer_handle_event(&s, &TransferEvent::AllMetadataAcked).unwrap();
        assert_eq!(s, TransferState::Streaming);

        // Streaming → Completing (receiver got all chunks)
        let (s, _) = transfer_handle_event(&s, &TransferEvent::AllChunksReceived).unwrap();
        assert_eq!(s, TransferState::Completing);

        // Completing → Complete (receiver sent ACK)
        let (s, _) = transfer_handle_event(&s, &TransferEvent::AckSent).unwrap();
        assert_eq!(s, TransferState::Complete);
    }

    #[test]
    fn incomplete_transfer_does_not_reach_complete() {
        // Still in Streaming — should NOT accept AckSent
        let result = transfer_handle_event(&TransferState::Streaming, &TransferEvent::AckSent);
        assert!(result.is_err());

        // Still in Negotiating — should NOT accept AllChunksReceived
        let result = transfer_handle_event(
            &TransferState::Negotiating,
            &TransferEvent::AllChunksReceived,
        );
        assert!(result.is_err());
    }

    #[test]
    fn idle_rejects_ack_sent() {
        let result = transfer_handle_event(&TransferState::Idle, &TransferEvent::AckSent);
        assert!(result.is_err());
    }

    #[test]
    fn pending_rejects_all_chunks_received() {
        let result =
            transfer_handle_event(&TransferState::Pending, &TransferEvent::AllChunksReceived);
        assert!(result.is_err());
    }
}
