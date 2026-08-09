use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum ConfigError {
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    #[error("TOML decode error: {0}")]
    TomlDe(#[from] toml::de::Error),
    #[error("TOML encode error: {0}")]
    TomlSer(#[from] toml::ser::Error),
}
pub type Result<T> = std::result::Result<T, ConfigError>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NxfrConfig {
    pub device_name: String,
    pub receive_dir: PathBuf,
    pub receiving_enabled: bool,
}

impl Default for NxfrConfig {
    fn default() -> Self {
        let hostname = std::fs::read_to_string("/etc/hostname")
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|_| "NXFR-Device".to_string());

        let mut recv_dir = dirs::download_dir().unwrap_or_else(|| PathBuf::from("."));
        recv_dir.push("NXFR");

        Self {
            device_name: hostname,
            receive_dir: recv_dir,
            receiving_enabled: false,
        }
    }
}

impl NxfrConfig {
    pub fn default_config_path() -> PathBuf {
        let mut path = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
        path.push("nxfr");
        path.push("config.toml");
        path
    }

    pub fn load() -> Result<Self> {
        Self::load_from(&Self::default_config_path())
    }

    pub fn load_from(path: &Path) -> Result<Self> {
        if !path.exists() {
            let default_cfg = Self::default();
            default_cfg.save_to(path)?;
            return Ok(default_cfg);
        }
        let content = std::fs::read_to_string(path)?;
        let cfg: NxfrConfig = toml::from_str(&content)?;
        Ok(cfg)
    }

    pub fn save(&self) -> Result<()> {
        self.save_to(&Self::default_config_path())
    }

    pub fn save_to(&self, path: &Path) -> Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let content = toml::to_string(self)?;
        std::fs::write(path, content)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_load_creates_default() {
        let dir = tempdir().unwrap();
        let cfg_path = dir.path().join("config.toml");
        let cfg = NxfrConfig::load_from(&cfg_path).unwrap();
        assert!(cfg_path.exists());
        assert!(!cfg.receiving_enabled);
    }

    #[test]
    fn test_save_and_reload() {
        let dir = tempdir().unwrap();
        let cfg_path = dir.path().join("config.toml");
        let cfg = NxfrConfig {
            device_name: "Test Name".to_string(),
            receiving_enabled: true,
            ..Default::default()
        };
        cfg.save_to(&cfg_path).unwrap();

        let loaded = NxfrConfig::load_from(&cfg_path).unwrap();
        assert_eq!(loaded.device_name, "Test Name");
        assert!(loaded.receiving_enabled);
    }

    #[test]
    fn test_default_values() {
        let cfg = NxfrConfig::default();
        assert!(!cfg.receiving_enabled);
        assert!(!cfg.device_name.is_empty());
    }
}
