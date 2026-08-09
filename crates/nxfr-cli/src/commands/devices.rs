use crate::ipc_client::IpcClient;
use serde_json::json;

pub async fn handle() -> anyhow::Result<()> {
    let mut client = IpcClient::connect().await?;
    let req = json!({ "cmd": "devices" });
    let resp = client.send_request(&req).await?;

    // ── Paired Devices ──────────────────────────────────────────
    println!("Paired Devices:");
    let paired = resp["paired"].as_array();
    if paired.map_or(true, |a| a.is_empty()) {
        println!("  (none)");
    } else {
        println!(
            "  {:<20} {:<10} {:<14} {}",
            "NAME", "TRUST", "AUTO-ACCEPT", "DEVICE ID"
        );
        for p in paired.unwrap() {
            let name = p["name"].as_str().unwrap_or("?");
            let trust = p["trust_level"].as_str().unwrap_or("?");
            let auto_accept = p["auto_accept"].as_str().unwrap_or("?");
            let device_id = p["device_id"].as_str().unwrap_or("?");
            let short_id = truncate_device_id(device_id);
            println!(
                "  {:<20} {:<10} {:<14} {}",
                name, trust, auto_accept, short_id
            );
        }
    }

    // ── Discovered Devices ──────────────────────────────────────
    println!("\nDiscovered Devices:");
    let discovered = resp["discovered"].as_array();
    if discovered.map_or(true, |a| a.is_empty()) {
        println!("  (none)");
    } else {
        println!("  {:<20} {:<24} {}", "NAME", "ADDRESS", "PORT");
        for d in discovered.unwrap() {
            let name = d["name"].as_str().unwrap_or("?");
            let addrs: Vec<&str> = d["addresses"]
                .as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str()).collect())
                .unwrap_or_default();
            let addr_str = if addrs.is_empty() {
                "?".to_string()
            } else {
                addrs.join(", ")
            };
            let port = d["port"].as_u64().unwrap_or(0);
            println!("  {:<20} {:<24} {}", name, addr_str, port);
        }
    }

    Ok(())
}

/// Truncate a hex device ID to `first8…last6` for readability.
/// IDs shorter than 16 chars are returned as-is.
pub fn truncate_device_id(id: &str) -> String {
    if id.len() > 16 {
        format!("{}…{}", &id[..8], &id[id.len() - 6..])
    } else {
        id.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncate_long_id() {
        let id = "7ec92f1f0a3b4c5d6e7f8a9b0c1d2ae69c";
        let short = truncate_device_id(id);
        assert_eq!(short, "7ec92f1f…2ae69c");
        // Must start with first 8 chars
        assert!(short.starts_with("7ec92f1f"));
        // Must end with last 6 chars
        assert!(short.ends_with("2ae69c"));
    }

    #[test]
    fn truncate_exact_boundary() {
        // 17 chars — just over threshold, should truncate
        let id = "abcdefgh123456789";
        let short = truncate_device_id(id);
        assert_eq!(short, "abcdefgh…456789");
    }

    #[test]
    fn truncate_short_id_unchanged() {
        let id = "abcdef1234567890"; // exactly 16 chars
        assert_eq!(truncate_device_id(id), id);
    }

    #[test]
    fn truncate_very_short_id() {
        let id = "abc";
        assert_eq!(truncate_device_id(id), "abc");
    }

    #[test]
    fn truncate_empty_id() {
        assert_eq!(truncate_device_id(""), "");
    }

    #[test]
    fn truncate_standard_sha256_hex() {
        // 64-char SHA-256 hex — the most common case
        let id = "7ec92f1f0a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6ae69c";
        let short = truncate_device_id(id);
        assert_eq!(short, "7ec92f1f…6ae69c");
        // Verify the truncation preserves first 8 and last 6
        assert_eq!(&short[..8], "7ec92f1f");
        assert!(short.ends_with("6ae69c"));
    }
}
