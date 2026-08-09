use crate::ipc_client::IpcClient;
use dialoguer::{theme::ColorfulTheme, Select};
use indicatif::{ProgressBar, ProgressStyle};
use serde_json::json;

/// Check if a string looks like a 64-char hex device_id.
fn is_hex_device_id(s: &str) -> bool {
    s.len() == 64 && s.chars().all(|c| c.is_ascii_hexdigit())
}

pub async fn handle(
    path: String,
    to: Option<String>,
    addr: Option<String>,
    retry_transfer_id: Option<String>,
) -> anyhow::Result<()> {
    let mut client = IpcClient::connect().await?;

    // Resolve target device.
    let (target_device_id, resolved_addr) = match to {
        Some(t) if is_hex_device_id(&t) => {
            // Already a device_id — use as-is.
            (t, None)
        }
        Some(name) => {
            // Resolve name → device_id by checking:
            // 1. Paired devices.
            // 2. Discovered devices.
            // 3. Daemon's own identity (self-send; mdns-sd suppresses self-discovery).
            let status_resp = client.send_request(&json!({ "cmd": "status" })).await?;
            let own_device_id = status_resp["device_id"].as_str().unwrap_or("").to_string();
            let own_name = status_resp["device_name"]
                .as_str()
                .unwrap_or("")
                .to_string();

            let devices_resp = client.send_request(&json!({ "cmd": "devices" })).await?;

            let mut resolved_id: Option<String> = None;
            let mut resolved_addr_inner: Option<String> = None;

            // Check paired devices.
            if let Some(paired) = devices_resp["paired"].as_array() {
                for p in paired {
                    if p["name"].as_str() == Some(&name) {
                        resolved_id = p["device_id"].as_str().map(|s| s.to_string());
                        break;
                    }
                }
            }

            // Check discovered devices.
            if resolved_id.is_none() {
                if let Some(discovered) = devices_resp["discovered"].as_array() {
                    for d in discovered {
                        if d["name"].as_str() == Some(&name) {
                            resolved_id = d["device_id_hint"].as_str().map(|s| s.to_string());
                            if let Some(addrs) = d["addresses"].as_array() {
                                if let Some(first_addr) = addrs.first().and_then(|a| a.as_str()) {
                                    let port = d["port"].as_u64().unwrap_or(17394);
                                    resolved_addr_inner = Some(format!("{}:{}", first_addr, port));
                                }
                            }
                            break;
                        }
                    }
                }
            }

            // Check self (mdns-sd suppresses self-discovery on Linux).
            // This catches both: name matches daemon config AND paired device
            // that happens to be self.
            if let Some(ref id) = resolved_id {
                if id == &own_device_id && resolved_addr_inner.is_none() {
                    resolved_addr_inner = Some("127.0.0.1:17394".to_string());
                }
            }
            if resolved_id.is_none() && !own_device_id.is_empty() && own_name == name {
                resolved_id = Some(own_device_id.clone());
                resolved_addr_inner = Some("127.0.0.1:17394".to_string());
            }

            match resolved_id {
                Some(id) => (id, resolved_addr_inner),
                None => {
                    anyhow::bail!(
                        "device '{}' not discovered or paired; use its device_id or check `nxfr devices`.",
                        name
                    );
                }
            }
        }
        None => {
            // Interactive picker — only when --to is not specified.
            let req = json!({ "cmd": "devices" });
            let resp = client.send_request(&req).await?;

            let mut options: Vec<(String, String)> = vec![];
            if let Some(paired) = resp["paired"].as_array() {
                for p in paired {
                    if let (Some(name), Some(id)) = (p["name"].as_str(), p["device_id"].as_str()) {
                        options.push((name.to_string(), id.to_string()));
                    }
                }
            }
            if let Some(discovered) = resp["discovered"].as_array() {
                for d in discovered {
                    if let (Some(name), Some(hint)) =
                        (d["name"].as_str(), d["device_id_hint"].as_str())
                    {
                        if !options.iter().any(|(_, id)| id == hint) {
                            options.push((name.to_string(), hint.to_string()));
                        }
                    }
                }
            }

            if options.is_empty() {
                anyhow::bail!(
                    "No devices discovered. Use --to <name> --addr <ip:port> or wait for discovery."
                );
            }

            let display: Vec<String> = options.iter().map(|(n, _)| n.clone()).collect();
            let selection = Select::with_theme(&ColorfulTheme::default())
                .with_prompt("Select target device")
                .default(0)
                .items(&display)
                .interact()?;

            (options[selection].1.clone(), None)
        }
    };

    // Determine address: explicit --addr overrides resolved_addr.
    let final_addr = addr.or(resolved_addr);

    // Build request with correct daemon JSON keys.
    let mut req = json!({
        "cmd": "send",
        "path": path,
        "target_device_id": target_device_id,
    });
    if let Some(a) = &final_addr {
        req.as_object_mut()
            .unwrap()
            .insert("target_addr".to_string(), json!(a));
    }
    if let Some(id) = retry_transfer_id {
        req.as_object_mut()
            .unwrap()
            .insert("retry_transfer_id".to_string(), json!(id));
    }

    // Send and check initial response.
    let resp = client.send_request(&req).await?;
    if resp["ok"].as_bool() != Some(true) {
        let error = resp["error"].as_str().unwrap_or("daemon returned an error");
        anyhow::bail!("Send failed: {error}");
    }
    let msg = resp["message"].as_str().unwrap_or("Transfer queued");
    println!("{msg}");

    // Stream progress events.
    let pb = ProgressBar::new(100);
    pb.set_style(
        ProgressStyle::default_bar()
            .template(
                "{spinner:.green} [{elapsed_precise}] [{bar:40.cyan/blue}] {bytes}/{total_bytes} ({eta})",
            )?
            .progress_chars("#>-"),
    );

    while let Some(event) = client.read_event().await? {
        match event["type"].as_str() {
            Some("progress") => {
                let bytes = event["bytes_sent"].as_u64().unwrap_or(0);
                let total = event["total_bytes"].as_u64().unwrap_or(100);
                pb.set_length(total);
                pb.set_position(bytes);
            }
            Some("transfer_complete") => {
                pb.finish_with_message("Transfer complete");
                let tid = event["transfer_id"].as_str().unwrap_or("?");
                println!("Transfer complete: {tid}");
                break;
            }
            Some("error") => {
                pb.finish_and_clear();
                let err_msg = event["message"].as_str().unwrap_or("Unknown error");
                anyhow::bail!("Transfer failed: {err_msg}");
            }
            Some("response") if event["ok"].as_bool() != Some(true) => {
                let error = event["error"].as_str().unwrap_or("Unknown error");
                anyhow::bail!("Transfer failed: {error}");
            }
            _ => {}
        }
    }

    Ok(())
}
