use crate::db::Result;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::path::Path;

const MAX_HISTORY_ROWS: usize = 200;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct TransferHistoryRecord {
    pub id: i64,
    pub ts_ms: i64,
    pub direction: String, // 'send' | 'recv'
    pub peer_name: String,
    pub peer_id: String,
    pub file_count: u32,
    pub total_bytes: u64,
    pub status: String, // 'complete' | 'rejected' | 'failed' | 'cancelled'
    pub file_paths: Vec<String>,
}

pub struct HistoryDb {
    conn: Connection,
}

impl HistoryDb {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let conn = Connection::open(path)?;
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA busy_timeout = 3000;",
        )?;
        Self::init_schema(&conn)?;
        Ok(Self { conn })
    }

    fn init_schema(conn: &Connection) -> Result<()> {
        conn.execute(
            "CREATE TABLE IF NOT EXISTS transfer_history (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms       INTEGER NOT NULL,
                direction   TEXT NOT NULL,
                peer_name   TEXT NOT NULL,
                peer_id     TEXT NOT NULL,
                file_count  INTEGER NOT NULL,
                total_bytes INTEGER NOT NULL,
                status      TEXT NOT NULL,
                file_paths  TEXT NOT NULL
            )",
            [],
        )?;
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_transfer_history_ts ON transfer_history (ts_ms DESC)",
            [],
        )?;
        Ok(())
    }

    pub fn add(&self, record: &TransferHistoryRecord) -> Result<i64> {
        let paths_json =
            serde_json::to_string(&record.file_paths).unwrap_or_else(|_| "[]".to_string());
        let ts = if record.ts_ms > 0 {
            record.ts_ms
        } else {
            chrono::Utc::now().timestamp_millis()
        };

        self.conn.execute(
            "INSERT INTO transfer_history (
                ts_ms, direction, peer_name, peer_id, file_count, total_bytes, status, file_paths
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                ts,
                record.direction,
                record.peer_name,
                record.peer_id,
                record.file_count,
                record.total_bytes,
                record.status,
                paths_json
            ],
        )?;

        let id = self.conn.last_insert_rowid();

        // Prune to keep max 200 rows
        self.conn.execute(
            "DELETE FROM transfer_history WHERE id NOT IN (
                SELECT id FROM transfer_history ORDER BY ts_ms DESC, id DESC LIMIT ?1
            )",
            params![MAX_HISTORY_ROWS],
        )?;

        Ok(id)
    }

    pub fn list(&self, limit: usize) -> Result<Vec<TransferHistoryRecord>> {
        let fetch_limit = if limit == 0 { MAX_HISTORY_ROWS } else { limit };
        let mut stmt = self.conn.prepare(
            "SELECT id, ts_ms, direction, peer_name, peer_id, file_count, total_bytes, status, file_paths
             FROM transfer_history
             ORDER BY ts_ms DESC, id DESC
             LIMIT ?1"
        )?;

        let rows = stmt.query_map(params![fetch_limit], |row| {
            let paths_json: String = row.get(8)?;
            let file_paths: Vec<String> = serde_json::from_str(&paths_json).unwrap_or_default();
            let file_count_i: i64 = row.get(5)?;
            let total_bytes_i: i64 = row.get(6)?;

            Ok(TransferHistoryRecord {
                id: row.get(0)?,
                ts_ms: row.get(1)?,
                direction: row.get(2)?,
                peer_name: row.get(3)?,
                peer_id: row.get(4)?,
                file_count: file_count_i as u32,
                total_bytes: total_bytes_i as u64,
                status: row.get(7)?,
                file_paths,
            })
        })?;

        let mut list = Vec::new();
        for r in rows {
            list.push(r?);
        }
        Ok(list)
    }

    pub fn clear(&self) -> Result<()> {
        self.conn.execute("DELETE FROM transfer_history", [])?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_history_crud_ordering_and_pruning() {
        let temp_dir = tempfile::tempdir().unwrap();
        let db_path = temp_dir.path().join("history.db");
        let db = HistoryDb::open(&db_path).unwrap();

        // 1. Initially empty
        let initial = db.list(0).unwrap();
        assert!(initial.is_empty());

        // 2. Add records
        let rec1 = TransferHistoryRecord {
            id: 0,
            ts_ms: 1000,
            direction: "send".to_string(),
            peer_name: "Pixel 8".to_string(),
            peer_id: "p8_id".to_string(),
            file_count: 1,
            total_bytes: 1024,
            status: "complete".to_string(),
            file_paths: vec!["/tmp/photo.jpg".to_string()],
        };
        let rec2 = TransferHistoryRecord {
            id: 0,
            ts_ms: 2000,
            direction: "recv".to_string(),
            peer_name: "MacBook Pro".to_string(),
            peer_id: "mbp_id".to_string(),
            file_count: 2,
            total_bytes: 2048,
            status: "failed".to_string(),
            file_paths: vec!["file1.pdf".to_string(), "file2.pdf".to_string()],
        };

        db.add(&rec1).unwrap();
        db.add(&rec2).unwrap();

        // 3. Verify newest first ordering
        let list = db.list(0).unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].peer_name, "MacBook Pro");
        assert_eq!(list[1].peer_name, "Pixel 8");

        // 4. Test pruning above 200 rows
        for i in 0..210 {
            let r = TransferHistoryRecord {
                id: 0,
                ts_ms: 3000 + i,
                direction: "send".to_string(),
                peer_name: format!("Peer_{i}"),
                peer_id: format!("id_{i}"),
                file_count: 1,
                total_bytes: 500,
                status: "complete".to_string(),
                file_paths: vec![],
            };
            db.add(&r).unwrap();
        }

        let pruned_list = db.list(0).unwrap();
        assert_eq!(pruned_list.len(), 200);
        // Newest should be i=209 (ts_ms = 3209)
        assert_eq!(pruned_list[0].peer_name, "Peer_209");

        // 5. Test clear
        db.clear().unwrap();
        assert!(db.list(0).unwrap().is_empty());
    }
}
