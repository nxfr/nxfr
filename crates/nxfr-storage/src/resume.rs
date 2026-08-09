use chrono::Utc;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::File;
use std::io::Write;
use std::path::PathBuf;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum ResumeError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("JSON error: {0}")]
    Json(#[from] serde_json::Error),
}
pub type Result<T> = std::result::Result<T, ResumeError>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResumeState {
    pub transfer_id: String,
    pub peer_device_id: String,
    pub display_name: String,
    pub manifest: Vec<ResumeManifestEntry>,
    pub files: HashMap<u32, ResumeFileState>,
    pub created_at: i64,
    pub expires_at: i64,
    pub version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResumeManifestEntry {
    pub file_id: u32,
    pub relative_path: String,
    pub size: u64,
    pub sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResumeFileState {
    pub received_bytes: u64,
    pub received_ranges: Vec<(u64, u64)>,
    pub partial_sha256: Option<String>,
    pub dest_path: String,
}

pub struct ResumeJournal {
    base_dir: PathBuf,
}

impl ResumeJournal {
    pub fn new(base_dir: PathBuf) -> Self {
        let _ = std::fs::create_dir_all(&base_dir);
        Self { base_dir }
    }

    pub fn default_dir() -> PathBuf {
        let mut path = dirs::data_local_dir().unwrap_or_else(|| PathBuf::from("."));
        path.push("nxfr");
        path.push("resume");
        path
    }

    pub fn save(&self, state: &ResumeState) -> Result<()> {
        let mut final_path = self.base_dir.clone();
        final_path.push(format!("{}.json", state.transfer_id));

        let mut tmp_path = final_path.clone();
        tmp_path.set_extension("json.tmp");

        {
            let mut file = File::create(&tmp_path)?;
            let json = serde_json::to_vec(state)?;
            file.write_all(&json)?;
            file.sync_all()?;
        }
        std::fs::rename(tmp_path, final_path)?;
        Ok(())
    }

    pub fn load(&self, transfer_id: &str) -> Result<Option<ResumeState>> {
        let mut path = self.base_dir.clone();
        path.push(format!("{}.json", transfer_id));
        if !path.exists() {
            return Ok(None);
        }
        let content = std::fs::read_to_string(&path)?;
        let state: ResumeState = serde_json::from_str(&content)?;
        Ok(Some(state))
    }

    pub fn delete(&self, transfer_id: &str) -> Result<()> {
        let mut path = self.base_dir.clone();
        path.push(format!("{}.json", transfer_id));
        if path.exists() {
            std::fs::remove_file(path)?;
        }
        Ok(())
    }

    pub fn list_active(&self) -> Result<Vec<String>> {
        let mut ids = Vec::new();
        if !self.base_dir.exists() {
            return Ok(ids);
        }
        for entry in std::fs::read_dir(&self.base_dir)? {
            let entry = entry?;
            let path = entry.path();
            if path.is_file() && path.extension().map(|e| e == "json").unwrap_or(false) {
                if let Some(stem) = path.file_stem().and_then(|s| s.to_str()) {
                    ids.push(stem.to_string());
                }
            }
        }
        Ok(ids)
    }

    pub fn gc_expired(&self) -> Result<usize> {
        let mut count = 0;
        let now = Utc::now().timestamp();
        for id in self.list_active()? {
            if let Ok(Some(state)) = self.load(&id) {
                if state.expires_at < now {
                    self.delete(&id)?;
                    count += 1;
                }
            }
        }
        Ok(count)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn sample_state() -> ResumeState {
        ResumeState {
            transfer_id: "test1234".to_string(),
            peer_device_id: "peer1".to_string(),
            display_name: "Test Transfer".to_string(),
            manifest: vec![],
            files: HashMap::new(),
            created_at: 1000,
            expires_at: 2000,
            version: 1,
        }
    }

    #[test]
    fn test_save_and_load() {
        let dir = tempdir().unwrap();
        let journal = ResumeJournal::new(dir.path().to_path_buf());
        let state = sample_state();
        journal.save(&state).unwrap();

        let loaded = journal.load("test1234").unwrap().unwrap();
        assert_eq!(loaded.transfer_id, "test1234");
        assert_eq!(loaded.expires_at, 2000);
    }

    #[test]
    fn test_delete() {
        let dir = tempdir().unwrap();
        let journal = ResumeJournal::new(dir.path().to_path_buf());
        let state = sample_state();
        journal.save(&state).unwrap();
        journal.delete("test1234").unwrap();
        assert!(journal.load("test1234").unwrap().is_none());
    }

    #[test]
    fn test_list_active() {
        let dir = tempdir().unwrap();
        let journal = ResumeJournal::new(dir.path().to_path_buf());
        let mut state1 = sample_state();
        state1.transfer_id = "id1".to_string();
        let mut state2 = sample_state();
        state2.transfer_id = "id2".to_string();

        journal.save(&state1).unwrap();
        journal.save(&state2).unwrap();

        let active = journal.list_active().unwrap();
        assert_eq!(active.len(), 2);
        assert!(active.contains(&"id1".to_string()));
        assert!(active.contains(&"id2".to_string()));
    }

    #[test]
    fn test_gc_expired() {
        let dir = tempdir().unwrap();
        let journal = ResumeJournal::new(dir.path().to_path_buf());
        let mut state = sample_state();
        state.expires_at = Utc::now().timestamp() - 100; // expired
        journal.save(&state).unwrap();

        let count = journal.gc_expired().unwrap();
        assert_eq!(count, 1);
        assert!(journal.load(&state.transfer_id).unwrap().is_none());
    }

    #[test]
    fn test_save_is_atomic() {
        let dir = tempdir().unwrap();
        let journal = ResumeJournal::new(dir.path().to_path_buf());
        let state = sample_state();
        journal.save(&state).unwrap();

        let tmp_path = dir.path().join(format!("{}.json.tmp", state.transfer_id));
        assert!(!tmp_path.exists());
    }
}
