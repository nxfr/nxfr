//! Session state machine per PROTOCOL §10.
//!
//! Pure function: `handle_event(state, event) -> (new_state, Vec<Action>)`.
//! No I/O, no clocks — timeouts arrive as injected `TimerFired` events.

use std::fmt;

use nxfr_common::error::StateError;
use nxfr_common::types::DeviceId;

/// Session states per §10.1.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionState {
    /// No TCP connection exists.
    Disconnected,
    /// TCP + TLS handshake in progress.
    Connecting,
    /// TLS established. Awaiting HELLO / HELLO_ACK.
    HelloWait,
    /// HELLO exchange complete.
    Established,
    /// SAS displayed, awaiting user confirmation.
    Pairing,
    /// Session operational. Transfers may proceed.
    Active,
    /// SESSION_CLOSE sent. Awaiting peer's close or timeout.
    Closing,
    /// Connection terminated. All resources released.
    Closed,
}

impl fmt::Display for SessionState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Disconnected => "Disconnected",
            Self::Connecting => "Connecting",
            Self::HelloWait => "HelloWait",
            Self::Established => "Established",
            Self::Pairing => "Pairing",
            Self::Active => "Active",
            Self::Closing => "Closing",
            Self::Closed => "Closed",
        })
    }
}

/// Events that drive the session state machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionEvent {
    /// User initiates connection.
    UserConnect,
    /// TLS handshake completed successfully.
    TlsEstablished,
    /// TLS handshake failed.
    TlsFailed,
    /// Valid HELLO received with compatible version.
    HelloReceived {
        device_id: DeviceId,
        is_paired: bool,
    },
    /// Valid HELLO_ACK received.
    HelloAckReceived {
        device_id: DeviceId,
        session_id: u32,
        is_paired: bool,
    },
    /// Incompatible version received.
    IncompatibleVersion,
    /// PAIR_REQUEST received or sent.
    PairRequestSent,
    /// PAIR_ACCEPT received from peer.
    PairAcceptReceived,
    /// PAIR_REJECT received from peer.
    PairRejectReceived,
    /// User accepted unpaired session or transfer initiated.
    UnpairedSessionAccepted,
    /// Both devices are already paired (is_paired=true and identity matches).
    MutuallyPaired,
    /// SESSION_CLOSE sent by us.
    SessionCloseSent,
    /// SESSION_CLOSE received from peer.
    SessionCloseReceived,
    /// Connection dropped unexpectedly.
    ConnectionLost,
    /// Fatal ERROR received.
    FatalErrorReceived,
    /// Timer expired (parameterized by which timer).
    TimerFired(SessionTimer),
}

impl fmt::Display for SessionEvent {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::UserConnect => write!(f, "UserConnect"),
            Self::TlsEstablished => write!(f, "TlsEstablished"),
            Self::TlsFailed => write!(f, "TlsFailed"),
            Self::HelloReceived { .. } => write!(f, "HelloReceived"),
            Self::HelloAckReceived { .. } => write!(f, "HelloAckReceived"),
            Self::IncompatibleVersion => write!(f, "IncompatibleVersion"),
            Self::PairRequestSent => write!(f, "PairRequestSent"),
            Self::PairAcceptReceived => write!(f, "PairAcceptReceived"),
            Self::PairRejectReceived => write!(f, "PairRejectReceived"),
            Self::UnpairedSessionAccepted => write!(f, "UnpairedSessionAccepted"),
            Self::MutuallyPaired => write!(f, "MutuallyPaired"),
            Self::SessionCloseSent => write!(f, "SessionCloseSent"),
            Self::SessionCloseReceived => write!(f, "SessionCloseReceived"),
            Self::ConnectionLost => write!(f, "ConnectionLost"),
            Self::FatalErrorReceived => write!(f, "FatalErrorReceived"),
            Self::TimerFired(t) => write!(f, "TimerFired({t:?})"),
        }
    }
}

/// Timers that can fire for the session state machine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionTimer {
    /// TLS handshake timeout (10s).
    TlsHandshake,
    /// HELLO exchange timeout (10s).
    HelloExchange,
    /// Pairing SAS confirmation timeout (60s).
    PairingSas,
    /// Session close grace period (5s).
    CloseGrace,
    /// Keepalive timeout (90s).
    KeepaliveTimeout,
}

/// Actions emitted by the session state machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionAction {
    /// Open TCP socket and begin TLS handshake.
    OpenConnection,
    /// Send HELLO message.
    SendHello,
    /// Send HELLO_ACK message with the given session_id.
    SendHelloAck { session_id: u32 },
    /// Record the session_id.
    RecordSessionId { session_id: u32 },
    /// Display SAS on both devices.
    DisplaySas,
    /// Pin peer identity in paired database.
    PinPeerIdentity { device_id: DeviceId },
    /// Send ERROR message.
    SendError { code: &'static str },
    /// Send SESSION_CLOSE.
    SendSessionClose,
    /// Close the TLS/TCP connection.
    CloseConnection,
    /// Release all resources.
    ReleaseResources,
    /// Start a timer.
    StartTimer(SessionTimer),
    /// Cancel a timer.
    CancelTimer(SessionTimer),
    /// Log an error/event.
    Log { message: String },
}

/// Pure session state transition function per §10.3.
///
/// Returns `(new_state, actions)` or an error if the transition is invalid.
pub fn session_handle_event(
    state: &SessionState,
    event: &SessionEvent,
) -> Result<(SessionState, Vec<SessionAction>), StateError> {
    use SessionAction::*;
    use SessionEvent::*;
    use SessionState::*;

    match (state, event) {
        // DISCONNECTED → CONNECTING: User initiates connection.
        (Disconnected, UserConnect) => Ok((
            Connecting,
            vec![OpenConnection, StartTimer(SessionTimer::TlsHandshake)],
        )),

        // CONNECTING → HELLO_WAIT: TLS success.
        (Connecting, TlsEstablished) => Ok((
            HelloWait,
            vec![
                CancelTimer(SessionTimer::TlsHandshake),
                SendHello,
                StartTimer(SessionTimer::HelloExchange),
            ],
        )),

        // CONNECTING → CLOSED: TLS failure or timeout.
        (Connecting, TlsFailed) | (Connecting, TimerFired(SessionTimer::TlsHandshake)) => Ok((
            Closed,
            vec![
                CancelTimer(SessionTimer::TlsHandshake),
                Log {
                    message: "TLS handshake failed or timed out".into(),
                },
                ReleaseResources,
            ],
        )),

        // HELLO_WAIT → ESTABLISHED: Valid HELLO received, compatible version.
        // Responder side: we received their HELLO, now send HELLO_ACK.
        (
            HelloWait,
            HelloReceived {
                device_id: _,
                is_paired: _,
            },
        ) => Ok((
            Established,
            vec![
                CancelTimer(SessionTimer::HelloExchange),
                // The caller provides the session_id for SendHelloAck.
                // We emit a placeholder; the transport layer fills in session_id.
                SendHelloAck { session_id: 0 },
            ],
        )),

        // HELLO_WAIT → ESTABLISHED: Valid HELLO_ACK received.
        // Initiator side: we sent HELLO, got HELLO_ACK back.
        (
            HelloWait,
            HelloAckReceived {
                device_id: _,
                session_id,
                is_paired: _,
            },
        ) => Ok((
            Established,
            vec![
                CancelTimer(SessionTimer::HelloExchange),
                RecordSessionId {
                    session_id: *session_id,
                },
            ],
        )),

        // HELLO_WAIT → CLOSED: Timeout or incompatible version.
        (HelloWait, TimerFired(SessionTimer::HelloExchange)) => Ok((
            Closed,
            vec![
                Log {
                    message: "HELLO exchange timed out".into(),
                },
                ReleaseResources,
            ],
        )),

        (HelloWait, IncompatibleVersion) => Ok((
            Closed,
            vec![
                SendError {
                    code: "unsupported_version",
                },
                ReleaseResources,
            ],
        )),

        // ESTABLISHED → PAIRING: Either side sends PAIR_REQUEST.
        (Established, PairRequestSent) => Ok((
            Pairing,
            vec![DisplaySas, StartTimer(SessionTimer::PairingSas)],
        )),

        // ESTABLISHED → ACTIVE: Both is_paired=true AND identity matches.
        (Established, MutuallyPaired) => Ok((Active, vec![])),

        // ESTABLISHED → ACTIVE: User accepts unpaired session.
        (Established, UnpairedSessionAccepted) => Ok((Active, vec![])),

        // PAIRING → ACTIVE: Both sides send PAIR_ACCEPT.
        (Pairing, PairAcceptReceived) => Ok((
            Active,
            vec![
                CancelTimer(SessionTimer::PairingSas),
                // The device_id to pin will be supplied by the caller.
                PinPeerIdentity {
                    device_id: DeviceId([0; 32]),
                },
            ],
        )),

        // PAIRING → ESTABLISHED: Either side sends PAIR_REJECT.
        (Pairing, PairRejectReceived) => Ok((
            Established,
            vec![
                CancelTimer(SessionTimer::PairingSas),
                Log {
                    message: "Pairing rejected by peer".into(),
                },
            ],
        )),

        // PAIRING → CLOSED: Pairing timeout.
        (Pairing, TimerFired(SessionTimer::PairingSas)) => Ok((
            Closed,
            vec![
                Log {
                    message: "Pairing SAS confirmation timed out".into(),
                },
                CloseConnection,
                ReleaseResources,
            ],
        )),

        // ACTIVE → CLOSING: Either side sends SESSION_CLOSE.
        (Active, SessionCloseSent) => Ok((
            Closing,
            vec![SendSessionClose, StartTimer(SessionTimer::CloseGrace)],
        )),

        // ACTIVE → CLOSED: Connection drops or fatal ERROR.
        (Active, ConnectionLost) | (Active, FatalErrorReceived) => Ok((
            Closed,
            vec![
                Log {
                    message: "Connection lost or fatal error in ACTIVE state".into(),
                },
                ReleaseResources,
            ],
        )),

        // CLOSING → CLOSED: Peer sends SESSION_CLOSE or close grace timeout.
        (Closing, SessionCloseReceived) | (Closing, TimerFired(SessionTimer::CloseGrace)) => Ok((
            Closed,
            vec![
                CancelTimer(SessionTimer::CloseGrace),
                CloseConnection,
                ReleaseResources,
            ],
        )),

        // ANY → CLOSED: Fatal error from any state.
        (_, FatalErrorReceived) if *state != Closed && *state != Disconnected => Ok((
            Closed,
            vec![
                SendError {
                    code: "internal_error",
                },
                CloseConnection,
                ReleaseResources,
            ],
        )),

        (_, ConnectionLost) if *state != Closed && *state != Disconnected => {
            Ok((Closed, vec![ReleaseResources]))
        }

        // KEEPALIVE timeout from ACTIVE → CLOSED.
        (Active, TimerFired(SessionTimer::KeepaliveTimeout)) => Ok((
            Closed,
            vec![
                SendError {
                    code: "session_timeout",
                },
                CloseConnection,
                ReleaseResources,
            ],
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

    #[test]
    fn disconnected_to_connecting() {
        let (new_state, actions) =
            session_handle_event(&SessionState::Disconnected, &SessionEvent::UserConnect).unwrap();
        assert_eq!(new_state, SessionState::Connecting);
        assert!(actions.contains(&SessionAction::OpenConnection));
        assert!(actions.contains(&SessionAction::StartTimer(SessionTimer::TlsHandshake)));
    }

    #[test]
    fn connecting_to_hello_wait() {
        let (new_state, actions) =
            session_handle_event(&SessionState::Connecting, &SessionEvent::TlsEstablished).unwrap();
        assert_eq!(new_state, SessionState::HelloWait);
        assert!(actions.contains(&SessionAction::SendHello));
    }

    #[test]
    fn connecting_tls_failure() {
        let (new_state, _) =
            session_handle_event(&SessionState::Connecting, &SessionEvent::TlsFailed).unwrap();
        assert_eq!(new_state, SessionState::Closed);
    }

    #[test]
    fn connecting_tls_timeout() {
        let (new_state, _) = session_handle_event(
            &SessionState::Connecting,
            &SessionEvent::TimerFired(SessionTimer::TlsHandshake),
        )
        .unwrap();
        assert_eq!(new_state, SessionState::Closed);
    }

    #[test]
    fn hello_wait_to_established_responder() {
        let (new_state, actions) = session_handle_event(
            &SessionState::HelloWait,
            &SessionEvent::HelloReceived {
                device_id: DeviceId([0; 32]),
                is_paired: false,
            },
        )
        .unwrap();
        assert_eq!(new_state, SessionState::Established);
        assert!(actions
            .iter()
            .any(|a| matches!(a, SessionAction::SendHelloAck { .. })));
    }

    #[test]
    fn hello_wait_to_established_initiator() {
        let (new_state, actions) = session_handle_event(
            &SessionState::HelloWait,
            &SessionEvent::HelloAckReceived {
                device_id: DeviceId([0; 32]),
                session_id: 0x1234,
                is_paired: false,
            },
        )
        .unwrap();
        assert_eq!(new_state, SessionState::Established);
        assert!(actions.contains(&SessionAction::RecordSessionId { session_id: 0x1234 }));
    }

    #[test]
    fn hello_wait_timeout() {
        let (new_state, _) = session_handle_event(
            &SessionState::HelloWait,
            &SessionEvent::TimerFired(SessionTimer::HelloExchange),
        )
        .unwrap();
        assert_eq!(new_state, SessionState::Closed);
    }

    #[test]
    fn hello_wait_incompatible_version() {
        let (new_state, actions) =
            session_handle_event(&SessionState::HelloWait, &SessionEvent::IncompatibleVersion)
                .unwrap();
        assert_eq!(new_state, SessionState::Closed);
        assert!(actions.iter().any(|a| matches!(
            a,
            SessionAction::SendError {
                code: "unsupported_version"
            }
        )));
    }

    #[test]
    fn established_to_pairing() {
        let (new_state, actions) =
            session_handle_event(&SessionState::Established, &SessionEvent::PairRequestSent)
                .unwrap();
        assert_eq!(new_state, SessionState::Pairing);
        assert!(actions.contains(&SessionAction::DisplaySas));
    }

    #[test]
    fn established_to_active_mutually_paired() {
        let (new_state, _) =
            session_handle_event(&SessionState::Established, &SessionEvent::MutuallyPaired)
                .unwrap();
        assert_eq!(new_state, SessionState::Active);
    }

    #[test]
    fn established_to_active_unpaired() {
        let (new_state, _) = session_handle_event(
            &SessionState::Established,
            &SessionEvent::UnpairedSessionAccepted,
        )
        .unwrap();
        assert_eq!(new_state, SessionState::Active);
    }

    #[test]
    fn pairing_accept() {
        let (new_state, actions) =
            session_handle_event(&SessionState::Pairing, &SessionEvent::PairAcceptReceived)
                .unwrap();
        assert_eq!(new_state, SessionState::Active);
        assert!(actions.contains(&SessionAction::CancelTimer(SessionTimer::PairingSas)));
    }

    #[test]
    fn pairing_reject() {
        let (new_state, _) =
            session_handle_event(&SessionState::Pairing, &SessionEvent::PairRejectReceived)
                .unwrap();
        assert_eq!(new_state, SessionState::Established);
    }

    #[test]
    fn pairing_timeout() {
        let (new_state, _) = session_handle_event(
            &SessionState::Pairing,
            &SessionEvent::TimerFired(SessionTimer::PairingSas),
        )
        .unwrap();
        assert_eq!(new_state, SessionState::Closed);
    }

    #[test]
    fn active_to_closing() {
        let (new_state, actions) =
            session_handle_event(&SessionState::Active, &SessionEvent::SessionCloseSent).unwrap();
        assert_eq!(new_state, SessionState::Closing);
        assert!(actions.contains(&SessionAction::SendSessionClose));
    }

    #[test]
    fn active_connection_lost() {
        let (new_state, _) =
            session_handle_event(&SessionState::Active, &SessionEvent::ConnectionLost).unwrap();
        assert_eq!(new_state, SessionState::Closed);
    }

    #[test]
    fn active_fatal_error() {
        let (new_state, _) =
            session_handle_event(&SessionState::Active, &SessionEvent::FatalErrorReceived).unwrap();
        assert_eq!(new_state, SessionState::Closed);
    }

    #[test]
    fn active_keepalive_timeout() {
        let (new_state, actions) = session_handle_event(
            &SessionState::Active,
            &SessionEvent::TimerFired(SessionTimer::KeepaliveTimeout),
        )
        .unwrap();
        assert_eq!(new_state, SessionState::Closed);
        assert!(actions.iter().any(|a| matches!(
            a,
            SessionAction::SendError {
                code: "session_timeout"
            }
        )));
    }

    #[test]
    fn closing_peer_closes() {
        let (new_state, _) =
            session_handle_event(&SessionState::Closing, &SessionEvent::SessionCloseReceived)
                .unwrap();
        assert_eq!(new_state, SessionState::Closed);
    }

    #[test]
    fn closing_timeout() {
        let (new_state, _) = session_handle_event(
            &SessionState::Closing,
            &SessionEvent::TimerFired(SessionTimer::CloseGrace),
        )
        .unwrap();
        assert_eq!(new_state, SessionState::Closed);
    }

    #[test]
    fn invalid_transition_rejected() {
        let result =
            session_handle_event(&SessionState::Disconnected, &SessionEvent::TlsEstablished);
        assert!(result.is_err());
    }

    #[test]
    fn closed_rejects_events() {
        let result = session_handle_event(&SessionState::Closed, &SessionEvent::UserConnect);
        assert!(result.is_err());
    }
}
