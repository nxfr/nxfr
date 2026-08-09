/// Derive the 6-digit SAS value from device IDs and TLS exporter bytes.
///
/// Per §9.2.3:
/// 1. context = sort(device_id_a, device_id_b) lexicographically (yields 64 bytes)
/// 2. sas_bytes are provided by the caller (from TLS-Exporter, 4 bytes)
/// 3. sas_value = BigEndian_u32(sas_bytes) mod 1000000
/// 4. Format as zero-padded 6-digit string
pub fn derive_sas(
    device_id_a: &[u8; 32],
    device_id_b: &[u8; 32],
    exporter_bytes: &[u8; 4],
) -> (String, [u8; 64]) {
    let mut context = [0u8; 64];
    if device_id_a < device_id_b {
        context[0..32].copy_from_slice(device_id_a);
        context[32..64].copy_from_slice(device_id_b);
    } else {
        context[0..32].copy_from_slice(device_id_b);
        context[32..64].copy_from_slice(device_id_a);
    }

    let u32_val = u32::from_be_bytes(*exporter_bytes);
    let value = u32_val % 1_000_000;

    (format!("{:06}", value), context)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_derive_sas() {
        let mut id_a = [0u8; 32];
        id_a[0] = 1;
        let mut id_b = [0u8; 32];
        id_b[0] = 2;
        let exporter = [0x01, 0x02, 0x03, 0x04];

        let (sas, context) = derive_sas(&id_a, &id_b, &exporter);
        assert_eq!(context[0..32], id_a);
        assert_eq!(context[32..64], id_b);
        assert_eq!(
            sas,
            format!("{:06}", u32::from_be_bytes(exporter) % 1_000_000)
        );

        let (sas_b, context_b) = derive_sas(&id_b, &id_a, &exporter);
        assert_eq!(sas, sas_b);
        assert_eq!(context, context_b);
    }

    #[test]
    fn test_edge_cases() {
        let id_a = [0u8; 32];
        let id_b = [1u8; 32];

        // exporter = [0,0,0,0]
        let (sas, _) = derive_sas(&id_a, &id_b, &[0, 0, 0, 0]);
        assert_eq!(sas, "000000");

        // exporter = [0xFF, 0xFF, 0xFF, 0xFF] -> 4294967295 % 1000000 = 967295
        let (sas, _) = derive_sas(&id_a, &id_b, &[0xFF, 0xFF, 0xFF, 0xFF]);
        assert_eq!(sas, "967295");

        // exporter = [0x00, 0x0F, 0x42, 0x40] -> 1000000 % 1000000 = 0
        let (sas, _) = derive_sas(&id_a, &id_b, &[0x00, 0x0F, 0x42, 0x40]);
        assert_eq!(sas, "000000");
    }
}
