//! Persistent identity management.
//!
//! Stores PKCS#8 DER key at `~/.local/share/nxfr/identity.der`
//! and self-signed cert at `~/.local/share/nxfr/identity.crt`.
//! Generates on first run, loads on subsequent runs.

use log::{info, warn};
use nxfr_crypto::generate_identity;
use rustls_pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use zeroize::Zeroize;

const KEY_FILENAME: &str = "identity.der";
const CERT_FILENAME: &str = "identity.crt";

/// Persistent identity: device_id + key/cert bytes for TLS.
///
/// Private key material is zeroized on drop to limit exposure in memory.
#[derive(Clone)]
pub struct PersistentIdentity {
    pub device_id: [u8; 32],
    key_der: Vec<u8>,
    cert_der: Vec<u8>,
}

impl Drop for PersistentIdentity {
    fn drop(&mut self) {
        self.key_der.zeroize();
    }
}

impl PersistentIdentity {
    /// Load from disk or generate a new identity.
    pub fn load_or_generate(data_dir: &Path) -> Result<Self, io::Error> {
        let key_path = data_dir.join(KEY_FILENAME);
        let cert_path = data_dir.join(CERT_FILENAME);

        if key_path.exists() && cert_path.exists() {
            info!("Loading existing identity from {}", data_dir.display());

            // Check key file permissions on Unix — warn if too permissive.
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let perms = fs::metadata(&key_path)?.permissions();
                let mode = perms.mode() & 0o777;
                if mode & 0o077 != 0 {
                    warn!(
                        "Identity key {} has overly permissive mode {:04o} (expected 0600). \
                         Repairing permissions.",
                        key_path.display(),
                        mode
                    );
                    let _ = fs::set_permissions(&key_path, fs::Permissions::from_mode(0o600));
                }
            }

            let key_der = fs::read(&key_path)?;
            let cert_der = fs::read(&cert_path)?;

            // Derive device_id from cert SPKI.
            let device_id = nxfr_crypto::device_id_from_cert(&cert_der).map_err(|e| {
                io::Error::new(io::ErrorKind::InvalidData, format!("bad cert: {e}"))
            })?;

            Ok(Self {
                device_id,
                key_der,
                cert_der,
            })
        } else {
            info!("Generating new identity in {}", data_dir.display());
            let ident =
                generate_identity().map_err(|e| io::Error::other(format!("keygen: {e}")))?;

            // Write key with restricted permissions (SECURITY §6).
            fs::write(&key_path, &ident.private_key_der)?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = fs::set_permissions(&key_path, fs::Permissions::from_mode(0o600));
            }
            // Write cert.
            fs::write(&cert_path, &ident.cert_der)?;

            Ok(Self {
                device_id: ident.device_id,
                key_der: ident.private_key_der,
                cert_der: ident.cert_der,
            })
        }
    }

    /// Get the private key in rustls format.
    pub fn private_key(&self) -> PrivateKeyDer<'static> {
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key_der.clone()))
    }

    /// Get the certificate in rustls format.
    pub fn certificate(&self) -> CertificateDer<'static> {
        CertificateDer::from(self.cert_der.clone())
    }

    /// Get the raw certificate DER bytes.
    pub fn cert_der_bytes(&self) -> &[u8] {
        &self.cert_der
    }

    /// Create from raw components (for testing).
    pub fn from_raw(device_id: [u8; 32], key_der: Vec<u8>, cert_der: Vec<u8>) -> Self {
        Self {
            device_id,
            key_der,
            cert_der,
        }
    }
}

/// Default data directory: `~/.local/share/nxfr/`
pub fn data_dir() -> PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(|| PathBuf::from("/tmp"))
        .join("nxfr")
}
