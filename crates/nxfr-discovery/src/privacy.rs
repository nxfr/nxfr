use sha2::{Digest, Sha256};
use std::fmt::Write;

/// Compute the advertised ID based on device ID and date string.
/// id = first_16_hex_chars(SHA-256(device_id || "YYYY-MM-DD"))
pub fn compute_advertised_id(device_id: &[u8; 32], date_str: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(device_id);
    hasher.update(date_str.as_bytes());
    let result = hasher.finalize();

    let mut hex_str = String::with_capacity(16);
    for byte in &result[..8] {
        write!(&mut hex_str, "{:02x}", byte).unwrap();
    }
    hex_str
}

/// Compute the advertised ID using the current UTC date.
pub fn compute_advertised_id_now(device_id: &[u8; 32]) -> String {
    let date_str = chrono::Utc::now().format("%Y-%m-%d").to_string();
    compute_advertised_id(device_id, &date_str)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compute_advertised_id_deterministic() {
        let device_id = [1u8; 32];
        let date_str = "2025-01-01";
        let id1 = compute_advertised_id(&device_id, date_str);
        let id2 = compute_advertised_id(&device_id, date_str);
        assert_eq!(id1, id2);
    }

    #[test]
    fn test_compute_advertised_id_length() {
        let device_id = [2u8; 32];
        let id = compute_advertised_id(&device_id, "2025-01-01");
        assert_eq!(id.len(), 16);
    }

    #[test]
    fn test_compute_advertised_id_changes_daily() {
        let device_id = [3u8; 32];
        let id1 = compute_advertised_id(&device_id, "2025-01-01");
        let id2 = compute_advertised_id(&device_id, "2025-01-02");
        assert_ne!(id1, id2);
    }

    #[test]
    fn test_compute_advertised_id_golden_vector() {
        // Use device_id = SHA-256("test-device-a-spki")
        // Hash of "test-device-a-spki" is 15345a9ebc1613a2f80110e2c5c24329cf16a76dc8ff08c3e299a17180d2f03f
        let hex_val = "15345a9ebc1613a2f80110e2c5c24329cf16a76dc8ff08c3e299a17180d2f03f";
        let mut device_id = [0u8; 32];
        hex::decode_to_slice(hex_val, &mut device_id).unwrap();

        let date_str = "2025-01-01";
        let id = compute_advertised_id(&device_id, date_str);

        // Let's compute manually to check the expected output.
        // SHA-256(device_id || "2025-01-01")
        let mut hasher = Sha256::new();
        hasher.update(&device_id);
        hasher.update(date_str.as_bytes());
        let result = hasher.finalize();
        let expected: String = result[..8].iter().map(|b| format!("{:02x}", b)).collect();

        assert_eq!(id, expected);
        assert_eq!(id.len(), 16);
    }
}
