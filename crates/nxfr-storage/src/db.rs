use chrono::Utc;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::path::Path;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum StorageError {
    #[error("Database error: {0}")]
    Db(#[from] rusqlite::Error),
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("Missing home directory")]
    MissingHomeDir,
}

pub type Result<T> = std::result::Result<T, StorageError>;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PairedDevice {
    pub device_id: String,
    pub name: String,
    pub public_key_spki: Vec<u8>,
    pub first_seen: i64,
    pub last_seen: i64,
    pub trust_level: String,
    pub auto_accept: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IdentityCheck {
    Matched,
    Changed,
    Unknown,
}

pub struct PairedDeviceDb {
    conn: Connection,
}

impl PairedDeviceDb {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let conn = Connection::open(path)?;
        Self::init_schema(&conn)?;
        Ok(Self { conn })
    }

    pub fn open_default() -> Result<Self> {
        let mut path = dirs::data_local_dir().ok_or(StorageError::MissingHomeDir)?;
        path.push("nxfr");
        path.push("paired.db");
        Self::open(&path)
    }

    fn init_schema(conn: &Connection) -> Result<()> {
        conn.execute(
            "CREATE TABLE IF NOT EXISTS paired_devices (
                device_id       TEXT PRIMARY KEY NOT NULL,
                name            TEXT NOT NULL,
                public_key_spki BLOB NOT NULL,
                first_seen      INTEGER NOT NULL,
                last_seen       INTEGER NOT NULL,
                trust_level     TEXT NOT NULL DEFAULT 'paired',
                auto_accept     TEXT NOT NULL DEFAULT 'prompt'
            )",
            [],
        )?;
        conn.execute(
            "CREATE TABLE IF NOT EXISTS schema_version (
                version INTEGER NOT NULL
            )",
            [],
        )?;

        let version: rusqlite::Result<i64> =
            conn.query_row("SELECT version FROM schema_version LIMIT 1", [], |row| {
                row.get(0)
            });
        if version.is_err() {
            conn.execute("INSERT INTO schema_version (version) VALUES (1)", [])?;
        }
        Ok(())
    }

    pub fn insert_or_update(&self, device: &PairedDevice) -> Result<()> {
        self.conn.execute(
            "INSERT INTO paired_devices (
                device_id, name, public_key_spki, first_seen, last_seen, trust_level, auto_accept
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
            ON CONFLICT(device_id) DO UPDATE SET
                name=excluded.name,
                public_key_spki=excluded.public_key_spki,
                last_seen=excluded.last_seen,
                trust_level=excluded.trust_level,
                auto_accept=excluded.auto_accept",
            params![
                device.device_id,
                device.name,
                device.public_key_spki,
                device.first_seen,
                device.last_seen,
                device.trust_level,
                device.auto_accept
            ],
        )?;
        Ok(())
    }

    pub fn lookup(&self, device_id: &str) -> Result<Option<PairedDevice>> {
        let mut stmt = self.conn.prepare(
            "SELECT device_id, name, public_key_spki, first_seen, last_seen, trust_level, auto_accept
             FROM paired_devices WHERE device_id = ?1"
        )?;
        let mut rows = stmt.query(params![device_id])?;
        if let Some(row) = rows.next()? {
            Ok(Some(PairedDevice {
                device_id: row.get(0)?,
                name: row.get(1)?,
                public_key_spki: row.get(2)?,
                first_seen: row.get(3)?,
                last_seen: row.get(4)?,
                trust_level: row.get(5)?,
                auto_accept: row.get(6)?,
            }))
        } else {
            Ok(None)
        }
    }

    pub fn remove(&self, device_id: &str) -> Result<()> {
        self.conn.execute(
            "DELETE FROM paired_devices WHERE device_id = ?1",
            params![device_id],
        )?;
        Ok(())
    }

    pub fn list_all(&self) -> Result<Vec<PairedDevice>> {
        let mut stmt = self.conn.prepare(
            "SELECT device_id, name, public_key_spki, first_seen, last_seen, trust_level, auto_accept
             FROM paired_devices"
        )?;
        let dev_iter = stmt.query_map([], |row| {
            Ok(PairedDevice {
                device_id: row.get(0)?,
                name: row.get(1)?,
                public_key_spki: row.get(2)?,
                first_seen: row.get(3)?,
                last_seen: row.get(4)?,
                trust_level: row.get(5)?,
                auto_accept: row.get(6)?,
            })
        })?;

        let mut devs = Vec::new();
        for dev in dev_iter {
            devs.push(dev?);
        }
        Ok(devs)
    }

    pub fn is_paired(&self, device_id: &str) -> bool {
        self.lookup(device_id).map(|d| d.is_some()).unwrap_or(false)
    }

    pub fn should_auto_accept(&self, device_id: &str) -> bool {
        if let Ok(Some(dev)) = self.lookup(device_id) {
            dev.trust_level == "paired" && dev.auto_accept == "always"
        } else {
            false
        }
    }

    pub fn update_last_seen(&self, device_id: &str) -> Result<()> {
        let now = Utc::now().timestamp();
        self.conn.execute(
            "UPDATE paired_devices SET last_seen = ?1 WHERE device_id = ?2",
            params![now, device_id],
        )?;
        Ok(())
    }

    pub fn verify_identity(&self, device_id: &str, incoming_key: &[u8]) -> Result<IdentityCheck> {
        if let Some(dev) = self.lookup(device_id)? {
            // Normalize stored key: if it's full X.509 cert DER (from older daemon versions), extract SPKI.
            let stored_spki = nxfr_crypto::extract_spki(&dev.public_key_spki)
                .unwrap_or_else(|_| dev.public_key_spki.clone());

            // Normalize incoming key: extract SPKI if full cert DER was passed.
            let incoming_spki =
                nxfr_crypto::extract_spki(incoming_key).unwrap_or_else(|_| incoming_key.to_vec());

            // Constant-time comparison (SECURITY §10).
            let is_match = if stored_spki.len() == incoming_spki.len() {
                let mut diff = 0u8;
                for (a, b) in stored_spki.iter().zip(incoming_spki.iter()) {
                    diff |= a ^ b;
                }
                diff == 0
            } else {
                false
            };

            if is_match {
                Ok(IdentityCheck::Matched)
            } else {
                Ok(IdentityCheck::Changed)
            }
        } else {
            Ok(IdentityCheck::Unknown)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_open_creates_schema() {
        let dir = tempdir().unwrap();
        let db_path = dir.path().join("paired.db");
        let db = PairedDeviceDb::open(&db_path).unwrap();
        let version: i64 = db
            .conn
            .query_row("SELECT version FROM schema_version", [], |row| row.get(0))
            .unwrap();
        assert_eq!(version, 1);
    }

    fn sample_device() -> PairedDevice {
        PairedDevice {
            device_id: "test-id".to_string(),
            name: "Test Dev".to_string(),
            public_key_spki: vec![1, 2, 3],
            first_seen: 1000,
            last_seen: 1000,
            trust_level: "paired".to_string(),
            auto_accept: "prompt".to_string(),
        }
    }

    #[test]
    fn test_insert_and_lookup() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();
        let dev = sample_device();
        db.insert_or_update(&dev).unwrap();

        let found = db.lookup("test-id").unwrap().unwrap();
        assert_eq!(found, dev);
    }

    #[test]
    fn test_upsert_updates() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();
        let mut dev = sample_device();
        db.insert_or_update(&dev).unwrap();

        dev.name = "New Name".to_string();
        dev.last_seen = 2000;
        db.insert_or_update(&dev).unwrap();

        let found = db.lookup("test-id").unwrap().unwrap();
        assert_eq!(found.name, "New Name");
        assert_eq!(found.last_seen, 2000);
    }

    #[test]
    fn test_remove() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();
        let dev = sample_device();
        db.insert_or_update(&dev).unwrap();
        db.remove("test-id").unwrap();
        assert!(db.lookup("test-id").unwrap().is_none());
    }

    #[test]
    fn test_list_all() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();
        let mut dev1 = sample_device();
        dev1.device_id = "id1".to_string();
        let mut dev2 = sample_device();
        dev2.device_id = "id2".to_string();

        db.insert_or_update(&dev1).unwrap();
        db.insert_or_update(&dev2).unwrap();

        let all = db.list_all().unwrap();
        assert_eq!(all.len(), 2);
    }

    #[test]
    fn test_is_paired() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();
        let dev = sample_device();
        db.insert_or_update(&dev).unwrap();
        assert!(db.is_paired("test-id"));
        assert!(!db.is_paired("other-id"));
    }

    #[test]
    fn test_should_auto_accept() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();
        let mut dev = sample_device();
        db.insert_or_update(&dev).unwrap();
        assert!(!db.should_auto_accept("test-id"));

        dev.auto_accept = "always".to_string();
        db.insert_or_update(&dev).unwrap();
        assert!(db.should_auto_accept("test-id"));
    }

    #[test]
    fn test_verify_identity_matched() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();
        db.insert_or_update(&sample_device()).unwrap();
        assert_eq!(
            db.verify_identity("test-id", &[1, 2, 3]).unwrap(),
            IdentityCheck::Matched
        );
    }

    #[test]
    fn test_verify_identity_changed() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();
        db.insert_or_update(&sample_device()).unwrap();
        assert_eq!(
            db.verify_identity("test-id", &[4, 5, 6]).unwrap(),
            IdentityCheck::Changed
        );
    }

    #[test]
    fn test_verify_identity_unknown() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();
        assert_eq!(
            db.verify_identity("test-id", &[1, 2, 3]).unwrap(),
            IdentityCheck::Unknown
        );
    }

    #[test]
    fn test_verify_identity_spki_and_legacy_cert_der_interop() {
        let dir = tempdir().unwrap();
        let db = PairedDeviceDb::open(&dir.path().join("db")).unwrap();

        let ident_a = nxfr_crypto::generate_identity().unwrap();
        let ident_b = nxfr_crypto::generate_identity().unwrap();

        let spki_a = nxfr_crypto::extract_spki(&ident_a.cert_der).unwrap();
        let spki_b = nxfr_crypto::extract_spki(&ident_b.cert_der).unwrap();

        // 1. Legacy daemon DB stores full cert DER in public_key_spki column.
        let legacy_dev = PairedDevice {
            device_id: "dev-legacy".to_string(),
            name: "Legacy Daemon".to_string(),
            public_key_spki: ident_a.cert_der.clone(),
            first_seen: 100,
            last_seen: 100,
            trust_level: "paired".to_string(),
            auto_accept: "prompt".to_string(),
        };
        db.insert_or_update(&legacy_dev).unwrap();

        // FFI client connects with extracted SPKI: MUST match legacy record.
        assert_eq!(
            db.verify_identity("dev-legacy", &spki_a).unwrap(),
            IdentityCheck::Matched
        );
        // Another daemon connects with cert DER: MUST match legacy record.
        assert_eq!(
            db.verify_identity("dev-legacy", &ident_a.cert_der).unwrap(),
            IdentityCheck::Matched
        );
        // Different SPKI: MUST report Changed.
        assert_eq!(
            db.verify_identity("dev-legacy", &spki_b).unwrap(),
            IdentityCheck::Changed
        );

        // 2. Modern DB stores extracted SPKI.
        let modern_dev = PairedDevice {
            device_id: "dev-modern".to_string(),
            name: "Modern Daemon / FFI".to_string(),
            public_key_spki: spki_a.clone(),
            first_seen: 200,
            last_seen: 200,
            trust_level: "paired".to_string(),
            auto_accept: "prompt".to_string(),
        };
        db.insert_or_update(&modern_dev).unwrap();

        // SPKI query: MUST match.
        assert_eq!(
            db.verify_identity("dev-modern", &spki_a).unwrap(),
            IdentityCheck::Matched
        );
        // Cert DER query: MUST match (normalizes to SPKI).
        assert_eq!(
            db.verify_identity("dev-modern", &ident_a.cert_der).unwrap(),
            IdentityCheck::Matched
        );
        // Wrong cert / SPKI: MUST report Changed.
        assert_eq!(
            db.verify_identity("dev-modern", &ident_b.cert_der).unwrap(),
            IdentityCheck::Changed
        );
    }
}
